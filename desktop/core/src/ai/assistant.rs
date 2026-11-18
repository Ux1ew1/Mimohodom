//! Запуск фонового помощника: проверка заметок, выжимка для стопки, вопросы о планах. Один запрос
//! за раз на каждую задачу, платный запрос после ошибки автоматически не повторяется, база
//! блокируется только на чтение и запись, не на время сети (порт `BackgroundAnalysis`, `ManagerJob`,
//! `SummaryRunner`, `CheckInJob`).

use super::polza::{self, AiError};
use super::{chat, checkin, review, text};
use crate::app::App;
use crate::policy;
use crate::store::now_ms;
use anyhow::{anyhow, Result};
use chrono::Local;
use serde_json::{json, Value};
use std::sync::Arc;

/// Как часто фоновая проверка берёт новые и изменённые заметки.
pub const REVIEW_EVERY_MS: i64 = 6 * 3_600_000;

#[derive(Default, Clone, serde::Serialize)]
pub struct AssistState {
    pub review_running: bool,
    /// Номер предложения, для которого готовится выжимка (0 — нет).
    pub summary_running: i64,
    pub summary_error: String,
    pub summary_error_id: i64,
    pub checkin_running: bool,
}

fn today() -> String {
    Local::now().format("%Y-%m-%d").to_string()
}

fn describe(e: &anyhow::Error) -> String {
    match e.downcast_ref::<AiError>() {
        Some(ai) => ai.message.clone(),
        None => {
            let m = e.to_string();
            if m.is_empty() {
                "Не удалось завершить запрос или сохранить результат.".into()
            } else {
                m
            }
        }
    }
}

fn log(app: &App, text: &str) {
    let _ = app.store.lock().unwrap().log(text);
}

fn ready(app: &App) -> Option<chat::Settings> {
    let cfg = chat::settings(app);
    if cfg.key.is_empty() || cfg.model.is_empty() {
        None
    } else {
        Some(cfg)
    }
}

pub const NEED_KEY: &str = "Для проверки сохраните ключ Polza.AI и модель в настройках ИИ.";

// ---------- проверка заметок ----------

/// Запускает проверку в фоне. `manual` берёт и уже просмотренные заметки (по кругу, начиная с
/// самых давних). Возвращает ошибку, только если запуск невозможен.
pub fn review_start(app: &Arc<App>, manual: bool) -> Result<()> {
    let Some(cfg) = ready(app) else {
        if manual {
            log(app, NEED_KEY);
        }
        return Err(anyhow!(NEED_KEY));
    };
    {
        let mut s = app.assist.lock().unwrap();
        if s.review_running {
            return Err(anyhow!("Проверка уже идёт"));
        }
        s.review_running = true;
    }
    let app = app.clone();
    std::thread::spawn(move || {
        if let Err(e) = review_work(&app, manual, &cfg) {
            log(&app, &format!("Проверка не завершена. {} Нажмите «Повторить после ошибок»; запрос мог быть оплачен.", describe(&e)));
        }
        app.assist.lock().unwrap().review_running = false;
    });
    Ok(())
}

