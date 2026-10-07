use super::*;

fn put16(bytes: &mut [u8], offset: usize, value: u16) {
    bytes[offset..offset + 2].copy_from_slice(&value.to_le_bytes());
}
fn put32(bytes: &mut [u8], offset: usize, value: u32) {
    bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}
fn put64(bytes: &mut [u8], offset: usize, value: u64) {
    bytes[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
}

// A real PC dictionary layout with deliberately unrelated data before and after
// the texture. All mip/layer colors are known independently of the transcoder.
pub(crate) fn resource_fixture(layers: u8, srgb: bool) -> Vec<u8> {
    let mut system = vec![0u8; 8192];
    let mut graphics = vec![0xa5; 8192];
    put64(&mut system, 8, 0x50001000);
    system[0x1008..0x100a].copy_from_slice(&[1, 1]);
    put64(&mut system, 0x20, 0x50000100);
    put16(&mut system, 0x28, 1);
    put16(&mut system, 0x2a, 2);
    put64(&mut system, 0x30, 0x50000180);
    put16(&mut system, 0x38, 1);
    put16(&mut system, 0x3a, 2);
    put32(&mut system, 0x100, 0x12345678);
    put64(&mut system, 0x180, 0x50000200);
    put64(&mut system, 0x228, 0x50000500);
    system[0x500..0x506].copy_from_slice(b"color\0");
    system[0x233] = layers - 1;
    put16(&mut system, 0x250, 8);
    put16(&mut system, 0x252, 8);
    put16(&mut system, 0x254, 1);
    put16(&mut system, 0x256, 4);
    put32(&mut system, 0x258, 0x31545844);
    system[0x25c] = u8::from(layers == 6);
    system[0x25d] = 4;
    system[0x25f] = u8::from(srgb);
    put64(&mut system, 0x270, 0x60000080);
    let colors = [0xf800u16, 0x07e0, 0x001f, 0xffff];
    let mut pos = 0x80;
    for layer in 0..layers as usize {
        for mip in 0..4 {
            let color = colors[(layer + mip) % 4];
            let count = if mip == 0 { 4 } else { 1 };
            for _ in 0..count {
                graphics[pos..pos + 2].copy_from_slice(&color.to_le_bytes());
                graphics[pos + 2..pos + 4].copy_from_slice(&color.to_le_bytes());
                graphics[pos + 4..pos + 8].fill(0);
                pos += 8;
            }
        }
    }
    encode_fixture(&system, &graphics)
}

pub(crate) fn encode_fixture(system: &[u8], graphics: &[u8]) -> Vec<u8> {
    let mut bytes = b"RSC7".to_vec();
    bytes.extend(13u32.to_le_bytes());
    bytes.extend(0x20000u32.to_le_bytes());
    bytes.extend(0xd0020000u32.to_le_bytes());
    let mut deflate = flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::fast());
    deflate.write_all(system).unwrap();
    deflate.write_all(graphics).unwrap();
    bytes.extend(deflate.finish().unwrap());
    bytes
}

fn converter(block: (u32, u32)) -> Transcoder {
    Transcoder::new(Settings {
        block,
        quality: "fast".into(),
        advanced: Default::default(),
    })
    .unwrap()
}

#[test]
fn all_footprints_preserve_mips_cube_faces_and_srgb() {
    // NUM-019: constant-color fixtures bound this sample, not arbitrary texture loss.
    for block in BLOCKS {
        for srgb in [false, true] {
            let source = resource_fixture(6, srgb);
            let mut encoder = converter(block);
            let output = encoder.resource(&source, None).unwrap();
            assert_eq!(output.counts.converted, 1);
            let (system, graphics) = prepare_rsc7(&output.bytes).unwrap();
            let texture = textures(&system, &graphics).unwrap().remove(0);
            assert_eq!(astc_block(texture.format), Some(block));
            assert_eq!((texture.layers, texture.levels, texture.srgb), (6, 4, srgb));
            assert_eq!(&graphics[..0x80], &[0xa5; 0x80]);
            assert_eq!(&graphics[0x500..0x600], &[0xa5; 0x100]);
            let mut offset = texture.data_offset;
            for layer in 0..6 {
                for mip in 0..4 {
                    let (w, h, size) = texture.mip_layout(mip).unwrap();
                    let mut pixels = vec![0u32; w * h];
                    texture2ddecoder::decode_astc(
                        &graphics[offset..offset + size],
                        w,
                        h,
                        block.0 as usize,
                        block.1 as usize,
                        &mut pixels,
                    )
                    .unwrap();
                    let expected = [0xffff0000u32, 0xff00ff00, 0xff0000ff, 0xffffffff]
                        [(layer + mip as usize) % 4];
                    for pixel in pixels {
                        for shift in [0, 8, 16, 24] {
                            let got = ((pixel >> shift) & 255) as i32;
                            let want = ((expected >> shift) & 255) as i32;
                            assert!(
                                (got - want).abs() <= 1,
                                "{block:?} sRGB={srgb} layer={layer} mip={mip}: {pixel:08x}"
                            );
                        }
                    }
                    offset += size;
                }
            }
            let rerun = encoder.resource(&output.bytes, None).unwrap();
            assert_eq!(rerun.counts.converted, 0);
            assert_eq!(rerun.counts.already_astc, 1);
            assert_eq!(rerun.bytes, output.bytes);
        }
    }
}

fn advanced_test_settings() -> Settings {
    Settings {
        block: (4, 4),
        quality: "thorough".into(),
        advanced: AdvancedSettings::default(),
    }
}

// A real BC3 edge block with independent color and alpha indices. The A=0
// texel is especially sensitive to the requested alpha error weight.
fn alpha_edge_resource() -> Vec<u8> {
    let (mut system, mut graphics) = prepare_rsc7(&resource_fixture(1, false)).unwrap();
    put16(&mut system, 0x250, 4);
    put16(&mut system, 0x252, 4);
    put32(&mut system, 0x258, 0x35545844);
    system[0x25d] = 1;
    graphics[0x80..0x90].copy_from_slice(&[
        0x3c, 0xd2, 0xd7, 0xd9, 0xa4, 0x59, 0x70, 0xe7, 0xee, 0xbd, 0x22, 0x11, 0x3e, 0x2d, 0x09,
        0x0d,
    ]);
    encode_fixture(&system, &graphics)
}

#[test]
fn advanced_settings_reject_nonfinite_and_out_of_range_values() {
    let baseline = advanced_test_settings();
    for effort in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY, -0.1, 100.1] {
        let mut settings = baseline.clone();
        settings.advanced.effort = Some(effort);
        assert!(Transcoder::new(settings).is_err());
    }
    for weight in [
        f32::NAN,
        f32::INFINITY,
        f32::NEG_INFINITY,
        0.0,
        -1.0,
        0.009,
        64.1,
    ] {
        for channel in 0..4 {
            let mut settings = baseline.clone();
            settings.advanced.channel_weights[channel] = weight;
            assert!(Transcoder::new(settings).is_err());
        }
    }
    let mut settings = baseline.clone();
    settings.advanced.alpha_weight_radius = Some(17);
    assert!(Transcoder::new(settings).is_err());
    for effort in [0.0, 100.0] {
        let mut settings = baseline.clone();
        settings.advanced.effort = Some(effort);
        settings.advanced.channel_weights = [0.01, 64.0, 1.0, 2.0];
        settings.advanced.alpha_weight_radius = Some(effort as u32 % 17);
        assert!(Transcoder::new(settings).is_ok());
    }
    let mut fastest = baseline;
    fastest.quality = "fastest".into();
    assert_eq!(fastest.effective_effort().unwrap(), 0.0);
}

