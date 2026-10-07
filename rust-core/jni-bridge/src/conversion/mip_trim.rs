//! Atomic per-archive ASTC mip reduction with a durable, hash-based rerun journal.
use super::*;
use crate::transcode::mip_trim::{self as trim, Policy};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;

#[derive(Clone)]
pub struct TrimOptions {
    pub input: PathBuf,
    pub in_place: bool,
    pub archives: Vec<PathBuf>,
    pub policy: Policy,
    pub jobs: usize,
    pub dry_run: bool,
    pub report: Option<PathBuf>,
}

#[derive(Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Totals {
    archives: u64,
    resources: u64,
    textures: u64,
    astc_textures: u64,
    changed_resources: u64,
    changed_textures: u64,
    source_payload_bytes: u64,
    output_payload_bytes: u64,
    reclaimed_bytes: u64,
    retained_overlap_bytes: u64,
    skipped: BTreeMap<String, u64>,
}
impl Totals {
    fn resource(&mut self, r: &trim::Report) {
        self.resources += 1;
        self.textures += r.textures;
        self.astc_textures += r.astc_textures;
        self.changed_resources += u64::from(r.changed_textures > 0);
        self.changed_textures += r.changed_textures;
        self.source_payload_bytes += r.source_payload_bytes;
        self.output_payload_bytes += r.output_payload_bytes;
        self.reclaimed_bytes += r.reclaimed_bytes;
        self.retained_overlap_bytes += r.retained_overlap_bytes;
        for (reason, count) in &r.skipped {
            *self.skipped.entry(reason.clone()).or_default() += count;
        }
    }
    fn add(&mut self, r: &Self) {
        self.archives += r.archives;
        self.resources += r.resources;
        self.textures += r.textures;
        self.astc_textures += r.astc_textures;
        self.changed_resources += r.changed_resources;
        self.changed_textures += r.changed_textures;
        self.source_payload_bytes += r.source_payload_bytes;
        self.output_payload_bytes += r.output_payload_bytes;
        self.reclaimed_bytes += r.reclaimed_bytes;
        self.retained_overlap_bytes += r.retained_overlap_bytes;
        for (reason, count) in &r.skipped {
            *self.skipped.entry(reason.clone()).or_default() += count;
        }
    }
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ResourceChange {
    path: String,
    source_sha256: String,
    output_sha256: Option<String>,
    source_bytes: usize,
    output_bytes: Option<usize>,
    report: trim::Report,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Journal {
    schema: u32,
    policy: Policy,
    archive: PathBuf,
    source_sha256: String,
    output_sha256: String,
    source_bytes: u64,
    output_bytes: u64,
    totals: Totals,
    resources: Vec<ResourceChange>,
}

fn digest(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}

fn resolve_destination(path: &Path) -> Result<PathBuf> {
    let mut ancestor = path;
    let mut missing = Vec::new();
    while !ancestor.exists() {
        missing.push(
            ancestor
                .file_name()
                .context("Invalid report path")?
                .to_owned(),
        );
        ancestor = parent_directory(ancestor);
    }
    let mut resolved = ancestor.canonicalize()?;
    for component in missing.into_iter().rev() {
        resolved.push(component);
    }
    Ok(resolved)
}

fn create_durable_directories(path: &Path) -> Result<()> {
    if path.is_dir() {
        return Ok(());
    }
    let parent = parent_directory(path);
    create_durable_directories(parent)?;
    match std::fs::create_dir(path) {
        Ok(()) => (),
        Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists && path.is_dir() => (),
        Err(e) => return Err(e.into()),
    }
    File::open(parent)?.sync_all()?;
    Ok(())
}

fn save_json(path: &Path, value: &impl Serialize) -> Result<()> {
    create_durable_directories(parent_directory(path))?;
    let mut temporary = tempfile::NamedTempFile::new_in(parent_directory(path))?;
    serde_json::to_writer_pretty(&mut temporary, value)?;
    temporary.write_all(b"\n")?;
    publish(temporary, path, path.exists())
}

struct Processed {
    totals: Totals,
    resources: Vec<ResourceChange>,
}

fn process(
    a: &Arc<Archive>,
    label: &str,
    options: &TrimOptions,
    pool: &rayon::ThreadPool,
    scratch: &Path,
    depth: usize,
) -> Result<Processed> {
    ensure!(depth <= 24, "Nested archive depth limit reached");
    let mut entries = editor::snapshot(a);
    let mut result = Processed {
        totals: Totals {
            archives: 1,
            ..Totals::default()
        },
        resources: Vec::new(),
    };
    for entry in &mut entries {
        if entry.directory || !entry.name.to_ascii_lowercase().ends_with(".rpf") {
            continue;
        }
        let h =
            Handle(open_nested_entry(a, entry).with_context(|| format!("{label}/{}", entry.path))?);
        let child = get(h.0)?;
        let processed = process(
            &child,
            &format!("{label}/{}", entry.path),
            options,
            pool,
            scratch,
            depth + 1,
        )?;
        if !options.dry_run && processed.totals.changed_textures > 0 {
            let mut file = tempfile::tempfile_in(scratch)?;
            editor::write_archive(&child, &mut file)?;
            entry.size = file.metadata()?.len();
            entry.replacement = Some(Replacement::File(Arc::new(file)));
        }
        result.totals.add(&processed.totals);
        result.resources.extend(processed.resources);
    }
    let jobs: Vec<_> = entries
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
    let completed: Result<Vec<_>> = pool.install(|| {
        jobs.par_iter()
            .map(|&index| -> Result<_> {
                let entry = &entries[index];
                let path = format!("{label}/{}", entry.path);
                let bytes = a
                    .parsed
                    .extract_entry(
                        a.bytes(),
                        &a.parsed.entries[entry.index],
                        key_guard().as_ref(),
                    )
                    .with_context(|| format!("Reading {path}"))?;
                let (system, graphics) = rpf_archive::resource::prepare_rsc7(&bytes)
                    .with_context(|| format!("Decoding {path}"))?;
                let trimmed = trim::resource(
                    &bytes,
                    system,
                    graphics,
                    &entry.name,
                    &options.policy,
                    options.dry_run,
                )
                .with_context(|| format!("Trimming {path}"))?;
                let mut change = None;
                let mut file = None;
                let size = trimmed.bytes.len() as u64;
                if trimmed.report.changed_textures > 0 {
                    change = Some(ResourceChange {
                        path,
                        source_sha256: digest(&bytes),
                        output_sha256: (!options.dry_run).then(|| digest(&trimmed.bytes)),
                        source_bytes: bytes.len(),
                        output_bytes: (!options.dry_run).then_some(trimmed.bytes.len()),
                        report: trimmed.report.clone(),
                    });
                    if !options.dry_run {
                        let mut temporary = tempfile::tempfile_in(scratch)?;
                        temporary.write_all(&trimmed.bytes)?;
                        file = Some(Arc::new(temporary));
                    }
                }
                Ok((index, trimmed.report, change, file, size))
            })
            .collect()
    });
    for (index, report, change, file, size) in completed? {
        result.totals.resource(&report);
        if let Some(change) = change {
            result.resources.push(change);
        }
        if let Some(file) = file {
            entries[index].size = size;
            entries[index].replacement = Some(Replacement::File(file));
        }
    }
    if !options.dry_run && result.totals.changed_textures > 0 {
        a.edits
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .current
            .entries = entries;
    }
    Ok(result)
}

fn verify_changes(
    a: &Arc<Archive>,
    label: &str,
    expected: &BTreeMap<&str, &str>,
    seen: &mut usize,
    depth: usize,
) -> Result<()> {
    ensure!(depth <= 24, "Nested archive depth limit reached");
    for entry in &a.entries {
        if entry.directory {
            continue;
        }
        let path = format!("{label}/{}", entry.path);
        if entry.name.to_ascii_lowercase().ends_with(".rpf") {
            let child = Handle(open_nested_entry(a, entry)?);
            verify_changes(&get(child.0)?, &path, expected, seen, depth + 1)?;
        } else if let Some(hash) = expected.get(path.as_str()) {
            let bytes = a.parsed.extract_entry(
                a.bytes(),
                &a.parsed.entries[entry.index],
                key_guard().as_ref(),
            )?;
            ensure!(
                digest(&bytes) == *hash,
                "Resource payload changed during RPF packing: {path}"
            );
            *seen += 1;
        }
    }
    Ok(())
}

/// The journal is synced before replacing an RPF. After interruption its source
/// hash means redo safely; its output hash means skip, even if publication was
/// the last completed syscall. Any other hash is refused, never trimmed again.
pub fn trim_mips(options: TrimOptions) -> Result<Value> {
    options.policy.validate()?;
    ensure!(
        options.dry_run || options.in_place,
        "Use --in-place to modify ASTC archives, or --dry-run to inspect"
    );
    ensure!((1..=64).contains(&options.jobs), "Jobs must be 1..64");
    let input = options.input.canonicalize()?;
    ensure!(input.is_dir(), "Choose an ASTC game directory");
    let mut files = Vec::new();
    collect_files(&input, &input, &mut files)?;
    let all: Vec<_> = files
        .iter()
        .filter(|p| p.extension().is_some_and(|e| e.eq_ignore_ascii_case("rpf")))
        .cloned()
        .collect();
    ensure!(!all.is_empty(), "No RPF files in input directory");
    let selected = if options.archives.is_empty() {
        all.clone()
    } else {
        for p in &options.archives {
            ensure!(
                all.contains(p),
                "Archive is outside input directory: {}",
                p.display()
            );
        }
        let mut selected = options.archives.clone();
        selected.sort();
        selected.dedup();
        selected
    };
    let state = input.join("rpf-mip-trim");
    // Validate the user report destination before even creating policy state.
    // A dry run must not be able to replace a game archive with JSON.
    let report_path = options
        .report
        .as_deref()
        .map(resolve_destination)
        .transpose()?;
    if let Some(path) = &report_path {
        ensure!(
            !path.starts_with(&state),
            "Report must be outside the mip-trim journal directory"
        );
        ensure!(
            !files.iter().any(|relative| input.join(relative) == *path),
            "Report path would overwrite an existing game file"
        );
        ensure!(!path.is_dir(), "Report path is a directory");
    }
    let policy_path = state.join("policy.json");
    if policy_path.exists() {
        let policy: Policy = serde_json::from_slice(&std::fs::read(&policy_path)?)?;
        ensure!(
            policy == options.policy,
            "Existing mip-trim policy differs; use an untrimmed ASTC resource copy"
        );
    } else if !options.dry_run {
        save_json(&policy_path, &options.policy)?;
    }
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(options.jobs)
        .build()?;
    let started = Instant::now();
    let mut total = Totals::default();
    let mut current_run = Totals::default();
    let mut archives = Vec::new();
    let mut skipped_applied = 0;
    for (index, relative) in selected.iter().enumerate() {
        let source = input.join(relative);
        let original = std::fs::metadata(&source)?;
        let handle = open_path(&source)?;
        let archive = get(handle.0)?;
        let source_hash = digest(archive.bytes());
        let journal_path = state
            .join("archives")
            .join(relative)
            .with_extension("rpf.json");
        if journal_path.exists() {
            let prior: Journal = serde_json::from_slice(&std::fs::read(&journal_path)?)?;
            ensure!(
                prior.schema == 1 && prior.policy == options.policy && prior.archive == *relative,
                "Journal identity mismatch for {}",
                relative.display()
            );
            if source_hash == prior.output_sha256 {
                skipped_applied += 1;
                total.add(&prior.totals);
                println!(
                    "{}",
                    json!({"event":"archive-already-trimmed", "path":relative, "sha256":source_hash})
                );
                archives.push(
                    json!({"path":relative,"alreadyApplied":true,"manifest":journal_path,
                    "sourceBytes":prior.source_bytes,"outputBytes":prior.output_bytes,"totals":prior.totals}),
                );
                continue;
            }
            ensure!(source_hash == prior.source_sha256, "Archive {} differs from both journal hashes; refusing a possible second mip reduction", relative.display());
        }
        println!(
            "{}",
            json!({"event":"archive-start","path":relative,"index":index+1,"total":selected.len()})
        );
        let scratch = if options.dry_run {
            tempfile::tempdir()?
        } else {
            tempfile::Builder::new()
                .prefix(".rpf-mip-work-")
                .tempdir_in(&input)?
        };
        let processed = process(
            &archive,
            &relative.to_string_lossy(),
            &options,
            &pool,
            scratch.path(),
            0,
        )?;
        let mut output_bytes = original.len();
        if options.dry_run {
            archives.push(
                json!({"path":relative,"sourceSha256":source_hash,"sourceBytes":original.len(),
                "totals":processed.totals,"resources":processed.resources}),
            );
        } else {
            let mut temporary = None;
            let mut output_hash = source_hash.clone();
            if processed.totals.changed_textures > 0 {
                let mut file = tempfile::Builder::new()
                    .prefix(".rpf-mip-")
                    .tempfile_in(parent_directory(&source))?;
                editor::write_archive(&archive, &mut file)?;
                file.flush()?;
                output_bytes = file.as_file().metadata()?.len();
                let fd = duplicate(file.as_file().as_raw_fd())?;
                let check = Handle(open_file(
                    fd,
                    source
                        .file_name()
                        .unwrap()
                        .to_str()
                        .context("Non-UTF8 archive name")?,
                )?);
                let checked = get(check.0)?;
                let inventory = verify_tree(
                    &checked,
                    &relative.to_string_lossy(),
                    0,
                    Some(&HashSet::new()),
                    None,
                )?;
                ensure!(
                    inventory.textures == processed.totals.textures
                        && inventory.already_astc == processed.totals.astc_textures,
                    "Texture inventory changed after packing"
                );
                let expected: BTreeMap<_, _> = processed
                    .resources
                    .iter()
                    .map(|r| (r.path.as_str(), r.output_sha256.as_deref().unwrap()))
                    .collect();
                let mut seen = 0;
                verify_changes(
                    &checked,
                    &relative.to_string_lossy(),
                    &expected,
                    &mut seen,
                    0,
                )?;
                ensure!(
                    seen == expected.len(),
                    "Changed resources missing after packing"
                );
                output_hash = digest(checked.bytes());
                temporary = Some(file);
            }
            let now = std::fs::metadata(&source)?;
            ensure!(
                now.dev() == original.dev()
                    && now.ino() == original.ino()
                    && now.len() == original.len()
                    && now.mtime() == original.mtime()
                    && now.mtime_nsec() == original.mtime_nsec(),
                "Source changed while trimming"
            );
            let journal = Journal {
                schema: 1,
                policy: options.policy.clone(),
                archive: relative.clone(),
                source_sha256: source_hash,
                output_sha256: output_hash,
                source_bytes: original.len(),
                output_bytes,
                totals: processed.totals.clone(),
                resources: processed.resources,
            };
            // Never publish data before its identity and exact changes are durable.
            save_json(&journal_path, &journal)?;
            if let Some(file) = temporary {
                publish(file, &source, true)?;
            }
            archives.push(json!({"path":relative,"manifest":journal_path,"sourceBytes":original.len(),"outputBytes":output_bytes,"totals":processed.totals}));
        }
        println!(
            "{}",
            json!({"event":"archive-done","path":relative,"sourceBytes":original.len(),"outputBytes":if options.dry_run {None}else{Some(output_bytes)},"totals":processed.totals})
        );
        total.add(&processed.totals);
        current_run.add(&processed.totals);
    }
    let report = json!({"schema":1,"complete":true,"dryRun":options.dry_run,"input":input,
        "policy":options.policy,"journalDirectory":state,"alreadyAppliedArchives":skipped_applied,
        "scope":if options.archives.is_empty(){"game-directory"}else{"selected-archives"},
        "seconds":started.elapsed().as_secs_f64(),"totalsScope":"selected-archives-including-journal-history",
        "totals":total,"currentRunTotals":current_run,"archives":archives});
    if let Some(path) = report_path {
        save_json(&path, &report)?;
    }
    Ok(report)
}

#[cfg(test)]
#[path = "mip_trim/tests.rs"]
mod tests;
