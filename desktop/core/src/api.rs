//! Единая точка команд для интерфейса (Tauri) и отладочного HTTP-сервера: `dispatch(cmd, args)`.

use crate::ai::{assistant, chat, checkin, folders, polza, review, video, video_run};
use crate::app::App;
use crate::policy;
use crate::reminders;
use crate::store::{now_ms, random_hex, Note, SaveInput, Store};
use crate::sync::{client, pairing, server};
use anyhow::{anyhow, Result};
use base64::Engine;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::Arc;

fn s(args: &Value, key: &str) -> String {
    args.get(key).and_then(|v| v.as_str()).unwrap_or("").to_string()
}

fn i(args: &Value, key: &str) -> i64 {
    args.get(key).and_then(|v| v.as_i64()).unwrap_or(0)
}

pub fn sync_enabled(app: &Arc<App>) -> bool {
    app.store.lock().unwrap().setting("sync.enabled").as_deref() == Some("1")
}

/// Запускает сервер, если синхронизация включена в настройках (вызывать при старте приложения).
pub fn start_background(app: &Arc<App>) {
    if sync_enabled(app) {
        let _ = server::start(app, server::default_port());
    }
    let app2 = app.clone();
    std::thread::spawn(move || loop {
        std::thread::sleep(std::time::Duration::from_secs(120));
        if sync_enabled(&app2) {
            sync_all_async(&app2, false);
        }
    });
    // Фоновый помощник: проверка заметок и вопросы о планах по расписанию.
    let app3 = app.clone();
    std::thread::spawn(move || loop {
        std::thread::sleep(std::time::Duration::from_secs(60));
        assistant::tick(&app3);
    });
    // Напоминания: проверка при запуске (пропущенные, пока приложение было закрыто) и затем раз в 15 секунд.
    let app4 = app.clone();
    std::thread::spawn(move || loop {
        reminders::tick(&app4);
        std::thread::sleep(std::time::Duration::from_secs(reminders::TICK_SECS));
    });
}

/// Обмен со всеми узлами, чей адрес известен (телефоны сами приходят к компьютеру).
pub fn sync_all_async(app: &Arc<App>, force: bool) {
    let peers = app.store.lock().unwrap().peers().unwrap_or_default();
    let targets: Vec<String> = peers
        .into_iter()
        .filter(|p| force || !p.hosts.is_empty())
        .map(|p| p.device)
        .collect();
    if targets.is_empty() {
        return;
    }
    let app = app.clone();
    std::thread::spawn(move || {
        {
            let mut busy = app.sync.busy.lock().unwrap();
            if *busy {
                return;
            }
            *busy = true;
        }
        let mut msgs = Vec::new();
        for d in targets {
            match client::sync_peer(&app, &d) {
                Ok(r) => msgs.push(format!(
                    "{}: отправлено {}, получено {}{}",
                    r.peer,
                    r.sent,
                    r.received,
                    if r.conflicts > 0 { format!(", конфликтов {}", r.conflicts) } else { String::new() }
                )),
                Err(e) => msgs.push(e.to_string()),
            }
        }
        app.set_status(&msgs.join("\n"));
        *app.sync.busy.lock().unwrap() = false;
    });
}

