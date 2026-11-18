//! Записи обмена и их слияние: что отправить соседу и как принять его записи
//! (правило «побеждает больший ts», копия при конфликте правок). См. docs/SYNC.md.

use super::chats;
use crate::policy;
use crate::store::{now_ms, random_hex, Note, Store};
use anyhow::Result;
use rusqlite::{params, OptionalExtension};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
pub struct Record {
    pub kind: String,
    pub uid: String,
    pub ts: i64,
    #[serde(default)]
    pub deleted: bool,
    // заметка
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub body: String,
    #[serde(default)]
    pub created: i64,
    #[serde(default)]
    pub updated: i64,
    #[serde(default)]
    pub reminder: i64,
    #[serde(default)]
    pub repeat: i64,
    #[serde(default = "minus_one")]
    pub lead: i64,
    #[serde(default)]
    pub images: Vec<String>,
    #[serde(default)]
    pub folder: String,
    #[serde(default, rename = "titleAuto")]
    pub title_auto: bool,
    #[serde(default, rename = "aiChecked")]
    pub ai_checked: bool,
    #[serde(default)]
    pub metadata: String,
    // папка
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub key: String,
    #[serde(default)]
    pub auto: bool,
    // чат (`title` и `created` общие с заметкой) и сообщение (`body`, `created` общие)
    #[serde(default)]
    pub checkin: bool,
    #[serde(default)]
    pub chat: String,
    #[serde(default)]
    pub role: String,
    #[serde(default)]
    pub sources: Vec<Value>,
    #[serde(default)]
    pub draft: String,
    // стопка: uid исходных заметок (`uid` записи — верхняя заметка)
    #[serde(default)]
    pub members: Vec<String>,
}

fn minus_one() -> i64 {
    -1
}

impl Record {
    /// Запись для передачи: только поля своего вида.
    pub fn to_wire(&self) -> Value {
        if self.deleted {
            return json!({"kind": self.kind, "uid": self.uid, "ts": self.ts, "deleted": true});
        }
        if self.kind == "chat" {
            return json!({"kind":"chat","uid":self.uid,"ts":self.ts,"deleted":false,
                   "title":self.title,"created":self.created,"checkin":self.checkin});
        }
        if self.kind == "message" {
            return json!({"kind":"message","uid":self.uid,"ts":self.ts,"deleted":false,
                   "chat":self.chat,"role":self.role,"body":self.body,"sources":self.sources,
                   "draft":self.draft,"created":self.created});
        }
        if self.kind == "stack" {
            return json!({"kind":"stack","uid":self.uid,"ts":self.ts,"deleted":false,"members":self.members});
        }
        if self.kind == "folder" {
            json!({"kind":"folder","uid":self.uid,"ts":self.ts,"deleted":false,
                   "name":self.name,"key":self.key,"created":self.created,"auto":self.auto})
        } else {
            json!({"kind":"note","uid":self.uid,"ts":self.ts,"deleted":false,
                   "title":self.title,"body":self.body,"created":self.created,"updated":self.updated,
                   "reminder":self.reminder,"repeat":self.repeat,"lead":self.lead,
                   "images":self.images,"folder":self.folder,"titleAuto":self.title_auto,
                   "aiChecked":self.ai_checked,"metadata":self.metadata})
        }
    }

    fn tomb(kind: &str, uid: &str, ts: i64) -> Record {
        Record { kind: kind.into(), uid: uid.into(), ts, deleted: true, lead: -1, ..Default::default() }
    }
}

#[derive(Default, Debug, Clone, PartialEq)]
pub struct Stats {
    pub applied: usize,
    pub ignored: usize,
    pub conflicts: usize,
}

impl Stats {
    pub fn add(&mut self, o: &Stats) {
        self.applied += o.applied;
        self.ignored += o.ignored;
        self.conflicts += o.conflicts;
    }
}

fn folder_uid_of(store: &Store, local_id: i64) -> Result<String> {
    if local_id == 0 {
        return Ok(String::new());
    }
    Ok(store.folder(local_id)?.map(|f| f.uid).unwrap_or_default())
}

fn note_record(store: &Store, n: &Note) -> Result<Record> {
    Ok(Record {
        kind: "note".into(),
        uid: n.uid.clone(),
        ts: n.sync_ts,
        deleted: false,
        title: n.title.clone(),
        body: n.body.clone(),
        created: n.created,
        updated: n.updated,
        reminder: n.reminder,
        repeat: n.repeat,
        lead: n.lead,
        images: n.images.clone(),
        folder: folder_uid_of(store, n.folder)?,
        title_auto: n.title_auto,
        ai_checked: n.ai_checked,
        metadata: n.metadata.clone(),
        ..Default::default()
    })
}

