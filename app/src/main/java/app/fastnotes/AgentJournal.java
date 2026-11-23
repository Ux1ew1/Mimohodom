package app.fastnotes;

import java.util.*;
import org.json.*;

/**
 * Правила журнала агента без Android: что пишется о каждом применённом действии, сколько его
 * можно отменить и как оно называется в колокольчике. Сама таблица — {@code AgentLog}.
 *
 * <p>Записи: {@code name} (агент сам дал название), {@code create} (по кнопке создана заметка),
 * {@code group} (по кнопке собрана колода), {@code extend} (по кнопке записи добавлены в колоду и
 * обновлена сводка), {@code answer} (ответ на уточняющий вопрос). В {@code data} — что сделано и
 * что нужно, чтобы вернуть как было.
 */
public final class AgentJournal {
  /** «Отменить» доступно сутки после действия. */
  public static final long UNDO_WINDOW = 24 * 3_600_000L;
  /** Журнал хранится 30 дней, как корзина. */
  public static final long KEEP = 30 * 86_400_000L;

  public static final String NAME = "name",
      CREATE = "create",
      GROUP = "group",
      EXTEND = "extend",
      ANSWER = "answer",
      COMMAND_BATCH = "command_batch";
  public static final String DONE = "done", UNDONE = "undone";

  private AgentJournal() {}

  public static boolean canUndo(String state, long created, long now) {
    return DONE.equals(state) && now - created < UNDO_WINDOW;
  }

  public static boolean expired(long created, long now) {
    return now - created > KEEP;
  }

  /** Сколько ещё можно отменить: «ещё 5 ч», «ещё 40 мин»; пусто — уже нельзя. */
  public static String left(String state, long created, long now) {
    if (!canUndo(state, created, now)) return "";
    long rest = Math.min(UNDO_WINDOW, created + UNDO_WINDOW - now);
    if (rest >= 3_600_000L) return "ещё " + rest / 3_600_000L + " ч";
    return "ещё " + Math.max(1, rest / 60_000L) + " мин";
  }

  // --- что записать ---

  public static JSONObject nameData(long note, String title, String before) throws JSONException {
    return new JSONObject().put("note", note).put("title", title).put("before", before);
  }

  public static JSONObject createData(long note, String title, long proposal)
      throws JSONException {
    return new JSONObject().put("note", note).put("title", title).put("proposal", proposal);
  }

  /**
   * Стопка: новая верхняя заметка, все заметки под ней и прежние стопки, которые в неё вошли
   * (верхняя → исходные), чтобы «Отменить» вернуло их как были.
   */
  public static JSONObject groupData(
      long top, String title, Collection<Long> members, Map<Long, List<Long>> before, long proposal)
      throws JSONException {
    return groupData(top, title, members, before, proposal, List.of());
  }

  /** То же и исходные, ушедшие в корзину по галочке «Пустые и дубли — в корзину». */
  public static JSONObject groupData(
      long top,
      String title,
      Collection<Long> members,
      Map<Long, List<Long>> before,
      long proposal,
      Collection<Long> trashed)
      throws JSONException {
    JSONArray gone = new JSONArray();
    for (long id : trashed) gone.put(id);
    JSONArray ids = new JSONArray();
    for (long id : members) ids.put(id);
    JSONObject old = new JSONObject();
    for (Map.Entry<Long, List<Long>> e : before.entrySet()) {
      JSONArray inside = new JSONArray();
      for (long id : e.getValue()) inside.put(id);
      old.put("" + e.getKey(), inside);
    }
    return new JSONObject()
        .put("note", top)
        .put("title", title)
        .put("members", ids)
        .put("before", old)
        .put("trashed", gone)
        .put("proposal", proposal);
  }

  /**
   * extend: колода, её сводка после обновления (по ней «Отменить» проверяет, что пользователь
   * ничего не менял), прежняя сводка и добавленные записи.
   */
  public static JSONObject extendData(
      long top,
      String title,
      String body,
      String wasTitle,
      String wasBody,
      Collection<Long> added,
      long proposal)
      throws JSONException {
    JSONArray ids = new JSONArray();
    for (long id : added) ids.put(id);
    return new JSONObject()
        .put("note", top)
        .put("title", title)
        .put("body", body)
        .put("was_title", wasTitle)
        .put("was_body", wasBody)
        .put("added", ids)
        .put("proposal", proposal);
  }

