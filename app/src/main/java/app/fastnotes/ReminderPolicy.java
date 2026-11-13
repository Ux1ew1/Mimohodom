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
