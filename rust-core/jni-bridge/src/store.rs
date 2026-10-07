use anyhow::{bail, ensure, Context, Result};
use memmap2::Mmap;
use rpf_archive::{GtaKeys, RpfArchive, RpfEntryKind, RpfVersion, RSC7_MAGIC};
use serde_json::{json, Value};
use std::{
    collections::{HashMap, HashSet},
    fs::File,
    io::{Read, Seek, Write},
    ops::Range,
    os::{
        fd::{AsRawFd, BorrowedFd, RawFd},
        unix::fs::{FileExt, MetadataExt},
    },
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, AtomicI64, Ordering},
        Arc, Mutex, OnceLock, RwLock,
    },
};

#[path = "audit.rs"]
mod audit;
#[path = "editor.rs"]
mod editor;
pub use audit::{scan, scan_progress};
pub use editor::{attach_nested, edit, edit_named_file, save_fd, stage_fd};

pub const PREVIEW_LIMIT: usize = 256 * 1024 * 1024;
static ARCHIVES: OnceLock<Mutex<HashMap<i64, Arc<Archive>>>> = OnceLock::new();
static DOCUMENTS: OnceLock<Mutex<HashMap<i64, Arc<File>>>> = OnceLock::new();
static KEYS: OnceLock<RwLock<Option<GtaKeys>>> = OnceLock::new();
static NEXT: AtomicI64 = AtomicI64::new(1);
static OPERATIONS: OnceLock<Mutex<HashMap<i64, Arc<AtomicBool>>>> = OnceLock::new();

pub fn begin_operation() -> i64 {
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    OPERATIONS
        .get_or_init(Default::default)
        .lock()
        .unwrap()
        .insert(id, Arc::new(AtomicBool::new(false)));
    id
}
pub fn finish_operation(id: i64) {
    OPERATIONS
        .get_or_init(Default::default)
        .lock()
        .unwrap()
        .remove(&id);
}

/// Check identity before any truncation, including unrelated open workspaces.
fn checked_output(fd: RawFd) -> Result<File> {
    let output = duplicate(fd)?;
    let target = output.metadata()?;
    let check = |source: &File| -> Result<()> {
        let source = source.metadata()?;
        ensure!(
            source.dev() != target.dev() || source.ino() != target.ino(),
            "Choose a new output document; this file is already open"
        );
        Ok(())
    };
    for archive in archives()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .values()
    {
        if let Backing::Mapped(_, file) = &*archive.data {
            check(file)?;
        }
    }
    for file in documents()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .values()
    {
        check(file)?;
    }
    Ok(output)
}
fn archives() -> &'static Mutex<HashMap<i64, Arc<Archive>>> {
    ARCHIVES.get_or_init(Default::default)
}
fn keys() -> &'static RwLock<Option<GtaKeys>> {
    KEYS.get_or_init(Default::default)
}
fn key_guard() -> std::sync::RwLockReadGuard<'static, Option<GtaKeys>> {
    // AES/NG borrow immutable user keys and use per-call buffers.
    // Keep key replacement exclusive without serializing resource I/O/decoding.
    keys().read().unwrap_or_else(|e| e.into_inner())
}
fn key_write_guard() -> std::sync::RwLockWriteGuard<'static, Option<GtaKeys>> {
    keys().write().unwrap_or_else(|e| e.into_inner())
}
fn documents() -> &'static Mutex<HashMap<i64, Arc<File>>> {
    DOCUMENTS.get_or_init(Default::default)
}
enum Backing {
    Mapped(Mmap, Arc<File>),
    Decoded(Vec<u8>),
}
impl Backing {
    fn bytes(&self) -> &[u8] {
        match self {
            Self::Mapped(m, _) => m,
            Self::Decoded(v) => v,
        }
    }
}

#[derive(Clone)]
pub struct IndexedEntry {
    pub name: String,
    pub path: String,
    parent: String,
    index: usize,
    directory: bool,
    size: u64,
    replacement: Option<Replacement>,
}
#[derive(Clone)]
enum Replacement {
    File(Arc<File>),
    Nested(Arc<Archive>),
}
pub struct Archive {
    pub parsed: RpfArchive,
    data: Arc<Backing>,
    range: Range<usize>,
    pub entries: Vec<IndexedEntry>,
    edits: Mutex<editor::EditHistory>,
    cancelled: AtomicBool,
    scan_state: Mutex<(u64, String)>,
}
impl Archive {
    fn bytes(&self) -> &[u8] {
        &self.data.bytes()[self.range.clone()]
    }
}

pub fn open(path: &str, name: &str) -> Result<i64> {
    open_file(File::open(path)?, name)
}
fn duplicate(fd: RawFd) -> Result<File> {
    ensure!(fd >= 0, "Invalid document descriptor");
    // The caller owns the descriptor throughout this synchronous JNI call.
    let file = File::from(unsafe { BorrowedFd::borrow_raw(fd) }.try_clone_to_owned()?);
    ensure!(
        file.metadata()?.is_file(),
        "Document provider is not seekable; select a local file"
    );
    Ok(file)
}
pub fn open_fd(fd: RawFd, name: &str) -> Result<i64> {
    open_file(duplicate(fd)?, name)
}
pub fn open_document(fd: RawFd) -> Result<i64> {
    let file = duplicate(fd)?;
    let id = -NEXT.fetch_add(1, Ordering::Relaxed);
    documents().lock().unwrap().insert(id, Arc::new(file));
    Ok(id)
}
fn open_file(file: File, name: &str) -> Result<i64> {
    ensure!(file.metadata()?.len() >= 16, "Invalid archive header");
    // Read-only mapping of the SAF descriptor; no private on-disk copy is made.
    let mapped =
        unsafe { Mmap::map(&file) }.context("Document provider does not support direct mapping")?;
    let length = mapped.len();
    insert_archive(
        Arc::new(Backing::Mapped(mapped, Arc::new(file))),
        0..length,
        name,
    )
}
fn insert_archive(data: Arc<Backing>, range: Range<usize>, name: &str) -> Result<i64> {
    let bytes = data
        .bytes()
        .get(range.clone())
        .context("Archive range outside document")?;
    ensure!(bytes.len() >= 16, "Invalid archive header");
    if u32::from_le_bytes(bytes[..4].try_into()?) == rpf_archive::RPF7_MAGIC {
        ensure!(
            u32::from_le_bytes(bytes[4..8].try_into()?) <= 1_000_000,
            "Archive entry limit exceeded"
        );
    }
    let parsed = RpfArchive::parse(bytes, name, key_guard().as_ref())?;
    let entries = index(&parsed)?;
    let edits = Mutex::new(editor::EditHistory::new(entries.clone()));
    let handle = NEXT.fetch_add(1, Ordering::Relaxed);
    archives().lock().unwrap().insert(
        handle,
        Arc::new(Archive {
            parsed,
            data,
            range,
            entries,
            edits,
            cancelled: AtomicBool::new(false),
            scan_state: Mutex::new((0, String::new())),
        }),
    );
    Ok(handle)
}