#[test]
fn perceptual_weights_are_multiplied_and_effective_floor_is_reported() {
    let mut settings = advanced_test_settings();
    settings.advanced.perceptual = true;
    let neutral = crate::astc_encoder::effective_channel_weights(&settings).unwrap();
    for (actual, expected) in neutral.into_iter().zip([0.675, 1.3275, 0.2475, 1.0]) {
        assert!((actual - expected).abs() < 0.00001);
    }
    settings.advanced.channel_weights = [2.0, 1.0, 1.0, 4.0];
    let weighted = crate::astc_encoder::effective_channel_weights(&settings).unwrap();
    assert!((weighted[0] - 1.35).abs() < 0.00001);
    assert_eq!(weighted[3], 4.0);
    settings.advanced.perceptual = false;
    settings.advanced.channel_weights = [0.01, 64.0, 1.0, 1.0];
    let report = settings.encoder_settings();
    assert!((report["effectiveChannelWeights"][0].as_f64().unwrap() - 0.064).abs() < 0.00001);
    assert!((report["channelWeights"][0].as_f64().unwrap() - 0.01).abs() < 0.00001);
}

#[test]
fn public_ffi_default_matches_previous_encoder_bit_for_bit() {
    use astcenc_rs::{ConfigBuilder, Context, Extents, Flags, Image, Profile, Swizzle};
    for block in [(4, 4), (8, 6), (12, 12)] {
        for srgb in [false, true] {
            let mut settings = advanced_test_settings();
            settings.block = block;
            let mut actual = Encoder::new(&settings, srgb).unwrap();
            let config = ConfigBuilder::new()
                .with_flags(Flags::empty())
                .with_profile(if srgb {
                    Profile::LdrSrgb
                } else {
                    Profile::LdrRgba
                })
                .with_block_size(Extents::new(block.0, block.1))
                .with_preset(astcenc_rs::PRESET_THOROUGH)
                .build()
                .unwrap();
            let mut previous = Context::with_threads(1, config).unwrap();
            for (width, height) in [(17, 9), (7, 5), (1, 1)] {
                let pixels: Vec<u8> = (0..height)
                    .flat_map(|y| {
                        (0..width).flat_map(move |x| {
                            [
                                (x * 13 + y * 3) as u8,
                                (y * 23 + x * 5) as u8,
                                (x * 7 + y * 11) as u8,
                                ((x * 17 + y * 29) % 256) as u8,
                            ]
                        })
                    })
                    .collect();
                let extents = Extents::new(width, height);
                let expected = previous
                    .compress(
                        &Image {
                            extents,
                            data: vec![pixels.as_slice()],
                        },
                        Swizzle::rgba(),
                    )
                    .unwrap();
                let encoded = actual.compress(&pixels, width, height).unwrap();
                assert_eq!(
                    encoded, expected,
                    "footprint={block:?} srgb={srgb} {width}x{height}"
                );
                let old_pixels = previous
                    .decompress::<u8>(&expected, extents, Swizzle::rgba())
                    .unwrap();
                assert_eq!(
                    actual.decompress(&encoded, width, height).unwrap(),
                    old_pixels.data[0]
                );
            }
        }
    }
}

#[test]
fn cached_context_changes_advanced_settings_without_changing_source_channels() {
    let original = alpha_edge_resource();
    let settings = advanced_test_settings();
    let run = |settings: &Settings| {
        crate::conversion::with_transcoder(settings, |encoder| encoder.resource(&original, None))
            .unwrap()
            .bytes
    };
    let baseline = run(&settings);
    let mut weighted = settings.clone();
    weighted.advanced.channel_weights[3] = 2.0;
    let weighted_result = run(&weighted);
    assert_ne!(weighted_result, baseline);
    assert_eq!(
        weighted_result,
        Transcoder::new(weighted)
            .unwrap()
            .resource(&original, None)
            .unwrap()
            .bytes
    );
    let mut variants = vec![settings.clone(); 3];
    variants[0].advanced.effort = Some(0.0);
    variants[1].advanced.alpha_weight_radius = Some(1);
    variants[2].advanced.perceptual = true;
    for variant in variants {
        assert_eq!(
            run(&variant),
            Transcoder::new(variant)
                .unwrap()
                .resource(&original, None)
                .unwrap()
                .bytes
        );
    }
    assert_eq!(run(&settings), baseline);
    let mut explicit = settings.clone();
    explicit.quality = "fastest".into();
    explicit.advanced.effort = Some(98.0);
    assert_eq!(run(&explicit), baseline);
}

#[test]
fn invalid_settings_fail_before_creating_output_even_without_textures() {
    let temp = tempfile::tempdir().unwrap();
    let input = temp.path().join("gta");
    let unpacked = temp.path().join("unpacked");
    fs::create_dir(&input).unwrap();
    fs::create_dir(&unpacked).unwrap();
    fs::write(
        unpacked.join("readme.txt"),
        b"No texture resource in this archive",
    )
    .unwrap();
    crate::conversion::pack_archive(&unpacked, &input.join("assets.rpf")).unwrap();
    let original = fs::read(input.join("assets.rpf")).unwrap();
    let mut invalid = vec![advanced_test_settings(); 4];
    invalid[0].block = (7, 7);
    invalid[1].advanced.effort = Some(f32::NAN);
    invalid[2].advanced.channel_weights[2] = f32::INFINITY;
    invalid[3].advanced.alpha_weight_radius = Some(17);
    for settings in invalid {
        for dry_run in [false, true] {
            let output = temp.path().join("never-created/output");
            let samples = temp.path().join("never-created/samples");
            let result = crate::conversion::convert(crate::conversion::ConvertOptions {
                input: input.clone(),
                output: Some(output),
                in_place: false,
                archives: vec![],
                settings: settings.clone(),
                jobs: 1,
                samples: Some(samples),
                dry_run,
                keep_going: true,
                repair_pages: false,
                require_no_bc: true,
            });
            assert!(result.is_err());
            assert!(!temp.path().join("never-created").exists());
            assert_eq!(fs::read(input.join("assets.rpf")).unwrap(), original);
            assert!(!input.join("rpf-astc-report.json").exists());
        }
    }
}

