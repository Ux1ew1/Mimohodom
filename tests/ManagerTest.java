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
    check(payload.getInt("max_tokens") == 1200, "Existing background output budget preserved");
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