fn index(archive: &RpfArchive) -> Result<Vec<IndexedEntry>> {
    let all = &archive.entries;
    ensure!(all.len() <= 1_000_000, "Archive has too many entries");
    let mut output = Vec::with_capacity(all.len());
    let mut visited = HashSet::new();
    let mut names = HashSet::new();
    let mut pending = Vec::new();
    if let Some(entry) = all.first() {
        if let RpfEntryKind::Directory {
            entries_index,
            entries_count,
        } = entry.kind
        {
            visited.insert(0usize);
            pending.push((
                entries_index as usize,
                entries_count as usize,
                String::new(),
                0,
            ));
        } else {
            pending.push((0, all.len(), String::new(), 0));
        }
    }
    while let Some((start, count, parent, depth)) = pending.pop() {
        ensure!(
            depth <= 128 && start.checked_add(count).is_some_and(|end| end <= all.len()),
            "Invalid directory range or depth"
        );
        for (i, entry) in all.iter().enumerate().skip(start).take(count) {
            ensure!(visited.insert(i), "Cyclic or overlapping archive directory");
            ensure!(
                !entry.name.is_empty()
                    && entry.name != "."
                    && entry.name != ".."
                    && !entry.name.contains(['/', '\\', '\0']),
                "Invalid entry name"
            );
            let path = if parent.is_empty() {
                entry.name.clone()
            } else {
                format!("{parent}/{}", entry.name)
            };
            ensure!(
                names.insert(path.to_lowercase()),
                "Ambiguous duplicate archive path"
            );
            let (directory, size) = match entry.kind {
                RpfEntryKind::Directory {
                    entries_index,
                    entries_count,
                } => {
                    pending.push((
                        entries_index as usize,
                        entries_count as usize,
                        path.clone(),
                        depth + 1,
                    ));
                    (true, 0)
                }
                RpfEntryKind::BinaryFile {
                    file_size,
                    uncompressed_size,
                    ..
                } => (false, uncompressed_size.max(file_size) as u64),
                RpfEntryKind::ResourceFile { file_size, .. } => (false, file_size as u64),
            };
            output.push(IndexedEntry {
                name: entry.name.clone(),
                path,
                parent: parent.clone(),
                index: i,
                directory,
                size,
                replacement: None,
            });
        }
    }
    Ok(output)
}

pub fn get(handle: i64) -> Result<Arc<Archive>> {
    archives()
        .lock()
        .unwrap()
        .get(&handle)
        .cloned()
        .context("Archive is closed")
}
pub fn close(handle: i64) {
    if handle < 0 {
        documents().lock().unwrap().remove(&handle);
        return;
    }
    archives().lock().unwrap().remove(&handle);
}
pub fn info(handle: i64) -> Result<Value> {
    let a = get(handle)?;
    let state = a.edits.lock().unwrap_or_else(|e| e.into_inner());
    let entries = &state.current.entries;
    Ok(
        json!({"name":a.parsed.name, "entries":entries.len(), "files":entries.iter().filter(|e| !e.directory).count(),
        "directories":entries.iter().filter(|e| e.directory).count(), "encryption":format!("{:?}",a.parsed.encryption), "total_size":a.bytes().len(),
        "editable":a.parsed.version == RpfVersion::V7, "dirty":state.current.revision != state.saved,
        "modified":state.current.revision != 0, "undo":!state.past.is_empty(), "redo":!state.future.is_empty()}),
    )
}
pub fn list(handle: i64, path: &str) -> Result<Value> {
    let a = get(handle)?;
    let entries = editor::snapshot(&a);
    let canonical = path.replace('\\', "/").trim_matches('/').to_lowercase();
    ensure!(
        canonical.is_empty()
            || entries
                .iter()
                .any(|e| e.directory && e.path.to_lowercase() == canonical),
        "Directory not found"
    );
    Ok(Value::Array(
        entries
            .iter()
            .filter(|e| e.parent.to_lowercase() == canonical)
            .map(|e| json!({"name":e.name,"path":e.path,"size":e.size,"is_dir":e.directory}))
            .collect(),
    ))
}
#[cfg(feature = "conversion")]
#[path = "conversion.rs"]
pub mod conversion;

fn lookup(a: &Archive, path: &str) -> Result<IndexedEntry> {
    let canonical = path.replace('\\', "/").to_lowercase();
    editor::snapshot(a)
        .into_iter()
        .find(|e| !e.directory && e.path.to_lowercase() == canonical)
        .context("File not found at the complete archive path")
}
pub fn read(handle: i64, path: &str) -> Result<Vec<u8>> {
    if handle < 0 {
        let file = documents()
            .lock()
            .unwrap()
            .get(&handle)
            .cloned()
            .context("Document is closed")?;
        let size = file.metadata()?.len();
        ensure!(
            size <= PREVIEW_LIMIT as u64,
            "File exceeds the 256 MiB preview limit"
        );
        let mut bytes = vec![0; size as usize];
        file.read_exact_at(&mut bytes, 0)?;
        return Ok(bytes);
    }
    if handle == 0 {
        let mut file = File::open(path)?.take(PREVIEW_LIMIT as u64 + 1);
        let mut data = Vec::new();
        file.read_to_end(&mut data)?;
        ensure!(
            data.len() <= PREVIEW_LIMIT,
            "File exceeds the 256 MiB preview limit"
        );
        return Ok(data);
    }
    let a = get(handle)?;
    let e = lookup(&a, path)?;
    read_entry(&a, &e)
}
fn read_entry(a: &Archive, e: &IndexedEntry) -> Result<Vec<u8>> {
    ensure!(
        e.size <= PREVIEW_LIMIT as u64,
        "File exceeds the 256 MiB preview limit"
    );
    match &e.replacement {
        Some(Replacement::File(file)) => {
            let mut data = vec![0; e.size as usize];
            file.read_exact_at(&mut data, 0)?;
            return Ok(data);
        }
        Some(Replacement::Nested(child)) => return editor::to_bytes(child, PREVIEW_LIMIT),
        None => {}
    }
    let entry = &a.parsed.entries[e.index];
    if let RpfEntryKind::BinaryFile {
        file_size: 0,
        uncompressed_size: 0,
        ..
    } = entry.kind
    {
        return Ok(vec![]);
    }
    a.parsed
        .extract_entry(a.bytes(), entry, key_guard().as_ref())
}
pub fn open_nested(handle: i64, path: &str) -> Result<i64> {
    let a = get(handle)?;
    let e = lookup(&a, path)?;
    open_nested_entry(&a, &e)
}
fn open_nested_entry(a: &Arc<Archive>, e: &IndexedEntry) -> Result<i64> {
    match &e.replacement {
        Some(Replacement::Nested(child)) => {
            let id = NEXT.fetch_add(1, Ordering::Relaxed);
            archives().lock().unwrap().insert(id, editor::fork(child)?);
            return Ok(id);
        }
        Some(Replacement::File(file)) => return open_file(file.try_clone()?, &e.name),
        None => {}
    }
    if let RpfEntryKind::BinaryFile {
        file_offset,
        file_size,
        uncompressed_size,
        is_encrypted: false,
    } = a.parsed.entries[e.index].kind
    {
        if a.parsed.version == RpfVersion::V7 && (file_size == 0 || file_size == uncompressed_size)
        {
            let offset = file_offset as usize * 512 + a.parsed.start_offset;
            let length = uncompressed_size as usize;
            a.bytes()
                .get(
                    offset
                        ..offset
                            .checked_add(length)
                            .context("Nested archive range overflow")?,
                )
                .context("Nested archive outside parent")?;
            let start = a.range.start + offset;
            return insert_archive(Arc::clone(&a.data), start..start + length, &e.name);
        }
    }
    // Compressed/encrypted nested packs need decoded bytes, kept in bounded RAM.
    // Never create a duplicate package on disk merely to open it.
    let decoded = read_entry(a, e)?;
    let length = decoded.len();
    insert_archive(Arc::new(Backing::Decoded(decoded)), 0..length, &e.name)
}
pub fn extract(handle: i64, path: &str, output: &str) -> Result<()> {
    if handle < 0 {
        let file = documents()
            .lock()
            .unwrap()
            .get(&handle)
            .cloned()
            .context("Document is closed")?;
        let size = file.metadata()?.len();
        let mut destination = File::create(output)?;
        let mut buffer = vec![0; 256 * 1024];
        let mut offset = 0;
        while offset < size {
            let count = (size - offset).min(buffer.len() as u64) as usize;
            file.read_exact_at(&mut buffer[..count], offset)?;
            destination.write_all(&buffer[..count])?;
            offset += count as u64;
        }
        return Ok(());
    }
    if handle == 0 {
        std::fs::copy(path, output)?;
        return Ok(());
    }
    let a = get(handle)?;
    let e = lookup(&a, path)?;
    extract_entry_to_file(&a, &e, Path::new(output))
}
fn extract_entry_to_file(a: &Arc<Archive>, e: &IndexedEntry, output: &Path) -> Result<()> {
    let file = std::fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(false)
        .open(output)?;
    extract_entry_fd(a, e, file.as_raw_fd(), &a.cancelled)
}