#[test]
fn npot_nonconstant_array_keeps_block_rows_layers_and_mip_boundaries() {
    let (mut system, mut graphics) = prepare_rsc7(&resource_fixture(2, false)).unwrap();
    put16(&mut system, 0x250, 17);
    put16(&mut system, 0x252, 9);
    system[0x25d] = 5;
    let mut position = 0x80;
    for layer in 0..2 {
        for mip in 0..5 {
            let width = (17u32 >> mip).max(1);
            let height = (9u32 >> mip).max(1);
            for by in 0..height.div_ceil(4) {
                for bx in 0..width.div_ceil(4) {
                    let color = (((5 + layer * 21) << 11)
                        | ((8 + mip * 10) << 5)
                        | (2 + bx * 4 + by * 2)) as u16;
                    put16(&mut graphics, position, color);
                    put16(&mut graphics, position + 2, color);
                    graphics[position + 4..position + 8].fill(0);
                    position += 8;
                }
            }
        }
    }
    let original = encode_fixture(&system, &graphics);
    for block in [(8u32, 6u32), (12, 12)] {
        let output = converter(block).resource(&original, None).unwrap();
        let (system, actual_graphics) = prepare_rsc7(&output.bytes).unwrap();
        let texture = textures(&system, &actual_graphics).unwrap().remove(0);
        assert_eq!(
            (
                texture.width,
                texture.height,
                texture.layers,
                texture.levels,
                texture.image_type
            ),
            (17, 9, 2, 5, 0)
        );
        assert_eq!(&actual_graphics[..0x80], &graphics[..0x80]);
        assert_eq!(&actual_graphics[0x500..0x600], &graphics[0x500..0x600]);
        let mut offset = texture.data_offset;
        for layer in 0..2 {
            for mip in 0..5 {
                let width = (17u32 >> mip).max(1) as usize;
                let height = (9u32 >> mip).max(1) as usize;
                // Independent byte addressing: do not use Texture::mip_layout here.
                let size = ((width + block.0 as usize - 1) / block.0 as usize)
                    * ((height + block.1 as usize - 1) / block.1 as usize)
                    * 16;
                let mut pixels = vec![0u32; width * height];
                texture2ddecoder::decode_astc(
                    &actual_graphics[offset..offset + size],
                    width,
                    height,
                    block.0 as usize,
                    block.1 as usize,
                    &mut pixels,
                )
                .unwrap();
                let red = (5 + layer * 21) * 8 + (5 + layer * 21) / 4;
                let green = (8 + mip * 10) * 4 + (8 + mip * 10) / 16;
                for pixel in &pixels {
                    assert!((((pixel >> 16) & 255) as i32 - red as i32).abs() <= 16);
                    assert!((((pixel >> 8) & 255) as i32 - green as i32).abs() <= 16);
                    assert_eq!(pixel >> 24, 255);
                }
                if mip == 0 {
                    let blue = |x: usize, y: usize| (pixels[y * width + x] & 255) as i32;
                    assert!(blue(width - 1, 0) > blue(0, 0) + 40);
                    assert!(blue(0, height - 1) > blue(0, 0) + 15);
                }
                offset += size;
            }
        }
        let expected_bytes = if block == (8, 6) { 320 } else { 192 };
        assert_eq!(offset - texture.data_offset, expected_bytes);
        assert_eq!(output.counts.astc_bytes, expected_bytes as u64);
    }
}

#[test]
fn runtime_writable_textures_keep_original_bc_bytes() {
    for (name, flags) in [("pedmugshot_01", 0), ("mp_crewpalette", 0), ("color", 1)] {
        let original = resource_fixture(1, false);
        let (mut system, graphics) = prepare_rsc7(&original).unwrap();
        system[0x500..0x580].fill(0);
        system[0x500..0x500 + name.len()].copy_from_slice(name.as_bytes());
        put32(&mut system, 0x248, flags);
        let original = encode_fixture(&system, &graphics);
        let converted = converter((6, 6)).resource(&original, None).unwrap();
        assert_eq!(converted.counts.converted, 0);
        assert_eq!(converted.counts.retained["runtime_writable"], 1);
        assert_eq!(converted.counts.remaining_bc, 1);
        assert_eq!(converted.bytes, original);
    }
}

#[test]
fn retained_owner_of_shared_payload_is_unchanged() {
    let source = resource_fixture(1, false);
    let (mut system, graphics) = prepare_rsc7(&source).unwrap();
    put16(&mut system, 0x28, 2);
    put16(&mut system, 0x38, 2);
    put64(&mut system, 0x188, 0x50000300);
    let copy = system[0x200..0x290].to_vec();
    system[0x300..0x390].copy_from_slice(&copy);
    put64(&mut system, 0x328, 0x50000540);
    system[0x540..0x54a].copy_from_slice(b"script_rt\0");
    // 12x12 footprint is smaller than the original BC payload. The converted
    // owner must still be relocated because the render target retains BC.
    let output = converter((12, 12))
        .resource(&encode_fixture(&system, &graphics), None)
        .unwrap();
    let (actual_sys, actual_gfx) = prepare_rsc7(&output.bytes).unwrap();
    assert_eq!(output.counts.converted, 1);
    assert_eq!(output.counts.retained["render_target"], 1);
    assert_eq!(&actual_sys[0x300..0x390], &system[0x300..0x390]);
    assert_eq!(&actual_gfx[0x80..0x80 + 56], &graphics[0x80..0x80 + 56]);
    assert_ne!(u64_le(&actual_sys, 0x270), u64_le(&actual_sys, 0x370));
}

#[test]
fn different_alias_shapes_do_not_share_encoded_mip_layouts() {
    let source = resource_fixture(1, false);
    let (mut system, graphics) = prepare_rsc7(&source).unwrap();
    put16(&mut system, 0x28, 2);
    put16(&mut system, 0x38, 2);
    put64(&mut system, 0x188, 0x50000300);
    let copy = system[0x200..0x290].to_vec();
    system[0x300..0x390].copy_from_slice(&copy);
    system[0x25d] = 1;
    system[0x35d] = 1;
    put16(&mut system, 0x350, 4);
    put16(&mut system, 0x352, 16);
    let output = converter((6, 6))
        .resource(&encode_fixture(&system, &graphics), None)
        .unwrap();
    let (sys, _) = prepare_rsc7(&output.bytes).unwrap();
    assert_eq!(output.counts.converted, 2);
    assert_ne!(u64_le(&sys, 0x270), u64_le(&sys, 0x370));
}

#[test]
fn bad_gpu_pointer_and_truncated_mip_chain_fail_without_output() {
    let source = resource_fixture(1, false);
    let (mut system, graphics) = prepare_rsc7(&source).unwrap();
    put64(&mut system, 0x270, 0x60001ff8);
    let source = encode_fixture(&system, &graphics);
    let result = converter((6, 6)).resource(&source, None);
    assert!(format!("{:#}", result.err().unwrap()).contains("outside its graphics section"));
}

#[test]
fn null_dictionary_slots_do_not_hide_later_textures() {
    let (mut system, graphics) = prepare_rsc7(&resource_fixture(1, false)).unwrap();
    put16(&mut system, 0x28, 2);
    put16(&mut system, 0x38, 2);
    put64(&mut system, 0x180, 0);
    put64(&mut system, 0x188, 0x50000200);
    let output = converter((4, 4))
        .resource(&encode_fixture(&system, &graphics), None)
        .unwrap();
    assert_eq!(output.counts.converted, 1);
    assert_eq!(output.counts.remaining_bc, 0);
}

