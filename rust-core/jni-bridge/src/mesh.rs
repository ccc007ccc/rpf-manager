//! Static PC RSC7 geometry. Layout references are recorded in THIRD_PARTY.md.
use anyhow::{bail, ensure, Context, Result};
use rpf_archive::resource::{prepare_rsc7, u16_le, u32_le, u64_le, ResReader};
use std::collections::HashSet;
type V3 = [f32; 3];
type Mat = [f32; 16];
const IDENTITY: Mat = [
    1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1.,
];
const MAX_VERTICES: usize = 1_000_000;
pub const STRIDE: usize = 12;
const COLORS: [V3; 5] = [
    [0.55, 0.72, 0.82],
    [0.82, 0.66, 0.43],
    [0.36, 0.45, 0.52],
    [0.66, 0.78, 0.76],
    [0.78, 0.80, 0.84],
];

#[derive(Default)]
pub struct Mesh {
    pub vertices: Vec<f32>,
    pub indices: Vec<u32>,
    pub parts: u32,
    pub flags: u32,
}
impl Mesh {
    pub fn encode(&self) -> Vec<u8> {
        let mut b = b"RPFM".to_vec();
        for n in [
            2,
            (self.vertices.len() / STRIDE) as u32,
            self.indices.len() as u32,
            self.parts,
            self.flags,
        ] {
            b.extend(n.to_le_bytes());
        }
        let (mut lo, mut hi) = ([f32::INFINITY; 3], [f32::NEG_INFINITY; 3]);
        for v in self.vertices.chunks_exact(STRIDE) {
            for k in 0..3 {
                lo[k] = lo[k].min(v[k]);
                hi[k] = hi[k].max(v[k]);
            }
        }
        if self.vertices.is_empty() {
            lo = [0.; 3];
            hi = [0.; 3];
        }
        for v in lo
            .into_iter()
            .chain(hi)
            .chain(self.vertices.iter().copied())
        {
            b.extend(v.to_le_bytes());
        }
        for i in &self.indices {
            b.extend(i.to_le_bytes());
        }
        b
    }
    pub fn append(&mut self, positions: &[V3], indices: &[u32], color: V3) -> Result<()> {
        ensure!(
            self.vertices.len() / STRIDE + positions.len() <= MAX_VERTICES
                && self.indices.len() + indices.len() <= 6_000_000,
            "Mesh exceeds preview geometry limit"
        );
        ensure!(
            positions
                .iter()
                .flatten()
                .all(|x| x.is_finite() && x.abs() < 1e8),
            "Invalid vertex coordinates"
        );
        ensure!(
            indices.len() % 3 == 0 && indices.iter().all(|i| (*i as usize) < positions.len()),
            "Invalid triangle indices"
        );
        let mut normals = vec![[0.; 3]; positions.len()];
        for tri in indices.chunks_exact(3) {
            let a = positions[tri[0] as usize];
            let b = positions[tri[1] as usize];
            let c = positions[tri[2] as usize];
            let n = cross(sub(b, a), sub(c, a));
            for i in tri {
                for k in 0..3 {
                    normals[*i as usize][k] += n[k];
                }
            }
        }
        let offset = (self.vertices.len() / STRIDE) as u32;
        for (p, n) in positions.iter().zip(normals) {
            self.vertices.extend(p);
            self.vertices.extend(normalize(n));
            self.vertices.extend(color);
            self.vertices.extend([0., 0., 0.]); // UV0 and whether this vertex has UVs.
        }
        self.indices.extend(indices.iter().map(|i| i + offset));
        self.parts += 1;
        Ok(())
    }
}
fn sub(a: V3, b: V3) -> V3 {
    [a[0] - b[0], a[1] - b[1], a[2] - b[2]]
}
fn cross(a: V3, b: V3) -> V3 {
    [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]
}
fn normalize(v: V3) -> V3 {
    let l = v.iter().map(|x| x * x).sum::<f32>().sqrt().max(0.00001);
    [v[0] / l, v[1] / l, v[2] / l]
}
fn f32_at(b: &[u8], o: usize) -> f32 {
    f32::from_le_bytes(b[o..o + 4].try_into().unwrap())
}
fn v3(b: &[u8], o: usize) -> V3 {
    [f32_at(b, o), f32_at(b, o + 4), f32_at(b, o + 8)]
}
fn transform(p: V3, m: &Mat) -> V3 {
    std::array::from_fn(|k| p[0] * m[k] + p[1] * m[4 + k] + p[2] * m[8 + k] + m[12 + k])
}
fn mul(a: &Mat, b: &Mat) -> Mat {
    std::array::from_fn(|i| {
        (0..4)
            .map(|k| a[(i % 4) + k * 4] * b[(i / 4) * 4 + k])
            .sum()
    })
}

