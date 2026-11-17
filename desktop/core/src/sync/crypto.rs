//! Шифрование обмена: AES-256-GCM, ключ из общего секрета через HKDF-SHA256 (docs/SYNC.md).

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use hkdf::Hkdf;
use rand::RngCore;
use sha2::Sha256;

pub const INFO: &[u8] = b"mimohodom-sync-v1";

pub fn derive_key(secret: &[u8]) -> [u8; 32] {
    let hk = Hkdf::<Sha256>::new(None, secret);
    let mut key = [0u8; 32];
    hk.expand(INFO, &mut key).expect("32 байта допустимы для HKDF-SHA256");
    key
}

/// `nonce(12) || шифртекст || тег(16)`.
pub fn seal(key: &[u8; 32], aad: &[u8], plain: &[u8]) -> Vec<u8> {
    let cipher = Aes256Gcm::new_from_slice(key).expect("ключ 32 байта");
    let mut nonce = [0u8; 12];
    rand::thread_rng().fill_bytes(&mut nonce);
    let ct = cipher
        .encrypt(Nonce::from_slice(&nonce), Payload { msg: plain, aad })
        .expect("шифрование AES-GCM не должно падать");
    let mut out = nonce.to_vec();
    out.extend_from_slice(&ct);
    out
}

pub fn open(key: &[u8; 32], aad: &[u8], data: &[u8]) -> Option<Vec<u8>> {
    if data.len() < 12 + 16 {
        return None;
    }
    let cipher = Aes256Gcm::new_from_slice(key).ok()?;
    cipher.decrypt(Nonce::from_slice(&data[..12]), Payload { msg: &data[12..], aad }).ok()
}

pub fn request_aad(device: &str) -> Vec<u8> {
    format!("req|{}", device).into_bytes()
}

pub fn response_aad(device: &str) -> Vec<u8> {
    format!("res|{}", device).into_bytes()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_and_tamper() {
        let key = derive_key(b"0123456789abcdef");
        let sealed = seal(&key, b"req|dev", "привет".as_bytes());
        assert_eq!(open(&key, b"req|dev", &sealed).unwrap(), "привет".as_bytes());
        assert!(open(&key, b"req|other", &sealed).is_none());
        let mut bad = sealed.clone();
        let last = bad.len() - 1;
        bad[last] ^= 1;
        assert!(open(&key, b"req|dev", &bad).is_none());
        assert!(open(&derive_key(b"another-secret!!"), b"req|dev", &sealed).is_none());
    }

    /// Вектор для сверки с Java-клиентом (SyncCrypto): ключ из секрета 00..0f.
    #[test]
    fn key_vector() {
        let secret: Vec<u8> = (0u8..16).collect();
        let key = derive_key(&secret);
        let hex: String = key.iter().map(|b| format!("{:02x}", b)).collect();
        assert_eq!(hex.len(), 64);
        // Значение зафиксировано: если изменится, протокол несовместим с Android.
        assert_eq!(hex, KEY_VECTOR);
    }

    const KEY_VECTOR: &str = "b7815271cae38b78371dc3ddc532319727add8cbf4b89dde9691e10b7ec929e9";
}
