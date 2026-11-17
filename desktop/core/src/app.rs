//! Общее состояние приложения: база, папка данных, состояние синхронизации.

use crate::store::Store;
use anyhow::Result;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

pub struct Pending {
    pub secret: Vec<u8>,
    pub expires: i64,
    pub fails: u32,
}

#[derive(Default)]
pub struct SyncRuntime {
    pub pending: Mutex<Option<Pending>>,
    pub rids: Mutex<HashMap<String, i64>>,
    /// Порт работающего сервера (0 — не запущен).
    pub port: Mutex<u16>,
    pub server: Mutex<Option<Arc<tiny_http::Server>>>,
    pub status: Mutex<String>,
    pub busy: Mutex<bool>,
}

pub struct App {
    pub store: Mutex<Store>,
    pub dir: PathBuf,
    pub sync: SyncRuntime,
}

impl App {
    pub fn open(dir: &Path) -> Result<Arc<App>> {
        std::fs::create_dir_all(dir.join("images"))?;
        let store = Store::open(&dir.join("mimohodom.db"))?;
        Ok(Arc::new(App { store: Mutex::new(store), dir: dir.to_path_buf(), sync: SyncRuntime::default() }))
    }

    pub fn open_memory(dir: &Path) -> Result<Arc<App>> {
        std::fs::create_dir_all(dir.join("images"))?;
        let store = Store::open_memory()?;
        Ok(Arc::new(App { store: Mutex::new(store), dir: dir.to_path_buf(), sync: SyncRuntime::default() }))
    }

    pub fn images_dir(&self) -> PathBuf {
        self.dir.join("images")
    }

    pub fn image_path(&self, name: &str) -> Option<PathBuf> {
        if crate::policy::safe_file_name(name) {
            Some(self.images_dir().join(name))
        } else {
            None
        }
    }

    /// Картинки, на которые ссылаются заметки, но файлов нет.
    pub fn missing_images(&self) -> Vec<String> {
        let names = self.store.lock().unwrap().image_names().unwrap_or_default();
        let mut out: Vec<String> =
            names.into_iter().filter(|n| self.image_path(n).map(|p| !p.exists()).unwrap_or(false)).collect();
        out.sort();
        out
    }

    /// Удаляет файлы, на которые уже не ссылается ни одна заметка.
    pub fn cleanup_images(&self) {
        let used = self.store.lock().unwrap().image_names().unwrap_or_default();
        if let Ok(rd) = std::fs::read_dir(self.images_dir()) {
            for e in rd.flatten() {
                let name = e.file_name().to_string_lossy().to_string();
                if !used.contains(&name) {
                    let _ = std::fs::remove_file(e.path());
                }
            }
        }
    }

    pub fn set_status(&self, s: &str) {
        *self.sync.status.lock().unwrap() = s.to_string();
    }
}