pub fn parse(data: &[u8], ext: &str) -> Result<Mesh> {
    let (system, graphics) = prepare_rsc7(data)?;
    let r = ResReader {
        system: &system,
        graphics: &graphics,
    };
    let mut m = Mesh::default();
    match ext {
        "ydr" => drawable(&r, 0x50000000, &mut m)?,
        "yft" => {
            let root = r.resolve(0x50000000, 0x38).context("Truncated fragment")?;
            drawable(&r, u64_le(root, 0x30), &mut m)?;
        }
        "ydd" => {
            let root = r
                .resolve(0x50000000, 0x40)
                .context("Truncated drawable dictionary")?;
            let count = u16_le(root, 0x38) as usize;
            ensure!(count <= 4096, "Too many drawables");
            let array = r
                .resolve(u64_le(root, 0x30), count * 8)
                .context("Invalid drawable dictionary")?;
            for i in 0..count {
                let ptr = u64_le(array, i * 8);
                if ptr != 0 {
                    drawable(&r, ptr, &mut m)?;
                }
            }
        }
        "ybn" => bound(&r, 0x50000000, &IDENTITY, &mut HashSet::new(), 0, &mut m)?,
        "ymap" => map(&r, &mut m)?,
        _ => bail!("No mesh decoder for this format"),
    }
    ensure!(
        !m.indices.is_empty() || (ext == "ymap" && m.parts == 0),
        "Resource contains no supported geometry"
    );
    Ok(m)
}
fn drawable(r: &ResReader, ptr: u64, m: &mut Mesh) -> Result<()> {
    let d = rpf_archive::ydd::parse_drawable(ptr, r)?;
    for model in d.models {
        for geo in model.geometries {
            let v = geo.vertex_buffer;
            let ib = geo.index_buffer;
            ensure!(
                v.stride >= 12 && v.count as usize <= MAX_VERTICES,
                "Unsupported vertex layout"
            );
            if let Some((flags, types)) = v.declaration {
                ensure!(
                    flags & 1 != 0 && matches!(types & 15, 6 | 7),
                    "Unsupported position declaration"
                );
            }
            let uv = v
                .declaration
                .map(|(flags, types)| uv_layout(flags, types, v.stride as usize))
                .transpose()?
                .flatten();
            let points: Vec<V3> = v
                .data
                .chunks_exact(v.stride as usize)
                .map(|b| v3(b, 0))
                .collect();
            let width = ib.data.len().checked_div(ib.count as usize).unwrap_or(0);
            let indices = match width {
                2 => ib
                    .data
                    .chunks_exact(2)
                    .map(|v| u16_le(v, 0) as u32)
                    .collect::<Vec<_>>(),
                4 => ib
                    .data
                    .chunks_exact(4)
                    .map(|v| u32_le(v, 0))
                    .collect::<Vec<_>>(),
                _ => bail!("Unsupported index format"),
            };
            let start = m.vertices.len();
            m.append(&points, &indices, COLORS[m.parts as usize % COLORS.len()])?;
            if let Some((offset, kind)) = uv {
                for (raw, vertex) in v
                    .data
                    .chunks_exact(v.stride as usize)
                    .zip(m.vertices[start..].chunks_exact_mut(STRIDE))
                {
                    let coords = if kind == 1 {
                        [half(u16_le(raw, offset)), half(u16_le(raw, offset + 2))]
                    } else {
                        [f32_at(raw, offset), f32_at(raw, offset + 4)]
                    };
                    ensure!(
                        coords.iter().all(|x| x.is_finite()),
                        "Invalid texture coordinates"
                    );
                    vertex[9..11].copy_from_slice(&coords);
                    vertex[11] = 1.;
                }
                m.flags |= 4;
            } else {
                m.flags |= 8;
            }
        }
    }
    Ok(())
}

