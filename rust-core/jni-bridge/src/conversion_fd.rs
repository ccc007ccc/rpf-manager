//! Android SAF adapter. The caller supplies a dedicated read-only archive handle
//! and a NEW temporary output descriptor; publishing belongs to the SAF layer.
use super::*;

static JOBS: OnceLock<Mutex<HashMap<i64, Arc<Mutex<Value>>>>> = OnceLock::new();
static ACTIVE_BATCH: AtomicBool = AtomicBool::new(false);

pub fn begin_batch() -> Result<()> {
    let _keys = key_guard();
    ensure!(
        !ACTIVE_BATCH.swap(true, Ordering::AcqRel),
        "A texture conversion batch is already running"
    );
    Ok(())
}
pub fn end_batch() {
    ACTIVE_BATCH.store(false, Ordering::Release);
}
pub fn batch_active() -> bool {
    ACTIVE_BATCH.load(Ordering::Acquire)
}

pub struct FdOptions {
    pub settings: Settings,
    pub jobs: usize,
    pub keep_going: bool,
    pub dry_run: bool,
    pub skip_unchanged: bool,
    pub repair_pages: bool,
    pub require_no_bc: bool,
}

pub(super) struct Control<'a> {
    pub cancel: &'a AtomicBool,
    pub progress: Option<&'a Mutex<Value>>,
}
impl Control<'_> {
    pub fn check(&self) -> Result<()> {
        ensure!(!self.cancel.load(Ordering::Relaxed), "Operation cancelled");
        Ok(())
    }
    pub fn stage(&self, stage: &str, path: &str) {
        if let Some(progress) = self.progress {
            let mut state = progress.lock().unwrap_or_else(|e| e.into_inner());
            state["stage"] = json!(stage);
            state["path"] = json!(path);
        }
    }
    pub fn completed(&self, counts: &Counts) {
        if let Some(progress) = self.progress {
            let mut state = progress.lock().unwrap_or_else(|e| e.into_inner());
            for (key, amount) in [
                ("resources", 1),
                ("textures", counts.textures),
                ("converted", counts.converted),
            ] {
                state[key] = json!(state[key].as_u64().unwrap_or_default() + amount);
            }
        }
    }
}

struct Job(i64);
impl Drop for Job {
    fn drop(&mut self) {
        JOBS.get_or_init(Default::default)
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&self.0);
    }
}

pub fn progress(handle: i64) -> Value {
    let state = JOBS
        .get_or_init(Default::default)
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(&handle)
        .cloned();
    state
        .map(|p| p.lock().unwrap_or_else(|e| e.into_inner()).clone())
        .unwrap_or_else(|| json!({"stage":"idle"}))
}

