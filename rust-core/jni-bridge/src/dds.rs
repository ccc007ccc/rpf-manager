//! Standalone 2D DDS surfaces using the same pixel decoders as YTD.
use anyhow::{bail, ensure, Result};
use rpf_archive::{
    resource::u32_le,
    ytd::{TextureFormat as F, YtdTexture},
};

pub fn parse(data: &[u8]) -> Result<YtdTexture> {
    ensure!(
        data.len() >= 128 && &data[..4] == b"DDS ",
        "Truncated DDS header"
    );
    ensure!(
        u32_le(data, 4) == 124 && u32_le(data, 76) == 32,
        "Invalid DDS header size"
    );
    let (width, height) = (u32_le(data, 16), u32_le(data, 12));
    ensure!(
        width > 0
            && height > 0
            && width <= u16::MAX as u32
            && height <= u16::MAX as u32
            && width as u64 * height as u64 <= 16_777_216,
        "DDS exceeds texture dimensions limit"
    );
    ensure!(
        u32_le(data, 112) & (0xfe00 | 0x200000) == 0,
        "DDS cubemaps and volumes are not supported"
    );
    let flags = u32_le(data, 80);
    let mut start = 128;
    let format = if flags & 4 != 0 {
        match &data[84..88] {
            b"DXT1" => F::DXT1,
            b"DXT3" => F::DXT3,
            b"DXT5" => F::DXT5,
            b"ATI1" | b"BC4U" => F::ATI1,
            b"ATI2" | b"BC5U" => F::ATI2,
            b"DX10" => {
                ensure!(data.len() >= 148, "Truncated DDS DX10 header");
                ensure!(
                    u32_le(data, 132) == 3 && u32_le(data, 140) == 1 && u32_le(data, 136) & 4 == 0,
                    "DDS arrays, cubemaps and volumes are not supported"
                );
                start = 148;
                match u32_le(data, 128) {
                    71 | 72 => F::DXT1,
                    74 | 75 => F::DXT3,
                    77 | 78 => F::DXT5,
                    80 => F::ATI1,
                    83 => F::ATI2,
                    98 | 99 => F::BC7,
                    28 | 29 => F::A8B8G8R8,
                    87 | 91 => F::A8R8G8B8,
                    88 | 93 => F::X8R8G8B8,
                    n => bail!("Unsupported DDS DXGI format {n}"),
                }
            }
            _ => bail!("Unsupported DDS compression"),
        }
    } else if u32_le(data, 88) == 32 && u32_le(data, 96) == 0xff00 {
        match (u32_le(data, 92), u32_le(data, 100), u32_le(data, 104)) {
            (0xff0000, 0xff, 0xff000000) => F::A8R8G8B8,
            (0xff0000, 0xff, 0) => F::X8R8G8B8,
            (0xff, 0xff0000, 0xff000000) => F::A8B8G8R8,
            _ => bail!("Unsupported DDS pixel masks"),
        }
    } else if flags & 0x20000 != 0 && u32_le(data, 88) == 8 {
        F::L8
    } else {
        bail!("Unsupported DDS pixel format")
    };
    let pitch = if !format.is_block_compressed() && u32_le(data, 8) & 8 != 0 {
        u32_le(data, 20)
    } else {
        0
    };
    ensure!(
        pitch <= u16::MAX as u32,
        "DDS row stride exceeds preview limit"
    );
    Ok(YtdTexture {
        name: "surface".into(),
        name_hash: 0,
        width: width as u16,
        height: height as u16,
        depth: 1,
        format,
        levels: u32_le(data, 28).clamp(1, 16) as u8,
        stride: pitch as u16,
        pixel_data: data[start..].to_vec(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn legacy_header_decodes_real_pixels_and_rejects_cubemaps() {
        let texture = rpf_archive::parse_ytd(&crate::fixtures::textures())
            .unwrap()
            .remove(0);
        let dds = texture.to_dds();
        let decoded = parse(&dds).unwrap();
        assert_eq!(
            rpf_archive::texture_utils::decompress_texture(&decoded).unwrap(),
            rpf_archive::texture_utils::decompress_texture(&texture).unwrap()
        );
        assert!(parse(&dds[..120]).is_err());
        let mut cube = dds;
        cube[112..116].copy_from_slice(&0x200u32.to_le_bytes());
        assert!(parse(&cube).is_err());
    }
}
