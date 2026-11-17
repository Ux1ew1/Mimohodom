//! Хранилище на компьютере: SQLite с теми же понятиями, что в Android `Store`
//! (заметки, папки, поиск), плюс служебные поля синхронизации (см. docs/SYNC.md).

use crate::policy;
use anyhow::{anyhow, Result};
use rand::RngCore;
use rusqlite::{params, Connection, OptionalExtension, Row};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

pub fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

pub fn random_hex(bytes: usize) -> String {
    let mut buf = vec![0u8; bytes];
    rand::thread_rng().fill_bytes(&mut buf);
    buf.iter().map(|b| format!("{:02x}", b)).collect()
}

/// Постоянный `uid` папки по её имени на момент создания: одноимённые папки сливаются.
pub fn folder_uid(key: &str) -> String {
    let mut h = Sha256::new();
    h.update(format!("folder:{}", key).as_bytes());
    let d = h.finalize();
    d[..16].iter().map(|b| format!("{:02x}", b)).collect()
}

#[derive(Serialize, Clone, Debug, Default, PartialEq)]
pub struct Note {
    pub id: i64,
    pub uid: String,
    pub title: String,
    pub body: String,
    pub created: i64,
    pub updated: i64,
    pub reminder: i64,
    pub repeat: i64,
    pub lead: i64,
    pub images: Vec<String>,
    pub folder: i64,
    pub title_auto: bool,
    pub ai_checked: bool,
    pub metadata: String,
    pub sync_ts: i64,
    pub sync_seq: i64,
}

#[derive(Serialize, Clone, Debug, Default, PartialEq)]
pub struct Folder {
    pub id: i64,
    pub uid: String,
    pub name: String,
    pub key: String,
    pub created: i64,
    pub auto: bool,
    pub notes: i64,
    pub sync_ts: i64,
    pub sync_seq: i64,
}

#[derive(Deserialize, Debug, Default)]
pub struct SaveInput {
    pub id: Option<i64>,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub body: String,
    #[serde(default)]
    pub reminder: i64,
    #[serde(default)]
    pub repeat: i64,
    #[serde(default = "minus_one")]
    pub lead: i64,
    pub images: Option<Vec<String>>,
    pub folder: Option<i64>,
}

fn minus_one() -> i64 {
    -1
}

#[derive(Serialize, Clone, Debug)]
pub struct Peer {
    pub device: String,
    pub name: String,
    pub platform: String,
    pub hosts: Vec<String>,
    pub sent_seq: i64,
    pub got_seq: i64,
    pub last_sync: i64,
    #[serde(skip)]
    pub secret: Vec<u8>,
}

pub struct Store {
    pub conn: Connection,
}

const NOTE_COLS: &str = "id,uid,title,body,created,updated,reminder,repeat_mode,lead_minutes,image,folder,title_auto,ai_checked,metadata,sync_ts,sync_seq";

fn read_note(r: &Row) -> rusqlite::Result<Note> {
    Ok(Note {
        id: r.get(0)?,
        uid: r.get(1)?,
        title: r.get(2)?,
        body: r.get(3)?,
        created: r.get(4)?,
        updated: r.get(5)?,
        reminder: r.get(6)?,
        repeat: r.get(7)?,
        lead: r.get(8)?,
        images: policy::image_parse(&r.get::<_, String>(9)?),
        folder: r.get(10)?,
        title_auto: r.get::<_, i64>(11)? == 1,
        ai_checked: r.get::<_, i64>(12)? != 0,
        metadata: r.get(13)?,
        sync_ts: r.get(14)?,
        sync_seq: r.get(15)?,
    })
}

const FOLDER_COLS: &str = "f.id,f.uid,f.name,f.key,f.created,f.auto,(SELECT count(*) FROM notes n WHERE n.folder=f.id),f.sync_ts,f.sync_seq";

fn read_folder(r: &Row) -> rusqlite::Result<Folder> {
    Ok(Folder {
        id: r.get(0)?,
        uid: r.get(1)?,
        name: r.get(2)?,
        key: r.get(3)?,
        created: r.get(4)?,
        auto: r.get::<_, i64>(5)? == 1,
        notes: r.get(6)?,
        sync_ts: r.get(7)?,
        sync_seq: r.get(8)?,
    })
}