fn uv_layout(flags: u32, types: u64, stride: usize) -> Result<Option<(usize, u8)>> {
    if flags & (1 << 6) == 0 {
        return Ok(None);
    }
    let sizes = [0, 4, 4, 8, 0, 8, 12, 16, 4, 4, 4, 0, 0, 0, 0, 0];
    let mut offset = 0;
    for i in 0..6 {
        if flags & (1 << i) != 0 {
            let n = sizes[((types >> (i * 4)) & 15) as usize];
            ensure!(n > 0, "Unsupported vertex component before UV0");
            offset += n;
        }
    }
    let kind = ((types >> 24) & 15) as u8;
    if !matches!(kind, 1 | 5) {
        return Ok(None);
    }
    ensure!(
        offset + sizes[kind as usize] <= stride,
        "UV0 exceeds vertex stride"
    );
    Ok(Some((offset, kind)))
}
fn half(v: u16) -> f32 {
    let sign = if v & 0x8000 != 0 { -1. } else { 1. };
    let exp = (v >> 10) & 31;
    let frac = (v & 1023) as f32;
    sign * match exp {
        0 => frac * 2f32.powi(-24),
        31 => {
            if frac == 0. {
                f32::INFINITY
            } else {
                f32::NAN
            }
        }
        _ => (1. + frac / 1024.) * 2f32.powi(exp as i32 - 15),
    }
}

