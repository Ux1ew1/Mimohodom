package app.fastnotes;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Пачка напоминаний из ответа ИИ: «напомни о каждом из этих дней». Модель выдаёт блок
 * {@code [[reminders]]} со строками {@code 2026-11-06 16:00 | Название | пояснение}; приложение
 * разбирает его, отбрасывает прошедшие и повторяющиеся строки и предлагает одну заметку со всеми
 * датами (серия {@link ReminderPolicy#REPEAT_LIST}) — или, если модель пометила даты разными делами
 * ({@code [[reminders separate]]}), по заметке на каждую дату. Если запрос пришёл из заметки, даты
 * можно добавить в неё. Чистая Java без Android, проверяется на JVM.
 */
public final class ReminderBatch {
  public static final int MAX_ITEMS = 40;
  private static final Pattern BLOCK =
      Pattern.compile("\\[\\[reminders(\\s+separate)?\\s*]](.*?)\\[\\[/reminders]]", Pattern.DOTALL);
  /** Начало запроса «напоминания по датам из заметки»: по нему чат узнаёт заметку-источник. */
  public static final String REQUEST = "Создай напоминания по датам из заметки";
  private static final String TIME_HINT = ". Время напоминания — время начала из текста.\n\n";
  private static final Pattern SCHEDULE_ROW = Pattern.compile(
      "^\\s*[-•*]?\\s*(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})\\s+"
          + "(?:(?:пн|вт|ср|чт|пт|сб|вс)\\s+)?(\\d{1,2}):(\\d{2})\\s+[—–-]\\s+(.+)$");
  private static final Pattern LINE =
      Pattern.compile("^\\s*[-*•]?\\s*(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{1,2}):(\\d{2})\\s*\\|(.*)$");

  public static final class Item {
    public final long at;
    public final String title, body;

    public Item(long at, String title, String body) {
      this.at = at;
      this.title = title;
      this.body = body;
    }
  }

  public static final class Result {
    public final List<Item> items = new ArrayList<>();
    /** Строки, которые не вошли: время прошло, повтор или не разобралось. */
    public int skipped;
    /** Модель считает даты разными делами: по умолчанию предлагать отдельные заметки. */
    public boolean separate;
  }

  /** Пользователь просит поставить напоминания (может быть несколько). */
  public static boolean wants(String query) {
    String q = query.toLowerCase(Locale.ROOT);
    if (q.contains("не напом") || q.contains("не надо напом") || q.contains("не нужно напом"))
      return false;
    return q.contains("напом");
  }

  private static final Pattern DATE_IN_TEXT =
      Pattern.compile("(?<![\\d.])(\\d{1,2})[./](\\d{1,2})(?:[./](?:\\d{2}|\\d{4}))?(?![\\d])");

  /** В тексте заметки две и больше дат вида 06.11 или 06.11.26: есть смысл предложить пачку. */
  public static boolean manyDates(String body) {
    int found = 0;
    Matcher m = DATE_IN_TEXT.matcher(body);
    while (m.find()) {
      int day = Integer.parseInt(m.group(1)), month = Integer.parseInt(m.group(2));
      if (day >= 1 && day <= 31 && month >= 1 && month <= 12 && ++found >= 2) return true;
    }
    return false;
  }

  /** Вопрос для чата по тексту заметки: пользователь видит его и может поправить перед отправкой. */
  public static String requestFor(String title, String body) {
    String text = body.length() > 4000 ? body.substring(0, 4000) : body;
    return REQUEST
        + (title.trim().isEmpty() ? "" : " «" + title.trim() + "»")
        + TIME_HINT
        + text;
  }

  /** Готовое расписание разбираем только целиком. Свободный текст остаётся модели. */
  static Result schedule(String body, ZoneId zone, long now) {
    Result result = new Result();
    for (String line : body.split("\\R")) {
      if (line.trim().isEmpty()) continue;
      Matcher row = SCHEDULE_ROW.matcher(line);
      if (!row.matches()) return new Result();
      try {
        long at = LocalDateTime.of(Integer.parseInt(row.group(3)), Integer.parseInt(row.group(2)),
            Integer.parseInt(row.group(1)), Integer.parseInt(row.group(4)), Integer.parseInt(row.group(5)))
            .atZone(zone).toInstant().toEpochMilli();
        String title = row.group(6).trim();
        if (title.isEmpty()) return new Result();
        if (at <= now || duplicate(result, at, title)) result.skipped++;
        else result.items.add(new Item(at, title, ""));
        if (result.items.size() > MAX_ITEMS) return new Result();
      } catch (java.time.DateTimeException e) {
        return new Result();
      }
    }
    result.items.sort((a, b) -> Long.compare(a.at, b.at));
    return result;
  }

  static Result requestSchedule(String query, ZoneId zone, long now) {
    int split = query.indexOf(TIME_HINT);
    if (!query.startsWith(REQUEST) || split < REQUEST.length()) return new Result();
    String title = query.substring(REQUEST.length(), split);
    if (!title.isEmpty() && !title.matches(" «[^»\\r\\n]*»")) return new Result();
    return schedule(query.substring(split + TIME_HINT.length()), zone, now);
  }

  static long[] offeredTimes(String body, long suggestion, String encoded, ZoneId zone, long now) {
    long[] saved = ReminderPolicy.parseTimes(encoded);
    if (saved.length > 1) return saved;
    long[] explicit = times(schedule(body, zone, now).items);
    return java.util.Arrays.binarySearch(explicit, suggestion) >= 0 ? explicit : saved;
  }

  /** Разбирает блок из сырого ответа модели; без блока или без годных строк список пуст. */
  public static Result parse(String raw, ZoneId zone, long now) {
    Result result = new Result();
    Matcher block = BLOCK.matcher(raw);
    if (!block.find()) return result;
    result.separate = block.group(1) != null;
    for (String line : block.group(2).split("\\R")) {
      if (line.trim().isEmpty()) continue;
      Matcher m = LINE.matcher(line);
      if (!m.matches()) {
        result.skipped++;
        continue;
      }
      long at;
      try {
        at =
            LocalDateTime.of(
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)),
                    Integer.parseInt(m.group(5)))
                .atZone(zone)
                .toInstant()
                .toEpochMilli();
      } catch (java.time.DateTimeException e) {
        result.skipped++;
        continue;
      }
      String[] rest = m.group(6).split("\\|", 2);
      String title = rest[0].trim();
      String body = rest.length > 1 ? rest[1].trim() : "";
      if (title.codePointCount(0, title.length()) > 80)
        title = title.substring(0, title.offsetByCodePoints(0, 80)).trim();
      if (title.isEmpty() || at <= now || result.items.size() >= MAX_ITEMS || duplicate(result, at, title)) {
        result.skipped++;
        continue;
      }
      result.items.add(new Item(at, title, body));
    }
    result.items.sort((a, b) -> Long.compare(a.at, b.at));
    return result;
  }

  /** Вид для столбца {@code messages.draft}: {@code {"kind":"reminders","items":[…]}}. */
  public static org.json.JSONObject toJson(Result result) {
    try {
      org.json.JSONArray items = new org.json.JSONArray();
      for (Item i : result.items)
        items.put(
            new org.json.JSONObject().put("at", i.at).put("title", i.title).put("body", i.body));
      return new org.json.JSONObject()
          .put("kind", "reminders")
          .put("items", items)
          .put("skipped", result.skipped)
          .put("separate", result.separate);
    } catch (org.json.JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Даты разными делами (отдельные заметки по умолчанию). */
  public static boolean separate(org.json.JSONObject draft) {
    return draft != null && draft.optBoolean("separate");
  }

  /** Заметка, из которой пришёл запрос (0 — запрос набран в чате). */
  public static long source(org.json.JSONObject draft) {
    return draft == null ? 0 : Math.max(0, draft.optLong("note"));
  }

  /** Даты строк по порядку, без повторов. */
  public static long[] times(List<Item> items) {
    long[] out = new long[items.size()];
    for (int i = 0; i < out.length; i++) out[i] = items.get(i).at;
    return ReminderPolicy.parseTimes(ReminderPolicy.encodeTimes(out));
  }

  /**
   * Название и текст одной заметки со всеми датами: общее название строк (или первое «и ещё N»), в
   * тексте — по строке на дату с пояснением.
   */
  public static String[] combined(List<Item> items, ZoneId zone) {
    if (items.isEmpty()) return new String[] {"", ""};
    boolean same = true;
    for (Item i : items) same &= i.title.equalsIgnoreCase(items.get(0).title);
    String title = same ? items.get(0).title : items.get(0).title + " и ещё " + (items.size() - 1);
    StringBuilder body = new StringBuilder();
    for (Item i : items) {
      if (body.length() > 0) body.append('\n');
      body.append("• ").append(label(i.at, zone));
      if (!same) body.append(" — ").append(i.title);
      if (!i.body.isEmpty()) body.append(same ? " — " : ". ").append(i.body);
    }
    return new String[] {title, body.toString()};
  }

  /**
   * Даты заметки после добавления новых: прежний список серии или прежнее разовое будущее
   * напоминание плюс новые. У заметки с повтором по правилу (каждый день …) списка нет — для неё
   * вызывающий делает новую заметку ({@link #canMerge}).
   */
  public static long[] mergedTimes(long reminder, int repeat, String times, long[] add, long now) {
    long[] out =
        repeat == ReminderPolicy.REPEAT_LIST
            ? ReminderPolicy.parseTimes(times)
            : reminder > now ? new long[] {reminder} : new long[0];
    for (long t : add) out = ReminderPolicy.withTime(out, t);
    return out;
  }

  /** Можно ли добавить даты в заметку: у неё нет повтора по правилу. */
  public static boolean canMerge(int repeat) {
    return !ReminderPolicy.periodic(repeat);
  }

  public static boolean isBatch(org.json.JSONObject draft) {
    return draft != null && "reminders".equals(draft.optString("kind"));
  }

  public static List<Item> items(org.json.JSONObject draft) {
    List<Item> out = new ArrayList<>();
    org.json.JSONArray items = draft.optJSONArray("items");
    if (items == null) return out;
    for (int i = 0; i < items.length(); i++) {
      org.json.JSONObject o = items.optJSONObject(i);
      if (o != null && o.optLong("at") > 0 && !o.optString("title").isEmpty())
        out.add(new Item(o.optLong("at"), o.optString("title"), o.optString("body")));
    }
    return out;
  }

  private static boolean duplicate(Result result, long at, String title) {
    for (Item i : result.items) if (i.at == at && i.title.equalsIgnoreCase(title)) return true;
    return false;
  }

  /** Текст ответа без служебного блока (в том числе оборванного по лимиту). */
  public static String strip(String text) {
    return text.replaceAll("(?s)\\[\\[reminders[^\\]]*]].*?(?:\\[\\[/reminders]]|$)", "").trim();
  }

  /** Подсказка модели о формате блока; добавляется к системному промпту. */
  public static String guidance() {
    return "Если пользователь просит напомнить о событиях (одном или нескольких), не утверждай, что"
        + " напоминания уже созданы: подготовь предложение и коротко перечисли события. В самом"
        + " конце ответа добавь ровно один блок, по строке на событие, время по часовому поясу"
        + " пользователя: [[reminders]]\n"
        + "2026-11-06 16:00 | Название до 80 символов | необязательное пояснение\n"
        + "[[/reminders]]. Дата полностью (год-месяц-день), время 24 часа. Если пользователь просит"
        + " сдвинуть время (например, за 2 часа до начала), впиши уже посчитанное время. Только"
        + " будущие даты, год возьми из контекста и сегодняшней даты. Не выдумывай события, а если"
        + " дата или время неразборчивы, пропусти строку и скажи об этом в тексте. Если все даты —"
        + " одно дело (расписание занятий, курс, серия приёмов, график), пиши блок как есть:"
        + " приложение сделает одну заметку со всеми датами. Если даты — разные дела, открой блок"
        + " строкой [[reminders separate]] вместо [[reminders]]: каждая дата станет отдельной"
        + " заметкой.";
  }

  /** Подпись для списка: «06.11 пт 16:00». */
  public static String label(long at, ZoneId zone) {
    java.time.ZonedDateTime t = java.time.Instant.ofEpochMilli(at).atZone(zone);
    String[] days = {"пн", "вт", "ср", "чт", "пт", "сб", "вс"};
    return String.format(
        Locale.ROOT,
        "%02d.%02d.%d %s %02d:%02d",
        t.getDayOfMonth(),
        t.getMonthValue(),
        t.getYear(),
        days[t.getDayOfWeek().getValue() - 1],
        t.getHour(),
        t.getMinute());
  }
}