impl Store {
    pub fn open(path: &Path) -> Result<Store> {
        let conn = Connection::open(path)?;
        conn.execute_batch("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;")?;
        let s = Store { conn };
        s.migrate()?;
        Ok(s)
    }

    pub fn open_memory() -> Result<Store> {
        let conn = Connection::open_in_memory()?;
        let s = Store { conn };
        s.migrate()?;
        Ok(s)
    }

    fn migrate(&self) -> Result<()> {
        let version: i64 = self.conn.query_row("PRAGMA user_version", [], |r| r.get(0))?;
        if version < 1 {
            self.conn.execute_batch(
                "CREATE TABLE notes(
                   id INTEGER PRIMARY KEY AUTOINCREMENT, uid TEXT NOT NULL UNIQUE,
                   title TEXT NOT NULL, body TEXT NOT NULL,
                   created INTEGER NOT NULL, updated INTEGER NOT NULL,
                   reminder INTEGER NOT NULL DEFAULT 0, repeat_mode INTEGER NOT NULL DEFAULT 0,
                   lead_minutes INTEGER NOT NULL DEFAULT -1, image TEXT NOT NULL DEFAULT '',
                   folder INTEGER NOT NULL DEFAULT 0, title_auto INTEGER NOT NULL DEFAULT 0,
                   ai_checked INTEGER NOT NULL DEFAULT 0, metadata TEXT NOT NULL DEFAULT '{}',
                   search_text TEXT NOT NULL DEFAULT '',
                   sync_ts INTEGER NOT NULL DEFAULT 0, sync_seq INTEGER NOT NULL DEFAULT 0,
                   sync_from TEXT NOT NULL DEFAULT '');
                 CREATE INDEX notes_updated ON notes(updated DESC);
                 CREATE INDEX notes_folder ON notes(folder, updated DESC);
                 CREATE INDEX notes_seq ON notes(sync_seq);
                 CREATE TABLE folders(
                   id INTEGER PRIMARY KEY AUTOINCREMENT, uid TEXT NOT NULL UNIQUE,
                   name TEXT NOT NULL, key TEXT NOT NULL UNIQUE, created INTEGER NOT NULL,
                   auto INTEGER NOT NULL DEFAULT 0,
                   sync_ts INTEGER NOT NULL DEFAULT 0, sync_seq INTEGER NOT NULL DEFAULT 0,
                   sync_from TEXT NOT NULL DEFAULT '');
                 CREATE TABLE tombstones(
                   kind TEXT NOT NULL, uid TEXT NOT NULL, ts INTEGER NOT NULL, seq INTEGER NOT NULL,
                   from_peer TEXT NOT NULL DEFAULT '', PRIMARY KEY(kind, uid));
                 CREATE INDEX tombstones_seq ON tombstones(seq);
                 CREATE TABLE sync_meta(
                   id INTEGER PRIMARY KEY CHECK(id=1), hlc INTEGER NOT NULL, seq INTEGER NOT NULL,
                   device TEXT NOT NULL, name TEXT NOT NULL);
                 CREATE TABLE peers(
                   device TEXT PRIMARY KEY, name TEXT NOT NULL, platform TEXT NOT NULL DEFAULT '',
                   secret BLOB NOT NULL, hosts TEXT NOT NULL DEFAULT '[]',
                   sent_seq INTEGER NOT NULL DEFAULT 0, got_seq INTEGER NOT NULL DEFAULT 0,
                   last_sync INTEGER NOT NULL DEFAULT 0);
                 CREATE TABLE settings(k TEXT PRIMARY KEY, v TEXT NOT NULL);
                 CREATE VIRTUAL TABLE notes_fts USING fts5(title, body, metadata, tokenize='unicode61');
                 PRAGMA user_version=1;",
            )?;
            self.conn.execute(
                "INSERT INTO sync_meta(id,hlc,seq,device,name) VALUES(1,?1,0,?2,?3)",
                params![now_ms(), random_hex(16), default_device_name()],
            )?;
        }
        Ok(())
    }

    // ---------- служебное ----------

    pub fn device(&self) -> Result<(String, String)> {
        Ok(self.conn.query_row("SELECT device,name FROM sync_meta WHERE id=1", [], |r| {
            Ok((r.get(0)?, r.get(1)?))
        })?)
    }

    pub fn set_device_name(&self, name: &str) -> Result<()> {
        let n = name.trim();
        if n.is_empty() {
            return Ok(());
        }
        self.conn.execute("UPDATE sync_meta SET name=?1 WHERE id=1", params![n])?;
        Ok(())
    }

    pub fn current_seq(&self) -> Result<i64> {
        Ok(self.conn.query_row("SELECT seq FROM sync_meta WHERE id=1", [], |r| r.get(0))?)
    }

    /// Новые (ts, seq) для локального изменения.
    pub fn stamp(&self) -> Result<(i64, i64)> {
        self.conn.execute(
            "UPDATE sync_meta SET seq=seq+1, hlc=max(hlc+1, ?1) WHERE id=1",
            params![now_ms()],
        )?;
        Ok(self.conn.query_row("SELECT hlc,seq FROM sync_meta WHERE id=1", [], |r| {
            Ok((r.get(0)?, r.get(1)?))
        })?)
    }

    /// Новый seq для записи, принятой от соседа; `ts` соседа запоминается в часах.
    pub fn stamp_remote(&self, remote_ts: i64) -> Result<i64> {
        self.conn.execute(
            "UPDATE sync_meta SET seq=seq+1, hlc=max(hlc, ?1) WHERE id=1",
            params![remote_ts],
        )?;
        Ok(self.current_seq()?)
    }

    pub fn setting(&self, key: &str) -> Option<String> {
        self.conn
            .query_row("SELECT v FROM settings WHERE k=?1", params![key], |r| r.get(0))
            .optional()
            .ok()
            .flatten()
    }

    pub fn set_setting(&self, key: &str, value: &str) -> Result<()> {
        self.conn.execute(
            "INSERT INTO settings(k,v) VALUES(?1,?2) ON CONFLICT(k) DO UPDATE SET v=excluded.v",
            params![key, value],
        )?;
        Ok(())
    }

    // ---------- индекс поиска ----------

    pub(crate) fn fts_put(&self, id: i64, title: &str, body: &str, search_text: &str) -> Result<()> {
        self.conn.execute("DELETE FROM notes_fts WHERE rowid=?1", params![id])?;
        self.conn.execute(
            "INSERT INTO notes_fts(rowid,title,body,metadata) VALUES(?1,?2,?3,?4)",
            params![id, title, body, search_text],
        )?;
        Ok(())
    }

    pub(crate) fn fts_del(&self, id: i64) -> Result<()> {
        self.conn.execute("DELETE FROM notes_fts WHERE rowid=?1", params![id])?;
        Ok(())
    }

    // ---------- заметки ----------

    pub fn note(&self, id: i64) -> Result<Option<Note>> {
        Ok(self
            .conn
            .query_row(&format!("SELECT {} FROM notes WHERE id=?1", NOTE_COLS), params![id], read_note)
            .optional()?)
    }

    pub fn note_by_uid(&self, uid: &str) -> Result<Option<Note>> {
        Ok(self
            .conn
            .query_row(&format!("SELECT {} FROM notes WHERE uid=?1", NOTE_COLS), params![uid], read_note)
            .optional()?)
    }

    /// Список: все заметки, по папке и/или по поиску. Новые правки сверху.
    pub fn notes(&self, query: &str, folder: Option<i64>) -> Result<Vec<Note>> {
        let mut out = Vec::new();
        let q = query.trim();
        if q.is_empty() {
            let (sql, p): (String, Vec<i64>) = match folder {
                Some(f) => (
                    format!("SELECT {} FROM notes WHERE folder=?1 ORDER BY updated DESC,id DESC", NOTE_COLS),
                    vec![f],
                ),
                None => (format!("SELECT {} FROM notes ORDER BY updated DESC,id DESC", NOTE_COLS), vec![]),
            };
            let mut st = self.conn.prepare(&sql)?;
            let rows = st.query_map(rusqlite::params_from_iter(p), read_note)?;
            for r in rows {
                out.push(r?);
            }
            return Ok(out);
        }
        let m = policy::search_query(q);
        if m.is_empty() {
            return Ok(out);
        }
        let mut sql = format!(
            "SELECT {} FROM notes WHERE id IN (SELECT rowid FROM notes_fts WHERE notes_fts MATCH ?1)",
            NOTE_COLS
        );
        if folder.is_some() {
            sql.push_str(" AND folder=?2");
        }
        sql.push_str(" ORDER BY updated DESC LIMIT 200");
        let mut st = self.conn.prepare(&sql)?;
        let rows = match folder {
            Some(f) => st.query_map(params![m, f], read_note)?.collect::<Vec<_>>(),
            None => st.query_map(params![m], read_note)?.collect::<Vec<_>>(),
        };
        for r in rows {
            out.push(r?);
        }
        Ok(out)
    }

    pub fn count_notes(&self) -> Result<i64> {
        Ok(self.conn.query_row("SELECT count(*) FROM notes", [], |r| r.get(0))?)
    }

    /// Сохраняет заметку (создаёт при `id == None`). Логика полей — порт Android `Store.save`.
    pub fn save_note(&self, input: &SaveInput) -> Result<Note> {
        let id = input.id.filter(|i| *i != 0);
        let old = match id {
            Some(i) => Some(self.note(i)?.ok_or_else(|| anyhow!("Заметка уже удалена"))?),
            None => None,
        };
        let mut now = now_ms();
        if let Some(o) = &old {
            now = now.max(o.updated + 1);
        }
        let title_auto = input.title.trim().is_empty()
            || old.as_ref().map(|o| o.title_auto && o.title == input.title.trim()).unwrap_or(false);
        let title = policy::auto_title(&input.title, &input.body);
        let body = input.body.trim().to_string();
        let created = old.as_ref().map(|o| o.created).unwrap_or(now);
        let meta = policy::metadata_base(&title, &body, created, now);
        let search = policy::search_text(&meta);
        let reminder = input.reminder.max(0);
        let repeat = if reminder == 0 { 0 } else { input.repeat };
        let lead = if reminder == 0 { -1 } else { input.lead };
        let images = match &input.images {
            Some(list) => {
                let mut v = list.clone();
                v.retain(|n| policy::safe_file_name(n));
                v.truncate(policy::MAX_IMAGES);
                policy::image_join(&v)
            }
            None => policy::image_join(&old.as_ref().map(|o| o.images.clone()).unwrap_or_default()),
        };
        let folder = match input.folder {
            Some(f) if f == 0 || self.folder_exists(f)? => f,
            _ => old.as_ref().map(|o| o.folder).unwrap_or(0),
        };
        let (ts, seq) = self.stamp()?;
        let tx = self.conn.unchecked_transaction()?;
        let row_id = match old {
            None => {
                let uid = random_hex(16);
                self.conn.execute(
                    "INSERT INTO notes(uid,title,body,created,updated,reminder,repeat_mode,lead_minutes,image,folder,title_auto,ai_checked,metadata,search_text,sync_ts,sync_seq,sync_from)
                     VALUES(?1,?2,?3,?4,?4,?5,?6,?7,?8,?9,?10,0,?11,?12,?13,?14,'')",
                    params![uid, title, body, now, reminder, repeat, lead, images, folder, title_auto as i64, meta.to_string(), search, ts, seq],
                )?;
                // Новая запись больше не нужна как надгробие (uid свежий, на всякий случай).
                self.conn.execute("DELETE FROM tombstones WHERE kind='note' AND uid=?1", params![uid])?;
                self.conn.last_insert_rowid()
            }
            Some(o) => {
                self.conn.execute(
                    "UPDATE notes SET title=?2,body=?3,updated=?4,reminder=?5,repeat_mode=?6,lead_minutes=?7,image=?8,folder=?9,title_auto=?10,metadata=?11,search_text=?12,sync_ts=?13,sync_seq=?14,sync_from='' WHERE id=?1",
                    params![o.id, title, body, now, reminder, repeat, lead, images, folder, title_auto as i64, meta.to_string(), search, ts, seq],
                )?;
                o.id
            }
        };
        self.fts_put(row_id, &title, &body, &search)?;
        tx.commit()?;
        self.note(row_id)?.ok_or_else(|| anyhow!("заметка не найдена после сохранения"))
    }

    /// Удаляет заметку и оставляет надгробие для синхронизации. Возвращает имена её картинок.
    pub fn delete_note(&self, id: i64) -> Result<Vec<String>> {
        let Some(n) = self.note(id)? else { return Ok(vec![]) };
        let (ts, seq) = self.stamp()?;
        let tx = self.conn.unchecked_transaction()?;
        self.conn.execute("DELETE FROM notes WHERE id=?1", params![id])?;
        self.fts_del(id)?;
        self.conn.execute(
            "INSERT OR REPLACE INTO tombstones(kind,uid,ts,seq,from_peer) VALUES('note',?1,?2,?3,'')",
            params![n.uid, ts, seq],
        )?;
        tx.commit()?;
        Ok(n.images)
    }

    /// Все имена картинок, на которые ссылаются заметки.
    pub fn image_names(&self) -> Result<std::collections::HashSet<String>> {
        let mut out = std::collections::HashSet::new();
        let mut st = self.conn.prepare("SELECT image FROM notes WHERE image<>''")?;
        let rows = st.query_map([], |r| r.get::<_, String>(0))?;
        for r in rows {
            for n in policy::image_parse(&r?) {
                out.insert(n);
            }
        }
        Ok(out)
    }

    // ---------- папки ----------

    pub fn folders(&self) -> Result<Vec<Folder>> {
        let mut st = self.conn.prepare(&format!("SELECT {} FROM folders f ORDER BY f.key", FOLDER_COLS))?;
        let rows = st.query_map([], read_folder)?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(out)
    }

    pub fn folder(&self, id: i64) -> Result<Option<Folder>> {
        Ok(self
            .conn
            .query_row(&format!("SELECT {} FROM folders f WHERE f.id=?1", FOLDER_COLS), params![id], read_folder)
            .optional()?)
    }

    pub fn folder_by_uid(&self, uid: &str) -> Result<Option<Folder>> {
        Ok(self
            .conn
            .query_row(&format!("SELECT {} FROM folders f WHERE f.uid=?1", FOLDER_COLS), params![uid], read_folder)
            .optional()?)
    }

    pub fn folder_exists(&self, id: i64) -> Result<bool> {
        Ok(id != 0 && self.folder(id)?.is_some())
    }

    /// Создаёт папку или возвращает существующую с тем же ключом. 0 — имя недопустимо.
    pub fn create_folder(&self, name: &str, auto: bool) -> Result<i64> {
        if !policy::folder_valid(name) {
            return Ok(0);
        }
        let key = policy::folder_key(name);
        if let Some(id) = self
            .conn
            .query_row("SELECT id FROM folders WHERE key=?1", params![key], |r| r.get::<_, i64>(0))
            .optional()?
        {
            return Ok(id);
        }
        let uid = folder_uid(&key);
        // Папка с этим uid могла быть удалена: надгробие снимаем, новая запись новее.
        let (ts, seq) = self.stamp()?;
        let tx = self.conn.unchecked_transaction()?;
        self.conn.execute("DELETE FROM tombstones WHERE kind='folder' AND uid=?1", params![uid])?;
        self.conn.execute(
            "INSERT INTO folders(uid,name,key,created,auto,sync_ts,sync_seq,sync_from) VALUES(?1,?2,?3,?4,?5,?6,?7,'')",
            params![uid, policy::folder_clean(name), key, now_ms(), auto as i64, ts, seq],
        )?;
        let id = self.conn.last_insert_rowid();
        tx.commit()?;
        Ok(id)
    }

    /// Переименовывает папку. false — имя недопустимо, занято или папки нет.
    pub fn rename_folder(&self, id: i64, name: &str) -> Result<bool> {
        if !policy::folder_valid(name) {
            return Ok(false);
        }
        let key = policy::folder_key(name);
        let taken: Option<i64> = self
            .conn
            .query_row("SELECT id FROM folders WHERE key=?1 AND id<>?2", params![key, id], |r| r.get(0))
            .optional()?;
        if taken.is_some() {
            return Ok(false);
        }
        let (ts, seq) = self.stamp()?;
        let n = self.conn.execute(
            "UPDATE folders SET name=?2,key=?3,sync_ts=?4,sync_seq=?5,sync_from='' WHERE id=?1",
            params![id, policy::folder_clean(name), key, ts, seq],
        )?;
        Ok(n == 1)
    }

    /// Удаляет папку; заметки возвращаются в «Все заметки» и получают новый ts.
    pub fn delete_folder(&self, id: i64) -> Result<()> {
        let Some(f) = self.folder(id)? else { return Ok(()) };
        let tx = self.conn.unchecked_transaction()?;
        let ids: Vec<i64> = {
            let mut st = self.conn.prepare("SELECT id FROM notes WHERE folder=?1")?;
            let rows = st.query_map(params![id], |r| r.get(0))?;
            rows.collect::<rusqlite::Result<Vec<_>>>()?
        };
        for nid in ids {
            let (ts, seq) = self.stamp()?;
            self.conn.execute(
                "UPDATE notes SET folder=0,sync_ts=?2,sync_seq=?3,sync_from='' WHERE id=?1",
                params![nid, ts, seq],
            )?;
        }
        let (ts, seq) = self.stamp()?;
        self.conn.execute("DELETE FROM folders WHERE id=?1", params![id])?;
        self.conn.execute(
            "INSERT OR REPLACE INTO tombstones(kind,uid,ts,seq,from_peer) VALUES('folder',?1,?2,?3,'')",
            params![f.uid, ts, seq],
        )?;
        tx.commit()?;
        Ok(())
    }

    /// Кладёт заметку в папку (0 — «Все заметки»). Время изменения заметки не трогает.
    pub fn move_note(&self, note_id: i64, folder: i64) -> Result<()> {
        if folder != 0 && !self.folder_exists(folder)? {
            return Ok(());
        }
        let (ts, seq) = self.stamp()?;
        self.conn.execute(
            "UPDATE notes SET folder=?2,sync_ts=?3,sync_seq=?4,sync_from='' WHERE id=?1",
            params![note_id, folder, ts, seq],
        )?;
        Ok(())
    }

    // ---------- соседи ----------

    pub fn peers(&self) -> Result<Vec<Peer>> {
        let mut st = self.conn.prepare(
            "SELECT device,name,platform,secret,hosts,sent_seq,got_seq,last_sync FROM peers ORDER BY name",
        )?;
        let rows = st.query_map([], read_peer)?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(out)
    }

    pub fn peer(&self, device: &str) -> Result<Option<Peer>> {
        Ok(self
            .conn
            .query_row(
                "SELECT device,name,platform,secret,hosts,sent_seq,got_seq,last_sync FROM peers WHERE device=?1",
                params![device],
                read_peer,
            )
            .optional()?)
    }

    pub fn save_peer(&self, p: &Peer) -> Result<()> {
        self.conn.execute(
            "INSERT INTO peers(device,name,platform,secret,hosts,sent_seq,got_seq,last_sync) VALUES(?1,?2,?3,?4,?5,?6,?7,?8)
             ON CONFLICT(device) DO UPDATE SET name=excluded.name,platform=excluded.platform,secret=excluded.secret,hosts=excluded.hosts",
            params![p.device, p.name, p.platform, p.secret, serde_json::to_string(&p.hosts)?, p.sent_seq, p.got_seq, p.last_sync],
        )?;
        Ok(())
    }

    pub fn update_peer_progress(&self, device: &str, sent: Option<i64>, got: Option<i64>, when: Option<i64>) -> Result<()> {
        if let Some(s) = sent {
            self.conn.execute("UPDATE peers SET sent_seq=?2 WHERE device=?1", params![device, s])?;
        }
        if let Some(g) = got {
            self.conn.execute("UPDATE peers SET got_seq=?2 WHERE device=?1", params![device, g])?;
        }
        if let Some(w) = when {
            self.conn.execute("UPDATE peers SET last_sync=?2 WHERE device=?1", params![device, w])?;
        }
        Ok(())
    }

    pub fn set_peer_hosts(&self, device: &str, hosts: &[String]) -> Result<()> {
        self.conn.execute(
            "UPDATE peers SET hosts=?2 WHERE device=?1",
            params![device, serde_json::to_string(hosts)?],
        )?;
        Ok(())
    }

    pub fn delete_peer(&self, device: &str) -> Result<()> {
        self.conn.execute("DELETE FROM peers WHERE device=?1", params![device])?;
        Ok(())
    }
}