#[test]
fn ordinary_bc_previews_use_deep_dictionary_validation_and_alpha_rules() {
    let (mut system, mut graphics) = prepare_rsc7(&resource_fixture(1, false)).unwrap();
    put16(&mut system, 0x28, 2);
    put16(&mut system, 0x38, 2);
    put64(&mut system, 0x180, 0);
    put64(&mut system, 0x188, 0x50000200);
    for block in graphics[0x80..0xa0].chunks_exact_mut(8) {
        block[..2].copy_from_slice(&0u16.to_le_bytes());
        block[2..4].copy_from_slice(&0xffffu16.to_le_bytes());
        block[4..8].fill(0xff);
    }
    let bytes = encode_fixture(&system, &graphics);
    let textures = crate::conversion::texture_list(&bytes, "ytd").unwrap();
    assert_eq!(textures.as_array().unwrap().len(), 1);
    let png = crate::conversion::texture_preview(&bytes, "ytd", 0).unwrap();
    let decoded = image::load_from_memory(&png).unwrap().to_rgba8();
    assert!(decoded.pixels().all(|pixel| pixel[3] == 0));
    put64(&mut system, 0x180, 0x50001ff8);
    assert!(crate::conversion::texture_list(&encode_fixture(&system, &graphics), "ytd").is_err());
}

#[test]
fn broken_dictionary_members_and_unowned_bc_are_not_empty_resources() {
    let (system, graphics) = prepare_rsc7(&resource_fixture(1, false)).unwrap();
    let mut bad_pointer = system.clone();
    put64(&mut bad_pointer, 0x270, 0x60002000);
    assert!(
        format!("{:#}", textures(&bad_pointer, &graphics).unwrap_err())
            .contains("invalid graphics pointer")
    );

    let mut broken_member = system.clone();
    put16(&mut broken_member, 0x28, 2);
    put16(&mut broken_member, 0x38, 2);
    put64(&mut broken_member, 0x188, 0x50008000);
    assert!(
        format!("{:#}", textures(&broken_member, &graphics).unwrap_err())
            .contains("Truncated texture object")
    );

    let mut unowned = system.clone();
    unowned[..64].fill(0);
    assert!(textures(&unowned, &graphics)
        .unwrap_err()
        .to_string()
        .contains("Unsupported texture ownership"));
    // An isolated FourCC has none of the object/name/GPU evidence above.
    unowned[0x228..0x230].fill(0);
    assert!(textures(&unowned, &graphics).unwrap().is_empty());
}

#[test]
fn object_pointer_arrays_and_hashes_do_not_masquerade_as_texture_dictionaries() {
    // Procedural versions of false positives found in core.ypt and
    // cut_michael1.ypt: an object array as pgBase, or a hash as signed refcount.
    for pointer_as_vtable in [false, true] {
        let (mut system, graphics) = prepare_rsc7(&resource_fixture(1, false)).unwrap();
        let root = system[..64].to_vec();
        system[0x800..0x840].copy_from_slice(&root);
        if pointer_as_vtable {
            put64(&mut system, 0x800, 0x50000700);
        } else {
            put32(&mut system, 0x818, 0xce8e57a7);
        }
        put16(&mut system, 0x828, 2);
        put16(&mut system, 0x838, 2);
        put64(&mut system, 0x830, 0x50000900);
        put64(&mut system, 0x900, 0x14060fa58);
        put64(&mut system, 0x908, 0x50000200);
        assert_eq!(textures(&system, &graphics).unwrap().len(), 1);
        let output = converter((4, 4))
            .resource(&encode_fixture(&system, &graphics), None)
            .unwrap();
        assert_eq!(output.counts.converted, 1);
    }
}

#[test]
fn ytd_root_validation_is_not_satisfied_by_a_second_dictionary() {
    let (mut system, graphics) = prepare_rsc7(&resource_fixture(1, false)).unwrap();
    let root = system[..64].to_vec();
    system[0x800..0x840].copy_from_slice(&root);
    put16(&mut system, 0x3a, 0);
    assert_eq!(textures(&system, &graphics).unwrap().len(), 1);
    assert!(format!(
        "{:#}",
        textures_for_resource(&system, &graphics, "broken.ytd").unwrap_err()
    )
    .contains("inconsistent counts"));
}

#[test]
fn retained_payloads_that_overlap_inside_bc_are_preserved() {
    for (format, data) in [(28, 0x60000088), (21, 0x60000060), (0xdeadbeef, 0x60000060)] {
        let (mut system, graphics) = prepare_rsc7(&resource_fixture(1, false)).unwrap();
        put16(&mut system, 0x28, 2);
        put16(&mut system, 0x38, 2);
        put64(&mut system, 0x188, 0x50000300);
        system[0x25d] = 1;
        let copy = system[0x200..0x290].to_vec();
        system[0x300..0x390].copy_from_slice(&copy);
        put32(&mut system, 0x358, format);
        put64(&mut system, 0x370, data);
        let output = converter((12, 12))
            .resource(&encode_fixture(&system, &graphics), None)
            .unwrap();
        let (actual_sys, actual_gfx) = prepare_rsc7(&output.bytes).unwrap();
        assert_eq!(output.counts.converted, 1);
        assert_eq!(&actual_gfx[..graphics.len()], graphics.as_slice());
        assert_eq!(&actual_sys[0x300..0x390], &system[0x300..0x390]);
        assert!(u64_le(&actual_sys, 0x270) >= 0x60000000 + graphics.len() as u64);
    }
}

#[test]
fn overlapping_bc_owners_and_other_gpu_references_force_relocation() {
    for second_texture in [false, true] {
        let (mut system, graphics) = prepare_rsc7(&resource_fixture(1, false)).unwrap();
        system[0x25d] = 1;
        if second_texture {
            put16(&mut system, 0x28, 2);
            put16(&mut system, 0x38, 2);
            put64(&mut system, 0x188, 0x50000300);
            let copy = system[0x200..0x290].to_vec();
            system[0x300..0x390].copy_from_slice(&copy);
            put64(&mut system, 0x370, 0x60000088);
        } else {
            // A non-texture object points into the BC payload.
            put64(&mut system, 0x600, 0x60000088);
        }
        let output = converter((12, 12))
            .resource(&encode_fixture(&system, &graphics), None)
            .unwrap();
        let (actual_sys, actual_gfx) = prepare_rsc7(&output.bytes).unwrap();
        assert_eq!(output.counts.converted, 1 + u64::from(second_texture));
        assert_eq!(&actual_gfx[..graphics.len()], graphics.as_slice());
        assert!(u64_le(&actual_sys, 0x270) >= 0x60000000 + graphics.len() as u64);
    }
}

