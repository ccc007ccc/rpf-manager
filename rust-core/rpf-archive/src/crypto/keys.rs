use anyhow::{Context, Result, bail};
use std::{fs, path::Path};

/// GTA V cryptographic keys needed for RPF decryption.
pub struct GtaKeys {
    pub aes_key: [u8; 32],
    pub ng_keys: Vec<Vec<u8>>,
    pub ng_decrypt_tables: Box<[[[u32; 256]; 16]; 17]>,
}

impl GtaKeys {
    /// Load pre-extracted keys from a directory.
    /// Expected files: gtav_aes_key.dat, gtav_ng_key.dat, gtav_ng_decrypt_tables.dat
    pub fn load_from_path(path: &Path) -> Result<Self> {
        let aes_bytes = fs::read(path.join("gtav_aes_key.dat"))
            .context("Missing gtav_aes_key.dat")?;
        if aes_bytes.len() != 32 {
            bail!("gtav_aes_key.dat has wrong size (expected 32 bytes)");
        }
        let aes_key: [u8; 32] = aes_bytes.try_into().unwrap();

        let ng_key_bytes = fs::read(path.join("gtav_ng_key.dat"))
            .context("Missing gtav_ng_key.dat")?;
        let ng_keys = read_ng_keys(&ng_key_bytes)?;

        let ng_table_bytes = fs::read(path.join("gtav_ng_decrypt_tables.dat"))
            .context("Missing gtav_ng_decrypt_tables.dat")?;
        let ng_decrypt_tables = read_ng_tables(&ng_table_bytes)?;

        Ok(Self { aes_key, ng_keys, ng_decrypt_tables })
    }

    pub fn save_to_path(&self, path: &Path) -> Result<()> {
        fs::create_dir_all(path)?;
        fs::write(path.join("gtav_aes_key.dat"), &self.aes_key)?;
        fs::write(path.join("gtav_ng_key.dat"), write_ng_keys(&self.ng_keys))?;
        fs::write(path.join("gtav_ng_decrypt_tables.dat"), write_ng_tables(&self.ng_decrypt_tables))?;
        eprintln!("[Keys] Saved to {}", path.display());
        Ok(())
    }
}

fn read_ng_keys(data: &[u8]) -> Result<Vec<Vec<u8>>> {
    const KEY_SIZE: usize = 272;
    const KEY_COUNT: usize = 101;
    if data.len() < KEY_SIZE * KEY_COUNT {
        bail!("NG key data too small: {} bytes", data.len());
    }
    Ok((0..KEY_COUNT)
        .map(|i| data[i * KEY_SIZE..(i + 1) * KEY_SIZE].to_vec())
        .collect())
}

fn write_ng_keys(keys: &[Vec<u8>]) -> Vec<u8> {
    keys.iter().flat_map(|k| k.iter().cloned()).collect()
}

fn read_ng_tables(data: &[u8]) -> Result<Box<[[[u32; 256]; 16]; 17]>> {
    const EXPECTED: usize = 17 * 16 * 1024;
    if data.len() < EXPECTED {
        bail!("NG table data too small: {} bytes (expected {})", data.len(), EXPECTED);
    }
    let mut tables = vec![[[0u32; 256]; 16]; 17];
    let mut offset = 0;
    for i in 0..17 {
        for j in 0..16 {
            for k in 0..256 {
                tables[i][j][k] = u32::from_le_bytes([
                    data[offset],
                    data[offset + 1],
                    data[offset + 2],
                    data[offset + 3],
                ]);
                offset += 4;
            }
        }
    }
    let arr: [[[u32; 256]; 16]; 17] = tables
        .try_into()
        .map_err(|_| anyhow::anyhow!("Failed to convert ng tables to fixed array"))?;
    Ok(Box::new(arr))
}

fn write_ng_tables(tables: &[[[u32; 256]; 16]; 17]) -> Vec<u8> {
    let mut out = Vec::with_capacity(17 * 16 * 1024);
    for i in 0..17 {
        for j in 0..16 {
            for k in 0..256 {
                out.extend_from_slice(&tables[i][j][k].to_le_bytes());
            }
        }
    }
    out
}
