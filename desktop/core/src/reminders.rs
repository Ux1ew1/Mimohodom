//! Напоминания на компьютере: когда показывать уведомление, повторы, «за N минут», откладывание,
//! календарь. Порт `ReminderPolicy` и `Reminders` с Android без системных вызовов: ядро только решает,
//! что пора показать (`Notice`), а само уведомление рисует оболочка (`src-tauri`).
//!
//! Состояние хранится в таблице настроек, по записи на заметку (номера заметок свои на каждом
//! устройстве, поэтому на телефон ничего не уходит):
//! - `rem.d.<id>` — время события, для которого уведомление уже показано;
//! - `rem.s.<id>` — отложенное оповещение: `событие|когда показать`;
//! - `rem.a.<id>` — сработавшее и ещё не закрытое напоминание (время события);
//! - `rem.last` — когда ядро проверяло напоминания в прошлый раз.

use crate::app::App;
use crate::store::{now_ms, Note, Store};
use anyhow::Result;
use chrono::{DateTime, Duration, LocalResult, Months, NaiveDateTime, TimeZone, Timelike};
use serde::Serialize;
use serde_json::{json, Value};
use std::collections::HashSet;
use std::sync::Arc;

pub const MISSED_GRACE_MS: i64 = 60_000;
pub const MISSED_WINDOW_MS: i64 = 7 * 24 * 60 * 60 * 1000;
pub const LEADS: [i64; 8] = [0, 5, 10, 15, 30, 60, 120, 1440];
pub const TOMORROW_HOUR: u32 = 9;
pub const SNOOZE_10_MIN: i64 = 0;
pub const SNOOZE_HOUR: i64 = 1;
pub const SNOOZE_TOMORROW: i64 = 2;
/// Как часто ядро проверяет, не пора ли показать напоминание.
pub const TICK_SECS: u64 = 15;
/// Сколько записей ждёт, пока оболочка покажет уведомления (дальше старые отбрасываются).
const OUTBOX_MAX: usize = 50;

const REPEAT_DAILY: i64 = 1;
const REPEAT_WEEKLY: i64 = 2;
const REPEAT_MONTHLY: i64 = 3;
const REPEAT_YEARLY: i64 = 4;

/// Что показать пользователю: готовое содержимое уведомления.
#[derive(Serialize, Clone, Debug, PartialEq)]
pub struct Notice {
    /// Номер заметки (0 — проверочное уведомление).
    pub id: i64,
    pub title: String,
    pub text: String,
    pub reminder: i64,
    pub snoozed: bool,
}

// ---------- чистые правила ----------

/// Допустимое значение «за N минут»; всё остальное — в момент события.
pub fn clean_lead(v: i64) -> i64 {
    if LEADS.contains(&v) {
        v
    } else {
        0
    }
}

/// Интервал «за N минут» для заметки: свой, если задан, иначе общий.
pub fn lead_for(note_lead: i64, default: i64) -> i64 {
    if LEADS.contains(&note_lead) {
        note_lead
    } else {
        clean_lead(default)
    }
}

pub fn trigger(reminder: i64, lead_minutes: i64) -> i64 {
    reminder - lead_minutes * 60_000
}

/// Пора ли передвигать доставленную серию: когда наступило само событие (с допуском в секунду), а не
/// предупреждение «за N минут».
pub fn advance_due(reminder: i64, now: i64) -> bool {
    reminder > 0 && now >= reminder - 1000
}

/// Приводит «местное» время к моменту: при неоднозначности (перевод часов назад) берёт более раннее,
/// при пропуске (перевод вперёд) сдвигает на час.
fn resolve<Tz: TimeZone>(tz: &Tz, n: NaiveDateTime) -> Option<DateTime<Tz>> {
    match tz.from_local_datetime(&n) {
        LocalResult::Single(d) => Some(d),
        LocalResult::Ambiguous(a, _) => Some(a),
        LocalResult::None => tz.from_local_datetime(&(n + Duration::hours(1))).earliest(),
    }
}

fn local_naive<Tz: TimeZone>(ms: i64, tz: &Tz) -> Option<NaiveDateTime> {
    tz.timestamp_millis_opt(ms).single().map(|d| d.naive_local())
}

