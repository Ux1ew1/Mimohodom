package app.fastnotes;

import java.util.*;
import org.json.*;

public final class ManagerTest {
  private static int checks;

  private static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    checks++;
  }

  public static void main(String[] args) throws Exception {
    Note a = new Note(1, "Рецепт", "Мука", 1, 2, 0), b = new Note(2, "Рецепт", "Молоко", 1, 2, 0);
    List<Note> notes = Arrays.asList(a, b);
    JSONObject row =
        new JSONObject()
            .put("type", "merge")
            .put("sources", new JSONArray("[1,2]"))
            .put("reason", "Дополняют друг друга")
            .put("title", "Рецепт")
            .put("body", "Мука и молоко");
    String text = new JSONObject().put("suggestions", new JSONArray().put(row)).toString();
    List<JSONObject> parsed = ManagerAnalysis.parse(text, notes);
    check(parsed.size() == 1, "Valid merge");
    check(
        ManagerAnalysis.parse("```json\n" + text + "\n```", notes).size() == 1, "Fenced response");
    String identity = parsed.get(0).getString("identity");
    row.put("sources", new JSONArray("[2,1]"));
    check(
        ManagerAnalysis.parse(
                new JSONObject().put("suggestions", new JSONArray().put(row)).toString(), notes)
            .get(0)
            .getString("identity")
            .equals(identity),
        "Order independent deduplication");
    row.put("sources", new JSONArray("[1,99]"));
    check(parse(row, notes).isEmpty(), "Invented source rejected");

    // Уточняющий вопрос: одна запись и 2–4 варианта ответа
    JSONObject ask =
        new JSONObject()
            .put("type", "clarify")
            .put("sources", new JSONArray("[1]"))
            .put("reason", "Что это за запись?")
            .put("options", new JSONArray("[\"Список покупок\",\"Про карту\",\"Список покупок\"]"));
    List<JSONObject> asked = parse(ask, notes);
    check(asked.size() == 1, "Clarify accepted without title and body");
    check(asked.get(0).getJSONArray("options").length() == 2, "Duplicate options removed");
    check(asked.get(0).getString("identity").startsWith("clarify|1:"), "Clarify identity per note");
    ask.put("options", new JSONArray("[\"Только один\"]"));
    check(parse(ask, notes).isEmpty(), "One option is not a question");
    ask.put("options", new JSONArray("[\"Да\",\"Нет\"]")).put("sources", new JSONArray("[1,2]"));
    check(parse(ask, notes).isEmpty(), "Clarify is about one note");
    ask.put("sources", new JSONArray("[1]")).put("reason", "я".repeat(301));
    check(parse(ask, notes).isEmpty(), "Long question rejected");

    // Подсказка модели: прошлые группы и ответы пользователя
    JSONObject base = ManagerAnalysis.payload(notes, "m");
    ManagerAnalysis.withContext(
        base, Arrays.asList(new TreeSet<>(Arrays.asList(1L, 2L))), Map.of(1L, "Список покупок"));
    String system = base.getJSONArray("messages").getJSONObject(0).getString("content");
    check(system.contains("[[1,2]]"), "Known groups handed to the model");
    check(system.contains("Список покупок"), "User answers handed to the model");
    check(system.contains("\"action\":\"ask\""), "Prompt describes clarifying questions");
    check(system.contains("\"actions\""), "Prompt asks for the agent action list");
    check(system.contains("extend"), "Prompt offers adding to an existing deck");
    check(parsed.get(0).getString("type").equals("group"), "Old merge becomes a deck card");
    // Колоды по теме: модель видит их и может предложить extend.
    Note top = new Note(10, "Рецепт блинов", "Мука, молоко, яйца", 1, 2, 0);
    JSONObject withStacks = ManagerAnalysis.payload(notes, "m");
    ManagerAnalysis.withContext(
        withStacks, Collections.emptyList(), Collections.emptyMap(), Arrays.asList(top));
    check(
        withStacks.getJSONArray("messages").getJSONObject(0).getString("content")
            .contains("\"stack\":10"),
        "Existing decks handed to the model");
    String extendText =
        "{\"actions\":[{\"action\":\"extend\",\"stack\":10,\"sources\":[2],"
            + "\"reason\":\"Тоже про блины\"}]}";
    List<JSONObject> extended = ManagerAnalysis.parse(extendText, notes, Arrays.asList(top));
    check(extended.size() == 1 && extended.get(0).getString("type").equals("extend"), "Extend card");
    check(extended.get(0).getLong("stack") == 10, "Extend keeps its deck");
    check(extended.get(0).getJSONArray("sources").length() == 2, "Deck revision is checked too");
    check(
        extended.get(0).getString("identity").startsWith("extend|2:"), "Extend identity by sources");
    check(ManagerAnalysis.parse(extendText, notes).isEmpty(), "Unknown deck rejected");
    List<JSONObject> grouped =
        ManagerAnalysis.parse(
            "{\"actions\":[{\"action\":\"group\",\"sources\":[1,2],\"title\":\"Блины\","
                + "\"reason\":\"Один рецепт\",\"trash\":[2,7]}]}",
            notes);
    check(grouped.size() == 1 && grouped.get(0).getString("body").isEmpty(), "Deck needs no text");
    check(grouped.get(0).getJSONArray("trash").toString().equals("[2]"), "Trash only from sources");
    check(
        ManagerAnalysis.payload(notes, "m").getJSONArray("messages").getJSONObject(0)
            .getString("content").contains("название магазина"),
        "Prompt rejects grouping by shared brand");
    row.put("sources", new JSONArray("[1,1]"));
    check(parse(row, notes).isEmpty(), "Duplicate source rejected");
    row.put("sources", new JSONArray("[1]"));
    check(parse(row, notes).isEmpty(), "Single-source merge rejected");
    row.put("sources", new JSONArray("[1,2]"));
    a.body = "x".repeat(12001);
    check(parse(row, notes).isEmpty(), "Truncated merge rejected");
    row.put("type", "link");
    check(parse(row, notes).size() == 1, "Truncated source allowed for links");
    JSONObject payload = ManagerAnalysis.payload(notes, "test");
    check(payload.getInt("max_tokens") == ManagerAnalysis.MAX_TOKENS && ManagerAnalysis.MAX_TOKENS == 1600, "Review output budget 1600 (stage 5 headroom)");
    // Настройка «Максимальная длина ответа»: коротко 300, обычно 2000, подробно 3000.
    check(ManagerAnalysis.answerTokens(0) == 2000, "No setting is normal 2000");
    check(ManagerAnalysis.answerTokens(300) == 300, "Short stays 300");
    check(ManagerAnalysis.answerTokens(600) == 2000, "Old normal 600 becomes 2000");
    check(ManagerAnalysis.answerTokens(1200) == 3000, "Old detailed 1200 becomes 3000");
    check(ManagerAnalysis.answerTokens(2000) == 2000 && ManagerAnalysis.answerTokens(3000) == 3000, "New values kept");
    check(ManagerAnalysis.answerMode(300) == 0 && ManagerAnalysis.answerMode(600) == 1 && ManagerAnalysis.answerMode(1200) == 2, "Spinner position");
    check(ManagerAnalysis.reviewTokens(300) == 1600, "Review never below 1600");
    check(ManagerAnalysis.reviewTokens(0) == 2000 && ManagerAnalysis.reviewTokens(3000) == 3000, "Review follows the setting");
    JSONArray sent =
        new JSONArray(payload.getJSONArray("messages").getJSONObject(1).getString("content"));
    check(
        sent.getJSONObject(0).getString("text").length()
                + sent.getJSONObject(1).getString("text").length()
            == 12000,
        "Combined text budget");
    check(!sent.getJSONObject(0).getBoolean("complete"), "Truncation disclosed");
    a.body = "Мука";
    row.put("type", "task").put("sources", new JSONArray("[1]"));
    check(parse(row, notes).size() == 1, "Task accepted");
    row.put("body", "");
    check(parse(row, notes).isEmpty(), "Empty draft rejected");
    row.put("type", "delete");
    check(parse(row, notes).isEmpty(), "Unknown action rejected");
    String before = ManagerAnalysis.fingerprint(a);
    a.image = "image.jpg";
    check(!before.equals(ManagerAnalysis.fingerprint(a)), "Attachment changes invalidate snapshot");
    check(ManagerAnalysis.parse("{\"suggestions\":[]}", notes).isEmpty(), "Empty review succeeds");
    boolean malformed = false;
    try {
      ManagerAnalysis.parse("bad", notes);
    } catch (Exception e) {
      malformed = true;
    }
    check(malformed, "Malformed response fails without actions");
    System.out.println("PASS: " + checks + " manager contract checks");
  }

  private static List<JSONObject> parse(JSONObject row, List<Note> notes) throws Exception {
    return ManagerAnalysis.parse(
        new JSONObject().put("suggestions", new JSONArray().put(row)).toString(), notes);
  }
}
