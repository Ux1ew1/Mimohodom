package app.fastnotes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Класс заметки для вопросов агента (docs/AGENT-PLANNER.md, 2.4): модель оценивает его в том же
 * ответе проверки, без отдельного запроса. От класса зависит, о чём агент спрашивает: {@link #INFO}
 * — ни о чём, {@link #SIMPLE} — не больше одного «когда?», {@link #EVENT} — недостающее (время,
 * место, что взять), {@link #COMPLEX} — предложить «Разобрать план». Чистая Java без Android.
 */
public final class TaskKind {
  public static final String INFO = "info", SIMPLE = "simple", EVENT = "event", COMPLEX = "complex";

  /** Строка для подсказки модели: как выбирать класс и какой формат ответа. */
  public static final String GUIDE =
      "КЛАСС. Для каждой записи из входа оцени, что это, и верни в том же JSON поле"
          + " \"kinds\":{\"ID\":\"класс\"}: info — сведения без дела (код, контакт, факт,"
          + " памятка, ссылка); simple — простое дело в одно действие («купить хлеб», «позвонить"
          + " маме»); event — событие или встреча с датой («врач 5 мая», «день рождения Пети»);"
          + " complex — большая задача из многих шагов, людей или дней («организовать выезд"
          + " команды», «ремонт кухни», «переезд»).\n";

  private TaskKind() {}

  /** Один из четырёх классов или пусто, если значение незнакомо. */
  public static String clean(String raw) {
    String k = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
    return switch (k) {
      case INFO, SIMPLE, EVENT, COMPLEX -> k;
      default -> "";
    };
  }

  /** Дело (не просто сведения): о нём есть смысл спрашивать «как продвигается?». */
  public static boolean task(String kind) {
    return SIMPLE.equals(kind) || EVENT.equals(kind) || COMPLEX.equals(kind);
  }

  /**
   * Классы из ответа модели: объект {@code {"kinds":{"12":"complex"}}} или массив {@code
   * [{"id":12,"kind":"complex"}]}. Берутся только записи входа ({@code known}) и знакомые классы.
   * Ответ без поля или не JSON — пустой результат, проверка от этого не ломается.
   */
  public static Map<Long, String> parse(String text, Set<Long> known) {
    Map<Long, String> out = new LinkedHashMap<>();
    JSONObject root;
    try {
      root = new JSONObject(AgentPlan.unwrap(text == null ? "" : text));
    } catch (Exception e) {
      return out;
    }
    Object raw = root.opt("kinds");
    if (raw instanceof JSONObject o) {
      // keys(), а не keySet(): у org.json в Android keySet() нет.
      for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
        String key = it.next();
        try {
          long id = Long.parseLong(key.trim());
          String kind = clean(o.optString(key, ""));
          if (known.contains(id) && !kind.isEmpty()) out.put(id, kind);
        } catch (NumberFormatException ignored) {
          // чужой ключ пропускаем
        }
      }
    } else if (raw instanceof JSONArray a) {
      for (int i = 0; i < a.length(); i++) {
        JSONObject row = a.optJSONObject(i);
        if (row == null) continue;
        long id = row.optLong("id", -1);
        String kind = clean(row.optString("kind", ""));
        if (known.contains(id) && !kind.isEmpty()) out.put(id, kind);
      }
    }
    return out;
  }
}