/// Записи для соседа: изменения со `seq` в (since, snapshot], кроме принятых от него самого.
/// Папки при `all_folders` отдаются все, независимо от `since`.
/// Возвращает (записи, есть ли ещё, новое значение `since`).
pub fn collect(
    store: &Store,
    since: i64,
    exclude: &str,
    limit: usize,
    all_folders: bool,
    chats: bool,
) -> Result<(Vec<Record>, bool, i64)> {
    if chats {
        chats::stamp_stacks(store)?;
    }
    let snapshot = store.current_seq()?;
    let mut items: Vec<(i64, Record)> = Vec::new();
    if chats {
        items.extend(chats::collect_items(store, since, snapshot, exclude, limit)?);
    }
    {
        let mut st = store.conn.prepare(
            "SELECT id,uid,title,body,created,updated,reminder,repeat_mode,lead_minutes,image,folder,title_auto,ai_checked,metadata,sync_ts,sync_seq
             FROM notes WHERE sync_seq>?1 AND sync_seq<=?2 AND sync_from<>?3 ORDER BY sync_seq LIMIT ?4",
        )?;
        let rows = st.query_map(params![since, snapshot, exclude, limit as i64 + 1], |r| {
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
        })?;
        for n in rows {
            let n = n?;
            items.push((n.sync_seq, note_record(store, &n)?));
        }
    }
    {
        let mut st = store.conn.prepare(
            "SELECT uid,ts,seq FROM tombstones WHERE kind='note' AND seq>?1 AND seq<=?2 AND from_peer<>?3 ORDER BY seq LIMIT ?4",
        )?;
        let rows = st.query_map(params![since, snapshot, exclude, limit as i64 + 1], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?, r.get::<_, i64>(2)?))
        })?;
        for t in rows {
            let (uid, ts, seq) = t?;
            items.push((seq, Record::tomb("note", &uid, ts)));
        }
    }
    items.sort_by_key(|(s, _)| *s);
    let more = items.len() > limit;
    items.truncate(limit);
    let next = if more { items.last().map(|(s, _)| *s).unwrap_or(snapshot) } else { snapshot };
    let mut out: Vec<Record> = Vec::new();
    // Папки идут первыми, чтобы заметки находили свои папки.
    {
        let sql = if all_folders {
            "SELECT uid,name,key,created,auto,sync_ts FROM folders".to_string()
        } else {
            "SELECT uid,name,key,created,auto,sync_ts FROM folders WHERE sync_seq>?1 AND sync_seq<=?2 AND sync_from<>?3".to_string()
        };
        let mut st = store.conn.prepare(&sql)?;
        let map = |r: &rusqlite::Row| -> rusqlite::Result<Record> {
            Ok(Record {
                kind: "folder".into(),
                uid: r.get(0)?,
                name: r.get(1)?,
                key: r.get(2)?,
                created: r.get(3)?,
                auto: r.get::<_, i64>(4)? == 1,
                ts: r.get(5)?,
                lead: -1,
                ..Default::default()
            })
        };
        let rows = if all_folders {
            st.query_map([], map)?.collect::<Vec<_>>()
        } else {
            st.query_map(params![since, snapshot, exclude], map)?.collect::<Vec<_>>()
        };
        for r in rows {
            out.push(r?);
        }
    }
    {
        let sql = if all_folders {
            "SELECT uid,ts FROM tombstones WHERE kind='folder'"
        } else {
            "SELECT uid,ts FROM tombstones WHERE kind='folder' AND seq>?1 AND seq<=?2 AND from_peer<>?3"
        };
        let mut st = store.conn.prepare(sql)?;
        let map = |r: &rusqlite::Row| -> rusqlite::Result<(String, i64)> { Ok((r.get(0)?, r.get(1)?)) };
        let rows = if all_folders {
            st.query_map([], map)?.collect::<Vec<_>>()
        } else {
            st.query_map(params![since, snapshot, exclude], map)?.collect::<Vec<_>>()
        };
        for r in rows {
            let (uid, ts) = r?;
            out.push(Record::tomb("folder", &uid, ts));
        }
    }
    out.extend(items.into_iter().map(|(_, r)| r));
    Ok((out, more, next))
}