#[test]
fn unknown_gpu_owner_before_bc_prevents_reclaiming_its_possible_tail() {
    let (mut system, graphics) = prepare_rsc7(&resource_fixture(1, false)).unwrap();
    system[0x25d] = 1;
    // The unknown object's extent is not available to the transcoder. Its
    // payload could start at 0x60 and continue into the BC texture at 0x80.
    put64(&mut system, 0x600, 0x60000060);
    let output = converter((12, 12))
        .resource(&encode_fixture(&system, &graphics), None)
        .unwrap();
    let (actual_sys, actual_gfx) = prepare_rsc7(&output.bytes).unwrap();
    assert_eq!(output.counts.converted, 1);
    assert_eq!(&actual_gfx[..graphics.len()], graphics.as_slice());
    assert_eq!(u64_le(&actual_sys, 0x600), 0x60000060);
    assert!(u64_le(&actual_sys, 0x270) >= 0x60000000 + graphics.len() as u64);
}

#[test]
fn page_map_relocation_preserves_serialized_metadata_chain() {
    let original = resource_fixture(1, false);
    let (mut system, mut graphics) = prepare_rsc7(&original).unwrap();
    put64(&mut system, 0x1000, 0x50000700);
    system[0x700..0x710].copy_from_slice(b"metadata fixture");
    // Force more pages than the old map's two slots under the 8 KiB minimum.
    graphics.resize(8192 * 257, 0xa5);
    let output = write_resource(&original, &mut system, &mut graphics).unwrap();
    let (actual_sys, _) = prepare_rsc7(&output).unwrap();
    let new_map = (u64_le(&actual_sys, 8) - 0x50000000) as usize;
    assert_ne!(new_map, 0x1000);
    assert_eq!(u64_le(&actual_sys, new_map), 0x50000700);
    assert_eq!(&actual_sys[0x700..0x710], &system[0x700..0x710]);
}

#[test]
fn prefixed_deflate_takes_precedence_when_its_length_matches_a_raw_section() {
    // Independent raw-DEFLATE fixture: a 494-byte stored block followed by a
    // fixed-Huffman block of 18 zero bytes, verified against zlib.
    let prefix: Vec<u8> = (0..494).map(|i| i as u8).collect();
    let mut compressed = vec![0, 238, 1, 17, 254];
    compressed.extend_from_slice(&prefix);
    compressed.extend_from_slice(&[99, 96, 64, 7, 0]);
    assert_eq!(compressed.len(), 504);
    let mut resource = b"RSC7".to_vec();
    resource.extend(13u32.to_le_bytes());
    resource.extend(0x08000000u32.to_le_bytes());
    resource.extend(0u32.to_le_bytes());
    resource.extend([0xa5; 8]);
    resource.extend(compressed);
    let (system, graphics) = prepare_rsc7(&resource).unwrap();
    assert_eq!(system.len(), 512);
    assert_eq!(&system[..494], prefix.as_slice());
    assert_eq!(&system[494..], &[0; 18]);
    assert!(graphics.is_empty());
}

#[test]
fn scan_and_conversion_share_blocking_policy_for_unsupported_srgb_bc() {
    for format in [0x31495441, 0x32495441, 0x20364342] {
        let (mut system, graphics) = prepare_rsc7(&resource_fixture(1, true)).unwrap();
        put32(&mut system, 0x258, format);
        let textures = textures(&system, &graphics).unwrap();
        let scan = Counts::scan(&textures).unwrap();
        let input = encode_fixture(&system, &graphics);
        let output = converter((4, 4)).resource(&input, None).unwrap();
        assert_eq!(
            (scan.convertible_bc, scan.blocked_bc, scan.remaining_bc),
            (0, 1, 1)
        );
        assert_eq!(scan.retained, output.counts.retained);
        assert_eq!(
            scan.blocked_bc_samples[0]["reason"],
            "unsupported_srgb_format"
        );
        assert_eq!(output.bytes, input);
    }
}

#[test]
fn single_and_dual_channel_bc_keep_d3d_sampling_defaults() {
    let one = [127u8, 127, 0, 0, 0, 0, 0, 0];
    let two: Vec<_> = one
        .into_iter()
        .chain([191u8, 191, 0, 0, 0, 0, 0, 0])
        .collect();
    let r = decode_bc(0x31495441, &one, 4, 4).unwrap();
    assert_eq!(&r[..4], &[127, 0, 0, 255]);
    let rg = decode_bc(0x32495441, &two, 4, 4).unwrap();
    assert_eq!(&rg[..4], &[127, 191, 0, 255]);
}

#[test]
fn bc1_preserves_punchthrough_alpha_through_astc_mips() {
    let transparent = [0, 0, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff];
    assert!(decode_bc(0x31545844, &transparent, 4, 4)
        .unwrap()
        .iter()
        .all(|&byte| byte == 0));
    let (system, mut graphics) = prepare_rsc7(&resource_fixture(1, false)).unwrap();
    for block in graphics[0x80..0x80 + 56].chunks_exact_mut(8) {
        block.copy_from_slice(&transparent);
    }
    let output = converter((4, 4))
        .resource(&encode_fixture(&system, &graphics), None)
        .unwrap();
    let (system, graphics) = prepare_rsc7(&output.bytes).unwrap();
    let texture = textures(&system, &graphics).unwrap().remove(0);
    let mut offset = texture.data_offset;
    for mip in 0..texture.levels {
        let (width, height, size) = texture.mip_layout(mip).unwrap();
        let mut pixels = vec![0u32; width * height];
        texture2ddecoder::decode_astc(
            &graphics[offset..offset + size],
            width,
            height,
            4,
            4,
            &mut pixels,
        )
        .unwrap();
        assert!(pixels.iter().all(|pixel| pixel >> 24 == 0));
        offset += size;
    }
}

#[test]
fn bc2_bc3_always_use_four_colors_without_changing_alpha() {
    for format in [0x32545844, 0x33545844, 0x34545844, 0x35545844] {
        let mut block = [0xffu8; 16];
        if format == 0x32545844 || format == 0x33545844 {
            block[..8].fill(0x88);
        } else {
            block[..8].fill(0);
            block[0] = 136;
            block[1] = 136;
        }
        put16(&mut block, 8, 0x001f);
        put16(&mut block, 10, 0xf800);
        block[12..].fill(0xe4);
        let expected = [
            [0, 0, 255, 136],
            [255, 0, 0, 136],
            [85, 0, 170, 136],
            [170, 0, 85, 136],
        ];
        let pixels = decode_bc(format, &block, 4, 4).unwrap();
        for row in pixels.chunks_exact(16) {
            assert_eq!(row, expected.concat());
        }
        assert_eq!(decode_bc(format, &block, 1, 1).unwrap(), expected[0]);
        put16(&mut block, 10, 0x001f);
        block[12..].fill(0xff);
        let pixels = decode_bc(format, &block, 4, 4).unwrap();
        assert!(pixels
            .chunks_exact(4)
            .all(|pixel| pixel == [0, 0, 255, 136]));
    }
}

