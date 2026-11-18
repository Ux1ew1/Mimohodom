//! Запуск сводок видео YouTube: по одной заметке на ролик, по очереди, и объединение со сводкой по
//! той же теме. Модель сама смотрит видео по ссылке. Автоматических повторов нет: запрос платный,
//! повтор только вручную. Дневной лимит проверок помощника на видео не распространяется, как и на
//! телефоне (порт `VideoRunner`). База блокируется только на чтение и запись, не на время сети.

use super::polza::{self, AiError};
use super::{review, video};
use crate::app::App;
use anyhow::{anyhow, Result};
use serde_json::{json, Value};
use std::sync::Arc;

/// Ожидание ответа: длинное видео модель обрабатывает дольше обычного запроса.
const TIMEOUT_SECS: u64 = 240;

#[derive(Default, Clone, serde::Serialize)]
pub struct VideoState {
    pub running: bool,
    /// `summary` — сводки роликов, `merge` — объединение с общей заметкой.
    pub kind: String,
    pub total: usize,
    pub done: usize,
    /// Результат последнего запуска, пока интерфейс его не забрал.
    pub created: Vec<i64>,
    pub existing: Vec<i64>,
    pub error: String,
    pub merged: i64,
    pub merge_error: String,
}

pub fn model(app: &App) -> String {
    let m = app.store.lock().unwrap().setting("ai.videoModel").unwrap_or_default().trim().to_string();
    if m.is_empty() {
        video::DEFAULT_MODEL.to_string()
    } else {
        m
    }
}

const NEED_KEY: &str = "Сохраните ключ Polza.AI в настройках ИИ.";

/// Запускает сводки по ссылкам. Возвращает ошибку, только если запуск невозможен.
pub fn start(app: &Arc<App>, links: Vec<String>) -> Result<()> {
    let key = app.keys.read();
    if key.is_empty() {
        return Err(anyhow!(NEED_KEY));
    }
    let mut queue: Vec<String> = Vec::new();
    for link in links {
        let id = video::link_id(&link);
        let canonical = video::canonical(&id);
        if !id.is_empty() && !queue.contains(&canonical) {
            queue.push(canonical);
        }
    }
    if queue.is_empty() {
        return Err(anyhow!("Нет ссылок на видео YouTube."));
    }
    if queue.len() > video::MAX_LINKS {
        return Err(anyhow!("За один раз можно обработать не больше {} видео. Ссылок: {}.", video::MAX_LINKS, queue.len()));
    }
    {
        let mut s = app.video.lock().unwrap();
        if s.running {
            return Err(anyhow!("Сводка видео уже готовится. Подождите её завершения."));
        }
        *s = VideoState { running: true, kind: "summary".into(), total: queue.len(), ..Default::default() };
    }
    let model = model(app);
    let app = app.clone();
    std::thread::spawn(move || {
        for link in &queue {
            let old = app.store.lock().unwrap().note_id_containing(link);
            if old > 0 {
                let mut s = app.video.lock().unwrap();
                s.existing.push(old);
                s.done += 1;
                continue;
            }
            match summarize(&app, link, &key, &model) {
                Ok(id) => {
                    let mut s = app.video.lock().unwrap();
                    s.created.push(id);
                    s.done += 1;
                }
                Err(e) => {
                    let mut s = app.video.lock().unwrap();
                    s.error = summary_error(&e, s.done, s.total);
                    break;
                }
            }
        }
        app.video.lock().unwrap().running = false;
    });
    Ok(())
}

fn summarize(app: &Arc<App>, link: &str, key: &str, model: &str) -> Result<i64> {
    let response = polza::complete(&app.ai_base(), key, &video::payload(model, link), TIMEOUT_SECS)?;
    let summary = video::parse_response(&response, link)?;
    let note = app
        .store
        .lock()
        .unwrap()
        .save_video(&summary.title, &summary.body, &summary.metadata)
        .map_err(|_| review::failure("Не удалось сохранить сводку видео. Запрос мог быть оплачен; повтор — вручную."))?;
    Ok(note.id)
}

fn summary_error(e: &anyhow::Error, done: usize, total: usize) -> String {
    let rest = if total.saturating_sub(done) > 1 { " Остальные ссылки не обработаны." } else { "" };
    match e.downcast_ref::<AiError>() {
        Some(ai) if ai.timeout => format!(
            "Модель не успела ответить: видео слишком длинное или сервис занят. Запрос мог быть оплачен; повтор — вручную.{}",
            rest
        ),
        Some(ai) => format!("{}{}", ai.message, rest),
        None => format!(
            "Не удалось сделать сводку видео. Проверьте интернет, баланс и поддержку видео выбранной моделью. Запрос мог быть оплачен; повтор — вручную.{}",
            rest
        ),
    }
}