pub fn extract_fd(handle: i64, path: &str, output: RawFd, cancel_handle: i64) -> Result<()> {
    let a = get(handle)?;
    let control = get(cancel_handle)?;
    extract_entry_fd(&a, &lookup(&a, path)?, output, &control.cancelled)
}

fn extract_entry_fd(
    a: &Arc<Archive>,
    e: &IndexedEntry,
    output: RawFd,
    cancelled: &AtomicBool,
) -> Result<()> {
    ensure!(!cancelled.load(Ordering::Relaxed), "Operation cancelled");
    let mut destination = checked_output(output)?;
    if let Some(Replacement::File(source)) = &e.replacement {
        let original = source.metadata()?;
        let target = destination.metadata()?;
        ensure!(
            original.dev() != target.dev() || original.ino() != target.ino(),
            "Output aliases the extracted file"
        );
    }
    destination.seek(std::io::SeekFrom::Start(0))?;
    destination.set_len(0)?;
    extract_entry_to_writer(a, e, &mut destination, cancelled)?;
    destination.flush()?;
    destination.sync_all()?;
    Ok(())
}

fn extract_entry_to_writer(
    a: &Arc<Archive>,
    e: &IndexedEntry,
    destination: &mut impl Write,
    cancelled: &AtomicBool,
) -> Result<()> {
    match &e.replacement {
        Some(Replacement::File(file)) => {
            return editor::copy_file(file, e.size, destination, cancelled);
        }
        Some(Replacement::Nested(child)) => {
            return editor::write_archive_cancellable(child, destination, cancelled);
        }
        None => {}
    }
    let entry = &a.parsed.entries[e.index];
    // Stream common unencrypted RPF7 binary entries, including multi-GB nested packs.
    if let RpfEntryKind::BinaryFile {
        file_offset,
        file_size,
        uncompressed_size,
        is_encrypted: false,
    } = entry.kind
    {
        if a.parsed.version == RpfVersion::V7 {
            let start = a.parsed.start_offset + file_offset as usize * 512;
            let count = if file_size == 0 {
                uncompressed_size
            } else {
                file_size
            } as usize;
            let raw = a
                .bytes()
                .get(start..start.checked_add(count).context("Offset overflow")?)
                .context("File outside archive")?;
            if file_size > 0 && file_size < uncompressed_size {
                let mut decoder =
                    flate2::read::DeflateDecoder::new(raw).take(uncompressed_size as u64 + 1);
                let size = copy_cancellable(&mut decoder, destination, cancelled)?;
                ensure!(size == uncompressed_size as u64, "Corrupt compressed file");
            } else {
                copy_cancellable(&mut &raw[..], destination, cancelled)?;
            }
            return Ok(());
        }
    }
    // Export is not a preview: do not apply PREVIEW_LIMIT here.
    let bytes = a
        .parsed
        .extract_entry(a.bytes(), entry, key_guard().as_ref())?;
    copy_cancellable(&mut &bytes[..], destination, cancelled)?;
    Ok(())
}

fn copy_cancellable(
    input: &mut impl Read,
    output: &mut impl Write,
    cancelled: &AtomicBool,
) -> Result<u64> {
    let mut buffer = vec![0; 256 * 1024];
    let mut total = 0;
    loop {
        ensure!(!cancelled.load(Ordering::Relaxed), "Operation cancelled");
        let count = input.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        output.write_all(&buffer[..count])?;
        total += count as u64;
    }
    Ok(total)
}

/// Recursively expands an archive into a normal directory. Nested RPF files
/// are traversed as well, so the output contains the complete resource tree.
pub fn extract_archive_to_dir(handle: i64, output: &str) -> Result<u64> {
    let a = get(handle)?;
    let root = Path::new(output);
    std::fs::create_dir_all(root)?;
    fn extract_handle(
        handle: i64,
        root: &Path,
        depth: usize,
        cancelled: &AtomicBool,
    ) -> Result<u64> {
        ensure!(depth <= 24, "Nested archive depth limit reached");
        let a = get(handle)?;
        ensure!(!a.cancelled.load(Ordering::Relaxed), "Operation cancelled");
        let mut written = 0u64;
        for entry in editor::snapshot(&a) {
            ensure!(!cancelled.load(Ordering::Relaxed), "Operation cancelled");
            let mut target = root.to_path_buf();
            for component in entry.path.split('/') {
                ensure!(
                    !component.is_empty()
                        && component != "."
                        && component != ".."
                        && !component.contains(['\\', '\0']),
                    "Invalid extracted path: {}",
                    entry.path
                );
                target.push(component);
            }
            if entry.directory {
                std::fs::create_dir_all(&target)?;
            } else if entry.name.to_ascii_lowercase().ends_with(".rpf") {
                std::fs::create_dir_all(&target)?;
                let child = open_nested_entry(&a, &entry)
                    .with_context(|| format!("Cannot open nested archive {}", entry.path))?;
                let result = extract_handle(child, &target, depth + 1, cancelled);
                close(child);
                written += result?;
            } else {
                if let Some(parent) = target.parent() {
                    std::fs::create_dir_all(parent)?;
                }
                extract_entry_to_file(&a, &entry, &target)
                    .with_context(|| format!("Cannot extract {}", entry.path))?;
                written += 1;
            }
        }
        Ok(written)
    }
    extract_handle(handle, root, 0, &a.cancelled)
}