  /** Список номеров из поля записи ({@code trashed}, {@code added}). */
  public static List<Long> ids(JSONObject data, String field) {
    List<Long> out = new ArrayList<>();
    JSONArray rows = data.optJSONArray(field);
    for (int i = 0; rows != null && i < rows.length(); i++) out.add(rows.optLong(i));
    return out;
  }

  public static JSONObject answerData(long note, String question, String answer, long proposal)
      throws JSONException {
    return new JSONObject()
        .put("note", note)
        .put("question", question)
        .put("answer", answer)
        .put("proposal", proposal);
  }

  /** Прежние стопки из записи {@link #groupData}: верхняя → исходные. */
  public static Map<Long, List<Long>> before(JSONObject data) {
    Map<Long, List<Long>> out = new LinkedHashMap<>();
    JSONObject old = data.optJSONObject("before");
    if (old == null) return out;
    for (Iterator<String> keys = old.keys(); keys.hasNext(); ) {
      String key = keys.next();
      JSONArray inside = old.optJSONArray(key);
      List<Long> ids = new ArrayList<>();
      for (int i = 0; inside != null && i < inside.length(); i++) ids.add(inside.optLong(i));
      try {
        out.put(Long.parseLong(key), ids);
      } catch (NumberFormatException ignored) {
      }
    }
    return out;
  }

  // --- как показать ---

  /** Одна строка для колокольчика и журнала. */
  public static String describe(String action, JSONObject data) {
    String title = data.optString("title");
    return switch (action) {
      case NAME -> "Название «" + title + "»" + (data.optString("before").isEmpty()
          ? ""
          : " вместо «" + data.optString("before") + "»");
      case CREATE -> "Создана заметка «" + title + "»";
      case GROUP -> {
        int n = data.optJSONArray("members") == null ? 0 : data.optJSONArray("members").length();
        int gone = ids(data, "trashed").size();
        yield "Собрана колода «"
            + title
            + "»"
            + (n > 0 ? " из " + n + " " + notes(n) : "")
            + (gone > 0 ? ", в корзину: " + gone : "");
      }
      case EXTEND -> {
        int n = ids(data, "added").size();
        yield "В колоду «" + title + "» добавлено: " + n + ", сводка обновлена";
      }
      case ANSWER -> "Ответ «" + data.optString("answer") + "» на вопрос «"
          + data.optString("question") + "»";
      case COMMAND_BATCH -> {
        int changes = (data.optJSONArray("created_notes") == null ? 0 : data.optJSONArray("created_notes").length())
            + (data.optJSONArray("folders") == null ? 0 : data.optJSONArray("folders").length())
            + (data.optJSONArray("reminders") == null ? 0 : data.optJSONArray("reminders").length());
        yield "Команда: " + data.optString("command") + (changes > 0 ? " · изменений: " + changes : "");
      }
      default -> "Действие помощника";
    };
  }

  /** Что произойдёт по «Отменить» — для подтверждения. */
  public static String undoHint(String action) {
    return switch (action) {
      case NAME -> "Вернётся прежнее название, и помощник больше не будет его менять.";
      case CREATE -> "Заметка уйдёт в корзину, предложение не вернётся.";
      case GROUP ->
          "Сводка уйдёт в корзину, исходные заметки вернутся в список (и из корзины, если ушли"
              + " туда по галочке); прежние колоды соберутся как были.";
      case EXTEND -> "Вернётся прежняя сводка колоды, добавленные заметки — обратно в список.";
      case ANSWER -> "Ответ забудется, вопрос снова появится в колокольчике.";
      case COMMAND_BATCH ->
          "Новые заметки уйдут в корзину, папки и напоминания вернутся к состоянию до пакета."
              + " Если что-то уже изменено вручную, отмена не выполняется частично.";
      default -> "";
    };
  }

  static String notes(int n) {
    int mod10 = n % 10, mod100 = n % 100;
    if (mod10 == 1 && mod100 != 11) return "заметки";
    return "заметок";
  }
}