#[test]
fn relocated_exclusive_bc_payload_is_erased_without_touching_neighbors() {
    let (system, graphics) = prepare_rsc7(&resource_fixture(1, false)).unwrap();
    let output = converter((4, 4))
        .resource(&encode_fixture(&system, &graphics), None)
        .unwrap();
    let (actual_sys, actual_gfx) = prepare_rsc7(&output.bytes).unwrap();
    assert_ne!(u64_le(&actual_sys, 0x270), u64_le(&system, 0x270));
    assert!(actual_gfx[0x80..0x80 + 56].iter().all(|&byte| byte == 0));
    assert_eq!(&actual_gfx[..0x80], &graphics[..0x80]);
    assert_eq!(
        &actual_gfx[0x80 + 56..graphics.len()],
        &graphics[0x80 + 56..]
    );
}

#[test]
fn zero_alpha_does_not_discard_rgb_data_channels() {
    for srgb in [false, true] {
        let (mut system, mut graphics) = prepare_rsc7(&resource_fixture(1, srgb)).unwrap();
        put32(&mut system, 0x258, 0x35545844);
        graphics[0x80..0x80 + 112].fill(0);
        for block in graphics[0x80..0x80 + 112].chunks_exact_mut(16) {
            put16(block, 8, 0x001f);
            put16(block, 10, 0x001f);
        }
        let output = converter((4, 4))
            .resource(&encode_fixture(&system, &graphics), None)
            .unwrap();
        let (system, graphics) = prepare_rsc7(&output.bytes).unwrap();
        let texture = textures(&system, &graphics).unwrap().remove(0);
        let mut offset = texture.data_offset;
        for mip in 0..texture.levels {
            let (width, height, size) = texture.mip_layout(mip).unwrap();
            let mut pixels = vec![0u32; width * height];
            texture2ddecoder::decode_astc(
                &graphics[offset..offset + size],
                width,
                height,
                4,
                4,
                &mut pixels,
            )
            .unwrap();
            assert!(pixels.iter().all(|&pixel| pixel == 0x000000ff));
            offset += size;
        }
    }
}

#[test]
fn nested_conversion_and_in_place_rerun_keep_unrelated_files() {
    use crate::conversion;
    let temp = tempfile::tempdir().unwrap();
    let input = temp.path().join("gta");
    let tree = temp.path().join("unpacked");
    std::fs::create_dir_all(tree.join("nested.rpf/empty")).unwrap();
    let texture = resource_fixture(1, false);
    std::fs::write(tree.join("nested.rpf/texture.ytd"), &texture).unwrap();
    std::fs::write(tree.join("readme.txt"), b"unchanged").unwrap();
    std::fs::create_dir(&input).unwrap();
    std::fs::write(input.join("game.bin"), b"executable placeholder").unwrap();
    conversion::pack_archive(&tree, &input.join("assets.rpf")).unwrap();
    let source = std::fs::read(input.join("assets.rpf")).unwrap();
    let output = temp.path().join("converted");
    let options = conversion::ConvertOptions {
        input: input.clone(),
        output: Some(output.clone()),
        in_place: false,
        archives: Vec::new(),
        settings: Settings {
            block: (6, 6),
            quality: "fast".into(),
            advanced: Default::default(),
        },
        jobs: 2,
        samples: None,
        dry_run: false,
        keep_going: false,
        repair_pages: false,
        require_no_bc: true,
    };
    let scan = conversion::convert(conversion::ConvertOptions {
        dry_run: true,
        ..options.clone()
    })
    .unwrap();
    let report = conversion::convert(options.clone()).unwrap();
    assert_eq!(
        scan["summary"]["sizeEstimate"]["sourceBytes"],
        report["summary"]["sourceTextureBytes"]
    );
    assert_eq!(
        scan["summary"]["sizeEstimate"]["byBlock"]["6x6"],
        report["summary"]["astcTextureBytes"]
    );
    assert_eq!(
        scan["summary"]["sizeEstimate"],
        report["summary"]["sizeEstimate"]
    );
    assert_eq!(report["summary"]["converted"], 1);
    assert_eq!(report["summary"]["archives"], 2);
    assert_eq!(report["complete"], true);
    assert_eq!(report["summary"]["bcFree"], true);
    assert_eq!(report["summary"]["remainingBc"], 0);
    assert_eq!(std::fs::read(input.join("assets.rpf")).unwrap(), source);
    assert_eq!(
        std::fs::read(output.join("game.bin")).unwrap(),
        b"executable placeholder"
    );
    let expanded = temp.path().join("expanded");
    conversion::extract_archive(&output.join("assets.rpf"), &expanded).unwrap();
    assert!(expanded.join("nested.rpf/empty").is_dir());
    assert_eq!(
        std::fs::read(expanded.join("readme.txt")).unwrap(),
        b"unchanged"
    );
    let before = std::fs::read(output.join("assets.rpf")).unwrap();
    let rerun = conversion::convert(conversion::ConvertOptions {
        input: output.clone(),
        output: None,
        in_place: true,
        ..options.clone()
    })
    .unwrap();
    assert_eq!(rerun["summary"]["converted"], 0);
    assert_eq!(rerun["summary"]["alreadyAstc"], 1);
    assert_eq!(std::fs::read(output.join("assets.rpf")).unwrap(), before);
    // A copied ASTC directory gets the current run's report, not a conflicting
    // copy of the previous converter report.
    let second_output = temp.path().join("second-copy");
    let copied = conversion::convert(conversion::ConvertOptions {
        input: output,
        output: Some(second_output.clone()),
        ..options
    })
    .unwrap();
    assert_eq!(copied["summary"]["alreadyAstc"], 1);
    let saved: serde_json::Value =
        serde_json::from_slice(&std::fs::read(second_output.join("rpf-astc-report.json")).unwrap())
            .unwrap();
    assert_eq!(saved["output"], serde_json::json!(second_output));
}

#[test]
fn failed_resource_is_retained_only_on_request_and_report_is_incomplete() {
    use crate::conversion;
    let temp = tempfile::tempdir().unwrap();
    let input = temp.path().join("gta");
    let tree = temp.path().join("unpacked");
    std::fs::create_dir(&tree).unwrap();
    std::fs::create_dir(&input).unwrap();
    std::fs::write(tree.join("good.ytd"), resource_fixture(1, false)).unwrap();
    let mut bad = resource_fixture(1, false);
    bad.truncate(20);
    std::fs::write(tree.join("bad.ytd"), &bad).unwrap();
    conversion::pack_archive(&tree, &input.join("assets.rpf")).unwrap();
    let original = std::fs::read(input.join("assets.rpf")).unwrap();
    let options = conversion::ConvertOptions {
        input: input.clone(),
        output: None,
        in_place: true,
        archives: Vec::new(),
        settings: Settings {
            block: (6, 6),
            quality: "fast".into(),
            advanced: Default::default(),
        },
        jobs: 2,
        samples: None,
        dry_run: false,
        keep_going: false,
        repair_pages: false,
        require_no_bc: false,
    };
    assert!(conversion::convert(options.clone()).is_err());
    assert_eq!(std::fs::read(input.join("assets.rpf")).unwrap(), original);
    assert!(!input.join("rpf-astc-report.json").exists());
    let report = conversion::convert(conversion::ConvertOptions {
        keep_going: true,
        ..options
    })
    .unwrap();
    assert_eq!(report["finished"], true);
    assert_eq!(report["complete"], false);
    assert_eq!(report["summary"]["converted"], 1);
    assert_eq!(report["summary"]["errors"].as_array().unwrap().len(), 1);
    assert_eq!(report["summary"]["errors"][0]["path"], "assets.rpf/bad.ytd");
    assert_eq!(report["summary"]["bcFree"], false);
    let expanded = temp.path().join("expanded");
    conversion::extract_archive(&input.join("assets.rpf"), &expanded).unwrap();
    assert_eq!(std::fs::read(expanded.join("bad.ytd")).unwrap(), bad);
}

