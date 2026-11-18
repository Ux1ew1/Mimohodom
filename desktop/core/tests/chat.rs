//! Сквозная проверка ИИ-чата против локальной заглушки Polza.AI: запрос, ответ, черновик, ошибки,
//! повтор без дубля, заметка по картинке. Платных запросов нет.

use mimo_core::api::dispatch;
use mimo_core::app::App;
use serde_json::{json, Value};
use std::sync::{Arc, Mutex};
use std::time::Duration;

fn temp(name: &str) -> std::path::PathBuf {
    let p = std::env::temp_dir().join(format!("mimo-chat-{}-{}", name, std::process::id()));
    let _ = std::fs::remove_dir_all(&p);
    p
}

/// Заглушка: отвечает на /v1/chat/completions по очереди из `replies`, запоминает тела запросов.
fn fake(replies: Vec<(u16, Value)>) -> (String, Arc<Mutex<Vec<Value>>>) {
    let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
    let base = format!("http://{}/api", server.server_addr().to_ip().unwrap());
    let seen = Arc::new(Mutex::new(Vec::new()));
    let seen2 = seen.clone();
    std::thread::spawn(move || {
        let mut queue = replies.into_iter();
        for mut req in server.incoming_requests() {
            let mut body = String::new();
            let _ = req.as_reader().read_to_string(&mut body);
            let url = req.url().to_string();
            if url.contains("models") {
                let _ = req.respond(tiny_http::Response::from_string(r#"{"data":[{"id":"b/model"},{"id":"a/model"}]}"#));
                continue;
            }
            if url.contains("balance") {
                let _ = req.respond(tiny_http::Response::from_string(r#"{"amount":"12.50","available":"10.00"}"#));
                continue;
            }
            seen2.lock().unwrap().push(serde_json::from_str(&body).unwrap_or(Value::Null));
            let (code, v) = queue.next().unwrap_or((500, json!({})));
            let _ = req.respond(tiny_http::Response::from_string(v.to_string()).with_status_code(code));
        }
    });
    (base, seen)
}

fn reply(text: &str) -> (u16, Value) {
    (200, json!({"choices": [{"message": {"content": text}, "finish_reason": "stop"}], "usage": {"total_tokens": 42, "cost_rub": "0.01"}}))
}

fn wait(app: &Arc<App>) -> Value {
    for _ in 0..200 {
        let st = dispatch(app, "ai_state", json!({})).unwrap();
        if st["state"]["running"] == 0 {
            return st;
        }
        std::thread::sleep(Duration::from_millis(25));
    }
    panic!("ответ не пришёл");
}

fn setup(name: &str, replies: Vec<(u16, Value)>) -> (Arc<App>, Arc<Mutex<Vec<Value>>>) {
    let app = App::open_memory(&temp(name)).unwrap();
    let (base, seen) = fake(replies);
    *app.ai_base.lock().unwrap() = base;
    dispatch(&app, "ai_key_set", json!({"key": "test-key"})).unwrap();
    dispatch(&app, "setting_set", json!({"key": "ai.model", "value": "test/model"})).unwrap();
    (app, seen)
}

#[test]
fn chat_answer_draft_and_retry() {
    let (app, seen) = setup(
        "answer",
        vec![
            (402, json!({})),
            reply("Нужна мука [[note:1]]. [[draft]]{\"title\":\"Блины\",\"body\":\"Мука и молоко\",\"metadata\":{\"topics\":[\"кухня\"]}}[[/draft]]"),
        ],
    );
    dispatch(&app, "save_note", json!({"title": "Рецепт блинов", "body": "Мука, молоко, яйца"})).unwrap();
    let chat = dispatch(&app, "chat_new", json!({})).unwrap().as_i64().unwrap();

    // Ошибка оплаты: текст для пользователя, сообщение пользователя сохранено один раз.
    dispatch(&app, "chat_send", json!({"chat": chat, "text": "Что нужно для блинов?"})).unwrap();
    let st = wait(&app);
    assert_eq!(st["state"]["error"], "Недостаточно средств на балансе Polza.AI.");
    assert_eq!(st["state"]["error_chat"].as_i64(), Some(chat));
    // Повтор того же текста не дублирует сообщение.
    dispatch(&app, "chat_send", json!({"chat": chat, "text": "Что нужно для блинов?"})).unwrap();
    let st = wait(&app);
    assert_eq!(st["state"]["error"], "");
    let msgs = dispatch(&app, "chat_messages", json!({"chat": chat})).unwrap();
    let msgs = msgs.as_array().unwrap();
    assert_eq!(msgs.len(), 2, "один вопрос и один ответ: {:?}", msgs);
    assert_eq!(msgs[1]["body"], "Нужна мука [[note:1]].");
    assert_eq!(msgs[1]["sources"], json!([{"id": 1, "title": "Рецепт блинов"}]));
    assert_eq!(msgs[1]["draft"]["title"], "Блины");
    assert!(st["state"]["usage"].as_str().unwrap().contains("42 токенов"));
    // Чат получил название по первому сообщению.
    let chats = dispatch(&app, "chats", json!({})).unwrap();
    assert_eq!(chats[0]["title"], "Что нужно для блинов?");

    // Запрос: системный промпт с заметкой, вопрос последним.
    let req = seen.lock().unwrap().last().cloned().unwrap();
    assert_eq!(req["model"], "test/model");
    let m = req["messages"].as_array().unwrap();
    assert!(m[0]["content"].as_str().unwrap().contains("Рецепт блинов"));
    assert_eq!(m.last().unwrap()["content"], "Что нужно для блинов?");

    // Черновик сохраняется заметкой с подсказками ИИ.
    let note = dispatch(&app, "draft_accept", json!({"message": msgs[1]["id"], "title": "Блины", "body": "Мука и молоко"})).unwrap();
    let found = dispatch(&app, "notes", json!({"query": "кухня"})).unwrap();
    assert_eq!(found[0]["id"], note["id"]);
    assert!(dispatch(&app, "draft_accept", json!({"message": msgs[1]["id"], "title": "Блины", "body": "x"})).is_err());

    // Без ключа отправить нельзя.
    dispatch(&app, "ai_key_set", json!({"key": ""})).unwrap();
    assert!(dispatch(&app, "chat_send", json!({"chat": chat, "text": "ещё"})).is_err());
}

#[test]
fn image_note_and_models() {
    let (app, seen) = setup(
        "vision",
        vec![reply("{\"title\":\"Чек из магазина\",\"summary\":\"Итого 450 ₽\",\"reminder_at\":null,\"reason\":\"\",\"metadata\":{\"topics\":[\"покупки\"]}}")],
    );
    let models = dispatch(&app, "ai_models", json!({})).unwrap();
    assert_eq!(models, json!(["a/model", "b/model"]));
    let bal = dispatch(&app, "ai_balance", json!({})).unwrap();
    assert_eq!(bal["available"], "10.00");

    let name = dispatch(&app, "image_add", json!({"data": "data:image/jpeg;base64,/9j/AAAA"})).unwrap();
    let chat = dispatch(&app, "chat_new", json!({})).unwrap().as_i64().unwrap();
    dispatch(&app, "chat_send", json!({"chat": chat, "text": "", "images": [name.clone()]})).unwrap();
    let st = wait(&app);
    assert_eq!(st["state"]["error"], "", "{}", st);
    let notes = dispatch(&app, "notes", json!({"query": ""})).unwrap();
    assert_eq!(notes[0]["title"], "Чек из магазина");
    assert_eq!(notes[0]["images"], json!([name.clone()]));
    let msgs = dispatch(&app, "chat_messages", json!({"chat": chat})).unwrap();
    assert!(msgs[0]["body"].as_str().unwrap().ends_with(&format!("[[img:{}]]", name.as_str().unwrap())));
    assert!(msgs[1]["body"].as_str().unwrap().contains("Создана заметка [[note:"));
    // Запрос с картинкой ушёл моделью для картинок по умолчанию.
    let req = seen.lock().unwrap()[0].clone();
    assert_eq!(req["model"], "google/gemini-2.5-flash");
    assert_eq!(req["messages"][1]["content"][1]["type"], "image_url");
    // Картинка чата не удаляется уборкой.
    app.cleanup_images();
    assert!(app.image_path(name.as_str().unwrap()).unwrap().exists());
}
