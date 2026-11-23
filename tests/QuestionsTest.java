package app.fastnotes;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Вопросы агента (этап 5): класс заметки из ответа проверки и «Как продвигается?». */
public final class QuestionsTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static long at(ZoneId zone, int y, int m, int d, int h, int min) {
    return ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli();
  }

  static ProgressCheck.Candidate task(long id, String kind, long reminder, long updated, long created) {
    ProgressCheck.Candidate c = new ProgressCheck.Candidate();
    c.id = id;
    c.title = "Дело " + id;
    c.kind = kind;
    c.reminder = reminder;
    c.updated = updated;
    c.created = created;
    return c;
  }

  public static void main(String[] args) {
    // ---- Класс заметки ----
    Set<Long> known = Set.of(1L, 2L, 3L, 4L);
    Map<Long, String> kinds =
        TaskKind.parse(
            "{\"actions\":[],\"kinds\":{\"1\":\"info\",\"2\":\"Complex\",\"3\":\"party\",\"9\":\"simple\",\"x\":\"event\"}}",
            known);
    check(kinds.size() == 2, "known ids and kinds only: " + kinds);
    check("info".equals(kinds.get(1L)) && "complex".equals(kinds.get(2L)), "case-insensitive kinds");
    Map<Long, String> rows =
        TaskKind.parse("```json\n{\"kinds\":[{\"id\":3,\"kind\":\"event\"},{\"id\":4,\"kind\":\"simple\"},{\"id\":5,\"kind\":\"info\"}]}\n```", known);
    check(rows.size() == 2 && "event".equals(rows.get(3L)), "array form and code fence");
    check(TaskKind.parse("{\"actions\":[]}", known).isEmpty(), "no kinds field");
    check(TaskKind.parse("не JSON", known).isEmpty(), "broken answer");
    check(TaskKind.parse(null, known).isEmpty(), "null answer");
    check(TaskKind.task("simple") && TaskKind.task("event") && TaskKind.task("complex"), "tasks");
    check(!TaskKind.task("info") && !TaskKind.task(""), "not tasks");
    check(TaskKind.clean(" EVENT ").equals("event") && TaskKind.clean("x").isEmpty(), "clean");
    check(ManagerAnalysis.PROMPT.contains("\"kinds\"") && ManagerAnalysis.PROMPT.contains("complex"), "prompt asks for kinds");
    check(ManagerAnalysis.PROMPT.contains("ВОПРОСЫ"), "prompt has question rules");

    // ---- «Как продвигается?» ----
    ZoneId zone = ZoneId.of("Europe/Moscow");
    long now = at(zone, 2026, 11, 20, 18, 0);
    long day = 86_400_000L;
    check(ProgressCheck.allowed(3) && !ProgressCheck.allowed(4), "four chats a day");
    List<ProgressCheck.Candidate> all = new ArrayList<>();
    all.add(task(1, "event", now + day + 3_600_000L, now - day, now - 10 * day)); // завтра — срок близко
    all.add(task(2, "simple", now - 3_600_000L, now - 2 * day, now - 5 * day)); // срок только что прошёл
    all.add(task(3, "complex", 0, now - 20 * day, now - 30 * day)); // давно без движения
    all.add(task(4, "complex", 0, now - 10 * day, now - 30 * day)); // тоже, но правили позже
    all.add(task(5, "info", now + 3_600_000L, now - 30 * day, now - 40 * day)); // сведения
    all.add(task(6, "simple", 0, now - 2 * day, now - 10 * day)); // недавно правили
    all.add(task(7, "complex", 0, now - 20 * day, now - 90 * day)); // старая
    ProgressCheck.Candidate asked = task(8, "event", now + 3_600_000L, now - day, now - 3 * day);
    asked.nextAsk = now + day; // недавно спрашивали
    all.add(asked);
    ProgressCheck.Candidate series = task(9, "simple", now + 3_600_000L, now - day, now - 3 * day);
    series.repeat = ReminderPolicy.REPEAT_DAILY;
    all.add(series);
    ProgressCheck.Candidate planned = task(10, "", 0, now - 15 * day, now - 20 * day);
    planned.planOpen = true;
    all.add(planned);
    all.add(task(11, "", now + day, now - day, now - 2 * day)); // без класса, но срок близко
    List<ProgressCheck.Candidate> picked = ProgressCheck.pick(all, now, 10);
    List<Long> ids = new ArrayList<>();
    for (ProgressCheck.Candidate c : picked) ids.add(c.id);
    check(ids.equals(List.of(2L, 11L, 1L, 3L, 10L, 4L)), "order: near first, then quiet oldest: " + ids);
    check(ProgressCheck.pick(all, now, 1).size() == 1 && ProgressCheck.pick(all, now, 1).get(0).id == 2, "limit");
    check(ProgressCheck.pick(new ArrayList<>(), now, 2).isEmpty(), "nothing to ask");
    // Тексты вопросов
    check(ProgressCheck.question(all.get(1), now, zone).equals("Срок «Дело 2» прошёл. Как с этим делом?"), "passed deadline");
    check(ProgressCheck.question(all.get(0), now, zone).equals("Скоро «Дело 1» — завтра в 19:00. Как подготовка?"),
        "near deadline: " + ProgressCheck.question(all.get(0), now, zone));
    check(ProgressCheck.question(all.get(2), now, zone).equals("Как продвигается «Дело 3»?"), "quiet task");
    // Ответы
    check(ProgressCheck.option(" сделано ").equals(ProgressCheck.DONE), "option match");
    check(ProgressCheck.option("Сделал почти").isEmpty(), "free answer is not an option");
    check(ProgressCheck.nextAsk(ProgressCheck.DONE, now) == Long.MAX_VALUE, "done: never again");
    check(ProgressCheck.nextAsk(ProgressCheck.DROP, now) == Long.MAX_VALUE, "dropped: never again");
    check(ProgressCheck.nextAsk(ProgressCheck.LATER, now) == now + 7 * day, "later: a week");
    check(ProgressCheck.nextAsk(ProgressCheck.ACTIVE, now) == now + 3 * day, "active: three days");
    check(ProgressCheck.nextAsk("", now) == now + 3 * day, "no answer: three days");
    check(ProgressCheck.reply(ProgressCheck.DONE, false).contains("корзину"), "done offers trash");
    check(ProgressCheck.reply(ProgressCheck.LATER, true).contains("Перенести"), "later offers postpone");
    check(ProgressCheck.reply(ProgressCheck.LATER, false).contains("через неделю"), "later without reminder");
    check(ProgressCheck.reply(ProgressCheck.ACTIVE, true).contains("спрошу"), "active reply");
    // Перенос напоминания
    long future = now + 5 * 3_600_000L;
    check(ProgressCheck.postponed(future, 1, now) == future + day, "future moves by a day");
    long past = at(zone, 2026, 11, 19, 10, 0);
    check(ProgressCheck.postponed(past, 1, now) == at(zone, 2026, 11, 21, 10, 0), "past moves to next same time");
    check(ProgressCheck.postponed(past, 7, now) == at(zone, 2026, 11, 27, 10, 0), "past by a week");
    check(ProgressCheck.postponed(0, 1, now) == 0, "no reminder");
    check(ProgressCheck.canPostpone(future, 0) && !ProgressCheck.canPostpone(future, ReminderPolicy.REPEAT_LIST)
        && !ProgressCheck.canPostpone(0, 0), "only single reminders move");

    // Числовые ключи без кавычек (google/gemma-3-27b-it): Android их не принимает
    check(JsonRepair.quoteNumericKeys("{\"kinds\":{41:\"info\"},\"actions\":[]}").equals("{\"kinds\":{\"41\":\"info\"},\"actions\":[]}"), "numeric key quoted");
    check(JsonRepair.quoteNumericKeys("{1:\"a\", 2 :\"b\",-3:\"c\"}").equals("{\"1\":\"a\", \"2\" :\"b\",\"-3\":\"c\"}"), "several keys, spaces, minus");
    check(JsonRepair.quoteNumericKeys("{\"t\":\"в 10:30, 11:00\",\"n\":[1,2]}").equals("{\"t\":\"в 10:30, 11:00\",\"n\":[1,2]}"), "strings and arrays untouched");
    check(JsonRepair.quoteNumericKeys("{\"a\":{\"b\":\"x\\\"y\"},3:1}").equals("{\"a\":{\"b\":\"x\\\"y\"},\"3\":1}"), "escaped quotes handled");
    check(JsonRepair.quoteNumericKeys("{\"id\":41,\"n\":5}").equals("{\"id\":41,\"n\":5}"), "values untouched");
    Map<Long, String> gemma =
        TaskKind.parse("{\"kinds\":{41:\"info\"},\"actions\":[],\"notes\":[{\"id\":41}]}", Set.of(41L));
    check("info".equals(gemma.get(41L)), "gemma kinds parsed");

    // Ежедневный обход дел
    long morning = at(zone, 2026, 11, 20, 9, 0), evening = at(zone, 2026, 11, 20, 20, 0);
    long todayNum = java.time.LocalDate.of(2026, 11, 20).toEpochDay();
    check(ProgressCheck.nextDaily(morning, 18 * 60, -1, zone) == at(zone, 2026, 11, 20, 18, 0), "today when ahead");
    check(ProgressCheck.nextDaily(evening, 18 * 60, -1, zone) == at(zone, 2026, 11, 21, 18, 0), "tomorrow when passed");
    check(ProgressCheck.nextDaily(morning, 18 * 60, todayNum, zone) == at(zone, 2026, 11, 21, 18, 0), "once a day");
    check(ProgressCheck.nextDaily(morning, 99999, -1, zone) == at(zone, 2026, 11, 20, 23, 59), "minutes clamped");
    check(ProgressCheck.left(0) == 4 && ProgressCheck.left(3) == 1 && ProgressCheck.left(9) == 0, "left today");

    System.out.println("QuestionsTest: " + count + " checks passed");
  }
}
