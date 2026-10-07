//! Synthetic data only: no game keys, tables or encrypted game files.
use rpf_archive::{GtaKeys, RpfArchive, RpfBuilder, RpfEncryption};
use rpf_archive::crypto::{decrypt_ng_with_key, cipher::get_ng_key_idx};

fn test_keys() -> GtaKeys {
    let mut tables = vec![[[0u32; 256]; 16]; 17];
    for round in &mut tables {
        for (lane, table) in round.iter_mut().enumerate() {
            for (value, entry) in table.iter_mut().enumerate() {
                *entry = (value as u32) << ((lane % 4) * 8);
            }
        }
    }
    GtaKeys {
        aes_key: [0x5a; 32],
        ng_keys: vec![vec![0x5a; 272]; 101],
        ng_decrypt_tables: tables.into_boxed_slice().try_into().unwrap(),
    }
}

#[test]
fn aes_directory_requires_user_keys_and_preserves_resources() {
    let keys = test_keys();
    let mut builder = RpfBuilder::new(RpfEncryption::Aes);
    builder.add_file("folder/example.txt", b"user resource".to_vec());
    let bytes = builder.build(Some(&keys)).unwrap();
    let missing = RpfArchive::parse(&bytes, "example.rpf", None).err().unwrap();
    assert!(missing.to_string().contains("requires GTA V keys"));
    let archive = RpfArchive::parse(&bytes, "example.rpf", Some(&keys)).unwrap();
    let entry = archive.entries.iter().find(|e| e.name == "example.txt").unwrap();
    assert_eq!(archive.extract_entry(&bytes, entry, Some(&keys)).unwrap(), b"user resource");
}

#[test]
fn ng_directory_retries_imported_keys_for_nonstandard_nested_names() {
    let mut builder = RpfBuilder::new(RpfEncryption::Open);
    builder.add_file("folder/example.txt", b"nested resource".to_vec());
    let mut bytes = builder.build(None).unwrap();
    let names_start = 16 + u32::from_le_bytes(bytes[4..8].try_into().unwrap()) as usize * 16;
    let names_end = names_start + u32::from_le_bytes(bytes[8..12].try_into().unwrap()) as usize;
    let ordinary = get_ng_key_idx("nonstandard.rpf", bytes.len() as u32);
    let alternate = (ordinary + 1) % 101;
    let mut keys = test_keys();
    keys.ng_keys[alternate].fill(0);
    // With these synthetic identity tables and zero subkeys, the decoder is a
    // byte permutation whose square is identity. This constructs test ciphertext.
    for range in [16..names_start, names_start..names_end] {
        let plain = bytes[range.clone()].to_vec();
        let encrypted = decrypt_ng_with_key(&plain, &keys, alternate).unwrap();
        assert_eq!(decrypt_ng_with_key(&encrypted, &keys, alternate).unwrap(), plain);
        bytes[range].copy_from_slice(&encrypted);
    }
    bytes[12..16].copy_from_slice(&RpfEncryption::Ng.as_u32().to_le_bytes());
    assert!(RpfArchive::parse(&bytes, "nonstandard.rpf", None).is_err());
    let archive = RpfArchive::parse(&bytes, "nonstandard.rpf", Some(&keys)).unwrap();
    let entry = archive.entries.iter().find(|e| e.name == "example.txt").unwrap();
    assert_eq!(archive.extract_entry(&bytes, entry, Some(&keys)).unwrap(), b"nested resource");
    assert!(decrypt_ng_with_key(b"payload", &keys, 101).is_err());
    keys.ng_keys[alternate].truncate(16);
    assert!(decrypt_ng_with_key(b"payload", &keys, alternate).is_err());
}
