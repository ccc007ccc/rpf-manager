//! A scan exercises each preview decoder. Containers and unsupported files are
//! counted explicitly; extension recognition alone is never a successful parse.
use super::*;
use std::{
    collections::BTreeMap,
    panic::{catch_unwind, AssertUnwindSafe},
};

pub fn scan_progress(handle: i64) -> Result<Value> {
    let a = get(handle)?;
    let state = a.scan_state.lock().unwrap_or_else(|e| e.into_inner());
    Ok(json!({"visited":state.0,"path":state.1}))
}
pub fn scan(handle: i64) -> Result<Value> {
    let root = get(handle)?;
    *root.scan_state.lock().unwrap_or_else(|e| e.into_inner()) = (0, String::new());
    let mut rows = Vec::new();
    walk(handle, "", 0, &root, &mut rows)?;
    let finished = !root.cancelled.load(Ordering::Relaxed)
        && rows.len() < 100_000
        && !rows
            .iter()
            .any(|r| r["detail"] == "Nested archive depth limit reached");
    let unopened_archives = rows
        .iter()
        .filter(|r| r["extension"] == "rpf" && r["status"] != "container")
        .count();
    let complete = finished && unopened_archives == 0;
    let mut formats: BTreeMap<String, BTreeMap<String, u64>> = BTreeMap::new();
    let mut counts: BTreeMap<String, u64> = BTreeMap::new();
    for row in &rows {
        let ext = row["extension"].as_str().unwrap_or("").to_owned();
        let status = row["status"].as_str().unwrap().to_owned();
        *counts.entry(status.clone()).or_default() += 1;
        *formats.entry(ext).or_default().entry(status).or_default() += 1;
    }
    let formats: Vec<Value> = formats
        .into_iter()
        .map(|(extension, counts)| json!({"extension":extension,"counts":counts}))
        .collect();
    Ok(
        json!({"schema":2,"name":root.parsed.name,"finished":finished,"complete":complete,
        "unopenedArchives":unopened_archives,"total":rows.len(),
        "counts":counts,"formats":formats,"rows":rows}),
    )
}
fn walk(
    handle: i64,
    prefix: &str,
    depth: usize,
    root: &Archive,
    rows: &mut Vec<Value>,
) -> Result<()> {
    let a = get(handle)?;
    let mut entries = editor::snapshot(&a);
    entries.sort_by(|a, b| a.path.cmp(&b.path));
    for entry in entries.iter().filter(|e| !e.directory) {
        if root.cancelled.load(Ordering::Relaxed) || rows.len() >= 100_000 {
            break;
        }
        let path = format!("{prefix}{}", entry.path);
        let ext = entry
            .name
            .rsplit_once('.')
            .map_or("", |(_, e)| e)
            .to_ascii_lowercase();
        *root.scan_state.lock().unwrap_or_else(|e| e.into_inner()) =
            (rows.len() as u64, path.clone());
        let mut child = None;
        let result = catch_unwind(AssertUnwindSafe(|| -> Result<(String, String)> {
            if ext == "rpf" {
                ensure!(depth < 24, "Nested archive depth limit reached");
                let h = open_nested(handle, &entry.path)?;
                child = Some(h);
                return Ok(("container".into(), "Archive index parsed".into()));
            }
            if !matches!(
                ext.as_str(),
                "ydr"
                    | "ydd"
                    | "yft"
                    | "ymap"
                    | "ybn"
                    | "ytd"
                    | "dds"
                    | "awc"
                    | "png"
                    | "jpg"
                    | "jpeg"
                    | "webp"
                    | "bmp"
                    | "txt"
                    | "xml"
                    | "meta"
                    | "json"
                    | "csv"
                    | "ini"
                    | "dat"
                    | "ide"
                    | "ipl"
                    | "cfg"
                    | "sps"
                    | "list"
                    | "ugc"
            ) && entry.size > 2 * 1024 * 1024
            {
                return Ok(("unsupported".into(), "No preview decoder".into()));
            }
            if entry.size > PREVIEW_LIMIT as u64 {
                return Ok((
                    "unsupported".into(),
                    "File exceeds the 256 MiB preview limit".into(),
                ));
            }
            let bytes = read_entry(&a, entry)?;
            decode(&bytes, &ext)
        }));
        let (status, detail) = match result {
            Ok(Ok(v)) => v,
            Ok(Err(e)) => {
                let message = format!("{e:#}");
                (error_status(&message).into(), message)
            }
            Err(_) => (
                "failed".into(),
                "Decoder rejected malformed resource".into(),
            ),
        };
        rows.push(
            json!({"path":path,"extension":ext,"size":entry.size,"status":status,"detail":detail}),
        );
        if let Some(h) = child {
            let result = walk(h, &format!("{path}!/"), depth + 1, root, rows);
            close(h);
            result?;
        }
    }
    *root.scan_state.lock().unwrap_or_else(|e| e.into_inner()) = (rows.len() as u64, String::new());
    Ok(())
}
fn decode(bytes: &[u8], ext: &str) -> Result<(String, String)> {
    let preview = |detail: String| Ok(("preview".into(), detail));
    match ext {
        "ydr" | "ydd" | "yft" | "ymap" | "ybn" => {
            let mesh = crate::mesh::parse(bytes, ext)?;
            if ext == "ymap" && mesh.parts == 0 {
                return Ok((
                    "partial".into(),
                    "Map metadata parsed; no entities to display; other map data is not visualized"
                        .into(),
                ));
            }
            let status = if mesh.flags & 3 != 0 {
                "partial"
            } else {
                "preview"
            };
            Ok((
                status.into(),
                format!(
                    "{} vertices; {} triangles; {} parts; {}",
                    mesh.vertices.len() / crate::mesh::STRIDE,
                    mesh.indices.len() / 3,
                    mesh.parts,
                    if mesh.flags & 1 != 0 {
                        "entity markers; external models are not loaded"
                    } else if mesh.flags & 2 != 0 {
                        "some bounds are approximated"
                    } else {
                        "static geometry"
                    }
                ),
            ))
        }
        "ytd" | "dds" => {
            #[cfg(feature = "conversion")]
            let results = conversion::texture_results(bytes, ext)?;
            #[cfg(not(feature = "conversion"))]
            let results = decode_textures(bytes, ext)?
                .iter()
                .map(|texture| rpf_archive::texture_utils::decompress_texture(texture).map(|_| ()))
                .collect::<Vec<_>>();
            ensure!(!results.is_empty(), "Empty texture dictionary");
            let count = results.len();
            let mut good = 0;
            let mut errors = Vec::new();
            for (index, result) in results.into_iter().enumerate() {
                match result {
                    Ok(_) => good += 1,
                    Err(e) => errors.push(format!("Texture {index}: {e}")),
                }
            }
            Ok((
                if good == count {
                    "preview"
                } else if good > 0 {
                    "partial"
                } else if errors.iter().all(|e| error_status(e) == "unsupported") {
                    "unsupported"
                } else {
                    "failed"
                }
                .into(),
                format!("{good}/{} textures decoded. {}", count, errors.join("; ")),
            ))
        }
        "awc" => {
            let audio = crate::audio::Awc::parse(bytes)?;
            let mut good = 0;
            let mut failed = 0;
            let mut errors = Vec::new();
            for t in &audio.tracks {
                if !t.reason.is_empty() {
                    errors.push(format!("{:08X}: {}", t.id, t.reason));
                    continue;
                }
                match t.wav(&mut std::io::sink()) {
                    Ok(()) => good += 1,
                    Err(e) => {
                        failed += 1;
                        errors.push(format!("{:08X}: {e}", t.id));
                    }
                }
            }
            Ok((
                if good == audio.tracks.len() {
                    "preview"
                } else if good > 0 {
                    "partial"
                } else if failed > 0 {
                    "failed"
                } else {
                    "unsupported"
                }
                .into(),
                format!(
                    "{good}/{} tracks decoded. {}",
                    audio.tracks.len(),
                    errors.join("; ")
                ),
            ))
        }
        "png" | "jpg" | "jpeg" | "webp" | "bmp" => {
            let reader =
                image::ImageReader::new(std::io::Cursor::new(bytes)).with_guessed_format()?;
            let (w, h) = reader.into_dimensions()?;
            ensure!(
                w as u64 * h as u64 <= 16_777_216,
                "Image exceeds preview limit"
            );
            image::load_from_memory(bytes)?;
            preview(format!("{w} × {h} image decoded"))
        }
        _ => {
            if bytes.len() > 2 * 1024 * 1024 {
                return Ok((
                    "unsupported".into(),
                    "Text exceeds 2 MiB preview limit".into(),
                ));
            }
            let Ok(text) = std::str::from_utf8(bytes) else {
                return Ok((
                    "unsupported".into(),
                    "Binary data or unsupported text encoding".into(),
                ));
            };
            if text
                .chars()
                .any(|c| c.is_control() && !matches!(c, '\r' | '\n' | '\t'))
            {
                return Ok(("unsupported".into(), "Binary data, not plain text".into()));
            }
            preview(format!(
                "UTF-8 text, {} bytes{}",
                bytes.len(),
                if bytes.len() > 65536 {
                    "; preview shows first 64 KiB"
                } else {
                    ""
                }
            ))
        }
    }
}

