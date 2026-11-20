//! Сквозная проверка фонового помощника против локальной заглушки Polza.AI: проверка заметок,
//! предложения, стопка с выжимкой, дневной лимит, ошибки и ручной повтор, вопрос о планах и
//! диалог, начатый помощником. Платных запросов нет.

use mimo_core::api::dispatch;
use mimo_core::app::App;
use serde_json::{json, Value};
use std::sync::{Arc, Mutex};
use std::time::Duration;

fn temp(name: &str) -> std::path::PathBuf {
    let p = std::env::temp_dir().join(format!("mimo-assist-{}-{}", name, std::process::id()));
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
    (200, json!({"choices": [{"message": {"content": text}, "finish_reason": "stop"}], "usage": {"total_tokens": 42}}))
}

fn setup(name: &str, replies: Vec<(u16, Value)>) -> (Arc<App>, Arc<Mutex<Vec<Value>>>) {
    let app = App::open_memory(&temp(name)).unwrap();
    let (base, seen) = fake(replies);
    *app.ai_base.lock().unwrap() = base;
    dispatch(&app, "ai_key_set", json!({"key": "test-key"})).unwrap();
    dispatch(&app, "setting_set", json!({"key": "ai.model", "value": "test/model"})).unwrap();
    (app, seen)
}

fn state(app: &Arc<App>) -> Value {
    dispatch(app, "assist_state", json!({})).unwrap()
}

