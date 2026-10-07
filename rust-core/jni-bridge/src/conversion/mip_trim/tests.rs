use super::*;

#[test]
fn nested_trim_keeps_story_content_journals_changes_and_resumes_atomically() {
    let temp = tempfile::tempdir().unwrap();
    let tree = temp.path().join("tree");
    let input = temp.path().join("astc");
    std::fs::create_dir_all(tree.join("nested.rpf")).unwrap();
    std::fs::create_dir(&input).unwrap();
    let texture = trim::tests::fixture(6);
    std::fs::write(tree.join("nested.rpf/scene.ytd"), &texture).unwrap();
    std::fs::write(tree.join("story.ysc"), b"unchanged story script").unwrap();
    std::fs::write(input.join("outside.bin"), b"unchanged outside RPF").unwrap();
    pack_archive(&tree, &input.join("assets.rpf")).unwrap();
    let original = std::fs::read(input.join("assets.rpf")).unwrap();
    let options = TrimOptions {
        input: input.clone(),
        in_place: true,
        archives: vec![],
        policy: trim::tests::policy(),
        jobs: 2,
        dry_run: false,
        report: None,
    };
    let linked_report = temp.path().join("report-link.json");
    std::os::unix::fs::symlink(input.join("assets.rpf"), &linked_report).unwrap();
    for report in [
        input.join("assets.rpf"),
        input.join("outside.bin"),
        input.join("rpf-mip-trim/policy.json"),
        linked_report,
    ] {
        for dry_run in [false, true] {
            let error = trim_mips(TrimOptions {
                report: Some(report.clone()),
                dry_run,
                ..options.clone()
            })
            .unwrap_err();
            assert!(error.to_string().starts_with("Report"));
            assert_eq!(std::fs::read(input.join("assets.rpf")).unwrap(), original);
            assert!(!input.join("rpf-mip-trim").exists());
        }
    }
    let scan = trim_mips(TrimOptions {
        dry_run: true,
        ..options.clone()
    })
    .unwrap();
    assert_eq!(scan["totals"]["changedTextures"], 1);
    assert!(!input.join("rpf-mip-trim").exists());
    assert_eq!(std::fs::read(input.join("assets.rpf")).unwrap(), original);
    let report = trim_mips(options.clone()).unwrap();
    assert_eq!(report["totals"]["changedTextures"], 1);
    assert_eq!(report["totals"]["archives"], 2);
    let output = std::fs::read(input.join("assets.rpf")).unwrap();
    let manifest = input.join("rpf-mip-trim/archives/assets.rpf.json");
    let journal: Journal = serde_json::from_slice(&std::fs::read(&manifest).unwrap()).unwrap();
    assert_eq!(journal.source_sha256, digest(&original));
    assert_eq!(journal.output_sha256, digest(&output));
    assert_eq!(journal.resources[0].path, "assets.rpf/nested.rpf/scene.ytd");
    assert_eq!(
        journal.resources[0].report.changes[0].before_dimensions,
        [8, 8]
    );
    assert_eq!(
        journal.resources[0].report.changes[0].after_dimensions,
        [4, 4]
    );
    let extracted = temp.path().join("extracted");
    extract_archive(&input.join("assets.rpf"), &extracted).unwrap();
    assert_eq!(
        std::fs::read(extracted.join("story.ysc")).unwrap(),
        b"unchanged story script"
    );
    assert_eq!(
        std::fs::read(input.join("outside.bin")).unwrap(),
        b"unchanged outside RPF"
    );
    assert_ne!(
        std::fs::read(extracted.join("nested.rpf/scene.ytd")).unwrap(),
        texture
    );
    let repeated = trim_mips(options.clone()).unwrap();
    assert_eq!(repeated["alreadyAppliedArchives"], 1);
    assert_eq!(repeated["totals"]["changedTextures"], 1);
    assert_eq!(repeated["currentRunTotals"]["changedTextures"], 0);
    assert_eq!(repeated["archives"][0]["totals"]["changedTextures"], 1);
    assert_eq!(std::fs::read(input.join("assets.rpf")).unwrap(), output);
    // Simulate interruption after durable journal but before archive publication.
    std::fs::write(input.join("assets.rpf"), &original).unwrap();
    let resumed = trim_mips(options.clone()).unwrap();
    assert_eq!(resumed["totals"]["changedTextures"], 1);
    assert_eq!(std::fs::read(input.join("assets.rpf")).unwrap(), output);
    // A valid RPF with external changes must never be silently trimmed again.
    let mut tampered = output;
    tampered.extend([0; 512]);
    std::fs::write(input.join("assets.rpf"), &tampered).unwrap();
    let error = trim_mips(options.clone()).unwrap_err();
    assert!(error
        .to_string()
        .contains("differs from both journal hashes"));
    assert_eq!(std::fs::read(input.join("assets.rpf")).unwrap(), tampered);
    let mut different = options;
    different.policy.drop_mips = 2;
    assert!(trim_mips(different)
        .unwrap_err()
        .to_string()
        .contains("policy differs"));
}