#[derive(Debug)]
enum PackNode {
    Directory {
        name: String,
        children: Vec<PackNode>,
    },
    File(PackFile),
}

#[derive(Debug)]
struct PackFile {
    name: String,
    path: PathBuf,
    size: u64,
    resource: bool,
    system_flags: u32,
    graphics_flags: u32,
}

#[derive(Debug)]
enum PackFlatKind {
    Directory {
        entries_index: u32,
        entries_count: u32,
    },
    Binary {
        file_offset: u32,
        file_size: u32,
        uncompressed_size: u32,
    },
    Resource {
        file_offset: u32,
        file_size: u32,
        system_flags: u32,
        graphics_flags: u32,
    },
}

#[derive(Debug)]
struct PackFlatEntry {
    name: String,
    name_offset: u32,
    source: Option<PathBuf>,
    kind: PackFlatKind,
}

fn scan_pack_tree(
    current: &Path,
    temp_root: &Path,
    cancelled: &AtomicBool,
    depth: usize,
    target: &std::fs::Metadata,
) -> Result<PackNode> {
    ensure!(depth <= 64, "Pack directory depth limit reached");
    let mut children = Vec::new();
    let mut names = HashSet::new();
    for entry in std::fs::read_dir(current)? {
        ensure!(!cancelled.load(Ordering::Relaxed), "Operation cancelled");
        let entry = entry?;
        let path = entry.path();
        ensure!(
            !entry.file_type()?.is_symlink(),
            "Pack input contains a symlink: {}",
            path.display()
        );
        let metadata = entry.metadata()?;
        ensure!(
            metadata.dev() != target.dev() || metadata.ino() != target.ino(),
            "Pack output must not overwrite an input file"
        );
        let name = entry
            .file_name()
            .to_str()
            .context("Pack input name is not UTF-8")?
            .to_owned();
        ensure!(
            !name.is_empty() && name != "." && name != ".." && !name.contains(['/', '\\', '\0']),
            "Invalid pack input name"
        );
        ensure!(
            names.insert(name.to_lowercase()),
            "Duplicate RPF name: {name}"
        );
        if metadata.is_dir() {
            if name.to_ascii_lowercase().ends_with(".rpf") {
                let nested_path = unique_pack_temp_path(temp_root, "nested")?;
                let mut nested_output = File::create(&nested_path)?;
                write_pack_tree(
                    &scan_pack_tree(&path, temp_root, cancelled, depth + 1, target)?,
                    &mut nested_output,
                    cancelled,
                )?;
                children.push(PackNode::File(pack_file(&name, &nested_path)?));
            } else {
                children.push(PackNode::Directory {
                    name,
                    children: scan_pack_tree_inner(&path, temp_root, cancelled, depth + 1, target)?,
                });
            }
        } else if metadata.is_file() {
            children.push(PackNode::File(pack_file(&name, &path)?));
        }
    }
    children.sort_by(|a, b| pack_node_name(a).cmp(pack_node_name(b)));
    Ok(PackNode::Directory {
        name: String::new(),
        children,
    })
}

fn scan_pack_tree_inner(
    current: &Path,
    temp_root: &Path,
    cancelled: &AtomicBool,
    depth: usize,
    target: &std::fs::Metadata,
) -> Result<Vec<PackNode>> {
    match scan_pack_tree(current, temp_root, cancelled, depth, target)? {
        PackNode::Directory { children, .. } => Ok(children),
        PackNode::File(_) => unreachable!("scan_pack_tree always returns a directory"),
    }
}

fn pack_node_name(node: &PackNode) -> &str {
    match node {
        PackNode::Directory { name, .. } => name,
        PackNode::File(file) => &file.name,
    }
}

fn pack_file(name: &str, path: &Path) -> Result<PackFile> {
    let mut file = File::open(path)?;
    let size = file.metadata()?.len();
    ensure!(
        size <= u32::MAX as u64,
        "{name} exceeds the RPF7 4 GiB entry size limit"
    );
    let mut header = [0u8; 16];
    let resource = size >= 16 && {
        file.read_exact(&mut header)?;
        u32::from_le_bytes(header[..4].try_into()?) == RSC7_MAGIC
    };
    let (system_flags, graphics_flags) = if resource {
        (
            u32::from_le_bytes(header[8..12].try_into()?),
            u32::from_le_bytes(header[12..16].try_into()?),
        )
    } else {
        (0, 0)
    };
    Ok(PackFile {
        name: name.to_string(),
        path: path.to_path_buf(),
        size,
        resource,
        system_flags,
        graphics_flags,
    })
}

fn flatten_pack_tree(root: &PackNode) -> Result<Vec<PackFlatEntry>> {
    let mut flat = Vec::new();
    let children = match root {
        PackNode::Directory { children, .. } => children,
        PackNode::File(_) => bail!("Pack root must be a directory"),
    };
    flat.push(PackFlatEntry {
        name: String::new(),
        name_offset: 0,
        source: None,
        kind: PackFlatKind::Directory {
            entries_index: 0,
            entries_count: 0,
        },
    });
    flatten_pack_directory(children, 0, &mut flat);
    ensure!(!flat.is_empty(), "Pack source directory is empty");
    Ok(flat)
}

fn flatten_pack_directory(children: &[PackNode], self_index: usize, flat: &mut Vec<PackFlatEntry>) {
    let entries_index = flat.len() as u32;
    if let PackFlatKind::Directory {
        entries_index: index,
        entries_count,
    } = &mut flat[self_index].kind
    {
        *index = entries_index;
        *entries_count = children.len() as u32;
    }
    let child_start = flat.len();
    for child in children {
        match child {
            PackNode::Directory { name, .. } => flat.push(PackFlatEntry {
                name: name.clone(),
                name_offset: 0,
                source: None,
                kind: PackFlatKind::Directory {
                    entries_index: 0,
                    entries_count: 0,
                },
            }),
            PackNode::File(file) => flat.push(PackFlatEntry {
                name: file.name.clone(),
                name_offset: 0,
                source: Some(file.path.clone()),
                kind: if file.resource {
                    PackFlatKind::Resource {
                        file_offset: 0,
                        file_size: file.size as u32,
                        system_flags: file.system_flags,
                        graphics_flags: file.graphics_flags,
                    }
                } else {
                    PackFlatKind::Binary {
                        file_offset: 0,
                        file_size: 0, // Zero denotes stored data; logical size is 32-bit.
                        uncompressed_size: file.size as u32,
                    }
                },
            }),
        }
    }
    for (offset, child) in children.iter().enumerate() {
        if let PackNode::Directory { children, .. } = child {
            flatten_pack_directory(children, child_start + offset, flat);
        }
    }
}

fn unique_pack_temp_path(root: &Path, prefix: &str) -> Result<PathBuf> {
    static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);
    loop {
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        let path = root.join(format!("{prefix}-{id}.rpf"));
        match std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&path)
        {
            Ok(_) => return Ok(path),
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(error.into()),
        }
    }
}