fn review_work(app: &Arc<App>, manual: bool, cfg: &chat::Settings) -> Result<()> {
    let (batch, total, failed, unlimited) = {
        let st = app.store.lock().unwrap();
        (st.review_batch(manual)?, st.count_notes()?, st.failed_count()?, st.setting("ai.unlimited").as_deref() == Some("1"))
    };
    if batch.is_empty() {
        // Фоновый пустой запуск ничего не пишет в историю: он случается каждые шесть часов.
        if manual {
            log(
                app,
                if total == 0 {
                    "Нет заметок для проверки."
                } else if failed > 0 {
                    "Все доступные записи ожидают ручного повтора после ошибки. Нажмите «Повторить после ошибок»."
                } else {
                    "Нет доступных заметок для проверки. Стопки и их исходники уже просмотрены и исключены из анализа."
                },
            );
        }
        return Ok(());
    }
    {
        let st = app.store.lock().unwrap();
        if !st.reserve_budget(&today(), unlimited)? {
            st.log("Дневной лимит: 4 пакета. Следующая проверка — завтра. Лимит можно снять в настройках помощника.")?;
            return Ok(());
        }
        st.claim_review(&batch)?;
    }
    log(app, &format!("Проверяем {} из {} заметок: названия, напоминания, связи и объединения…", batch.len(), total));
    let now = now_ms();
    let payload = review::review_payload(&batch, &cfg.model, now);
    let response = polza::complete(&app.ai_base(), &cfg.key, &payload, 120)?;
    let (model_proposals, results) = review::review_parse(&response, &batch, now_ms())?;
    let proposals = review::proposals(&batch, model_proposals)?;
    let (outcome, merges, failed_left) = {
        let st = app.store.lock().unwrap();
        let outcome = st.finish_review(&batch, proposals, &results)?;
        let merges = st.pending_proposals()?.iter().filter(|p| p["type"] == "merge").count();
        (outcome, merges, st.failed_count()?)
    };
    let mut line = format!(
        "Просмотрено записей: {} из {}. Переименовано: {}. Новых предложений напоминаний: {}. Новых предложений менеджера: {}. Объединений к просмотру: {}.",
        batch.len(),
        total,
        outcome.renamed,
        outcome.reminders,
        outcome.added,
        merges
    );
    line.push_str(if batch.len() < 2 { " Для объединения нужны минимум две доступные заметки." } else { " Объединения и напоминания требуют подтверждения." });
    if failed_left > 0 {
        line.push_str(&format!(" После ошибки пропущено: {}. «Повторить после ошибок» вернёт их в очередь.", failed_left));
    }
    if (batch.len() as i64) < total {
        line.push_str(" Проверена часть коллекции; за запрос — до 8 записей.");
    }
    if outcome.reviewed < batch.len() {
        line.push_str(" Часть записей изменилась во время запроса; их правки сохранены.");
    }
    log(app, &line);
    Ok(())
}

// ---------- выжимка для стопки ----------

/// Готовит выжимку для предложения-связи по порциям. Готовая часть сохраняется после каждого
/// запроса, поэтому после ошибки или лимита работа продолжается с того же места.
pub fn summary_start(app: &Arc<App>, proposal: i64) -> Result<()> {
    let Some(cfg) = ready(app) else {
        return Err(anyhow!(NEED_KEY));
    };
    {
        let mut s = app.assist.lock().unwrap();
        if s.summary_running != 0 {
            return Err(anyhow!("Выжимка уже готовится"));
        }
        s.summary_running = proposal;
        s.summary_error.clear();
        s.summary_error_id = 0;
    }
    let app = app.clone();
    std::thread::spawn(move || {
        let result = summary_work(&app, proposal, &cfg);
        let mut s = app.assist.lock().unwrap();
        if let Err(e) = result {
            let text = describe(&e);
            let _ = app.store.lock().unwrap().log(&format!("Не удалось подготовить выжимку. {}", text));
            s.summary_error = text;
            s.summary_error_id = proposal;
        }
        s.summary_running = 0;
    });
    Ok(())
}

fn summary_work(app: &Arc<App>, id: i64, cfg: &chat::Settings) -> Result<()> {
    let stale = || anyhow!("Исходники изменились. Запустите новую проверку.");
    let (p, sources) = {
        let st = app.store.lock().unwrap();
        let (p, state) = st.proposal(id)?;
        if state != "pending" || !st.sources_current(&p) {
            return Err(stale());
        }
        let sources = st.proposal_sources(&p)?;
        (p, sources)
    };
    let mut version = String::from("summary-v1");
    for n in &sources {
        version.push_str(&format!("|{}:{}", n.id, review::fingerprint(n)));
    }
    let mut progress = p.get("_summary").cloned().unwrap_or(json!({}));
    if progress["revision"].as_str() != Some(version.as_str()) {
        progress = json!({"revision": version, "cursor": 0});
    }
    let fragments = review::fragments(&sources);
    let mut cursor = progress["cursor"].as_u64().unwrap_or(0) as usize;
    let unlimited = app.store.lock().unwrap().setting("ai.unlimited").as_deref() == Some("1");
    while cursor < fragments.len() {
        let (payload, consumed) = {
            let st = app.store.lock().unwrap();
            let (p, _) = st.proposal(id)?;
            if !st.sources_current(&p) {
                return Err(stale());
            }
            let built = review::summary_payload(&cfg.model, progress["body"].as_str().unwrap_or(""), &fragments, cursor)?;
            if !st.reserve_budget(&today(), unlimited)? {
                return Err(anyhow!("Достигнут дневной лимит. Продолжите завтра или снимите лимит в настройках помощника. Готовая часть выжимки сохранена."));
            }
            st.log(&format!("Готовим выжимку: обработано фрагментов {} из {}. Исходников: {}.", cursor, fragments.len(), sources.len()))?;
            built
        };
        let response = polza::complete(&app.ai_base(), &cfg.key, &payload, 120)?;
        let (title, body) = review::summary_parse(&response)?;
        cursor += consumed;
        progress["cursor"] = json!(cursor);
        progress["ready"] = json!(cursor == fragments.len());
        progress["title"] = json!(title);
        progress["body"] = json!(body);
        app.store.lock().unwrap().save_summary(id, &progress)?;
    }
    if progress["body"].as_str().unwrap_or("").is_empty() {
        return Err(anyhow!("В исходниках нет текста для выжимки."));
    }
    log(app, &format!("Выжимка готова. Откройте предложение и подтвердите создание стопки. Исходников: {}.", sources.len()));
    Ok(())
}

