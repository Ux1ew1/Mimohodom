package app.fastnotes;

import java.time.*;
import java.util.*;
import org.json.*;

/** One bounded request for the manual review, with both response sections required. */
public final class ReviewAnalysis {
  public static JSONObject payload(List<Note> notes, String model, long now, ZoneId zone)
      throws Exception {
    JSONObject payload = ManagerAnalysis.payload(notes, model);
    JSONArray messages = payload.getJSONArray("messages");
    String perNote =
        Analysis.payload(notes, model, now, zone)
            .getJSONArray("messages")
            .getJSONObject(0)
            .getString("content");
    JSONObject instruction = messages.getJSONObject(0);
    instruction.put(
        "content",
        instruction.getString("content")
            + "\n"
            + perNote
            + "\n"
            + "Выполни ОБЕ проверки в одном ответе: {\"notes\":[...],\"actions\":[...],"
            + "\"kinds\":{...}}. Массив notes должен содержать каждый входной ID, kinds — класс"
            + " каждой записи. Улучшай только автоматические заголовки с"
            + " needs_title=true; авторские заголовки сохраняй. Для actions вначале сопоставь"
            + " записи между собой и с колодами stacks: близкие дубли и дополняющие записи могут"
            + " подходить для колоды. Не объединяй разные темы ради группировки. Если подходящих"
            + " записей нет, actions=[]. Экономь ответ: до 2 действий, до 1 колоды (group или"
            + " extend), краткие объяснения, metadata необязательна. Для metadata достаточно"
            + " summary до 100 символов и до 2 aliases. Умести оба массива в 1200 токенов.");
    JSONArray data = new JSONArray(messages.getJSONObject(1).getString("content"));
    for (int i = 0; i < notes.size(); i++) {
      Note n = notes.get(i);
      data.getJSONObject(i)
          .put("needs_title", n.autoTitle)
          .put("has_reminder", n.reminder > 0)
          .put(
              "created", Instant.ofEpochMilli(n.created).atZone(zone).toOffsetDateTime().toString())
          .put(
              "updated",
              Instant.ofEpochMilli(n.updated).atZone(zone).toOffsetDateTime().toString());
    }
    messages.getJSONObject(1).put("content", data.toString());
    return payload;
  }

  public static final class Result {
    public List<JSONObject> proposals;
    public Map<Long, Analysis.Result> notes;
  }

  public static Result parse(JSONObject response, List<Note> notes, long now) throws Exception {
    return parse(response, notes, Collections.emptyList(), now);
  }

  /** {@code stacks} — верхние заметки колод, показанных модели для extend. */
  public static Result parse(JSONObject response, List<Note> notes, List<Note> stacks, long now)
      throws Exception {
    JSONObject choice = response.getJSONArray("choices").getJSONObject(0);
    if ("length".equals(choice.optString("finish_reason")))
      throw new Failure(
          "Ответ модели оборвался по лимиту 1600 токенов. Попробуйте другую модель или более"
              + " короткие заметки.");
    String text = choice.getJSONObject("message").optString("content", "");
    if (text.trim().isEmpty())
      throw new Failure("Модель вернула пустой ответ. Попробуйте другую модель.");
    Result result = new Result();
    result.proposals = ManagerAnalysis.parse(text, notes, stacks);
    JSONObject root = new JSONObject(AgentPlan.unwrap(text));
    // Раздел действий обязателен (новый «actions» или прежний «suggestions»), иначе формат неверный.
    JSONArray actions = root.optJSONArray("actions");
    if (actions == null) actions = root.getJSONArray("suggestions");
    if (actions.length() > 0 && result.proposals.isEmpty() && !onlyNames(actions))
      throw new Failure(
          "Модель предложила действия с неверными источниками или неполным текстом. Предложения не"
              + " применены; повторите вручную.");
    result.notes = Analysis.parse(text, notes, now);
    if (result.notes.size() != notes.size())
      throw new Failure(
          "Модель проверила не все записи. Результат не применён; повторите проверку вручную.");
    return result;
  }

  /** Модель могла прислать в actions только названия — это не ошибка источников. */
  private static boolean onlyNames(JSONArray actions) {
    for (int i = 0; i < actions.length(); i++) {
      JSONObject a = actions.optJSONObject(i);
      if (a == null || !"name".equals(a.optString("action"))) return false;
    }
    return true;
  }

  public static final class Failure extends Exception {
    public Failure(String message) {
      super(message);
    }
  }

  public static String error(Exception error) {
    if (error instanceof Failure) return error.getMessage();
    if (error instanceof java.net.SocketTimeoutException) return "Сервер не ответил вовремя.";
    if (error instanceof java.net.UnknownHostException)
      return "Не удалось подключиться к Polza.AI. Проверьте интернет.";
    if (error instanceof JSONException)
      return "Модель вернула неверный формат ответа. Нужны оба раздела: проверка заметок и"
                 + " предложения.";
    String message = error.getMessage();
    if (message != null
        && (message.equals("Проверьте API-ключ и доступ к модели в настройках.")
            || message.equals("Недостаточно средств на балансе Polza.AI.")
            || message.equals("Превышен лимит запросов. Попробуйте позже.")
            || message.matches("Polza\\.AI вернул ошибку [0-9]{3}\\. Повторите запрос позже\\.")))
      return message;
    return "Не удалось завершить запрос или сохранить результат.";
  }
}
