//! Shared archive conversion, verification and reporting for Android and Linux.
use super::*;
use crate::transcode::{self, Counts, Settings, Transcoder};
use rayon::prelude::*;
use std::collections::HashSet;
use std::{cell::RefCell, os::unix::fs::MetadataExt, sync::atomic::AtomicU64, time::Instant};
#[path = "conversion_fd.rs"]
mod fd;
pub use fd::{batch_active, begin_batch, convert_fd, end_batch, progress, FdOptions};
#[path = "astc_preview.rs"]
mod preview;
pub use preview::{texture_list, texture_preview, texture_results};
#[path = "conversion/mip_trim.rs"]
mod mip_trim;
pub use mip_trim::{trim_mips, TrimOptions};

#[derive(Clone)]
pub struct ConvertOptions {
    pub input: PathBuf,
    pub output: Option<PathBuf>,
    pub in_place: bool,
    pub archives: Vec<PathBuf>,
    pub settings: Settings,
    pub jobs: usize,
    pub samples: Option<PathBuf>,
    pub dry_run: bool,
    pub repair_pages: bool,
    pub keep_going: bool,
    pub require_no_bc: bool,
}

#[derive(Default)]
struct Summary {
    archives: u64,
    resources: u64,
    counts: Counts,
    errors: Vec<Value>,
}
impl Summary {
    fn add_counts(&mut self, c: &Counts) -> Result<()> {
        self.counts.size_estimate.add(&c.size_estimate)?;
        self.counts.textures += c.textures;
        self.counts.converted += c.converted;
        self.counts.repaired_resources += c.repaired_resources;
        self.counts.already_astc += c.already_astc;
        self.counts.remaining_bc += c.remaining_bc;
        self.counts.convertible_bc += c.convertible_bc;
        self.counts.blocked_bc += c.blocked_bc;
        self.counts.blocked_bc_samples.extend(
            c.blocked_bc_samples
                .iter()
                .take(16 - self.counts.blocked_bc_samples.len())
                .cloned(),
        );
        for (format, count) in &c.formats {
            *self.counts.formats.entry(format.clone()).or_default() += count;
        }
        self.counts.bc_bytes += c.bc_bytes;
        self.counts.astc_bytes += c.astc_bytes;
        for (k, v) in &c.retained {
            *self.counts.retained.entry(k.clone()).or_default() += v;
        }
        Ok(())
    }
    fn add(&mut self, other: &Self) -> Result<()> {
        self.add_counts(&other.counts)?;
        self.archives += other.archives;
        self.resources += other.resources;
        self.errors.extend(other.errors.iter().cloned());
        Ok(())
    }
    fn json(&self) -> Value {
        let estimate = &self.counts.size_estimate;
        let by_block: std::collections::BTreeMap<String, u64> = transcode::BLOCKS
            .iter()
            .map(|(w, h)| {
                let key = format!("{w}x{h}");
                let bytes = estimate.by_block.get(&key).copied().unwrap_or_default();
                (key, bytes)
            })
            .collect();
        json!({"archives":self.archives,"resources":self.resources,
        "textures":self.counts.textures,"converted":self.counts.converted,"repairedResources":self.counts.repaired_resources,"alreadyAstc":self.counts.already_astc,
        "remainingBc":self.counts.remaining_bc,"bcFree":self.errors.is_empty() && self.counts.remaining_bc == 0,
        "convertibleBc":self.counts.convertible_bc,"blockedBc":self.counts.blocked_bc,
        "blockedBcSamples":self.counts.blocked_bc_samples,"formats":self.counts.formats,
        "retained":self.counts.retained,"errors":self.errors,"sourceTextureBytes":self.counts.bc_bytes,"astcTextureBytes":self.counts.astc_bytes,
        "sizeEstimate":{"scope":"convertible-bc-payloads","complete":self.errors.is_empty(),
            "textures":estimate.textures,"payloads":estimate.payloads,"sourceBytes":estimate.source_bytes,"byBlock":by_block}})
    }
}

struct Handle(i64);
impl Drop for Handle {
    fn drop(&mut self) {
        close(self.0);
    }
}
fn open_path(path: &Path) -> Result<Handle> {
    Ok(Handle(open(
        path.to_str().context("Non-UTF8 archive path")?,
        path.file_name()
            .and_then(|n| n.to_str())
            .context("Archive name missing")?,
    )?))
}
fn parent_directory(path: &Path) -> &Path {
    path.parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."))
}
fn publish(temporary: tempfile::NamedTempFile, target: &Path, replace: bool) -> Result<()> {
    temporary.as_file().sync_all()?;
    if replace {
        temporary.persist(target).map_err(|e| e.error)?;
    } else {
        temporary.persist_noclobber(target).map_err(|e| e.error)?;
    }
    File::open(parent_directory(target))?.sync_all()?;
    Ok(())
}

