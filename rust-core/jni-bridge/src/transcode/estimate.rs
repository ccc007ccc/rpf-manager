//! Exact block-data estimates for the BC payloads the transcoder will encode.
//! These are neither RPF file sizes nor VRAM estimates: container compression,
//! page allocation, retained textures and runtime resources are not included.

use super::{Texture, BLOCKS};
use anyhow::{ensure, Context, Result};
use std::collections::{BTreeMap, HashSet};

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct SizeEstimate {
    /// Convertible texture objects, including aliases of another object's data.
    pub textures: u64,
    /// Distinct encoding jobs, deduplicated within one resource by payload_key.
    pub payloads: u64,
    /// BC bytes read by those jobs, matching the converter's bc_bytes counter.
    /// Different layouts or color spaces may read the same source range again.
    pub source_bytes: u64,
    /// ASTC block bytes for each legal footprint, including every mip and layer.
    /// Empty when there are no convertible payloads; missing entries mean zero.
    pub by_block: BTreeMap<String, u64>,
}

impl SizeEstimate {
    /// Merge estimates from separate resources; payload identities are local to
    /// a resource. On overflow, leave this estimate unchanged.
    pub fn add(&mut self, other: &Self) -> Result<()> {
        let textures = self
            .textures
            .checked_add(other.textures)
            .context("Texture estimate count overflow")?;
        let payloads = self
            .payloads
            .checked_add(other.payloads)
            .context("Payload estimate count overflow")?;
        let source_bytes = self
            .source_bytes
            .checked_add(other.source_bytes)
            .context("Source byte estimate overflow")?;
        let mut updates = Vec::with_capacity(other.by_block.len());
        for (block, &bytes) in &other.by_block {
            let total = self
                .by_block
                .get(block)
                .copied()
                .unwrap_or(0)
                .checked_add(bytes)
                .with_context(|| format!("ASTC {block} byte estimate overflow"))?;
            updates.push((block, total));
        }
        self.textures = textures;
        self.payloads = payloads;
        self.source_bytes = source_bytes;
        for (block, total) in updates {
            if let Some(value) = self.by_block.get_mut(block) {
                *value = total;
            } else {
                self.by_block.insert(block.clone(), total);
            }
        }
        Ok(())
    }
}

fn mip_bytes(
    width: u32,
    height: u32,
    block: (u32, u32),
    block_bytes: u64,
    layers: u32,
) -> Result<u64> {
    // Integer div_ceil also charges a complete block for every tail mip.
    let columns = u64::from(width).div_ceil(u64::from(block.0));
    let rows = u64::from(height).div_ceil(u64::from(block.1));
    columns
        .checked_mul(rows)
        .and_then(|blocks| blocks.checked_mul(block_bytes))
        .and_then(|bytes| bytes.checked_mul(u64::from(layers)))
        .context("Mip byte estimate overflow")
}