fn write_pack_tree(root: &PackNode, output: &mut File, cancelled: &AtomicBool) -> Result<u64> {
    let mut flat = flatten_pack_tree(root)?;
    let target = output.metadata()?;
    for entry in &flat {
        if let Some(path) = &entry.source {
            let source = std::fs::metadata(path)?;
            ensure!(
                source.dev() != target.dev() || source.ino() != target.ino(),
                "Pack output must not overwrite an input file"
            );
        }
    }
    let mut names = Vec::<u8>::new();
    let mut name_offsets = Vec::<u32>::with_capacity(flat.len());
    let mut name_map = HashMap::<String, u32>::new();
    for entry in &flat {
        let offset = *name_map.entry(entry.name.clone()).or_insert_with(|| {
            let offset = names.len() as u32;
            names.extend_from_slice(entry.name.as_bytes());
            names.push(0);
            offset
        });
        name_offsets.push(offset);
    }
    for (index, offset) in name_offsets.iter().enumerate() {
        flat[index].name_offset = *offset;
    }
    let rem = names.len() % 16;
    if rem != 0 {
        names.resize(names.len() + (16 - rem), 0);
    }
    let names_length = names.len() as u32;
    ensure!(
        names.len() <= u16::MAX as usize,
        "Pack names table exceeds 64 KiB"
    );
    let entry_count = flat.len() as u32;
    let header_bytes = 16u64 + entry_count as u64 * 16 + names_length as u64;
    let header_blocks = (header_bytes + 511) / 512;
    let mut current_block = header_blocks as u32;
    for entry in &mut flat {
        match &mut entry.kind {
            PackFlatKind::Directory { .. } => {}
            PackFlatKind::Binary {
                file_offset,
                uncompressed_size: file_size,
                ..
            }
            | PackFlatKind::Resource {
                file_offset,
                file_size,
                ..
            } => {
                ensure!(current_block < 0x800000, "RPF7 block offset limit exceeded");
                *file_offset = current_block;
                let blocks = (*file_size as u64).div_ceil(512);
                current_block = u32::try_from(current_block as u64 + blocks)?;
            }
        }
    }
    let mut entries = Vec::<u8>::with_capacity(flat.len() * 16);
    for (index, entry) in flat.iter().enumerate() {
        let name_offset = name_offsets[index] as u16;
        match &entry.kind {
            PackFlatKind::Directory {
                entries_index,
                entries_count,
            } => {
                entries.extend_from_slice(&entry.name_offset.to_le_bytes());
                entries.extend_from_slice(&0x7FFFFF00u32.to_le_bytes());
                entries.extend_from_slice(&entries_index.to_le_bytes());
                entries.extend_from_slice(&entries_count.to_le_bytes());
            }
            PackFlatKind::Binary {
                file_offset,
                file_size,
                uncompressed_size,
            } => {
                entries.extend_from_slice(&name_offset.to_le_bytes());
                entries.extend_from_slice(&file_size.to_le_bytes()[..3]);
                entries.extend_from_slice(&file_offset.to_le_bytes()[..3]);
                entries.extend_from_slice(&uncompressed_size.to_le_bytes());
                entries.extend_from_slice(&0u32.to_le_bytes());
            }
            PackFlatKind::Resource {
                file_offset,
                file_size,
                system_flags,
                graphics_flags,
            } => {
                // Resource entries store a 24-bit sector offset with the high bit set.
                // Writing only the high byte used to drop the low 16 bits, so models
                // and textures packed from a folder could not be found again.
                entries.extend_from_slice(&name_offset.to_le_bytes());
                entries.extend_from_slice(&(*file_size).min(0xFFFFFF).to_le_bytes()[..3]);
                let packed_offset = *file_offset | 0x800000;
                entries.extend_from_slice(&packed_offset.to_le_bytes()[..3]);
                entries.extend_from_slice(&system_flags.to_le_bytes());
                entries.extend_from_slice(&graphics_flags.to_le_bytes());
            }
        }
    }
    ensure!(
        entries.len() == flat.len() * 16,
        "Incorrect RPF7 entry table size"
    );
    ensure!(!cancelled.load(Ordering::Relaxed), "Operation cancelled");
    output.seek(std::io::SeekFrom::Start(0))?;
    output.set_len(0)?;
    output.write_all(&rpf_archive::RPF7_MAGIC.to_le_bytes())?;
    output.write_all(&entry_count.to_le_bytes())?;
    output.write_all(&names_length.to_le_bytes())?;
    output.write_all(&0x4E45504Fu32.to_le_bytes())?;
    output.write_all(&entries)?;
    output.write_all(&names)?;
    let header_size = header_blocks as usize * 512;
    let mut position = 16 + entries.len() + names.len();
    while position < header_size {
        output.write_all(&[0u8; 512][..(header_size - position).min(512)])?;
        position += 512;
    }
    let mut buffer = vec![0u8; 256 * 1024];
    for entry in &flat {
        let size = match &entry.kind {
            PackFlatKind::Binary {
                uncompressed_size: file_size,
                ..
            }
            | PackFlatKind::Resource { file_size, .. } => *file_size as usize,
            PackFlatKind::Directory { .. } => continue,
        };
        let path = entry.source.as_deref().context("Missing pack source")?;
        let mut input = File::open(path)?;
        ensure!(
            input.metadata()?.len() == size as u64,
            "Pack source changed: {}",
            path.display()
        );
        let mut remaining = size;
        if let PackFlatKind::Resource {
            system_flags,
            graphics_flags,
            ..
        } = entry.kind
        {
            input.seek(std::io::SeekFrom::Start(16))?;
            output.write_all(&editor::resource_header(
                system_flags,
                graphics_flags,
                size as u64,
            ))?;
            remaining -= 16;
        }
        while remaining != 0 {
            ensure!(!cancelled.load(Ordering::Relaxed), "Operation cancelled");
            let count = remaining.min(buffer.len());
            input.read_exact(&mut buffer[..count])?;
            output.write_all(&buffer[..count])?;
            remaining -= count;
        }
        let padding = (512 - (size % 512)) % 512;
        output.write_all(&[0u8; 512][..padding])?;
    }
    output.flush()?;
    output.sync_all()?;
    Ok(output.metadata()?.len())
}