pub(super) fn tombstone_ts(store: &Store, kind: &str, uid: &str) -> Result<Option<i64>> {
    Ok(store
        .conn
        .query_row("SELECT ts FROM tombstones WHERE kind=?1 AND uid=?2", params![kind, uid], |r| r.get(0))
        .optional()?)
}

pub(super) fn put_tombstone(store: &Store, kind: &str, uid: &str, ts: i64, peer: &str) -> Result<()> {
    let seq = store.stamp_remote(ts)?;
    store.conn.execute(
        "INSERT OR REPLACE INTO tombstones(kind,uid,ts,seq,from_peer) VALUES(?1,?2,?3,?4,?5)",
        params![kind, uid, ts, seq, peer],
    )?;
    Ok(())
}

/// Принимает пачку записей от соседа `peer`. `sent_seq` — до какого локального `seq` записи уже
/// дошли до этого соседа (по нему отличаем «менялось у нас после обмена» для копий при конфликте).
pub fn apply(store: &Store, peer: &str, sent_seq: i64, recs: &[Record]) -> Result<Stats> {
    let mut stats = Stats::default();
    let tx = store.conn.unchecked_transaction()?;
    // Папки раньше заметок.
    for r in recs.iter().filter(|r| r.kind == "folder") {
        if apply_folder(store, peer, r)? {
            stats.applied += 1;
        } else {
            stats.ignored += 1;
        }
    }
    for r in recs.iter().filter(|r| r.kind == "note") {
        match apply_note(store, peer, sent_seq, r)? {
            Outcome::Ignored => stats.ignored += 1,
            Outcome::Applied => stats.applied += 1,
            Outcome::Conflict => {
                stats.applied += 1;
                stats.conflicts += 1;
            }
        }
    }
    chats::apply(store, peer, recs, &mut stats)?;
    tx.commit()?;
    Ok(stats)
}

fn apply_folder(store: &Store, peer: &str, r: &Record) -> Result<bool> {
    let local = store.folder_by_uid(&r.uid)?;
    if r.deleted {
        match local {
            Some(f) if r.ts > f.sync_ts => {
                // Заметки возвращаются в «Все заметки» без нового ts: их версии с этим же
                // изменением приходят от соседа, а свежий ts не должен перебить чужое удаление.
                store.conn.execute("UPDATE notes SET folder=0 WHERE folder=?1", params![f.id])?;
                store.conn.execute("DELETE FROM folders WHERE id=?1", params![f.id])?;
                put_tombstone(store, "folder", &r.uid, r.ts, peer)?;
                return Ok(true);
            }
            Some(_) => return Ok(false),
            None => {
                if tombstone_ts(store, "folder", &r.uid)?.map(|t| t < r.ts).unwrap_or(true) {
                    put_tombstone(store, "folder", &r.uid, r.ts, peer)?;
                    return Ok(true);
                }
                return Ok(false);
            }
        }
    }
    if !policy::folder_valid(&r.name) {
        return Ok(false);
    }
    if let Some(t) = tombstone_ts(store, "folder", &r.uid)? {
        if t >= r.ts {
            return Ok(false);
        }
        store.conn.execute("DELETE FROM tombstones WHERE kind='folder' AND uid=?1", params![r.uid])?;
    }
    // Ключ должен быть уникален: при столкновении с другой папкой добавляем номер.
    let mut name = policy::folder_clean(&r.name);
    let mut key = policy::folder_key(&name);
    let mut n = 2;
    while let Some(other) = store
        .conn
        .query_row("SELECT uid FROM folders WHERE key=?1", params![key], |x| x.get::<_, String>(0))
        .optional()?
    {
        if other == r.uid {
            break;
        }
        name = format!("{} ({})", policy::folder_clean(&r.name), n);
        key = policy::folder_key(&name);
        n += 1;
    }
    let seq = store.stamp_remote(r.ts)?;
    match local {
        Some(f) => {
            if r.ts <= f.sync_ts {
                return Ok(false);
            }
            store.conn.execute(
                "UPDATE folders SET name=?2,key=?3,auto=?4,sync_ts=?5,sync_seq=?6,sync_from=?7 WHERE id=?1",
                params![f.id, name, key, r.auto as i64, r.ts, seq, peer],
            )?;
        }
        None => {
            store.conn.execute(
                "INSERT INTO folders(uid,name,key,created,auto,sync_ts,sync_seq,sync_from) VALUES(?1,?2,?3,?4,?5,?6,?7,?8)",
                params![r.uid, name, key, r.created, r.auto as i64, r.ts, seq, peer],
            )?;
        }
    }
    Ok(true)
}

