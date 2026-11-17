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
 * разбирает его, отбрасывает прошедшие и повторяющиеся строки и предлагает создать по заметке с
 * напоминанием на каждую дату. Чистая Java без Android, проверяется на JVM.
 */
public final class ReminderBatch {
  public static final int MAX_ITEMS = 40;
  private static final Pattern BLOCK =
      Pattern.compile("\\[\\[reminders]](.*?)\\[\\[/reminders]]", Pattern.DOTALL);
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
    return "Создай напоминания по датам из заметки"
        + (title.trim().isEmpty() ? "" : " «" + title.trim() + "»")
        + ". Время напоминания — время начала из текста.\n\n"
        + text;
  }

  /** Разбирает блок из сырого ответа модели; без блока или без годных строк список пуст. */
  public static Result parse(String raw, ZoneId zone, long now) {
    Result result = new Result();
    Matcher block = BLOCK.matcher(raw);
    if (!block.find()) return result;
    for (String line : block.group(1).split("\\R")) {
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
          .put("skipped", result.skipped);
    } catch (org.json.JSONException e) {
      throw new IllegalStateException(e);
    }
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
    return text.replaceAll("(?s)\\[\\[reminders]].*?(?:\\[\\[/reminders]]|$)", "").trim();
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
        + " дата или время неразборчивы, пропусти строку и скажи об этом в тексте.";
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