/// Packs a local directory into an unencrypted, open RPF7 archive without
/// holding the complete archive or all source files in memory.
pub fn pack_directory(input: &str, output_fd: RawFd) -> Result<u64> {
    pack_directory_with_cancel(input, output_fd, &AtomicBool::new(false))
}
pub fn pack_directory_cancellable(input: &str, output_fd: RawFd, operation: i64) -> Result<u64> {
    let cancelled = OPERATIONS
        .get_or_init(Default::default)
        .lock()
        .unwrap()
        .get(&operation)
        .cloned()
        .context("Operation is closed")?;
    pack_directory_with_cancel(input, output_fd, &cancelled)
}
fn pack_directory_with_cancel(
    input: &str,
    output_fd: RawFd,
    cancelled: &AtomicBool,
) -> Result<u64> {
    let root = Path::new(input);
    ensure!(root.is_dir(), "Pack source is not a directory");
    let mut output = checked_output(output_fd)?;
    let target = output.metadata()?;
    let parent = root.parent().unwrap_or(Path::new("."));
    let temp_root = unique_pack_temp_path(parent, "temp-root")?;
    std::fs::remove_file(&temp_root)?;
    std::fs::create_dir(&temp_root)?;
    let result = (|| {
        let tree = scan_pack_tree(root, &temp_root, cancelled, 0, &target)?;
        write_pack_tree(&tree, &mut output, cancelled)
    })();
    let cleanup = std::fs::remove_dir_all(&temp_root);
    let packed_size = result?;
    cleanup?;
    Ok(packed_size)
}
pub fn load_keys(path: &str) -> Result<()> {
    let loaded = GtaKeys::load_from_path(Path::new(path))?;
    let mut keys = key_write_guard();
    #[cfg(feature = "conversion")]
    ensure!(
        !conversion::batch_active(),
        "Finish texture conversion before changing keys"
    );
    *keys = Some(loaded);
    Ok(())
}
pub fn clear_keys() -> Result<()> {
    let mut keys = key_write_guard();
    #[cfg(feature = "conversion")]
    ensure!(
        !conversion::batch_active(),
        "Finish texture conversion before changing keys"
    );
    *keys = None;
    Ok(())
}

pub fn cancel(handle: i64) {
    if let Some(cancelled) = OPERATIONS
        .get_or_init(Default::default)
        .lock()
        .unwrap()
        .get(&handle)
    {
        cancelled.store(true, Ordering::Relaxed);
    }
    if let Ok(a) = get(handle) {
        a.cancelled.store(true, Ordering::Relaxed);
    }
}

pub fn reset_cancel(handle: i64) {
    if let Ok(a) = get(handle) {
        a.cancelled.store(false, Ordering::Relaxed);
    }
}

/// Validate destination identity before truncating any SAF document.
pub fn copy_output_fd(handle: i64, source: RawFd, target: RawFd) -> Result<()> {
    use std::io::{Seek, SeekFrom};
    use std::os::unix::fs::MetadataExt;
    let input = duplicate(source)?;
    let mut output = duplicate(target)?;
    let target_info = output.metadata()?;
    let same = |f: &File| -> Result<()> {
        let m = f.metadata()?;
        ensure!(
            m.dev() != target_info.dev() || m.ino() != target_info.ino(),
            "Choose a new file; overwriting an open source is not allowed"
        );
        Ok(())
    };
    same(&input)?;
    for a in archives().lock().unwrap().values() {
        if let Backing::Mapped(_, f) = &*a.data {
            same(f)?;
        }
    }
    for f in documents().lock().unwrap().values() {
        same(f)?;
    }
    let a = get(handle).ok();
    let unused = AtomicBool::new(false);
    let cancel = a.as_ref().map_or(&unused, |a| &a.cancelled);
    ensure!(!cancel.load(Ordering::Relaxed), "Operation cancelled");
    output.seek(SeekFrom::Start(0))?;
    output.set_len(0)?;
    editor::copy_file(&input, input.metadata()?.len(), &mut output, cancel)?;
    output.flush()?;
    output.sync_all()?;
    Ok(())
}

#[cfg(feature = "conversion")]
pub fn textures(handle: i64, path: &str) -> Result<Value> {
    conversion::texture_list(
        &read(handle, path)?,
        &path.rsplit('.').next().unwrap_or("").to_ascii_lowercase(),
    )
}
#[cfg(not(feature = "conversion"))]
pub fn textures(handle: i64, path: &str) -> Result<Value> {
    let ts = decode_textures(
        &read(handle, path)?,
        path.rsplit('.')
            .next()
            .unwrap_or("")
            .to_ascii_lowercase()
            .as_str(),
    )?;
    ensure!(
        !ts.is_empty() || !path.to_ascii_lowercase().ends_with(".ytd"),
        "No readable textures in dictionary"
    );
    Ok(Value::Array(ts.iter().enumerate().map(|(i,t)| json!({"index":i,"name":t.name,"width":t.width,"height":t.height,"format":t.format.to_string(),"levels":t.levels})).collect()))
}

pub fn texture_sources(handle: i64) -> Result<Value> {
    if handle <= 0 {
        return Ok(json!([]));
    }
    let a = get(handle)?;
    Ok(json!(editor::snapshot(&a)
        .into_iter()
        .filter(|e| !e.directory && e.name.to_ascii_lowercase().ends_with(".ytd"))
        .map(|e| json!({"name":e.name,"path":e.path}))
        .collect::<Vec<_>>()))
}
#[cfg(feature = "conversion")]
pub fn texture_png(handle: i64, path: &str, index: usize) -> Result<Vec<u8>> {
    conversion::texture_preview(
        &read(handle, path)?,
        &path.rsplit('.').next().unwrap_or("").to_ascii_lowercase(),
        index,
    )
}
#[cfg(not(feature = "conversion"))]
pub fn texture_png(handle: i64, path: &str, index: usize) -> Result<Vec<u8>> {
    let ts = decode_textures(
        &read(handle, path)?,
        path.rsplit('.')
            .next()
            .unwrap_or("")
            .to_ascii_lowercase()
            .as_str(),
    )?;
    let texture = ts.get(index).context("Texture index out of range")?;
    let rgba = rpf_archive::texture_utils::decompress_texture(texture)?;
    let mut bytes = std::io::Cursor::new(Vec::new());
    let bitmap = image::RgbaImage::from_raw(texture.width as u32, texture.height as u32, rgba)
        .context("Invalid pixel buffer")?;
    bitmap.write_to(&mut bytes, image::ImageFormat::Png)?;
    Ok(bytes.into_inner())
}

pub(crate) fn decode_textures(data: &[u8], ext: &str) -> Result<Vec<rpf_archive::ytd::YtdTexture>> {
    if ext == "dds" {
        return Ok(vec![crate::dds::parse(data)?]);
    }
    let (system, graphics) = rpf_archive::resource::prepare_rsc7(data)?;
    decode_texture_sections(&system, &graphics, ext)
}