fn wait_idle(app: &Arc<App>) -> Value {
    for _ in 0..300 {
        let st = state(app);
        let r = &st["running"];
        if r["review_running"] == false && r["summary_running"] == 0 && r["checkin_running"] == false {
            return st;
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    panic!("помощник не закончил");
}

fn note(app: &Arc<App>, title: &str, body: &str) -> i64 {
    dispatch(app, "save_note", json!({"title": title, "body": body})).unwrap()["id"].as_i64().unwrap()
}

fn last_log(st: &Value) -> String {
    st["log"][0]["text"].as_str().unwrap_or("").to_string()
}

#[test]
fn review_suggestions_stack_and_summary() {
    let when = chrono::Local::now() + chrono::Duration::days(4);
    let at = when.format("%Y-%m-%dT10:00:00%:z").to_string();
    let review = json!({
        "notes": [
            {"id": 1, "title": "Сдать анализы", "reminder_at": at, "reason": "в субботу в 10:00", "metadata": {"topics": ["здоровье"], "aliases": ["поликлиника"]}},
            {"id": 2, "title": "Лишнее", "metadata": {"topics": ["здоровье"]}},
        ],
        "suggestions": [
            {"type": "task", "sources": [2], "reason": "возможно, ещё не записались", "title": "Записаться к врачу", "body": "Записаться к терапевту"},
            {"type": "link", "sources": [1, 2], "reason": "Анализы и приём врача — один поход"},
        ],
    });
    let summary = json!({"title": "Здоровье", "body": "Анализы и приём терапевта."});
    let (app, seen) = setup("flow", vec![reply(&review.to_string()), reply(&summary.to_string())]);
    let a = note(&app, "", "в субботу в 10:00 сдать анализы в клинике");
    let b = note(&app, "Врач", "записаться к терапевту про анализы");
    assert_eq!((a, b), (1, 2));

    // Ключа нет — запуск невозможен и понятно почему.
    let bare = App::open_memory(&temp("bare")).unwrap();
    assert!(dispatch(&bare, "assist_review", json!({})).unwrap_err().to_string().contains("ключ"));

    dispatch(&app, "assist_review", json!({})).unwrap();
    let st = wait_idle(&app);
    assert!(last_log(&st).starts_with("Просмотрено записей: 2 из 2. Переименовано: 1. Новых предложений напоминаний: 1."), "{}", last_log(&st));
    assert_eq!(st["used"], 1);

    // Запрос содержит оба раздела и тексты заметок как данные.
    let sent = seen.lock().unwrap()[0].clone();
    let system = sent["messages"][0]["content"].as_str().unwrap();
    assert!(system.contains("Ты менеджер заметок") && system.contains("Выполни ОБЕ проверки"));
    assert!(sent["messages"][1]["content"].as_str().unwrap().contains("сдать анализы"));

    // Название, подсказки поиска и предложение напоминания применены.
    let n1 = dispatch(&app, "note", json!({"id": a})).unwrap();
    assert_eq!(n1["title"], "Сдать анализы");
    assert!(n1["suggestion"].as_i64().unwrap() > 0);
    assert_eq!(n1["reminder"], 0, "напоминание не ставится без подтверждения");
    assert_eq!(dispatch(&app, "notes", json!({"query": "поликлиника"})).unwrap().as_array().unwrap().len(), 1);
    assert_eq!(st["suggestions"].as_array().unwrap().len(), 1);
    let types: Vec<&str> = st["proposals"].as_array().unwrap().iter().map(|p| p["type"].as_str().unwrap()).collect();
    assert!(types.contains(&"task") && types.contains(&"link"), "{:?}", types);
    assert_eq!(st["folders"][0]["name"], "Здоровье", "две заметки с одной темой — предложение папки");

    // Папка по теме: создаётся только по подтверждению и забирает заметки.
    let moved = dispatch(&app, "folder_suggestion_accept", json!({"name": "Здоровье", "notes": [a, b]})).unwrap();
    assert_eq!(moved["moved"], 2);
    assert!(state(&app)["folders"].as_array().unwrap().is_empty());

    // Принять предложение напоминания.
    dispatch(&app, "suggestion_accept", json!({"id": a})).unwrap();
    assert!(dispatch(&app, "note", json!({"id": a})).unwrap()["reminder"].as_i64().unwrap() > 0);

    // Связь → выжимка → стопка.
    let link = state(&app)["proposals"].as_array().unwrap().iter().find(|p| p["type"] == "link").cloned();
    // Заметка a изменилась (поставили напоминание): связь устарела, новая проверка её пересоздаст.
    assert!(link.is_none(), "предложение с изменённым источником скрыто");
    let st = state(&app);
    assert_eq!(st["proposals"].as_array().unwrap().len(), 1, "дело про врача (источник b) осталось");

    dispatch(&app, "assist_review", json!({})).unwrap();
    // Ответов в заглушке больше нет: ошибка сервера попадает в историю и в очередь повтора.
    let st = wait_idle(&app);
    assert!(last_log(&st).starts_with("Проверка не завершена."), "{}", last_log(&st));
}

#[test]
fn summary_flow_and_stack_listing() {
    let when = chrono::Local::now() + chrono::Duration::days(4);
    let _ = when;
    // Первая проверка: модель сама называет две записи про борщ — стопку собираем из них, а не из всей пачки.
    let review = json!({"notes": [{"id": 1}, {"id": 2}], "suggestions": [{"type": "link", "sources": [1, 2], "reason": "Обе про борщ"}]});
    let summary = json!({"title": "Борщ", "body": "Свёкла, капуста, мясо, морковь."});
    let (app, _) = setup("stack", vec![reply(&review.to_string()), reply(&summary.to_string())]);
    let a = note(&app, "Борщ", "свёкла капуста мясо варить час");
    let b = note(&app, "Борщ второй", "свёкла капуста морковь варить");
    let c = note(&app, "Шины", "записаться на шиномонтаж");
    dispatch(&app, "assist_review", json!({})).unwrap();
    let st = wait_idle(&app);
    let link = st["proposals"].as_array().unwrap().iter().find(|p| p["type"] == "link").cloned().expect("связь предложена");
    assert!(link["source_notes"].as_array().unwrap().len() >= 2);
    let id = link["id"].as_i64().unwrap();
    // Заметка про шины в пачку не попала: темы разные.
    assert!(!link["sources"].as_array().unwrap().iter().any(|s| s["id"] == c));

    // Принять связь без выжимки можно, только если пользователь сам написал текст.
    assert!(dispatch(&app, "proposal_accept", json!({"id": id, "title": "Борщ", "body": ""})).is_err());
    dispatch(&app, "proposal_summary", json!({"id": id})).unwrap();
    let st = wait_idle(&app);
    let p = st["proposals"].as_array().unwrap().iter().find(|p| p["id"] == id).unwrap().clone();
    assert_eq!(p["_summary"]["ready"], true);
    assert_eq!(p["_summary"]["title"], "Борщ");
    assert!(last_log(&st).starts_with("Выжимка готова"));

    let top = dispatch(&app, "proposal_accept", json!({"id": id, "title": p["_summary"]["title"], "body": p["_summary"]["body"]})).unwrap();
    let top_id = top["id"].as_i64().unwrap();
    // Список показывает стопку свёрнутой: исходники скрыты, у верхней заметки — счётчик.
    let list = dispatch(&app, "notes", json!({})).unwrap();
    let ids: Vec<i64> = list.as_array().unwrap().iter().map(|n| n["id"].as_i64().unwrap()).collect();
    assert!(ids.contains(&top_id) && ids.contains(&c) && !ids.contains(&a) && !ids.contains(&b), "{:?}", ids);
    let top_row = list.as_array().unwrap().iter().find(|n| n["id"] == top_id).unwrap();
    assert_eq!(top_row["stack"]["count"], 2);
    // Раскрыть стопку: исходники идут сразу под верхней заметкой.
    dispatch(&app, "stack_toggle", json!({"top": top_id})).unwrap();
    let list = dispatch(&app, "notes", json!({})).unwrap();
    let rows = list.as_array().unwrap();
    let pos = rows.iter().position(|n| n["id"] == top_id).unwrap();
    assert_eq!(rows[pos + 1]["member_of"], top_id);
    assert_eq!(rows[pos + 2]["member_of"], top_id);
    // Поиск показывает всё плоско, в том числе исходники.
    assert_eq!(dispatch(&app, "notes", json!({"query": "морковь"})).unwrap().as_array().unwrap().len(), 2);
    // Разбор стопки возвращает исходники в список.
    dispatch(&app, "stack_release", json!({"top": top_id})).unwrap();
    let ids: Vec<i64> = dispatch(&app, "notes", json!({})).unwrap().as_array().unwrap().iter().map(|n| n["id"].as_i64().unwrap()).collect();
    assert!(ids.contains(&a) && ids.contains(&b));
}

#[test]
fn limit_errors_and_retry() {
    let review = json!({"notes": [{"id": 1}], "suggestions": []});
    let (app, seen) = setup("limit", vec![(402, json!({})), reply(&review.to_string())]);
    note(&app, "Одна", "заметка для проверки");
    dispatch(&app, "assist_review", json!({})).unwrap();
    let st = wait_idle(&app);
    assert!(last_log(&st).contains("Недостаточно средств на балансе Polza.AI."), "{}", last_log(&st));
    assert_eq!(st["failed"], 1);
    // Автоматически (без ручного запуска) платный запрос после ошибки не повторяется.
    dispatch(&app, "assist_retry_failed", json!({})).unwrap();
    let st = wait_idle(&app);
    assert_eq!(seen.lock().unwrap().len(), 2, "повтор — только по команде пользователя");
    assert!(last_log(&st).starts_with("Просмотрено записей: 1 из 1."), "{}", last_log(&st));
    assert_eq!(st["failed"], 0);
    // Дневной лимит: четыре запроса уже потрачены.
    let day = chrono::Local::now().format("%Y-%m-%d").to_string();
    dispatch(&app, "setting_set", json!({"key": "analysis.day", "value": day})).unwrap();
    dispatch(&app, "setting_set", json!({"key": "analysis.count", "value": "4"})).unwrap();
    dispatch(&app, "assist_review", json!({})).unwrap();
    let st = wait_idle(&app);
    assert!(last_log(&st).starts_with("Дневной лимит"), "{}", last_log(&st));
    assert_eq!(seen.lock().unwrap().len(), 2, "при исчерпанном лимите запроса нет");
    // Лимит можно снять.
    dispatch(&app, "setting_set", json!({"key": "ai.unlimited", "value": "1"})).unwrap();
    assert_eq!(state(&app)["unlimited"], true);
}

#[test]
fn checkin_question_and_followup_chat() {
    let (app, seen) = setup(
        "checkin",
        vec![
            reply("Здравствуйте! Во сколько у вас завтра встреча?"),
            reply("Понял. Записываю.\n[[memo]]Встреча завтра в 15:00[[/memo]]\n[[draft]]{\"title\":\"Встреча\",\"body\":\"Завтра в 15:00 встреча\"}[[/draft]]"),
        ],
    );
    note(&app, "Встреча", "завтра встреча с заказчиком");
    dispatch(&app, "checkin_set", json!({"on": true, "minutes": 18 * 60, "every": 1})).unwrap();
    let st = state(&app);
    assert_eq!(st["checkin"]["on"], true);
    assert!(st["checkin"]["next"].as_i64().unwrap() > 0);

    dispatch(&app, "checkin_now", json!({})).unwrap();
    let st = wait_idle(&app);
    assert!(st["checkin"]["status"].as_str().unwrap().starts_with("Последний вопрос:"));
    let sent = seen.lock().unwrap()[0].clone();
    assert!(sent["messages"][0]["content"].as_str().unwrap().contains("ОДНО короткое сообщение"));
    let data = sent["messages"][1]["content"].as_str().unwrap();
    assert!(data.contains("Недавние заметки") && data.contains("Встреча: завтра встреча с заказчиком"));

    let chats = dispatch(&app, "chats", json!({})).unwrap();
    let chat = chats[0]["id"].as_i64().unwrap();
    let msgs = dispatch(&app, "chat_messages", json!({"chat": chat})).unwrap();
    assert_eq!(msgs[0]["role"], "assistant");
    assert_eq!(msgs[0]["body"], "Здравствуйте! Во сколько у вас завтра встреча?");

    // Ответ пользователя: помощник ведёт диалог по своему промпту и сам записывает итог.
    dispatch(&app, "chat_send", json!({"chat": chat, "text": "В 15:00"})).unwrap();
    for _ in 0..300 {
        if dispatch(&app, "ai_state", json!({})).unwrap()["state"]["running"] == 0 {
            break;
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    let followup = seen.lock().unwrap()[1].clone();
    assert!(followup["messages"][0]["content"].as_str().unwrap().contains("Этот диалог начал ты сам"));
    let msgs = dispatch(&app, "chat_messages", json!({"chat": chat})).unwrap();
    let last = msgs.as_array().unwrap().last().unwrap().clone();
    assert!(last["body"].as_str().unwrap().contains("Заметка создана"), "{}", last["body"]);
    assert!(last["draft"].is_null() || last["draft"] == "", "итог записан сам, предложения нет");
    assert!(last["body"].as_str().unwrap().contains("[[memo]]Встреча завтра в 15:00[[/memo]]"), "память диалога сохранена в тексте");
    let found = dispatch(&app, "notes", json!({"query": "15:00"})).unwrap();
    assert!(found.as_array().unwrap().iter().any(|n| n["title"] == "Встреча" && n["body"] == "Завтра в 15:00 встреча"));
    // Пока пользователь отвечает, помощник не молчит; выключение снимает расписание.
    dispatch(&app, "checkin_set", json!({"on": false})).unwrap();
    assert_eq!(state(&app)["checkin"]["next"], -1);
}