pub fn box_geometry(lo: V3, hi: V3) -> (Vec<V3>, Vec<u32>) {
    // Separate face vertices preserve crisp collision and mechanical edges.
    let corners: Vec<V3> = (0..8)
        .map(|i| std::array::from_fn(|k| if i & (1 << k) == 0 { lo[k] } else { hi[k] }))
        .collect();
    let faces = [
        [0, 2, 3, 1],
        [4, 5, 7, 6],
        [0, 1, 5, 4],
        [2, 6, 7, 3],
        [0, 4, 6, 2],
        [1, 3, 7, 5],
    ];
    let mut p = Vec::new();
    let mut ix = Vec::new();
    for f in faces {
        let base = p.len() as u32;
        p.extend(f.map(|i| corners[i]));
        ix.extend([0, 1, 2, 0, 2, 3].map(|i| base + i));
    }
    (p, ix)
}
fn box_mesh(lo: V3, hi: V3, t: &Mat, m: &mut Mesh, color: V3) -> Result<()> {
    ensure!((0..3).all(|k| lo[k] <= hi[k]), "Invalid bounds");
    let (mut p, ix) = box_geometry(lo, hi);
    for v in &mut p {
        *v = transform(*v, t);
    }
    m.append(&p, &ix, color)
}
fn sphere(center: V3, radius: f32, t: &Mat, m: &mut Mesh) -> Result<()> {
    ensure!(radius.is_finite() && radius > 0., "Invalid sphere radius");
    let mut points = Vec::new();
    let mut indices = Vec::new();
    for y in 0..=12 {
        for x in 0..=24 {
            let a = y as f32 * std::f32::consts::PI / 12.;
            let b = x as f32 * std::f32::consts::TAU / 24.;
            let p = [
                center[0] + radius * a.sin() * b.cos(),
                center[1] + radius * a.sin() * b.sin(),
                center[2] + radius * a.cos(),
            ];
            points.push(transform(p, t));
        }
    }
    for y in 0..12 {
        for x in 0..24 {
            let i = y * 25 + x;
            indices.extend([i, i + 1, i + 25, i + 1, i + 26, i + 25]);
        }
    }
    m.append(&points, &indices, [0.89, 0.65, 0.38])
}
fn bound(
    r: &ResReader,
    ptr: u64,
    t: &Mat,
    seen: &mut HashSet<u64>,
    depth: usize,
    m: &mut Mesh,
) -> Result<()> {
    ensure!(depth < 64 && seen.insert(ptr), "Cyclic collision hierarchy");
    let b = r.resolve(ptr, 0x70).context("Truncated collision bound")?;
    let kind = b[0x10];
    let lo = v3(b, 0x30);
    let hi = v3(b, 0x20);
    match kind {
        10 => {
            let b = r.resolve(ptr, 0xB0).context("Truncated composite")?;
            let n = u16_le(b, 0xA0) as usize;
            ensure!(n <= 8192, "Too many collision children");
            if n > 0 {
                let children = r
                    .resolve(u64_le(b, 0x70), n * 8)
                    .context("Invalid collision children")?;
                let transforms = r.resolve(u64_le(b, 0x78), n * 64);
                for i in 0..n {
                    let p = u64_le(children, i * 8);
                    if p == 0 {
                        continue;
                    }
                    let local: Mat = transforms
                        .map(|v| std::array::from_fn(|k| f32_at(v, i * 64 + k * 4)))
                        .unwrap_or(IDENTITY);
                    bound(r, p, &mul(t, &local), seen, depth + 1, m)?;
                }
            }
        }
        4 | 8 => {
            let g = r
                .resolve(ptr, 0xF0)
                .context("Truncated collision geometry")?;
            let nv = u32_le(g, 0xD0) as usize;
            let np = u32_le(g, 0xD4) as usize;
            ensure!(
                nv <= MAX_VERTICES && np <= 2_000_000,
                "Collision exceeds geometry limit"
            );
            let v = r
                .resolve(u64_le(g, 0xB0), nv * 6)
                .context("Invalid collision vertices")?;
            let p = r
                .resolve(u64_le(g, 0x88), np * 16)
                .context("Invalid collision polygons")?;
            let quantum = v3(g, 0x90);
            let center = v3(g, 0xA0);
            let points: Vec<V3> = v
                .chunks_exact(6)
                .map(|v| {
                    transform(
                        std::array::from_fn(|k| {
                            i16::from_le_bytes([v[k * 2], v[k * 2 + 1]]) as f32 * quantum[k]
                                + center[k]
                        }),
                        t,
                    )
                })
                .collect();
            let mut ix = Vec::new();
            let mut simplified = false;
            for poly in p.chunks_exact(16) {
                if poly[0] & 7 == 0 {
                    ix.extend([4, 6, 8].map(|o| (u16_le(poly, o) & 0x7fff) as u32));
                } else {
                    simplified = true;
                }
            }
            if !ix.is_empty() {
                m.append(&points, &ix, [0.83, 0.63, 0.40])?;
            }
            if simplified {
                m.flags |= 2;
                box_mesh(lo, hi, t, m, [0.60, 0.49, 0.34])?;
            }
        }
        0 => sphere(v3(b, 0x40), f32_at(b, 0x14), t, m)?,
        3 => box_mesh(lo, hi, t, m, [0.89, 0.65, 0.38])?,
        1 | 12 | 13 | 15 => {
            m.flags |= 2;
            box_mesh(lo, hi, t, m, [0.70, 0.53, 0.37])?;
        }
        _ => bail!("Unsupported collision bound type {kind}"),
    }
    seen.remove(&ptr);
    Ok(())
}
fn map(r: &ResReader, m: &mut Mesh) -> Result<()> {
    let header = r
        .resolve(0x50000000, 112)
        .context("Truncated map metadata")?;
    let count = u16_le(header, 76) as usize;
    ensure!(count > 0 && count <= 4096, "Invalid metadata block count");
    let array = r
        .resolve(u64_le(header, 48), count * 16)
        .context("Invalid metadata block table")?;
    let mut blocks = Vec::new();
    for b in array.chunks_exact(16) {
        blocks.push((
            u32_le(b, 0),
            r.resolve(u64_le(b, 8), u32_le(b, 4) as usize)
                .context("Invalid metadata block")?,
        ));
    }
    let root = blocks
        .iter()
        .find(|(hash, _)| *hash == rpf_archive::rage_joaat("CMapData"))
        .context("CMapData block not found")?
        .1;
    ensure!(root.len() >= 112, "Truncated CMapData");
    let entity_count = u16_le(root, 104) as usize;
    m.flags |= 1;
    // A valid CMapData can contain only occlusion or streaming metadata. Its
    // empty entity list has no pointer to resolve and no geometry to upload.
    if entity_count == 0 {
        return Ok(());
    }
    fn resolve<'a>(blocks: &[(u32, &'a [u8])], p: u64, n: usize) -> Result<(u32, &'a [u8])> {
        let id = (p & 0xFFF) as usize;
        let off = ((p >> 12) & 0xFFFFF) as usize;
        let (hash, data) = blocks
            .get(id.checked_sub(1).context("Null metadata pointer")?)
            .context("Invalid metadata pointer")?;
        Ok((
            *hash,
            data.get(off..off.checked_add(n).context("Metadata range overflow")?)
                .context("Truncated metadata value")?,
        ))
    }
    let pointers = resolve(&blocks, u64_le(root, 96), entity_count * 8)?.1;
    for p in pointers.chunks_exact(8) {
        let (hash, e) = resolve(&blocks, u64_le(p, 0), 128)?;
        ensure!(
            hash == rpf_archive::rage_joaat("CEntityDef")
                || hash == rpf_archive::rage_joaat("CMloInstanceDef"),
            "Unsupported map entity definition"
        );
        let position = v3(e, 32);
        let q: [f32; 4] = std::array::from_fn(|k| f32_at(e, 48 + k * 4));
        ensure!(q.iter().all(|x| x.is_finite()), "Invalid entity rotation");
        let scale = [f32_at(e, 64), f32_at(e, 64), f32_at(e, 68)];
        let (mut points, ix) = box_geometry([-0.5, -0.5, 0.], [0.5, 0.5, 1.]);
        let len = q.iter().map(|v| v * v).sum::<f32>().sqrt();
        ensure!(len > 0.00001, "Invalid entity quaternion");
        let xyz = [-q[0] / len, -q[1] / len, -q[2] / len];
        let w = q[3] / len;
        for p in &mut points {
            for k in 0..3 {
                p[k] *= scale[k];
            }
            let a = cross(xyz, *p);
            let b = cross(xyz, a);
            for k in 0..3 {
                p[k] += 2. * (w * a[k] + b[k]) + position[k];
            }
        }
        let hash_color = (u32_le(e, 8) % 3) as usize;
        m.append(
            &points,
            &ix,
            [[0.48, 0.72, 0.63], [0.68, 0.79, 0.71], [0.37, 0.57, 0.53]][hash_color],
        )?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn uv_offsets_follow_declarations_and_half_values() {
        assert_eq!(
            uv_layout(89, 0x7755555555996996, 36).unwrap(),
            Some((28, 5))
        );
        assert_eq!(
            uv_layout(89, 0x0300000001996006, 32).unwrap(),
            Some((28, 1))
        );
        assert!(uv_layout(89, 0x7755555555996996, 24).is_err());
        assert_eq!(half(0x3800), 0.5);
        assert_eq!(half(0xbc00), -1.);
    }
    #[test]
    fn invalid_indices_and_nan_are_rejected() {
        assert!(Mesh::default()
            .append(&[[0., 0., 0.]], &[0, 1, 2], [1.; 3])
            .is_err());
        assert!(Mesh::default()
            .append(&[[f32::NAN, 0., 0.]], &[0, 0, 0], [1.; 3])
            .is_err());
    }
    #[test]
    fn truncated_resource_does_not_panic() {
        for n in 0..128 {
            assert!(parse(&vec![0; n], "ydr").is_err());
        }
    }
}
