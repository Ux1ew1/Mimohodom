package app.fastnotes;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.TimeZone;

/** Проверяет правила напоминаний без Android: пропущенные, откладывание, подписи. */
public final class ReminderPolicyTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static long at(ZoneId zone, int y, int m, int d, int h, int min) {
    return ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli();
  }

  public static void main(String[] args) {
    ZoneId zone = ZoneId.of("Europe/Moscow");
    long now = at(zone, 2026, 11, 13, 12, 0);
    long minute = 60_000L, hour = 60 * minute, day = 24 * hour;

    // Пропущенные напоминания
    check(ReminderPolicy.missed(now - hour, now - hour, now, false), "missed after an hour");
    check(!ReminderPolicy.missed(now - hour, now - hour, now, true), "delivered is not missed");
    check(!ReminderPolicy.missed(0, 0, now, false), "no reminder is not missed");
    check(!ReminderPolicy.missed(now + hour, now + hour, now, false), "future is not missed");
    check(
        !ReminderPolicy.missed(now - 30_000, now - 30_000, now, false),
        "inside the grace period the alarm fires by itself");
    check(
        ReminderPolicy.missed(now - 2 * minute, now - 2 * minute, now, false),
        "just after the grace period");
    check(
        ReminderPolicy.missed(now + 10 * minute, now - 5 * minute, now, false),
        "lead time passed while the event is still ahead");
    check(
        ReminderPolicy.missed(now - 6 * day, now - 6 * day, now, false), "six days ago is shown");
    check(
        !ReminderPolicy.missed(now - 8 * day, now - 8 * day, now, false),
        "older than a week is not flooded");
    check(
        !ReminderPolicy.missed(now + hour, now - 3 * minute, now - 3 * minute + 30_000, false),
        "grace is counted from the notification time");

    // Откладывание
    check(
        ReminderPolicy.snoozeAt(ReminderPolicy.SNOOZE_10_MIN, now, zone) == now + 10 * minute,
        "snooze ten minutes");
    check(
        ReminderPolicy.snoozeAt(ReminderPolicy.SNOOZE_HOUR, now, zone) == now + hour,
        "snooze an hour");
    check(
        ReminderPolicy.snoozeAt(ReminderPolicy.SNOOZE_TOMORROW, now, zone)
            == at(zone, 2026, 11, 14, 9, 0),
        "tomorrow at nine");
    check(
        ReminderPolicy.snoozeAt(ReminderPolicy.SNOOZE_TOMORROW, at(zone, 2026, 11, 13, 23, 59), zone)
            == at(zone, 2026, 11, 14, 9, 0),
        "tomorrow after midnight boundary");
    check(
        ReminderPolicy.snoozeAt(ReminderPolicy.SNOOZE_TOMORROW, at(zone, 2026, 12, 31, 8, 0), zone)
            == at(zone, 2027, 1, 1, 9, 0),
        "tomorrow across a year");
    check(ReminderPolicy.snoozeAt(99, now, zone) == now + 10 * minute, "unknown kind falls back");
    check(
        ReminderPolicy.snoozeAt(ReminderPolicy.SNOOZE_TOMORROW, at(zone, 2026, 3, 28, 12, 0), zone)
            == at(zone, 2026, 3, 29, 9, 0),
        "tomorrow keeps local wall time");

    // Запись об отложенном напоминании
    long[] decoded = ReminderPolicy.decodeSnooze(ReminderPolicy.encodeSnooze(123L, 456L));
    check(decoded != null && decoded[0] == 123L && decoded[1] == 456L, "snooze round trip");
    check(ReminderPolicy.decodeSnooze(null) == null, "null snooze");
    check(ReminderPolicy.decodeSnooze("abc") == null, "garbage snooze");
    check(ReminderPolicy.decodeSnooze("1|x") == null, "non numeric snooze");
    check(ReminderPolicy.decodeSnooze("1|2|3") == null, "too many parts");

    // Подписи
    TimeZone utc = TimeZone.getTimeZone("UTC");
    long event = at(ZoneId.of("UTC"), 2026, 11, 13, 14, 30);
    String normal = ReminderPolicy.eventLine(event, event - hour, false, utc);
    check(normal.startsWith("Событие: ") && normal.contains("14:30"), "normal line: " + normal);
    String lateButFresh = ReminderPolicy.eventLine(event, event + 30_000, false, utc);
    check(lateButFresh.startsWith("Событие: "), "inside grace is not marked missed");
    String missed = ReminderPolicy.eventLine(event, event + hour, false, utc);
    check(missed.startsWith("Пропущено: ") && missed.contains("14:30"), "missed line: " + missed);
    String snoozed = ReminderPolicy.eventLine(event, event + hour, true, utc);
    check(snoozed.startsWith("Отложено. ") && snoozed.contains("14:30"), "snoozed line: " + snoozed);
    check(ReminderPolicy.snoozeLabel(ReminderPolicy.SNOOZE_HOUR).equals("Через час"), "hour label");
    check(
        ReminderPolicy.snoozeLabel(ReminderPolicy.SNOOZE_TOMORROW).equals("Завтра"),
        "tomorrow label");
    check(ReminderPolicy.snoozeLabel(0).equals("Через 10 мин"), "ten minute label");

    // Повторы
    ZoneId zu = ZoneId.of("UTC");
    long first = at(zu, 2026, 11, 13, 9, 0);
    check(
        ReminderPolicy.nextOccurrence(first, ReminderPolicy.REPEAT_NONE, first + day, zu) == first,
        "no repeat keeps the time");
    check(
        ReminderPolicy.nextOccurrence(first, ReminderPolicy.REPEAT_DAILY, first + 10, zu)
            == first + day,
        "daily goes to tomorrow");
    check(
        ReminderPolicy.nextOccurrence(first, ReminderPolicy.REPEAT_DAILY, first + 3 * day + hour, zu)
            == first + 4 * day,
        "daily skips missed days");
    check(
        ReminderPolicy.nextOccurrence(first, ReminderPolicy.REPEAT_DAILY, first, zu) == first + day,
        "next occurrence is strictly after now");
    check(
        ReminderPolicy.nextOccurrence(first, ReminderPolicy.REPEAT_WEEKLY, first + 8 * day, zu)
            == first + 14 * day,
        "weekly skips a missed week");
    check(
        ReminderPolicy.nextOccurrence(
                at(zu, 2026, 1, 31, 9, 0), ReminderPolicy.REPEAT_MONTHLY, at(zu, 2026, 2, 1, 0, 0), zu)
            == at(zu, 2026, 2, 28, 9, 0),
        "monthly clamps to the last day of a short month");
    check(
        ReminderPolicy.nextOccurrence(
                at(zu, 2026, 1, 31, 9, 0), ReminderPolicy.REPEAT_MONTHLY, at(zu, 2026, 3, 1, 0, 0), zu)
            == at(zu, 2026, 3, 31, 9, 0),
        "monthly from the original day returns to the 31st");
    check(
        ReminderPolicy.nextOccurrence(
                at(zu, 2028, 2, 29, 9, 0), ReminderPolicy.REPEAT_YEARLY, at(zu, 2028, 3, 1, 0, 0), zu)
            == at(zu, 2029, 2, 28, 9, 0),
        "yearly leap day falls back to the 28th");
    ZoneId berlin = ZoneId.of("Europe/Berlin");
    long beforeDst = at(berlin, 2026, 3, 28, 9, 0);
    check(
        ReminderPolicy.nextOccurrence(beforeDst, ReminderPolicy.REPEAT_DAILY, beforeDst + hour, berlin)
            == at(berlin, 2026, 3, 29, 9, 0),
        "daily keeps local time across the DST change");
    check(
        ReminderPolicy.nextOccurrence(0, ReminderPolicy.REPEAT_DAILY, now, zu) == 0,
        "no reminder stays zero");
    check(ReminderPolicy.nextOccurrence(first, 99, now, zu) == first, "unknown mode is ignored");
    check(ReminderPolicy.repeatLabel(ReminderPolicy.REPEAT_WEEKLY).equals("Каждую неделю"), "weekly label");
    check(ReminderPolicy.repeatLabel(0).equals("Не повторять"), "no repeat label");

    // Утренняя сводка
    long morning = at(zu, 2026, 11, 13, 6, 30);
    check(
        ReminderPolicy.nextDigestAt(morning, 8 * 60, zu) == at(zu, 2026, 11, 13, 8, 0),
        "digest later today");
    check(
        ReminderPolicy.nextDigestAt(at(zu, 2026, 11, 13, 8, 0), 8 * 60, zu)
            == at(zu, 2026, 11, 14, 8, 0),
        "digest at the exact time goes to tomorrow");
    check(
        ReminderPolicy.nextDigestAt(at(zu, 2026, 11, 13, 21, 0), 8 * 60, zu)
            == at(zu, 2026, 11, 14, 8, 0),
        "digest after the time goes to tomorrow");
    check(
        ReminderPolicy.nextDigestAt(morning, 99_999, zu) == at(zu, 2026, 11, 13, 23, 59),
        "digest time is clamped");
    check(ReminderPolicy.upcomingToday(at(zu, 2026, 11, 13, 15, 0), morning, zu), "event later today");
    check(!ReminderPolicy.upcomingToday(at(zu, 2026, 11, 13, 6, 0), morning, zu), "past event today");
    check(!ReminderPolicy.upcomingToday(at(zu, 2026, 11, 14, 0, 0), morning, zu), "midnight is tomorrow");
    check(ReminderPolicy.upcomingToday(at(zu, 2026, 11, 13, 23, 59), morning, zu), "last minute of today");
    check(!ReminderPolicy.upcomingToday(0, morning, zu), "no reminder is not today");
    check(ReminderPolicy.eventCount(1).equals("1 событие"), "count 1");
    check(ReminderPolicy.eventCount(2).equals("2 события"), "count 2");
    check(ReminderPolicy.eventCount(5).equals("5 событий"), "count 5");
    check(ReminderPolicy.eventCount(11).equals("11 событий"), "count 11");
    check(ReminderPolicy.eventCount(21).equals("21 событие"), "count 21");
    check(ReminderPolicy.eventCount(112).equals("112 событий"), "count 112");

    System.out.println("ReminderPolicyTest: " + count + " checks passed");
  }
}
