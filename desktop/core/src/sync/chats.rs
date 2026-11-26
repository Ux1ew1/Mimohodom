//! Чаты, сообщения и стопки в обмене (docs/SYNC.md, «Чаты и стопки»). Записи вида `chat`,
//! `message` и `stack`; правила слияния те же, что у заметок: побеждает больший `ts`.
//!
//! Номера заметок (`[[note:ID]]` в тексте, `id` в источниках) у каждого устройства свои, поэтому
//! на проводе вместо них — `uid` заметок, а при приёме они снова заменяются местными номерами.

use super::engine::{put_tombstone, tombstone_ts, Record, Stats};
use crate::store::Store;
use anyhow::Result;
use rusqlite::{params, OptionalExtension};
use serde_json::{json, Value};
use std::collections::BTreeMap;

// ---------- ссылки на заметки ----------

/// Заменяет в тексте каждую ссылку `[[note:X]]` результатом `f(X)`; `None` — ссылка уходит как есть.
fn map_links(text: &str, f: &dyn Fn(&str) -> String) -> String {
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(start) = rest.find("[[note:") {
        out.push_str(&rest[..start]);
        let after = &rest[start + 7..];
        match after.find("]]") {
            Some(end) if end > 0 && end <= 40 && !after[..end].contains('[') => {
                out.push_str("[[note:");
                out.push_str(&f(&after[..end]));
                out.push_str("]]");
                rest = &after[end + 2..];
            }
            _ => {
                out.push_str("[[note:");
                rest = after;
            }
        }
    }
    out.push_str(rest);
    out
}

fn uid_of_note(store: &Store, id: &str) -> String {
    id.parse::<i64>()
        .ok()
        .and_then(|i| store.conn.query_row("SELECT uid FROM notes WHERE id=?1", params![i], |r| r.get::<_, String>(0)).ok())
        .unwrap_or_default()
}

fn id_of_note(store: &Store, uid: &str) -> i64 {
    store.conn.query_row("SELECT id FROM notes WHERE uid=?1", params![uid], |r| r.get(0)).unwrap_or(0)
}

/// Текст сообщения для передачи: номера заметок → uid (неизвестная заметка → 0).
fn body_out(store: &Store, body: &str) -> String {
    map_links(body, &|x| {
        let uid = uid_of_note(store, x);
        if uid.is_empty() { "0".to_string() } else { uid }
    })
}

fn body_in(store: &Store, body: &str) -> String {
    map_links(body, &|x| id_of_note(store, x).to_string())
}

fn sources_out(store: &Store, raw: &str) -> Vec<Value> {
    let list: Vec<Value> = serde_json::from_str(raw).unwrap_or_default();
    list.iter()
        .filter_map(|s| {
            let uid = uid_of_note(store, &s["id"].as_i64().unwrap_or(0).to_string());
            if uid.is_empty() { None } else { Some(json!({"uid": uid, "title": s["title"].as_str().unwrap_or(""),
                "contextOnly":s["contextOnly"].as_bool().unwrap_or(false)})) }
        })
        .collect()
}

fn sources_in(store: &Store, list: &[Value]) -> String {
    let local: Vec<Value> = list
        .iter()
        .filter_map(|s| {
            let id = id_of_note(store, s["uid"].as_str().unwrap_or(""));
            if id == 0 { None } else { Some(json!({"id": id, "title": s["title"].as_str().unwrap_or(""),
                "contextOnly":s["contextOnly"].as_bool().unwrap_or(false)})) }
        })
        .collect();
    Value::Array(local).to_string()
}

// ---------- стопки ----------

/// Текущие стопки: `uid` верхней заметки → `uid` исходных через запятую по возрастанию.
fn stack_state(store: &Store) -> Result<BTreeMap<String, String>> {
    let mut out: BTreeMap<String, Vec<String>> = BTreeMap::new();
    let mut st = store.conn.prepare(
        "SELECT t.uid, n.uid FROM note_members m JOIN notes t ON t.id=m.top JOIN notes n ON n.id=m.note
         WHERE t.uid<>'' AND n.uid<>'' AND n.id<>t.id",
    )?;
    let rows = st.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?;
    for r in rows {
        let (top, member) = r?;
        out.entry(top).or_default().push(member);
    }
    Ok(out
        .into_iter()
        .map(|(k, mut v)| {
            v.sort();
            (k, v.join(","))
        })
        .collect())
}

