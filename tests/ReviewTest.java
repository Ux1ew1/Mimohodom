package app.fastnotes;

import java.time.*;
import java.util.*;
import org.json.*;

public final class ReviewTest {
  static int checks;
  static final long NOW = Instant.parse("2026-11-10T00:00:00Z").toEpochMilli();

  static void check(boolean value, String label) {
    if (!value) throw new AssertionError(label);
    checks++;
  }

  static JSONObject response(String text, String finish) throws Exception {
    return new JSONObject()
        .put(
            "choices",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("finish_reason", finish)
                        .put("message", new JSONObject().put("content", text))));
  }

  static void fails(JSONObject response, List<Note> notes, String label) throws Exception {
    boolean failed = false;
    try {
      ReviewAnalysis.parse(response, notes, NOW);
    } catch (Exception e) {
      failed = true;
    }
    check(failed, label);
  }

  public static void main(String[] args) throws Exception {
    Note a = new Note(1, "Мука", "Мука и молоко", 1, 2, 0),
        b = new Note(2, "Мой рецепт", "Добавить соль", 1, 2, 0);
    a.autoTitle = true;
    List<Note> notes = Arrays.asList(a, b);
    JSONObject payload = ReviewAnalysis.payload(notes, "test", NOW, ZoneId.of("Europe/Moscow"));
    JSONArray data =
        new JSONArray(payload.getJSONArray("messages").getJSONObject(1).getString("content"));
    check(data.getJSONObject(0).getBoolean("needs_title"), "Automatic title improvement enabled");
    check(!data.getJSONObject(1).getBoolean("needs_title"), "Custom title protected");
    check(
        data.getJSONObject(0).has("created") && data.getJSONObject(0).has("has_reminder"),
        "Reminder context supplied");
    check(payload.getInt("max_tokens") == 1200, "Shared response budget retained");
    String instruction = payload.getJSONArray("messages").getJSONObject(0).getString("content");
    check(
        instruction.contains("ОБЕ проверки")
            && instruction.contains("suggestions")
            && instruction.contains("notes"),
        "Unified response contract");
    JSONObject merge =
        new JSONObject()
            .put("type", "merge")
            .put("sources", new JSONArray("[1,2]"))
            .put("reason", "Один рецепт")
            .put("title", "Рецепт")
            .put("body", "Мука, молоко и соль");
    JSONArray rows =
        new JSONArray()
            .put(
                new JSONObject()
                    .put("id", 1)
                    .put("title", "Рецепт блинов")
                    .put("reminder_at", "2030-01-01T12:00:00+03:00")
                    .put("reason", "Дата в тексте"))
            .put(
                new JSONObject()
                    .put("id", 2)
                    .put("title", "Нельзя менять")
                    .put("reminder_at", JSONObject.NULL));
    JSONObject answer =
        new JSONObject().put("notes", rows).put("suggestions", new JSONArray().put(merge));
    ReviewAnalysis.Result result =
        ReviewAnalysis.parse(response(answer.toString(), "stop"), notes, NOW);
    check(result.notes.get(1L).title.equals("Рецепт блинов"), "Title and grouping parsed together");
    check(result.notes.get(2L).title.isEmpty(), "Model cannot rename custom title");
    check(result.notes.get(1L).reminder > 0, "Reminder proposal parsed");
    check(result.proposals.size() == 1, "Merge parsed in same response");
    fails(response(answer.toString(), "length"), notes, "Truncated response is explicit error");
    fails(response("", "stop"), notes, "Empty response fails");
    fails(response("{\"suggestions\":[]}", "stop"), notes, "Missing per-note section fails");
    fails(
        response("{\"notes\":[],\"suggestions\":[]}", "stop"),
        notes,
        "Omitted notes not silently successful");
    answer.put("suggestions", new JSONArray());
    check(
        ReviewAnalysis.parse(response(answer.toString(), "stop"), notes, NOW).proposals.isEmpty(),
        "No suitable merge is valid");
    merge.put("sources", new JSONArray("[1,99]"));
    answer.put("suggestions", new JSONArray().put(merge));
    fails(
        response(answer.toString(), "stop"),
        notes,
        "Invalid source suggestions reported as failure");
    check(
        ReviewAnalysis.error(new java.net.SocketTimeoutException()).contains("вовремя"),
        "Timeout diagnosed");
    check(
        ReviewAnalysis.error(new java.io.IOException("Недостаточно средств на балансе Polza.AI."))
            .contains("средств"),
        "Balance error retained");
    check(
        !ReviewAnalysis.error(new Exception("private note or key")).contains("private"),
        "Unexpected error hides sensitive details");
    check(ReviewPolicy.allowed(0, false), "Default budget allows first request");
    check(ReviewPolicy.allowed(3, false), "Default budget allows fourth request");
    check(!ReviewPolicy.allowed(4, false), "Default budget stops fifth request");
    check(ReviewPolicy.allowed(4, true), "User can remove exhausted daily cap");
    check(ReviewPolicy.allowed(100, true), "Unlimited mode has no daily cap");
    check(!ReviewPolicy.allowed(100, false), "Restored cap respects already used requests");
    check(
        ReviewPolicy.summary("Просмотрено записей: 8 из 17. " + "Подробности ".repeat(100))
            .equals("Проверка завершена"),
        "Verbose log stays out of UI summary");
    check(
        ReviewPolicy.summary("Проверка не завершена. Ошибка")
            .equals("Не удалось завершить проверку"),
        "Short failure is distinguishable");
    check(
        ReviewPolicy.summary("Дневной лимит: 4 пакета").contains("исчерпан"),
        "Cap has clear status");
    System.out.println("PASS: " + checks + " unified review checks");
  }
}
