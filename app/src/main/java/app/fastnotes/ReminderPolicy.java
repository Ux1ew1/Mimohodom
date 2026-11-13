package app.fastnotes;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Правила напоминаний без зависимости от Android: пропущенные, откладывание, подписи. */
public final class ReminderPolicy {
  public static final long MISSED_GRACE_MS = 60_000L;
  public static final long MISSED_WINDOW_MS = 7L * 24 * 60 * 60 * 1000;
  public static final int SNOOZE_10_MIN = 0, SNOOZE_HOUR = 1, SNOOZE_TOMORROW = 2;
  public static final int TOMORROW_HOUR = 9;
  public static final int REPEAT_NONE = 0,
      REPEAT_DAILY = 1,
      REPEAT_WEEKLY = 2,
      REPEAT_MONTHLY = 3,
      REPEAT_YEARLY = 4;
  public static final int DIGEST_DEFAULT_MINUTES = 8 * 60;

  private ReminderPolicy() {}

  /**
   * Напоминание пропущено, если его время оповещения прошло больше минуты назад, оно не
   * доставлено, а событие было не раньше чем неделю назад. Минута нужна, чтобы не опережать
   * будильник, который сейчас сработает сам.
   */
  public static boolean missed(long reminder, long trigger, long now, boolean delivered) {
    return !delivered
        && reminder > 0
        && trigger + MISSED_GRACE_MS <= now
        && now - reminder <= MISSED_WINDOW_MS;
  }

  /** Время повторного оповещения: через 10 минут, через час или завтра в 9:00. */
  public static long snoozeAt(int kind, long now, ZoneId zone) {
    return switch (kind) {
      case SNOOZE_HOUR -> now + 60L * 60 * 1000;
      case SNOOZE_TOMORROW ->
          ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
              .toLocalDate()
              .plusDays(1)
              .atTime(TOMORROW_HOUR, 0)
              .atZone(zone)
              .toInstant()
              .toEpochMilli();
      default -> now + 10L * 60 * 1000;
    };
  }

  public static String snoozeLabel(int kind) {
    return switch (kind) {
      case SNOOZE_HOUR -> "Через час";
      case SNOOZE_TOMORROW -> "Завтра";
      default -> "Через 10 мин";
    };
  }

  /** Вторая строка уведомления: дата события и пометка, если оно пропущено или отложено. */
  public static String eventLine(long reminder, long now, boolean snoozed, TimeZone zone) {
    SimpleDateFormat format = new SimpleDateFormat("d MMM, HH:mm", new Locale("ru"));
    format.setTimeZone(zone);
    String date = format.format(new Date(reminder));
    if (snoozed) return "Отложено. Событие: " + date;
    if (reminder + MISSED_GRACE_MS <= now) return "Пропущено: событие было " + date;
    return "Событие: " + date;
  }

  public static String repeatLabel(int mode) {
    return switch (mode) {
      case REPEAT_DAILY -> "Каждый день";
      case REPEAT_WEEKLY -> "Каждую неделю";
      case REPEAT_MONTHLY -> "Каждый месяц";
      case REPEAT_YEARLY -> "Каждый год";
      default -> "Не повторять";
    };
  }

  /**
   * Ближайшее повторение строго после {@code now}, с тем же временем суток. Если в месяце нет
   * нужного числа (31-е, 29 февраля), берётся последний день месяца. Для {@link #REPEAT_NONE}
   * возвращает исходное время.
   */
  public static long nextOccurrence(long reminder, int mode, long now, ZoneId zone) {
    if (mode < REPEAT_DAILY || mode > REPEAT_YEARLY || reminder <= 0) return reminder;
    ZonedDateTime start = Instant.ofEpochMilli(reminder).atZone(zone);
    ZonedDateTime next = start;
    for (int k = 1; k <= 200_000; k++) {
      next =
          switch (mode) {
            case REPEAT_DAILY -> start.plusDays(k);
            case REPEAT_WEEKLY -> start.plusWeeks(k);
            case REPEAT_MONTHLY -> start.plusMonths(k);
            default -> start.plusYears(k);
          };
      if (next.toInstant().toEpochMilli() > now) break;
    }
    return next.toInstant().toEpochMilli();
  }

  /** Ближайшее время утренней сводки: сегодня в указанные минуты от полуночи или завтра. */
  public static long nextDigestAt(long now, int minutes, ZoneId zone) {
    int safe = Math.max(0, Math.min(minutes, 24 * 60 - 1));
    ZonedDateTime current = Instant.ofEpochMilli(now).atZone(zone);
    ZonedDateTime today =
        current.toLocalDate().atTime(safe / 60, safe % 60).atZone(zone);
    if (today.toInstant().toEpochMilli() > now) return today.toInstant().toEpochMilli();
    return current
        .toLocalDate()
        .plusDays(1)
        .atTime(safe / 60, safe % 60)
        .atZone(zone)
        .toInstant()
        .toEpochMilli();
  }

  /** Событие ещё впереди и случится до конца сегодняшнего дня. */
  public static boolean upcomingToday(long reminder, long now, ZoneId zone) {
    if (reminder <= 0 || reminder < now) return false;
    long endOfDay =
        Instant.ofEpochMilli(now)
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli();
    return reminder < endOfDay;
  }

  /** «1 событие», «2 события», «5 событий» с учётом русских окончаний. */
  public static String eventCount(int count) {
    int hundreds = count % 100, units = count % 10;
    String word =
        hundreds >= 11 && hundreds <= 14
            ? "событий"
            : units == 1 ? "событие" : units >= 2 && units <= 4 ? "события" : "событий";
    return count + " " + word;
  }

  /** Запись об отложенном напоминании: время события и время повторного оповещения. */
  public static String encodeSnooze(long reminder, long at) {
    return reminder + "|" + at;
  }

  /** Возвращает {событие, оповещение} или null для повреждённой записи. */
  public static long[] decodeSnooze(String value) {
    if (value == null) return null;
    String[] parts = value.split("\\|");
    if (parts.length != 2) return null;
    try {
      return new long[] {Long.parseLong(parts[0]), Long.parseLong(parts[1])};
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