pub fn dispatch(app: &Arc<App>, cmd: &str, args: Value) -> Result<Value> {
    match cmd {
        // ---------- заметки ----------
        "notes" => {
            let q = s(&args, "query");
            let folder = args.get("folder").and_then(|v| v.as_i64());
            let st = app.store.lock().unwrap();
            list_notes(&st, &q, folder)
        }
        "note" => {
            let st = app.store.lock().unwrap();
            match st.note(i(&args, "id"))? {
                Some(n) => {
                    let stacks = st.stacks()?;
                    note_json(&st, &n, stacks.get(&n.id), 0)
                }
                None => Ok(Value::Null),
            }
        }
        "save_note" => {
            let input: SaveInput = serde_json::from_value(args)?;
            let note = {
                let st = app.store.lock().unwrap();
                let before = match input.id.filter(|i| *i != 0) {
                    Some(id) => st.note(id)?,
                    None => None,
                };
                let note = st.save_note(&input)?;
                reminders::on_saved(&st, before.as_ref(), &note)?;
                note
            };
            app.cleanup_images();
            Ok(serde_json::to_value(note)?)
        }
        "delete_note" => {
            {
                let st = app.store.lock().unwrap();
                st.delete_note(i(&args, "id"))?;
                reminders::on_deleted(&st, i(&args, "id"))?;
            }
            app.cleanup_images();
            Ok(json!(true))
        }
        // ---------- напоминания и календарь ----------
        "reminders_active" => Ok(Value::Array(reminders::local_active(app)?)),
        "reminder_snooze" => Ok(json!(reminders::local_snooze(app, i(&args, "id"), i(&args, "kind"))?)),
        "reminder_dismiss" => {
            reminders::dismiss(&app.store.lock().unwrap(), i(&args, "id"))?;
            Ok(json!(true))
        }
        "reminder_test" => {
            reminders::push_outbox(app, vec![reminders::test_notice()]);
            Ok(json!(true))
        }
        "calendar" => Ok(Value::Array(reminders::local_calendar(app, i(&args, "from"), i(&args, "to"))?)),
        "move_note" => {
            app.store.lock().unwrap().move_note(i(&args, "id"), i(&args, "folder"))?;
            Ok(json!(true))
        }
        // ---------- папки ----------
        "folders" => Ok(serde_json::to_value(app.store.lock().unwrap().folders()?)?),
        "create_folder" => {
            let id = app.store.lock().unwrap().create_folder(&s(&args, "name"), false)?;
            if id == 0 {
                return Err(anyhow!("Недопустимое имя папки"));
            }
            Ok(json!(id))
        }
        "rename_folder" => {
            let ok = app.store.lock().unwrap().rename_folder(i(&args, "id"), &s(&args, "name"))?;
            if !ok {
                return Err(anyhow!("Имя недопустимо или уже занято"));
            }
            Ok(json!(true))
        }
        "delete_folder" => {
            app.store.lock().unwrap().delete_folder(i(&args, "id"))?;
            Ok(json!(true))
        }
        // ---------- картинки ----------
        "image_add" => {
            let data = s(&args, "data");
            let data = data.rsplit(',').next().unwrap_or("").to_string();
            let bytes = base64::engine::general_purpose::STANDARD
                .decode(data.as_bytes())
                .map_err(|_| anyhow!("Не удалось прочитать картинку"))?;
            if bytes.is_empty() || bytes.len() > 8 * 1024 * 1024 {
                return Err(anyhow!("Картинка слишком большая"));
            }
            let name = format!("{}-{}-{}.jpg", random_hex(4), random_hex(2), random_hex(6));
            std::fs::write(app.images_dir().join(&name), bytes)?;
            Ok(json!(name))
        }
        "image_get" => {
            let name = s(&args, "name");
            let path = app.image_path(&name).ok_or_else(|| anyhow!("плохое имя файла"))?;
            match std::fs::read(path) {
                Ok(b) => Ok(json!(format!(
                    "data:image/jpeg;base64,{}",
                    base64::engine::general_purpose::STANDARD.encode(b)
                ))),
                Err(_) => Ok(Value::Null),
            }
        }
        // ---------- настройки ----------
        "app_version" => Ok(json!(env!("CARGO_PKG_VERSION").replace('-', "."))),
        "setting_get" => Ok(json!(app.store.lock().unwrap().setting(&s(&args, "key")))),
        "setting_set" => {
            app.store.lock().unwrap().set_setting(&s(&args, "key"), &s(&args, "value"))?;
            Ok(json!(true))
        }
        // ---------- экспорт ----------
        "export" => {
            let st = app.store.lock().unwrap();
            let notes = st.notes("", None)?;
            let folders = st.folders()?;
            let out = json!({
                "app": "mimohodom-desktop",
                "version": 5,
                "exported": now_ms(),
                "folders": folders.iter().map(|f| json!({"id": f.id, "name": f.name, "created": f.created})).collect::<Vec<_>>(),
                "notes": notes.iter().map(|n| json!({
                    "id": n.id, "title": n.title, "body": n.body, "created": n.created, "updated": n.updated,
                    "reminder": n.reminder, "repeat": n.repeat, "lead": n.lead, "images": n.images, "folder": n.folder
                })).collect::<Vec<_>>(),
            });
            Ok(json!(serde_json::to_string_pretty(&out)?))
        }
        "export_to" => {
            let text = dispatch(app, "export", json!({}))?;
            std::fs::write(s(&args, "path"), text.as_str().unwrap_or(""))?;
            Ok(json!(true))
        }
        // ---------- синхронизация ----------
        "sync_state" => {
            let st = app.store.lock().unwrap();
            let (device, name) = st.device()?;
            let peers = st.peers()?;
            drop(st);
            let pending = app.sync.pending.lock().unwrap().as_ref().map(|p| p.expires);
            Ok(json!({
                "enabled": sync_enabled(app),
                "running": server::is_running(app),
                "port": *app.sync.port.lock().unwrap(),
                "device": device,
                "name": name,
                "peers": peers,
                "status": app.sync.status.lock().unwrap().clone(),
                "busy": *app.sync.busy.lock().unwrap(),
                "codeExpires": pending,
                "hosts": pairing::local_hosts(*app.sync.port.lock().unwrap()),
            }))
        }
        "sync_enable" => {
            let on = args.get("on").and_then(|v| v.as_bool()).unwrap_or(false);
            app.store.lock().unwrap().set_setting("sync.enabled", if on { "1" } else { "0" })?;
            if on {
                server::start(app, server::default_port())?;
            } else {
                server::stop(app);
            }
            Ok(json!(true))
        }
        "sync_pair_code" => Ok(serde_json::to_value(pairing::new_code(app)?)?),
        "sync_cancel_code" => {
            pairing::cancel_code(app);
            Ok(json!(true))
        }
        "sync_accept_code" => {
            let peer = pairing::accept_code(app, &s(&args, "text"))?;
            // Сразу пробуем обменяться: это подтверждает сопряжение на той стороне.
            let app2 = app.clone();
            let device = peer.device.clone();
            std::thread::spawn(move || {
                *app2.sync.busy.lock().unwrap() = true;
                let msg = match client::sync_peer(&app2, &device) {
                    Ok(r) => format!("{}: сопряжено. Отправлено {}, получено {}", r.peer, r.sent, r.received),
                    Err(e) => {
                        let _ = app2.store.lock().unwrap().delete_peer(&device);
                        e.to_string()
                    }
                };
                app2.set_status(&msg);
                *app2.sync.busy.lock().unwrap() = false;
            });
            Ok(json!(peer.name))
        }
        "sync_now" => {
            let device = s(&args, "device");
            if device.is_empty() {
                sync_all_async(app, true);
            } else {
                let app2 = app.clone();
                std::thread::spawn(move || {
                    {
                        let mut busy = app2.sync.busy.lock().unwrap();
                        if *busy {
                            return;
                        }
                        *busy = true;
                    }
                    let msg = match client::sync_peer(&app2, &device) {
                        Ok(r) => format!(
                            "{}: отправлено {}, получено {}, картинок {}↓ {}↑{}",
                            r.peer,
                            r.sent,
                            r.received,
                            r.images_down,
                            r.images_up,
                            if r.conflicts > 0 { format!(", конфликтов {}", r.conflicts) } else { String::new() }
                        ),
                        Err(e) => e.to_string(),
                    };
                    app2.set_status(&msg);
                    *app2.sync.busy.lock().unwrap() = false;
                });
            }
            Ok(json!(true))
        }
        "sync_remove_peer" => {
            app.store.lock().unwrap().delete_peer(&s(&args, "device"))?;
            Ok(json!(true))
        }
        "device_rename" => {
            app.store.lock().unwrap().set_device_name(&s(&args, "name"))?;
            Ok(json!(true))
        }
        "folder_valid" => Ok(json!(policy::folder_valid(&s(&args, "name")))),
        // ---------- ИИ: ключ и модели ----------
        "ai_state" => {
            let cfg = chat::settings(app);
            let state = app.chat.lock().unwrap().clone();
            Ok(json!({
                "hasKey": !cfg.key.is_empty(),
                "model": cfg.model,
                "imageModel": cfg.image_model,
                "defaultImageModel": chat::DEFAULT_IMAGE_MODEL,
                "videoModel": app.store.lock().unwrap().setting("ai.videoModel").unwrap_or_default().trim().to_string(),
                "defaultVideoModel": video::DEFAULT_MODEL,
                "maxTokens": cfg.max_tokens,
                "state": state,
            }))
        }
        "ai_key_set" => {
            app.keys.write(&s(&args, "key"))?;
            Ok(json!(!app.keys.read().is_empty()))
        }
        "ai_balance" => {
            let key = app.keys.read();
            if key.is_empty() {
                return Err(anyhow!("Сначала сохраните ключ Polza.AI"));
            }
            let (amount, available) = polza::balance(&app.ai_base(), &key)?;
            Ok(json!({"amount": amount, "available": available}))
        }
        "ai_models" => {
            let key = app.keys.read();
            if key.is_empty() {
                return Err(anyhow!("Сначала сохраните ключ Polza.AI"));
            }
            Ok(json!(polza::models(&app.ai_base(), &key)?))
        }
        // ---------- видео YouTube ----------
        "video_info" => {
            let text = s(&args, "text");
            Ok(json!({"links": video::all(&text), "only_links": video::only_links(&text)}))
        }
        "video_start" => {
            let links: Vec<String> = args.get("links").and_then(|v| v.as_array()).map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default();
            video_run::start(app, links)?;
            Ok(json!(true))
        }
        "video_poll" => Ok(video_run::poll(app)),
        "video_merge_candidate" => video_run::candidate(app, i(&args, "id")),
        "video_merge" => {
            video_run::merge(app, i(&args, "src"), i(&args, "dst"))?;
            Ok(json!(true))
        }
        // ---------- ИИ-чат ----------
        "chats" => Ok(serde_json::to_value(app.store.lock().unwrap().chats()?)?),
        "chat_new" => {
            let title = s(&args, "title");
            let title = if title.trim().is_empty() { "Новый чат".to_string() } else { policy::auto_title("", &title) };
            Ok(json!(app.store.lock().unwrap().new_chat(&title)?))
        }
        "chat_rename" => {
            app.store.lock().unwrap().rename_chat(i(&args, "id"), &s(&args, "title"))?;
            Ok(json!(true))
        }
        "chat_delete" => {
            app.store.lock().unwrap().delete_chat(i(&args, "id"))?;
            app.cleanup_images();
            Ok(json!(true))
        }
        "chat_messages" => Ok(serde_json::to_value(app.store.lock().unwrap().messages(i(&args, "chat"))?)?),
        "chat_send" => {
            let chat_id = i(&args, "chat");
            let text = s(&args, "text");
            let images: Vec<String> = args
                .get("images")
                .and_then(|v| v.as_array())
                .map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect())
                .unwrap_or_default();
            // Первое сообщение даёт чату название, как на телефоне (только если отправка началась).
            let first = app.store.lock().unwrap().messages(chat_id)?.is_empty() && !text.trim().is_empty();
            let title = policy::auto_title("", &text);
            chat::send(app, chat_id, text, images)?;
            if first {
                app.store.lock().unwrap().rename_chat(chat_id, &title)?;
            }
            Ok(json!(true))
        }
        "draft_accept" => {
            let note = app.store.lock().unwrap().accept_draft(i(&args, "message"), &s(&args, "title"), &s(&args, "body"))?;
            Ok(serde_json::to_value(note)?)
        }
        "draft_dismiss" => {
            app.store.lock().unwrap().dismiss_draft(i(&args, "message"))?;
            Ok(json!(true))
        }
        "reminders_accept" => {
            let skip: Vec<usize> = args
                .get("skip")
                .and_then(|v| v.as_array())
                .map(|a| a.iter().filter_map(|x| x.as_u64().map(|n| n as usize)).collect())
                .unwrap_or_default();
            let made = app.store.lock().unwrap().accept_reminders(i(&args, "message"), &skip)?;
            Ok(json!(made.len()))
        }
        // ---------- фоновый помощник ----------
        "assist_state" => assist_state(app),
        "assist_badge" => Ok(assistant::summary(app)),
        "assist_review" => {
            assistant::review_start(app, true)?;
            Ok(json!(true))
        }
        "assist_retry_failed" => {
            app.store.lock().unwrap().retry_failed()?;
            assistant::review_start(app, false)?;
            Ok(json!(true))
        }
        "suggestion_accept" => {
            app.store.lock().unwrap().accept_suggestion(i(&args, "id"))?;
            Ok(json!(true))
        }
        "suggestion_dismiss" => {
            app.store.lock().unwrap().dismiss_suggestion(i(&args, "id"))?;
            Ok(json!(true))
        }
        "proposal_accept" => {
            let note = app.store.lock().unwrap().accept_proposal(i(&args, "id"), &s(&args, "title"), &s(&args, "body"))?;
            Ok(serde_json::to_value(note)?)
        }
        "proposal_dismiss" => {
            app.store.lock().unwrap().set_proposal_state(i(&args, "id"), "dismissed")?;
            Ok(json!(true))
        }
        "proposal_summary" => {
            assistant::summary_start(app, i(&args, "id"))?;
            Ok(json!(true))
        }
        "stack_toggle" => {
            app.store.lock().unwrap().toggle_stack(i(&args, "top"))?;
            Ok(json!(true))
        }
        "stack_release" => {
            app.store.lock().unwrap().unstack(i(&args, "top"))?;
            Ok(json!(true))
        }
        "folder_suggestion_accept" => {
            let st = app.store.lock().unwrap();
            let id = st.create_folder(&s(&args, "name"), true)?;
            if id == 0 {
                return Err(anyhow!("Недопустимое имя папки"));
            }
            let ids: Vec<i64> = args.get("notes").and_then(|v| v.as_array()).map(|a| a.iter().filter_map(|x| x.as_i64()).collect()).unwrap_or_default();
            let mut moved = 0;
            for note in ids {
                // Переносим только заметки, которые всё ещё лежат в «Все заметки».
                if st.note(note)?.map(|n| n.folder == 0).unwrap_or(false) {
                    st.move_note(note, id)?;
                    moved += 1;
                }
            }
            Ok(json!({"folder": id, "moved": moved}))
        }
        "folder_suggestion_dismiss" => {
            app.store.lock().unwrap().dismiss_topic(&s(&args, "key"))?;
            Ok(json!(true))
        }
        "checkin_set" => {
            let st = app.store.lock().unwrap();
            let on = args.get("on").and_then(|v| v.as_bool()).unwrap_or(false);
            let was = st.setting("checkin.on").as_deref() == Some("1");
            if let Some(m) = args.get("minutes").and_then(|v| v.as_i64()) {
                st.set_setting("checkin.minutes", &m.clamp(0, 24 * 60 - 1).to_string())?;
            }
            if let Some(e) = args.get("every").and_then(|v| v.as_i64()) {
                st.set_setting("checkin.every", &checkin::every(e).to_string())?;
            }
            st.set_setting("checkin.on", if on { "1" } else { "0" })?;
            if on && !was {
                // При включении начинаем с чистого листа: прежнее молчание не учитывается.
                st.set_setting("checkin.since", &now_ms().to_string())?;
                st.set_setting("checkin.missed", "0")?;
                st.set_setting("checkin.chat", "0")?;
                st.set_setting("checkin.status", "")?;
            }
            Ok(json!(true))
        }
        "checkin_now" => {
            assistant::checkin_start(app, true)?;
            Ok(json!(true))
        }
        other => Err(anyhow!("Неизвестная команда: {}", other)),
    }
}