pub fn import_keys(path: &Path) -> Result<()> {
    load_keys(path.to_str().context("Non-UTF8 key path")?)
}

pub fn extract_archive(input: &Path, output: &Path) -> Result<u64> {
    ensure!(!output.exists(), "Extraction destination already exists");
    let handle = open_path(input)?;
    let parent = parent_directory(output);
    std::fs::create_dir_all(parent)?;
    let stage = tempfile::Builder::new()
        .prefix(".rpf-extract-")
        .tempdir_in(parent)?;
    let count = extract_archive_to_dir(
        handle.0,
        stage.path().to_str().context("Non-UTF8 output path")?,
    )?;
    std::fs::rename(stage.path(), output)?;
    Ok(count)
}

pub fn pack_archive(input: &Path, output: &Path) -> Result<u64> {
    use std::os::fd::AsRawFd;
    ensure!(!output.exists(), "Pack destination already exists");
    let parent = parent_directory(output);
    std::fs::create_dir_all(parent)?;
    let mut temporary = tempfile::Builder::new()
        .prefix(".rpf-pack-")
        .tempfile_in(parent)?;
    let n = pack_directory(
        input.to_str().context("Non-UTF8 input path")?,
        temporary.as_file().as_raw_fd(),
    )?;
    temporary.flush()?;
    let check = open_path(temporary.path())?;
    verify_tree(&get(check.0)?, "", 0, None, None)?;
    drop(check);
    publish(temporary, output, false)?;
    Ok(n)
}

fn verify_tree(
    a: &Arc<Archive>,
    label: &str,
    depth: usize,
    allowed_errors: Option<&HashSet<String>>,
    cancelled: Option<&AtomicBool>,
) -> Result<Counts> {
    ensure!(depth <= 24, "Nested archive depth limit reached");
    let mut inventory = Counts::default();
    for entry in &a.entries {
        ensure!(
            !cancelled.is_some_and(|c| c.load(Ordering::Relaxed)),
            "Operation cancelled"
        );
        if entry.directory {
            continue;
        }
        if entry.name.to_ascii_lowercase().ends_with(".rpf") {
            let h = Handle(open_nested_entry(a, entry)?);
            let child = verify_tree(
                &get(h.0)?,
                &format!("{label}/{}", entry.path),
                depth + 1,
                allowed_errors,
                cancelled,
            )?;
            inventory.textures += child.textures;
            inventory.already_astc += child.already_astc;
            inventory.remaining_bc += child.remaining_bc;
        } else if matches!(
            a.parsed.entries[entry.index].kind,
            RpfEntryKind::ResourceFile { .. }
        ) {
            let bytes = a.parsed.extract_entry(
                a.bytes(),
                &a.parsed.entries[entry.index],
                key_guard().as_ref(),
            )?;
            let parsed =
                rpf_archive::resource::prepare_rsc7(&bytes).and_then(|(system, graphics)| {
                    transcode::textures_for_resource(&system, &graphics, &entry.name)
                });
            match parsed {
                Ok(textures) => {
                    inventory.textures += textures.len() as u64;
                    inventory.remaining_bc += textures
                        .iter()
                        .filter(|t| transcode::is_bc_format(t.format))
                        .count() as u64;
                    inventory.already_astc += textures
                        .iter()
                        .filter(|t| transcode::astc_block(t.format).is_some())
                        .count() as u64;
                }
                // Packing opaque resources does not claim to validate their
                // contents. Conversion may only retain explicitly reported errors.
                Err(_)
                    if allowed_errors
                        .is_none_or(|paths| paths.contains(&format!("{label}/{}", entry.path))) => {
                }
                Err(e) => return Err(e),
            }
        }
    }
    Ok(inventory)
}

fn verify_counts(actual: &Counts, expected: &Counts) -> Result<()> {
    ensure!(
        actual.textures == expected.textures,
        "Texture count differs after RPF round trip"
    );
    ensure!(
        actual.already_astc == expected.converted + expected.already_astc,
        "ASTC count differs after RPF round trip"
    );
    ensure!(
        actual.remaining_bc == expected.remaining_bc,
        "Remaining BC count differs after RPF round trip"
    );
    Ok(())
}