/// Добавляет сводку видео `src` разделом в заметку `dst` и обновляет общую сводку одним текстовым
/// запросом. Если запрос не удался, заметки остаются прежними.
pub fn merge(app: &Arc<App>, src: i64, dst: i64) -> Result<()> {
    let key = app.keys.read();
    if key.is_empty() {
        return Err(anyhow!(NEED_KEY));
    }
    if src == dst {
        return Err(anyhow!("Нельзя объединить заметку с самой собой."));
    }
    {
        let mut s = app.video.lock().unwrap();
        if s.running {
            return Err(anyhow!("Сводка видео уже готовится. Подождите её завершения."));
        }
        *s = VideoState { running: true, kind: "merge".into(), total: 1, ..Default::default() };
    }
    let model = model(app);
    let app = app.clone();
    std::thread::spawn(move || {
        let result = merge_work(&app, src, dst, &key, &model);
        let mut s = app.video.lock().unwrap();
        match result {
            Ok(()) => {
                s.merged = dst;
                s.done = 1;
            }
            Err(e) => s.merge_error = merge_error(&e),
        }
        s.running = false;
    });
    Ok(())
}

fn merge_work(app: &Arc<App>, src: i64, dst: i64, key: &str, model: &str) -> Result<()> {
    let (into, from) = {
        let st = app.store.lock().unwrap();
        (st.note(dst)?, st.note(src)?)
    };
    let (Some(into), Some(from)) = (into, from) else {
        return Err(review::failure("Заметка уже удалена. Объединять нечего."));
    };
    let (a, b) = (video::split(&into), video::split(&from));
    let mut all = a.sections.clone();
    all.extend(b.sections);
    if all.len() > video::MAX_SECTIONS {
        return Err(review::failure("В заметке уже слишком много видео."));
    }
    let response = polza::complete(&app.ai_base(), key, &video::merge_payload(model, &all), TIMEOUT_SECS)?;
    let overall = video::merge_parse_response(&response)?;
    // Своё название заметки не трогаем: заменяем его, только если оно осталось от видео.
    let ours = a.overall.is_empty() && a.sections.first().map(|s| s.title == into.title).unwrap_or(false);
    let title = if ours && !overall.title.is_empty() { overall.title.clone() } else { into.title.clone() };
    let done = app
        .store
        .lock()
        .unwrap()
        .merge_video(src, from.updated, dst, into.updated, &title, &video::compose(&overall.text, &all))
        .map_err(|_| review::failure("Не удалось сохранить общую заметку. Заметки остались отдельными."))?;
    if !done {
        return Err(review::failure("Заметка изменилась во время объединения. Заметки остались прежними."));
    }
    Ok(())
}

fn merge_error(e: &anyhow::Error) -> String {
    match e.downcast_ref::<AiError>() {
        Some(ai) if ai.timeout => "Модель не успела ответить. Запрос мог быть оплачен; повтор — вручную. Заметки остались отдельными.".into(),
        Some(ai) => ai.message.clone(),
        None => "Не удалось объединить заметки. Проверьте интернет и баланс. Запрос мог быть оплачен; повтор — вручную. Заметки остались отдельными.".into(),
    }
}

/// Самая похожая заметка с видео для только что созданной сводки или `null`.
pub fn candidate(app: &App, id: i64) -> Result<Value> {
    let st = app.store.lock().unwrap();
    let Some(fresh) = st.note(id)? else { return Ok(Value::Null) };
    let pool = st.video_notes()?;
    Ok(match video::best(&fresh, &pool) {
        Some(target) => json!({"id": target.id, "title": target.title, "fresh_title": fresh.title}),
        None => Value::Null,
    })
}

/// Состояние для интерфейса. Когда запуск закончился, результат отдаётся один раз и очищается,
/// чтобы его не показать дважды.
pub fn poll(app: &App) -> Value {
    let mut s = app.video.lock().unwrap();
    let mut out = json!({"running": s.running, "kind": s.kind, "total": s.total, "done": s.done, "outcome": null});
    let has_result = !s.created.is_empty() || !s.existing.is_empty() || !s.error.is_empty() || s.merged != 0 || !s.merge_error.is_empty();
    if !s.running && has_result {
        out["outcome"] = json!({
            "kind": s.kind,
            "created": s.created,
            "existing": s.existing,
            "error": s.error,
            "merged": s.merged,
            "merge_error": s.merge_error,
        });
        *s = VideoState::default();
    }
    out
}
