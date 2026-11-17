//! Хранилище фонового помощника: очередь проверки, предложения менеджера, стопки, предложения
//! напоминаний, история проверок, данные для вопросов о планах (порт `ManagerStore` и частей Android
//! `Store`). Только на этом устройстве, на телефон не передаётся.

use super::review::{self, AnalysisResult};
use super::text;
use crate::policy;
use crate::store::{now_ms, Note, SaveInput, Store};
use anyhow::{anyhow, Result};
use rusqlite::{params, OptionalExtension};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};

#[derive(Default, Debug, Clone, PartialEq)]
pub struct Outcome {
    pub renamed: usize,
    pub reminders: usize,
    pub added: usize,
    pub reviewed: usize,
}

const KEEP_LOG: i64 = 30;

impl Store {
    // ---------- стопки ----------

    /// Верхняя заметка стопки, в которую входит заметка (или она сама), или 0.
    pub fn top_of(&self, id: i64) -> i64 {
        self.conn
            .query_row(
                "SELECT top FROM note_groups WHERE top=?1 UNION SELECT top FROM note_members WHERE note=?1",
                params![id],
                |r| r.get(0),
            )
            .optional()
            .ok()
            .flatten()
            .unwrap_or(0)
    }

    pub fn stacks_cleanup(&self) -> Result<()> {
        self.conn.execute("DELETE FROM note_groups WHERE NOT EXISTS(SELECT 1 FROM note_members m WHERE m.top=note_groups.top)", [])?;
        Ok(())
    }