/// Estimate one resource's convertible source BC textures. Call separately for
/// each resource, then add the results; equal GPU offsets in different resources
/// do not alias. Existing ASTC and intentionally retained textures contribute no
/// bytes to these conversion-only totals.
pub fn estimate(textures: &[Texture]) -> Result<SizeEstimate> {
    let mut result = SizeEstimate::default();
    let mut payloads = HashSet::new();
    let mut astc_bytes = [0u64; BLOCKS.len()];
    for texture in textures {
        if !texture.convertible() {
            continue;
        }
        result.textures = result
            .textures
            .checked_add(1)
            .context("Texture estimate count overflow")?;
        if !payloads.insert(texture.payload_key()) {
            continue;
        }
        ensure!(
            texture.width > 0 && texture.height > 0 && texture.layers > 0,
            "Texture {} has invalid dimensions or layer count",
            texture.name
        );
        ensure!(
            (1..=u32::BITS).contains(&texture.levels),
            "Texture {} has an invalid mip count",
            texture.name
        );
        let source_block_bytes = super::bc_bytes(texture.format)
            .context("Convertible texture has no BC block size")?
            as u64;
        result.payloads = result
            .payloads
            .checked_add(1)
            .context("Payload estimate count overflow")?;
        for mip in 0..texture.levels {
            let width = (texture.width >> mip).max(1);
            let height = (texture.height >> mip).max(1);
            let source = mip_bytes(width, height, (4, 4), source_block_bytes, texture.layers)
                .with_context(|| format!("Estimating source BC bytes for {}", texture.name))?;
            result.source_bytes = result
                .source_bytes
                .checked_add(source)
                .context("Source byte estimate overflow")?;
            for (index, &block) in BLOCKS.iter().enumerate() {
                let bytes = mip_bytes(width, height, block, 16, texture.layers)
                    .with_context(|| format!("Estimating ASTC bytes for {}", texture.name))?;
                astc_bytes[index] = astc_bytes[index]
                    .checked_add(bytes)
                    .context("ASTC byte estimate overflow")?;
            }
        }
    }
    if result.payloads != 0 {
        result.by_block = BLOCKS
            .iter()
            .zip(astc_bytes)
            .map(|(&(width, height), bytes)| (format!("{width}x{height}"), bytes))
            .collect();
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn texture() -> Texture {
        Texture {
            offset: 0x100,
            name: "color".into(),
            format: 0x31545844,
            width: 8,
            height: 8,
            depth: 1,
            layers: 1,
            levels: 1,
            image_type: 0,
            resource_type: 0,
            stride: 0,
            srgb: false,
            data_offset: 0x80,
            extra_flags: 0,
        }
    }

    #[test]
    fn npot_mip_array_matches_independent_block_counts() {
        let mut source = texture();
        source.width = 19;
        source.height = 11;
        source.levels = 5;
        source.layers = 3;
        let actual = estimate(&[source]).unwrap();
        // Mips: 19x11, 9x5, 4x2, 2x1, 1x1. BC1 needs
        // (15 + 6 + 1 + 1 + 1) blocks * 8 bytes * 3 layers = 576.
        // These literal ASTC totals are independently counted per footprint.
        let expected = [
            ("4x4", 1152),
            ("5x4", 912),
            ("5x5", 816),
            ("6x5", 816),
            ("6x6", 624),
            ("8x5", 672),
            ("8x6", 528),
            ("8x8", 528),
            ("10x5", 480),
            ("10x6", 384),
            ("10x8", 384),
            ("10x10", 384),
            ("12x10", 384),
            ("12x12", 288),
        ]
        .into_iter()
        .map(|(block, bytes)| (block.to_owned(), bytes))
        .collect::<BTreeMap<_, _>>();
        assert_eq!(
            (actual.textures, actual.payloads, actual.source_bytes),
            (1, 1, 576)
        );
        assert_eq!(actual.by_block, expected);
    }

    #[test]
    fn tail_mips_and_cube_faces_keep_full_blocks() {
        let mut tail = texture();
        tail.width = 3;
        tail.height = 2;
        tail.levels = 2;
        for (format, source_bytes) in [(0x31545844, 16), (0x35545844, 32)] {
            tail.format = format;
            let actual = estimate(&[tail.clone()]).unwrap();
            // Both 3x2 and 1x1 need one block, even for 12x12 ASTC.
            assert_eq!(actual.source_bytes, source_bytes);
            assert_eq!(actual.by_block.len(), 14);
            assert!(actual.by_block.values().all(|&bytes| bytes == 32));
        }
        let mut cube = texture();
        cube.image_type = 1;
        cube.layers = 6;
        cube.levels = 4;
        let actual = estimate(&[cube]).unwrap();
        // Each face has 8x8, 4x4, 2x2, 1x1: BC1 uses 7 blocks.
        assert_eq!(actual.source_bytes, 336);
        assert_eq!(actual.by_block["4x4"], 672);
        assert_eq!(actual.by_block["12x12"], 384);
    }

    #[test]
    fn payload_identity_matches_alias_and_encoding_semantics() {
        let original = texture();
        let mut alias = original.clone();
        alias.offset = 0x200;
        alias.name = "alias".into();
        let mut srgb = original.clone();
        srgb.srgb = true;
        let mut reshaped = original.clone();
        reshaped.width = 16;
        reshaped.height = 4;
        let mut mipped = original.clone();
        mipped.levels = 2;
        let mut layered = original.clone();
        layered.layers = 2;
        let mut bc3 = original.clone();
        bc3.format = 0x35545844;
        let actual = estimate(&[original, alias, srgb, reshaped, mipped, layered, bc3]).unwrap();
        // Only original/alias share an encoding job. The distinct jobs read
        // 32 + 32 + 32 + 40 + 64 + 64 bytes, even at one shared GPU offset.
        assert_eq!(
            (actual.textures, actual.payloads, actual.source_bytes),
            (7, 6, 264)
        );
        assert_eq!(actual.by_block["4x4"], 464);
        assert_eq!(actual.by_block["12x12"], 144);
    }

    #[test]
    fn already_astc_and_retained_resources_do_not_inflate_conversion_size() {
        let mut retained = Vec::new();
        for format in [0x04045341, 21, 0x20364342] {
            let mut entry = texture();
            entry.format = format;
            retained.push(entry);
        }
        let mut target = texture();
        target.resource_type = 1;
        retained.push(target);
        let mut writable = texture();
        writable.extra_flags = 1;
        retained.push(writable);
        for name in ["script_rt_minimap", "pedmugshot_01", "mp_crewpalette"] {
            let mut entry = texture();
            entry.name = name.into();
            retained.push(entry);
        }
        let mut volume = texture();
        volume.depth = 2;
        retained.push(volume);
        let mut srgb_bc4 = texture();
        srgb_bc4.format = 0x31495441;
        srgb_bc4.srgb = true;
        retained.push(srgb_bc4);
        assert_eq!(estimate(&retained).unwrap(), SizeEstimate::default());
        assert!(estimate(&[]).unwrap().by_block.is_empty());
        retained.push(texture());
        let actual = estimate(&retained).unwrap();
        assert_eq!(
            (actual.textures, actual.payloads, actual.source_bytes),
            (1, 1, 32)
        );
        assert_eq!(actual.by_block.len(), 14);
        assert_eq!(actual.by_block["4x4"], 64);
    }

    #[test]
    fn merging_resources_does_not_deduplicate_their_local_offsets() {
        let source = texture();
        let mut alias = source.clone();
        alias.offset = 0x200;
        let first = estimate(&[source.clone(), alias]).unwrap();
        let second = estimate(&[source]).unwrap();
        let mut total = SizeEstimate::default();
        total.add(&first).unwrap();
        total.add(&second).unwrap();
        assert_eq!(
            (total.textures, total.payloads, total.source_bytes),
            (3, 2, 64)
        );
        assert_eq!(total.by_block["4x4"], 128);
        assert_eq!(total.by_block["12x12"], 32);
    }

    #[test]
    fn invalid_layouts_and_byte_overflow_are_errors() {
        for invalid in [
            Texture {
                width: 0,
                ..texture()
            },
            Texture {
                height: 0,
                ..texture()
            },
            Texture {
                layers: 0,
                ..texture()
            },
            Texture {
                levels: 0,
                ..texture()
            },
            Texture {
                levels: 33,
                ..texture()
            },
        ] {
            assert!(estimate(&[invalid]).is_err());
        }
        // ceil((2^32-1)/4)^2 * 16 = 2^64. This must fail for
        // BC3 source bytes and for ASTC output bytes of a BC1 source.
        for format in [0x31545844, 0x35545844] {
            let oversized = Texture {
                width: u32::MAX,
                height: u32::MAX,
                format,
                ..texture()
            };
            assert!(estimate(&[oversized]).is_err());
        }
    }

    #[test]
    fn failed_merge_keeps_all_original_totals() {
        let increment = estimate(&[texture()]).unwrap();
        let mut block_overflow = SizeEstimate::default();
        block_overflow.by_block.insert("4x4".into(), u64::MAX);
        for mut total in [
            SizeEstimate {
                textures: u64::MAX,
                ..SizeEstimate::default()
            },
            SizeEstimate {
                payloads: u64::MAX,
                ..SizeEstimate::default()
            },
            SizeEstimate {
                source_bytes: u64::MAX,
                ..SizeEstimate::default()
            },
            block_overflow,
        ] {
            let before = total.clone();
            assert!(total.add(&increment).is_err());
            assert_eq!(total, before);
        }
    }
}
