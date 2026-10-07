//! NUM-019: BC -> ASTC is an opt-in lossy resource conversion, not a bit-exact
//! replacement. See GTAV NUMERICAL_COMPATIBILITY.md and the sample comparisons.
//! PC RSC7 texture conversion. Resource objects and unrelated GPU data retain
//! their virtual addresses. Only texture payloads and their format fields change.
use crate::astc_encoder::Encoder;
use anyhow::{bail, ensure, Context, Result};
use rpf_archive::archive::resource_size_from_flags;
use rpf_archive::resource::{prepare_rsc7, u16_le, u32_le, u64_le, ResReader};
use std::{
    collections::{BTreeMap, HashMap},
    fs::{self, OpenOptions},
    io::Write,
    path::Path,
    sync::atomic::{AtomicBool, Ordering},
};

pub const BLOCKS: [(u32, u32); 14] = [
    (4, 4),
    (5, 4),
    (5, 5),
    (6, 5),
    (6, 6),
    (8, 5),
    (8, 6),
    (8, 8),
    (10, 5),
    (10, 6),
    (10, 8),
    (10, 10),
    (12, 10),
    (12, 12),
];

// Private RAGE asset marker: bytes ['A', 'S', block_width, block_height].
// This is deliberately independent of DXVK's private DXGI format numbers.
pub fn astc_format(w: u32, h: u32) -> u32 {
    0x5341 | (w << 16) | (h << 24)
}
/// Existing mip chain already reduced to the minimum game texture setting.
pub fn astc_minimum_format(w: u32, h: u32) -> u32 {
    0x4c41 | (w << 16) | (h << 24)
}
pub fn astc_block(format: u32) -> Option<(u32, u32)> {
    let b = (format >> 16 & 255, format >> 24);
    (matches!(format & 0xffff, 0x5341 | 0x4c41) && BLOCKS.contains(&b)).then_some(b)
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Texture {
    pub offset: usize,
    pub name: String,
    pub format: u32,
    pub width: u32,
    pub height: u32,
    pub depth: u32,
    pub layers: u32,
    pub levels: u32,
    pub image_type: u8,
    pub resource_type: u8,
    pub stride: u16,
    pub srgb: bool,
    pub data_offset: usize,
    pub extra_flags: u32,
}

fn bc_bytes(format: u32) -> Option<usize> {
    match format {
        0x31545844 | 0x31495441 => Some(8),
        0x32545844 | 0x33545844 | 0x34545844 | 0x35545844 | 0x32495441 | 0x20374342 => Some(16),
        _ => None,
    }
}

pub fn is_bc_format(format: u32) -> bool {
    bc_bytes(format).is_some() || format == 0x20364342
}

impl Texture {
    fn convertible(&self) -> bool {
        let name = self.name.to_ascii_lowercase();
        bc_bytes(self.format).is_some()
            && self.resource_type == 0
            && self.depth == 1
            && self.image_type <= 1
            && self.extra_flags == 0
            && !(self.srgb && matches!(self.format, 0x31495441 | 0x32495441))
            && !name.starts_with("script_rt")
            && !name.starts_with("pedmugsho")
            && name != "mp_crewpalette"
    }
    fn retention_reason(&self) -> String {
        let name = self.name.to_ascii_lowercase();
        if self.resource_type == 1 || name.starts_with("script_rt") {
            "render_target".into()
        } else if self.extra_flags != 0 || name.starts_with("pedmugsho") || name == "mp_crewpalette"
        {
            "runtime_writable".into()
        } else if self.depth != 1 || self.image_type > 1 {
            "volume_or_special_image".into()
        } else if self.srgb && matches!(self.format, 0x31495441 | 0x32495441 | 0x20364342) {
            "unsupported_srgb_format".into()
        } else {
            format!("format_0x{:08x}", self.format)
        }
    }
    fn payload_key(&self) -> (usize, u32, bool, u32, u32, u32, u32) {
        (
            self.data_offset,
            self.format,
            self.srgb,
            self.width,
            self.height,
            self.layers,
            self.levels,
        )
    }
    pub fn mip_layout(&self, mip: u32) -> Result<(usize, usize, usize)> {
        let w = (self.width >> mip).max(1) as usize;
        let h = (self.height >> mip).max(1) as usize;
        let d = (self.depth >> mip).max(1) as usize;
        let (bw, bh, bytes) = if let Some((bw, bh)) = astc_block(self.format) {
            (bw as usize, bh as usize, 16)
        } else if let Some(bytes) =
            bc_bytes(self.format).or((self.format == 0x20364342).then_some(16))
        {
            (4, 4, bytes)
        } else {
            bail!("Unsupported texture format 0x{:08x}", self.format)
        };
        Ok((w, h, w.div_ceil(bw) * h.div_ceil(bh) * bytes * d))
    }
    pub fn byte_size(&self) -> Result<usize> {
        let mut size = 0usize;
        for mip in 0..self.levels {
            size += self.mip_layout(mip)?.2;
        }
        size.checked_mul(self.layers as usize)
            .context("Texture size overflow")
    }

    // Used only to prove that overwriting a converted payload cannot damage a
    // retained owner. Unknown layouts conservatively cover the rest of graphics.
    fn storage_end(&self) -> Option<usize> {
        let size = if is_bc_format(self.format) || astc_block(self.format).is_some() {
            self.byte_size().ok()?
        } else {
            let bytes = match self.format {
                21 | 22 | 32 => 4,
                25 => 2,
                28 | 50 => 1,
                _ => return None,
            };
            let mut size = 0usize;
            for mip in 0..self.levels {
                let width = (self.width >> mip).max(1) as usize;
                let height = (self.height >> mip).max(1) as usize;
                let depth = (self.depth >> mip).max(1) as usize;
                let pitch = (width * bytes).max((self.stride as usize) >> mip);
                size = size.checked_add(pitch.checked_mul(height)?.checked_mul(depth)?)?;
            }
            size.checked_mul(self.layers as usize)?
        };
        self.data_offset.checked_add(size)
    }
}

fn texture_name(r: &ResReader<'_>, pointer: u64) -> Option<String> {
    if pointer >> 28 != 5 {
        return None;
    }
    let bytes = r.system.get((pointer - 0x50000000) as usize..)?;
    let end = bytes.iter().take(1025).position(|&byte| byte == 0)?;
    let name = String::from_utf8_lossy(&bytes[..end]).into_owned();
    (!name.is_empty() && !name.chars().any(char::is_control)).then_some(name)
}

fn texture_at(r: &ResReader<'_>, address: u64) -> Result<Option<Texture>> {
    // A pgDictionary may contain null slots; these do not hide later entries.
    if address == 0 {
        return Ok(None);
    }
    ensure!(
        address >> 28 == 5 && address & 7 == 0,
        "Invalid texture object pointer 0x{address:x}"
    );
    let base = r
        .resolve(address, 0x40)
        .context("Truncated texture object")?;
    let name = texture_name(r, u64_le(base, 0x28)).context("Invalid texture name")?;
    let kind = base[0x32] & 3;
    if kind >= 2 {
        return Ok(None); // references carry no texture payload
    }
    let raw = r
        .resolve(address, 0x90)
        .context("Truncated PC texture object")?;
    let (width, height, depth) = (
        u16_le(raw, 0x50) as u32,
        u16_le(raw, 0x52) as u32,
        u16_le(raw, 0x54) as u32,
    );
    ensure!(
        width > 0
            && height > 0
            && width <= 16384
            && height <= 16384
            && depth <= 2048
            && (1..=16).contains(&raw[0x5d]),
        "Texture {name} has invalid dimensions or mip count"
    );
    let data = u64_le(raw, 0x70);
    ensure!(
        data >> 28 == 6 && r.resolve(data, 1).is_some(),
        "Texture {name} has an invalid graphics pointer"
    );
    let texture = Texture {
        offset: (address - 0x50000000) as usize,
        name,
        format: u32_le(raw, 0x58),
        width,
        height,
        depth: depth.max(1),
        layers: base[0x33] as u32 + 1,
        levels: raw[0x5d] as u32,
        image_type: raw[0x5c],
        resource_type: kind,
        stride: u16_le(raw, 0x56),
        srgb: raw[0x5f] & 1 != 0,
        data_offset: (data - 0x60000000) as usize,
        extra_flags: u32_le(raw, 0x48),
    };
    {
        let t = &texture;
        ensure!(
            t.image_type != 1 || (t.layers % 6 == 0 && t.width == t.height),
            "Texture {} has an invalid cube layout",
            t.name
        );
        if is_bc_format(t.format) || astc_block(t.format).is_some() {
            ensure!(
                t.data_offset
                    .checked_add(t.byte_size()?)
                    .is_some_and(|end| end <= r.graphics.len()),
                "Texture {} mip chain is outside its graphics section",
                t.name
            );
        }
    }
    Ok(Some(texture))
}

fn dictionary_prefix(r: &ResReader<'_>, raw: &[u8]) -> bool {
    // pgDictionary begins with pgBase, an optional parent, and a signed
    // reference count. Arrays of unrelated object pointers can otherwise make
    // plausible count/capacity pairs from the low halves of pointers or hashes.
    let vtable_section = u64_le(raw, 0) >> 28;
    !matches!(vtable_section, 5 | 6)
        && u32_le(raw, 0x18) <= i32::MAX as u32
        && [u64_le(raw, 8), u64_le(raw, 0x10)]
            .into_iter()
            .all(|pointer| pointer == 0 || (pointer >> 28 == 5 && r.resolve(pointer, 8).is_some()))
}

fn dictionary_pointers<'a>(r: &ResReader<'a>, offset: usize) -> Result<&'a [u8]> {
    let raw = r
        .system
        .get(offset..offset + 0x40)
        .context("Truncated texture dictionary")?;
    ensure!(
        dictionary_prefix(r, raw),
        "Invalid PC texture dictionary prefix"
    );
    let count = u16_le(raw, 0x38) as usize;
    ensure!(
        count == u16_le(raw, 0x28) as usize
            && count <= u16_le(raw, 0x2a) as usize
            && count <= u16_le(raw, 0x3a) as usize,
        "Texture dictionary has inconsistent counts or capacities"
    );
    if count == 0 {
        return Ok(&[]);
    }
    let (hashes, pointers) = (u64_le(raw, 0x20), u64_le(raw, 0x30));
    ensure!(
        hashes >> 28 == 5 && pointers >> 28 == 5,
        "Texture dictionary arrays are outside the system section"
    );
    r.resolve(hashes, count * 4)
        .context("Texture hash array is out of bounds")?;
    r.resolve(pointers, count * 8)
        .context("Texture pointer array is out of bounds")
}

