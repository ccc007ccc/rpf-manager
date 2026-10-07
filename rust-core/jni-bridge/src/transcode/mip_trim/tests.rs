use super::*;

fn put16(b: &mut [u8], o: usize, v: u16) {
    b[o..o + 2].copy_from_slice(&v.to_le_bytes());
}
fn put64(b: &mut [u8], o: usize, v: u64) {
    b[o..o + 8].copy_from_slice(&v.to_le_bytes());
}

pub(crate) fn fixture(layers: u8) -> Vec<u8> {
    let (mut system, mut graphics) =
        prepare_rsc7(&super::super::tests::resource_fixture(layers, true)).unwrap();
    system[0x258..0x25c].copy_from_slice(&astc_format(4, 4).to_le_bytes());
    let mut offset = 0x80;
    for layer in 0..layers {
        for (mip, bytes) in [64, 16, 16, 16].into_iter().enumerate() {
            graphics[offset..offset + bytes].fill(layer * 4 + mip as u8 + 17);
            offset += bytes;
        }
    }
    super::super::tests::encode_fixture(&system, &graphics)
}

pub(crate) fn policy() -> Policy {
    Policy {
        name: "test-drop-one".into(),
        drop_mips: 1,
        minimum_dimension: 1,
        minimum_source_bytes: 0,
        require_even_dimensions: false,
        preserved_dictionaries: Vec::new(),
    }
}

fn run(bytes: &[u8]) -> Trimmed {
    let (sys, gfx) = prepare_rsc7(bytes).unwrap();
    resource(bytes, sys, gfx, "fixture.ytd", &policy(), false).unwrap()
}

#[test]
fn cube_faces_keep_exact_lower_mips_and_unrelated_bytes() {
    let original = fixture(6);
    let (system, graphics) = prepare_rsc7(&original).unwrap();
    let output = run(&original);
    let (mut sys, gfx) = prepare_rsc7(&output.bytes).unwrap();
    let t = textures_for_resource(&sys, &gfx, "fixture.ytd")
        .unwrap()
        .remove(0);
    assert_eq!(
        (t.width, t.height, t.levels, t.layers, t.srgb),
        (4, 4, 3, 6, true)
    );
    assert_eq!(t.format, astc_minimum_format(4, 4));
    for layer in 0..6 {
        assert_eq!(
            &gfx[0x80 + layer * 48..0x80 + (layer + 1) * 48],
            &graphics[0x80 + layer * 112 + 64..0x80 + (layer + 1) * 112]
        );
    }
    assert!(gfx[0x80 + 6 * 48..0x80 + 6 * 112].iter().all(|&v| v == 0));
    assert_eq!(&gfx[..0x80], &graphics[..0x80]);
    assert_eq!(&gfx[0x80 + 6 * 112..], &graphics[0x80 + 6 * 112..]);
    for range in [0x250..0x254, 0x256..0x25c, 0x25d..0x25e, 0x270..0x278] {
        sys[range.clone()].copy_from_slice(&system[range]);
    }
    assert_eq!(sys, system);
    assert_eq!(output.report.reclaimed_bytes, 384);
    let rerun = run(&output.bytes);
    assert_eq!(rerun.bytes, output.bytes);
    assert_eq!(rerun.report.changed_textures, 0);
    assert_eq!(rerun.report.skipped["already_minimum_astc"], 1);
}

#[test]
fn excluded_formats_runtime_images_and_small_mips_are_unchanged() {
    for mode in 0..6 {
        let (mut sys, gfx) = prepare_rsc7(&fixture(1)).unwrap();
        match mode {
            0 => sys[0x258..0x25c].copy_from_slice(&0x31545844u32.to_le_bytes()),
            1 => sys[0x232] = 1,
            2 => sys[0x248] = 1,
            3 => {
                sys[0x500..0x580].fill(0);
                sys[0x500..0x50d].copy_from_slice(b"pedmugshot_01");
            }
            4 => sys[0x25d] = 1,
            _ => put16(&mut sys, 0x254, 2),
        }
        let original = super::super::tests::encode_fixture(&sys, &gfx);
        let output = run(&original);
        assert_eq!(output.bytes, original, "mode={mode}");
        assert_eq!(output.report.changed_textures, 0);
    }
}

