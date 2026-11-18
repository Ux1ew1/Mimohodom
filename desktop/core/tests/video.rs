//! Сквозная проверка сводок видео YouTube против локальной заглушки Polza.AI: запрос с видео по
//! ссылке, повторная ссылка без запроса, общая заметка по теме, ошибки без повторов. Платных
//! запросов нет.

use mimo_core::api::dispatch;
use mimo_core::app::App;
use serde_json::{json, Value};
use std::sync::{Arc, Mutex};
use std::time::Duration;

const A: &str = "https://youtu.be/YCmvK_Up-4k?si=x";
const A_CANON: &str = "https://www.youtube.com/watch?v=YCmvK_Up-4k";
const B: &str = "https://www.youtube.com/watch?v=jNQXAC9IVRw&t=5s";
const B_CANON: &str = "https://www.youtube.com/watch?v=jNQXAC9IVRw";

fn temp(name: &str) -> std::path::PathBuf {
    let p = std::env::temp_dir().join(format!("mimo-video-{}-{}", name, std::process::id()));
    let _ = std::fs::remove_dir_all(&p);
    p
}

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
            seen2.lock().unwrap().push(serde_json::from_str(&body).unwrap_or(Value::Null));
            let (code, v) = queue.next().unwrap_or((500, json!({})));
            let _ = req.respond(tiny_http::Response::from_string(v.to_string()).with_status_code(code));
        }
    });
    (base, seen)
}

fn reply(text: &str) -> (u16, Value) {
    (200, json!({"choices": [{"message": {"content": text}, "finish_reason": "stop"}]}))
}

fn setup(name: &str, replies: Vec<(u16, Value)>, key: bool) -> (Arc<App>, Arc<Mutex<Vec<Value>>>) {
    let app = App::open_memory(&temp(name)).unwrap();
    let (base, seen) = fake(replies);
    *app.ai_base.lock().unwrap() = base;
    if key {
        dispatch(&app, "ai_key_set", json!({"key": "test-key"})).unwrap();
    }
    (app, seen)
}