fn collect_files(root: &Path, current: &Path, out: &mut Vec<PathBuf>) -> Result<()> {
    let mut children = std::fs::read_dir(current)?.collect::<std::io::Result<Vec<_>>>()?;
    children.sort_by_key(|e| e.file_name());
    for child in children {
        let ty = child.file_type()?;
        ensure!(
            !ty.is_symlink(),
            "Input contains a symlink: {}",
            child.path().display()
        );
        if ty.is_dir() {
            collect_files(root, &child.path(), out)?;
        } else if ty.is_file() {
            out.push(child.path().strip_prefix(root)?.to_owned());
        }
    }
    Ok(())
}

thread_local! {static TRANSCODER:RefCell<Option<(Settings,Transcoder)>>=const {RefCell::new(None)};}

pub(crate) fn with_transcoder<T>(
    settings: &Settings,
    work: impl FnOnce(&mut Transcoder) -> Result<T>,
) -> Result<T> {
    TRANSCODER.with(|state| {
        let mut state = state.borrow_mut();
        if state
            .as_ref()
            .is_none_or(|(previous, _)| previous != settings)
        {
            *state = Some((settings.clone(), Transcoder::new(settings.clone())?));
        }
        work(&mut state.as_mut().unwrap().1)
    })
}

struct JobResult {
    index: usize,
    file: Option<Arc<File>>,
    size: u64,
    counts: Counts,
    error: Option<String>,
}

fn process_archive(
    a: &Arc<Archive>,
    label: &str,
    options: &ConvertOptions,
    pool: &rayon::ThreadPool,
    scratch: &Path,
    sample_counter: &AtomicU64,
    depth: usize,
    control: &fd::Control,
) -> Result<Summary> {
    ensure!(depth <= 24, "Nested archive depth limit reached");
    control.check()?;
    let mut entries = editor::snapshot(a);
    let mut summary = Summary {
        archives: 1,
        ..Summary::default()
    };
    for entry in &mut entries {
        control.check()?;
        if entry.directory || !entry.name.to_ascii_lowercase().ends_with(".rpf") {
            continue;
        }
        let h =
            Handle(open_nested_entry(a, entry).with_context(|| format!("{label}/{}", entry.path))?);
        let child = get(h.0)?;
        let result = process_archive(
            &child,
            &format!("{label}/{}", entry.path),
            options,
            pool,
            scratch,
            sample_counter,
            depth + 1,
            control,
        )?;
        if result.counts.changed() {
            // Serialize completed children immediately to release their staged
            // resource files; retain only one temporary descriptor per child.
            let mut file = tempfile::tempfile_in(scratch)?;
            editor::write_archive_cancellable(&child, &mut file, control.cancel)?;
            entry.size = file.metadata()?.len();
            entry.replacement = Some(Replacement::File(Arc::new(file)));
        }
        summary.add(&result)?;
    }
    let jobs: Vec<usize> = entries
        .iter()
        .enumerate()
        .filter_map(|(i, e)| {
            (!e.directory
                && e.replacement.is_none()
                && matches!(
                    a.parsed.entries[e.index].kind,
                    RpfEntryKind::ResourceFile { .. }
                ))
            .then_some(i)
        })
        .collect();
    let results:Result<Vec<JobResult>>=pool.install(||jobs.par_iter().map(|&i|{
        let mut task=(||->Result<JobResult>{
        control.check()?;
        let entry=&entries[i];
        control.stage(if options.repair_pages {"repairing"} else {"encoding"},&format!("{label}/{}",entry.path));
        let bytes=a.parsed.extract_entry(a.bytes(),&a.parsed.entries[entry.index],key_guard().as_ref())
            .with_context(||format!("{label}/{}",entry.path))?;
        let (system,graphics)=rpf_archive::resource::prepare_rsc7(&bytes)
            .with_context(||format!("{label}/{}",entry.path))?;
        let records=transcode::textures_for_resource(&system,&graphics,&entry.name)
            .with_context(||format!("Reading textures in {label}/{}",entry.path))?;
        if options.dry_run {
            let c=Counts::scan(&records)?;
            return Ok(JobResult{index:i,file:None,size:0,counts:c,error:None});
        }
        if records.is_empty() && !options.repair_pages {return Ok(JobResult{index:i,file:None,size:0,counts:Counts::default(),error:None});}
        let converted=if options.repair_pages {
            let counts=Counts::scan(&records)?;
            transcode::repair_pages(&bytes,system,graphics,counts)
                .with_context(||format!("Repairing resource pages in {label}/{}",entry.path))?
        } else {
            let samples=options.samples.as_ref().map(|path| {
                let n=sample_counter.fetch_add(1,Ordering::Relaxed);
                let safe: String = entry.path.chars().map(|c| {
                    if c.is_ascii_alphanumeric() || c == '_' || c == '-' { c } else { '_' }
                }).take(100).collect();
                path.join(format!("{n:05}-{safe}"))
            });
            with_transcoder(&options.settings, |encoder| {
                encoder.resource_sections_cancellable(&bytes,system,graphics,samples.as_deref(),Some(control.cancel))
            }).with_context(||format!("Transcoding {label}/{}",entry.path))?
        };
        let size=converted.bytes.len() as u64;
        let file=if converted.counts.changed() {
            let mut file=tempfile::tempfile_in(scratch)?;
            file.write_all(&converted.bytes)?;
            Some(Arc::new(file))
        } else {None};
        if converted.counts.changed() {println!("{}",json!({"event":"resource","path":format!("{label}/{}",entry.path),
            "converted":converted.counts.converted,"repairedResources":converted.counts.repaired_resources,"sourceTextureBytes":converted.counts.bc_bytes,"astcTextureBytes":converted.counts.astc_bytes}));}
        Ok(JobResult{index:i,file,size,counts:converted.counts,error:None})
        })();
        control.check()?;
        if let Ok(result)=&mut task {
            for sample in &mut result.counts.blocked_bc_samples { sample["path"]=json!(format!("{label}/{}",entries[i].path)); }
        }
        if let Ok(result)=&task {control.completed(&result.counts);}
        match task {
            Err(error) if options.keep_going=>{
                let message=format!("{error:#}");
                println!("{}",json!({"event":"retained-error","path":format!("{label}/{}",entries[i].path),"message":message}));
                Ok(JobResult{index:i,file:None,size:0,counts:Counts::default(),error:Some(message)})
            },
            result=>result,
        }
    }).collect());
    for result in results? {
        summary.resources += 1;
        summary.add_counts(&result.counts)?;
        if let Some(error) = result.error {
            summary.errors.push(
                json!({"path":format!("{label}/{}",entries[result.index].path),"message":error}),
            );
        }
        if let Some(file) = result.file {
            entries[result.index].size = result.size;
            entries[result.index].replacement = Some(Replacement::File(file));
        }
    }
    if summary.counts.changed() {
        control.check()?;
        a.edits
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .current
            .entries = entries;
    }
    Ok(summary)
}

