//! Хранение ключа Polza.AI. На компьютере — системное хранилище (Windows Credential Manager,
//! macOS Keychain), в базе и экспорте ключа нет. Для тестов — хранилище в памяти.

use anyhow::{anyhow, Result};
use std::sync::Mutex;

pub trait KeyStore: Send + Sync {
    fn read(&self) -> String;
    fn write(&self, key: &str) -> Result<()>;
}

const SERVICE: &str = "app.fastnotes.desktop";
const ACCOUNT: &str = "polza-api-key";

pub struct SystemKeys;

impl KeyStore for SystemKeys {
    fn read(&self) -> String {
        keyring::Entry::new(SERVICE, ACCOUNT).and_then(|e| e.get_password()).unwrap_or_default()
    }
    fn write(&self, key: &str) -> Result<()> {
        let entry = keyring::Entry::new(SERVICE, ACCOUNT).map_err(|e| anyhow!("Хранилище ключей недоступно: {}", e))?;
        if key.trim().is_empty() {
            match entry.delete_credential() {
                Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
                Err(e) => Err(anyhow!("Не удалось удалить ключ: {}", e)),
            }
        } else {
            entry.set_password(key.trim()).map_err(|e| anyhow!("Не удалось сохранить ключ: {}", e))
        }
    }
}

#[derive(Default)]
pub struct MemoryKeys(pub Mutex<String>);

impl KeyStore for MemoryKeys {
    fn read(&self) -> String {
        self.0.lock().unwrap().clone()
    }
    fn write(&self, key: &str) -> Result<()> {
        *self.0.lock().unwrap() = key.trim().to_string();
        Ok(())
    }
}
