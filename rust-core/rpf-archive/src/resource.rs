use anyhow::{bail, Result, Context};
use flate2::{Decompress, FlushDecompress, Status};
use crate::archive::{resource_size_from_flags, RSC7_MAGIC};

// ─── Internal virtual-memory reader ──────────────────────────────────────────

pub struct ResReader<'a> {
    pub system:   &'a [u8],
    pub graphics: &'a [u8],
}

impl<'a> ResReader<'a> {
    pub fn resolve(&self, va: u64, len: usize) -> Option<&'a [u8]> {
        let (section, base) = match va >> 28 {
            5 => (self.system, 0x50000000),
            6 => (self.graphics, 0x60000000),
            _ => return None,
        };
        let off = usize::try_from(va - base).ok()?;
        section.get(off..off.checked_add(len)?)
    }

    pub fn string_at(&self, va: u64) -> Option<String> {
        if va >> 28 == 5 {
            let off = (va - 0x50000000) as usize;
            let slice = self.system.get(off..)?;
            let end = slice.iter().position(|&b| b == 0).unwrap_or(slice.len());
            Some(String::from_utf8_lossy(&slice[..end]).into_owned())
        } else {
            None
        }
    }
}

pub fn u16_le(b: &[u8], off: usize) -> u16 {
    u16::from_le_bytes(b[off..off + 2].try_into().unwrap_or([0; 2]))
}
pub fn u32_le(b: &[u8], off: usize) -> u32 {
    u32::from_le_bytes(b[off..off + 4].try_into().unwrap_or([0; 4]))
}
pub fn u64_le(b: &[u8], off: usize) -> u64 {
    u64::from_le_bytes(b[off..off + 8].try_into().unwrap_or([0; 8]))
}

/// Helper to decompress and prepare RSC7 resource sections.
pub fn prepare_rsc7(data: &[u8]) -> Result<(Vec<u8>, Vec<u8>)> {
    if data.len() < 16 {
        bail!("RSC7 data too short");
    }

    let magic = u32::from_le_bytes(data[0..4].try_into().unwrap());
    if magic != RSC7_MAGIC {
        bail!("Not an RSC7 file (magic = 0x{:08X})", magic);
    }

    let system_flags  = u32::from_le_bytes(data[8..12].try_into().unwrap());
    let graphics_flags = u32::from_le_bytes(data[12..16].try_into().unwrap());

    let sys_size  = resource_size_from_flags(system_flags);
    let gfx_size  = resource_size_from_flags(graphics_flags);
    let body      = &data[16..];

    let expected = sys_size.checked_add(gfx_size).context("Resource size overflow")?;
    if expected == 0 || expected > 256 * 1024 * 1024 {
        bail!("Resource exceeds the 256 MiB preview limit");
    }
    // Bound decompression before allocating; never accept truncated sections.
    let decompressed = if let Some(out) = inflate_exact(body, expected) {
        out
    } else if let Some(out) = body.get(8..).and_then(|tail| inflate_exact(tail, expected)) {
        // Some nested game resources carry an 8-byte prelude before raw
        // DEFLATE. Accept that layout only when the flags-derived section size
        // matches exactly; malformed streams still fail instead of yielding
        // partial sections.
        // A prefixed stream can have exactly the same byte count as a raw body.
        // Validate compressed candidates before using the length-only fallback.
        out
    } else if let Some(out) = inflate_text_mode(body, expected)
        .or_else(|| body.get(8..).and_then(|tail| inflate_text_mode(tail, expected))) {
        out
    } else if body.len() == expected {
        body.to_vec()
    } else {
        bail!("Invalid resource payload length");
    };

    if decompressed.len() < sys_size {
        bail!(
            "Decompressed size {} < expected system size {}",
            decompressed.len(), sys_size
        );
    }

    let system = decompressed[..sys_size].to_vec();
    let graphics = if decompressed.len() >= sys_size + gfx_size {
        decompressed[sys_size..sys_size + gfx_size].to_vec()
    } else {
        decompressed[sys_size..].to_vec()
    };

    Ok((system, graphics))
}

fn inflate_exact(data: &[u8], expected: usize) -> Option<Vec<u8>> {
    let mut decoder = Decompress::new(false);
    let mut out = Vec::with_capacity(expected.checked_add(1)?);
    let status = decoder.decompress_vec(data, &mut out, FlushDecompress::Finish).ok()?;
    (status == Status::StreamEnd && out.len() == expected
        && decoder.total_in() == data.len() as u64).then_some(out)
}

fn inflate_text_mode(data: &[u8], expected: usize) -> Option<Vec<u8>> {
    let mut line_feeds = 0;
    for (offset, &byte) in data.iter().enumerate() {
        if byte == b'\n' {
            if offset == 0 || data[offset - 1] != b'\r' {
                return None;
            }
            line_feeds += 1;
        }
    }
    if line_feeds == 0 {
        return None;
    }
    let mut restored = Vec::with_capacity(data.len() - line_feeds);
    for (offset, &byte) in data.iter().enumerate() {
        if byte != b'\r' || data.get(offset + 1) != Some(&b'\n') {
            restored.push(byte);
        }
    }
    inflate_exact(&restored, expected)
}