/// `k`-е повторение от начала серии с тем же временем суток; в коротком месяце — последний день.
fn nth(start: NaiveDateTime, mode: i64, k: i64) -> Option<NaiveDateTime> {
    match mode {
        REPEAT_DAILY => start.checked_add_signed(Duration::try_days(k)?),
        REPEAT_WEEKLY => start.checked_add_signed(Duration::try_weeks(k)?),
        REPEAT_MONTHLY => start.checked_add_months(Months::new(u32::try_from(k).ok()?)),
        REPEAT_YEARLY => start.checked_add_months(Months::new(u32::try_from(k.checked_mul(12)?).ok()?)),
        _ => None,
    }
}

/// Нижняя граница номера повторения, после которой время гарантированно позже `from` (чтобы не
/// перебирать годы суточной серии по одному шагу). Сутки с переводом часов бывают до 25 часов, месяц — до 31 дня.
fn skip_k(reminder: i64, mode: i64, from: i64) -> i64 {
    let elapsed = (from - reminder).max(0);
    let period = match mode {
        REPEAT_DAILY => 25 * 3_600_000,
        REPEAT_WEEKLY => 7 * 25 * 3_600_000,
        REPEAT_MONTHLY => 31 * 24 * 3_600_000,
        _ => 366 * 24 * 3_600_000,
    };
    (elapsed / period - 1).max(1)
}

/// Ближайшее повторение строго после `now`. Для не повторяющегося напоминания возвращает исходное время.
pub fn next_occurrence<Tz: TimeZone>(reminder: i64, mode: i64, now: i64, tz: &Tz) -> i64 {
    if !(REPEAT_DAILY..=REPEAT_YEARLY).contains(&mode) || reminder <= 0 {
        return reminder;
    }
    let Some(start) = local_naive(reminder, tz) else { return reminder };
    let mut k = skip_k(reminder, mode, now);
    let end = k + 200_000;
    while k <= end {
        let Some(n) = nth(start, mode, k) else { break };
        if let Some(d) = resolve(tz, n) {
            let ms = d.timestamp_millis();
            if ms > now {
                return ms;
            }
        }
        k += 1;
    }
    reminder
}

/// Все времена события в окне `[from, to)`: само событие и, для серии, его повторения (не больше `cap`).
/// До ближайшего сохранённого времени серия назад не разворачивается: начало серии не хранится.
pub fn occurrences<Tz: TimeZone>(reminder: i64, mode: i64, from: i64, to: i64, tz: &Tz, cap: usize) -> Vec<i64> {
    let mut out = Vec::new();
    if reminder <= 0 || to <= from {
        return out;
    }
    if reminder >= from && reminder < to {
        out.push(reminder);
    }
    if !(REPEAT_DAILY..=REPEAT_YEARLY).contains(&mode) {
        return out;
    }
    let Some(start) = local_naive(reminder, tz) else { return out };
    let mut k = skip_k(reminder, mode, from);
    // Пропуск мог оказаться слишком большим только в пределах одного шага; шаг назад страхует границу.
    k = (k - 1).max(1);
    while out.len() < cap {
        let Some(n) = nth(start, mode, k) else { break };
        let Some(d) = resolve(tz, n) else { k += 1; continue };
        let ms = d.timestamp_millis();
        if ms >= to {
            break;
        }
        if ms >= from {
            out.push(ms);
        }
        k += 1;
    }
    out
}

/// Время повторного оповещения: через 10 минут, через час или завтра в 9:00.
pub fn snooze_at<Tz: TimeZone>(kind: i64, now: i64, tz: &Tz) -> i64 {
    match kind {
        SNOOZE_HOUR => now + 3_600_000,
        SNOOZE_TOMORROW => {
            let tomorrow = tz
                .timestamp_millis_opt(now)
                .single()
                .map(|d| d.naive_local().date() + Duration::days(1))
                .and_then(|d| d.and_hms_opt(TOMORROW_HOUR, 0, 0));
            match tomorrow.and_then(|n| resolve(tz, n)) {
                Some(d) => d.timestamp_millis(),
                None => now + 24 * 3_600_000,
            }
        }
        _ => now + 600_000,
    }
}

