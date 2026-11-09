package app.fastnotes;

import java.time.*;
import java.util.*;
import org.json.*;

public final class AnalysisTest {
  static int count;

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    count++;
  }

  static String row(long id, String title, String date, String reason) throws Exception {
    return new JSONObject()
        .put(
            "notes",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("id", id)
                        .put("title", title)
                        .put("reminder_at", date == null ? JSONObject.NULL : date)
                        .put("reason", reason)))
        .toString();
  }

  public static void main(String[] args) throws Exception {
    long now = Instant.parse("2026-11-05T10:00:00Z").toEpochMilli();
    Note note =
        new Note(1, "Завтра", "Завтра в 18:00 забрать заказ", now - 86400000, now - 86400000, 0);
    note.autoTitle = true;
    List<Note> notes = Arrays.asList(note);
    JSONObject payload = Analysis.payload(notes, "test/model", now, ZoneId.of("Europe/Moscow"));
    JSONArray messages = payload.getJSONArray("messages");
    JSONObject context =
        new JSONArray(messages.getJSONObject(1).getString("content")).getJSONObject(0);
    check(
        context.getString("created").equals("2026-11-04T13:00+03:00"),
        "Creation date and offset supplied");
    check(
        context.has("updated") && context.getBoolean("needs_title"),
        "Edit date and title provenance supplied");
    check(
        messages.getJSONObject(0).getString("content").contains("от created"),
        "Relative dates anchored to creation");
    check(payload.getInt("max_tokens") == 1200, "Output budget");
    String valid = row(1, "Забрать заказ", "2026-11-05T18:00:00+03:00", "Завтра в 18:00");
    Analysis.Result r = Analysis.parse(valid, notes, now).get(1L);
    check(r.title.equals("Забрать заказ"), "Automatic title accepted");
    check(
        r.reminder == Instant.parse("2026-11-05T15:00:00Z").toEpochMilli(),
        "Offset converted to instant");
    check(
        Analysis.parse("```json\n" + valid + "\n```", notes, now).size() == 1,
        "Fenced JSON supported");
    check(
        Analysis.parse(row(999, "Wrong", "2026-11-05T18:00:00+03:00", "test"), notes, now)
            .isEmpty(),
        "Unknown ID rejected");
    note.autoTitle = false;
    r = Analysis.parse(valid, notes, now).get(1L);
    check(r.title.isEmpty(), "Manual title protected");
    note.reminder = now + 1000;
    r = Analysis.parse(valid, notes, now).get(1L);
    check(r.reminder == 0, "Existing reminder protected");
    note.reminder = 0;
    check(
        Analysis.parse(row(1, "", "2026-11-04T18:00:00+03:00", "test"), notes, now).get(1L).reminder
            == 0,
        "Past date rejected");
    check(
        Analysis.parse(row(1, "", "2026-11-06T18:00:00", "test"), notes, now).get(1L).reminder == 0,
        "Date without offset rejected");
    check(
        Analysis.parse(row(1, "", "2026-11-06", "test"), notes, now).get(1L).reminder == 0,
        "Date without time rejected");
    check(
        Analysis.parse(row(1, "", null, ""), notes, now).get(1L).reminder == 0,
        "No reminder is valid");
    check(
        Analysis.parse(row(1, "", "2026-11-05T18:00:00+03:00", ""), notes, now).get(1L).reminder
            == 0,
        "Explanation required");
    note.autoTitle = true;
    check(
        Analysis.parse(row(1, "x".repeat(81), null, ""), notes, now).get(1L).title.isEmpty(),
        "Title length bounded");
    note.body = "long ".repeat(10000);
    context =
        new JSONArray(
                Analysis.payload(notes, "test", now, ZoneId.of("UTC"))
                    .getJSONArray("messages")
                    .getJSONObject(1)
                    .getString("content"))
            .getJSONObject(0);
    check(context.getString("text").length() <= 1200, "Input fragment bounded");
    System.out.println("PASS: " + count + " background analysis checks");
  }
}