/// Ждёт конца запуска и возвращает его результат.
fn finish(app: &Arc<App>) -> Value {
    for _ in 0..400 {
        let p = dispatch(app, "video_poll", json!({})).unwrap();
        if p["running"] == false && !p["outcome"].is_null() {
            return p["outcome"].clone();
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    panic!("запуск не закончился");
}

const FIRST: &str = "ЗАГОЛОВОК: Steam Link против Moonlight\nКРАТКО: Сравнение двух способов стримить игры на iPhone.\nГЛАВНОЕ:\n- Steam Link прост\n- Moonlight быстрее\nТЕМЫ: стриминг игр, Moonlight";
const SECOND: &str = "ЗАГОЛОВОК: Настройка Sunshine\nКРАТКО: Сервер Sunshine для клиента Moonlight.\nГЛАВНОЕ:\n- Sunshine ставится на ПК\nТЕМЫ: Moonlight, Sunshine";
const OVERALL: &str = "ЗАГОЛОВОК: Стриминг игр на iPhone\nОБЩЕЕ: Оба видео про Moonlight.\n- Steam Link проще";

fn note(app: &Arc<App>, id: i64) -> Value {
    dispatch(app, "note", json!({"id": id})).unwrap()
}

#[test]
fn summary_repeat_and_shared_note() {
    let (app, seen) = setup("flow", vec![reply(FIRST), reply(SECOND), reply(OVERALL)], true);

    let info = dispatch(&app, "video_info", json!({"text": format!("{}\n{}\n{}", A, B, A)})).unwrap();
    assert_eq!(info["links"], json!([A_CANON, B_CANON]));
    assert_eq!(info["only_links"], true);
    let own = dispatch(&app, "video_info", json!({"text": format!("Посмотреть позже {}", A)})).unwrap();
    assert_eq!(own["only_links"], false);

    // Ссылка уходит модели по умолчанию, ответ становится заметкой с темами для поиска.
    dispatch(&app, "video_start", json!({"links": [A]})).unwrap();
    let out = finish(&app);
    assert_eq!(out["error"], "");
    let first = out["created"][0].as_i64().unwrap();
    let req = seen.lock().unwrap()[0].clone();
    assert_eq!(req["model"], "google/gemini-2.5-flash-lite");
    assert_eq!(req["messages"][1]["content"][1]["type"], "video_url");
    assert_eq!(req["messages"][1]["content"][1]["video_url"]["url"], A_CANON);
    let n = note(&app, first);
    assert_eq!(n["title"], "Steam Link против Moonlight");
    assert!(n["body"].as_str().unwrap().ends_with(&format!("\n\nВидео: {}", A_CANON)));
    assert!(n["body"].as_str().unwrap().contains("• Moonlight быстрее"));
    assert_eq!(dispatch(&app, "notes", json!({"query": "стриминг"})).unwrap().as_array().unwrap().len(), 1);
    // Результат отдаётся один раз.
    assert!(dispatch(&app, "video_poll", json!({})).unwrap()["outcome"].is_null());

    // Та же ссылка в другом виде: запроса нет, показывается прежняя заметка.
    dispatch(&app, "video_start", json!({"links": [A_CANON]})).unwrap();
    let out = finish(&app);
    assert_eq!(out["created"], json!([]));
    assert_eq!(out["existing"], json!([first]));
    assert_eq!(seen.lock().unwrap().len(), 1, "повтор не платный");

    // Второе видео по той же теме: предлагается общая заметка, объединение — один текстовый запрос.
    dispatch(&app, "setting_set", json!({"key": "ai.videoModel", "value": "my/video-model"})).unwrap();
    dispatch(&app, "video_start", json!({"links": [B]})).unwrap();
    let out = finish(&app);
    let second = out["created"][0].as_i64().unwrap();
    assert_eq!(seen.lock().unwrap()[1]["model"], "my/video-model");
    let target = dispatch(&app, "video_merge_candidate", json!({"id": second})).unwrap();
    assert_eq!(target["id"], first);
    assert_eq!(target["title"], "Steam Link против Moonlight");

    dispatch(&app, "video_merge", json!({"src": second, "dst": first})).unwrap();
    let out = finish(&app);
    assert_eq!(out["merge_error"], "");
    assert_eq!(out["merged"], first);
    let asked = seen.lock().unwrap()[2].clone();
    let user = asked["messages"][1]["content"].as_str().unwrap();
    assert!(user.starts_with("Видео 1: Steam Link против Moonlight") && user.contains("Видео 2: Настройка Sunshine"));
    assert!(!user.contains("youtube.com"));
    assert!(note(&app, second).is_null(), "отдельная заметка удалена");
    let merged = note(&app, first);
    assert_eq!(merged["title"], "Стриминг игр на iPhone");
    let body = merged["body"].as_str().unwrap();
    assert!(body.starts_with("Общее по теме\nОба видео про Moonlight."));
    assert!(body.contains("━━ Steam Link против Moonlight ━━") && body.contains("━━ Настройка Sunshine ━━"));
    assert!(body.contains(A_CANON) && body.contains(B_CANON));
    // Темы второго видео ищутся по общей заметке; повторная ссылка на него тоже находит её.
    assert_eq!(dispatch(&app, "notes", json!({"query": "sunshine"})).unwrap().as_array().unwrap().len(), 1);
    dispatch(&app, "video_start", json!({"links": [B]})).unwrap();
    assert_eq!(finish(&app)["existing"], json!([first]));
    assert_eq!(seen.lock().unwrap().len(), 3);
    // Новых кандидатов для общей заметки у того же видео нет: оно уже внутри.
    assert!(dispatch(&app, "video_merge_candidate", json!({"id": first})).unwrap().is_null());
}

#[test]
fn errors_do_not_repeat_and_keep_notes() {
    let (app, seen) = setup("errors", vec![(402, json!({})), reply("  \n"), reply(FIRST), (500, json!({}))], true);

    // 402: понятный текст, заметки нет, вторая ссылка не обработана и не отправлена.
    dispatch(&app, "video_start", json!({"links": [A, B]})).unwrap();
    let out = finish(&app);
    assert!(out["error"].as_str().unwrap().starts_with("Недостаточно средств на балансе Polza.AI."));
    assert!(out["error"].as_str().unwrap().contains("Остальные ссылки не обработаны."));
    assert_eq!(out["created"], json!([]));
    assert_eq!(seen.lock().unwrap().len(), 1, "автоматического повтора нет");
    assert_eq!(dispatch(&app, "notes", json!({"query": ""})).unwrap().as_array().unwrap().len(), 0);

    // Пустой ответ модели.
    dispatch(&app, "video_start", json!({"links": [A]})).unwrap();
    let out = finish(&app);
    assert!(out["error"].as_str().unwrap().starts_with("Модель не вернула сводку"));
    assert_eq!(seen.lock().unwrap().len(), 2);

    // Первая из двух ссылок готова, на второй ошибка: готовое остаётся.
    dispatch(&app, "video_start", json!({"links": [A, B]})).unwrap();
    let out = finish(&app);
    assert_eq!(out["created"].as_array().unwrap().len(), 1);
    assert!(out["error"].as_str().unwrap().starts_with("Polza.AI вернул ошибку 500"));
    assert!(!out["error"].as_str().unwrap().contains("Остальные"));
    let notes = dispatch(&app, "notes", json!({"query": ""})).unwrap();
    assert_eq!(notes.as_array().unwrap().len(), 1);
}

#[test]
fn merge_failure_changes_nothing_and_limits_hold() {
    let (app, seen) = setup("merge-fail", vec![reply(FIRST), reply(SECOND), (402, json!({}))], true);
    dispatch(&app, "video_start", json!({"links": [A]})).unwrap();
    let a = finish(&app)["created"][0].as_i64().unwrap();
    dispatch(&app, "video_start", json!({"links": [B]})).unwrap();
    let b = finish(&app)["created"][0].as_i64().unwrap();
    let before = (note(&app, a), note(&app, b));

    dispatch(&app, "video_merge", json!({"src": b, "dst": a})).unwrap();
    let out = finish(&app);
    assert!(out["merge_error"].as_str().unwrap().starts_with("Недостаточно средств"));
    assert_eq!(out["merged"], 0);
    assert_eq!((note(&app, a), note(&app, b)), before, "заметки остались прежними");
    assert_eq!(seen.lock().unwrap().len(), 3);

    // Неверные запуски отказывают сразу, до запросов.
    assert!(dispatch(&app, "video_merge", json!({"src": a, "dst": a})).is_err());
    assert!(dispatch(&app, "video_start", json!({"links": []})).is_err());
    assert!(dispatch(&app, "video_start", json!({"links": ["https://example.org/x"]})).is_err());
    let many: Vec<String> = (0..11).map(|i| format!("https://youtu.be/aaaaaaaaaa{}", (b'a' + i as u8) as char)).collect();
    let e = dispatch(&app, "video_start", json!({"links": many})).unwrap_err().to_string();
    assert!(e.contains("не больше 10"), "{}", e);
    assert_eq!(seen.lock().unwrap().len(), 3, "лишних запросов нет");

    // Без ключа запуск не начинается.
    let (bare, seen) = setup("no-key", vec![], false);
    let e = dispatch(&bare, "video_start", json!({"links": [A]})).unwrap_err().to_string();
    assert!(e.contains("ключ Polza.AI"), "{}", e);
    assert!(seen.lock().unwrap().is_empty());
    let state = dispatch(&bare, "ai_state", json!({})).unwrap();
    assert_eq!(state["defaultVideoModel"], "google/gemini-2.5-flash-lite");
    assert_eq!(state["videoModel"], "");
}