const MONTHS: [&str; 12] = ["янв.", "февр.", "мар.", "апр.", "мая", "июн.", "июл.", "авг.", "сент.", "окт.", "нояб.", "дек."];

/// «5 нояб., 14:30».
pub fn date_label<Tz: TimeZone>(ms: i64, tz: &Tz) -> String {
    use chrono::Datelike;
    match local_naive(ms, tz) {
        Some(n) => format!("{} {}, {:02}:{:02}", n.day(), MONTHS[n.month0() as usize], n.hour(), n.minute()),
        None => String::new(),
    }
}

/// Вторая строка уведомления: дата события и пометка, если оно пропущено или отложено.
pub fn event_line<Tz: TimeZone>(reminder: i64, now: i64, snoozed: bool, tz: &Tz) -> String {
    let date = date_label(reminder, tz);
    if snoozed {
        format!("Отложено. Событие: {}", date)
    } else if reminder + MISSED_GRACE_MS <= now {
        format!("Пропущено: событие было {}", date)
    } else {
        format!("Событие: {}", date)
    }
}

pub fn snooze_label(kind: i64) -> &'static str {
    match kind {
        SNOOZE_HOUR => "Через час",
        SNOOZE_TOMORROW => "Завтра",
        _ => "Через 10 мин",
    }
}

fn snippet(body: &str) -> String {
    let flat: String = body.split_whitespace().collect::<Vec<_>>().join(" ");
    if flat.chars().count() > 140 {
        format!("{}…", flat.chars().take(140).collect::<String>())
    } else {
        flat
    }
}

fn notice<Tz: TimeZone>(n: &Note, event: i64, now: i64, snoozed: bool, tz: &Tz) -> Notice {
    let line = event_line(event, now, snoozed, tz);
    let body = snippet(&n.body);
    let title = if n.title.trim().is_empty() { "Напоминание".to_string() } else { n.title.clone() };
    Notice { id: n.id, title, text: if body.is_empty() || body == n.title { line } else { format!("{}\n{}", line, body) }, reminder: event, snoozed }
}

// ---------- состояние в настройках ----------

fn get_i64(st: &Store, prefix: &str, id: i64) -> Option<i64> {
    st.setting(&format!("{}{}", prefix, id)).and_then(|v| v.parse().ok())
}

fn put(st: &Store, prefix: &str, id: i64, value: &str) -> Result<()> {
    st.set_setting(&format!("{}{}", prefix, id), value)
}

fn clear(st: &Store, prefix: &str, id: i64) -> Result<()> {
    st.delete_setting(&format!("{}{}", prefix, id))
}

fn key_id(key: &str, prefix: &str) -> Option<i64> {
    key.strip_prefix(prefix).and_then(|s| s.parse().ok())
}

fn decode_snooze(v: &str) -> Option<(i64, i64)> {
    let (a, b) = v.split_once('|')?;
    Some((a.parse().ok()?, b.parse().ok()?))
}

/// Общий интервал «за N минут» из настроек.
pub fn default_lead(st: &Store) -> i64 {
    clean_lead(st.setting("reminder.lead").and_then(|v| v.parse().ok()).unwrap_or(0))
}

// ---------- проверка ----------