/// Заметка для интерфейса: поля ядра плюс предложение напоминания и сведения о стопке.
fn note_json(st: &Store, n: &Note, stack: Option<&(Vec<i64>, bool)>, member_of: i64) -> Result<Value> {
    let mut v = serde_json::to_value(n)?;
    let (at, reason) = st.suggestion(n.id);
    let live = at > now_ms() && n.reminder == 0;
    v["suggestion"] = json!(if live { at } else { 0 });
    v["suggestion_reason"] = json!(if live { reason } else { String::new() });
    v["stack"] = match stack {
        Some((members, open)) => json!({"count": members.len(), "expanded": open}),
        None => Value::Null,
    };
    v["member_of"] = json!(member_of);
    Ok(v)
}

/// Сколько знаков текста уходит в список: карточке хватает начала, а полный текст открывается командой `note`.
/// Без этого окно получало бы весь текст всех заметок (на 30 000 заметок — десятки мегабайт).
const LIST_BODY_CHARS: usize = 400;

fn list_item(st: &Store, n: &Note, stack: Option<&(Vec<i64>, bool)>, member_of: i64) -> Result<Value> {
    let mut short = n.clone();
    if short.body.chars().count() > LIST_BODY_CHARS {
        short.body = short.body.chars().take(LIST_BODY_CHARS).collect();
    }
    note_json(st, &short, stack, member_of)
}