fn decode_texture_sections(
    system: &[u8],
    graphics: &[u8],
    ext: &str,
) -> Result<Vec<rpf_archive::ytd::YtdTexture>> {
    use rpf_archive::resource::{u16_le, u64_le, ResReader};
    let r = ResReader { system, graphics };
    if ext == "ytd" {
        return rpf_archive::ytd::parse_texture_dict_at(&r, 0x50000000);
    }
    let roots = match ext {
        "ydr" => vec![0x50000000],
        "yft" => vec![u64_le(
            r.resolve(0x50000000, 0x38).context("Truncated fragment")?,
            0x30,
        )],
        "ydd" => {
            let root = r
                .resolve(0x50000000, 0x40)
                .context("Truncated drawable dictionary")?;
            let count = u16_le(root, 0x38) as usize;
            ensure!(count <= 4096, "Too many drawables");
            if count == 0 {
                return Ok(vec![]);
            }
            r.resolve(u64_le(root, 0x30), count * 8)
                .context("Invalid drawable pointers")?
                .chunks_exact(8)
                .map(|b| u64_le(b, 0))
                .collect()
        }
        _ => anyhow::bail!("No texture dictionary in this format"),
    };
    let mut textures = Vec::new();
    let mut seen = HashSet::new();
    for ptr in roots {
        let d = r.resolve(ptr, 0x18).context("Invalid drawable")?;
        let group = u64_le(d, 0x10);
        if group == 0 {
            continue;
        }
        let s = r.resolve(group, 16).context("Invalid shader group")?;
        let dict = u64_le(s, 8);
        if dict != 0 && seen.insert(dict) {
            textures.extend(rpf_archive::ytd::parse_texture_dict_at(&r, dict)?);
        }
    }
    Ok(textures)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rpf_archive::{RpfBuilder, RpfEncryption, RpfEntry};
    use std::os::fd::AsRawFd;

    #[test]
    fn key_readers_extract_concurrently_while_replacement_is_exclusive() {
        use std::{sync::mpsc, thread, time::Duration};
        let expected = crate::fixtures::textures();
        let mut builder = RpfBuilder::new(RpfEncryption::Open);
        builder.add_file("texture.ytd", expected.clone());
        let file = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(file.path(), builder.build(None).unwrap()).unwrap();
        let archive = open_fd(file.as_file().as_raw_fd(), "parallel.rpf").unwrap();
        let first_reader = key_guard();
        let writer_is_blocked =
            matches!(keys().try_write(), Err(std::sync::TryLockError::WouldBlock));
        let (done, completed) = mpsc::channel();
        let worker = thread::spawn(move || {
            // The actual resource extraction acquires its own key guard.
            done.send(read(archive, "texture.ytd")).unwrap();
        });
        let result = completed.recv_timeout(Duration::from_secs(5));
        // Release before checking the timeout so a regressed exclusive read
        // lock fails the test instead of leaving its worker permanently blocked.
        drop(first_reader);
        worker.join().unwrap();
        close(archive);
        assert!(
            writer_is_blocked,
            "key replacement entered while a decoder held a reader"
        );
        assert_eq!(
            result
                .expect("a second resource reader was serialized")
                .unwrap(),
            expected
        );
    }

    #[cfg(feature = "conversion")]
    #[test]
    fn key_readers_aes_ng_and_all_imported_selectors_match_serial_decryption() {
        use rpf_archive::crypto::{decrypt_aes, decrypt_ng, decrypt_ng_with_key, encrypt_aes};
        use std::{sync::Barrier, thread};
        let mut tables = vec![[[0u32; 256]; 16]; 17];
        for (round, round_tables) in tables.iter_mut().enumerate() {
            for (lane, table) in round_tables.iter_mut().enumerate() {
                for (value, entry) in table.iter_mut().enumerate() {
                    *entry = (value as u32).rotate_left((round + lane) as u32)
                        ^ (round as u32).wrapping_mul(0x9e3779b9)
                        ^ (lane as u32).wrapping_mul(0x01010101);
                }
            }
        }
        let imported = GtaKeys {
            aes_key: [0x5a; 32],
            ng_keys: (0..101)
                .map(|key| (0..272).map(|byte| (key * 17 + byte * 23) as u8).collect())
                .collect(),
            ng_decrypt_tables: tables.into_boxed_slice().try_into().unwrap(),
        };
        let inputs: Vec<Vec<u8>> = (0..8)
            .map(|sample| {
                (0..67)
                    .map(|byte| (sample * 41 + byte * 23) as u8)
                    .collect()
            })
            .collect();
        let encrypted: Vec<_> = inputs
            .iter()
            .map(|input| encrypt_aes(input, &imported.aes_key))
            .collect();
        let serial_ng: Vec<_> = inputs
            .iter()
            .enumerate()
            .map(|(i, input)| decrypt_ng(input, &imported, "parallel.rpf", i as u32))
            .collect();
        let serial_selected: Vec<Vec<_>> = (0..imported.ng_keys.len())
            .map(|selector| {
                inputs
                    .iter()
                    .map(|input| decrypt_ng_with_key(input, &imported, selector).unwrap())
                    .collect()
            })
            .collect();
        let barrier = Barrier::new(4);
        thread::scope(|scope| {
            let mut workers = Vec::new();
            for worker in 0..4 {
                let (imported, inputs, encrypted, serial_ng, serial_selected, barrier) = (
                    &imported,
                    &inputs,
                    &encrypted,
                    &serial_ng,
                    &serial_selected,
                    &barrier,
                );
                workers.push(scope.spawn(move || {
                    barrier.wait();
                    for selector in 0..imported.ng_keys.len() {
                        let sample = (selector + worker) % inputs.len();
                        let decoded =
                            decrypt_ng_with_key(&inputs[sample], imported, selector)
                                .unwrap();
                        assert_eq!(
                            decoded, serial_selected[selector][sample],
                            "worker {worker}, NG key index {selector}"
                        );
                        assert_eq!(
                            &decoded[64..],
                            &inputs[sample][64..],
                            "NG partial tail changed"
                        );
                        assert_eq!(
                            decrypt_ng(&inputs[sample], imported, "parallel.rpf", sample as u32),
                            serial_ng[sample]
                        );
                        assert_eq!(
                            decrypt_aes(&encrypted[sample], &imported.aes_key),
                            inputs[sample]
                        );
                    }
                }));
            }
            for worker in workers {
                worker.join().unwrap();
            }
        });
    }

    #[test]
    fn descriptor_opens_large_archive_without_a_copy_and_outlives_caller() {
        let mut builder = RpfBuilder::new(RpfEncryption::Open);
        builder.add_file("tiny.txt", b"direct read".to_vec());
        let file = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(file.path(), builder.build(None).unwrap()).unwrap();
        file.as_file().set_len(2 * 1024 * 1024 * 1024).unwrap();
        let h = open_fd(file.as_file().as_raw_fd(), "large.rpf").unwrap();
        drop(file);
        assert_eq!(info(h).unwrap()["total_size"], 2u64 * 1024 * 1024 * 1024);
        assert_eq!(read(h, "tiny.txt").unwrap(), b"direct read");
        close(h);
    }

    #[test]
    fn document_reads_do_not_share_a_cursor_or_depend_on_java_descriptor() {
        use std::io::{Seek, SeekFrom};
        let mut file = tempfile::NamedTempFile::new().unwrap();
        file.write_all(b"resource bytes").unwrap();
        file.seek(SeekFrom::Start(5)).unwrap();
        let h = open_document(file.as_file().as_raw_fd()).unwrap();
        assert_eq!(read(h, "asset.ydr").unwrap(), b"resource bytes");
        assert_eq!(file.stream_position().unwrap(), 5);
        drop(file);
        assert_eq!(read(h, "asset.ydr").unwrap(), b"resource bytes");
        close(h);
        assert!(read(h, "asset.ydr").is_err());
    }

    #[test]
    fn stored_nested_archive_shares_mapping_and_survives_parent_close() {
        let mut child = RpfBuilder::new(RpfEncryption::Open);
        child.add_file("leaf.txt", b"inside".to_vec());
        let mut parent = RpfBuilder::new(RpfEncryption::Open);
        parent.add_file("nested.rpf", child.build(None).unwrap());
        let file = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(file.path(), parent.build(None).unwrap()).unwrap();
        let outer = open_fd(file.as_file().as_raw_fd(), "outer.rpf").unwrap();
        let inner = open_nested(outer, "nested.rpf").unwrap();
        assert!(Arc::ptr_eq(
            &get(outer).unwrap().data,
            &get(inner).unwrap().data
        ));
        close(outer);
        assert_eq!(read(inner, "leaf.txt").unwrap(), b"inside");
        close(inner);
    }

    #[test]
    fn truncated_aes_header_is_rejected_before_directory_parsing() {
        let header = [
            0x37, 0x46, 0x50, 0x52, 0x8d, 0x05, 0, 0, 0xd0, 0x6d, 0, 0, 0xf9, 0xff, 0xff, 0x0f,
        ];
        let message = RpfArchive::parse(&header, "common.rpf", None)
            .err()
            .unwrap()
            .to_string();
        assert!(message.contains("truncated"), "{message}");
    }
    #[test]
    fn full_paths_and_json_round_trip() {
        let mut b = RpfBuilder::new(RpfEncryption::Open);
        b.add_file("first/a.txt", b"one".to_vec());
        b.add_file("second/a.txt", b"two".to_vec());
        b.add_file("中文/quote\".txt", b"three".to_vec());
        let f = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(f.path(), b.build(None).unwrap()).unwrap();
        let h = open(f.path().to_str().unwrap(), "test.rpf").unwrap();
        assert_eq!(read(h, "second/a.txt").unwrap(), b"two");
        assert!(read(h, "a.txt").is_err());
        let list = list(h, "中文").unwrap();
        assert_eq!(list[0]["name"], "quote\".txt");
        close(h);
        assert!(get(h).is_err());
    }
    #[test]
    fn streaming_pack_round_trips_files_and_nested_rpf_directories() {
        let input = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(input.path().join("nested.rpf")).unwrap();
        std::fs::write(input.path().join("a.txt"), b"outer").unwrap();
        std::fs::write(input.path().join("nested.rpf/leaf.txt"), b"inner").unwrap();
        let output = tempfile::NamedTempFile::new().unwrap();
        let packed_size =
            pack_directory(input.path().to_str().unwrap(), output.as_file().as_raw_fd()).unwrap();
        assert!(packed_size > 0);

        let outer = open(output.path().to_str().unwrap(), "packed.rpf").unwrap();
        assert_eq!(read(outer, "a.txt").unwrap(), b"outer");
        let inner = open_nested(outer, "nested.rpf").unwrap();
        assert_eq!(read(inner, "leaf.txt").unwrap(), b"inner");

        let extracted = tempfile::tempdir().unwrap();
        extract_archive_to_dir(outer, extracted.path().to_str().unwrap()).unwrap();
        assert_eq!(
            std::fs::read(extracted.path().join("nested.rpf/leaf.txt")).unwrap(),
            b"inner"
        );
        close(inner);
        close(outer);
    }

    #[test]
    fn streaming_pack_round_trips_rsc7_resources() {
        let input = tempfile::tempdir().unwrap();
        let mut resource = Vec::new();
        resource.extend_from_slice(&RSC7_MAGIC.to_le_bytes());
        resource.extend_from_slice(&0u32.to_le_bytes());
        resource.extend_from_slice(&0u32.to_le_bytes());
        resource.extend_from_slice(&0u32.to_le_bytes());
        resource.extend_from_slice(b"payload-bytes");
        std::fs::write(input.path().join("prop.ydr"), &resource).unwrap();
        let output = tempfile::NamedTempFile::new().unwrap();
        pack_directory(input.path().to_str().unwrap(), output.as_file().as_raw_fd()).unwrap();
        let packed = open(output.path().to_str().unwrap(), "packed.rpf").unwrap();
        assert_eq!(read(packed, "prop.ydr").unwrap(), resource);
        close(packed);
    }

    #[test]
    fn fd_extraction_protects_sources_and_observes_root_cancellation() {
        let source = tempfile::NamedTempFile::new().unwrap();
        let mut builder = RpfBuilder::new(RpfEncryption::Open);
        builder.add_file("leaf.txt", b"decoded".to_vec());
        let bytes = builder.build(None).unwrap();
        std::fs::write(source.path(), &bytes).unwrap();
        let handle = open_fd(source.as_file().as_raw_fd(), "test.rpf").unwrap();
        assert!(extract_fd(handle, "leaf.txt", source.as_file().as_raw_fd(), handle).is_err());
        assert_eq!(std::fs::read(source.path()).unwrap(), bytes);
        let output = tempfile::NamedTempFile::new().unwrap();
        extract_fd(handle, "leaf.txt", output.as_file().as_raw_fd(), handle).unwrap();
        assert_eq!(std::fs::read(output.path()).unwrap(), b"decoded");
        cancel(handle);
        std::fs::write(output.path(), b"sentinel").unwrap();
        assert!(extract_fd(handle, "leaf.txt", output.as_file().as_raw_fd(), handle).is_err());
        assert_eq!(std::fs::read(output.path()).unwrap(), b"sentinel");
        close(handle);
    }

    #[test]
    fn packing_rejects_source_aliases_duplicate_names_and_cancelled_operations() {
        let input = tempfile::tempdir().unwrap();
        let source = input.path().join("source.txt");
        std::fs::write(&source, b"original").unwrap();
        let file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(&source)
            .unwrap();
        assert!(pack_directory(input.path().to_str().unwrap(), file.as_raw_fd()).is_err());
        assert_eq!(std::fs::read(&source).unwrap(), b"original");
        let nested = input.path().join("nested.rpf");
        std::fs::create_dir(&nested).unwrap();
        let inner_path = nested.join("inner.txt");
        std::fs::write(&inner_path, b"nested source").unwrap();
        let inner = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(&inner_path)
            .unwrap();
        assert!(pack_directory(input.path().to_str().unwrap(), inner.as_raw_fd()).is_err());
        assert_eq!(std::fs::read(inner_path).unwrap(), b"nested source");
        let output = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(output.path(), b"sentinel").unwrap();
        let operation = begin_operation();
        cancel(operation);
        assert!(pack_directory_cancellable(
            input.path().to_str().unwrap(),
            output.as_file().as_raw_fd(),
            operation
        )
        .is_err());
        finish_operation(operation);
        assert_eq!(std::fs::read(output.path()).unwrap(), b"sentinel");
        std::fs::write(input.path().join("SOURCE.txt"), b"conflict").unwrap();
        assert!(
            pack_directory(input.path().to_str().unwrap(), output.as_file().as_raw_fd()).is_err()
        );
        assert_eq!(std::fs::read(output.path()).unwrap(), b"sentinel");
    }

    #[test]
    fn directory_cycles_are_rejected() {
        let a = RpfArchive {
            name: "bad".into(),
            start_offset: 0,
            encryption: RpfEncryption::Open,
            version: RpfVersion::V7,
            entries: vec![RpfEntry {
                name: "root".into(),
                name_lower: "root".into(),
                kind: RpfEntryKind::Directory {
                    entries_index: 0,
                    entries_count: 1,
                },
            }],
        };
        assert!(index(&a).is_err());
    }
}
