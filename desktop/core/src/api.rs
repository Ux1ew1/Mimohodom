//! Единая точка команд для интерфейса (Tauri) и отладочного HTTP-сервера: `dispatch(cmd, args)`.

use crate::ai::{chat, polza};
use crate::app::App;
use crate::policy;
use crate::store::{now_ms, random_hex, SaveInput};
use crate::sync::{client, pairing, server};
use anyhow::{anyhow, Result};
use base64::Engine;
use serde_json::{json, Value};
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
            Ok(serde_json::to_value(st.notes(&q, folder)?)?)
        }
        "note" => {
            let st = app.store.lock().unwrap();
            Ok(serde_json::to_value(st.note(i(&args, "id"))?)?)
        }
        "save_note" => {
            let input: SaveInput = serde_json::from_value(args)?;
            let note = app.store.lock().unwrap().save_note(&input)?;
            app.cleanup_images();
            Ok(serde_json::to_value(note)?)
        }
        "delete_note" => {
            app.store.lock().unwrap().delete_note(i(&args, "id"))?;
            app.cleanup_images();
            Ok(json!(true))
        }
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
        other => Err(anyhow!("Неизвестная команда: {}", other)),
    }
}