/// Список заметок: исходные заметки стопок скрыты, пока стопка свёрнута; при поиске видно всё.
fn list_notes(st: &Store, query: &str, folder: Option<i64>) -> Result<Value> {
    let notes = st.notes(query, folder)?;
    let stacks = st.stacks()?;
    let flat = !query.trim().is_empty();
    let mut member_top: HashMap<i64, i64> = HashMap::new();
    for (top, (members, _)) in &stacks {
        for m in members {
            member_top.insert(*m, *top);
        }
    }
    let mut out = Vec::new();
    for n in &notes {
        if !flat && member_top.contains_key(&n.id) {
            continue;
        }
        out.push(list_item(st, n, stacks.get(&n.id), if flat { member_top.get(&n.id).copied().unwrap_or(0) } else { 0 })?);
        if flat {
            continue;
        }
        if let Some((members, true)) = stacks.get(&n.id) {
            for m in members {
                if let Some(member) = st.note(*m)? {
                    out.push(list_item(st, &member, None, n.id)?);
                }
            }
        }
    }
    Ok(Value::Array(out))
}

/// Всё, что нужно экрану помощника, одним ответом.
fn assist_state(app: &Arc<App>) -> Result<Value> {
    let now = now_ms();
    let cfg = chat::settings(app);
    let run = app.assist.lock().unwrap().clone();
    let next = assistant::checkin_next(app, now);
    let st = app.store.lock().unwrap();
    let day = chrono::Local::now().format("%Y-%m-%d").to_string();
    let get = |k: &str, d: i64| st.setting(k).and_then(|v| v.parse::<i64>().ok()).unwrap_or(d);
    let suggestions: Vec<Value> = st
        .pending_suggestions(now)?
        .into_iter()
        .filter_map(|(id, at, reason)| st.note(id).ok().flatten().map(|n| json!({"id": id, "title": n.title, "at": at, "reason": reason})))
        .collect();
    let mut proposals = Vec::new();
    for mut p in st.pending_proposals()? {
        let titles: Vec<Value> = p["sources"]
            .as_array()
            .map(|a| a.iter().filter_map(|s| s["id"].as_i64()).filter_map(|id| st.note(id).ok().flatten().map(|n| json!({"id": id, "title": n.title}))).collect())
            .unwrap_or_default();
        p["source_notes"] = Value::Array(titles);
        proposals.push(p);
    }
    let stacked: std::collections::HashSet<i64> = st.stacks()?.into_iter().flat_map(|(top, (m, _))| m.into_iter().chain(std::iter::once(top))).collect();
    let visible: Vec<Note> = st.notes("", None)?.into_iter().filter(|n| !stacked.contains(&n.id)).collect();
    let folder_list = folders::suggest(&visible, &st.folder_keys()?, &st.dismissed_topics());
    let log: Vec<Value> = st.log_list()?.into_iter().map(|(t, text)| json!({"time": t, "text": text})).collect();
    Ok(json!({
        "ready": !cfg.key.is_empty() && !cfg.model.is_empty(),
        "running": run,
        "background": st.setting("assistant.background").as_deref() == Some("1"),
        "unlimited": st.setting("ai.unlimited").as_deref() == Some("1"),
        "used": st.budget_used(&day),
        "limit": review::DAILY_LIMIT,
        "failed": st.failed_count()?,
        "checkin": {
            "on": st.setting("checkin.on").as_deref() == Some("1"),
            "minutes": get("checkin.minutes", checkin::DEFAULT_MINUTES),
            "every": checkin::every(get("checkin.every", checkin::DEFAULT_EVERY)),
            "next": next,
            "status": st.setting("checkin.status").unwrap_or_default(),
            "paused": get("checkin.missed", 0) >= checkin::MAX_MISSED,
        },
        "suggestions": suggestions,
        "proposals": proposals,
        "folders": folder_list,
        "log": log,
    }))
}