/// Сверяет стопки с последним переданным состоянием и нумерует изменившиеся: триггеров на
/// таблицах стопок нет, изменения находятся сравнением. Вызывается перед отбором и приёмом.
pub fn stamp_stacks(store: &Store) -> Result<()> {
    let cur = stack_state(store)?;
    let mut known: BTreeMap<String, (String, bool)> = BTreeMap::new();
    {
        let mut st = store.conn.prepare("SELECT top,members,deleted FROM stack_sync")?;
        let rows = st.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, i64>(2)? != 0)))?;
        for r in rows {
            let (t, m, d) = r?;
            known.insert(t, (m, d));
        }
    }
    for (top, members) in &cur {
        if matches!(known.get(top), Some((m, false)) if m == members) {
            continue;
        }
        let (ts, seq) = store.stamp()?;
        store.conn.execute(
            "INSERT INTO stack_sync(top,members,ts,seq,from_peer,deleted) VALUES(?1,?2,?3,?4,'',0)
             ON CONFLICT(top) DO UPDATE SET members=excluded.members,ts=excluded.ts,seq=excluded.seq,from_peer='',deleted=0",
            params![top, members, ts, seq],
        )?;
    }
    for (top, (_, deleted)) in &known {
        if !deleted && !cur.contains_key(top) {
            let (ts, seq) = store.stamp()?;
            store.conn.execute(
                "UPDATE stack_sync SET members='',ts=?2,seq=?3,from_peer='',deleted=1 WHERE top=?1",
                params![top, ts, seq],
            )?;
        }
    }
    Ok(())
}

// ---------- отбор ----------

/// Записи чатов, сообщений и стопок со `seq` в (since, snapshot]; по `limit + 1` каждого вида.
pub fn collect_items(store: &Store, since: i64, snapshot: i64, exclude: &str, limit: usize) -> Result<Vec<(i64, Record)>> {
    let mut items: Vec<(i64, Record)> = Vec::new();
    let lim = limit as i64 + 1;
    {
        let mut st = store.conn.prepare(
            "SELECT uid,title,created,kind,sync_ts,sync_seq,linked_note_uid FROM chats
             WHERE sync_seq>?1 AND sync_seq<=?2 AND sync_from<>?3 AND uid<>'' ORDER BY sync_seq LIMIT ?4",
        )?;
        let rows = st.query_map(params![since, snapshot, exclude, lim], |r| {
            Ok((
                r.get::<_, i64>(5)?,
                Record {
                    kind: "chat".into(),
                    uid: r.get(0)?,
                    title: r.get(1)?,
                    created: r.get(2)?,
                    checkin: r.get::<_, i64>(3)? == 1,
                    ts: r.get(4)?,
                    note: Some(r.get(6)?),
                    lead: -1,
                    ..Default::default()
                },
            ))
        })?;
        for r in rows {
            items.push(r?);
        }
    }
    {
        let mut st = store.conn.prepare(
            "SELECT m.uid,c.uid,m.role,m.body,m.sources,m.draft,m.created,m.sync_ts,m.sync_seq
             FROM messages m JOIN chats c ON c.id=m.chat
             WHERE m.sync_seq>?1 AND m.sync_seq<=?2 AND m.sync_from<>?3 AND m.uid<>'' AND c.uid<>''
             ORDER BY m.sync_seq LIMIT ?4",
        )?;
        let rows = st.query_map(params![since, snapshot, exclude, lim], |r| {
            Ok((
                r.get::<_, i64>(8)?,
                (r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, String>(3)?, r.get::<_, String>(4)?, r.get::<_, String>(5)?, r.get::<_, i64>(6)?, r.get::<_, i64>(7)?),
            ))
        })?;
        let raw: Vec<_> = rows.collect::<rusqlite::Result<_>>()?;
        for (seq, (uid, chat, role, body, sources, draft, created, ts)) in raw {
            items.push((
                seq,
                Record {
                    kind: "message".into(),
                    uid,
                    ts,
                    chat,
                    role,
                    body: body_out(store, &body),
                    sources: sources_out(store, &sources),
                    draft,
                    created,
                    lead: -1,
                    ..Default::default()
                },
            ));
        }
    }
    {
        let mut st = store.conn.prepare(
            "SELECT uid,ts,seq FROM tombstones WHERE kind='chat' AND seq>?1 AND seq<=?2 AND from_peer<>?3 ORDER BY seq LIMIT ?4",
        )?;
        let rows = st.query_map(params![since, snapshot, exclude, lim], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?, r.get::<_, i64>(2)?))
        })?;
        for r in rows {
            let (uid, ts, seq) = r?;
            items.push((seq, Record { kind: "chat".into(), uid, ts, deleted: true, lead: -1, ..Default::default() }));
        }
    }
    {
        let mut st = store.conn.prepare(
            "SELECT top,members,ts,seq,deleted FROM stack_sync
             WHERE seq>?1 AND seq<=?2 AND from_peer<>?3 ORDER BY seq LIMIT ?4",
        )?;
        let rows = st.query_map(params![since, snapshot, exclude, lim], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, i64>(2)?, r.get::<_, i64>(3)?, r.get::<_, i64>(4)? != 0))
        })?;
        for r in rows {
            let (top, members, ts, seq, deleted) = r?;
            items.push((
                seq,
                Record {
                    kind: "stack".into(),
                    uid: top,
                    ts,
                    deleted,
                    members: if deleted || members.is_empty() { vec![] } else { members.split(',').map(String::from).collect() },
                    lead: -1,
                    ..Default::default()
                },
            ));
        }
    }
    Ok(items)
}

