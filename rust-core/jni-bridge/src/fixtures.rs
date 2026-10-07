//! Original, procedural test assets. No game assets are bundled.
use crate::mesh::box_geometry;
use rpf_archive::{RpfBuilder, RpfEncryption};
use std::io::Write;
const SYS: u64 = 0x50000000;
const GFX: u64 = 0x60000000;
fn u16w(b: &mut [u8], o: usize, v: u16) {
    b[o..o + 2].copy_from_slice(&v.to_le_bytes());
}
fn u32w(b: &mut [u8], o: usize, v: u32) {
    b[o..o + 4].copy_from_slice(&v.to_le_bytes());
}
fn u64w(b: &mut [u8], o: usize, v: u64) {
    b[o..o + 8].copy_from_slice(&v.to_le_bytes());
}
fn fw(b: &mut [u8], o: usize, v: f32) {
    b[o..o + 4].copy_from_slice(&v.to_le_bytes());
}
fn alloc(b: &mut Vec<u8>, size: usize) -> usize {
    let o = b.len().div_ceil(16) * 16;
    b.resize(o + size, 0);
    o
}
fn ptr(b: &mut [u8], o: usize, v: usize) {
    u64w(b, o, SYS + v as u64);
}
fn rsc(mut sys: Vec<u8>, mut gfx: Vec<u8>, version: u32) -> Vec<u8> {
    fn pad(b: &mut Vec<u8>) -> u32 {
        if b.is_empty() {
            return 0;
        }
        let mut shift = 0;
        while (8192 << shift) < b.len() {
            shift += 1;
        }
        b.resize(8192 << shift, 0);
        0x20000 | shift as u32
    }
    let sf = pad(&mut sys) | ((version >> 4) << 28);
    let gf = pad(&mut gfx) | ((version & 15) << 28);
    let mut out = b"RSC7".to_vec();
    out.extend(version.to_le_bytes());
    out.extend(sf.to_le_bytes());
    out.extend(gf.to_le_bytes());
    let mut compressor =
        flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::default());
    compressor.write_all(&sys).unwrap();
    compressor.write_all(&gfx).unwrap();
    out.extend(compressor.finish().unwrap());
    out
}
pub fn rover() -> Vec<u8> {
    let mut parts = vec![
        box_geometry([-1.8, -0.76, 0.65], [1.8, 0.76, 1.12]),
        box_geometry([-1.7, -0.69, 1.12], [-0.7, 0.69, 1.35]),
        box_geometry([-0.55, -0.66, 1.12], [0.88, 0.66, 1.92]),
        box_geometry([-0.64, -0.78, 1.92], [0.98, 0.78, 2.05]),
        box_geometry([0.95, -0.67, 1.12], [1.62, 0.67, 1.37]),
        box_geometry([-1.9, -0.81, 0.65], [-1.76, 0.81, 0.83]),
        box_geometry([1.76, -0.81, 0.65], [1.9, 0.81, 0.83]),
    ];
    for x in [-1.15, 1.15] {
        for y in [-0.87, 0.87] {
            let mut points = Vec::new();
            let mut indices = Vec::new();
            for side in [-0.19, 0.19] {
                for i in 0..16 {
                    let a = i as f32 * std::f32::consts::TAU / 16.;
                    points.push([x + 0.48 * a.cos(), y + side, 0.58 + 0.48 * a.sin()]);
                }
            }
            for i in 0..16u32 {
                let j = (i + 1) % 16;
                indices.extend([i, j, i + 16, j, j + 16, i + 16]);
            }
            for i in 1..15u32 {
                indices.extend([0, i + 1, i, 16, 16 + i, 17 + i]);
            }
            parts.push((points, indices));
        }
    }
    let mut sys = vec![0; 0xB0];
    let mut gfx = Vec::new();
    let list = alloc(&mut sys, 16);
    ptr(&mut sys, 0x50, list);
    let mp = alloc(&mut sys, 8);
    ptr(&mut sys, list, mp);
    u16w(&mut sys, list + 8, 1);
    let model = alloc(&mut sys, 0x30);
    ptr(&mut sys, mp, model);
    let gp = alloc(&mut sys, parts.len() * 8);
    ptr(&mut sys, model + 8, gp);
    u16w(&mut sys, model + 16, parts.len() as u16);
    for (i, (positions, indices)) in parts.into_iter().enumerate() {
        let geo = alloc(&mut sys, 0x98);
        ptr(&mut sys, gp + i * 8, geo);
        let vb = alloc(&mut sys, 0x40);
        ptr(&mut sys, geo + 0x18, vb);
        let ib = alloc(&mut sys, 0x40);
        ptr(&mut sys, geo + 0x38, ib);
        let vo = alloc(&mut gfx, positions.len() * 20);
        let io = alloc(&mut gfx, indices.len() * 2);
        u16w(&mut sys, vb + 8, 20);
        let declaration = alloc(&mut sys, 16);
        ptr(&mut sys, vb + 0x30, declaration);
        u32w(&mut sys, declaration, 0x41);
        u16w(&mut sys, declaration + 4, 20);
        u64w(&mut sys, declaration + 8, 6 | (5 << 24));
        u64w(&mut sys, vb + 16, GFX + vo as u64);
        u32w(&mut sys, vb + 24, positions.len() as u32);
        u32w(&mut sys, ib + 8, indices.len() as u32);
        u64w(&mut sys, ib + 16, GFX + io as u64);
        for (j, p) in positions.iter().enumerate() {
            for k in 0..3 {
                fw(&mut gfx, vo + j * 20 + k * 4, p[k]);
            }
            let uv = [[0., 0.], [1., 0.], [1., 1.], [0., 1.]][j % 4];
            fw(&mut gfx, vo + j * 20 + 12, uv[0]);
            fw(&mut gfx, vo + j * 20 + 16, uv[1]);
        }
        for (j, idx) in indices.iter().enumerate() {
            u16w(&mut gfx, io + j * 2, *idx as u16);
        }
    }
    let name = alloc(&mut sys, 16);
    sys[name..name + 5].copy_from_slice(b"rover");
    ptr(&mut sys, 0xA8, name);
    let group = alloc(&mut sys, 0x40);
    ptr(&mut sys, 0x10, group);
    let dict = alloc(&mut sys, 64);
    ptr(&mut sys, group + 8, dict);
    write_textures(&mut sys, &mut gfx, dict);
    rsc(sys, gfx, 165)
}
pub fn textures() -> Vec<u8> {
    let mut sys = vec![0; 64];
    let mut gfx = vec![];
    write_textures(&mut sys, &mut gfx, 0);
    rsc(sys, gfx, 13)
}
fn write_textures(sys: &mut Vec<u8>, gfx: &mut Vec<u8>, dict: usize) {
    let pixels = alloc(gfx, 128 * 128 * 4 * 2);
    let tp = alloc(sys, 16);
    let hashes = alloc(sys, 8);
    ptr(sys, dict + 0x20, hashes);
    u32w(sys, hashes, 0x12345678);
    u32w(sys, hashes + 4, 0x87654321);
    u16w(sys, dict + 0x28, 2);
    u16w(sys, dict + 0x2a, 2);
    ptr(sys, dict + 0x30, tp);
    u16w(sys, dict + 0x38, 2);
    u16w(sys, dict + 0x3a, 2);
    for i in 0..2 {
        let t = alloc(sys, 0x90);
        ptr(sys, tp + i * 8, t);
        let name = alloc(sys, 16);
        let label = if i == 0 {
            b"studio_grid".as_slice()
        } else {
            b"signal_alpha".as_slice()
        };
        sys[name..name + label.len()].copy_from_slice(label);
        ptr(sys, t + 0x28, name);
        u16w(sys, t + 0x50, 128);
        u16w(sys, t + 0x52, 128);
        u16w(sys, t + 0x54, 1);
        u16w(sys, t + 0x56, 512);
        u32w(sys, t + 0x58, 21);
        sys[t + 0x5D] = 1;
        u64w(sys, t + 0x70, GFX + (pixels + i * 128 * 128 * 4) as u64);
        for y in 0..128 {
            for x in 0..128 {
                let (r, g, b, a) = if i == 0 {
                    if (x / 16 + y / 16) % 2 == 0 {
                        (208, 161, 93, 255)
                    } else {
                        (46, 57, 68, 255)
                    }
                } else {
                    let dx = x as i32 - 64;
                    let dy = y as i32 - 64;
                    if dx * dx + dy * dy < 2800 {
                        (107, 192, 175, ((128 - x) * 2 - 1) as u8)
                    } else {
                        (0, 0, 0, 0)
                    }
                };
                let o = pixels + (i * 128 * 128 + y * 128 + x) * 4;
                gfx[o..o + 4].copy_from_slice(&[b, g, r, a]);
            }
        }
    }
}