/// Один проход: находит напоминания, которым пора сработать, помечает их показанными, передвигает
/// серии и возвращает уведомления. Вызывается раз в `TICK_SECS`, а также при запуске (тогда показывает
/// и пропущенные, пока приложение было закрыто).
///
/// Напоминание, которое ядро видит впервые уже после срока (пришло по синхронизации с телефона, где
/// оно давно показано, или осталось от прошлых запусков), помечается показанным молча: уведомлять о
/// нём второй раз незачем. Пропущенным считается только то, что наступило, пока приложение было закрыто.
pub fn run_due<Tz: TimeZone>(st: &Store, now: i64, tz: &Tz) -> Result<Vec<Notice>> {
    let saved_last = st.setting("rem.last").and_then(|v| v.parse::<i64>().ok());
    let first_run = saved_last.is_none();
    let last = saved_last.unwrap_or(now);
    let lead_default = default_lead(st);
    let mut out = Vec::new();
    let mut known = HashSet::new();

    for n in st.reminder_notes(now - MISSED_WINDOW_MS)? {
        known.insert(n.id);
        let trig = trigger(n.reminder, lead_for(n.lead, lead_default));
        let mut delivered = get_i64(st, "rem.d.", n.id) == Some(n.reminder);
        if !delivered {
            // `updated` защищает только что поставленное напоминание, у которого «за N минут» уже наступило.
            if (first_run && trig <= now) || trig.max(n.updated) < last {
                put(st, "rem.d.", n.id, &n.reminder.to_string())?;
                delivered = true;
            } else if trig <= now {
                if now - n.reminder <= MISSED_WINDOW_MS {
                    out.push(notice(&n, n.reminder, now, false, tz));
                    put(st, "rem.a.", n.id, &n.reminder.to_string())?;
                }
                put(st, "rem.d.", n.id, &n.reminder.to_string())?;
                delivered = true;
            }
        }
        if delivered && n.repeat != 0 && advance_due(n.reminder, now) {
            let next = next_occurrence(n.reminder, n.repeat, now, tz);
            if next > n.reminder {
                st.advance_reminder(n.id, n.reminder, next)?;
            }
        }
    }

    // Отложенные оповещения.
    for (k, v) in st.settings_with_prefix("rem.s.")? {
        let Some(id) = key_id(&k, "rem.s.") else { continue };
        let Some((event, at)) = decode_snooze(&v) else { st.delete_setting(&k)?; continue };
        let Some(n) = st.note(id)? else { st.delete_setting(&k)?; continue };
        // Событие перенесли на другом устройстве: отложенное оповещение относилось к старому времени.
        if n.repeat == 0 && n.reminder != event {
            st.delete_setting(&k)?;
            continue;
        }
        if at <= now {
            out.push(notice(&n, event, now, true, tz));
            put(st, "rem.a.", id, &event.to_string())?;
            st.delete_setting(&k)?;
        }
    }

    // Уборка: состояние удалённых заметок и закрытые напоминания, у которых заметки уже нет.
    for prefix in ["rem.d.", "rem.a."] {
        for (k, _) in st.settings_with_prefix(prefix)? {
            if let Some(id) = key_id(&k, prefix) {
                if !known.contains(&id) && st.note(id)?.is_none() {
                    st.delete_setting(&k)?;
                }
            }
        }
    }
    st.set_setting("rem.last", &now.to_string())?;
    Ok(out)
}

/// После сохранения заметки: если событие перенесли, убрали или сменили повтор, снимает отложенное
/// оповещение и закрывает показанное; правка одного текста их не трогает.
pub fn on_saved(st: &Store, before: Option<&Note>, after: &Note) -> Result<()> {
    if before.map(|b| b.reminder != after.reminder || b.repeat != after.repeat).unwrap_or(true) {
        clear(st, "rem.s.", after.id)?;
        clear(st, "rem.a.", after.id)?;
    }
    Ok(())
}

/// При удалении заметки.
pub fn on_deleted(st: &Store, id: i64) -> Result<()> {
    for p in ["rem.d.", "rem.s.", "rem.a."] {
        clear(st, p, id)?;
    }
    Ok(())
}

/// Сработавшие и не закрытые напоминания для экрана.
pub fn active<Tz: TimeZone>(st: &Store, now: i64, tz: &Tz) -> Result<Vec<Value>> {
    let mut list = Vec::new();
    for (k, v) in st.settings_with_prefix("rem.a.")? {
        let (Some(id), Ok(event)) = (key_id(&k, "rem.a."), v.parse::<i64>()) else { continue };
        let Some(n) = st.note(id)? else { continue };
        let snoozed = st.setting(&format!("rem.s.{}", id)).is_some();
        // Отложенное не считается сработавшим; перенесённое на другом устройстве событие — тоже.
        if snoozed || (n.repeat == 0 && n.reminder != event) {
            continue;
        }
        list.push(json!({
            "id": id,
            "title": if n.title.trim().is_empty() { "Напоминание".to_string() } else { n.title.clone() },
            "reminder": event,
            "line": event_line(event, now, false, tz),
        }));
    }
    list.sort_by_key(|v| v["reminder"].as_i64().unwrap_or(0));
    Ok(list)
}

