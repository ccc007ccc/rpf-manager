//! Lossy resolution reduction by retaining existing ASTC mip blocks verbatim.
//! All GPU addresses and resource page layouts stay unchanged. The sidecar archive journal
//! in conversion::mip_trim, not an undocumented texture flag, prevents reruns.
use super::*;
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Policy {
    pub name: String,
    pub drop_mips: u32,
    pub minimum_dimension: u32,
    pub minimum_source_bytes: usize,
    pub require_even_dimensions: bool,
    pub preserved_dictionaries: Vec<String>,
}

impl Policy {
    /// SettingsManager maps menu Low (0) to grcTexturePC::MEDIUM (1).
    /// Keep the resource-side policy versioned with its reproduction manifest.
    pub fn gta_minimum() -> Self {
        Self {
            name: "gta-minimum-v1".into(),
            drop_mips: 1,
            minimum_dimension: 4,
            minimum_source_bytes: 4096,
            require_even_dimensions: true,
            preserved_dictionaries: vec!["mp_eye_colour.ytd".into()],
        }
    }

    pub fn validate(&self) -> Result<()> {
        ensure!(!self.name.is_empty(), "Mip policy needs an identity");
        ensure!(
            (1..=15).contains(&self.drop_mips),
            "Drop count must be 1..15"
        );
        ensure!(
            (1..=16384).contains(&self.minimum_dimension),
            "Invalid minimum dimension"
        );
        Ok(())
    }

    fn selection(
        &self,
        t: &Texture,
        resource_name: &str,
    ) -> std::result::Result<u32, &'static str> {
        if astc_block(t.format).is_none() {
            return Err("non_astc");
        }
        if t.format & 0xffff == 0x4c41 {
            return Err("already_minimum_astc");
        }
        if self.preserved_dictionaries.iter().any(|name| {
            name.eq_ignore_ascii_case(resource_name.rsplit('/').next().unwrap_or(resource_name))
        }) {
            return Err("fixed_coordinate_dictionary");
        }
        let name = t.name.to_ascii_lowercase();
        if t.resource_type != 0 || name.starts_with("script_rt") {
            return Err("render_target");
        }
        if t.extra_flags != 0 || name.starts_with("pedmugsho") || name == "mp_crewpalette" {
            return Err("runtime_writable");
        }
        if t.depth != 1 || t.image_type > 1 {
            return Err("volume_or_special_image");
        }
        if t.byte_size().unwrap_or(0) < self.minimum_source_bytes {
            return Err("below_source_byte_threshold");
        }
        if self.require_even_dimensions && (t.width % 2 != 0 || t.height % 2 != 0) {
            return Err("non_reversible_dimensions");
        }
        let mut drop = self.drop_mips.min(t.levels - 1);
        while drop > 0
            && ((t.width >> drop).max(1) < self.minimum_dimension
                || (t.height >> drop).max(1) < self.minimum_dimension)
        {
            drop -= 1;
        }
        if drop == 0 {
            return Err("minimum_dimension_or_single_mip");
        }
        Ok(drop)
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Change {
    pub texture: String,
    pub object_offset: usize,
    pub format: String,
    pub output_format: String,
    pub layers: u32,
    pub srgb: bool,
    pub dropped_mips: u32,
    pub before_dimensions: [u32; 2],
    pub after_dimensions: [u32; 2],
    pub before_levels: u32,
    pub after_levels: u32,
    pub before_data_offset: usize,
    pub after_data_offset: usize,
    pub before_payload_bytes: usize,
    pub after_payload_bytes: usize,
    pub reclaimed_bytes: usize,
    pub shared_payload: bool,
    pub old_payload_preserved: bool,
}

#[derive(Clone, Default, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Report {
    pub textures: u64,
    pub astc_textures: u64,
    pub changed_textures: u64,
    pub source_payload_bytes: u64,
    pub output_payload_bytes: u64,
    pub reclaimed_bytes: u64,
    pub retained_overlap_bytes: u64,
    pub skipped: BTreeMap<String, u64>,
    pub changes: Vec<Change>,
}

pub struct Trimmed {
    pub bytes: Vec<u8>,
    pub report: Report,
}