// ---------- приём ----------

/// Принимает чаты, сообщения и стопки (в таком порядке: сообщение ищет свой чат, стопка — заметки).
pub fn apply(store: &Store, peer: &str, recs: &[Record], stats: &mut Stats) -> Result<()> {
    if recs.iter().any(|r| r.kind == "stack") {
        // Свои необработанные изменения стопок получают ts раньше чужих: правка «после» побеждает.
        stamp_stacks(store)?;
    }
    for r in recs.iter().filter(|r| r.kind == "chat") {
        if apply_chat(store, peer, r)? {
            stats.applied += 1;
        } else {
            stats.ignored += 1;
        }
    }
    for r in recs.iter().filter(|r| r.kind == "message") {
        if apply_message(store, peer, r)? {
            stats.applied += 1;
        } else {
            stats.ignored += 1;
        }
    }
    for r in recs.iter().filter(|r| r.kind == "stack") {
        if apply_stack(store, peer, r)? {
            stats.applied += 1;
        } else {
            stats.ignored += 1;
        }
    }
    Ok(())
}

fn apply_chat(store: &Store, peer: &str, r: &Record) -> Result<bool> {
    let local: Option<(i64, i64)> = store
        .conn
        .query_row("SELECT id,sync_ts FROM chats WHERE uid=?1", params![r.uid], |x| Ok((x.get(0)?, x.get(1)?)))
        .optional()?;
    if r.deleted {
        match local {
            Some((id, ts)) if r.ts > ts => {
                store.conn.execute("DELETE FROM chats WHERE id=?1", params![id])?;
                // Триггер оставил свой ts; чужое надгробие заменяет его.
                put_tombstone(store, "chat", &r.uid, r.ts, peer)?;
                return Ok(true);
            }
            Some(_) => return Ok(false),
            None => {
                if tombstone_ts(store, "chat", &r.uid)?.map(|t| t < r.ts).unwrap_or(true) {
                    put_tombstone(store, "chat", &r.uid, r.ts, peer)?;
                    return Ok(true);
                }
                return Ok(false);
            }
        }
    }
    if let Some(t) = tombstone_ts(store, "chat", &r.uid)? {
        if t >= r.ts {
            return Ok(false);
        }
        store.conn.execute("DELETE FROM tombstones WHERE kind='chat' AND uid=?1", params![r.uid])?;
    }
    let title = if r.title.trim().is_empty() { "Чат".to_string() } else { r.title.trim().to_string() };
    match local {
        Some((id, ts)) => {
            if r.ts <= ts {
                return Ok(false);
            }
            let seq = store.stamp_remote(r.ts)?;
            store.conn.execute(
                "UPDATE chats SET title=?2,created=?3,kind=CASE WHEN ?4=1 THEN 1 ELSE kind END,
                 sync_ts=?5,sync_seq=?6,sync_from=?7,linked_note_uid=coalesce(?8,linked_note_uid) WHERE id=?1",
                params![id, title, r.created, r.checkin as i64, r.ts, seq, peer, r.note],
            )?;
        }
        None => {
            let seq = store.stamp_remote(r.ts)?;
            store.conn.execute(
                "INSERT INTO chats(title,created,kind,uid,sync_ts,sync_seq,sync_from,linked_note_uid)
                 VALUES(?1,?2,?3,?4,?5,?6,?7,?8)",
                params![title, r.created, r.checkin as i64, r.uid, r.ts, seq, peer,
                    r.note.as_deref().unwrap_or("")],
            )?;
        }
    }
    Ok(true)
}

