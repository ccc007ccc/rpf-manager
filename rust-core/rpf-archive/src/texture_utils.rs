use crate::resource::{u16_le, u32_le};
use crate::ytd::{TextureFormat, YtdTexture};
use anyhow::{Result, bail, ensure};

/// Decode the top mip into tightly packed RGBA, independent of decoder byte order.
pub fn decompress_texture(texture: &YtdTexture) -> Result<Vec<u8>> {
    let (w, h) = (texture.width as usize, texture.height as usize);
    ensure!(w > 0 && h > 0 && w * h <= 16_777_216, "Texture exceeds the 16 megapixel limit");
    if texture.format.is_block_compressed() {
        return decode_bc_rgba(texture.format as u32, &texture.pixel_data, w, h);
    }
    let bpp = match texture.format {
        TextureFormat::A8R8G8B8 | TextureFormat::X8R8G8B8 | TextureFormat::A8B8G8R8 => 4,
        TextureFormat::A1R5G5B5 => 2,
        TextureFormat::A8 | TextureFormat::L8 => 1,
        _ => bail!("Unsupported texture format: {}", texture.format),
    };
    let pitch = (texture.stride as usize).max(w * bpp);
    ensure!(texture.pixel_data.len() >= pitch * h, "Truncated texture data");
    let mut rgba = Vec::with_capacity(w * h * 4);
    for y in 0..h {
        for x in 0..w {
            let p = &texture.pixel_data[y * pitch + x * bpp..];
            let c = match texture.format {
                TextureFormat::A8R8G8B8 => [p[2], p[1], p[0], p[3]],
                TextureFormat::X8R8G8B8 => [p[2], p[1], p[0], 255],
                TextureFormat::A8B8G8R8 => [p[0], p[1], p[2], p[3]],
                TextureFormat::A8 => [255, 255, 255, p[0]],
                TextureFormat::L8 => [p[0], p[0], p[0], 255],
                TextureFormat::A1R5G5B5 => {
                    let v = u16::from_le_bytes([p[0], p[1]]);
                    let expand = |n: u16| ((n * 255 + 15) / 31) as u8;
                    [expand((v >> 10) & 31), expand((v >> 5) & 31), expand(v & 31), if v & 0x8000 != 0 {255} else {0}]
                },
                _ => unreachable!(),
            };
            rgba.extend_from_slice(&c);
        }
    }
    Ok(rgba)
}

/// Shared BC decoding for previews, exports and ASTC conversion.
pub fn decode_bc_rgba(format: u32, bytes: &[u8], w: usize, h: usize) -> Result<Vec<u8>> {
    ensure!(w > 0 && h > 0 && w.checked_mul(h).is_some_and(|pixels| pixels <= 16_777_216), "Texture exceeds the 16 megapixel limit");
    let decode = match format {
        0x31545844 => texture2ddecoder::decode_bc1a,
        0x32545844 | 0x33545844 => decode_bc2_four_colors,
        0x34545844 | 0x35545844 => decode_bc3_four_colors,
        0x31495441 => texture2ddecoder::decode_bc4,
        0x32495441 => texture2ddecoder::decode_bc5,
        0x20374342 => texture2ddecoder::decode_bc7,
        _ => bail!("Unsupported BC format"),
    };
    let mut pixels = vec![0u32; w * h];
    decode(bytes, w, h, &mut pixels).map_err(anyhow::Error::msg)?;
    Ok(pixels
        .into_iter()
        .flat_map(|p| {
            let mut c = [(p >> 16) as u8, (p >> 8) as u8, p as u8, (p >> 24) as u8];
            if format == 0x31495441 {
                c[1] = 0;
                c[2] = 0;
                c[3] = 255;
            }
            if format == 0x32495441 {
                c[2] = 0;
                c[3] = 255;
            }
            c
        })
        .collect())
}

fn decode_bc2_four_colors(
    bytes: &[u8],
    width: usize,
    height: usize,
    pixels: &mut [u32],
) -> Result<(), &'static str> {
    decode_four_color_blocks(
        bytes,
        width,
        height,
        pixels,
        texture2ddecoder::decode_bc2_block,
    )
}

fn decode_bc3_four_colors(
    bytes: &[u8],
    width: usize,
    height: usize,
    pixels: &mut [u32],
) -> Result<(), &'static str> {
    decode_four_color_blocks(
        bytes,
        width,
        height,
        pixels,
        texture2ddecoder::decode_bc3_block,
    )
}

fn decode_four_color_blocks(
    bytes: &[u8],
    width: usize,
    height: usize,
    pixels: &mut [u32],
    decode: fn(&[u8], &mut [u32]),
) -> Result<(), &'static str> {
    if width == 0
        || height == 0
        || pixels.len() < width * height
        || bytes.len() < width.div_ceil(4) * height.div_ceil(4) * 16
    {
        return Err("Invalid BC2/BC3 image extent or payload");
    }
    for block_y in 0..height.div_ceil(4) {
        for block_x in 0..width.div_ceil(4) {
            let offset = (block_y * width.div_ceil(4) + block_x) * 16;
            let mut block = [0u8; 16];
            block.copy_from_slice(&bytes[offset..offset + 16]);
            let first = u16_le(&block, 8);
            let second = u16_le(&block, 10);
            if first < second {
                block[8..10].copy_from_slice(&second.to_le_bytes());
                block[10..12].copy_from_slice(&first.to_le_bytes());
                let indices = u32_le(&block, 12) ^ 0x55555555;
                block[12..16].copy_from_slice(&indices.to_le_bytes());
            } else if first == second {
                block[12..16].fill(0);
            }
            let mut decoded = [0u32; 16];
            decode(&block, &mut decoded);
            for row in 0..4.min(height - block_y * 4) {
                let count = 4.min(width - block_x * 4);
                let start = (block_y * 4 + row) * width + block_x * 4;
                pixels[start..start + count].copy_from_slice(&decoded[row * 4..row * 4 + count]);
            }
        }
    }
    Ok(())
}