pub fn resource(
    original: &[u8],
    mut system: Vec<u8>,
    mut graphics: Vec<u8>,
    name: &str,
    policy: &Policy,
    dry_run: bool,
) -> Result<Trimmed> {
    policy.validate()?;
    let records = textures_for_resource(&system, &graphics, name)?;
    let selected: BTreeMap<_, _> = records
        .iter()
        .filter_map(|t| policy.selection(t, name).ok().map(|drop| (t.offset, drop)))
        .collect();
    let mut report = Report::default();
    let mut expected = records.clone();
    let mut gpu_pointers = BTreeMap::<usize, Vec<usize>>::new();
    for (field, pointer) in system.chunks_exact(8).enumerate() {
        let address = u64_le(pointer, 0);
        if address >> 28 == 6 {
            gpu_pointers
                .entry((address - 0x60000000) as usize)
                .or_default()
                .push(field * 8);
        }
    }
    let mut aliases = HashMap::new();
    let mut retained_aliases = std::collections::HashSet::new();
    let mut pending = Vec::new();
    for (index, t) in records.iter().enumerate() {
        report.textures += 1;
        report.astc_textures += u64::from(astc_block(t.format).is_some());
        let drop = match policy.selection(t, name) {
            Ok(drop) => drop,
            Err(reason) => {
                *report.skipped.entry(reason.into()).or_default() += 1;
                continue;
            }
        };
        let mut after = t.clone();
        let block = astc_block(t.format).unwrap();
        after.format = astc_minimum_format(block.0, block.1);
        after.width = (t.width >> drop).max(1);
        after.height = (t.height >> drop).max(1);
        after.levels -= drop;
        after.stride = 0;
        let source_size = t.byte_size()?;
        let size = after.byte_size()?;
        let alias = (t.payload_key(), drop);
        let source_end = t.data_offset + source_size;
        let same_owner = |other: &Texture| {
            selected.get(&other.offset) == Some(&drop) && other.payload_key() == t.payload_key()
        };
        let exclusive = records.iter().all(|other| {
            same_owner(other)
                || other.data_offset >= source_end
                || other.storage_end().is_some_and(|end| end <= t.data_offset)
        }) && gpu_pointers.range(..source_end).all(|(_, fields)| {
            fields.iter().all(|field| {
                records.iter().any(|other| {
                    *field == other.offset + 0x70
                        && (same_owner(other)
                            || other.storage_end().is_some_and(|end| end <= t.data_offset))
                })
            })
        });
        if !exclusive {
            *report
                .skipped
                .entry("shared_or_unknown_gpu_owner".into())
                .or_default() += 1;
            if retained_aliases.insert(t.payload_key()) {
                report.retained_overlap_bytes += source_size as u64;
            }
            continue;
        }
        let shared = aliases.contains_key(&alias);
        let offset = if let Some(&offset) = aliases.get(&alias) {
            offset
        } else {
            let offset = t.data_offset;
            aliases.insert(alias, offset);
            report.source_payload_bytes += source_size as u64;
            report.output_payload_bytes += size as u64;
            report.reclaimed_bytes += (source_size - size) as u64;
            if !dry_run {
                let source_layer_size = source_size / t.layers as usize;
                let skipped_size = (0..drop).try_fold(0, |sum, mip| -> Result<usize> {
                    Ok(sum + t.mip_layout(mip)?.2)
                })?;
                let mut payload = Vec::with_capacity(size);
                for layer in 0..t.layers as usize {
                    let start = t.data_offset + layer * source_layer_size;
                    payload.extend_from_slice(
                        &graphics[start + skipped_size..start + source_layer_size],
                    );
                }
                ensure!(
                    payload.len() == size,
                    "Retained mip layout mismatch for {}",
                    t.name
                );
                pending.push((t.data_offset, source_size, offset, payload));
            }
            offset
        };
        after.data_offset = offset;
        report.changed_textures += 1;
        report.changes.push(Change {
            texture: t.name.clone(),
            object_offset: t.offset,
            format: format!("0x{:08x}", t.format),
            layers: t.layers,
            srgb: t.srgb,
            output_format: format!("0x{:08x}", after.format),
            dropped_mips: drop,
            before_dimensions: [t.width, t.height],
            after_dimensions: [after.width, after.height],
            before_levels: t.levels,
            after_levels: after.levels,
            before_data_offset: t.data_offset,
            after_data_offset: offset,
            before_payload_bytes: source_size,
            after_payload_bytes: size,
            reclaimed_bytes: if !shared { source_size - size } else { 0 },
            shared_payload: shared,
            old_payload_preserved: false,
        });
        expected[index] = after;
    }
    if dry_run || report.changed_textures == 0 {
        return Ok(Trimmed {
            bytes: original.to_vec(),
            report,
        });
    }
    // Read all source chains before zeroing ranges; distinct objects can alias.
    for (source, size, _, _) in &pending {
        graphics[*source..*source + *size].fill(0);
    }
    for (_, _, offset, payload) in &pending {
        graphics[*offset..*offset + payload.len()].copy_from_slice(payload);
    }
    let changed: std::collections::HashSet<_> = report
        .changes
        .iter()
        .map(|change| change.object_offset)
        .collect();
    for t in &expected {
        if !changed.contains(&t.offset) {
            continue;
        }
        system[t.offset + 0x50..t.offset + 0x52].copy_from_slice(&(t.width as u16).to_le_bytes());
        system[t.offset + 0x52..t.offset + 0x54].copy_from_slice(&(t.height as u16).to_le_bytes());
        system[t.offset + 0x56..t.offset + 0x58].fill(0);
        system[t.offset + 0x58..t.offset + 0x5c].copy_from_slice(&t.format.to_le_bytes());
        system[t.offset + 0x5d] = t.levels as u8;
        system[t.offset + 0x70..t.offset + 0x78]
            .copy_from_slice(&(0x60000000u64 + t.data_offset as u64).to_le_bytes());
    }
    ensure!(
        system.len() == resource_size_from_flags(u32_le(original, 8))
            && graphics.len() == resource_size_from_flags(u32_le(original, 12)),
        "Mip trim must preserve resource section lengths"
    );
    // Do not normalize page flags or relocate page maps during size reduction.
    let mut encoder = flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::new(3));
    encoder.write_all(&system)?;
    encoder.write_all(&graphics)?;
    let mut bytes = original[..16].to_vec();
    bytes.extend_from_slice(&encoder.finish()?);
    let (check_system, check_graphics) = prepare_rsc7(&bytes)?;
    ensure!(
        check_system == system && check_graphics == graphics,
        "Mip trim changed sections during serialization"
    );
    ensure!(
        textures_for_resource(&check_system, &check_graphics, name)? == expected,
        "Mip trim metadata failed round trip"
    );
    for (_, _, offset, payload) in pending {
        ensure!(
            &check_graphics[offset..offset + payload.len()] == payload.as_slice(),
            "Retained ASTC mip bytes changed"
        );
    }
    Ok(Trimmed { bytes, report })
}

#[cfg(test)]
pub(crate) mod tests;