/// Откладывает оповещение, не меняя время самого события; возвращает время нового оповещения.
pub fn snooze<Tz: TimeZone>(st: &Store, id: i64, kind: i64, now: i64, tz: &Tz) -> Result<i64> {
    let Some(n) = st.note(id)? else { return Ok(0) };
    let event = get_i64(st, "rem.a.", id).unwrap_or(n.reminder);
    let at = snooze_at(kind, now, tz);
    put(st, "rem.s.", id, &format!("{}|{}", event, at))?;
    clear(st, "rem.a.", id)?;
    Ok(at)
}

pub fn dismiss(st: &Store, id: i64) -> Result<()> {
    clear(st, "rem.a.", id)
}

/// События календаря в окне `[from, to)` по порядку времени.
pub fn calendar<Tz: TimeZone>(st: &Store, from: i64, to: i64, tz: &Tz) -> Result<Vec<Value>> {
    let lead_default = default_lead(st);
    let mut out: Vec<(i64, Value)> = Vec::new();
    for n in st.calendar_notes(from, to)? {
        let lead = lead_for(n.lead, lead_default);
        for (i, at) in occurrences(n.reminder, n.repeat, from, to, tz, 400).into_iter().enumerate() {
            let title = if n.title.trim().is_empty() { "Напоминание".to_string() } else { n.title.clone() };
            // Первое время серии — то, что хранится в заметке; остальные — расчётные повторения.
            out.push((at, json!({"id": n.id, "title": title, "at": at, "repeat": n.repeat, "lead": lead, "projected": at != n.reminder && (i > 0 || n.repeat != 0)})));
        }
    }
    out.sort_by_key(|(at, _)| *at);
    Ok(out.into_iter().map(|(_, v)| v).collect())
}

// ---------- обёртка над приложением ----------

fn local() -> chrono::Local {
    chrono::Local
}

/// Раз в `TICK_SECS`: проверяет напоминания и кладёт уведомления в очередь оболочки.
pub fn tick(app: &Arc<App>) {
    let found = {
        let st = app.store.lock().unwrap();
        run_due(&st, now_ms(), &local())
    };
    match found {
        Ok(list) if !list.is_empty() => push_outbox(app, list),
        Ok(_) => {}
        Err(e) => app.set_status(&format!("Напоминания: {}", e)),
    }
}

pub fn push_outbox(app: &Arc<App>, list: Vec<Notice>) {
    let mut q = app.notices.lock().unwrap();
    q.extend(list);
    let over = q.len().saturating_sub(OUTBOX_MAX);
    if over > 0 {
        q.drain(..over);
    }
}

/// Уведомления, которые оболочке пора показать. Если всплывающие уведомления выключены, очередь просто
/// очищается: напоминание остаётся в списке «Сработало» и в календаре.
pub fn take_popups(app: &Arc<App>) -> Vec<Notice> {
    let list: Vec<Notice> = std::mem::take(&mut *app.notices.lock().unwrap());
    let popups_on = app.store.lock().unwrap().setting("reminder.popup").as_deref() != Some("0");
    if popups_on {
        list
    } else {
        Vec::new()
    }
}

pub fn local_active(app: &Arc<App>) -> Result<Vec<Value>> {
    active(&app.store.lock().unwrap(), now_ms(), &local())
}

pub fn local_snooze(app: &Arc<App>, id: i64, kind: i64) -> Result<i64> {
    snooze(&app.store.lock().unwrap(), id, kind, now_ms(), &local())
}

pub fn local_calendar(app: &Arc<App>, from: i64, to: i64) -> Result<Vec<Value>> {
    calendar(&app.store.lock().unwrap(), from, to, &local())
}