fn compressed_texture_header(r: &ResReader<'_>, address: u64) -> bool {
    if address >> 28 != 5 || address & 7 != 0 {
        return false;
    }
    let Some(raw) = r.resolve(address, 0x90) else {
        return false;
    };
    let format = u32_le(raw, 0x58);
    // This is a structural probe, never a FourCC-only search. At dictionary
    // entries it also identifies corrupt GPU pointers that must be reported.
    (is_bc_format(format) || astc_block(format).is_some())
        && raw[0x32] & 3 < 2
        && texture_name(r, u64_le(raw, 0x28)).is_some()
        && (1..=16384).contains(&u16_le(raw, 0x50))
        && (1..=16384).contains(&u16_le(raw, 0x52))
        && u16_le(raw, 0x54) <= 2048
        && (1..=16).contains(&raw[0x5d])
}

/// Find pgDictionary<grcTexture> tables, including embedded dictionaries. A
/// broken member in an identified dictionary is an error, not an empty result.
pub fn textures(system: &[u8], graphics: &[u8]) -> Result<Vec<Texture>> {
    let r = ResReader { system, graphics };
    let mut found = BTreeMap::new();
    for off in (0..system.len().saturating_sub(63)).step_by(8) {
        let raw = &system[off..off + 64];
        let count = u16_le(raw, 0x38);
        if count == 0
            || count != u16_le(raw, 0x28)
            || count > u16_le(raw, 0x2a)
            || count > u16_le(raw, 0x3a)
        {
            continue;
        }
        if !dictionary_prefix(&r, raw) {
            continue;
        }
        let Ok(pointers) = dictionary_pointers(&r, off) else {
            continue;
        };
        if pointers.is_empty() {
            continue;
        }
        let mut candidates = Vec::new();
        let mut error = None;
        let mut identified = false;
        for p in pointers.chunks_exact(8) {
            let address = u64_le(p, 0);
            match texture_at(&r, address) {
                Ok(Some(texture)) => {
                    identified = true;
                    candidates.push(texture);
                }
                Ok(None) => {}
                Err(e) => {
                    identified |= compressed_texture_header(&r, address);
                    if error.is_none() {
                        error = Some(e);
                    }
                }
            }
        }
        if !identified {
            continue;
        }
        if let Some(e) = error {
            return Err(e)
                .with_context(|| format!("Invalid texture dictionary at system offset 0x{off:x}"));
        }
        for t in candidates {
            found.insert(t.offset, t);
        }
    }
    // Independently inspect complete PC compressed texture objects. A valid
    // object missed by dictionary discovery must not turn into a BC-free claim.
    for off in (0..system.len().saturating_sub(0x8f)).step_by(8) {
        let format = u32_le(system, off + 0x58);
        if (!is_bc_format(format) && astc_block(format).is_none())
            || found.contains_key(&off)
            || !compressed_texture_header(&r, 0x50000000 + off as u64)
        {
            continue;
        }
        let raw = &system[off..off + 0x90];
        let address = 0x50000000 + off as u64;
        let data = u64_le(raw, 0x70);
        let valid_gpu_start = data >> 28 == 6 && r.resolve(data, 1).is_some();
        let referenced = || {
            system
                .chunks_exact(8)
                .any(|pointer| u64_le(pointer, 0) == address)
        };
        if !valid_gpu_start && !referenced() {
            continue;
        }
        let texture = texture_at(&r, address)
            .with_context(|| format!("Unrecognized texture object at system offset 0x{off:x}"))?
            .unwrap();
        bail!("Unsupported texture ownership: {} at system offset 0x{off:x} is outside a recognized texture dictionary", texture.name);
    }
    Ok(found.into_values().collect())
}