// ---------- вопросы о планах ----------

fn setting_i64(app: &App, key: &str, default: i64) -> i64 {
    app.store.lock().unwrap().setting(key).and_then(|v| v.parse().ok()).unwrap_or(default)
}

fn set_status(app: &App, text: &str) {
    let _ = app.store.lock().unwrap().set_setting("checkin.status", text);
}

/// Сколько вопросов подряд осталось без ответа, не считая последнего, на который ещё ждут.
fn confirmed_misses(app: &App) -> i64 {
    let previous = setting_i64(app, "checkin.chat", 0);
    let st = app.store.lock().unwrap();
    // Удалённый чат — тоже реакция: пользователь не хочет этот вопрос.
    if previous == 0 || !st.chat_exists(previous).unwrap_or(false) || !st.user_text(previous).is_empty() {
        return 0;
    }
    st.setting("checkin.missed").and_then(|v| v.parse().ok()).unwrap_or(0)
}

/// Просит модель составить вопрос и создаёт чат с ним. Возвращает номер чата или 0, если задавать
/// нечего (помощник замолчал после трёх вопросов без ответа).
pub fn checkin_compose(app: &Arc<App>, cfg: &chat::Settings) -> Result<i64> {
    let now = now_ms();
    let streak = {
        let previous = setting_i64(app, "checkin.chat", 0);
        let st = app.store.lock().unwrap();
        if previous == 0 || !st.chat_exists(previous).unwrap_or(false) || !st.user_text(previous).is_empty() {
            0
        } else {
            st.setting("checkin.missed").and_then(|v| v.parse().ok()).unwrap_or(0) + 1
        }
    };
    if streak >= checkin::MAX_MISSED {
        app.store.lock().unwrap().set_setting("checkin.missed", &streak.to_string())?;
        return Ok(0);
    }
    let prompt = {
        let st = app.store.lock().unwrap();
        let events: Vec<String> = st.upcoming(now, 8)?.into_iter().map(|(at, title)| checkin::event_line(at, now, &Local, &title)).collect();
        let recent: Vec<String> = st.recent_notes(6)?.into_iter().map(|(t, b)| checkin::recent_line(&t, &text::excerpt(&b, "", 200))).collect();
        let past: Vec<String> = st.past_questions(3)?.into_iter().map(|(q, a)| checkin::past_line(&q, &a)).collect();
        checkin::prompt(now, &Local, &chat::zone(), &events, &recent, &past)
    };
    let payload = json!({
        "model": cfg.model,
        "max_tokens": 220,
        "messages": [
            {"role": "system", "content": checkin::system()},
            {"role": "user", "content": prompt},
        ],
    });
    let response = polza::complete(&app.ai_base(), &cfg.key, &payload, 60)?;
    let raw = response.pointer("/choices/0/message/content").and_then(|c| c.as_str()).unwrap_or("");
    let question = checkin::clean(raw);
    if question.is_empty() {
        return Err(anyhow!("Модель не составила вопрос."));
    }
    let st = app.store.lock().unwrap();
    let chat = st.new_assistant_chat(&policy::auto_title("", &question), &question)?;
    st.set_setting("checkin.last", &now.to_string())?;
    st.set_setting("checkin.chat", &chat.to_string())?;
    st.set_setting("checkin.missed", &streak.to_string())?;
    st.set_setting("checkin.status", &format!("Последний вопрос: {}", Local::now().format("%d.%m, %H:%M")))?;
    Ok(chat)
}

