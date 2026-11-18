//! Ядро «Мимоходом» для Windows и macOS: хранилище, правила, синхронизация с телефоном и
//! другими компьютерами. Без интерфейса: его подключает `src-tauri` (или отладочный сервер).

pub mod ai;
pub mod api;
pub mod app;
pub mod policy;
pub mod store;
pub mod sync;
