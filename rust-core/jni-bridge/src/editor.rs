//! Copy-on-edit metadata and streaming OPEN RPF7 output. Original mappings stay read-only.
use super::*;
use anyhow::bail;
use rpf_archive::{archive::resource_version_from_flags, RpfEncryption};
use std::{
    collections::VecDeque,
    io::{Seek, SeekFrom},
    os::unix::fs::MetadataExt,
};

#[derive(Clone)]
pub(super) struct Revision {
    pub entries: Vec<IndexedEntry>,
    pub revision: u64,
}
pub(super) struct EditHistory {
    pub current: Revision,
    pub past: Vec<Revision>,
    pub future: Vec<Revision>,
    pub saved: u64,
    next: u64,
    batch: Option<Revision>,
}
impl EditHistory {
    pub fn new(entries: Vec<IndexedEntry>) -> Self {
        Self {
            current: Revision {
                entries,
                revision: 0,
            },
            past: vec![],
            future: vec![],
            saved: 0,
            next: 1,
            batch: None,
        }
    }
    fn commit(&mut self, entries: Vec<IndexedEntry>) {
        let old = std::mem::replace(
            &mut self.current,
            Revision {
                entries,
                revision: self.next,
            },
        );
        self.next += 1;
        if self.batch.is_some() {
            return;
        }
        self.past.push(old);
        self.trim_history();
        self.future.clear();
    }
    fn trim_history(&mut self) {
        // Bound metadata history, with no copies of archive payloads.
        let limit = (2_000_000 / self.current.entries.len().max(1)).clamp(1, 40);
        if self.past.len() > limit {
            self.past.drain(..self.past.len() - limit);
        }
    }
}
pub(super) fn snapshot(a: &Archive) -> Vec<IndexedEntry> {
    a.edits
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .current
        .entries
        .clone()
}
pub(super) fn fork(a: &Archive) -> Result<Arc<Archive>> {
    let entries = snapshot(a);
    Ok(Arc::new(Archive {
        parsed: RpfArchive {
            name: a.parsed.name.clone(),
            start_offset: a.parsed.start_offset,
            encryption: a.parsed.encryption,
            entries: a.parsed.entries.clone(),
            version: a.parsed.version,
        },
        data: a.data.clone(),
        range: a.range.clone(),
        entries: a.entries.clone(),
        edits: Mutex::new(EditHistory::new(entries)),
        cancelled: AtomicBool::new(false),
        scan_state: Mutex::new((0, String::new())),
    }))
}
fn editable(a: &Archive) -> Result<()> {
    ensure!(
        a.parsed.version == RpfVersion::V7,
        "Editing is supported for RPF7 only"
    );
    Ok(())
}
fn name(value: &str) -> Result<()> {
    ensure!(
        !value.trim().is_empty()
            && value != "."
            && value != ".."
            && !value.contains(['/', '\\', '\0'])
            && !value.chars().any(char::is_control)
            && value.len() <= 255,
        "Invalid entry name"
    );
    Ok(())
}
fn path(value: &str) -> Result<String> {
    let p = value.replace('\\', "/");
    ensure!(
        !p.starts_with('/') && !p.ends_with('/'),
        "Invalid archive path"
    );
    ensure!(p.split('/').count() <= 128, "Directory depth limit reached");
    for part in p.split('/') {
        name(part)?;
    }
    Ok(p)
}
fn parent(p: &str) -> &str {
    p.rsplit_once('/').map_or("", |(p, _)| p)
}
fn has_path(e: &IndexedEntry, p: &str) -> bool {
    e.path.to_lowercase() == p.to_lowercase()
}
fn inside(e: &IndexedEntry, p: &str) -> bool {
    has_path(e, p) || e.path.to_lowercase().starts_with(&(p.to_lowercase() + "/"))
}
fn validate_destination(entries: &[IndexedEntry], p: &str) -> Result<()> {
    let parent = parent(p);
    ensure!(
        parent.is_empty() || entries.iter().any(|e| e.directory && has_path(e, parent)),
        "Parent directory not found"
    );
    ensure!(
        !entries.iter().any(|e| has_path(e, p)),
        "An entry with that name already exists"
    );
    ensure!(entries.len() < 1_000_000, "Archive entry limit exceeded");
    Ok(())
}

