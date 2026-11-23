//! Компьютер без окна для проверки с другими клиентами (Java-клиент Android).
//! Печатает строки `PAIR <код>` и `READY`; затем читает команды со стандартного ввода:
//!   DUMP  — печатает JSON со всеми заметками/папками/файлами; QUIT — выход.
//! Заранее кладёт в базу две заметки, папку и «картинку».

use mimo_core::{app::App, store::SaveInput, sync::{pairing, server}};
use std::io::BufRead;

fn main() {
    let dir = std::env::args().nth(1).expect("укажите папку данных");
    let _ = std::fs::remove_dir_all(&dir);
    let app = App::open_memory(std::path::Path::new(&dir)).unwrap();
    {
        let st = app.store.lock().unwrap();
        let f = st.create_folder("Работа", false).unwrap();
        st.save_note(&SaveInput { id: None, title: "Отчёт".into(), body: "квартальный отчёт".into(), reminder: 1_900_000_000_000, repeat: 2, lead: 15, images: Some(vec!["img-one.jpg".into()]), folder: Some(f) }).unwrap();
        st.save_note(&SaveInput { id: None, title: "".into(), body: "Купить молоко и хлеб".into(), reminder: 0, repeat: 0, lead: -1, images: None, folder: None }).unwrap();
        // Стопка: «Итог» поверх «Отчёта»; чат с ссылкой на заметку и картинкой.
        let top = st.save_note(&SaveInput { id: None, title: "Итог".into(), body: "итог по отчёту".into(), reminder: 0, repeat: 0, lead: -1, images: None, folder: None }).unwrap();
        let src = st.notes("", None).unwrap().into_iter().find(|n| n.title == "Отчёт").unwrap();
        st.conn.execute("INSERT INTO note_groups(top) VALUES(?1)", rusqlite::params![top.id]).unwrap();
        st.conn.execute("INSERT INTO note_members(note,top) VALUES(?1,?2)", rusqlite::params![src.id, top.id]).unwrap();
        let chat = st.new_chat("Про отчёт").unwrap();
        st.add_message(chat, "user", "что с отчётом?", &[], None).unwrap();
        st.add_message(chat, "assistant", &format!("Отчёт квартальный [[note:{}]].\n[[img:chat-img.jpg]]", src.id), &[serde_json::json!({"id": src.id, "title": "Отчёт"})], None).unwrap();
    }
    std::fs::write(app.images_dir().join("chat-img.jpg"), [0xFFu8, 0xD8, 7, 7, 0xFF, 0xD9]).unwrap();
    std::fs::write(app.images_dir().join("img-one.jpg"), [0xFFu8, 0xD8, 1, 2, 3, 4, 5, 0xFF, 0xD9]).unwrap();
    let port = server::start(&app, 0).unwrap();
    let code = pairing::new_code(&app).unwrap();
    let uri = code.uri.split("&h=").next().unwrap().to_string() + &format!("&h=127.0.0.1:{}", port);
    println!("PAIR {}", uri);
    println!("READY");
    for line in std::io::stdin().lock().lines() {
        let line = line.unwrap_or_default();
        match line.trim() {
            "DUMP" => {
                let st = app.store.lock().unwrap();
                let notes = st.notes("", None).unwrap();
                let folders = st.folders().unwrap();
                let chats: Vec<serde_json::Value> = st.chats().unwrap().iter().map(|c| serde_json::json!({
                    "title": c.title,
                    "messages": st.messages(c.id).unwrap().iter().map(|m| serde_json::json!({"role": m.role, "body": m.body, "sources": m.sources})).collect::<Vec<_>>(),
                })).collect();
                let stacks: Vec<serde_json::Value> = st.stacks().unwrap().into_iter().map(|(top, (members, _))| {
                    let uid = |id: i64| st.note(id).unwrap().map(|n| n.uid).unwrap_or_default();
                    serde_json::json!({"top": uid(top), "members": members.into_iter().map(uid).collect::<Vec<_>>()})
                }).collect();
                let mut files: Vec<String> = std::fs::read_dir(app.images_dir()).unwrap().flatten().map(|e| e.file_name().to_string_lossy().to_string()).collect();
                files.sort();
                let out = serde_json::json!({
                    "notes": notes.iter().map(|n| serde_json::json!({"id": n.id, "uid": n.uid, "title": n.title, "body": n.body, "reminder": n.reminder, "repeat": n.repeat, "lead": n.lead, "images": n.images, "folder": n.folder, "created": n.created, "updated": n.updated})).collect::<Vec<_>>(),
                    "folders": folders.iter().map(|f| serde_json::json!({"id": f.id, "uid": f.uid, "name": f.name})).collect::<Vec<_>>(),
                    "files": files,
                    "chats": chats,
                    "stacks": stacks,
                });
                println!("DUMP {}", out);
            }
            "QUIT" => break,
            other if other.starts_with("ADD ") => {
                let rest = &other[4..];
                let (t, b) = rest.split_once('|').unwrap_or((rest, ""));
                app.store.lock().unwrap().save_note(&SaveInput { id: None, title: t.into(), body: b.into(), reminder: 0, repeat: 0, lead: -1, images: None, folder: None }).unwrap();
                println!("ADDED");
            }
            _ => {}
        }
    }
    server::stop(&app);
}
