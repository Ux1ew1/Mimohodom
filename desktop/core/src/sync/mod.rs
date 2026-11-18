//! Синхронизация по локальной сети. Протокол — docs/SYNC.md.

pub mod chats;
pub mod client;
pub mod crypto;
pub mod engine;
pub mod pairing;
pub mod server;

pub const HTTP_PORT: u16 = 47616;
pub const DISCOVERY_PORT: u16 = 47615;
pub const MAX_BODY: usize = 64 * 1024 * 1024;
