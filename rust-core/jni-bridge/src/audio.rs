//! PC AWC: bounded metadata, PCM16 and Rockstar IMA ADPCM decoding.
use anyhow::{bail, ensure, Context, Result};
use serde_json::{json, Value};
use std::io::Write;

const PCM_LIMIT: usize = 64 * 1024 * 1024;
fn u16_at(b: &[u8], o: usize) -> Result<u16> {
    Ok(u16::from_le_bytes(
        b.get(o..o + 2).context("Truncated AWC")?.try_into()?,
    ))
}
fn u32_at(b: &[u8], o: usize) -> Result<u32> {
    Ok(u32::from_le_bytes(
        b.get(o..o + 4).context("Truncated AWC")?.try_into()?,
    ))
}
#[derive(Clone)]
struct Span<'a> {
    data: &'a [u8],
    samples: usize,
}
pub struct Track<'a> {
    pub id: u32,
    pub samples: usize,
    pub rate: u32,
    pub codec: u8,
    pub reason: String,
    spans: Vec<Span<'a>>,
}
pub struct Awc<'a> {
    pub tracks: Vec<Track<'a>>,
}
struct Chunk<'a> {
    tag: u8,
    bytes: &'a [u8],
}
impl<'a> Awc<'a> {
    pub fn parse(data: &'a [u8]) -> Result<Self> {
        ensure!(data.len() >= 16, "Truncated AWC header");
        ensure!(
            &data[..4] == b"ADAT",
            "Unsupported AWC byte order or whole-file encryption"
        );
        ensure!(u16_at(data, 4)? == 1, "Unsupported AWC version");
        let flags = u16_at(data, 6)?;
        let count = u32_at(data, 8)? as usize;
        let data_offset = u32_at(data, 12)? as usize;
        ensure!(count > 0 && count <= 65536, "Invalid AWC stream count");
        ensure!(data_offset <= data.len(), "Invalid AWC data offset");
        let info_start = 16 + if flags & 1 != 0 { count * 2 } else { 0 };
        let mut cursor = info_start + count * 4;
        ensure!(cursor <= data_offset, "Invalid AWC stream table");
        let mut streams = Vec::new();
        for i in 0..count {
            let info = u32_at(data, info_start + i * 4)?;
            let mut chunks = Vec::new();
            for _ in 0..info >> 29 {
                ensure!(cursor + 8 <= data_offset, "Truncated AWC chunk table");
                let raw = u64::from_le_bytes(data[cursor..cursor + 8].try_into()?);
                cursor += 8;
                let start = (raw & 0x0fffffff) as usize;
                let len = ((raw >> 28) & 0x0fffffff) as usize;
                ensure!(start >= data_offset, "AWC chunk overlaps header");
                let bytes = data
                    .get(start..start + len)
                    .context("AWC chunk outside file")?;
                let tag = (raw >> 56) as u8;
                ensure!(
                    !chunks.iter().any(|c: &Chunk| c.tag == tag),
                    "Duplicate AWC chunk"
                );
                chunks.push(Chunk { tag, bytes });
            }
            streams.push((info & 0x1fffffff, chunks));
        }
        let mut tracks = Vec::new();
        // Streaming AWC stores multiple independently playable channels in blocks.
        for (_, chunks) in &streams {
            let Some(format) = chunks.iter().find(|c| c.tag == 0x48).map(|c| c.bytes) else {
                continue;
            };
            let block_count = u32_at(format, 0)? as usize;
            let block_size = u32_at(format, 4)? as usize;
            let channels = u32_at(format, 8)? as usize;
            ensure!(
                channels > 0 && channels <= 256 && block_count <= 65536 && block_size >= 2048,
                "Invalid AWC streaming layout"
            );
            ensure!(
                format.len() >= 12 + channels * 16,
                "Truncated AWC channel formats"
            );
            let payload = chunks
                .iter()
                .find(|c| c.tag == 0x55)
                .context("AWC stream data missing")?
                .bytes;
            ensure!(
                block_count > 0
                    && (block_count - 1)
                        .checked_mul(block_size)
                        .is_some_and(|n| n < payload.len()),
                "Truncated AWC streaming data"
            );
            let first = tracks.len();
            for c in 0..channels {
                let f = &format[12 + c * 16..12 + (c + 1) * 16];
                tracks.push(Track {
                    id: u32_at(f, 0)? & 0x1fffffff,
                    samples: u32_at(f, 4)? as usize,
                    rate: u16_at(f, 10)? as u32,
                    codec: f[12],
                    reason: String::new(),
                    spans: vec![],
                });
            }
            if flags & 8 == 0 {
                for block in payload.chunks(block_size).take(block_count) {
                    ensure!(block.len() >= channels * 24, "Truncated AWC block channels");
                    let mut counts = Vec::new();
                    let mut header = channels * 24;
                    for c in 0..channels {
                        let n = u32_at(block, c * 24 + 4)? as usize;
                        ensure!(n <= block.len() / 2048, "Invalid AWC channel block count");
                        header += n * 4;
                        counts.push(n);
                    }
                    let mut offset = header.div_ceil(2048) * 2048;
                    for (c, n) in counts.into_iter().enumerate() {
                        let bytes = block
                            .get(offset..offset + n * 2048)
                            .context("AWC channel outside block")?;
                        let samples = u32_at(block, c * 24 + 12)? as usize;
                        tracks[first + c].spans.push(Span {
                            data: bytes,
                            samples,
                        });
                        offset += n * 2048;
                    }
                }
            } else {
                for t in &mut tracks[first..] {
                    t.reason = "encrypted".into();
                }
            }
        }
        for (id, chunks) in &streams {
            if chunks.iter().any(|c| c.tag == 0x48) || tracks.iter().any(|t| t.id == *id) {
                continue;
            }
            let format = chunks.iter().find(|c| c.tag == 0xfa).map(|c| c.bytes);
            let payload = chunks.iter().find(|c| c.tag == 0x55).map(|c| c.bytes);
            let mut t = Track {
                id: *id,
                samples: 0,
                rate: 0,
                codec: 255,
                reason: "format".into(),
                spans: vec![],
            };
            if let Some(f) = format {
                ensure!(f.len() >= 20, "Truncated AWC format chunk");
                t.samples = u32_at(f, 0)? as usize;
                t.rate = u16_at(f, 8)? as u32;
                t.codec = f[19];
                t.reason.clear();
                if flags & 2 != 0 {
                    t.reason = "encrypted".into();
                }
                if let Some(bytes) = payload {
                    t.spans.push(Span {
                        data: bytes,
                        samples: t.samples,
                    });
                } else {
                    t.reason = "no_data".into();
                }
            }
            tracks.push(t);
        }
        ensure!(!tracks.is_empty(), "AWC contains no streams");
        for t in &mut tracks {
            if !t.reason.is_empty() {
                continue;
            }
            if !matches!(t.codec, 0 | 4) {
                t.reason = "codec".into();
            } else if !(1000..=192000).contains(&t.rate) || t.samples == 0 {
                t.reason = "format".into();
            } else if t.samples > PCM_LIMIT / 2 {
                t.reason = "limit".into();
            }
        }
        Ok(Self { tracks })
    }
    pub fn metadata(&self) -> Value {
        json!(self.tracks.iter().enumerate().map(|(index, t)| json!({
            "index":index, "id":format!("{:08X}", t.id), "samples":t.samples, "rate":t.rate,
            "codec":match t.codec {0=>"PCM16".into(),4=>"IMA ADPCM".into(),n=>format!("Codec {n}")},
            "duration_ms": if t.rate > 0 {t.samples as u64 * 1000 / t.rate as u64} else {0},
            "playable":t.reason.is_empty(), "reason":t.reason
        })).collect::<Vec<_>>())
    }
}
impl Track<'_> {
    pub fn wav(&self, output: &mut impl Write) -> Result<()> {
        ensure!(
            self.reason.is_empty(),
            "Unsupported AWC track: {}",
            self.reason
        );
        ensure!(
            self.samples <= PCM_LIMIT / 2,
            "Audio exceeds decoded preview limit"
        );
        let size = (self.samples * 2) as u32;
        output.write_all(b"RIFF")?;
        output.write_all(&(size + 36).to_le_bytes())?;
        output.write_all(b"WAVEfmt \x10\0\0\0\x01\0\x01\0")?;
        output.write_all(&self.rate.to_le_bytes())?;
        output.write_all(&(self.rate * 2).to_le_bytes())?;
        output.write_all(b"\x02\0\x10\0data")?;
        output.write_all(&size.to_le_bytes())?;
        let mut remaining = self.samples;
        for span in &self.spans {
            let count = remaining.min(span.samples);
            match self.codec {
                0 => output.write_all(
                    span.data
                        .get(..count * 2)
                        .context("Truncated AWC PCM samples")?,
                )?,
                4 => decode_ima(span.data, count, output)?,
                _ => bail!("Unsupported AWC codec"),
            }
            remaining -= count;
            if remaining == 0 {
                break;
            }
        }
        ensure!(remaining == 0, "Truncated AWC audio samples");
        Ok(())
    }
}
fn decode_ima(data: &[u8], count: usize, output: &mut impl Write) -> Result<()> {
    const INDEX: [i32; 8] = [-1, -1, -1, -1, 2, 4, 6, 8];
    const STEP: [i32; 89] = [
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60,
        66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371,
        408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878,
        2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845,
        8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086,
        29794, 32767,
    ];
    let mut left = count;
    for block in data.chunks(2048) {
        if left == 0 {
            break;
        }
        ensure!(
            block.len() >= 4 && block[0] <= 88,
            "Invalid AWC ADPCM block"
        );
        let mut index = block[0] as i32;
        let mut predictor = i16::from_le_bytes([block[2], block[3]]) as i32;
        let mut pcm = Vec::with_capacity(8176);
        for nibble in block[4..].iter().flat_map(|b| [b & 15, b >> 4]) {
            if left == 0 {
                break;
            }
            let delta = (((nibble & 7) as i32 * 2 + 1) * STEP[index as usize]) >> 3;
            predictor =
                (predictor + if nibble & 8 != 0 { -delta } else { delta }).clamp(-32768, 32767);
            index = (index + INDEX[(nibble & 7) as usize]).clamp(0, 88);
            pcm.extend((predictor as i16).to_le_bytes());
            left -= 1;
        }
        output.write_all(&pcm)?;
    }
    ensure!(left == 0, "Truncated AWC ADPCM samples");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ima_known_samples_and_odd_length() {
        let mut out = Vec::new();
        decode_ima(&[0, 0, 0xe8, 3, 0x11, 0x77], 3, &mut out).unwrap();
        assert_eq!(
            out,
            [1002i16, 1004, 1017]
                .into_iter()
                .flat_map(i16::to_le_bytes)
                .collect::<Vec<_>>()
        );
        assert!(decode_ima(&[0, 0, 0, 0, 0], 3, &mut Vec::new()).is_err());
        assert!(decode_ima(&[89, 0, 0, 0, 0], 1, &mut Vec::new()).is_err());
    }
    #[test]
    fn pcm_wav_is_exact_without_padding_samples() {
        let t = Track {
            id: 1,
            samples: 3,
            rate: 8000,
            codec: 0,
            reason: String::new(),
            spans: vec![Span {
                data: &[0, 0, 1, 0, 255, 255, 9, 9],
                samples: 3,
            }],
        };
        let mut out = Vec::new();
        t.wav(&mut out).unwrap();
        assert_eq!(out.len(), 50);
        assert_eq!(&out[44..], &[0, 0, 1, 0, 255, 255]);
        assert_eq!(&out[..4], b"RIFF");
        assert_eq!(u32_at(&out, 24).unwrap(), 8000);
    }
    #[test]
    fn truncated_headers_are_errors() {
        for n in 0..64 {
            assert!(Awc::parse(&vec![0; n]).is_err());
        }
    }
}