fn error_status(message: &str) -> &'static str {
    let message = message.to_ascii_lowercase();
    if message.contains("unsupported")
        || message.contains("not supported")
        || message.contains("limit")
        || message.contains("no supported geometry")
    {
        "unsupported"
    } else {
        "failed"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cancellation_before_scan_is_not_lost() {
        let f = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(f.path(), crate::fixtures::archive()).unwrap();
        let h = open(f.path().to_str().unwrap(), "demo.rpf").unwrap();
        cancel(h);
        let report = scan(h).unwrap();
        assert_eq!(report["complete"], false);
        assert_eq!(report["finished"], false);
        assert_eq!(report["total"], 0);
        close(h);
    }
    #[test]
    fn scan_exercises_decoders_and_separates_containers() {
        let f = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(f.path(), crate::fixtures::archive()).unwrap();
        let h = open(f.path().to_str().unwrap(), "demo.rpf").unwrap();
        let report = scan(h).unwrap();
        assert_eq!(report["complete"], true);
        assert_eq!(report["finished"], true);
        assert_eq!(report["unopenedArchives"], 0);
        assert_eq!(report["counts"]["container"], 1);
        assert_eq!(report["counts"]["partial"], 2);
        assert_eq!(report["counts"]["preview"], 7);
        assert!(report["counts"].get("failed").is_none());
        close(h);
        assert!(decode(b"not a resource", "ydr").is_err());
    }
    #[test]
    fn unreadable_nested_archive_keeps_coverage_incomplete() {
        let mut builder = rpf_archive::RpfBuilder::new(rpf_archive::RpfEncryption::Open);
        builder.add_file("broken.rpf", vec![0; 64]);
        builder.add_file("broken.ymap", vec![0; 64]);
        builder.add_file("empty.ymap", crate::fixtures::empty_map());
        let file = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(file.path(), builder.build(None).unwrap()).unwrap();
        let handle = open(file.path().to_str().unwrap(), "coverage.rpf").unwrap();
        let report = scan(handle).unwrap();
        assert_eq!(report["finished"], true);
        assert_eq!(report["complete"], false);
        assert_eq!(report["unopenedArchives"], 1);
        assert_eq!(report["counts"]["failed"], 2);
        assert_eq!(report["counts"]["partial"], 1);
        close(handle);
    }
}