fn read_peer(r: &Row) -> rusqlite::Result<Peer> {
    let hosts: String = r.get(4)?;
    Ok(Peer {
        device: r.get(0)?,
        name: r.get(1)?,
        platform: r.get(2)?,
        secret: r.get(3)?,
        hosts: serde_json::from_str(&hosts).unwrap_or_default(),
        sent_seq: r.get(5)?,
        got_seq: r.get(6)?,
        last_sync: r.get(7)?,
    })
}

pub fn default_device_name() -> String {
    let host = std::env::var("COMPUTERNAME").or_else(|_| std::env::var("HOSTNAME")).unwrap_or_default();
    let os = if cfg!(target_os = "macos") {
        "Mac"
    } else if cfg!(target_os = "windows") {
        "Windows"
    } else {
        "Компьютер"
    };
    if host.trim().is_empty() {
        os.to_string()
    } else {
        format!("{} ({})", host.trim(), os)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input(title: &str, body: &str) -> SaveInput {
        SaveInput { id: None, title: title.into(), body: body.into(), reminder: 0, repeat: 0, lead: -1, images: None, folder: None }
    }

    #[test]
    fn save_search_delete() {
        let s = Store::open_memory().unwrap();
        let a = s.save_note(&input("", "Купить молоко и хлеб\nещё что-то")).unwrap();
        assert_eq!(a.title, "Купить молоко и хлеб");
        assert!(a.title_auto);
        assert_eq!(a.uid.len(), 32);
        let b = s.save_note(&input("Рецепт борща", "свёкла, капуста")).unwrap();
        assert_eq!(s.notes("", None).unwrap().len(), 2);
        let found = s.notes("молоко", None).unwrap();
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].id, a.id);
        assert_eq!(s.notes("борщ", None).unwrap().len(), 1);
        // редактирование обновляет индекс
        let mut edit = input("Рецепт борща", "теперь про картошку");
        edit.id = Some(b.id);
        let b2 = s.save_note(&edit).unwrap();
        assert!(b2.updated > b.updated);
        assert!(b2.sync_seq > b.sync_seq);
        assert_eq!(s.notes("картошка", None).unwrap().len(), 1);
        assert_eq!(s.notes("свёкла", None).unwrap().len(), 0);
        s.delete_note(a.id).unwrap();
        assert_eq!(s.notes("молоко", None).unwrap().len(), 0);
        let t: i64 = s.conn.query_row("SELECT count(*) FROM tombstones WHERE kind='note'", [], |r| r.get(0)).unwrap();
        assert_eq!(t, 1);
    }

    #[test]
    fn folders_flow() {
        let s = Store::open_memory().unwrap();
        let f = s.create_folder("  Работа ", false).unwrap();
        assert!(f > 0);
        assert_eq!(s.create_folder("работа", false).unwrap(), f);
        assert_eq!(s.create_folder("Все заметки", false).unwrap(), 0);
        let g = s.create_folder("Дом", false).unwrap();
        assert!(!s.rename_folder(g, "РАБОТА").unwrap());
        assert!(s.rename_folder(g, "Семья").unwrap());
        let n = s.save_note(&input("а", "б")).unwrap();
        s.move_note(n.id, f).unwrap();
        assert_eq!(s.notes("", Some(f)).unwrap().len(), 1);
        assert_eq!(s.folders().unwrap().iter().find(|x| x.id == f).unwrap().notes, 1);
        let before = s.note(n.id).unwrap().unwrap();
        s.delete_folder(f).unwrap();
        let after = s.note(n.id).unwrap().unwrap();
        assert_eq!(after.folder, 0);
        assert_eq!(after.updated, before.updated);
        assert!(after.sync_seq > before.sync_seq);
    }

    #[test]
    fn reminder_options() {
        let s = Store::open_memory().unwrap();
        let mut i = input("т", "т");
        i.reminder = 1_900_000_000_000;
        i.repeat = 2;
        i.lead = 15;
        let n = s.save_note(&i).unwrap();
        assert_eq!((n.repeat, n.lead), (2, 15));
        let mut j = input("т", "т");
        j.id = Some(n.id);
        let n2 = s.save_note(&j).unwrap();
        assert_eq!((n2.reminder, n2.repeat, n2.lead), (0, 0, -1));
    }
}