/// JSON commands: mkdir(path), rename(path,name), delete(path), undo, redo.
pub fn edit(handle: i64, command: &str) -> Result<()> {
    let a = get(handle)?;
    editable(&a)?;
    let cmd: Value = serde_json::from_str(command)?;
    let op = cmd["op"].as_str().context("Missing edit operation")?;
    // NG table encryption depends on the nested archive's basename. Rename an
    // NG child as a streamed OPEN snapshot, retaining one atomic undo step.
    let nested_rename = if op == "rename" {
        let p = path(cmd["path"].as_str().context("Missing archive path")?)?;
        let new_name = cmd["name"].as_str().context("Missing new name")?;
        name(new_name)?;
        let (entry, revision) = {
            let state = a.edits.lock().unwrap_or_else(|e| e.into_inner());
            (
                state
                    .current
                    .entries
                    .iter()
                    .find(|e| has_path(e, &p))
                    .cloned()
                    .context("Entry not found")?,
                state.current.revision,
            )
        };
        if !entry.directory
            && entry.name.to_ascii_lowercase().ends_with(".rpf")
            && entry.name != new_name
        {
            let child_handle = open_nested(handle, &entry.path)?;
            let child = get(child_handle);
            close(child_handle);
            let child = child?;
            if child.parsed.encryption == RpfEncryption::Ng {
                let snapshot = fork(&child)?;
                let size = Plan::new(snapshot.clone(), 0)?.size;
                Some((revision, size, snapshot))
            } else {
                None
            }
        } else {
            None
        }
    } else {
        None
    };
    let mut h = a.edits.lock().unwrap_or_else(|e| e.into_inner());
    if op == "begin_batch" {
        ensure!(h.batch.is_none(), "A batch edit is already running");
        h.batch = Some(h.current.clone());
        return Ok(());
    }
    if op == "end_batch" {
        let before = h.batch.take().context("No batch edit is running")?;
        if before.revision != h.current.revision {
            h.past.push(before);
            h.trim_history();
            h.future.clear();
        }
        return Ok(());
    }
    if let Some((revision, _, _)) = &nested_rename {
        ensure!(
            h.current.revision == *revision,
            "Workspace changed during rename; try again"
        );
    }
    if op == "undo" || op == "redo" {
        ensure!(h.batch.is_none(), "Finish the batch edit before changing history");
        let rev = if op == "undo" {
            h.past.pop()
        } else {
            h.future.pop()
        }
        .context("No history available")?;
        let old = std::mem::replace(&mut h.current, rev);
        if op == "undo" {
            h.future.push(old);
        } else {
            h.past.push(old);
        }
        return Ok(());
    }
    let p = path(cmd["path"].as_str().context("Missing archive path")?)?;
    let mut entries = h.current.entries.clone();
    match op {
        "mkdir" => {
            validate_destination(&entries, &p)?;
            entries.push(IndexedEntry {
                name: p.rsplit('/').next().unwrap().into(),
                parent: parent(&p).into(),
                path: p,
                index: usize::MAX,
                directory: true,
                size: 0,
                replacement: None,
            });
        }
        "delete" => {
            ensure!(entries.iter().any(|e| has_path(e, &p)), "Entry not found");
            entries.retain(|e| !inside(e, &p));
        }
        "rename" => {
            let new = cmd["name"].as_str().context("Missing new name")?;
            name(new)?;
            let old = entries
                .iter()
                .find(|e| has_path(e, &p))
                .context("Entry not found")?
                .path
                .clone();
            let new_path = if parent(&old).is_empty() {
                new.into()
            } else {
                format!("{}/{new}", parent(&old))
            };
            if old == new_path {
                return Ok(());
            }
            ensure!(
                !entries
                    .iter()
                    .any(|e| !has_path(e, &old) && has_path(e, &new_path)),
                "An entry with that name already exists"
            );
            for e in &mut entries {
                if inside(e, &old) {
                    if has_path(e, &old) {
                        if let Some((_, size, child)) = &nested_rename {
                            e.size = *size;
                            e.replacement = Some(Replacement::Nested(child.clone()));
                        }
                    }
                    e.path = format!("{new_path}{}", &e.path[old.len()..]);
                    e.parent = parent(&e.path).into();
                    e.name = e.path.rsplit('/').next().unwrap().into();
                }
            }
        }
        _ => bail!("Unknown edit operation"),
    }
    h.commit(entries);
    Ok(())
}

/// Caller stages only the imported/edited file, then Rust retains its descriptor.
/// This keeps undo stable if the external document is later changed or removed.
#[cfg(test)]
pub fn edit_file(handle: i64, target: &str, fd: RawFd, replace: bool) -> Result<()> {
    edit_named_file(
        handle,
        target,
        fd,
        replace,
        target.rsplit('/').next().unwrap_or(target),
    )
}

pub fn edit_named_file(
    handle: i64,
    target: &str,
    fd: RawFd,
    replace: bool,
    source_name: &str,
) -> Result<()> {
    let a = get(handle)?;
    editable(&a)?;
    let p = path(target)?;
    let file = Arc::new(duplicate(fd)?);
    let mut size = file.metadata()?.len();
    ensure!(
        size <= u32::MAX as u64,
        "File exceeds RPF7's 4 GiB entry limit"
    );
    file_header(&file, size)?;
    let mut replacement = Replacement::File(file.clone());
    if size >= 16 && source_name != p.rsplit('/').next().unwrap_or(&p) {
        let mut header = [0; 16];
        file.read_exact_at(&mut header, 0)?;
        if header[..4] == rpf_archive::RPF7_MAGIC.to_le_bytes()
            && header[12..16] == RpfEncryption::Ng.as_u32().to_le_bytes()
        {
            let child_handle = open_file(file.try_clone()?, source_name)?;
            let child = get(child_handle);
            close(child_handle);
            let child = child?;
            let child = fork(&child)?;
            size = Plan::new(child.clone(), 0)?.size;
            ensure!(
                size <= u32::MAX as u64,
                "Nested RPF exceeds the 4 GiB entry limit"
            );
            replacement = Replacement::Nested(child);
        }
    }
    let mut h = a.edits.lock().unwrap_or_else(|e| e.into_inner());
    let mut entries = h.current.entries.clone();
    if replace {
        let entry = entries
            .iter_mut()
            .find(|e| has_path(e, &p) && !e.directory)
            .context("File not found")?;
        entry.size = size;
        entry.replacement = Some(replacement);
    } else {
        validate_destination(&entries, &p)?;
        entries.push(IndexedEntry {
            name: p.rsplit('/').next().unwrap().into(),
            parent: parent(&p).into(),
            path: p,
            index: usize::MAX,
            directory: false,
            size,
            replacement: Some(replacement),
        });
    }
    h.commit(entries);
    Ok(())
}