/// Does not close the borrowed descriptors. Cancellation/failure never publishes
/// the temporary output and never writes any mapped input. Use one handle per job.
pub fn convert_fd(
    handle: i64,
    output_fd: RawFd,
    scratch: &Path,
    options: FdOptions,
) -> Result<Value> {
    ensure!(
        (1..=8).contains(&options.jobs),
        "Android workers must be between 1 and 8"
    );
    Transcoder::new(options.settings.clone())?;
    let archive = get(handle)?;
    let input = match &*archive.data {
        Backing::Mapped(_, file) => file.clone(),
        _ => bail!("Select a top-level local RPF document"),
    };
    let original = input.metadata()?;
    ensure!(
        !archive.cancelled.load(Ordering::Relaxed),
        "Operation cancelled"
    );
    let mut output = if options.dry_run {
        None
    } else {
        Some(checked_output(output_fd)?)
    };
    let state = Arc::new(Mutex::new(
        json!({"stage":"starting","resources":0,"textures":0,"converted":0}),
    ));
    {
        let mut jobs = JOBS
            .get_or_init(Default::default)
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        ensure!(
            !jobs.contains_key(&handle),
            "Archive conversion is already running"
        );
        jobs.insert(handle, state.clone());
    }
    let _job = Job(handle);
    let control = Control {
        cancel: &archive.cancelled,
        progress: Some(&state),
    };
    std::fs::create_dir_all(scratch)?;
    let work = tempfile::Builder::new()
        .prefix("astc-")
        .tempdir_in(scratch)?;
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(options.jobs)
        .build()?;
    let convert_options = ConvertOptions {
        input: PathBuf::new(),
        output: None,
        in_place: false,
        archives: vec![],
        settings: options.settings.clone(),
        jobs: options.jobs,
        samples: None,
        dry_run: options.dry_run,
        repair_pages: options.repair_pages,
        keep_going: options.keep_going,
        require_no_bc: options.require_no_bc,
    };
    let started = Instant::now();
    let label = &archive.parsed.name;
    let summary = process_archive(
        &archive,
        label,
        &convert_options,
        &pool,
        work.path(),
        &AtomicU64::new(0),
        0,
        &control,
    )?;
    control.check()?;
    if !options.dry_run && !options.repair_pages && options.require_no_bc {
        ensure!(
            summary.errors.is_empty() && summary.counts.remaining_bc == 0,
            "BC-free conversion failed: {} retained BC textures and {} resource errors; original archive unchanged",
            summary.counts.remaining_bc, summary.errors.len()
        );
    }
    let now = input.metadata()?;
    ensure!(
        now.dev() == original.dev()
            && now.ino() == original.ino()
            && now.len() == original.len()
            && now.mtime() == original.mtime()
            && now.mtime_nsec() == original.mtime_nsec()
            && now.nlink() > 0,
        "Source changed while converting"
    );
    let changed = summary.counts.changed();
    let mut written = 0;
    if !options.dry_run && (changed || !options.skip_unchanged) {
        control.stage("writing", label);
        if changed {
            // Includes staged-source and unrelated-open-workspace identity guards.
            editor::save_fd(handle, output_fd)?;
        } else {
            let output = output.as_mut().unwrap();
            output.seek(std::io::SeekFrom::Start(0))?;
            output.set_len(0)?;
            for chunk in archive.bytes().chunks(256 * 1024) {
                control.check()?;
                output.write_all(chunk)?;
            }
            output.flush()?;
            output.sync_all()?;
        }
        control.check()?;
        control.stage("verifying", label);
        let verify = Handle(open_file(duplicate(output_fd)?, label)?);
        let allowed = summary
            .errors
            .iter()
            .filter_map(|e| e["path"].as_str().map(str::to_owned))
            .collect();
        let inventory = verify_tree(
            &get(verify.0)?,
            label,
            0,
            Some(&allowed),
            Some(&archive.cancelled),
        )?;
        verify_counts(&inventory, &summary.counts)?;
        written = output.as_ref().unwrap().metadata()?.len();
    }
    control.check()?;
    let now = input.metadata()?;
    ensure!(
        now.len() == original.len()
            && now.mtime() == original.mtime()
            && now.mtime_nsec() == original.mtime_nsec()
            && now.nlink() > 0,
        "Source changed while writing"
    );
    Ok(
        json!({"schema":1,"finished":true,"complete":summary.errors.is_empty() && (options.dry_run || options.repair_pages || summary.counts.remaining_bc == 0),
        "repairPages":options.repair_pages,"requireNoBc":options.require_no_bc,
        "dryRun":options.dry_run,"changed":changed,"bytes":written,"seconds":started.elapsed().as_secs_f64(),
        "block":options.settings.block,"quality":options.settings.quality,"encoderSettings":options.settings.encoder_settings(),
        "summary":summary.json()}),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::fd::AsRawFd;
    fn options() -> FdOptions {
        FdOptions {
            settings: Settings {
                block: (6, 6),
                quality: "fast".into(),
                advanced: Default::default(),
            },
            jobs: 2,
            keep_going: true,
            dry_run: false,
            skip_unchanged: false,
            repair_pages: false,
            require_no_bc: false,
        }
    }
    #[test]
    fn descriptors_are_borrowed_and_inputs_are_protected() {
        let mut builder = rpf_archive::RpfBuilder::new(rpf_archive::RpfEncryption::Open);
        builder.add_file("leaf.txt", b"original".to_vec());
        let bytes = builder.build(None).unwrap();
        let source = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(source.path(), &bytes).unwrap();
        let h = Handle(open_fd(source.as_file().as_raw_fd(), "test.rpf").unwrap());
        let scratch = tempfile::tempdir().unwrap();
        assert!(convert_fd(h.0, source.as_file().as_raw_fd(), scratch.path(), options()).is_err());
        assert_eq!(std::fs::read(source.path()).unwrap(), bytes);
        let output = tempfile::NamedTempFile::new().unwrap();
        let result =
            convert_fd(h.0, output.as_file().as_raw_fd(), scratch.path(), options()).unwrap();
        assert_eq!(result["changed"], false);
        assert_eq!(std::fs::read(output.path()).unwrap(), bytes);
        assert!(source.as_file().metadata().is_ok() && output.as_file().metadata().is_ok());
        assert_eq!(progress(h.0)["stage"], "idle");
    }
    #[test]
    fn cancellation_does_not_truncate_output() {
        let mut builder = rpf_archive::RpfBuilder::new(rpf_archive::RpfEncryption::Open);
        builder.add_file("leaf.txt", b"original".to_vec());
        let source = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(source.path(), builder.build(None).unwrap()).unwrap();
        let h = Handle(open_fd(source.as_file().as_raw_fd(), "test.rpf").unwrap());
        get(h.0).unwrap().cancelled.store(true, Ordering::Relaxed);
        let output = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(output.path(), b"output sentinel").unwrap();
        assert!(convert_fd(
            h.0,
            output.as_file().as_raw_fd(),
            source.path().parent().unwrap(),
            options()
        )
        .is_err());
        assert_eq!(std::fs::read(output.path()).unwrap(), b"output sentinel");
    }
    #[test]
    fn converted_descriptor_reopens_and_previews_as_astc() {
        let mut builder = rpf_archive::RpfBuilder::new(rpf_archive::RpfEncryption::Open);
        builder.add_file(
            "color.ytd",
            crate::transcode::tests::resource_fixture(1, false),
        );
        let source = tempfile::NamedTempFile::new().unwrap();
        let original = builder.build(None).unwrap();
        std::fs::write(source.path(), &original).unwrap();
        let h = Handle(open_fd(source.as_file().as_raw_fd(), "color.rpf").unwrap());
        let output = tempfile::NamedTempFile::new().unwrap();
        let scratch = tempfile::tempdir().unwrap();
        let result =
            convert_fd(h.0, output.as_file().as_raw_fd(), scratch.path(), options()).unwrap();
        assert_eq!(result["summary"]["converted"], 1);
        assert_eq!(result["complete"], true);
        assert_eq!(std::fs::read(source.path()).unwrap(), original);
        let check = Handle(open_fd(output.as_file().as_raw_fd(), "color.rpf").unwrap());
        let list = super::super::super::textures(check.0, "color.ytd").unwrap();
        assert_eq!(list[0]["format"], "ASTC 6x6");
        let png = super::super::super::texture_png(check.0, "color.ytd", 0).unwrap();
        let image = image::load_from_memory(&png).unwrap().to_rgba8();
        assert_eq!(image.dimensions(), (8, 8));
        assert_eq!(image.get_pixel(0, 0).0, [255, 0, 0, 255]);
        let coverage = crate::store::scan(check.0).unwrap();
        assert_eq!(coverage["counts"]["preview"], 1);
        assert_eq!(coverage["counts"]["failed"].as_u64().unwrap_or(0), 0);
    }

    #[test]
    fn retained_bc_is_incomplete_and_strict_mode_keeps_output_untouched() {
        let (mut system, graphics) = rpf_archive::resource::prepare_rsc7(
            &crate::transcode::tests::resource_fixture(1, false),
        )
        .unwrap();
        system[0x500..0x580].fill(0);
        system[0x500..0x50a].copy_from_slice(b"script_rt\0");
        let mut builder = rpf_archive::RpfBuilder::new(rpf_archive::RpfEncryption::Open);
        builder.add_file(
            "dynamic.ytd",
            crate::transcode::tests::encode_fixture(&system, &graphics),
        );
        let source = tempfile::NamedTempFile::new().unwrap();
        let bytes = builder.build(None).unwrap();
        std::fs::write(source.path(), &bytes).unwrap();
        let handle = Handle(open_fd(source.as_file().as_raw_fd(), "dynamic.rpf").unwrap());
        let output = tempfile::NamedTempFile::new().unwrap();
        let scratch = tempfile::tempdir().unwrap();
        let partial = convert_fd(
            handle.0,
            output.as_file().as_raw_fd(),
            scratch.path(),
            options(),
        )
        .unwrap();
        assert_eq!(partial["complete"], false);
        assert_eq!(partial["summary"]["remainingBc"], 1);
        std::fs::write(output.path(), b"sentinel").unwrap();
        assert!(convert_fd(
            handle.0,
            output.as_file().as_raw_fd(),
            scratch.path(),
            FdOptions {
                require_no_bc: true,
                ..options()
            }
        )
        .is_err());
        assert_eq!(std::fs::read(output.path()).unwrap(), b"sentinel");
        assert_eq!(std::fs::read(source.path()).unwrap(), bytes);
    }

    #[test]
    fn android_page_repair_keeps_payloads_and_reports_repairs() {
        let broken = crate::transcode::tests::illegal_astc_fixture(true, true, true);
        let (_, graphics) = rpf_archive::resource::prepare_rsc7(&broken).unwrap();
        let mut builder = rpf_archive::RpfBuilder::new(rpf_archive::RpfEncryption::Open);
        builder.add_file("broken.ytd", broken);
        let source = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(source.path(), builder.build(None).unwrap()).unwrap();
        let handle = Handle(open_fd(source.as_file().as_raw_fd(), "repair.rpf").unwrap());
        let output = tempfile::NamedTempFile::new().unwrap();
        let scratch = tempfile::tempdir().unwrap();
        let report = convert_fd(
            handle.0,
            output.as_file().as_raw_fd(),
            scratch.path(),
            FdOptions {
                repair_pages: true,
                require_no_bc: true,
                ..options()
            },
        )
        .unwrap();
        assert_eq!(report["complete"], true);
        assert_eq!(report["summary"]["converted"], 0);
        assert_eq!(report["summary"]["repairedResources"], 1);
        let check = Handle(open_fd(output.as_file().as_raw_fd(), "repair.rpf").unwrap());
        let resource = crate::store::read(check.0, "broken.ytd").unwrap();
        let (_, repaired) = rpf_archive::resource::prepare_rsc7(&resource).unwrap();
        assert_eq!(&repaired[..graphics.len()], &graphics);
    }
}