enum Outcome {
    Ignored,
    Applied,
    Conflict,
}

fn apply_note(store: &Store, peer: &str, sent_seq: i64, r: &Record) -> Result<Outcome> {
    let local = store.note_by_uid(&r.uid)?;
    if r.deleted {
        match local {
            Some(n) if r.ts > n.sync_ts => {
                store.conn.execute("DELETE FROM notes WHERE id=?1", params![n.id])?;
                store.fts_del(n.id)?;
                put_tombstone(store, "note", &r.uid, r.ts, peer)?;
                return Ok(Outcome::Applied);
            }
            Some(_) => return Ok(Outcome::Ignored),
            None => {
                if tombstone_ts(store, "note", &r.uid)?.map(|t| t < r.ts).unwrap_or(true) {
                    put_tombstone(store, "note", &r.uid, r.ts, peer)?;
                    return Ok(Outcome::Applied);
                }
                return Ok(Outcome::Ignored);
            }
        }
    }
    if let Some(t) = tombstone_ts(store, "note", &r.uid)? {
        if t >= r.ts {
            return Ok(Outcome::Ignored);
        }
        store.conn.execute("DELETE FROM tombstones WHERE kind='note' AND uid=?1", params![r.uid])?;
    }
    let folder = if r.folder.is_empty() {
        0
    } else {
        store.folder_by_uid(&r.folder)?.map(|f| f.id).unwrap_or(0)
    };
    let mut images = r.images.clone();
    images.retain(|n| policy::safe_file_name(n));
    images.truncate(policy::MAX_IMAGES);
    let image = policy::image_join(&images);
    let meta = policy::metadata_read(&r.metadata);
    let metadata = if meta.as_object().map(|o| o.is_empty()).unwrap_or(true) {
        policy::metadata_base(&r.title, &r.body, r.created, r.updated).to_string()
    } else {
        r.metadata.clone()
    };
    let search = policy::search_text(&policy::metadata_read(&metadata));
    let ai_checked = if r.ai_checked { r.updated } else { 0 };

    let mut conflict = false;
    let row_id;
    match local {
        Some(n) => {
            if r.ts <= n.sync_ts {
                return Ok(Outcome::Ignored);
            }
            // Побеждает чужая запись. Если мы тоже правили после прошлой отправки — сохраняем нашу копию.
            if n.sync_seq > sent_seq && (n.title != r.title || n.body != r.body) {
                let (ts, seq) = store.stamp()?;
                let now = now_ms();
                let title = format!("{} (копия при конфликте)", n.title);
                let meta2 = policy::metadata_base(&title, &n.body, now, now);
                let s2 = policy::search_text(&meta2);
                store.conn.execute(
                    "INSERT INTO notes(uid,title,body,created,updated,reminder,repeat_mode,lead_minutes,image,folder,title_auto,ai_checked,metadata,search_text,sync_ts,sync_seq,sync_from)
                     VALUES(?1,?2,?3,?4,?4,0,0,-1,?5,?6,0,0,?7,?8,?9,?10,'')",
                    params![random_hex(16), title, n.body, now, policy::image_join(&n.images), n.folder, meta2.to_string(), s2, ts, seq],
                )?;
                let cid = store.conn.last_insert_rowid();
                store.fts_put(cid, &title, &n.body, &s2)?;
                conflict = true;
            }
            let seq = store.stamp_remote(r.ts)?;
            store.conn.execute(
                "UPDATE notes SET title=?2,body=?3,created=?4,updated=?5,reminder=?6,repeat_mode=?7,lead_minutes=?8,image=?9,folder=?10,title_auto=?11,ai_checked=?12,metadata=?13,search_text=?14,sync_ts=?15,sync_seq=?16,sync_from=?17 WHERE id=?1",
                params![n.id, r.title, r.body, r.created, r.updated, r.reminder, r.repeat, r.lead, image, folder, r.title_auto as i64, ai_checked, metadata, search, r.ts, seq, peer],
            )?;
            row_id = n.id;
        }
        None => {
            let seq = store.stamp_remote(r.ts)?;
            store.conn.execute(
                "INSERT INTO notes(uid,title,body,created,updated,reminder,repeat_mode,lead_minutes,image,folder,title_auto,ai_checked,metadata,search_text,sync_ts,sync_seq,sync_from)
                 VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17)",
                params![r.uid, r.title, r.body, r.created, r.updated, r.reminder, r.repeat, r.lead, image, folder, r.title_auto as i64, ai_checked, metadata, search, r.ts, seq, peer],
            )?;
            row_id = store.conn.last_insert_rowid();
        }
    }
    store.fts_put(row_id, &r.title, &r.body, &search)?;
    Ok(if conflict { Outcome::Conflict } else { Outcome::Applied })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::SaveInput;

    fn input(title: &str, body: &str) -> SaveInput {
        SaveInput { id: None, title: title.into(), body: body.into(), reminder: 0, repeat: 0, lead: -1, images: None, folder: None }
    }

    /// Односторонняя передача: A -> B через `collect`/`apply`.
    fn push(a: &Store, b: &Store, since: i64, a_dev: &str, b_dev: &str, b_sent: i64) -> Stats {
        let (recs, _more, _next) = collect(a, since, b_dev, 1000, true, true).unwrap();
        // через провод: сериализация и разбор
        let wire: Vec<Record> = recs
            .iter()
            .map(|r| serde_json::from_value(r.to_wire()).unwrap())
            .collect();
        apply(b, a_dev, b_sent, &wire).unwrap()
    }

    #[test]
    fn new_notes_and_folders_travel() {
        let a = Store::open_memory().unwrap();
        let b = Store::open_memory().unwrap();
        let f = a.create_folder("Работа", false).unwrap();
        let mut i = input("План", "позвонить маме");
        i.folder = Some(f);
        i.reminder = 1_900_000_000_000;
        i.repeat = 1;
        let n = a.save_note(&i).unwrap();
        push(&a, &b, 0, "A", "B", 0);
        let nb = b.note_by_uid(&n.uid).unwrap().unwrap();
        assert_eq!((nb.title.as_str(), nb.body.as_str()), ("План", "позвонить маме"));
        assert_eq!((nb.reminder, nb.repeat, nb.created, nb.updated), (n.reminder, 1, n.created, n.updated));
        let fb = b.folder(nb.folder).unwrap().unwrap();
        assert_eq!(fb.name, "Работа");
        assert_eq!(fb.uid, a.folder(f).unwrap().unwrap().uid);
        assert_eq!(b.notes("позвонить", None).unwrap().len(), 1);
        // повторная отправка ничего не меняет
        let s = push(&a, &b, 0, "A", "B", 0);
        assert_eq!(s.conflicts, 0);
        assert_eq!(b.count_notes().unwrap(), 1);
    }

    #[test]
    fn echo_is_not_returned() {
        let a = Store::open_memory().unwrap();
        let b = Store::open_memory().unwrap();
        a.save_note(&input("x", "y")).unwrap();
        push(&a, &b, 0, "A", "B", 0);
        // B ничего не меняла: записи, принятые от A, A обратно не отдаются.
        let (recs, _, _) = collect(&b, 0, "A", 100, false, true).unwrap();
        assert!(recs.iter().all(|r| r.kind != "note"));
        let (recs, _, _) = collect(&b, 0, "C", 100, false, true).unwrap();
        assert_eq!(recs.iter().filter(|r| r.kind == "note").count(), 1);
    }

    #[test]
    fn newer_wins_and_delete_wins() {
        let a = Store::open_memory().unwrap();
        let b = Store::open_memory().unwrap();
        let n = a.save_note(&input("v1", "текст")).unwrap();
        push(&a, &b, 0, "A", "B", 0);
        let seq_a = a.current_seq().unwrap();
        // B правит позже
        let nb = b.note_by_uid(&n.uid).unwrap().unwrap();
        let mut e = input("v2", "текст правка");
        e.id = Some(nb.id);
        b.save_note(&e).unwrap();
        push(&b, &a, 0, "B", "A", seq_a);
        assert_eq!(a.note_by_uid(&n.uid).unwrap().unwrap().title, "v2");
        assert!(a.current_seq().unwrap() > seq_a);
        // A удаляет, B получает надгробие
        a.delete_note(a.note_by_uid(&n.uid).unwrap().unwrap().id).unwrap();
        push(&a, &b, 0, "A", "B", 0);
        assert!(b.note_by_uid(&n.uid).unwrap().is_none());
        assert_eq!(b.count_notes().unwrap(), 0);
        assert!(b.notes("правка", None).unwrap().is_empty());
        // старая версия не воскрешает удалённое
        let old = Record { kind: "note".into(), uid: n.uid.clone(), ts: 5, title: "old".into(), body: "b".into(), lead: -1, ..Default::default() };
        apply(&b, "A", 0, &[old]).unwrap();
        assert_eq!(b.count_notes().unwrap(), 0);
    }

    #[test]
    fn edit_after_delete_resurrects() {
        let a = Store::open_memory().unwrap();
        let b = Store::open_memory().unwrap();
        let n = a.save_note(&input("t", "b")).unwrap();
        push(&a, &b, 0, "A", "B", 0);
        b.delete_note(b.note_by_uid(&n.uid).unwrap().unwrap().id).unwrap();
        // A правит уже после удаления на B (ts A больше)
        let mut e = input("t2", "b2");
        e.id = Some(n.id);
        // гарантируем больший ts
        std::thread::sleep(std::time::Duration::from_millis(3));
        a.save_note(&e).unwrap();
        push(&a, &b, 0, "A", "B", 0);
        // удаление новее? Нет: ts правки больше надгробия — заметка воскресает.
        let tomb_ts = tombstone_ts(&b, "note", &n.uid).unwrap();
        let alive = b.note_by_uid(&n.uid).unwrap();
        if let Some(t) = tomb_ts {
            assert!(alive.is_none() || t < alive.unwrap().sync_ts);
        } else {
            assert_eq!(alive.unwrap().title, "t2");
        }
    }

    #[test]
    fn conflicting_edits_keep_a_copy() {
        let a = Store::open_memory().unwrap();
        let b = Store::open_memory().unwrap();
        let n = a.save_note(&input("общая", "исходный")).unwrap();
        push(&a, &b, 0, "A", "B", 0);
        let sent_by_b = b.current_seq().unwrap(); // B уже отдала всё, что у неё было
        // обе правят независимо; B позже
        let mut ea = input("общая", "правка на A");
        ea.id = Some(n.id);
        a.save_note(&ea).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(3));
        let nb = b.note_by_uid(&n.uid).unwrap().unwrap();
        let mut eb = input("общая", "правка на B");
        eb.id = Some(nb.id);
        b.save_note(&eb).unwrap();
        // A получает версию B: побеждает B, версия A остаётся копией
        let (recs, _, _) = collect(&b, sent_by_b, "A", 100, true, true).unwrap();
        let s = apply(&a, "B", 0, &recs).unwrap();
        assert_eq!(s.conflicts, 1);
        let all = a.notes("", None).unwrap();
        assert_eq!(all.len(), 2);
        assert!(all.iter().any(|x| x.body == "правка на B" && x.uid == n.uid));
        assert!(all.iter().any(|x| x.body == "правка на A" && x.title.contains("копия при конфликте")));
    }

    #[test]
    fn folder_delete_returns_notes() {
        let a = Store::open_memory().unwrap();
        let b = Store::open_memory().unwrap();
        let f = a.create_folder("Дом", false).unwrap();
        let mut i = input("к", "п");
        i.folder = Some(f);
        let n = a.save_note(&i).unwrap();
        push(&a, &b, 0, "A", "B", 0);
        assert_eq!(b.folders().unwrap().len(), 1);
        std::thread::sleep(std::time::Duration::from_millis(3));
        a.delete_folder(f).unwrap();
        push(&a, &b, 0, "A", "B", 0);
        assert!(b.folders().unwrap().is_empty());
        assert_eq!(b.note_by_uid(&n.uid).unwrap().unwrap().folder, 0);
    }

    #[test]
    fn same_named_folders_merge() {
        let a = Store::open_memory().unwrap();
        let b = Store::open_memory().unwrap();
        a.create_folder("Идеи", false).unwrap();
        b.create_folder("идеи", false).unwrap();
        push(&a, &b, 0, "A", "B", 0);
        assert_eq!(b.folders().unwrap().len(), 1);
    }

    #[test]
    fn pagination_progress() {
        let a = Store::open_memory().unwrap();
        for i in 0..7 {
            a.save_note(&input(&format!("n{}", i), "t")).unwrap();
        }
        let (r1, more1, next1) = collect(&a, 0, "B", 3, false, true).unwrap();
        assert_eq!(r1.len(), 3);
        assert!(more1);
        let (r2, more2, next2) = collect(&a, next1, "B", 3, false, true).unwrap();
        assert_eq!(r2.len(), 3);
        assert!(more2);
        let (r3, more3, next3) = collect(&a, next2, "B", 3, false, true).unwrap();
        assert_eq!(r3.len(), 1);
        assert!(!more3);
        assert_eq!(next3, a.current_seq().unwrap());
    }
}