/// Attach an immutable child snapshot, so later child edits cannot rewrite parent history.
pub fn attach_nested(parent_handle: i64, target: &str, child_handle: i64) -> Result<()> {
    ensure!(
        parent_handle != child_handle,
        "Cannot attach an archive to itself"
    );
    let a = get(parent_handle)?;
    let child = get(child_handle)?;
    editable(&a)?;
    let p = path(target)?;
    let snapshot = fork(&child)?;
    let size = Plan::new(snapshot.clone(), 0)?.size;
    let mut h = a.edits.lock().unwrap_or_else(|e| e.into_inner());
    let mut entries = h.current.entries.clone();
    let entry = entries
        .iter_mut()
        .find(|e| !e.directory && has_path(e, &p))
        .context("Nested archive not found")?;
    entry.size = size;
    entry.replacement = Some(Replacement::Nested(snapshot));
    h.commit(entries);
    Ok(())
}

const BUFFER: usize = 256 * 1024;
pub(super) fn copy_file(
    file: &File,
    size: u64,
    output: &mut impl Write,
    cancel: &AtomicBool,
) -> Result<()> {
    copy_region(size, output, cancel, |off, data| {
        file.read_exact_at(data, off)?;
        Ok(())
    })
}
fn copy_region(
    size: u64,
    out: &mut impl Write,
    cancel: &AtomicBool,
    mut read: impl FnMut(u64, &mut [u8]) -> Result<()>,
) -> Result<()> {
    let mut buffer = vec![0; BUFFER];
    let mut offset = 0;
    while offset < size {
        ensure!(!cancel.load(Ordering::Relaxed), "Operation cancelled");
        let n = (size - offset).min(BUFFER as u64) as usize;
        read(offset, &mut buffer[..n])?;
        out.write_all(&buffer[..n])?;
        offset += n as u64;
    }
    Ok(())
}
#[derive(Clone, Copy)]
struct Format {
    packed: u32,
    logical: u32,
    resource: Option<(u32, u32)>,
}
fn file_header(file: &File, size: u64) -> Result<Option<(u32, u32)>> {
    if size < 4 {
        return Ok(None);
    }
    let mut magic = [0; 4];
    file.read_exact_at(&mut magic, 0)?;
    if u32::from_le_bytes(magic) != rpf_archive::RSC7_MAGIC {
        return Ok(None);
    }
    ensure!(size >= 16, "Truncated RSC7 header");
    let mut header = [0; 16];
    file.read_exact_at(&mut header, 0)?;
    let sys = u32::from_le_bytes(header[8..12].try_into()?);
    let gfx = u32::from_le_bytes(header[12..16].try_into()?);
    Ok(Some((sys, gfx)))
}
enum Payload {
    Original(usize),
    File(Arc<File>),
    Nested(Box<Plan>),
}
struct PlannedFile {
    offset: u64,
    length: u64,
    format: Format,
    source: Payload,
}
struct Plan {
    archive: Arc<Archive>,
    header: Vec<u8>,
    files: Vec<PlannedFile>,
    size: u64,
    revision: u64,
}
fn align(n: u64, to: u64) -> u64 {
    n.div_ceil(to) * to
}
impl Plan {
    fn new(archive: Arc<Archive>, depth: usize) -> Result<Self> {
        editable(&archive)?;
        ensure!(depth <= 24, "Nested archive depth limit reached");
        let state = archive.edits.lock().unwrap_or_else(|e| e.into_inner());
        let entries = state.current.entries.clone();
        let revision = state.current.revision;
        drop(state);
        let mut children: HashMap<String, Vec<usize>> = HashMap::new();
        for (i, e) in entries.iter().enumerate() {
            children.entry(e.parent.to_lowercase()).or_default().push(i);
        }
        for list in children.values_mut() {
            list.sort_by_key(|i| entries[*i].name.to_lowercase());
        }
        // Every directory's immediate children occupy one contiguous table range.
        let mut flat = vec![None];
        let mut dirs = HashMap::new();
        let mut queue = VecDeque::from([(0usize, String::new())]);
        while let Some((idx, parent)) = queue.pop_front() {
            let list = children
                .get(&parent.to_lowercase())
                .cloned()
                .unwrap_or_default();
            dirs.insert(idx, (flat.len(), list.len()));
            for i in list {
                let next = flat.len();
                flat.push(Some(i));
                if entries[i].directory {
                    queue.push_back((next, entries[i].path.clone()));
                }
            }
        }
        ensure!(flat.len() == entries.len() + 1, "Orphaned archive entry");
        let mut names = Vec::new();
        let mut offsets = Vec::new();
        let mut dedup = HashMap::new();
        for i in &flat {
            let name = i.map_or("", |i| entries[i].name.as_str());
            let off = *dedup.entry(name).or_insert_with(|| {
                let n = names.len();
                names.extend_from_slice(name.as_bytes());
                names.push(0);
                n
            });
            ensure!(
                off <= u16::MAX as usize,
                "RPF7 name table exceeds its 64 KiB offset limit"
            );
            offsets.push(off as u32);
        }
        names.resize(align(names.len() as u64, 16) as usize, 0);
        let mut header = Vec::new();
        header.extend(rpf_archive::RPF7_MAGIC.to_le_bytes());
        header.extend((flat.len() as u32).to_le_bytes());
        header.extend((names.len() as u32).to_le_bytes());
        header.extend(RpfEncryption::Open.as_u32().to_le_bytes());
        let mut offset = align(16 + flat.len() as u64 * 16 + names.len() as u64, 512);
        let mut files = Vec::new();
        for (index, source_index) in flat.iter().enumerate() {
            if let Some(&(start, count)) = dirs.get(&index) {
                header.extend(offsets[index].to_le_bytes());
                header.extend(0x7fffff00u32.to_le_bytes());
                header.extend((start as u32).to_le_bytes());
                header.extend((count as u32).to_le_bytes());
                continue;
            }
            let e = &entries[source_index.context("Missing file entry")?];
            let (source, length, format) = match &e.replacement {
                Some(Replacement::File(file)) => {
                    ensure!(
                        file.metadata()?.len() == e.size,
                        "Imported file changed after staging"
                    );
                    let resource = file_header(file, e.size)?;
                    (
                        Payload::File(file.clone()),
                        e.size,
                        Format {
                            packed: 0,
                            logical: e.size as u32,
                            resource,
                        },
                    )
                }
                Some(Replacement::Nested(child)) => {
                    let plan = Plan::new(child.clone(), depth + 1)?;
                    let n = plan.size;
                    ensure!(
                        n <= u32::MAX as u64,
                        "Nested RPF exceeds the 4 GiB entry limit"
                    );
                    (
                        Payload::Nested(Box::new(plan)),
                        n,
                        Format {
                            packed: 0,
                            logical: n as u32,
                            resource: None,
                        },
                    )
                }
                None => match archive.parsed.entries[e.index].kind {
                    RpfEntryKind::BinaryFile {
                        file_size,
                        uncompressed_size,
                        ..
                    } => (
                        Payload::Original(e.index),
                        if file_size == 0 {
                            uncompressed_size
                        } else {
                            file_size
                        } as u64,
                        Format {
                            packed: file_size,
                            logical: uncompressed_size,
                            resource: None,
                        },
                    ),
                    RpfEntryKind::ResourceFile {
                        file_size,
                        system_flags,
                        graphics_flags,
                        ..
                    } => (
                        Payload::Original(e.index),
                        file_size as u64,
                        Format {
                            packed: 0,
                            logical: file_size,
                            resource: Some((system_flags, graphics_flags)),
                        },
                    ),
                    _ => bail!("Invalid file source"),
                },
            };
            ensure!(
                offset / 512 < 0x800000,
                "RPF7 archive exceeds its 4 GiB block offset limit"
            );
            if format.resource.is_some() {
                ensure!(length >= 16, "Truncated resource");
            }
            if let Payload::Original(i) = &source {
                let block = match archive.parsed.entries[*i].kind {
                    RpfEntryKind::BinaryFile { file_offset, .. }
                    | RpfEntryKind::ResourceFile { file_offset, .. } => file_offset,
                    _ => unreachable!(),
                };
                ensure!(
                    (archive.parsed.start_offset as u64 + block as u64 * 512 + length)
                        <= archive.bytes().len() as u64,
                    "File outside archive: {}",
                    e.path
                );
            }
            let packed = if format.resource.is_some() {
                length.min(0xffffff) as u32
            } else {
                format.packed
            };
            let block = (offset / 512) as u32;
            header.extend((offsets[index] as u16).to_le_bytes());
            header.extend(&packed.to_le_bytes()[..3]);
            let mut encoded = block.to_le_bytes();
            if format.resource.is_some() {
                encoded[2] |= 0x80;
            }
            header.extend(&encoded[..3]);
            let (a, b) = format.resource.unwrap_or((format.logical, 0));
            header.extend(a.to_le_bytes());
            header.extend(b.to_le_bytes());
            files.push(PlannedFile {
                offset,
                length,
                format,
                source,
            });
            offset = align(offset + length, 512);
        }
        header.extend(names);
        let header_size = align(header.len() as u64, 512) as usize;
        header.resize(header_size, 0);
        Ok(Self {
            archive,
            header,
            files,
            size: offset,
            revision,
        })
    }
    fn write(&self, out: &mut impl Write, cancel: &AtomicBool) -> Result<()> {
        ensure!(!cancel.load(Ordering::Relaxed), "Operation cancelled");
        out.write_all(&self.header)?;
        let mut written = self.header.len() as u64;
        for file in &self.files {
            ensure!(written == file.offset, "Invalid write plan");
            match &file.source {
                Payload::Nested(plan) => plan.write(out, cancel)?,
                Payload::File(source) => {
                    if let Some((sys, gfx)) = file.format.resource {
                        out.write_all(&resource_header(sys, gfx, file.length))?;
                        copy_region(file.length - 16, out, cancel, |off, buf| {
                            source.read_exact_at(buf, off + 16)?;
                            Ok(())
                        })?;
                    } else {
                        copy_file(source, file.length, out, cancel)?;
                    }
                }
                Payload::Original(i) => {
                    let entry = &self.archive.parsed.entries[*i];
                    let (block, encrypted, key_length) = match entry.kind {
                        RpfEntryKind::BinaryFile {
                            file_offset,
                            is_encrypted,
                            uncompressed_size,
                            ..
                        } => (file_offset, is_encrypted, uncompressed_size),
                        RpfEntryKind::ResourceFile {
                            file_offset,
                            is_encrypted,
                            file_size,
                            ..
                        } => (file_offset, is_encrypted, file_size),
                        _ => unreachable!(),
                    };
                    let skip = if let Some((sys, gfx)) = file.format.resource {
                        out.write_all(&resource_header(sys, gfx, file.length))?;
                        16
                    } else {
                        0
                    };
                    let start = self.archive.parsed.start_offset + block as usize * 512 + skip;
                    let raw = &self.archive.bytes()[start..start + file.length as usize - skip];
                    copy_region(raw.len() as u64, out, cancel, |off, buf| {
                        let bytes = &raw[off as usize..off as usize + buf.len()];
                        if encrypted && self.archive.parsed.encryption.is_encrypted() {
                            let keys = key_guard();
                            let keys = keys.as_ref().context("Encrypted entry requires GTA V keys")?;
                            let plain = match self.archive.parsed.encryption {
                                RpfEncryption::Aes => rpf_archive::crypto::decrypt_aes(bytes, &keys.aes_key),
                                RpfEncryption::Ng => rpf_archive::crypto::decrypt_ng(
                                    bytes, keys, &entry.name, key_length,
                                ),
                                _ => bail!("Unsupported archive encryption"),
                            };
                            buf.copy_from_slice(&plain);
                        } else {
                            buf.copy_from_slice(bytes);
                        }
                        Ok(())
                    })?;
                }
            }
            written += file.length;
            let padded = align(written, 512);
            out.write_all(&[0; 512][..(padded - written) as usize])?;
            written = padded;
        }
        ensure!(written == self.size, "Incomplete archive write");
        Ok(())
    }
    fn reject_source(&self, target: &std::fs::Metadata) -> Result<()> {
        let same = |file: &File| -> Result<()> {
            let m = file.metadata()?;
            ensure!(
                m.dev() != target.dev() || m.ino() != target.ino(),
                "Choose a new file; overwriting an open source is not allowed"
            );
            Ok(())
        };
        if let Backing::Mapped(_, f) = &*self.archive.data {
            same(f)?;
        }
        // Include staged sources retained only in undo/redo, not just this revision.
        let h = self.archive.edits.lock().unwrap_or_else(|e| e.into_inner());
        for rev in std::iter::once(&h.current).chain(&h.past).chain(&h.future) {
            for entry in &rev.entries {
                if let Some(Replacement::File(f)) = &entry.replacement {
                    same(f)?;
                }
            }
        }
        drop(h);
        for file in &self.files {
            if let Payload::Nested(p) = &file.source {
                p.reject_source(target)?;
            }
        }
        Ok(())
    }
}
pub(super) fn resource_header(sys: u32, gfx: u32, length: u64) -> [u8; 16] {
    let mut header = [0; 16];
    header[..4].copy_from_slice(&rpf_archive::RSC7_MAGIC.to_le_bytes());
    header[4..8].copy_from_slice(&resource_version_from_flags(sys, gfx).to_le_bytes());
    header[8..12].copy_from_slice(&sys.to_le_bytes());
    header[12..16].copy_from_slice(&gfx.to_le_bytes());
    if length >= 0xffffff {
        let n = (length as u32).to_le_bytes();
        header[7] = n[0];
        header[14] = n[1];
        header[5] = n[2];
        header[2] = n[3];
    }
    header
}
pub(super) fn write_archive(a: &Arc<Archive>, output: &mut impl Write) -> Result<()> {
    let plan = Plan::new(a.clone(), 0)?;
    plan.write(output, &a.cancelled)
}
pub(super) fn write_archive_cancellable(
    a: &Arc<Archive>,
    output: &mut impl Write,
    cancelled: &AtomicBool,
) -> Result<()> {
    let plan = Plan::new(a.clone(), 0)?;
    plan.write(output, cancelled)
}
pub(super) fn to_bytes(a: &Arc<Archive>, limit: usize) -> Result<Vec<u8>> {
    let plan = Plan::new(a.clone(), 0)?;
    ensure!(
        plan.size <= limit as u64,
        "Edited archive exceeds the preview limit; save it to a new file"
    );
    let mut bytes = Vec::with_capacity(plan.size as usize);
    plan.write(&mut bytes, &AtomicBool::new(false))?;
    Ok(bytes)
}
/// SAF must open with "rw" (no truncation); validate identity before any write.
pub fn save_fd(handle: i64, fd: RawFd) -> Result<()> {
    serialize_fd(handle, fd, true)
}
/// Stage a verified publication without changing the source workspace's saved revision.
pub fn stage_fd(handle: i64, fd: RawFd) -> Result<()> {
    serialize_fd(handle, fd, false)
}
fn serialize_fd(handle: i64, fd: RawFd, mark_saved: bool) -> Result<()> {
    let a = get(handle)?;
    let plan = Plan::new(a.clone(), 0)?;
    let mut target = duplicate(fd)?;
    let metadata = target.metadata()?;
    plan.reject_source(&metadata)?;
    // Also protect unrelated open workspaces and standalone documents.
    for source in archives().lock().unwrap().values() {
        if let Backing::Mapped(_, f) = &*source.data {
            let m = f.metadata()?;
            ensure!(
                m.dev() != metadata.dev() || m.ino() != metadata.ino(),
                "Choose a new file; this file is already open"
            );
        }
    }
    for f in documents().lock().unwrap().values() {
        let m = f.metadata()?;
        ensure!(
            m.dev() != metadata.dev() || m.ino() != metadata.ino(),
            "Choose a new file; this document is already open"
        );
    }
    ensure!(!a.cancelled.load(Ordering::Relaxed), "Operation cancelled");
    target.seek(SeekFrom::Start(0))?;
    target.set_len(0)?;
    plan.write(&mut target, &a.cancelled)?;
    target.flush()?;
    target.sync_all()?;
    if mark_saved {
        a.edits.lock().unwrap_or_else(|e| e.into_inner()).saved = plan.revision;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::fd::AsRawFd;
    #[test]
    fn imported_aes_payload_survives_edit_save_and_missing_keys_fail() {
        // Global imported keys are process state. Isolate this scenario from
        // other parallel tests, including the shared-reader lock regression.
        const CHILD: &str = "RPF_IMPORTED_KEY_TEST_CHILD";
        if std::env::var_os(CHILD).is_none() {
            let status = std::process::Command::new(std::env::current_exe().unwrap())
                .args(["--exact", "store::editor::tests::imported_aes_payload_survives_edit_save_and_missing_keys_fail", "--nocapture"])
                .env(CHILD, "1").status().unwrap();
            assert!(status.success());
            return;
        }
        use rpf_archive::{RpfBuilder, crypto::encrypt_aes};
        let keys = GtaKeys {
            aes_key: [0x5a; 32],
            ng_keys: vec![vec![0; 272]; 101],
            ng_decrypt_tables: vec![[[0; 256]; 16]; 17].into_boxed_slice().try_into().unwrap(),
        };
        // An uncompressed payload spans two streaming buffers and a partial AES block.
        let expected: Vec<u8> = (0..300_019).map(|n| (n * 31) as u8).collect();
        let mut builder = RpfBuilder::new(RpfEncryption::Open);
        builder.add_file("payload.bin", expected.clone());
        let mut bytes = builder.build(None).unwrap();
        let parsed = RpfArchive::parse(&bytes, "encrypted.rpf", None).unwrap();
        let entry = parsed.entries.iter().find(|e| e.name == "payload.bin").unwrap();
        let RpfEntryKind::BinaryFile { file_offset, file_size, .. } = entry.kind else { panic!() };
        let offset = file_offset as usize * 512;
        assert_eq!(file_size as usize, expected.len());
        bytes[offset..offset + expected.len()].copy_from_slice(&encrypt_aes(&expected, &keys.aes_key));
        // First file follows the root directory. Set its per-file encrypted flag.
        bytes[44..48].copy_from_slice(&1u32.to_le_bytes());
        let names_start = 16 + parsed.entries.len() * 16;
        let names_end = names_start + u32::from_le_bytes(bytes[8..12].try_into().unwrap()) as usize;
        for range in [16..names_start, names_start..names_end] {
            let encrypted = encrypt_aes(&bytes[range.clone()], &keys.aes_key);
            bytes[range].copy_from_slice(&encrypted);
        }
        bytes[12..16].copy_from_slice(&RpfEncryption::Aes.as_u32().to_le_bytes());
        let source = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(source.path(), &bytes).unwrap();
        assert!(open_fd(source.as_file().as_raw_fd(), "encrypted.rpf").is_err());
        let key_dir = tempfile::tempdir().unwrap();
        keys.save_to_path(key_dir.path()).unwrap();
        load_keys(key_dir.path().to_str().unwrap()).unwrap();
        let handle = open_fd(source.as_file().as_raw_fd(), "encrypted.rpf").unwrap();
        assert_eq!(read(handle, "payload.bin").unwrap(), expected);
        command(handle, json!({"op":"rename", "path":"payload.bin", "name":"renamed.bin"}));
        let output = tempfile::NamedTempFile::new().unwrap();
        save_fd(handle, output.as_file().as_raw_fd()).unwrap();
        clear_keys().unwrap();
        let saved = open_fd(output.as_file().as_raw_fd(), "saved.rpf").unwrap();
        assert_eq!(read(saved, "renamed.bin").unwrap(), expected);
        // Removing keys from an open encrypted workspace must not copy ciphertext
        // into an apparently successful OPEN output.
        let plan = Plan::new(get(handle).unwrap(), 0).unwrap();
        let error = plan.write(&mut Vec::new(), &AtomicBool::new(false)).unwrap_err();
        assert!(error.to_string().contains("requires GTA V keys"));
        assert_eq!(std::fs::read(source.path()).unwrap(), bytes);
        close(saved);
        close(handle);
    }

    #[test]
    fn staging_preserves_dirty_revision_and_original_until_publication() {
        let (source, h) = sample();
        let original = std::fs::read(source.path()).unwrap();
        command(h, json!({"op":"mkdir","path":"pending"}));
        let staged = tempfile::NamedTempFile::new().unwrap();
        stage_fd(h, staged.as_file().as_raw_fd()).unwrap();
        assert_eq!(info(h).unwrap()["dirty"], true);
        assert!(stage_fd(h, source.as_file().as_raw_fd()).is_err());
        assert_eq!(std::fs::read(source.path()).unwrap(), original);
        let candidate = open_fd(staged.as_file().as_raw_fd(), "candidate.rpf").unwrap();
        assert!(snapshot(&get(candidate).unwrap()).iter().any(|entry| entry.path == "pending"));
        command(h, json!({"op":"undo"}));
        assert_eq!(info(h).unwrap()["dirty"], false);
        close(candidate);
        close(h);
    }
    #[test]
    fn cancelled_save_keeps_destination_and_dirty_history() {
        let (_source, h) = sample();
        command(h, json!({"op":"mkdir","path":"changed"}));
        let target = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(target.path(), b"destination").unwrap();
        cancel(h);
        assert!(save_fd(h, target.as_file().as_raw_fd()).is_err());
        assert_eq!(std::fs::read(target.path()).unwrap(), b"destination");
        assert_eq!(info(h).unwrap()["dirty"], true);
        reset_cancel(h);
        save_fd(h, target.as_file().as_raw_fd()).unwrap();
        assert_eq!(info(h).unwrap()["dirty"], false);
        close(h);
    }
    #[test]
    fn files_above_preview_limit_are_saved_with_bounded_writes() {
        let (_source, h) = sample();
        let large = tempfile::NamedTempFile::new().unwrap();
        large.as_file().set_len(PREVIEW_LIMIT as u64 + 512).unwrap();
        edit_file(h, "large.bin", large.as_file().as_raw_fd(), false).unwrap();
        assert!(read(h, "large.bin").is_err());
        struct Counter {
            bytes: u64,
            largest: usize,
        }
        impl Write for Counter {
            fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
                self.bytes += bytes.len() as u64;
                self.largest = self.largest.max(bytes.len());
                Ok(bytes.len())
            }
            fn flush(&mut self) -> std::io::Result<()> {
                Ok(())
            }
        }
        let a = get(h).unwrap();
        let plan = Plan::new(a.clone(), 0).unwrap();
        let mut sink = Counter {
            bytes: 0,
            largest: 0,
        };
        plan.write(&mut sink, &a.cancelled).unwrap();
        assert_eq!(sink.bytes, plan.size);
        assert!(sink.bytes > PREVIEW_LIMIT as u64);
        assert!(sink.largest <= 256 * 1024);
        close(h);
    }
    fn sample() -> (tempfile::NamedTempFile, i64) {
        let f = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(f.path(), crate::fixtures::archive()).unwrap();
        let h = open_fd(f.as_file().as_raw_fd(), "sample.rpf").unwrap();
        (f, h)
    }
    fn command(h: i64, c: Value) {
        edit(h, &c.to_string()).unwrap();
    }
    #[test]
    fn batch_retains_all_successful_changes_in_one_undo_step() {
        let (source, h) = sample();
        let original = std::fs::read(source.path()).unwrap();
        command(h, json!({"op":"mkdir","path":"earlier"}));
        command(h, json!({"op":"begin_batch"}));
        assert!(edit(h, &json!({"op":"begin_batch"}).to_string()).is_err());
        assert!(edit(h, &json!({"op":"undo"}).to_string()).is_err());
        // More operations than the regular history cap must still undo together.
        for i in 0..60 {
            command(h, json!({"op":"mkdir","path":format!("batch-{i}")}));
        }
        let payload = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(payload.path(), b"batch payload").unwrap();
        edit_file(h, "batch-0/item.txt", payload.as_file().as_raw_fd(), false).unwrap();
        assert!(edit(h, &json!({"op":"delete","path":"does-not-exist"}).to_string()).is_err());
        command(h, json!({"op":"end_batch"}));
        assert_eq!(read(h, "batch-0/item.txt").unwrap(), b"batch payload");
        command(h, json!({"op":"undo"}));
        let entries = snapshot(&get(h).unwrap());
        assert!(entries.iter().any(|entry| entry.path == "earlier"));
        assert!(!entries.iter().any(|entry| entry.path.starts_with("batch-")));
        command(h, json!({"op":"redo"}));
        assert_eq!(read(h, "batch-0/item.txt").unwrap(), b"batch payload");
        command(h, json!({"op":"undo"}));
        command(h, json!({"op":"undo"}));
        assert_eq!(info(h).unwrap()["modified"], false);
        assert_eq!(std::fs::read(source.path()).unwrap(), original);
        close(h);
    }
    #[test]
    fn empty_or_failed_batch_preserves_redo_history() {
        let (_source, h) = sample();
        command(h, json!({"op":"mkdir","path":"redo-me"}));
        command(h, json!({"op":"undo"}));
        command(h, json!({"op":"begin_batch"}));
        assert!(edit(h, &json!({"op":"delete","path":"missing"}).to_string()).is_err());
        command(h, json!({"op":"end_batch"}));
        assert_eq!(info(h).unwrap()["undo"], false);
        assert_eq!(info(h).unwrap()["redo"], true);
        command(h, json!({"op":"redo"}));
        assert!(snapshot(&get(h).unwrap()).iter().any(|entry| entry.path == "redo-me"));
        close(h);
    }
    #[test]
    fn edits_round_trip_preserve_resources_and_original_and_history() {
        let (f, h) = sample();
        let original = std::fs::read(f.path()).unwrap();
        let model = read(h, "rover.ydr").unwrap();
        command(h, json!({"op":"mkdir","path":"新建"}));
        let staged = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(staged.path(), "hello\n世界").unwrap();
        edit_file(h, "新建/文本.txt", staged.as_file().as_raw_fd(), false).unwrap();
        command(h, json!({"op":"rename","path":"新建","name":"改名"}));
        command(h, json!({"op":"delete","path":"rover.ydr"}));
        assert!(read(h, "rover.ydr").is_err());
        command(h, json!({"op":"undo"}));
        assert_eq!(read(h, "rover.ydr").unwrap(), model);
        command(h, json!({"op":"redo"}));
        command(h, json!({"op":"undo"}));
        let out = tempfile::NamedTempFile::new().unwrap();
        save_fd(h, out.as_file().as_raw_fd()).unwrap();
        assert_eq!(info(h).unwrap()["dirty"], false);
        let saved = open_fd(out.as_file().as_raw_fd(), "saved.rpf").unwrap();
        assert_eq!(
            read(saved, "改名/文本.txt").unwrap(),
            "hello\n世界".as_bytes()
        );
        assert_eq!(read(saved, "rover.ydr").unwrap(), model);
        assert_eq!(std::fs::read(f.path()).unwrap(), original);
        assert!(save_fd(h, f.as_file().as_raw_fd()).is_err());
        assert_eq!(std::fs::read(f.path()).unwrap(), original);
        close(saved);
        close(h);
    }
    #[test]
    fn nested_snapshots_are_independent_and_survive_child_close() {
        let (_f, h) = sample();
        let child = open_nested(h, "nested.rpf").unwrap();
        command(
            child,
            json!({"op":"rename","path":"readme.txt","name":"changed.txt"}),
        );
        attach_nested(h, "nested.rpf", child).unwrap();
        command(child, json!({"op":"delete","path":"changed.txt"}));
        close(child);
        let out = tempfile::NamedTempFile::new().unwrap();
        save_fd(h, out.as_file().as_raw_fd()).unwrap();
        let saved = open_fd(out.as_file().as_raw_fd(), "saved.rpf").unwrap();
        let nested = open_nested(saved, "nested.rpf").unwrap();
        assert!(!read(nested, "changed.txt").unwrap().is_empty());
        command(h, json!({"op":"undo"}));
        let old = open_nested(h, "nested.rpf").unwrap();
        assert!(read(old, "readme.txt").is_ok());
        for id in [h, saved, nested, old] {
            close(id);
        }
    }
    #[test]
    fn bad_names_and_conflicts_do_not_change_history() {
        let (_f, h) = sample();
        for p in ["../x", "a//b", "/absolute", "a\u{0}", "rover.ydr"] {
            assert!(edit(h, &json!({"op":"mkdir","path":p}).to_string()).is_err());
        }
        assert_eq!(info(h).unwrap()["dirty"], false);
        command(h, json!({"op":"mkdir","path":"empty"}));
        let bytes = to_bytes(&get(h).unwrap(), PREVIEW_LIMIT).unwrap();
        let out = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(out.path(), bytes).unwrap();
        let saved = open_fd(out.as_file().as_raw_fd(), "empty.rpf").unwrap();
        assert_eq!(list(saved, "empty").unwrap(), json!([]));
        close(saved);
        close(h);
    }
}