fn apply_message(store: &Store, peer: &str, r: &Record) -> Result<bool> {
    let Some(chat): Option<i64> =
        store.conn.query_row("SELECT id FROM chats WHERE uid=?1", params![r.chat], |x| x.get(0)).optional()?
    else {
        // Чата нет (удалён или ещё не пришёл): сообщению некуда деться.
        return Ok(false);
    };
    if r.role != "user" && r.role != "assistant" {
        return Ok(false);
    }
    let body = body_in(store, &r.body);
    let sources = sources_in(store, &r.sources);
    let local: Option<(i64, i64)> = store
        .conn
        .query_row("SELECT id,sync_ts FROM messages WHERE uid=?1", params![r.uid], |x| Ok((x.get(0)?, x.get(1)?)))
        .optional()?;
    match local {
        Some((id, ts)) => {
            if r.ts <= ts {
                return Ok(false);
            }
            let seq = store.stamp_remote(r.ts)?;
            store.conn.execute(
                "UPDATE messages SET body=?2,sources=?3,draft=?4,sync_ts=?5,sync_seq=?6,sync_from=?7 WHERE id=?1",
                params![id, body, sources, r.draft, r.ts, seq, peer],
            )?;
        }
        None => {
            let seq = store.stamp_remote(r.ts)?;
            store.conn.execute(
                "INSERT INTO messages(chat,role,body,sources,draft,created,uid,sync_ts,sync_seq,sync_from)
                 VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)",
                params![chat, r.role, body, sources, r.draft, r.created, r.uid, r.ts, seq, peer],
            )?;
        }
    }
    Ok(true)
}