pub fn textures_for_resource(system: &[u8], graphics: &[u8], name: &str) -> Result<Vec<Texture>> {
    if name.eq_ignore_ascii_case("ytd") || name.to_ascii_lowercase().ends_with(".ytd") {
        let r = ResReader { system, graphics };
        // YTD has an authoritative root: validate every slot, even if another
        // embedded dictionary would make the global scan nonempty.
        for pointer in dictionary_pointers(&r, 0)
            .context("Invalid root YTD dictionary")?
            .chunks_exact(8)
        {
            texture_at(&r, u64_le(pointer, 0)).context("Invalid root YTD texture")?;
        }
    }
    textures(system, graphics)
}

#[derive(Clone, Debug, PartialEq)]
pub struct AdvancedSettings {
    /// Overrides the named quality preset's search effort, without changing the footprint.
    pub effort: Option<f32>,
    pub channel_weights: [f32; 4],
    /// None disables alpha weighting; Some(0) enables it without a neighborhood.
    pub alpha_weight_radius: Option<u32>,
    pub perceptual: bool,
}
impl Default for AdvancedSettings {
    fn default() -> Self {
        Self {
            effort: None,
            channel_weights: [1.0; 4],
            alpha_weight_radius: None,
            perceptual: false,
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Settings {
    pub block: (u32, u32),
    pub quality: String,
    pub advanced: AdvancedSettings,
}
impl Settings {
    pub fn effective_effort(&self) -> Result<f32> {
        let preset = match self.quality.as_str() {
            "fastest" => astcenc_sys::ASTCENC_PRE_FASTEST,
            "fast" => astcenc_sys::ASTCENC_PRE_FAST,
            "medium" => astcenc_sys::ASTCENC_PRE_MEDIUM,
            "thorough" => astcenc_sys::ASTCENC_PRE_THOROUGH,
            "very-thorough" => astcenc_sys::ASTCENC_PRE_VERYTHOROUGH,
            "exhaustive" => astcenc_sys::ASTCENC_PRE_EXHAUSTIVE,
            _ => bail!("Invalid ASTC quality"),
        };
        Ok(self.advanced.effort.unwrap_or(preset))
    }
    pub fn validate(&self) -> Result<()> {
        ensure!(
            BLOCKS.contains(&self.block),
            "Invalid ASTC block footprint; choose one of the 14 supported 2D footprints"
        );
        let effort = self.effective_effort()?;
        ensure!(
            effort.is_finite() && (0.0..=100.0).contains(&effort),
            "ASTC effort must be finite and between 0 and 100"
        );
        ensure!(
            self.advanced
                .channel_weights
                .iter()
                .all(|w| w.is_finite() && (0.01..=64.0).contains(w)),
            "ASTC channel weights must each be finite and between 0.01 and 64"
        );
        ensure!(
            self.advanced.alpha_weight_radius.is_none_or(|r| r <= 16),
            "ASTC alpha weight radius must be between 0 and 16"
        );
        Ok(())
    }
    pub fn encoder_settings(&self) -> serde_json::Value {
        serde_json::json!({
            "quality":self.quality,"requestedEffort":self.advanced.effort,
            "effectiveEffort":self.effective_effort().ok(),
            "channelWeights":self.advanced.channel_weights,
            "effectiveChannelWeights":crate::astc_encoder::effective_channel_weights(self).ok(),
            "channelWeightsAppliedAs":"multipliers", "minimumRelativeChannelWeight":0.001,
            "alphaWeightEnabled":self.advanced.alpha_weight_radius.is_some(),
            "alphaWeightRadius":self.advanced.alpha_weight_radius,
            "perceptual":self.advanced.perceptual,"profilePolicy":"source-srgb"
        })
    }
}
pub struct Transcoder {
    settings: Settings,
    encoders: HashMap<bool, Encoder>,
}
mod estimate;
pub use estimate::SizeEstimate;
pub mod mip_trim;

#[derive(Default, Debug)]
pub struct Counts {
    pub textures: u64,
    pub converted: u64,
    pub repaired_resources: u64,
    pub already_astc: u64,
    pub remaining_bc: u64,
    pub convertible_bc: u64,
    pub blocked_bc: u64,
    pub blocked_bc_samples: Vec<serde_json::Value>,
    pub formats: BTreeMap<String, u64>,
    pub retained: BTreeMap<String, u64>,
    pub bc_bytes: u64,
    pub astc_bytes: u64,
    pub size_estimate: SizeEstimate,
}
impl Counts {
    pub fn changed(&self) -> bool {
        self.converted > 0 || self.repaired_resources > 0
    }

    fn source_texture(&mut self, texture: &Texture) {
        self.textures += 1;
        *self
            .formats
            .entry(format!("0x{:08x}", texture.format))
            .or_default() += 1;
        if astc_block(texture.format).is_some() {
            self.already_astc += 1;
        } else if texture.convertible() {
            self.convertible_bc += 1;
        } else {
            let reason = texture.retention_reason();
            *self.retained.entry(reason.clone()).or_default() += 1;
            if is_bc_format(texture.format) {
                self.remaining_bc += 1;
                self.blocked_bc += 1;
                if self.blocked_bc_samples.len() < 16 {
                    self.blocked_bc_samples
                        .push(serde_json::json!({"texture":texture.name,
                        "format":format!("0x{:08x}",texture.format),"reason":reason}));
                }
            }
        }
    }

    pub fn scan(textures: &[Texture]) -> Result<Self> {
        let mut counts = Self {
            size_estimate: estimate::estimate(textures)?,
            ..Self::default()
        };
        for texture in textures {
            counts.source_texture(texture);
        }
        // Scanning preserves all BC. blocked_bc identifies just the subset that
        // the same converter policy cannot turn into ASTC.
        counts.remaining_bc += counts.convertible_bc;
        Ok(counts)
    }
}
pub struct Converted {
    pub bytes: Vec<u8>,
    pub counts: Counts,
}

impl Transcoder {
    pub fn new(settings: Settings) -> Result<Self> {
        settings.validate()?;
        Ok(Self {
            settings,
            encoders: HashMap::new(),
        })
    }
    fn encoder(&mut self, srgb: bool) -> Result<&mut Encoder> {
        if !self.encoders.contains_key(&srgb) {
            self.encoders
                .insert(srgb, Encoder::new(&self.settings, srgb)?);
        }
        Ok(self.encoders.get_mut(&srgb).unwrap())
    }
    pub fn resource(&mut self, data: &[u8], sample_dir: Option<&Path>) -> Result<Converted> {
        let (system, graphics) = prepare_rsc7(data)?;
        self.resource_sections(data, system, graphics, sample_dir)
    }
    pub fn resource_sections(
        &mut self,
        data: &[u8],
        system: Vec<u8>,
        graphics: Vec<u8>,
        sample_dir: Option<&Path>,
    ) -> Result<Converted> {
        self.resource_sections_cancellable(data, system, graphics, sample_dir, None)
    }
    pub fn resource_sections_cancellable(
        &mut self,
        data: &[u8],
        mut system: Vec<u8>,
        mut graphics: Vec<u8>,
        sample_dir: Option<&Path>,
        cancel: Option<&AtomicBool>,
    ) -> Result<Converted> {
        let check_cancel = || -> Result<()> {
            ensure!(
                !cancel.is_some_and(|c| c.load(Ordering::Relaxed)),
                "Operation cancelled"
            );
            Ok(())
        };
        check_cancel()?;
        let records = textures(&system, &graphics)?;
        let mut counts = Counts {
            size_estimate: estimate::estimate(&records)?,
            ..Counts::default()
        };
        let mut expected: BTreeMap<_, _> = records.iter().map(|t| (t.offset, t.clone())).collect();
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
        let mut pending = Vec::new();
        let mut quality_log = None;
        // Decode every source before clearing old ranges: separate texture
        // objects are allowed to share a BC payload.
        for t in &records {
            counts.source_texture(t);
            check_cancel()?;
            if astc_block(t.format).is_some() {
                continue;
            }
            if !t.convertible() {
                continue;
            }
            let alias = t.payload_key();
            if let Some(&(offset, size)) = aliases.get(&alias) {
                pending.push((t.clone(), offset, size, None, false));
                counts.converted += 1;
                continue;
            }
            let mut offset = t.data_offset;
            let mut encoded = Vec::new();
            for layer in 0..t.layers {
                for mip in 0..t.levels {
                    check_cancel()?;
                    let (w, h, size) = t.mip_layout(mip)?;
                    let rgba = decode_bc(t.format, &graphics[offset..offset + size], w, h)?;
                    let blocks = self
                        .encoder(t.srgb)?
                        .compress(&rgba, w as u32, h as u32)
                        .with_context(|| format!("ASTC encode {}", t.name))?;
                    check_cancel()?;
                    if let Some(dir) = sample_dir {
                        let decoded = self
                            .encoder(t.srgb)?
                            .decompress(&blocks, w as u32, h as u32)
                            .with_context(|| format!("ASTC audit decode {}", t.name))?;
                        let actual = &decoded;
                        ensure!(
                            actual.len() == rgba.len(),
                            "ASTC audit size mismatch for {}",
                            t.name
                        );
                        let mut squared_error = 0u64;
                        let mut absolute_error = 0u64;
                        let mut max_error = 0u8;
                        let mut pixels_over_2 = 0u64;
                        let mut pixels_over_4 = 0u64;
                        let mut pixels_over_8 = 0u64;
                        let mut matted_squared_error = 0u64;
                        let mut matted_absolute_error = 0u64;
                        let mut max_matted_error = 0u8;
                        let mut pixels_matted_over_2 = 0u64;
                        let mut pixels_matted_over_4 = 0u64;
                        let mut pixels_matted_over_8 = 0u64;
                        for (source, result) in rgba.chunks_exact(4).zip(actual.chunks_exact(4)) {
                            let mut pixel_error = 0u8;
                            let mut pixel_matted_error = 0u8;
                            for (&a, &b) in source.iter().zip(result) {
                                let error = a.abs_diff(b);
                                squared_error += u64::from(error) * u64::from(error);
                                absolute_error += u64::from(error);
                                max_error = max_error.max(error);
                                pixel_error = pixel_error.max(error);
                            }
                            for channel in 0..3 {
                                for background in [0u16, 255u16] {
                                    let source_alpha = u16::from(source[3]);
                                    let result_alpha = u16::from(result[3]);
                                    let source_matted = (u16::from(source[channel]) * source_alpha
                                        + background * (255 - source_alpha)
                                        + 127)
                                        / 255;
                                    let result_matted = (u16::from(result[channel]) * result_alpha
                                        + background * (255 - result_alpha)
                                        + 127)
                                        / 255;
                                    let error = source_matted.abs_diff(result_matted) as u8;
                                    matted_squared_error += u64::from(error) * u64::from(error);
                                    matted_absolute_error += u64::from(error);
                                    max_matted_error = max_matted_error.max(error);
                                    pixel_matted_error = pixel_matted_error.max(error);
                                }
                            }
                            pixels_over_2 += u64::from(pixel_error > 2);
                            pixels_over_4 += u64::from(pixel_error > 4);
                            pixels_over_8 += u64::from(pixel_error > 8);
                            pixels_matted_over_2 += u64::from(pixel_matted_error > 2);
                            pixels_matted_over_4 += u64::from(pixel_matted_error > 4);
                            pixels_matted_over_8 += u64::from(pixel_matted_error > 8);
                        }
                        let components = rgba.len() as u64;
                        let mse = squared_error as f64 / components as f64;
                        let psnr = if mse == 0.0 {
                            None
                        } else {
                            Some(10.0 * (255.0 * 255.0 / mse).log10())
                        };
                        let matted_components = (rgba.len() as u64 / 4) * 6;
                        let matted_mse = matted_squared_error as f64 / matted_components as f64;
                        let matted_psnr = if matted_mse == 0.0 {
                            None
                        } else {
                            Some(10.0 * (255.0 * 255.0 / matted_mse).log10())
                        };
                        if quality_log.is_none() {
                            fs::create_dir_all(dir)?;
                            quality_log = Some(
                                OpenOptions::new()
                                    .create(true)
                                    .append(true)
                                    .open(dir.join("quality.jsonl"))?,
                            );
                        }
                        let resource_id =
                            dir.file_name().and_then(|name| name.to_str()).unwrap_or("");
                        let row = serde_json::json!({"resource":resource_id,"texture":t.name,"format":format!("0x{:08x}",t.format),
                            "layer":layer,"mip":mip,"width":w,"height":h,"srgb":t.srgb,"block":[self.settings.block.0,self.settings.block.1],
                            "encoderSettings":self.settings.encoder_settings(),
                            "psnr_rgba_db":psnr,"mse_rgba":mse,"mae_rgba":absolute_error as f64/components as f64,
                            "max_component_error":max_error,"pixels_over_2":pixels_over_2,"pixels_over_4":pixels_over_4,"pixels_over_8":pixels_over_8,
                            "psnr_matted_db":matted_psnr,"mse_matted":matted_mse,"mae_matted":matted_absolute_error as f64/matted_components as f64,
                            "max_matted_error":max_matted_error,"pixels_matted_over_2":pixels_matted_over_2,"pixels_matted_over_4":pixels_matted_over_4,"pixels_matted_over_8":pixels_matted_over_8});
                        writeln!(quality_log.as_mut().unwrap(), "{}", row)?;
                        let visual_sample = resource_id
                            .split_once('-')
                            .and_then(|(index, _)| index.parse::<usize>().ok())
                            .is_some_and(|index| index < 64);
                        let low_quality = matted_psnr.is_some_and(|score| score < 50.0);
                        if layer == 0
                            && mip == 0
                            && ((visual_sample && counts.converted < 2) || low_quality)
                        {
                            let block = self.settings.block;
                            let settings = self.settings.encoder_settings();
                            sample(
                                dir,
                                t,
                                &rgba,
                                &blocks,
                                self.encoder(t.srgb)?,
                                block,
                                &settings,
                            )?;
                        }
                    }
                    encoded.extend_from_slice(&blocks);
                    offset += size;
                }
            }
            let size = encoded.len();
            // All overlapping owners matter, including retained non-BC images
            // that begin partway through this payload and non-texture GPU data.
            // Unknown retained layouts cannot prove that a preceding range ends.
            let source_end = t.data_offset + t.byte_size()?;
            let same_owner = |other: &Texture| other.convertible() && other.payload_key() == alias;
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
            let destination = if size <= t.byte_size()? && exclusive {
                t.data_offset
            } else {
                let position = graphics.len().div_ceil(16) * 16;
                graphics.resize(position + size, 0);
                position
            };
            aliases.insert(alias, (destination, size));
            counts.bc_bytes += t.byte_size()? as u64;
            counts.astc_bytes += size as u64;
            counts.converted += 1;
            pending.push((t.clone(), destination, size, Some(encoded), exclusive));
        }
        if counts.converted == 0 {
            return Ok(Converted {
                bytes: data.to_vec(),
                counts,
            });
        }
        for (t, _, _, encoded, exclusive) in &pending {
            if encoded.is_some() && *exclusive {
                graphics[t.data_offset..t.data_offset + t.byte_size()?].fill(0);
            }
        }
        for (t, offset, size, encoded, _) in pending {
            if let Some(encoded) = encoded {
                graphics[offset..offset + size].copy_from_slice(&encoded);
            }
            system[t.offset + 0x58..t.offset + 0x5c].copy_from_slice(
                &astc_format(self.settings.block.0, self.settings.block.1).to_le_bytes(),
            );
            // Fractional bits/pixel cannot be represented by legacy MipStride.
            // ASTC-aware loaders calculate each mip's block-row pitch instead.
            system[t.offset + 0x56..t.offset + 0x58].fill(0);
            system[t.offset + 0x70..t.offset + 0x78]
                .copy_from_slice(&(0x60000000u64 + offset as u64).to_le_bytes());
            let metadata = expected.get_mut(&t.offset).unwrap();
            metadata.format = astc_format(self.settings.block.0, self.settings.block.1);
            metadata.stride = 0;
            metadata.data_offset = offset;
        }
        let bytes = write_resource(data, &mut system, &mut graphics)?;
        let (check_sys, check_gfx) = prepare_rsc7(&bytes)?;
        ensure!(
            check_sys == system && check_gfx == graphics,
            "Resource sections changed during serialization"
        );
        let check = textures(&check_sys, &check_gfx)?;
        ensure!(
            check == expected.into_values().collect::<Vec<_>>(),
            "Texture metadata changed during resource serialization"
        );
        ensure!(
            check
                .iter()
                .filter(|t| astc_block(t.format).is_some())
                .count() as u64
                == counts.converted + counts.already_astc,
            "ASTC texture count mismatch after resource serialization"
        );
        ensure!(
            check.iter().filter(|t| is_bc_format(t.format)).count() as u64 == counts.remaining_bc,
            "Remaining BC count mismatch after resource serialization"
        );
        Ok(Converted { bytes, counts })
    }
}

fn decode_bc(format: u32, bytes: &[u8], w: usize, h: usize) -> Result<Vec<u8>> {
    rpf_archive::texture_utils::decode_bc_rgba(format, bytes, w, h)
}

fn page_flags(bytes: usize, version: u32) -> Result<u32> {
    if bytes == 0 {
        return Ok(version & 0xf0000000);
    }
    let fields = [
        (256, 4, 1),
        (128, 5, 3),
        (64, 7, 15),
        (32, 11, 63),
        (16, 17, 127),
        (8, 24, 1),
        (4, 25, 1),
        (2, 26, 1),
        (1, 27, 1),
    ];
    // pgBase::MakeDefragmentable requires each individual chunk >= 8 KiB.
    // Rounding the total alone still allows illegal 512..4096-byte tail pages.
    for shift in 4..16 {
        let mut units = bytes.div_ceil(512usize << shift);
        let mut flags = (version & 0xf0000000) | shift as u32;
        let mut pages = 0;
        for (weight, bit, limit) in fields {
            let n = if (512usize << shift) * weight < (8192usize << 16) {
                (units / weight).min(limit)
            } else {
                0
            };
            units -= n * weight;
            flags |= (n as u32) << bit;
            pages += n;
        }
        if units == 0 && pages <= 64 {
            return Ok(flags);
        }
    }
    bail!("Resource exceeds page-flag capacity")
}
fn page_count(flags: u32) -> usize {
    [
        (27, 1),
        (26, 1),
        (25, 1),
        (24, 1),
        (17, 127),
        (11, 63),
        (7, 15),
        (5, 3),
        (4, 1),
    ]
    .into_iter()
    .map(|(shift, mask)| ((flags >> shift) & mask) as usize)
    .sum()
}
fn legal_page_flags(flags: u32) -> bool {
    let base = 512usize << (flags & 15);
    [
        (256, 4, 1),
        (128, 5, 3),
        (64, 7, 15),
        (32, 11, 63),
        (16, 17, 127),
        (8, 24, 1),
        (4, 25, 1),
        (2, 26, 1),
        (1, 27, 1),
    ]
    .into_iter()
    .all(|(weight, bit, mask)| {
        (flags >> bit) & mask == 0 || (8192..(8192usize << 16)).contains(&(base * weight))
    })
}

/// Normalize serialized page layout without interpreting or re-encoding payloads.
pub fn repair_pages(
    original: &[u8],
    mut system: Vec<u8>,
    mut graphics: Vec<u8>,
    mut counts: Counts,
) -> Result<Converted> {
    ensure!(original.len() >= 16, "Truncated resource header");
    if legal_page_flags(u32_le(original, 8)) && legal_page_flags(u32_le(original, 12)) {
        return Ok(Converted {
            bytes: original.to_vec(),
            counts,
        });
    }
    let bytes = write_resource(original, &mut system, &mut graphics)?;
    let (check_sys, check_gfx) = prepare_rsc7(&bytes)?;
    ensure!(
        check_sys == system && check_gfx == graphics,
        "Page repair round trip changed sections"
    );
    counts.repaired_resources += 1;
    Ok(Converted { bytes, counts })
}

fn write_resource(
    original: &[u8],
    system: &mut Vec<u8>,
    graphics: &mut Vec<u8>,
) -> Result<Vec<u8>> {
    let old_sys = u32_le(original, 8);
    let old_gfx = u32_le(original, 12);
    let mut sys_flags =
        if system.len() == resource_size_from_flags(old_sys) && legal_page_flags(old_sys) {
            old_sys
        } else {
            page_flags(system.len(), old_sys)?
        };
    let gfx_flags =
        if graphics.len() == resource_size_from_flags(old_gfx) && legal_page_flags(old_gfx) {
            old_gfx
        } else {
            page_flags(graphics.len(), old_gfx)?
        };
    if sys_flags != old_sys || gfx_flags != old_gfx {
        ensure!(system.len() >= 16, "Truncated paged resource root");
        let old_map = u64_le(system, 8);
        let old_offset = old_map
            .checked_sub(0x50000000)
            .and_then(|o| usize::try_from(o).ok());
        let old_header = if old_map == 0 {
            None
        } else {
            let off = old_offset.context("Invalid resource page-map pointer")?;
            Some(
                <[u8; 16]>::try_from(
                    system
                        .get(off..off + 16)
                        .context("Truncated resource page-map header")?,
                )
                .unwrap(),
            )
        };
        let has_page_map = old_header.is_some_and(|header| header[11] == 0);
        let capacity = page_count(old_sys) + page_count(old_gfx);
        let needed = page_count(sys_flags) + page_count(gfx_flags);
        let map = if let Some(off) = old_offset.filter(|&o| {
            has_page_map
                && o.checked_add(16 + capacity * 8)
                    .is_some_and(|e| e <= system.len())
                && needed <= capacity
        }) {
            off
        } else {
            let off = system.len().div_ceil(16) * 16;
            system.resize(off + 16 + 128 * 8, 0);
            if let Some(header) = old_header.filter(|_| has_page_map) {
                // pgBasePageMap::Next links serialized metadata. Moving the
                // page-map storage must preserve that chain and its addresses.
                system[off..off + 16].copy_from_slice(&header);
            } else {
                // A pgBase may have metadata as its first node without a map.
                system[off..off + 8].copy_from_slice(&old_map.to_le_bytes());
            }
            sys_flags = page_flags(system.len(), old_sys)?;
            system[8..16].copy_from_slice(&(0x50000000u64 + off as u64).to_le_bytes());
            off
        };
        ensure!(
            page_count(sys_flags) + page_count(gfx_flags) <= 128,
            "Too many resource pages"
        );
        system[map + 8] = page_count(sys_flags) as u8;
        system[map + 9] = page_count(gfx_flags) as u8;
        system[map + 10] = 0;
        system[map + 11] = 0;
    }
    system.resize(resource_size_from_flags(sys_flags), 0);
    graphics.resize(resource_size_from_flags(gfx_flags), 0);
    let mut result = original[..16].to_vec();
    result[8..12].copy_from_slice(&sys_flags.to_le_bytes());
    result[12..16].copy_from_slice(&gfx_flags.to_le_bytes());
    let mut encoder = flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::new(3));
    encoder.write_all(system)?;
    encoder.write_all(graphics)?;
    result.extend_from_slice(&encoder.finish()?);
    Ok(result)
}

fn sample(
    dir: &Path,
    t: &Texture,
    rgba: &[u8],
    blocks: &[u8],
    encoder: &mut Encoder,
    block: (u32, u32),
    encoder_settings: &serde_json::Value,
) -> Result<()> {
    std::fs::create_dir_all(dir)?;
    let decoded = encoder
        .decompress(blocks, t.width, t.height)
        .context("ASTC reference decode")?;
    let name = format!(
        "{:x}-{}",
        t.offset,
        t.name
            .chars()
            .map(|c| if c.is_ascii_alphanumeric() || c == '_' {
                c
            } else {
                '_'
            })
            .take(100)
            .collect::<String>()
    );
    image::save_buffer(
        dir.join(format!("{name}-bc.png")),
        rgba,
        t.width,
        t.height,
        image::ColorType::Rgba8,
    )?;
    image::save_buffer(
        dir.join(format!("{name}-astc.png")),
        &decoded,
        t.width,
        t.height,
        image::ColorType::Rgba8,
    )?;
    let mse = rgba
        .iter()
        .zip(&decoded)
        .map(|(a, b)| (*a as f64 - *b as f64).powi(2))
        .sum::<f64>()
        / rgba.len() as f64;
    let stats = serde_json::json!({"name":t.name,"width":t.width,"height":t.height,"srgb":t.srgb,"block":block,
        "encoderSettings":encoder_settings,
        "psnr_rgba_db":if mse==0.0 {None}else{Some(10.0*(255.0*255.0/mse).log10())},"mse_rgba":mse});
    std::fs::write(
        dir.join(format!("{name}.json")),
        serde_json::to_vec_pretty(&stats)?,
    )?;
    let mut astc = vec![0x13, 0xab, 0xa1, 0x5c, block.0 as u8, block.1 as u8, 1];
    for dimension in [t.width, t.height, 1] {
        astc.extend_from_slice(&dimension.to_le_bytes()[..3]);
    }
    astc.extend_from_slice(blocks);
    std::fs::write(dir.join(format!("{name}.astc")), astc)?;
    Ok(())
}

#[cfg(test)]
pub(crate) mod tests;