#[test]
fn payload_aliases_share_once_and_unknown_owners_are_preserved() {
    for unknown in [false, true] {
        let (mut sys, gfx) = prepare_rsc7(&fixture(1)).unwrap();
        put16(&mut sys, 0x28, 2);
        put16(&mut sys, 0x38, 2);
        put64(&mut sys, 0x188, 0x50000300);
        let object = sys[0x200..0x290].to_vec();
        sys[0x300..0x390].copy_from_slice(&object);
        if unknown {
            put64(&mut sys, 0x600, 0x60000060);
        }
        let original = super::super::tests::encode_fixture(&sys, &gfx);
        let result = run(&original);
        let (check_sys, check_gfx) = prepare_rsc7(&result.bytes).unwrap();
        assert_eq!(u64_le(&check_sys, 0x270), u64_le(&check_sys, 0x370));
        if unknown {
            assert_eq!(result.bytes, original);
            assert_eq!(result.report.changed_textures, 0);
            assert_eq!(result.report.skipped["shared_or_unknown_gpu_owner"], 2);
            assert_eq!(result.report.retained_overlap_bytes, 112);
            assert_eq!(result.report.reclaimed_bytes, 0);
        } else {
            assert_eq!(result.report.changed_textures, 2);
            assert_eq!(result.report.source_payload_bytes, 112);
            assert_eq!(result.report.reclaimed_bytes, 64);
            assert_eq!(check_gfx.len(), gfx.len());
        }
    }
}

#[test]
fn retained_overlapping_texture_is_not_damaged() {
    let (mut sys, gfx) = prepare_rsc7(&fixture(1)).unwrap();
    put16(&mut sys, 0x28, 2);
    put16(&mut sys, 0x38, 2);
    put64(&mut sys, 0x188, 0x50000300);
    let object = sys[0x200..0x290].to_vec();
    sys[0x300..0x390].copy_from_slice(&object);
    sys[0x332] = 1;
    let original = super::super::tests::encode_fixture(&sys, &gfx);
    let output = run(&original);
    let (actual_sys, actual_gfx) = prepare_rsc7(&output.bytes).unwrap();
    assert_eq!(&actual_sys[0x300..0x390], &sys[0x300..0x390]);
    assert_eq!(&actual_gfx[..gfx.len()], gfx.as_slice());
    assert_eq!(output.report.changed_textures, 0);
    assert_eq!(output.bytes, original);
}

#[test]
fn malformed_payload_refuses_trim_and_dry_run_does_not_touch_resource() {
    let original = fixture(1);
    let (sys, gfx) = prepare_rsc7(&original).unwrap();
    let scan = resource(
        &original,
        sys.clone(),
        gfx.clone(),
        "fixture.ytd",
        &policy(),
        true,
    )
    .unwrap();
    assert_eq!(scan.bytes, original);
    assert_eq!(scan.report.changed_textures, 1);
    let mut broken = sys;
    put64(&mut broken, 0x270, 0x60001ff0);
    assert!(resource(&original, broken, gfx, "fixture.ytd", &policy(), false).is_err());
}

#[test]
fn game_minimum_uses_menu_mapping_and_protects_fixed_coordinate_lookup() {
    let (sys, gfx) = prepare_rsc7(&fixture(1)).unwrap();
    let mut t = textures_for_resource(&sys, &gfx, "fixture.ytd")
        .unwrap()
        .remove(0);
    let policy = Policy::gta_minimum();
    t.width = 64;
    t.height = 64;
    assert_eq!(policy.selection(&t, "world.ytd"), Ok(1));
    assert_eq!(
        policy.selection(&t, "MP_EYE_COLOUR.YTD"),
        Err("fixed_coordinate_dictionary")
    );
    t.width = 63;
    assert_eq!(
        policy.selection(&t, "world.ytd"),
        Err("non_reversible_dimensions")
    );
    t.width = 32;
    t.height = 32;
    assert_eq!(
        policy.selection(&t, "world.ytd"),
        Err("below_source_byte_threshold")
    );
    t.width = 6;
    t.height = 4096;
    assert_eq!(
        policy.selection(&t, "world.ytd"),
        Err("minimum_dimension_or_single_mip")
    );
    t.width = 64;
    t.height = 64;
    t.levels = 1;
    assert_eq!(
        policy.selection(&t, "world.ytd"),
        Err("minimum_dimension_or_single_mip")
    );
}