#[test]
fn strict_bc_free_conversion_rejects_retained_hdr_without_publishing() {
    use crate::conversion;
    let temp = tempfile::tempdir().unwrap();
    let input = temp.path().join("gta");
    let tree = temp.path().join("unpacked");
    std::fs::create_dir(&tree).unwrap();
    std::fs::create_dir(&input).unwrap();
    let (mut system, graphics) = prepare_rsc7(&resource_fixture(1, false)).unwrap();
    put32(&mut system, 0x258, 0x20364342);
    std::fs::write(tree.join("hdr.ytd"), encode_fixture(&system, &graphics)).unwrap();
    std::fs::write(tree.join("color.ytd"), resource_fixture(1, false)).unwrap();
    conversion::pack_archive(&tree, &input.join("assets.rpf")).unwrap();
    let original = std::fs::read(input.join("assets.rpf")).unwrap();
    let options = conversion::ConvertOptions {
        input: input.clone(),
        output: None,
        in_place: true,
        archives: Vec::new(),
        settings: Settings {
            block: (4, 4),
            quality: "fast".into(),
            advanced: Default::default(),
        },
        jobs: 1,
        samples: None,
        dry_run: true,
        keep_going: false,
        repair_pages: false,
        require_no_bc: true,
    };
    let scan = conversion::convert(options.clone()).unwrap();
    assert_eq!(scan["summary"]["remainingBc"], 2);
    assert_eq!(scan["summary"]["convertibleBc"], 1);
    assert_eq!(scan["summary"]["blockedBc"], 1);
    assert_eq!(
        scan["summary"]["blockedBcSamples"][0]["path"],
        "assets.rpf/hdr.ytd"
    );
    assert_eq!(scan["summary"]["bcFree"], false);
    let error = conversion::convert(conversion::ConvertOptions {
        dry_run: false,
        ..options.clone()
    })
    .unwrap_err();
    assert!(error.to_string().contains("1 retained BC textures"));
    assert_eq!(std::fs::read(input.join("assets.rpf")).unwrap(), original);
    let report = conversion::convert(conversion::ConvertOptions {
        dry_run: false,
        repair_pages: false,
        require_no_bc: false,
        ..options
    })
    .unwrap();
    assert_eq!(report["complete"], false);
    assert_eq!(report["summary"]["remainingBc"], 1);
    assert_eq!(report["summary"]["bcFree"], false);
}

#[test]
fn embedded_texture_parse_errors_cannot_be_reported_as_bc_free() {
    use crate::conversion;
    let temp = tempfile::tempdir().unwrap();
    let input = temp.path().join("gta");
    let tree = temp.path().join("unpacked");
    std::fs::create_dir(&tree).unwrap();
    std::fs::create_dir(&input).unwrap();
    let (mut system, graphics) = prepare_rsc7(&resource_fixture(1, false)).unwrap();
    put64(&mut system, 0x270, 0x60002000);
    std::fs::write(
        tree.join("fragment.yft"),
        encode_fixture(&system, &graphics),
    )
    .unwrap();
    conversion::pack_archive(&tree, &input.join("assets.rpf")).unwrap();
    let original = std::fs::read(input.join("assets.rpf")).unwrap();
    let options = conversion::ConvertOptions {
        input: input.clone(),
        output: None,
        in_place: true,
        archives: Vec::new(),
        settings: Settings {
            block: (4, 4),
            quality: "fast".into(),
            advanced: Default::default(),
        },
        jobs: 1,
        samples: None,
        dry_run: false,
        keep_going: true,
        repair_pages: false,
        require_no_bc: true,
    };
    let error = conversion::convert(options.clone()).unwrap_err();
    assert!(error.to_string().contains("1 resource errors"));
    assert_eq!(std::fs::read(input.join("assets.rpf")).unwrap(), original);
    let report = conversion::convert(conversion::ConvertOptions {
        dry_run: true,
        ..options
    })
    .unwrap();
    assert_eq!(report["complete"], false);
    assert_eq!(report["summary"]["bcFree"], false);
    assert_eq!(
        report["summary"]["errors"][0]["path"],
        "assets.rpf/fragment.yft"
    );
}

#[test]
fn high_quality_presets_preserve_constant_color_mips() {
    for quality in ["very-thorough", "exhaustive"] {
        let mut encoder = Transcoder::new(Settings {
            block: (4, 4),
            quality: quality.into(),
            advanced: Default::default(),
        })
        .unwrap();
        let output = encoder.resource(&resource_fixture(1, false), None).unwrap();
        let (system, graphics) = prepare_rsc7(&output.bytes).unwrap();
        let texture = textures(&system, &graphics).unwrap().remove(0);
        let mut offset = texture.data_offset;
        for (mip, expected) in [0xffff0000u32, 0xff00ff00, 0xff0000ff, 0xffffffff]
            .into_iter()
            .enumerate()
        {
            let (width, height, size) = texture.mip_layout(mip as u32).unwrap();
            let mut pixels = vec![0u32; width * height];
            texture2ddecoder::decode_astc(
                &graphics[offset..offset + size],
                width,
                height,
                4,
                4,
                &mut pixels,
            )
            .unwrap();
            assert!(pixels.iter().all(|&pixel| pixel == expected));
            offset += size;
        }
    }
}

#[test]
fn stored_files_larger_than_preview_limit_and_large_resource_headers_roundtrip() {
    use crate::conversion;
    use std::fs::File;
    use std::os::unix::fs::FileExt;
    let temp = tempfile::tempdir().unwrap();
    let input = temp.path().join("files");
    std::fs::create_dir(&input).unwrap();
    let large = File::create(input.join("large.bin")).unwrap();
    let length = 256 * 1024 * 1024 + 513;
    large.set_len(length).unwrap();
    large.write_all_at(b"large-tail", length - 10).unwrap();
    let resource = File::create(input.join("large.ytd")).unwrap();
    let resource_length = 0x1000000 + 37;
    resource.set_len(resource_length).unwrap();
    resource
        .write_all_at(&resource_fixture(1, false)[..16], 0)
        .unwrap();
    resource
        .write_all_at(b"resource-tail", resource_length - 13)
        .unwrap();
    let packed = temp.path().join("packed.rpf");
    conversion::pack_archive(&input, &packed).unwrap();
    let output = temp.path().join("expanded");
    conversion::extract_archive(&packed, &output).unwrap();
    for (name, size, suffix) in [
        ("large.bin", length, &b"large-tail"[..]),
        ("large.ytd", resource_length, &b"resource-tail"[..]),
    ] {
        let file = File::open(output.join(name)).unwrap();
        assert_eq!(file.metadata().unwrap().len(), size);
        let mut actual = vec![0; suffix.len()];
        file.read_exact_at(&mut actual, size - suffix.len() as u64)
            .unwrap();
        assert_eq!(actual, suffix);
    }
    assert_eq!(
        &std::fs::read(output.join("large.ytd")).unwrap()[..16],
        &resource_fixture(1, false)[..16]
    );
}