fn apply_stack(store: &Store, peer: &str, r: &Record) -> Result<bool> {
    let row_ts: Option<i64> =
        store.conn.query_row("SELECT ts FROM stack_sync WHERE top=?1", params![r.uid], |x| x.get(0)).optional()?;
    if row_ts.map(|t| r.ts <= t).unwrap_or(false) {
        return Ok(false);
    }
    let top = id_of_note(store, &r.uid);
    if r.deleted {
        if top != 0 {
            store.conn.execute("DELETE FROM note_groups WHERE top=?1", params![top])?;
        }
        let seq = store.stamp_remote(r.ts)?;
        store.conn.execute(
            "INSERT INTO stack_sync(top,members,ts,seq,from_peer,deleted) VALUES(?1,'',?2,?3,?4,1)
             ON CONFLICT(top) DO UPDATE SET members='',ts=excluded.ts,seq=excluded.seq,from_peer=excluded.from_peer,deleted=1",
            params![r.uid, r.ts, seq, peer],
        )?;
        return Ok(true);
    }
    if top == 0 {
        return Ok(false);
    }
    let members: Vec<i64> = r.members.iter().map(|u| id_of_note(store, u)).filter(|i| *i != 0 && *i != top).collect();
    if members.is_empty() {
        return Ok(false);
    }
    // Как при создании стопки на телефоне: исходные уходят из прежних стопок, а сами верхние
    // заметки других стопок, попавшие в состав, становятся исходными.
    let expanded: i64 = store
        .conn
        .query_row("SELECT expanded FROM note_groups WHERE top=?1", params![top], |x| x.get(0))
        .optional()?
        .unwrap_or(0);
    store.conn.execute("DELETE FROM note_groups WHERE top=?1", params![top])?;
    store.conn.execute("DELETE FROM note_members WHERE note=?1", params![top])?;
    for m in &members {
        store.conn.execute("DELETE FROM note_groups WHERE top=?1", params![m])?;
    }
    store.conn.execute("INSERT INTO note_groups(top,expanded) VALUES(?1,?2)", params![top, expanded])?;
    for m in &members {
        store.conn.execute("INSERT OR REPLACE INTO note_members(note,top) VALUES(?1,?2)", params![m, top])?;
    }
    store.stacks_cleanup()?;
    let now = stack_state(store)?.remove(&r.uid).unwrap_or_default();
    let seq = store.stamp_remote(r.ts)?;
    store.conn.execute(
        "INSERT INTO stack_sync(top,members,ts,seq,from_peer,deleted) VALUES(?1,?2,?3,?4,?5,0)
         ON CONFLICT(top) DO UPDATE SET members=excluded.members,ts=excluded.ts,seq=excluded.seq,from_peer=excluded.from_peer,deleted=0",
        params![r.uid, now, r.ts, seq, peer],
    )?;
    Ok(true)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::SaveInput;
    use crate::sync::engine::{apply as apply_all, collect};

    fn note(s: &Store, title: &str) -> crate::store::Note {
        s.save_note(&SaveInput { id: None, title: title.into(), body: "текст".into(), reminder: 0, repeat: 0, lead: -1, images: None, folder: None }).unwrap()
    }

    /// Односторонняя передача A -> B через провод (сериализация и разбор записей).
    fn push(a: &Store, b: &Store, since: i64, a_dev: &str, b_dev: &str, b_sent: i64) -> Stats {
        let (recs, _, _) = collect(a, since, b_dev, 1000, true, true).unwrap();
        let wire: Vec<Record> = recs.iter().map(|r| serde_json::from_value(r.to_wire()).unwrap()).collect();
        apply_all(b, a_dev, b_sent, &wire).unwrap()
    }

    #[test]
    fn links_are_translated() {
        let out = map_links("см. [[note:12]] и [[note:5]], а [[note: и [[note:x", &|x| format!("<{}>", x));
        assert_eq!(out, "см. [[note:<12>]] и [[note:<5>]], а [[note: и [[note:x");
    }

    #[test]
    fn chat_and_messages_travel_with_note_links() {
        let a = Store::open_memory().unwrap();
        let b = Store::open_memory().unwrap();
        // На B заметки с другими номерами, чем на A.
        note(&b, "лишняя");
        let n = note(&a, "Борщ");
        let chat = a.new_chat("Про борщ").unwrap();
        a.add_message(chat, "user", "что у меня про борщ?", &[], None).unwrap();
        let src = vec![json!({"id": n.id, "title": "Борщ"})];
        a.add_message(chat, "assistant", &format!("Есть заметка [[note:{}]].", n.id), &src, None).unwrap();
        push(&a, &b, 0, "A", "B", 0);
        let nb = b.note_by_uid(&n.uid).unwrap().unwrap();
        assert_ne!(nb.id, n.id);
        let chats = b.chats().unwrap();
        assert_eq!(chats.len(), 1);
        assert_eq!(chats[0].title, "Про борщ");
        let msgs = b.messages(chats[0].id).unwrap();
        assert_eq!(msgs.len(), 2);
        assert_eq!(msgs[0].body, "что у меня про борщ?");
        assert_eq!(msgs[1].body, format!("Есть заметка [[note:{}]].", nb.id));
        assert_eq!(msgs[1].sources[0]["id"], nb.id);
        // повтор ничего не дублирует
        push(&a, &b, 0, "A", "B", 0);
        assert_eq!(b.chats().unwrap().len(), 1);
        assert_eq!(b.messages(chats[0].id).unwrap().len(), 2);
        // принятое не возвращается отправителю
        let (recs, _, _) = collect(&b, 0, "A", 100, false, true).unwrap();
        assert!(recs.iter().all(|r| r.kind != "chat" && r.kind != "message"));
    }

    #[test]
    fn unknown_note_link_becomes_zero() {
        let a = Store::open_memory().unwrap();
        let b = Store::open_memory().unwrap();
        let chat = a.new_chat("c").unwrap();
        a.add_message(chat, "assistant", "см. [[note:77]]", &[json!({"id": 77, "title": "нет"})], None).unwrap();
        push(&a, &b, 0, "A", "B", 0);
        let m = &b.messages(b.chats().unwrap()[0].id).unwrap()[0];
        assert_eq!(m.body, "см. [[note:0]]");
        assert!(m.sources.as_array().unwrap().is_empty());
    }

    #[test]
    fn message_edit_and_both_directions() {
        let a = Store::open_memory().unwrap();
        let b = Store::open_memory().unwrap();
        let chat = a.new_chat("Идея").unwrap();
        let draft = json!({"title": "Идея", "body": "текст", "metadata": {}});
        a.add_message(chat, "assistant", "Предлагаю заметку", &[], Some(&draft)).unwrap();
        push(&a, &b, 0, "A", "B", 0);
        let cb = b.chats().unwrap()[0].id;
        assert!(b.messages(cb).unwrap()[0].draft.is_object());
        let seq_a = a.current_seq().unwrap();
        // B отвечает и снимает предложение; A получает оба изменения.
        std::thread::sleep(std::time::Duration::from_millis(3));
        b.add_message(cb, "user", "спасибо", &[], None).unwrap();
        let mb = b.messages(cb).unwrap()[0].id;
        b.dismiss_draft(mb).unwrap();
        push(&b, &a, 0, "B", "A", seq_a);
        let ms = a.messages(chat).unwrap();
        assert_eq!(ms.len(), 2);
        assert_eq!(ms[1].body, "спасибо");
        assert!(ms[0].draft.is_null(), "draft: {:?}", ms[0].draft);
    }

    #[test]
    fn messages_keep_chronological_order() {
        let a = Store::open_memory().unwrap();
        let b = Store::open_memory().unwrap();
        let chat = a.new_chat("c").unwrap();
        a.add_message(chat, "user", "первое", &[], None).unwrap();
        push(&a, &b, 0, "A", "B", 0);
        std::thread::sleep(std::time::Duration::from_millis(3));
        let cb = b.chats().unwrap()[0].id;
        // B пишет раньше по номеру в своей базе, чем придёт сообщение A, но позже по времени.
        b.add_message(cb, "assistant", "второе", &[], None).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(3));
        a.add_message(chat, "user", "третье", &[], None).unwrap();
        push(&a, &b, 0, "A", "B", 0);
        let bodies: Vec<String> = b.messages(cb).unwrap().into_iter().map(|m| m.body).collect();
        assert_eq!(bodies, vec!["первое", "второе", "третье"]);
    }

    #[test]
    fn chat_rename_and_delete_travel() {
        let a = Store::open_memory().unwrap();
        let b = Store::open_memory().unwrap();
        let chat = a.new_chat("старое").unwrap();
        a.add_message(chat, "user", "привет", &[], None).unwrap();
        push(&a, &b, 0, "A", "B", 0);
        std::thread::sleep(std::time::Duration::from_millis(3));
        a.rename_chat(chat, "новое").unwrap();
        push(&a, &b, 0, "A", "B", 0);
        assert_eq!(b.chats().unwrap()[0].title, "новое");
        std::thread::sleep(std::time::Duration::from_millis(3));
        a.delete_chat(chat).unwrap();
        push(&a, &b, 0, "A", "B", 0);
        assert!(b.chats().unwrap().is_empty());
        let left: i64 = b.conn.query_row("SELECT count(*) FROM messages", [], |r| r.get(0)).unwrap();
        assert_eq!(left, 0);
        // удалённый чат не воскресает от старой записи
        let uid: String = a.conn.query_row("SELECT uid FROM tombstones WHERE kind='chat'", [], |r| r.get(0)).unwrap();
        let old = Record { kind: "chat".into(), uid, ts: 5, title: "x".into(), lead: -1, ..Default::default() };
        apply_all(&b, "A", 0, &[old]).unwrap();
        assert!(b.chats().unwrap().is_empty());
    }

    #[test]
    fn stacks_travel_and_dissolve() {
        let a = Store::open_memory().unwrap();
        let b = Store::open_memory().unwrap();
        let top = note(&a, "Итог");
        let m1 = note(&a, "Часть 1");
        let m2 = note(&a, "Часть 2");
        a.conn.execute("INSERT INTO note_groups(top) VALUES(?1)", params![top.id]).unwrap();
        a.conn.execute("INSERT INTO note_members(note,top) VALUES(?1,?2),(?3,?2)", params![m1.id, top.id, m2.id]).unwrap();
        push(&a, &b, 0, "A", "B", 0);
        let tb = b.note_by_uid(&top.uid).unwrap().unwrap();
        let stacks = b.stacks().unwrap();
        assert_eq!(stacks.len(), 1);
        assert_eq!(stacks[&tb.id].0.len(), 2);
        // принятое не возвращается отправителю
        let (recs, _, _) = collect(&b, 0, "A", 100, false, true).unwrap();
        assert!(recs.iter().all(|r| r.kind != "stack"));
        // разбор стопки на A доходит до B
        std::thread::sleep(std::time::Duration::from_millis(3));
        a.conn.execute("DELETE FROM note_groups WHERE top=?1", params![top.id]).unwrap();
        push(&a, &b, 0, "A", "B", 0);
        assert!(b.stacks().unwrap().is_empty());
        // старая запись о стопке не оживляет разобранную
        let old = Record { kind: "stack".into(), uid: top.uid.clone(), ts: 5, members: vec![m1.uid.clone()], lead: -1, ..Default::default() };
        apply_all(&b, "A", 0, &[old]).unwrap();
        assert!(b.stacks().unwrap().is_empty());
    }
}
