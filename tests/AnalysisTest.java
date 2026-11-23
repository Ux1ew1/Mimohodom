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
    // Плавающее напоминание: ИИ предлагает, за сколько напомнить
    String leadRow =
        new JSONObject()
            .put(
                "notes",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("id", 1)
                            .put("title", "")
                            .put("reminder_at", "2026-11-10T18:00:00+03:00")
                            .put("lead_minutes", 2880)
                            .put("reason", "Оплатить до 10 ноября")))
            .toString();
    note.body = "Оплатить налог до 10 ноября";
    check(Analysis.parse(leadRow, notes, now).get(1L).lead == 2880, "Two days lead accepted");
    check(
        Analysis.parse(valid, notes, now).get(1L).lead == -1,
        "No lead from the model means the general setting");
    String shortLead = leadRow.replace("2026-11-10T18:00:00+03:00", "2026-11-06T18:00:00+03:00");
    check(Analysis.parse(shortLead, notes, now).get(1L).lead == 1440, "Lead shortened to stay in the future");
    String oddLead = leadRow.replace("2880", "7");
    check(Analysis.parse(oddLead, notes, now).get(1L).lead == -1, "Unknown lead rejected");
    check(
        Analysis.payload(notes, "test", now, ZoneId.of("UTC"))
            .getJSONArray("messages")
            .getJSONObject(0)
            .getString("content")
            .contains("lead_minutes"),
        "Prompt asks for lead");
    // Одна заметка — несколько дат: модель отдаёт всю серию в reminder_times
    check(
        Analysis.payload(notes, "test", now, ZoneId.of("UTC"))
            .getJSONArray("messages")
            .getJSONObject(0)
            .getString("content")
            .contains("reminder_times"),
        "Prompt asks for every date");
    note.body = "На 25.11, 28.11, 29.11 на буфет пирог";
    String multiRow =
        new JSONObject()
            .put(
                "notes",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("id", 1)
                            .put("title", "")
                            .put("reminder_at", "2026-11-25T09:00:00+03:00")
                            .put(
                                "reminder_times",
                                new JSONArray()
                                    .put("2026-11-29T15:00:00+03:00")
                                    .put("2026-11-25T09:00:00+03:00")
                                    .put("2026-11-25T09:00:00+03:00")
                                    .put("не дата")
                                    .put("2026-11-04T09:00:00+03:00")
                                    .put("2026-11-28T14:00:00+03:00"))
                            .put("lead_minutes", 1440)
                            .put("reason", "Буфет 25, 28 и 29 ноября")))
            .toString();
    Analysis.Result multi = Analysis.parse(multiRow, notes, now).get(1L);
    check(multi.times.length == 3, "Every listed date accepted, duplicates and past dropped");
    check(
        multi.reminder == Instant.parse("2026-11-25T06:00:00Z").toEpochMilli(),
        "Earliest date leads the series");
    check(
        multi.times[2] == Instant.parse("2026-11-29T12:00:00Z").toEpochMilli(),
        "Series sorted ascending");
    check(multi.lead == 1440, "Series lead checked against the earliest date");
    String timesOnly =
        new JSONObject()
            .put(
                "notes",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("id", 1)
                            .put("title", "")
                            .put("reminder_at", JSONObject.NULL)
                            .put(
                                "reminder_times",
                                new JSONArray()
                                    .put("2026-11-29T15:00:00+03:00")
                                    .put("2026-11-25T09:00:00+03:00")
                                    .put("2026-11-28T14:00:00+03:00"))
                            .put("reason", "Буфет 25, 28 и 29 ноября")))
            .toString();
    Analysis.Result listed = Analysis.parse(timesOnly, notes, now).get(1L);
    check(
        listed.reminder == Instant.parse("2026-11-25T06:00:00Z").toEpochMilli()
            && listed.times.length == 3,
        "Dates list works without reminder_at");
    String singleListed =
        new JSONObject()
            .put(
                "notes",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("id", 1)
                            .put("title", "")
                            .put("reminder_at", JSONObject.NULL)
                            .put(
                                "reminder_times",
                                new JSONArray().put("2026-11-06T18:00:00+03:00"))
                            .put("reason", "Одна дата")))
            .toString();
    Analysis.Result single = Analysis.parse(singleListed, notes, now).get(1L);
    check(
        single.reminder == Instant.parse("2026-11-06T15:00:00Z").toEpochMilli()
            && single.times.length == 0,
        "One listed date stays a plain reminder");
    check(
        Analysis.parse(
                    singleListed.replace("2026-11-06T18:00:00+03:00", "2026-11-04T18:00:00+03:00"),
                    notes,
                    now)
                .get(1L)
                .reminder
            == 0,
        "Series of only past dates suggests nothing");
    note.autoTitle = false;
    note.reminder = now + 1000;
    check(
        Analysis.parse(multiRow, notes, now).get(1L).reminder == 0,
        "Existing reminder protects the whole series");
    note.reminder = 0;
    System.out.println("PASS: " + count + " background analysis checks");
  }
}