/// Output is atomic per top-level RPF. A failed conversion never replaces that
/// RPF; earlier completed archives remain usable and can be recognized on rerun.
pub fn convert(options: ConvertOptions) -> Result<Value> {
    ensure!(
        options.jobs > 0 && options.jobs <= 64,
        "Jobs must be between 1 and 64"
    );
    if !options.repair_pages {
        Transcoder::new(options.settings.clone())?;
    }
    ensure!(
        options.dry_run || (options.in_place != options.output.is_some()),
        "Choose output directory or in-place mode"
    );
    let input = options.input.canonicalize()?;
    ensure!(input.is_dir(), "Choose a GTA directory");
    let mut files = Vec::new();
    collect_files(&input, &input, &mut files)?;
    let all_archives: Vec<_> = files
        .iter()
        .filter(|p| p.extension().is_some_and(|e| e.eq_ignore_ascii_case("rpf")))
        .cloned()
        .collect();
    ensure!(
        !all_archives.is_empty(),
        "No RPF files found in GTA directory"
    );
    let selected = if options.archives.is_empty() {
        all_archives.clone()
    } else {
        for p in &options.archives {
            ensure!(
                all_archives.contains(p),
                "Requested archive is not in GTA directory: {}",
                p.display()
            );
        }
        options.archives.clone()
    };
    let destination = if options.in_place || options.dry_run {
        input.clone()
    } else {
        let target = options.output.as_ref().unwrap();
        ensure!(
            !target.exists(),
            "Output directory already exists; choose a new directory"
        );
        let parent = parent_directory(target);
        std::fs::create_dir_all(parent)?;
        let resolved = parent
            .canonicalize()?
            .join(target.file_name().context("Invalid output directory")?);
        ensure!(
            !resolved.starts_with(&input),
            "Output directory must be outside GTA input directory"
        );
        std::fs::create_dir(&resolved)?;
        resolved
    };
    let start = Instant::now();
    let mut total = Summary::default();
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(options.jobs)
        .build()?;
    let sample_counter = AtomicU64::new(0);
    for (index, relative) in selected.iter().enumerate() {
        let source = input.join(relative);
        let target = destination.join(relative);
        let original = std::fs::metadata(&source)?;
        println!(
            "{}",
            json!({"event":"archive-start","path":relative,"index":index+1,"total":selected.len()})
        );
        let handle = open_path(&source)?;
        let archive = get(handle.0)?;
        let scratch = if options.dry_run {
            tempfile::tempdir()?
        } else {
            tempfile::Builder::new()
                .prefix(".rpf-astc-work-")
                .tempdir_in(&destination)?
        };
        let summary = process_archive(
            &archive,
            &relative.to_string_lossy(),
            &options,
            &pool,
            scratch.path(),
            &sample_counter,
            0,
            &fd::Control {
                cancel: &archive.cancelled,
                progress: None,
            },
        )?;
        if !options.dry_run && options.require_no_bc {
            ensure!(summary.errors.is_empty() && summary.counts.remaining_bc == 0,
                "BC-free conversion failed for {}: {} retained BC textures and {} resource errors; original archive unchanged",
                relative.display(), summary.counts.remaining_bc, summary.errors.len());
        }
        if !options.dry_run {
            std::fs::create_dir_all(target.parent().unwrap())?;
            if summary.counts.changed() || !options.in_place {
                let mut temporary = tempfile::Builder::new()
                    .prefix(".rpf-astc-")
                    .tempfile_in(target.parent().unwrap())?;
                if summary.counts.changed() {
                    editor::write_archive(&archive, &mut temporary)?;
                } else {
                    std::io::copy(&mut File::open(&source)?, &mut temporary)?;
                }
                temporary.flush()?;
                // NG uses the source basename, not the temporary filename.
                let fd = duplicate(std::os::fd::AsRawFd::as_raw_fd(temporary.as_file()))?;
                let verify = Handle(open_file(
                    fd,
                    source
                        .file_name()
                        .unwrap()
                        .to_str()
                        .context("Non-UTF8 archive name")?,
                )?);
                let allowed_errors = summary
                    .errors
                    .iter()
                    .filter_map(|e| e["path"].as_str().map(str::to_owned))
                    .collect();
                let inventory = verify_tree(
                    &get(verify.0)?,
                    &relative.to_string_lossy(),
                    0,
                    Some(&allowed_errors),
                    Some(&archive.cancelled),
                )?;
                drop(verify);
                verify_counts(&inventory, &summary.counts)?;
                let now = std::fs::metadata(&source)?;
                ensure!(
                    now.dev() == original.dev()
                        && now.ino() == original.ino()
                        && now.len() == original.len()
                        && now.mtime() == original.mtime()
                        && now.mtime_nsec() == original.mtime_nsec(),
                    "Source changed while converting"
                );
                publish(temporary, &target, options.in_place)?;
            }
        }
        println!(
            "{}",
            json!({"event":"archive-done","path":relative,"summary":summary.json()})
        );
        total.add(&summary)?;
    }
    if !options.dry_run && !options.in_place && options.archives.is_empty() {
        for relative in files.iter().filter(|p| {
            !all_archives.contains(p) && p.as_path() != Path::new("rpf-astc-report.json")
        }) {
            let target = destination.join(relative);
            std::fs::create_dir_all(target.parent().unwrap())?;
            std::fs::copy(input.join(relative), target)?;
        }
    }
    let report = json!({"schema":1,"finished":true,"complete":total.errors.is_empty() && (options.dry_run || options.repair_pages || total.counts.remaining_bc == 0),
        "dryRun":options.dry_run,"repairPages":options.repair_pages,"requireNoBc":options.require_no_bc,"scope":if options.archives.is_empty(){"game-directory"}else{"selected-archives"},
        "input":input,"output":destination,"block":options.settings.block,"quality":options.settings.quality,
        "encoderSettings":options.settings.encoder_settings(),
        "seconds":start.elapsed().as_secs_f64(),"summary":total.json()});
    if !options.dry_run {
        let report_path = destination.join("rpf-astc-report.json");
        let mut file = tempfile::NamedTempFile::new_in(&destination)?;
        file.write_all(&serde_json::to_vec_pretty(&report)?)?;
        publish(file, &report_path, options.in_place)?;
    }
    Ok(report)
}
