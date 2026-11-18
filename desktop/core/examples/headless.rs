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
    }
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
                let mut files: Vec<String> = std::fs::read_dir(app.images_dir()).unwrap().flatten().map(|e| e.file_name().to_string_lossy().to_string()).collect();
                files.sort();
                let out = serde_json::json!({
                    "notes": notes.iter().map(|n| serde_json::json!({"uid": n.uid, "title": n.title, "body": n.body, "reminder": n.reminder, "repeat": n.repeat, "lead": n.lead, "images": n.images, "folder": n.folder, "created": n.created, "updated": n.updated})).collect::<Vec<_>>(),
                    "folders": folders.iter().map(|f| serde_json::json!({"id": f.id, "uid": f.uid, "name": f.name})).collect::<Vec<_>>(),
                    "files": files,
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
