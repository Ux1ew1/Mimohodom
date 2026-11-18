//! Общее состояние приложения: база, папка данных, состояние синхронизации и ИИ-чата.

use crate::ai::assistant::AssistState;
use crate::ai::chat::ChatState;
use crate::ai::keys::{KeyStore, MemoryKeys, SystemKeys};
use crate::ai::video_run::VideoState;
use crate::store::Store;
use anyhow::Result;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime};

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
    /// Ключ Polza.AI: системное хранилище или память (тесты, отладочный сервер).
    pub keys: Box<dyn KeyStore>,
    pub chat: Mutex<ChatState>,
    /// Состояние фонового помощника: проверка, выжимка, вопрос.
    pub assist: Mutex<AssistState>,
    /// Состояние сводок видео YouTube: идёт ли запрос и результат, который ещё не забрал интерфейс.
    pub video: Mutex<VideoState>,
    /// Адрес API Polza.AI; в тестах — локальная заглушка.
    pub ai_base: Mutex<String>,
    /// Уведомления о напоминаниях, которые оболочка ещё не показала.
    pub notices: Mutex<Vec<crate::reminders::Notice>>,
}

/// Файл картинки моложе этого срока не удаляется, даже если на него ещё никто не ссылается:
/// так переживают уборку вложения несохранённой заметки и неотправленного сообщения.
const IMAGE_GRACE: Duration = Duration::from_secs(6 * 3600);

impl App {
    fn build(dir: &Path, store: Store, keys: Box<dyn KeyStore>) -> Arc<App> {
        Arc::new(App {
            store: Mutex::new(store),
            dir: dir.to_path_buf(),
            sync: SyncRuntime::default(),
            keys,
            chat: Mutex::new(ChatState::default()),
            assist: Mutex::new(AssistState::default()),
            video: Mutex::new(VideoState::default()),
            ai_base: Mutex::new(crate::ai::polza::DEFAULT_BASE.to_string()),
            notices: Mutex::new(Vec::new()),
        })
    }

    pub fn open(dir: &Path) -> Result<Arc<App>> {
        std::fs::create_dir_all(dir.join("images"))?;
        let store = Store::open(&dir.join("mimohodom.db"))?;
        Ok(Self::build(dir, store, Box::new(SystemKeys)))
    }

    /// База в файле, но ключ ИИ только в памяти: отладочный сервер не трогает системное хранилище.
    pub fn open_dev(dir: &Path) -> Result<Arc<App>> {
        std::fs::create_dir_all(dir.join("images"))?;
        let store = Store::open(&dir.join("mimohodom.db"))?;
        Ok(Self::build(dir, store, Box::new(MemoryKeys::default())))
    }

    pub fn open_memory(dir: &Path) -> Result<Arc<App>> {
        std::fs::create_dir_all(dir.join("images"))?;
        let store = Store::open_memory()?;
        Ok(Self::build(dir, store, Box::new(MemoryKeys::default())))
    }

    pub fn ai_base(&self) -> String {
        self.ai_base.lock().unwrap().clone()
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

    /// Картинки, на которые ссылаются заметки и сообщения чатов, но файлов нет.
    pub fn missing_images(&self) -> Vec<String> {
        let names = {
            let st = self.store.lock().unwrap();
            let mut n = st.image_names().unwrap_or_default();
            n.extend(st.chat_image_names().unwrap_or_default());
            n
        };
        let mut out: Vec<String> =
            names.into_iter().filter(|n| self.image_path(n).map(|p| !p.exists()).unwrap_or(false)).collect();
        out.sort();
        out
    }

    /// Удаляет старые файлы, на которые уже не ссылается ни одна заметка и ни одно сообщение чата.
    pub fn cleanup_images(&self) {
        let used = {
            let st = self.store.lock().unwrap();
            let mut u = st.image_names().unwrap_or_default();
            match st.chat_image_names() {
                Ok(c) => u.extend(c),
                // Не смогли прочитать чаты — ничего не удаляем.
                Err(_) => return,
            }
            u
        };
        let now = SystemTime::now();
        if let Ok(rd) = std::fs::read_dir(self.images_dir()) {
            for e in rd.flatten() {
                let name = e.file_name().to_string_lossy().to_string();
                if used.contains(&name) {
                    continue;
                }
                let young = e
                    .metadata()
                    .and_then(|m| m.modified())
                    .map(|t| now.duration_since(t).map(|age| age < IMAGE_GRACE).unwrap_or(true))
                    .unwrap_or(true);
                if !young {
                    let _ = std::fs::remove_file(e.path());
                }
            }
        }
    }

    pub fn set_status(&self, s: &str) {
        *self.sync.status.lock().unwrap() = s.to_string();
    }
}
