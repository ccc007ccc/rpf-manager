//! Resource previews use the same texture discovery and BC decoding as conversion.
//! PNG previews show mip 0 / layer 0. ASTC never masquerades as a standard DDS format.
use super::*;
use crate::transcode::Texture;

enum Textures {
    Original(Vec<rpf_archive::ytd::YtdTexture>, bool),
    Resource {
        system: Vec<u8>,
        graphics: Vec<u8>,
        textures: Vec<Texture>,
    },
}
impl Textures {
    fn read(data: &[u8], ext: &str) -> Result<Self> {
        if !data.starts_with(b"RSC7") {
            let srgb = ext == "dds"
                && data.len() >= 148
                && &data[84..88] == b"DX10"
                && matches!(
                    rpf_archive::resource::u32_le(data, 128),
                    29 | 72 | 75 | 78 | 91 | 93 | 99
                );
            return Ok(Self::Original(decode_textures(data, ext)?, srgb));
        }
        let (system, graphics) = rpf_archive::resource::prepare_rsc7(data)?;
        let textures = transcode::textures_for_resource(&system, &graphics, ext)?;
        Ok(Self::Resource {
            system,
            graphics,
            textures,
        })
    }
    fn list(&self) -> Value {
        match self {
            Self::Original(ts,srgb)=>json!(ts.iter().enumerate().map(|(i,t)|json!({"index":i,"name":t.name,
                "width":t.width,"height":t.height,"format":t.format.to_string(),"levels":t.levels,"srgb":srgb})).collect::<Vec<_>>()),
            Self::Resource {textures,..}=>json!(textures.iter().enumerate().map(|(i,t)|json!({"index":i,"name":t.name,
                "width":t.width,"height":t.height,"levels":t.levels,"layers":t.layers,"depth":t.depth,"srgb":t.srgb,
                "format":transcode::astc_block(t.format).map(|(w,h)|format!("ASTC {w}x{h}"))
                    .unwrap_or_else(||rpf_archive::ytd::TextureFormat::from_u32(t.format).to_string())})).collect::<Vec<_>>()),
        }
    }
    fn rgba(&self, index: usize) -> Result<(u32, u32, Vec<u8>)> {
        let (width, height, rgba) = match self {
            Self::Original(ts, _) => {
                let t = ts.get(index).context("Texture index out of range")?;
                (
                    t.width as u32,
                    t.height as u32,
                    rpf_archive::texture_utils::decompress_texture(t)?,
                )
            }
            Self::Resource {
                system,
                graphics,
                textures,
            } => {
                let t = textures.get(index).context("Texture index out of range")?;
                ensure!(
                    t.depth == 1 && t.image_type <= 1,
                    "Volume and special texture previews are not supported"
                );
                let (w, h) = (t.width as usize, t.height as usize);
                ensure!(
                    w * h <= 16_777_216,
                    "Texture exceeds the 16 megapixel limit"
                );
                let rgba = if let Some((bw, bh)) = transcode::astc_block(t.format) {
                    let size = t.mip_layout(0)?.2;
                    let bytes = graphics
                        .get(t.data_offset..t.data_offset + size)
                        .context("Truncated ASTC payload")?;
                    let mut pixels = vec![0u32; w * h];
                    texture2ddecoder::decode_astc(
                        bytes,
                        w,
                        h,
                        bw as usize,
                        bh as usize,
                        &mut pixels,
                    )
                    .map_err(anyhow::Error::msg)?;
                    pixels
                        .into_iter()
                        .flat_map(|p| [(p >> 16) as u8, (p >> 8) as u8, p as u8, (p >> 24) as u8])
                        .collect()
                } else if transcode::is_bc_format(t.format) {
                    let size = t.mip_layout(0)?.2;
                    let bytes = graphics
                        .get(t.data_offset..t.data_offset + size)
                        .context("Truncated BC payload")?;
                    rpf_archive::texture_utils::decode_bc_rgba(t.format, bytes, w, h)?
                } else {
                    use rpf_archive::ytd::{TextureFormat, YtdTexture};
                    let format = TextureFormat::from_u32(t.format);
                    let stride = rpf_archive::resource::u16_le(system, t.offset + 0x56);
                    let size = if format.is_block_compressed() {
                        t.mip_layout(0)?.2
                    } else {
                        let bpp = match format {
                            TextureFormat::A8 | TextureFormat::L8 => 1,
                            TextureFormat::A1R5G5B5 => 2,
                            TextureFormat::A8R8G8B8
                            | TextureFormat::A8B8G8R8
                            | TextureFormat::X8R8G8B8 => 4,
                            _ => bail!("Unsupported texture format 0x{:08x}", t.format),
                        };
                        (stride as usize).max(w * bpp) * h
                    };
                    rpf_archive::texture_utils::decompress_texture(&YtdTexture {
                        name: t.name.clone(),
                        name_hash: 0,
                        width: t.width as u16,
                        height: t.height as u16,
                        depth: 1,
                        format,
                        levels: 1,
                        stride,
                        pixel_data: graphics
                            .get(t.data_offset..t.data_offset + size)
                            .context("Truncated texture payload")?
                            .to_vec(),
                    })?
                };
                (t.width, t.height, rgba)
            }
        };
        Ok((width, height, rgba))
    }
    fn png(&self, index: usize) -> Result<Vec<u8>> {
        let (width, height, rgba) = self.rgba(index)?;
        let mut bytes = std::io::Cursor::new(Vec::new());
        image::RgbaImage::from_raw(width, height, rgba)
            .context("Invalid pixel buffer")?
            .write_to(&mut bytes, image::ImageFormat::Png)?;
        Ok(bytes.into_inner())
    }
}
pub fn texture_list(data: &[u8], ext: &str) -> Result<Value> {
    let list = Textures::read(data, ext)?.list();
    ensure!(
        ext != "ytd" || !list.as_array().unwrap().is_empty(),
        "No readable textures in dictionary"
    );
    Ok(list)
}
pub fn texture_preview(data: &[u8], ext: &str, index: usize) -> Result<Vec<u8>> {
    Textures::read(data, ext)?.png(index)
}

pub fn texture_results(data: &[u8], ext: &str) -> Result<Vec<Result<()>>> {
    let textures = Textures::read(data, ext)?;
    let count = textures.list().as_array().unwrap().len();
    ensure!(count > 0, "Empty texture dictionary");
    Ok((0..count)
        .map(|index| textures.rgba(index).map(|_| ()))
        .collect())
}
