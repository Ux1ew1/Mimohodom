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
      REPEAT_YEARLY = 4,
      /** Серия по списку дат: в {@code notes.reminder_times} лежат все даты, в {@code reminder} — ближайшая. */
      REPEAT_LIST = 5;
  /** Сколько дат может быть у одной заметки. */
  public static final int MAX_TIMES = 40;
  public static final int DIGEST_DEFAULT_MINUTES = 8 * 60;
  /** Допустимые значения «напомнить за N минут»: от момента события до недели. */
  public static final int[] LEADS = {0, 5, 10, 15, 30, 60, 120, 1440, 2880, 4320, 10080};
  /** Подсказка для модели: как выбирать, за сколько напомнить. */
  public static final String LEAD_GUIDE =
      "lead_minutes — за сколько минут ДО события напомнить, одно из 0, 15, 60, 120, 1440, 2880,"
          + " 4320, 10080 (10080 — неделя). Выбирай по смыслу дела, а не всегда 0: встреча,"
          + " звонок, сеанс, эфир — 15 или 60; поездка, врач, экзамен, спектакль, собеседование,"
          + " где нужно собраться, — 120 или 1440; оплата, документы, заявка, срок сдачи,"
          + " подарок, покупка билетов, всё, что надо успеть сделать или купить заранее, — 2880 или"
          + " 4320; крупное событие или дедлайн с долгой подготовкой — 10080; если дело в тот же"
          + " день без подготовки — 0. В reason одной фразой объясни, почему напомнить именно за"
          + " это время.";

  private ReminderPolicy() {}

  /**
   * Проверяет «за сколько напомнить», предложенное моделью: значение должно быть из списка, а время
   * оповещения (событие минус интервал) не раньше {@code now}. Если интервал велик, берётся
   * наибольший допустимый меньший; без значения или при ошибке — -1 (действует общая настройка).
   */
  public static int cleanSuggestedLead(Object raw, long at, long now) {
    if (!(raw instanceof Number)) return -1;
    double value = ((Number) raw).doubleValue();
    if (value != Math.rint(value)) return -1;
    int lead = -1;
    for (int allowed : LEADS) if (allowed == (int) value) lead = allowed;
    if (lead < 0) return -1;
    int best = 0;
    for (int allowed : LEADS) if (allowed <= lead && at - allowed * 60_000L >= now) best = allowed;
    return best;
  }

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
      case REPEAT_LIST -> "По списку дат";
      default -> "Не повторять";
    };
  }

  /**
   * Ближайшее повторение строго после {@code now}, с тем же временем суток. Если в месяце нет
   * нужного числа (31-е, 29 февраля), берётся последний день месяца. Для {@link #REPEAT_NONE} и
   * {@link #REPEAT_LIST} (у неё следующую дату даёт {@link #nextInList}) возвращает исходное время.
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

  // ---- Серия по списку дат (одна заметка — несколько дат) ----

  /**
   * Разбирает список дат {@code notes.reminder_times}: JSON-массив моментов в миллисекундах, например
   * {@code [1793700000000,1793786400000]}. Нечисла и неположительные значения отбрасываются, повторы
   * убираются, даты сортируются, остаются не больше {@link #MAX_TIMES} самых ранних. Пустая или
   * повреждённая строка — пустой список.
   */
  public static long[] parseTimes(String raw) {
    if (raw == null) return new long[0];
    String text = raw.trim();
    if (text.startsWith("[")) text = text.substring(1);
    if (text.endsWith("]")) text = text.substring(0, text.length() - 1);
    java.util.TreeSet<Long> set = new java.util.TreeSet<>();
    for (String part : text.split(",")) {
      String p = part.trim();
      if (p.isEmpty()) continue;
      try {
        long t = Long.parseLong(p);
        if (t > 0) set.add(t);
      } catch (NumberFormatException e) {
        // мусор пропускаем
      }
    }
    long[] out = new long[Math.min(set.size(), MAX_TIMES)];
    int i = 0;
    for (long t : set) {
      if (i >= out.length) break;
      out[i++] = t;
    }
    return out;
  }

  /** Обратное к {@link #parseTimes}: упорядоченный массив без повторов; пустой список — "". */
  public static String encodeTimes(long[] times) {
    long[] clean = parseTimes(join(times));
    return clean.length == 0 ? "" : "[" + join(clean) + "]";
  }

  private static String join(long[] times) {
    StringBuilder out = new StringBuilder();
    if (times != null)
      for (long t : times) {
        if (out.length() > 0) out.append(',');
        out.append(t);
      }
    return out.toString();
  }

  /**
   * Следующая дата серии после наступившей: самая ранняя из списка позже и события {@code reminder},
   * и {@code now} (промежуточные даты, пропущенные при выключенном телефоне, не догоняются). Если
   * дат впереди нет, серия закончилась — возвращается {@code reminder}.
   */
  public static long nextInList(long reminder, long[] times, long now) {
    long after = Math.max(reminder, now);
    for (long t : times) if (t > after) return t;
    return reminder;
  }

  /**
   * Какая дата должна стоять в {@code reminder} для списка: ближайшая будущая; если все прошли —
   * последняя; пустой список — 0 (напоминания нет).
   */
  public static long currentInList(long[] times, long now) {
    if (times.length == 0) return 0;
    for (long t : times) if (t > now) return t;
    return times[times.length - 1];
  }

  /** Список с добавленной датой (без повтора). */
  public static long[] withTime(long[] times, long at) {
    long[] out = java.util.Arrays.copyOf(times, times.length + 1);
    out[times.length] = at;
    return parseTimes(join(out));
  }

  /** Список без даты {@code at}. */
  public static long[] withoutTime(long[] times, long at) {
    return java.util.Arrays.stream(times).filter(t -> t != at).toArray();
  }

  /** Перенос одной даты без потери остальных. Пустой результат означает конфликт. */
  public static long[] moveInList(long[] times, long from, long to) {
    if (to <= 0 || java.util.Arrays.binarySearch(times, from) < 0
        || (from != to && java.util.Arrays.binarySearch(times, to) >= 0)) return new long[0];
    return withTime(withoutTime(times, from), to);
  }

  /** Сколько дат списка ещё впереди. */
  public static int remaining(long[] times, long now) {
    int count = 0;
    for (long t : times) if (t > now) count++;
    return count;
  }

  /** «1 дата», «3 даты», «5 дат». */
  public static String dateCount(int count) {
    int hundreds = count % 100, units = count % 10;
    String word =
        hundreds >= 11 && hundreds <= 14
            ? "дат"
            : units == 1 ? "дата" : units >= 2 && units <= 4 ? "даты" : "дат";
    return count + " " + word;
  }

  /** Повторяется ли напоминание по правилу (каждый день … каждый год), а не по списку дат. */
  public static boolean periodic(int mode) {
    return mode >= REPEAT_DAILY && mode <= REPEAT_YEARLY;
  }

  /**
   * Пора ли передвигать доставленное повторяющееся напоминание: только когда наступило само событие
   * (с допуском в секунду на неточность будильника), а не время предупреждения «за N минут».
   */
  public static boolean advanceDue(long reminder, long now) {
    return reminder > 0 && now >= reminder - 1000;
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