/// Задаёт вопрос в фоне. Ручной запуск («Спросить сейчас») забывает прежнее молчание.
pub fn checkin_start(app: &Arc<App>, manual: bool) -> Result<()> {
    let Some(cfg) = ready(app) else {
        return Err(anyhow!("Для вопросов сохраните ключ и модель Polza.AI в настройках ИИ."));
    };
    {
        let mut s = app.assist.lock().unwrap();
        if s.checkin_running {
            return Err(anyhow!("Вопрос уже готовится"));
        }
        s.checkin_running = true;
    }
    if manual {
        let st = app.store.lock().unwrap();
        let _ = st.set_setting("checkin.missed", "0");
        let _ = st.set_setting("checkin.chat", "0");
    }
    let app = app.clone();
    std::thread::spawn(move || {
        if let Err(e) = checkin_compose(&app, &cfg) {
            set_status(&app, &format!("Не удалось задать вопрос. {}", describe(&e)));
        }
        app.assist.lock().unwrap().checkin_running = false;
    });
    Ok(())
}

/// Ближайшее время вопроса или -1 (выключено, пауза из-за молчания). Для экрана настроек и расписания.
pub fn checkin_next(app: &App, now: i64) -> i64 {
    let (on, minutes, every, last, since) = {
        let st = app.store.lock().unwrap();
        let get = |k: &str, d: i64| st.setting(k).and_then(|v| v.parse().ok()).unwrap_or(d);
        (st.setting("checkin.on").as_deref() == Some("1"), get("checkin.minutes", checkin::DEFAULT_MINUTES), get("checkin.every", checkin::DEFAULT_EVERY), get("checkin.last", 0), get("checkin.since", 0))
    };
    if !on {
        return -1;
    }
    let missed = confirmed_misses(app);
    if last > 0 {
        checkin::next_at(last, minutes, every, last, missed, &Local)
    } else {
        checkin::next_at(if since > 0 { since } else { now }, minutes, every, 0, missed, &Local)
    }
}

// ---------- расписание ----------

/// Раз в минуту: фоновая проверка (раз в шесть часов) и вопрос по расписанию.
pub fn tick(app: &Arc<App>) {
    let now = now_ms();
    if ready(app).is_none() {
        return;
    }
    let (background, last_review, checkin_on) = {
        let st = app.store.lock().unwrap();
        (
            st.setting("assistant.background").as_deref() == Some("1"),
            st.setting("review.last").and_then(|v| v.parse::<i64>().ok()).unwrap_or(0),
            st.setting("checkin.on").as_deref() == Some("1"),
        )
    };
    if background && now - last_review >= REVIEW_EVERY_MS && !app.assist.lock().unwrap().review_running {
        let _ = app.store.lock().unwrap().set_setting("review.last", &now.to_string());
        let _ = review_start(app, false);
    }
    if checkin_on && !app.assist.lock().unwrap().checkin_running {
        let slot = checkin_next_slot(app, now);
        if slot > 0 && now >= slot {
            if checkin::stale(slot, now) {
                let st = app.store.lock().unwrap();
                let _ = st.set_setting("checkin.last", &now.to_string());
                let _ = st.set_setting("checkin.status", "Вопрос пропущен: компьютер долго был выключен.");
            } else if !checkin::busy(app.store.lock().unwrap().last_user_message(), now) {
                let _ = checkin_start(app, false);
            }
        }
    }
}

/// Срок вопроса, который уже мог наступить: как `checkin_next`, но без сдвига на будущее.
fn checkin_next_slot(app: &App, now: i64) -> i64 {
    let since = setting_i64(app, "checkin.since", 0);
    if since == 0 {
        let _ = app.store.lock().unwrap().set_setting("checkin.since", &now.to_string());
        return -1;
    }
    checkin_next(app, now)
}

/// Короткая строка для боковой панели и экрана помощника.
pub fn summary(app: &App) -> Value {
    let now = now_ms();
    let st = app.store.lock().unwrap();
    let suggestions = st.pending_suggestions(now).map(|v| v.len()).unwrap_or(0);
    let proposals = st.pending_proposals().map(|v| v.len()).unwrap_or(0);
    let status = st.log_list().ok().and_then(|l| l.first().map(|(_, t)| t.clone())).unwrap_or_default();
    json!({
        "suggestions": suggestions,
        "proposals": proposals,
        "status": status,
        "label": if status.is_empty() { String::new() } else { review::status_label(&status).to_string() },
    })
}