pub(crate) fn illegal_astc_fixture(
    bad_system: bool,
    bad_graphics: bool,
    metadata_root: bool,
) -> Vec<u8> {
    let (mut system, mut graphics) = prepare_rsc7(&resource_fixture(1, false)).unwrap();
    put32(&mut system, 0x258, astc_format(4, 4));
    if metadata_root {
        system[0x100b] = 1;
    }
    put64(&mut system, 0x1000, 0x50000700);
    system[0x700..0x710].copy_from_slice(b"metadata fixture");
    if bad_system {
        system.extend([0x31; 512]);
    }
    if bad_graphics {
        graphics.extend([0x93; 512]);
    }
    let mut bytes = encode_fixture(&system, &graphics);
    if bad_system {
        put32(&mut bytes, 8, 0x08020000);
    }
    if bad_graphics {
        put32(&mut bytes, 12, 0xd8020000);
    }
    bytes
}

#[test]
fn page_flags_never_emit_small_tail_chunks() {
    for size in [1, 512, 8191, 8192, 8193, 8704, 131073, 2097664, 67108865] {
        let flags = page_flags(size, 0xd0000000).unwrap();
        assert!(legal_page_flags(flags), "{size}: {flags:08x}");
        assert!(resource_size_from_flags(flags) >= size);
        assert_eq!(flags & 0xf0000000, 0xd0000000);
        assert!(page_count(flags) <= 64);
    }
    // All four tail fields are individually invalid at a 512-byte base.
    assert!(!legal_page_flags(0x0f000000));
    assert!(legal_page_flags(0x00020000));
}

#[test]
fn page_repair_preserves_astc_payload_pointers_metadata_and_valid_resources() {
    for (bad_system, bad_graphics) in [(true, false), (false, true), (true, true)] {
        for metadata_root in [false, true] {
            let original = illegal_astc_fixture(bad_system, bad_graphics, metadata_root);
            let (system, graphics) = prepare_rsc7(&original).unwrap();
            let records = textures(&system, &graphics).unwrap();
            let repaired = repair_pages(
                &original,
                system.clone(),
                graphics.clone(),
                Counts::scan(&records).unwrap(),
            )
            .unwrap();
            assert_eq!(repaired.counts.repaired_resources, 1);
            assert_eq!(repaired.counts.converted, 0);
            let sys_flags = u32_le(&repaired.bytes, 8);
            let gfx_flags = u32_le(&repaired.bytes, 12);
            assert!(legal_page_flags(sys_flags) && legal_page_flags(gfx_flags));
            let (actual_sys, actual_gfx) = prepare_rsc7(&repaired.bytes).unwrap();
            assert_eq!(&actual_gfx[..graphics.len()], &graphics);
            assert!(actual_gfx[graphics.len()..].iter().all(|b| *b == 0));
            assert_eq!(textures(&actual_sys, &actual_gfx).unwrap(), records);
            let map = (u64_le(&actual_sys, 8) - 0x50000000) as usize;
            assert_eq!(actual_sys[map + 8] as usize, page_count(sys_flags));
            assert_eq!(actual_sys[map + 9] as usize, page_count(gfx_flags));
            assert_eq!(
                u64_le(&actual_sys, map),
                if metadata_root {
                    0x50001000
                } else {
                    0x50000700
                }
            );
            for i in 0..system.len() {
                if (8..16).contains(&i) || (map + 8..map + 12).contains(&i) {
                    continue;
                }
                assert_eq!(actual_sys[i], system[i], "system byte {i:x}");
            }
            let again =
                repair_pages(&repaired.bytes, actual_sys, actual_gfx, Counts::default()).unwrap();
            assert_eq!(again.counts.repaired_resources, 0);
            assert_eq!(again.bytes, repaired.bytes);
        }
    }
    let valid = resource_fixture(1, false);
    let (system, graphics) = prepare_rsc7(&valid).unwrap();
    assert_eq!(
        repair_pages(&valid, system, graphics, Counts::default())
            .unwrap()
            .bytes,
        valid
    );
}

#[test]
fn recursive_archive_page_repair_publishes_without_converting_astc() {
    use crate::conversion;
    let temp = tempfile::tempdir().unwrap();
    let tree = temp.path().join("tree");
    std::fs::create_dir_all(tree.join("nested.rpf")).unwrap();
    let broken = illegal_astc_fixture(true, true, true);
    let valid = resource_fixture(1, false);
    std::fs::write(tree.join("nested.rpf/broken.ytd"), &broken).unwrap();
    std::fs::write(tree.join("valid.ytd"), &valid).unwrap();
    let input = temp.path().join("input");
    std::fs::create_dir(&input).unwrap();
    conversion::pack_archive(&tree, &input.join("assets.rpf")).unwrap();
    let options = conversion::ConvertOptions {
        input: input.clone(),
        output: None,
        in_place: true,
        archives: Vec::new(),
        settings: Settings {
            block: (4, 4),
            quality: "fast".into(),
            advanced: Default::default(),
        },
        jobs: 2,
        samples: None,
        dry_run: false,
        keep_going: false,
        require_no_bc: false,
        repair_pages: true,
    };
    let report = conversion::convert(options.clone()).unwrap();
    assert_eq!(report["summary"]["repairedResources"], 1);
    assert_eq!(report["summary"]["converted"], 0);
    assert_eq!(report["complete"], true);
    let expanded = temp.path().join("expanded");
    conversion::extract_archive(&input.join("assets.rpf"), &expanded).unwrap();
    let repaired = std::fs::read(expanded.join("nested.rpf/broken.ytd")).unwrap();
    let (_, before) = prepare_rsc7(&broken).unwrap();
    let (_, after) = prepare_rsc7(&repaired).unwrap();
    assert_eq!(&after[..before.len()], &before);
    assert!(legal_page_flags(u32_le(&repaired, 8)) && legal_page_flags(u32_le(&repaired, 12)));
    assert_eq!(std::fs::read(expanded.join("valid.ytd")).unwrap(), valid);
    let archive = std::fs::read(input.join("assets.rpf")).unwrap();
    let rerun = conversion::convert(options).unwrap();
    assert_eq!(rerun["summary"]["repairedResources"], 0);
    assert_eq!(std::fs::read(input.join("assets.rpf")).unwrap(), archive);
}
