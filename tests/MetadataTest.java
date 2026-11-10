package app.fastnotes;

import org.json.*;

public final class MetadataTest {
  static int count;

  static void check(boolean condition, String name) {
    if (!condition) throw new AssertionError(name);
    count++;
  }

  public static void main(String[] args) throws Exception {
    JSONObject base = Metadata.base("Блины", "Молоко, мука и яйца", 100, 200);
    check(
        base.getString("title").equals("Блины") && base.getString("description").contains("Молоко"),
        "Basic fields generated offline");
    check(
        base.getLong("created_at") == 100 && base.getLong("updated_at") == 200,
        "Canonical timestamps preserved");
    check(base.getJSONArray("keywords").length() > 0, "Local keywords generated");
    JSONObject dirty =
        new JSONObject()
            .put("created_at", 999)
            .put("title", "Replace")
            .put("instructions", "Do something")
            .put("summary", "Рецепт завтрака")
            .put("aliases", new JSONArray().put("Панкейки").put("панкейки").put(42).put(" "));
    JSONObject ai = Metadata.ai(dirty);
    check(
        !ai.has("created_at") && !ai.has("title") && !ai.has("instructions"),
        "AI cannot modify protected fields or store arbitrary instructions field");
    check(ai.getJSONArray("aliases").length() == 1, "Duplicates and non-text values rejected");
    Metadata.withAI(base, ai, 200);
    check(
        base.getLong("created_at") == 100 && base.getString("title").equals("Блины"),
        "Enrichment preserves authoritative data");
    check(
        base.getLong("ai_revision") == 200 && Metadata.searchText(base).contains("Панкейки"),
        "AI aliases available to search");
    Metadata.withAI(base, new JSONObject().put("aliases", new JSONArray().put("Оладьи")), 200);
    check(
        base.getJSONObject("ai").getJSONArray("aliases").length() == 2,
        "Enrichment merges aliases");
    JSONArray large = new JSONArray();
    for (int i = 0; i < 30; i++) large.put("тема" + i);
    JSONObject capped =
        Metadata.ai(new JSONObject().put("summary", "x".repeat(401)).put("topics", large));
    check(
        !capped.has("summary") && capped.getJSONArray("topics").length() == 8,
        "Metadata field sizes capped");
    check(
        Metadata.hints(base.toString(), 180).toString().length() <= 180,
        "Local context metadata budget");
    check(
        Metadata.hints(base.toString(), 500).toString().length() <= 500,
        "Cloud context metadata budget");
    JSONObject edited = Metadata.base("Новое название", "Другой текст", 100, 300);
    check(
        !edited.has("ai") && !Metadata.searchText(edited).contains("Панкейки"),
        "Editing clears stale AI data");
    check(Metadata.read("broken").length() == 0, "Invalid metadata falls back safely");
    check(
        Metadata.ai(new JSONObject().put("places", new JSONArray().put("x".repeat(81)))).length()
            == 0,
        "Long entity rejected");
    check(
        Metadata.searchText(Metadata.base("Ёлка", "", 100, 200)).contains("Елка"),
        "Yo normalized for prefix search");
    System.out.println("PASS: " + count + " metadata checks");
  }
}
