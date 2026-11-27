package app.fastnotes;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.json.JSONArray;
import org.json.JSONObject;

/** Чат по заметке (этап 6): происхождение чата и правка заметки по кнопке «Применить». */
public final class NoteChatTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static long at(ZoneId zone, int y, int m, int d, int h, int min) {
    return ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli();
  }

  public static void main(String[] args) throws Exception {
    // ---- Происхождение чата ----
    check(ChatOrigin.clean("agent").equals(ChatOrigin.AGENT), "agent");
    check(ChatOrigin.clean("note").equals(ChatOrigin.NOTE), "note");
    check(ChatOrigin.fromRecord("plan", false, null).equals(ChatOrigin.PLAN), "plan origin on sync");
    check(ChatOrigin.fromRecord(null, false, "plan").equals(ChatOrigin.PLAN), "legacy record keeps plan origin");
    check(ChatOrigin.label("plan").equals("По заметке"), "plan chat label");
    check(ChatOrigin.clean("").equals(ChatOrigin.USER), "empty is user");
    check(ChatOrigin.clean(null).equals(ChatOrigin.USER), "null is user");
    check(ChatOrigin.clean("robot").equals(ChatOrigin.USER), "unknown is user");
    check(ChatOrigin.checkin("agent") && !ChatOrigin.checkin("note") && !ChatOrigin.checkin("user"), "checkin flag");
    check(ChatOrigin.fromRecord("note", true, "user").equals("note"), "origin field wins");
    check(ChatOrigin.fromRecord(null, true, null).equals("agent"), "desktop checkin makes agent chat");
    check(ChatOrigin.fromRecord(null, false, null).equals("user"), "new chat without fields is user");
    check(ChatOrigin.fromRecord(null, false, "note").equals("note"), "desktop keeps note chat");
    check(ChatOrigin.fromRecord(null, false, "agent").equals("agent"), "desktop keeps agent chat");
    check(ChatOrigin.fromRecord("", false, "user").equals("user"), "empty field is absent");
    check(ChatOrigin.fromRecord("x", true, "note").equals("user"), "bad field is user");
    check(ChatOrigin.label("agent").equals("От помощника") && ChatOrigin.label("note").equals("По заметке"), "labels");
    check(ChatOrigin.label("user").isEmpty(), "plain chat has no label");

    // ---- Правка заметки ----
    ZoneId zone = ZoneId.of("Europe/Moscow");
    long now = at(zone, 2026, 11, 20, 12, 0);
    long hour = 3_600_000L;
    JSONObject raw =
        new JSONObject()
            .put("title", "  Выезд\nкоманды  ")
            .put("append", "Олег — закупка")
            .put("remind", new JSONArray().put("2026-11-25 10:00").put("2026-11-19 10:00").put("потом").put("2026-11-27"))
            .put("replace", false);
    NoteEdit e = NoteEdit.fromModel(raw, zone, now);
    check(e != null, "edit parsed");
    check(e.title.equals("Выезд команды"), "title one line: " + e.title);
    check(e.append.equals("Олег — закупка"), "append");
    check(e.remind.length == 2 && e.remind[0] == at(zone, 2026, 11, 25, 10, 0), "future dates only");
    check(e.remind[1] == at(zone, 2026, 11, 27, 9, 0), "date without time is 09:00");
    check(e.skipped == 2, "past and unreadable skipped");
    check(NoteEdit.fromModel(new JSONObject().put("title", "").put("remind", new JSONArray()), zone, now) == null, "empty edit is null");
    check(NoteEdit.fromModel(null, zone, now) == null, "missing edit");
    check(NoteEdit.fromModel(new JSONObject().put("title", "я".repeat(300)), zone, now).title.length() <= NoteEdit.MAX_TITLE, "title clipped");
    check(NoteEdit.fromModel(new JSONObject().put("append", "a".repeat(9000)), zone, now).append.length() <= NoteEdit.MAX_APPEND + 1, "append clipped");
    NoteEdit back = NoteEdit.fromJson(new JSONObject(e.toJson().toString()));
    check(back.title.equals(e.title) && back.append.equals(e.append) && back.remind.length == 2 && !back.replace, "json round trip");
    check(NoteEdit.fromJson(new JSONObject()) == null, "empty json is null");
    check(e.describe(zone).size() == 3, "three lines");
    check(e.describe(zone).get(2).startsWith("Напоминание: 25.11.2026"), "date line: " + e.describe(zone).get(2));

    // Применение: название, текст, даты к прежнему разовому напоминанию
    long old = at(zone, 2026, 11, 22, 18, 0);
    NoteEdit.Result r = e.apply("Выезд", "Список дел", old, 0, "", now);
    check(r.title.equals("Выезд команды"), "new title");
    check(r.body.equals("Список дел\n\nОлег — закупка"), "appended body");
    check(r.repeat == ReminderPolicy.REPEAT_LIST && r.reminder == old, "dates added to single reminder");
    check(ReminderPolicy.parseTimes(r.times).length == 3, "three dates in list");
    NoteEdit.Result emptyBody = e.apply("Выезд", "  ", 0, 0, "", now);
    check(emptyBody.body.equals("Олег — закупка"), "append to empty body");
    NoteEdit titleOnly = NoteEdit.fromModel(new JSONObject().put("title", "Новое"), zone, now);
    NoteEdit.Result keep = titleOnly.apply("Старое", "Текст", old, ReminderPolicy.REPEAT_WEEKLY, "", now);
    check(keep.body.equals("Текст") && keep.reminder == old && keep.repeat == ReminderPolicy.REPEAT_WEEKLY, "title only keeps the rest");
    // Замена прежних дат
    NoteEdit one = NoteEdit.fromModel(new JSONObject().put("remind", new JSONArray().put("2026-11-30 08:00")).put("replace", true), zone, now);
    NoteEdit.Result replaced = one.apply("Т", "т", old, ReminderPolicy.REPEAT_LIST, "[" + old + "," + (old + 24 * hour) + "]", now);
    check(replaced.repeat == 0 && replaced.times.isEmpty() && replaced.reminder == at(zone, 2026, 11, 30, 8, 0), "replace makes single reminder");
    // Повтор по правилу заменяется: смешать нельзя
    NoteEdit add = NoteEdit.fromModel(new JSONObject().put("remind", new JSONArray().put("2026-11-30 08:00")), zone, now);
    NoteEdit.Result periodic = add.apply("Т", "т", old, ReminderPolicy.REPEAT_DAILY, "", now);
    check(periodic.repeat == 0 && periodic.reminder == at(zone, 2026, 11, 30, 8, 0), "periodic replaced by dates");
    // Даты прошли к моменту нажатия
    NoteEdit.Result late = add.apply("Т", "т", old, 0, "", at(zone, 2026, 12, 1, 0, 0));
    check(late.datesPassed && late.reminder == old, "passed dates do not change reminder");
    // Добавление к списку
    NoteEdit.Result listed = add.apply("Т", "т", old, ReminderPolicy.REPEAT_LIST, "[" + old + "," + (old + 24 * hour) + "]", now);
    check(ReminderPolicy.parseTimes(listed.times).length == 3 && listed.reminder == old, "added to list");

    // ---- Ответ модели в чате по заметке ----
    PlanChat.Reply reply =
        PlanChat.parse(
            "{\"say\":\"Переименую и допишу.\",\"options\":[\"Да\"],\"plan\":null,"
                + "\"edit\":{\"title\":\"Выезд\",\"append\":\"\",\"remind\":[]}}",
            zone,
            now);
    check(reply.edit != null && reply.edit.title.equals("Выезд"), "edit in reply");
    check(reply.options.isEmpty(), "edit has no options");
    check(reply.plan == null, "no plan");
    PlanChat.Reply silent = PlanChat.parse("{\"edit\":{\"append\":\"x\"}}", zone, now);
    check(silent.say.equals("Применить изменения к заметке?"), "default say for edit");
    PlanChat.Reply noEdit = PlanChat.parse("{\"say\":\"Когда?\",\"options\":[\"Завтра\"],\"edit\":null}", zone, now);
    check(noEdit.edit == null && noEdit.options.size() == 1, "null edit keeps options");
    check(PlanChat.system(now, zone).contains("\"replace\""), "prompt describes edit");
    // Ответы на вопросы помощника идут в запрос чата по заметке (этап 5/6)
    String withAnswers =
        PlanChat.context("Выезд", "текст", null, java.util.List.of("Во сколько выезд? → В 10:00"), zone);
    check(withAnswers.contains("не переспрашивай") && withAnswers.contains("В 10:00"), "answers in context");
    check(!PlanChat.context("Выезд", "текст", null, zone).contains("Ответы пользователя"), "no answers block when empty");
    JSONObject withHistory =
        PlanChat.payload("m", "Выезд", "текст", null, java.util.List.of("Кто едет? → Команда"), new JSONArray(), "Привет", now, zone);
    check(withHistory.getJSONArray("messages").getJSONObject(1).getString("content").contains("Команда"), "payload carries answers");

    System.out.println("NoteChatTest: " + count + " checks passed");
  }
}