pub fn audio() -> Vec<u8> {
    let rate = 16000u16;
    let samples = 32000u32;
    let mut pcm = Vec::new();
    for i in 0..samples {
        let envelope = ((i as f32 / 800.).min(1.) * ((samples - i) as f32 / 1600.).min(1.)).min(1.);
        let value = ((i as f32 * std::f32::consts::TAU * 440. / rate as f32).sin()
            * 8000.
            * envelope) as i16;
        pcm.extend(value.to_le_bytes());
    }
    let mut adpcm = vec![0; 2048 * 8];
    for block in adpcm.chunks_exact_mut(2048) {
        block[0] = 30;
        for (i, b) in block[4..].iter_mut().enumerate() {
            *b = if i % 40 < 20 { 0x11 } else { 0x99 };
        }
    }
    let mut out = vec![0; 16 + 8 + 32];
    out[..4].copy_from_slice(b"ADAT");
    u16w(&mut out, 4, 1);
    u16w(&mut out, 6, 0xff00);
    u32w(&mut out, 8, 2);
    u32w(&mut out, 12, 56);
    u32w(&mut out, 16, (2 << 29) | 0x123);
    u32w(&mut out, 20, (2 << 29) | 0x456);
    for (i, data) in [pcm, adpcm].into_iter().enumerate() {
        let mut format = vec![0; 20];
        u32w(&mut format, 0, samples);
        u16w(&mut format, 8, rate);
        format[19] = if i == 0 { 0 } else { 4 };
        for (j, (tag, bytes)) in [(0xfau64, format), (0x55, data)].into_iter().enumerate() {
            let offset = out.len();
            let chunk = (tag << 56) | ((bytes.len() as u64) << 28) | offset as u64;
            u64w(&mut out, 24 + i * 16 + j * 8, chunk);
            out.extend(bytes);
        }
    }
    out
}
pub fn collision() -> Vec<u8> {
    let mut sys = vec![0; 0x130];
    sys[0x10] = 4;
    let (points, indices) = box_geometry([-2., -1., 0.], [2., 1., 1.]);
    let vo = alloc(&mut sys, points.len() * 6);
    let po = alloc(&mut sys, indices.len() / 3 * 16);
    ptr(&mut sys, 0xB0, vo);
    ptr(&mut sys, 0x88, po);
    for k in 0..3 {
        fw(&mut sys, 0x90 + k * 4, 0.001);
        fw(&mut sys, 0x30 + k * 4, [-2., -1., 0.][k]);
        fw(&mut sys, 0x20 + k * 4, [2., 1., 1.][k]);
    }
    u32w(&mut sys, 0xD0, points.len() as u32);
    u32w(&mut sys, 0xD4, (indices.len() / 3) as u32);
    for (i, p) in points.iter().enumerate() {
        for k in 0..3 {
            u16w(
                &mut sys,
                vo + i * 6 + k * 2,
                (p[k] * 1000.).round() as i16 as u16,
            );
        }
    }
    for (i, t) in indices.chunks_exact(3).enumerate() {
        for k in 0..3 {
            u16w(&mut sys, po + i * 16 + 4 + k * 2, t[k] as u16);
        }
    }
    rsc(sys, vec![], 43)
}
pub fn map() -> Vec<u8> {
    let n = 20;
    let mut sys = vec![0; 112];
    let table = alloc(&mut sys, 48);
    ptr(&mut sys, 48, table);
    u16w(&mut sys, 76, 3);
    u32w(&mut sys, 28, 1);
    let root = alloc(&mut sys, 512);
    let pointers = alloc(&mut sys, n * 8);
    let entities = alloc(&mut sys, n * 128);
    for (i, (hash, size, pos)) in [
        (3545841574u32, 512, root),
        (7, n * 8, pointers),
        (3461354627, n * 128, entities),
    ]
    .into_iter()
    .enumerate()
    {
        u32w(&mut sys, table + i * 16, hash);
        u32w(&mut sys, table + i * 16 + 4, size as u32);
        ptr(&mut sys, table + i * 16 + 8, pos);
    }
    u64w(&mut sys, root + 96, 2);
    u16w(&mut sys, root + 104, n as u16);
    for i in 0..n {
        u64w(&mut sys, pointers + i * 8, 3 | (((i * 128) as u64) << 12));
        let o = entities + i * 128;
        u32w(&mut sys, o + 8, 100 + i as u32);
        fw(&mut sys, o + 32, (i % 5) as f32 * 3.);
        fw(&mut sys, o + 36, (i / 5) as f32 * 3.);
        fw(&mut sys, o + 60, 1.);
        fw(&mut sys, o + 64, 1.4);
        fw(&mut sys, o + 68, 1. + (i % 4) as f32);
    }
    rsc(sys, vec![], 2)
}
pub fn empty_map() -> Vec<u8> {
    let mut sys = vec![0; 112];
    let table = alloc(&mut sys, 16);
    let root = alloc(&mut sys, 512);
    ptr(&mut sys, 48, table);
    u16w(&mut sys, 76, 1);
    u32w(&mut sys, 28, 1);
    u32w(&mut sys, table, rpf_archive::rage_joaat("CMapData"));
    u32w(&mut sys, table + 4, 512);
    ptr(&mut sys, table + 8, root);
    rsc(sys, vec![], 2)
}
pub fn archive() -> Vec<u8> {
    let mut b = RpfBuilder::new(RpfEncryption::Open);
    b.add_file("rover.ydr", rover());
    b.add_file("materials.ytd", textures());
    b.add_file("blockout.ymap", map());
    b.add_file("empty.ymap", empty_map());
    b.add_file("chassis.ybn", collision());
    b.add_file("tones.awc", audio());
    b.add_file("notes/说明.txt","这些是程序生成的示例资源，不包含游戏资产。\nOriginal procedural assets for testing RPF Manager.\n".as_bytes().to_vec());
    b.add_file(
        "notes/readme.txt",
        b"A small workspace for exploring textures, models, map entities and collision geometry.\n"
            .to_vec(),
    );
    let mut nested = RpfBuilder::new(RpfEncryption::Open);
    nested.add_file(
        "readme.txt",
        b"This file belongs to the nested archive.\n".to_vec(),
    );
    b.add_file("nested.rpf", nested.build(None).unwrap());
    b.build(None).unwrap()
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn empty_map_is_valid_but_a_broken_entity_list_is_not() {
        let bytes = empty_map();
        let mesh = crate::mesh::parse(&bytes, "ymap").unwrap();
        assert_eq!(mesh.flags, 1);
        assert_eq!(mesh.parts, 0);
        assert!(mesh.indices.is_empty() && mesh.vertices.is_empty());
        let encoded = mesh.encode();
        assert_eq!(encoded.len(), 48);
        assert!(encoded[24..].iter().all(|b| *b == 0));

        let (mut sys, gfx) = rpf_archive::resource::prepare_rsc7(&bytes).unwrap();
        let table = (rpf_archive::resource::u64_le(&sys, 48) - SYS) as usize;
        let root = (rpf_archive::resource::u64_le(&sys, table + 8) - SYS) as usize;
        u16w(&mut sys, root + 104, 1);
        assert!(crate::mesh::parse(&rsc(sys, gfx, 2), "ymap").is_err());
    }
    #[test]
    fn all_preview_formats_parse() {
        let rover = rover();
        let model = crate::mesh::parse(&rover, "ydr").unwrap();
        assert_eq!(model.parts, 11);
        assert_eq!(model.flags & 4, 4);
        assert!(model
            .vertices
            .chunks_exact(crate::mesh::STRIDE)
            .all(|v| v[11] == 1.));
        assert_eq!(
            crate::store::decode_textures(&rover, "ydr").unwrap().len(),
            2
        );
        let sound = audio();
        let awc = crate::audio::Awc::parse(&sound).unwrap();
        assert_eq!(awc.tracks.len(), 2);
        for track in awc.tracks {
            let mut wav = Vec::new();
            track.wav(&mut wav).unwrap();
            assert_eq!(wav.len(), 64044);
        }
        let c = crate::mesh::parse(&collision(), "ybn").unwrap();
        assert_eq!(c.indices.len(), 36);
        assert_eq!(c.vertices[0], -2.);
        let m = crate::mesh::parse(&map(), "ymap").unwrap();
        assert_eq!(m.parts, 20);
        assert_eq!(m.flags, 1);
        let ts = rpf_archive::parse_ytd(&textures()).unwrap();
        assert_eq!(ts.len(), 2);
        let rgba = rpf_archive::texture_utils::decompress_texture(&ts[0]).unwrap();
        assert_eq!(&rgba[..4], &[208, 161, 93, 255]);
        assert_eq!(rgba.len(), 128 * 128 * 4);
    }
}