    /// Состав всех стопок: верхняя заметка → (исходные по возрастанию номера, развёрнута ли).
    pub fn stacks(&self) -> Result<HashMap<i64, (Vec<i64>, bool)>> {
        self.stacks_cleanup()?;
        let mut out: HashMap<i64, (Vec<i64>, bool)> = HashMap::new();
        let mut st = self.conn.prepare("SELECT top,expanded FROM note_groups")?;
        for r in st.query_map([], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, i64>(1)? != 0)))? {
            let (top, open) = r?;
            out.insert(top, (Vec::new(), open));
        }
        let mut st = self.conn.prepare("SELECT top,note FROM note_members ORDER BY top,note")?;
        for r in st.query_map([], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, i64>(1)?)))? {
            let (top, note) = r?;
            if let Some(e) = out.get_mut(&top) {
                e.0.push(note);
            }
        }
        Ok(out)
    }

    pub fn toggle_stack(&self, top: i64) -> Result<()> {
        self.conn.execute("UPDATE note_groups SET expanded=1-expanded WHERE top=?1", params![top])?;
        Ok(())
    }

    /// Разбирает стопку: исходные заметки снова видны в списке, сама верхняя заметка остаётся.
    pub fn unstack(&self, top: i64) -> Result<()> {
        self.conn.execute("DELETE FROM note_groups WHERE top=?1", params![top])?;
        Ok(())
    }

    // ---------- предложения напоминаний ----------

    /// (время, объяснение) предложенного напоминания заметки; время 0 — предложения нет.
    pub fn suggestion(&self, id: i64) -> (i64, String) {
        self.conn
            .query_row("SELECT suggestion,suggestion_reason FROM notes WHERE id=?1", params![id], |r| Ok((r.get(0)?, r.get(1)?)))
            .unwrap_or((0, String::new()))
    }

    /// Заметки с непринятым предложением, время которого ещё впереди: (id, время, причина).
    pub fn pending_suggestions(&self, now: i64) -> Result<Vec<(i64, i64, String)>> {
        let mut st = self.conn.prepare("SELECT id,suggestion,suggestion_reason FROM notes WHERE suggestion>?1 AND reminder=0 ORDER BY suggestion")?;
        let rows = st.query_map(params![now], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn dismiss_suggestion(&self, id: i64) -> Result<()> {
        self.conn.execute("UPDATE notes SET suggestion=0,suggestion_reason='' WHERE id=?1", params![id])?;
        Ok(())
    }

    /// Ставит предложенное напоминание. Подсказки ИИ сохраняются: правка по команде пользователя не
    /// должна заставлять помощника заново платить за разбор той же заметки.
    pub fn accept_suggestion(&self, id: i64) -> Result<()> {
        let n = self.note(id)?.ok_or_else(|| anyhow!("Заметка уже удалена"))?;
        let (at, _) = self.suggestion(id);
        if at <= now_ms() || n.reminder != 0 {
            self.dismiss_suggestion(id)?;
            return Err(anyhow!("Время предложения уже прошло"));
        }
        let ai = policy::metadata_read(&n.metadata).get("ai").cloned().unwrap_or(Value::Null);
        let saved = self.save_note(&SaveInput { id: Some(id), title: n.title.clone(), body: n.body.clone(), reminder: at, lead: -1, ..Default::default() })?;
        self.apply_metadata(saved.id, saved.updated, &ai)?;
        self.conn.execute("UPDATE notes SET ai_checked=updated WHERE id=?1", params![id])?;
        Ok(())
    }

    // ---------- проверка заметок ----------

    pub fn review_batch(&self, manual: bool) -> Result<Vec<Note>> {
        let mut st = self.conn.prepare(
            "SELECT n.id,r.revision FROM notes n LEFT JOIN manager_review r ON r.note=n.id
             WHERE n.id NOT IN (SELECT top FROM note_groups) AND n.id NOT IN (SELECT note FROM note_members)
             ORDER BY CASE WHEN r.note IS NULL THEN 0 ELSE 1 END, COALESCE(r.attempted,0), n.id",
        )?;
        let rows: Vec<(i64, Option<String>)> = st.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?.collect::<rusqlite::Result<_>>()?;
        let mut seeds = Vec::new();
        for (id, revision) in rows {
            let Some(n) = self.note(id)? else { continue };
            let fresh = match &revision {
                None => true,
                Some(rev) => *rev != review::fingerprint(&n),
            };
            // Без ручного запуска берутся только новые и изменённые; неудачные ждут ручного повтора.
            if manual || fresh {
                seeds.push(n);
            }
        }
        Ok(review::select(&seeds))
    }

    pub fn failed_count(&self) -> Result<i64> {
        let mut count = 0;
        let mut st = self.conn.prepare(
            "SELECT n.id,r.revision FROM manager_review r JOIN notes n ON n.id=r.note
             WHERE r.failed=1 AND n.id NOT IN (SELECT top FROM note_groups) AND n.id NOT IN (SELECT note FROM note_members)",
        )?;
        let rows: Vec<(i64, String)> = st.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?.collect::<rusqlite::Result<_>>()?;
        for (id, rev) in rows {
            if let Some(n) = self.note(id)? {
                if review::fingerprint(&n) == rev {
                    count += 1;
                }
            }
        }
        Ok(count)
    }

    pub fn retry_failed(&self) -> Result<()> {
        self.conn.execute("DELETE FROM manager_review WHERE failed=1", [])?;
        Ok(())
    }

    /// Помечает пачку как отправленную: если ответ потеряется, заметки не уйдут в платный запрос
    /// повторно сами, а ждут ручного повтора.
    pub fn claim_review(&self, batch: &[Note]) -> Result<()> {
        let now = now_ms();
        let tx = self.conn.unchecked_transaction()?;
        for n in batch {
            self.conn.execute(
                "INSERT OR REPLACE INTO manager_review(note,revision,attempted,failed) VALUES(?1,?2,?3,1)",
                params![n.id, review::fingerprint(n), now],
            )?;
        }
        tx.commit()?;
        Ok(())
    }

    /// Применяет разбор одной заметки, если её не правили (и она не в стопке): название, подсказки
    /// поиска, предложение напоминания. Время правки не меняется, чтобы не затереть чужую правку.
    pub fn apply_analysis(&self, snapshot: &Note, r: &AnalysisResult) -> Result<bool> {
        let tx = self.conn.unchecked_transaction()?;
        let applied = self.apply_analysis_inner(snapshot, r)?;
        tx.commit()?;
        Ok(applied)
    }

    fn apply_analysis_inner(&self, snapshot: &Note, r: &AnalysisResult) -> Result<bool> {
        let Some(n) = self.note(snapshot.id)? else { return Ok(false) };
        if n.updated != snapshot.updated || self.top_of(n.id) != 0 {
            return Ok(false);
        }
        let rename = n.title_auto && !r.title.is_empty();
        let title = if rename { r.title.clone() } else { n.title.clone() };
        let base = policy::metadata_base(&title, &n.body, n.created, n.updated);
        let old_ai = policy::metadata_read(&n.metadata).get("ai").cloned().unwrap_or(Value::Null);
        let merged = text::metadata_with_ai(&base, &old_ai, n.updated);
        let merged = text::metadata_with_ai(&merged, &r.metadata, n.updated);
        let search = policy::search_text(&merged);
        let (suggestion, reason) = if n.reminder == 0 && r.reminder > now_ms() { (r.reminder, r.reason.clone()) } else { (0, String::new()) };
        let changed = if rename {
            // Название — содержимое: оно должно дойти до других устройств.
            let (ts, seq) = self.stamp()?;
            self.conn.execute(
                "UPDATE notes SET title=?2,title_auto=0,ai_failed=0,ai_checked=updated,metadata=?3,search_text=?4,
                   suggestion=CASE WHEN ?5>0 THEN ?5 ELSE suggestion END, suggestion_reason=CASE WHEN ?5>0 THEN ?6 ELSE suggestion_reason END,
                   sync_ts=?7,sync_seq=?8,sync_from='' WHERE id=?1 AND updated=?9",
                params![n.id, title, merged.to_string(), search, suggestion, reason, ts, seq, n.updated],
            )?
        } else {
            self.conn.execute(
                "UPDATE notes SET ai_failed=0,ai_checked=updated,metadata=?2,search_text=?3,
                   suggestion=CASE WHEN ?4>0 THEN ?4 ELSE suggestion END, suggestion_reason=CASE WHEN ?4>0 THEN ?5 ELSE suggestion_reason END
                 WHERE id=?1 AND updated=?6",
                params![n.id, merged.to_string(), search, suggestion, reason, n.updated],
            )?
        };
        if changed == 1 {
            self.fts_put(n.id, &title, &n.body, &search)?;
        }
        Ok(changed == 1)
    }

    /// Записывает итог проверки одной транзакцией: разбор заметок, предложения, отметки «просмотрено».
    /// Заметки, изменённые во время запроса, пропускаются, а предложения про них отбрасываются.
    pub fn finish_review(&self, batch: &[Note], proposals: Vec<Value>, analysis: &HashMap<i64, AnalysisResult>) -> Result<Outcome> {
        let tx = self.conn.unchecked_transaction()?;
        let mut out = Outcome::default();
        let mut valid: Vec<Value> = proposals.into_iter().filter(|p| self.sources_current(p)).collect();
        let mut unchanged = Vec::new();
        for snapshot in batch {
            let Some(current) = self.note(snapshot.id)? else { continue };
            if review::fingerprint(&current) != review::fingerprint(snapshot) {
                continue;
            }
            if let Some(r) = analysis.get(&snapshot.id) {
                if self.apply_analysis_inner(snapshot, r)? {
                    let after = self.note(snapshot.id)?.unwrap();
                    if after.title != current.title {
                        out.renamed += 1;
                    }
                    let (at, _) = self.suggestion(snapshot.id);
                    if at > now_ms() && current.reminder == 0 {
                        out.reminders += 1;
                    }
                    out.reviewed += 1;
                }
            }
            unchanged.push(snapshot.id);
        }
        // Своё переименование не делает предложение устаревшим: пересчитываем отпечатки источников.
        for p in valid.iter_mut() {
            if let Some(sources) = p["sources"].as_array_mut() {
                for s in sources.iter_mut() {
                    if let Some(n) = s["id"].as_i64().and_then(|id| self.note(id).ok().flatten()) {
                        s["revision"] = json!(review::fingerprint(&n));
                    }
                }
            }
            review::set_identity(p);
            let inserted = self.conn.execute(
                "INSERT OR IGNORE INTO manager_proposals(identity,data,created) VALUES(?1,?2,?3)",
                params![p["identity"].as_str().unwrap_or(""), p.to_string(), now_ms()],
            )?;
            out.added += inserted;
        }
        for id in unchanged {
            if let Some(n) = self.note(id)? {
                self.conn.execute("UPDATE manager_review SET revision=?2,failed=0 WHERE note=?1", params![id, review::fingerprint(&n)])?;
            }
        }
        tx.commit()?;
        Ok(out)
    }

    // ---------- предложения менеджера ----------

    /// Все источники предложения на месте, не изменились и не входят в стопки.
    pub fn sources_current(&self, p: &Value) -> bool {
        let Some(sources) = p["sources"].as_array() else { return false };
        sources.iter().all(|s| {
            let (Some(id), Some(rev)) = (s["id"].as_i64(), s["revision"].as_str()) else { return false };
            match self.note(id) {
                Ok(Some(n)) => review::fingerprint(&n) == rev && self.top_of(id) == 0,
                _ => false,
            }
        })
    }

    pub fn proposal(&self, id: i64) -> Result<(Value, String)> {
        let (data, state): (String, String) = self
            .conn
            .query_row("SELECT data,state FROM manager_proposals WHERE id=?1", params![id], |r| Ok((r.get(0)?, r.get(1)?)))
            .optional()?
            .ok_or_else(|| anyhow!("Предложение не найдено"))?;
        let mut v: Value = serde_json::from_str(&data)?;
        v["id"] = json!(id);
        Ok((v, state))
    }

    /// Предложения, которые ещё ждут решения; устаревшие помечаются и скрываются.
    pub fn pending_proposals(&self) -> Result<Vec<Value>> {
        let mut st = self.conn.prepare("SELECT id FROM manager_proposals WHERE state='pending' ORDER BY created DESC,id DESC")?;
        let ids: Vec<i64> = st.query_map([], |r| r.get(0))?.collect::<rusqlite::Result<_>>()?;
        let mut out = Vec::new();
        for id in ids {
            let (p, _) = self.proposal(id)?;
            if self.sources_current(&p) {
                out.push(p);
            } else {
                self.set_proposal_state(id, "stale")?;
            }
        }
        Ok(out)
    }

    pub fn set_proposal_state(&self, id: i64, state: &str) -> Result<()> {
        self.conn.execute("UPDATE manager_proposals SET state=?2 WHERE id=?1 AND state='pending'", params![id, state])?;
        Ok(())
    }

    pub fn save_summary(&self, id: i64, summary: &Value) -> Result<()> {
        let (mut p, _) = self.proposal(id)?;
        p["_summary"] = summary.clone();
        let n = self.conn.execute("UPDATE manager_proposals SET data=?2 WHERE id=?1 AND state='pending'", params![id, p.to_string()])?;
        if n != 1 {
            return Err(anyhow!("Предложение уже закрыто"));
        }
        Ok(())
    }

    /// Исходные заметки предложения для выжимки.
    pub fn proposal_sources(&self, p: &Value) -> Result<Vec<Note>> {
        let mut ids: Vec<i64> = p["sources"].as_array().map(|a| a.iter().filter_map(|s| s["id"].as_i64()).collect()).unwrap_or_default();
        ids.sort_unstable();
        ids.dedup();
        ids.into_iter().map(|id| self.note(id)?.ok_or_else(|| anyhow!("Исходники изменились. Запустите новую проверку."))).collect()
    }

    /// Принимает предложение: создаёт заметку (с правками пользователя), а для объединения и связи
    /// собирает стопку из исходных заметок. Повторный вызов возвращает уже созданную заметку.
    pub fn accept_proposal(&self, id: i64, title: &str, body: &str) -> Result<Note> {
        let (p, state) = self.proposal(id)?;
        if state == "accepted" {
            let result = self.conn.query_row("SELECT result FROM manager_proposals WHERE id=?1", params![id], |r| r.get::<_, i64>(0))?;
            return self.note(result)?.ok_or_else(|| anyhow!("Заметка уже удалена"));
        }
        if state != "pending" || !self.sources_current(&p) {
            return Err(anyhow!("Исходные заметки изменились. Нужна новая проверка."));
        }
        if body.trim().is_empty() {
            return Err(anyhow!("Добавьте текст заметки"));
        }
        let note = self.save_note(&SaveInput { title: title.to_string(), body: body.to_string(), lead: -1, ..Default::default() })?;
        let tx = self.conn.unchecked_transaction()?;
        let kind = p["type"].as_str().unwrap_or("");
        if kind == "merge" || kind == "link" {
            self.conn.execute("INSERT INTO note_groups(top) VALUES(?1)", params![note.id])?;
            for s in p["sources"].as_array().cloned().unwrap_or_default() {
                if let Some(source) = s["id"].as_i64() {
                    self.conn.execute("INSERT OR REPLACE INTO note_members(note,top) VALUES(?1,?2)", params![source, note.id])?;
                }
            }
            // Новая заметка-итог уже проверена: подсказки поиска берутся из её исходников.
            self.conn.execute("UPDATE notes SET ai_checked=updated WHERE id=?1", params![note.id])?;
            self.conn.execute(
                "INSERT OR REPLACE INTO manager_review(note,revision,attempted,failed) VALUES(?1,?2,?3,0)",
                params![note.id, review::fingerprint(&self.note(note.id)?.unwrap()), now_ms()],
            )?;
        }
        self.conn.execute("UPDATE manager_proposals SET state='accepted',result=?2 WHERE id=?1", params![id, note.id])?;
        tx.commit()?;
        Ok(note)
    }

    // ---------- бюджет проверок ----------

    /// Занимает один запрос из дневного лимита; false — лимит исчерпан.
    pub fn reserve_budget(&self, day: &str, unlimited: bool) -> Result<bool> {
        let count: i64 = if self.setting("analysis.day").as_deref() == Some(day) { self.setting("analysis.count").and_then(|v| v.parse().ok()).unwrap_or(0) } else { 0 };
        if !review::allowed(count, unlimited) {
            return Ok(false);
        }
        self.set_setting("analysis.day", day)?;
        self.set_setting("analysis.count", &(count + 1).to_string())?;
        Ok(true)
    }

    pub fn budget_used(&self, day: &str) -> i64 {
        if self.setting("analysis.day").as_deref() == Some(day) {
            self.setting("analysis.count").and_then(|v| v.parse().ok()).unwrap_or(0)
        } else {
            0
        }
    }

    // ---------- история ----------

    pub fn log(&self, text: &str) -> Result<()> {
        self.conn.execute("INSERT INTO review_log(time,text) VALUES(?1,?2)", params![now_ms(), text])?;
        self.conn.execute("DELETE FROM review_log WHERE id<=(SELECT max(id) FROM review_log)-?1", params![KEEP_LOG])?;
        Ok(())
    }

    pub fn log_list(&self) -> Result<Vec<(i64, String)>> {
        let mut st = self.conn.prepare("SELECT time,text FROM review_log ORDER BY id DESC")?;
        let rows = st.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    // ---------- папки по темам ----------

    pub fn folder_keys(&self) -> Result<HashMap<String, i64>> {
        Ok(self.folders()?.into_iter().map(|f| (f.key, f.id)).collect())
    }

    pub fn dismissed_topics(&self) -> HashSet<String> {
        self.setting("folders.dismissed").and_then(|v| serde_json::from_str::<Vec<String>>(&v).ok()).unwrap_or_default().into_iter().collect()
    }

    pub fn dismiss_topic(&self, key: &str) -> Result<()> {
        let mut all = self.dismissed_topics();
        all.insert(key.to_string());
        let mut list: Vec<String> = all.into_iter().collect();
        list.sort();
        self.set_setting("folders.dismissed", &serde_json::to_string(&list)?)
    }

    // ---------- вопросы о планах ----------

    /// Начатый помощником чат: название и первое сообщение (вопрос).
    pub fn new_assistant_chat(&self, title: &str, question: &str) -> Result<i64> {
        let tx = self.conn.unchecked_transaction()?;
        let chat = self.new_chat(title)?;
        self.conn.execute("UPDATE chats SET kind=1 WHERE id=?1", params![chat])?;
        self.add_message(chat, "assistant", question, &[], None)?;
        tx.commit()?;
        Ok(chat)
    }

    pub fn chat_kind(&self, chat: i64) -> i64 {
        self.conn.query_row("SELECT kind FROM chats WHERE id=?1", params![chat], |r| r.get(0)).unwrap_or(0)
    }

    pub fn last_user_message(&self) -> i64 {
        self.conn.query_row("SELECT coalesce(max(created),0) FROM messages WHERE role='user'", [], |r| r.get(0)).unwrap_or(0)
    }

    /// Текст пользователя в чате (пустой — на вопрос так и не ответили).
    pub fn user_text(&self, chat: i64) -> String {
        let mut st = match self.conn.prepare("SELECT body FROM messages WHERE chat=?1 AND role='user' ORDER BY id") {
            Ok(s) => s,
            Err(_) => return String::new(),
        };
        let rows = st.query_map(params![chat], |r| r.get::<_, String>(0)).map(|it| it.flatten().collect::<Vec<_>>()).unwrap_or_default();
        text::split(&rows.join("\n")).0.trim().to_string()
    }

    pub fn first_message(&self, chat: i64) -> String {
        self.conn.query_row("SELECT body FROM messages WHERE chat=?1 ORDER BY id LIMIT 1", params![chat], |r| r.get(0)).unwrap_or_default()
    }

    /// Ближайшие заметки с напоминаниями: (время, название).
    pub fn upcoming(&self, now: i64, limit: i64) -> Result<Vec<(i64, String)>> {
        let mut st = self.conn.prepare("SELECT reminder,title FROM notes WHERE reminder>=?1 ORDER BY reminder LIMIT ?2")?;
        let rows = st.query_map(params![now, limit], |r| Ok((r.get(0)?, r.get(1)?)))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// Свежие заметки: (название, текст).
    pub fn recent_notes(&self, limit: i64) -> Result<Vec<(String, String)>> {
        let mut st = self.conn.prepare("SELECT title,body FROM notes ORDER BY updated DESC LIMIT ?1")?;
        let rows = st.query_map(params![limit], |r| Ok((r.get(0)?, r.get(1)?)))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// Прошлые вопросы помощника с ответами пользователя (новые первыми).
    pub fn past_questions(&self, limit: i64) -> Result<Vec<(String, String)>> {
        let mut st = self.conn.prepare("SELECT id FROM chats WHERE kind=1 ORDER BY id DESC LIMIT ?1")?;
        let ids: Vec<i64> = st.query_map(params![limit], |r| r.get(0))?.collect::<rusqlite::Result<_>>()?;
        Ok(ids.into_iter().map(|id| (self.first_message(id), self.user_text(id))).collect())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::Store;

    fn add(st: &Store, title: &str, body: &str) -> Note {
        st.save_note(&SaveInput { title: title.into(), body: body.into(), lead: -1, ..Default::default() }).unwrap()
    }

    #[test]
    fn analysis_applies_without_touching_edit_time() {
        let st = Store::open_memory().unwrap();
        let n = add(&st, "", "в субботу в 10:00 сдать анализы в клинике");
        assert!(n.title_auto);
        let future = now_ms() + 3 * 86_400_000;
        let r = AnalysisResult { title: "Сдать анализы".into(), reason: "в субботу в 10:00".into(), reminder: future, metadata: json!({"topics": ["здоровье"]}) };
        let before = st.current_seq().unwrap();
        assert!(st.apply_analysis(&n, &r).unwrap());
        let after = st.note(n.id).unwrap().unwrap();
        assert_eq!(after.title, "Сдать анализы");
        assert!(!after.title_auto);
        assert_eq!(after.updated, n.updated, "время правки пользователя не меняется");
        assert!(st.current_seq().unwrap() > before, "новое название уходит на другие устройства");
        assert_eq!(st.suggestion(n.id).0, future);
        // Подсказки попали в поиск.
        assert_eq!(st.notes("здоровье", None).unwrap().len(), 1);
        // Если пользователь успел изменить заметку, разбор не применяется.
        let edited = st.save_note(&SaveInput { id: Some(n.id), title: "Свой".into(), body: "другое".into(), lead: -1, ..Default::default() }).unwrap();
        assert_eq!(st.suggestion(n.id).0, 0, "правка снимает предложение");
        assert!(!st.apply_analysis(&n, &r).unwrap());
        assert_eq!(st.note(n.id).unwrap().unwrap().updated, edited.updated);
    }

    #[test]
    fn accepting_a_suggestion_keeps_search_hints() {
        let st = Store::open_memory().unwrap();
        let n = add(&st, "Врач", "приём у врача в пятницу в 9:00");
        let future = now_ms() + 86_400_000;
        let r = AnalysisResult { reason: "в пятницу в 9:00".into(), reminder: future, metadata: json!({"aliases": ["поликлиника"]}), ..Default::default() };
        st.apply_analysis(&n, &r).unwrap();
        assert_eq!(st.pending_suggestions(now_ms()).unwrap().len(), 1);
        st.accept_suggestion(n.id).unwrap();
        let after = st.note(n.id).unwrap().unwrap();
        assert_eq!(after.reminder, future);
        assert_eq!(st.suggestion(n.id).0, 0);
        assert_eq!(st.notes("поликлиника", None).unwrap().len(), 1, "подсказки ИИ не потеряны");
        assert!(st.accept_suggestion(n.id).is_err());
        // Скрытое предложение исчезает.
        let m = add(&st, "Звонок", "позвонить завтра в 12:00");
        st.apply_analysis(&m, &AnalysisResult { reason: "завтра в 12:00".into(), reminder: future, ..Default::default() }).unwrap();
        st.dismiss_suggestion(m.id).unwrap();
        assert!(st.pending_suggestions(now_ms()).unwrap().is_empty());
    }

    #[test]
    fn review_queue_and_proposals() {
        let st = Store::open_memory().unwrap();
        let a = add(&st, "Борщ", "свёкла капуста мясо");
        let b = add(&st, "Борщ 2", "свёкла капуста морковь");
        let batch = st.review_batch(false).unwrap();
        assert_eq!(batch.len(), 2);
        st.claim_review(&batch).unwrap();
        // Заявленные, но не завершённые заметки ждут ручного повтора.
        assert!(st.review_batch(false).unwrap().is_empty());
        assert_eq!(st.failed_count().unwrap(), 2);
        assert_eq!(st.review_batch(true).unwrap().len(), 2, "ручной запуск берёт всё");
        let proposals = review::proposals(&batch, vec![json!({"type": "task", "reason": "r", "title": "t", "body": "b", "sources": [{"id": a.id, "revision": review::fingerprint(&a)}]})]).unwrap();
        let analysis: HashMap<i64, AnalysisResult> = batch.iter().map(|n| (n.id, AnalysisResult::default())).collect();
        let out = st.finish_review(&batch, proposals.clone(), &analysis).unwrap();
        assert_eq!((out.reviewed, out.added), (2, 2));
        assert_eq!(st.failed_count().unwrap(), 0);
        assert!(st.review_batch(false).unwrap().is_empty(), "просмотренное больше не берётся");
        // Те же предложения повторно не добавляются.
        let again = st.finish_review(&batch, proposals, &analysis).unwrap();
        assert_eq!(again.added, 0);
        assert_eq!(st.pending_proposals().unwrap().len(), 2);
        // Правка источника делает предложение устаревшим.
        st.save_note(&SaveInput { id: Some(b.id), title: "Борщ 2".into(), body: "изменено".into(), lead: -1, ..Default::default() }).unwrap();
        let left = st.pending_proposals().unwrap();
        assert!(left.iter().all(|p| p["type"] == "task"), "связь с изменённой заметкой устарела");
        assert_eq!(left.len(), 1);
        assert_eq!(st.review_batch(false).unwrap().len(), 1, "изменённая заметка снова в очереди");
    }

    #[test]
    fn merge_builds_a_stack_and_hides_sources() {
        let st = Store::open_memory().unwrap();
        let a = add(&st, "Борщ", "свёкла капуста мясо");
        let b = add(&st, "Борщ 2", "свёкла капуста морковь");
        let batch = vec![a.clone(), b.clone()];
        let model = json!({"suggestions": [{"type": "merge", "sources": [a.id, b.id], "reason": "дубли", "title": "Борщ", "body": "Свёкла, капуста, мясо, морковь"}]});
        let merge = review::manager_parse(&model.to_string(), &batch).unwrap();
        let analysis: HashMap<i64, AnalysisResult> = batch.iter().map(|n| (n.id, AnalysisResult::default())).collect();
        st.finish_review(&batch, merge, &analysis).unwrap();
        let p = st.pending_proposals().unwrap();
        let id = p[0]["id"].as_i64().unwrap();
        assert!(st.accept_proposal(id, "Борщ", "").is_err(), "пустой текст");
        let top = st.accept_proposal(id, "Борщ", "Свёкла, капуста, мясо, морковь").unwrap();
        assert_eq!(st.top_of(a.id), top.id);
        assert_eq!(st.top_of(top.id), top.id);
        let stacks = st.stacks().unwrap();
        assert_eq!(stacks[&top.id].0, vec![a.id, b.id]);
        // Повторное принятие не плодит заметок.
        assert_eq!(st.accept_proposal(id, "Борщ", "Свёкла").unwrap().id, top.id);
        assert_eq!(st.count_notes().unwrap(), 3);
        // Стопки и их исходники не попадают в проверку.
        assert!(st.review_batch(true).unwrap().is_empty());
        // Удаление верхней заметки возвращает исходные в список.
        st.delete_note(top.id).unwrap();
        assert_eq!(st.top_of(a.id), 0);
        assert!(st.stacks().unwrap().is_empty());
        assert_eq!(st.review_batch(true).unwrap().len(), 2);
    }

    #[test]
    fn budget_resets_each_day() {
        let st = Store::open_memory().unwrap();
        for _ in 0..4 {
            assert!(st.reserve_budget("2026-11-18", false).unwrap());
        }
        assert!(!st.reserve_budget("2026-11-18", false).unwrap());
        assert!(st.reserve_budget("2026-11-18", true).unwrap(), "лимит можно снять");
        assert!(st.reserve_budget("2026-11-19", false).unwrap());
        assert_eq!(st.budget_used("2026-11-19"), 1);
    }

    #[test]
    fn history_keeps_last_entries_and_checkin_chat() {
        let st = Store::open_memory().unwrap();
        for i in 0..40 {
            st.log(&format!("запись {}", i)).unwrap();
        }
        let log = st.log_list().unwrap();
        assert_eq!(log.len() as i64, KEEP_LOG);
        assert_eq!(log[0].1, "запись 39");
        let chat = st.new_assistant_chat("Смены", "Когда у вас смена на этой неделе?").unwrap();
        assert_eq!(st.chat_kind(chat), 1);
        assert_eq!(st.user_text(chat), "");
        st.add_message(chat, "user", "в среду", &[], None).unwrap();
        assert_eq!(st.user_text(chat), "в среду");
        assert_eq!(st.past_questions(3).unwrap(), vec![("Когда у вас смена на этой неделе?".to_string(), "в среду".to_string())]);
        assert!(st.last_user_message() > 0);
    }
}