/// Проверочное уведомление из настроек.
pub fn test_notice() -> Notice {
    Notice { id: 0, title: "Проверочное уведомление".into(), text: "Так будут приходить напоминания.".into(), reminder: 0, snoozed: false }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::SaveInput;
    use chrono::{FixedOffset, Utc};

    fn utc() -> Utc {
        Utc
    }
    fn at(y: i32, m: u32, d: u32, h: u32, mi: u32) -> i64 {
        Utc.with_ymd_and_hms(y, m, d, h, mi, 0).unwrap().timestamp_millis()
    }

    #[test]
    fn leads() {
        assert_eq!(lead_for(15, 60), 15);
        assert_eq!(lead_for(-1, 60), 60);
        assert_eq!(lead_for(-1, 7), 0);
        assert_eq!(trigger(10_000_000, 5), 10_000_000 - 300_000);
    }

    #[test]
    fn daily_weekly_monthly_yearly() {
        let r = at(2026, 1, 31, 9, 0);
        let now = at(2026, 2, 1, 0, 0);
        assert_eq!(next_occurrence(r, 1, now, &utc()), at(2026, 2, 1, 9, 0));
        assert_eq!(next_occurrence(r, 2, now, &utc()), at(2026, 2, 7, 9, 0));
        // 31 января + месяц = последний день февраля; следующий месяц снова считается от 31-го.
        assert_eq!(next_occurrence(r, 3, now, &utc()), at(2026, 2, 28, 9, 0));
        assert_eq!(next_occurrence(r, 3, at(2026, 3, 1, 0, 0), &utc()), at(2026, 3, 31, 9, 0));
        assert_eq!(next_occurrence(at(2024, 2, 29, 9, 0), 4, at(2025, 3, 1, 0, 0), &utc()), at(2026, 2, 28, 9, 0));
        // Без повтора и со строго «после now».
        assert_eq!(next_occurrence(r, 0, now, &utc()), r);
        assert_eq!(next_occurrence(r, 1, at(2026, 2, 1, 9, 0), &utc()), at(2026, 2, 2, 9, 0));
    }

    #[test]
    fn long_series_jumps_quickly() {
        let r = at(2000, 1, 1, 8, 30);
        let now = at(2026, 11, 18, 12, 0);
        assert_eq!(next_occurrence(r, 1, now, &utc()), at(2026, 11, 19, 8, 30));
        assert_eq!(next_occurrence(r, 2, now, &utc()), at(2026, 11, 21, 8, 30));
        assert_eq!(next_occurrence(r, 3, now, &utc()), at(2026, 12, 1, 8, 30));
        assert_eq!(next_occurrence(r, 4, now, &utc()), at(2027, 1, 1, 8, 30));
    }

    #[test]
    fn keeps_wall_clock_across_offsets() {
        // В поясе +03:00 время суток сохраняется: 09:00 по местному = 06:00 UTC.
        let tz = FixedOffset::east_opt(3 * 3600).unwrap();
        let r = at(2026, 5, 1, 6, 0);
        assert_eq!(next_occurrence(r, 1, r, &tz), at(2026, 5, 2, 6, 0));
        // Завтра в 9:00 по местному.
        assert_eq!(snooze_at(SNOOZE_TOMORROW, at(2026, 5, 1, 20, 0), &tz), at(2026, 5, 2, 6, 0));
        assert_eq!(snooze_at(SNOOZE_10_MIN, 1000, &tz), 1000 + 600_000);
        assert_eq!(snooze_at(SNOOZE_HOUR, 1000, &tz), 1000 + 3_600_000);
    }

    #[test]
    fn occurrences_in_window() {
        let r = at(2026, 11, 3, 10, 0);
        // Одиночное: только если попало в окно.
        assert_eq!(occurrences(r, 0, at(2026, 11, 1, 0, 0), at(2026, 12, 1, 0, 0), &utc(), 100), vec![r]);
        assert!(occurrences(r, 0, at(2026, 12, 1, 0, 0), at(2027, 1, 1, 0, 0), &utc(), 100).is_empty());
        // Неделя: 3, 10, 17, 24 ноября.
        let w = occurrences(r, 2, at(2026, 11, 1, 0, 0), at(2026, 12, 1, 0, 0), &utc(), 100);
        assert_eq!(w, vec![r, at(2026, 11, 10, 10, 0), at(2026, 11, 17, 10, 0), at(2026, 11, 24, 10, 0)]);
        // Окно далеко после начала серии: без прыжков назад.
        let far = occurrences(r, 1, at(2027, 3, 1, 0, 0), at(2027, 3, 4, 0, 0), &utc(), 100);
        assert_eq!(far, vec![at(2027, 3, 1, 10, 0), at(2027, 3, 2, 10, 0), at(2027, 3, 3, 10, 0)]);
        // Серия не разворачивается назад.
        assert!(occurrences(r, 1, at(2026, 10, 1, 0, 0), at(2026, 10, 5, 0, 0), &utc(), 100).is_empty());
        // Предел.
        assert_eq!(occurrences(r, 1, at(2026, 11, 3, 0, 0), at(2030, 1, 1, 0, 0), &utc(), 5).len(), 5);
    }

    #[test]
    fn labels() {
        let now = at(2026, 11, 5, 12, 0);
        assert_eq!(date_label(at(2026, 11, 5, 14, 30), &utc()), "5 нояб., 14:30");
        assert_eq!(event_line(at(2026, 11, 5, 14, 30), now, false, &utc()), "Событие: 5 нояб., 14:30");
        assert_eq!(event_line(at(2026, 11, 5, 11, 0), now, false, &utc()), "Пропущено: событие было 5 нояб., 11:00");
        assert_eq!(event_line(at(2026, 11, 5, 14, 30), now, true, &utc()), "Отложено. Событие: 5 нояб., 14:30");
    }

    fn note(st: &Store, title: &str, reminder: i64, repeat: i64, lead: i64) -> Note {
        st.save_note(&SaveInput { id: None, title: title.into(), body: "текст".into(), reminder, repeat, lead, images: None, folder: None }).unwrap()
    }

    #[test]
    fn fires_once_and_repeats_advance() {
        let st = Store::open_memory().unwrap();
        let t0 = now_ms();
        // Напоминание через 20 минут, предупреждение за 15.
        let n = note(&st, "Врач", t0 + 20 * 60_000, 1, 15);
        assert!(run_due(&st, t0, &Utc).unwrap().is_empty());
        // Раньше предупреждения тихо.
        assert!(run_due(&st, t0 + 4 * 60_000, &Utc).unwrap().is_empty());
        // Предупреждение пришло, серию ещё не двигаем (событие впереди).
        let w = run_due(&st, t0 + 6 * 60_000, &Utc).unwrap();
        assert_eq!(w.len(), 1);
        assert_eq!(w[0].title, "Врач");
        assert_eq!(st.note(n.id).unwrap().unwrap().reminder, n.reminder);
        assert!(run_due(&st, t0 + 7 * 60_000, &Utc).unwrap().is_empty());
        assert_eq!(active(&st, t0 + 7 * 60_000, &Utc).unwrap().len(), 1);
        // Когда наступило само событие, серия передвигается на завтра и снова ждёт.
        assert!(run_due(&st, t0 + 20 * 60_000 + 5_000, &Utc).unwrap().is_empty());
        let moved = st.note(n.id).unwrap().unwrap();
        assert_eq!(moved.reminder, n.reminder + 24 * 3_600_000);
        // Передвижение не считается правкой для синхронизации.
        assert_eq!((moved.sync_ts, moved.sync_seq), (n.sync_ts, n.sync_seq));
        assert!(run_due(&st, t0 + 21 * 60_000, &Utc).unwrap().is_empty());
        // Завтра срабатывает снова.
        assert_eq!(run_due(&st, t0 + 24 * 3_600_000 + 6 * 60_000, &Utc).unwrap().len(), 1);
    }

    #[test]
    fn missed_while_closed_is_shown_but_old_arrivals_are_silent() {
        let st = Store::open_memory().unwrap();
        let t0 = now_ms();
        // Ядро работало в момент t0: отметка последней проверки.
        assert!(run_due(&st, t0, &Utc).unwrap().is_empty());
        let a = note(&st, "Пока было закрыто", t0 + 3_600_000, 0, -1);
        // Поставили заметку, приложение закрыли и открыли через 3 часа: событие пропущено.
        // (Отметку проверки возвращаем к моменту до срока, как если бы ядро остановилось раньше.)
        st.set_setting("rem.last", &(t0 + 10_000).to_string()).unwrap();
        let shown = run_due(&st, t0 + 3 * 3_600_000, &Utc).unwrap();
        assert_eq!(shown.len(), 1);
        assert!(shown[0].text.starts_with("Пропущено"), "{}", shown[0].text);
        assert_eq!(shown[0].id, a.id);

        // Заметка со старым напоминанием, пришедшая позже (например, по синхронизации): молча.
        let late = t0 + 4 * 3_600_000;
        let input = SaveInput { id: None, title: "Старое".into(), body: String::new(), reminder: t0 + 3_600_000 + 5, repeat: 0, lead: -1, images: None, folder: None };
        let b = st.save_note(&input).unwrap();
        // Для заметки считаем «обновлена давно»: перенесём правку в прошлое.
        st.conn.execute("UPDATE notes SET updated=?2 WHERE id=?1", rusqlite::params![b.id, t0]).unwrap();
        assert!(run_due(&st, late, &Utc).unwrap().is_empty());
        // И больше не напоминает.
        assert!(run_due(&st, late + 60_000, &Utc).unwrap().is_empty());
    }

    #[test]
    fn first_run_is_silent() {
        let st = Store::open_memory().unwrap();
        let t0 = now_ms();
        let _ = note(&st, "Давно", t0 - 5 * 3_600_000, 0, -1);
        assert!(run_due(&st, t0, &Utc).unwrap().is_empty());
    }

    #[test]
    fn snooze_dismiss_and_edit() {
        let st = Store::open_memory().unwrap();
        let t0 = now_ms();
        let n = note(&st, "Позвонить", t0 + 60_000, 0, -1);
        run_due(&st, t0, &Utc).unwrap();
        assert_eq!(run_due(&st, t0 + 70_000, &Utc).unwrap().len(), 1);
        assert_eq!(active(&st, t0 + 70_000, &Utc).unwrap().len(), 1);
        // Отложили на 10 минут: из «сработало» исчезло, через 10 минут показывается снова с пометкой.
        let at = snooze(&st, n.id, SNOOZE_10_MIN, t0 + 70_000, &Utc).unwrap();
        assert_eq!(at, t0 + 70_000 + 600_000);
        assert!(active(&st, t0 + 80_000, &Utc).unwrap().is_empty());
        assert!(run_due(&st, t0 + 500_000, &Utc).unwrap().is_empty());
        let again = run_due(&st, t0 + 700_000, &Utc).unwrap();
        assert_eq!(again.len(), 1);
        assert!(again[0].snoozed && again[0].text.starts_with("Отложено"));
        assert_eq!(active(&st, t0 + 700_000, &Utc).unwrap().len(), 1);
        dismiss(&st, n.id).unwrap();
        assert!(active(&st, t0 + 700_000, &Utc).unwrap().is_empty());
        // Перенос события снимает отложенное.
        snooze(&st, n.id, SNOOZE_HOUR, t0 + 800_000, &Utc).unwrap();
        let mut moved = n.clone();
        moved.reminder += 1_000_000;
        on_saved(&st, Some(&n), &moved).unwrap();
        assert!(st.setting(&format!("rem.s.{}", n.id)).is_none());
        // Удалённая заметка не оставляет состояния.
        run_due(&st, t0 + 900_000, &Utc).unwrap();
        st.delete_note(n.id).unwrap();
        run_due(&st, t0 + 950_000, &Utc).unwrap();
        assert!(st.settings_with_prefix("rem.").unwrap().iter().all(|(k, _)| k == "rem.last"));
    }

    #[test]
    fn calendar_lists_series_occurrences() {
        let st = Store::open_memory().unwrap();
        let r = at(2026, 11, 3, 10, 0);
        note(&st, "Планёрка", r, 2, -1);
        note(&st, "Разовое", at(2026, 11, 12, 18, 0), 0, -1);
        let cal = calendar(&st, at(2026, 11, 1, 0, 0), at(2026, 12, 1, 0, 0), &Utc).unwrap();
        let titles: Vec<&str> = cal.iter().map(|v| v["title"].as_str().unwrap()).collect();
        assert_eq!(titles, vec!["Планёрка", "Планёрка", "Разовое", "Планёрка", "Планёрка"]);
        assert_eq!(cal[0]["projected"], json!(false));
        assert_eq!(cal[1]["projected"], json!(true));
    }
}
