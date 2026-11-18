//! Большой объём данных: тысячи заметок с напоминаниями, поиск, список, календарь, проверка
//! напоминаний и обмен между двумя компьютерами. По умолчанию 3000 заметок (быстро); для настоящей
//! проверки: `MIMO_SCALE_N=30000 cargo test -p mimo-core --release --test scale -- --nocapture`.
//! Пороги щедрые: тест ловит провалы на порядок, а не мелкие колебания.

use mimo_core::api;
use mimo_core::app::App;
use mimo_core::reminders;
use mimo_core::store::{now_ms, SaveInput};
use mimo_core::sync::{client, pairing, server};
use serde_json::json;
use std::path::PathBuf;
use std::time::Instant;

fn temp(name: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("mimo-scale-{}-{}", name, std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    d
}

fn n_notes() -> usize {
    std::env::var("MIMO_SCALE_N").ok().and_then(|v| v.parse().ok()).unwrap_or(3000)
}

const WORDS: [&str; 16] = [
    "проект", "встреча", "купить", "отчёт", "идея", "звонок", "врач", "дорога", "ужин", "бюджет", "письмо", "пароль", "поездка", "договор", "задача", "книга",
];

fn body(i: usize) -> String {
    let mut s = String::new();
    for k in 0..60 {
        s.push_str(WORDS[(i * 7 + k * 3) % WORDS.len()]);
        s.push(' ');
        if k % 12 == 11 {
            s.push('\n');
        }
    }
    s.push_str(&format!("уникальная{}", i));
    s
}

fn timed<T>(label: &str, f: impl FnOnce() -> T) -> (T, f64) {
    let t = Instant::now();
    let v = f();
    let ms = t.elapsed().as_secs_f64() * 1000.0;
    println!("{:<44} {:>9.1} мс", label, ms);
    (v, ms)
}

#[test]
fn many_notes() {
    let n = n_notes();
    println!("заметок: {}", n);
    let a = App::open_memory(&temp("a")).unwrap();
    let now = now_ms();

    let (_, _) = timed("создание заметок", || {
        let st = a.store.lock().unwrap();
        for i in 0..n {
            // Каждая 20-я с напоминанием, каждая 100-я — серия; время — в ближайшие два месяца.
            let (reminder, repeat) = if i % 20 == 0 { (now + ((i as i64 % 60) + 1) * 86_400_000 + (i as i64 % 24) * 3_600_000, if i % 100 == 0 { 2 } else { 0 }) } else { (0, 0) };
            st.save_note(&SaveInput { id: None, title: format!("Заметка {}", i), body: body(i), reminder, repeat, lead: if i % 40 == 0 { 15 } else { -1 }, images: None, folder: None }).unwrap();
        }
    });

    let (found, ms) = timed("поиск по слову", || api::dispatch(&a, "notes", json!({"query": "уникальная1234", "folder": null})).unwrap());
    assert!(!found.as_array().unwrap().is_empty());
    assert!(ms < 1500.0, "поиск слишком медленный: {} мс", ms);
    let (_, ms) = timed("поиск по частому слову", || api::dispatch(&a, "notes", json!({"query": "проект", "folder": null})).unwrap());
    assert!(ms < 4000.0, "поиск по частому слову: {} мс", ms);

    let (all, ms) = timed("весь список заметок (как в окне)", || api::dispatch(&a, "notes", json!({"query": "", "folder": null})).unwrap());
    assert_eq!(all.as_array().unwrap().len(), n);
    // В список уходит только начало текста: полный открывается командой `note`.
    assert!(all.as_array().unwrap().iter().all(|v| v["body"].as_str().unwrap().chars().count() <= 400));
    assert!(ms < 6000.0, "список слишком медленный: {} мс", ms);

    let month = 31 * 86_400_000;
    let (cal, ms) = timed("календарь на месяц", || reminders::local_calendar(&a, now, now + month).unwrap());
    assert!(!cal.is_empty());
    assert!(ms < 800.0, "календарь: {} мс", ms);

    let (_, ms) = timed("проверка напоминаний (первый проход)", || reminders::tick(&a));
    assert!(ms < 1500.0, "проверка напоминаний: {} мс", ms);
    let (_, ms) = timed("проверка напоминаний (повторный проход)", || reminders::tick(&a));
    assert!(ms < 1500.0, "повторная проверка напоминаний: {} мс", ms);

    // Обмен: второй компьютер забирает всё по сети.
    let b = App::open_memory(&temp("b")).unwrap();
    let port = server::start(&a, 0).unwrap();
    let code = pairing::new_code(&a).unwrap();
    let uri = code.uri.split("&h=").next().unwrap().to_string() + &format!("&h=127.0.0.1:{}", port);
    let peer = pairing::accept_code(&b, &uri).unwrap();
    let (r, ms) = timed("первый обмен (всё на пустой компьютер)", || client::sync_peer(&b, &peer.device).unwrap());
    assert_eq!(r.received as usize, n, "{:?}", r);
    assert!(ms < 60_000.0, "обмен: {} мс", ms);
    let (r2, ms) = timed("повторный обмен (изменений нет)", || client::sync_peer(&b, &peer.device).unwrap());
    assert_eq!((r2.sent, r2.received), (0, 0), "{:?}", r2);
    assert!(ms < 3000.0, "пустой обмен: {} мс", ms);
    // Одна правка в середине большой базы доходит одним коротким обменом.
    {
        let st = a.store.lock().unwrap();
        let mut one = st.note(((n / 2) as i64).max(1)).unwrap().unwrap();
        one.body.push_str(" правка");
        st.save_note(&SaveInput { id: Some(one.id), title: one.title.clone(), body: one.body.clone(), reminder: one.reminder, repeat: one.repeat, lead: one.lead, images: None, folder: None }).unwrap();
    }
    let (r3, ms) = timed("обмен после одной правки", || client::sync_peer(&b, &peer.device).unwrap());
    assert_eq!(r3.received, 1, "{:?}", r3);
    assert!(ms < 3000.0, "обмен одной правки: {} мс", ms);
}
