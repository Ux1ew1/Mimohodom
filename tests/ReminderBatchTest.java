package app.fastnotes;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/** Проверяет разбор пачки напоминаний из ответа ИИ без Android. */
public final class ReminderBatchTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static long at(ZoneId zone, int y, int m, int d, int h, int min) {
    return ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli();
  }

  public static void main(String[] args) {
    ZoneId zone = ZoneId.of("Europe/Moscow");
    long now = at(zone, 2026, 11, 5, 12, 0);
    String schedule = "• 25.11.2026 ср 17:00 — Пирог и пицца\n"
        + "• 28.11.2026 сб 15:00 — Забрать пиццу\n• 29.11.2026 вс 15:00 — Забрать пиццу";
    long[] expected = {at(zone, 2026, 11, 25, 17, 0), at(zone, 2026, 11, 28, 15, 0),
        at(zone, 2026, 11, 29, 15, 0)};
    String query = ReminderBatch.requestFor("Буфет", schedule);
    check(java.util.Arrays.equals(expected,
        ReminderBatch.times(ReminderBatch.requestSchedule(query, zone, now).items)), "all three source dates");
    check(java.util.Arrays.equals(expected, ReminderBatch.offeredTimes(schedule, expected[0], "", zone, now)),
        "complete previously saved single suggestion");
    check(ReminderBatch.offeredTimes(schedule, expected[0] + 1, "", zone, now).length == 0,
        "keep unrelated suggestion");
    check(ReminderBatch.requestSchedule(query + "\nИ ещё завтра утром", zone, now).items.isEmpty(),
        "never silently drop unparsed instructions");

    String raw =
        "Нашёл три дня. Создать напоминания?\n"
            + "[[reminders]]\n"
            + "2026-11-07 15:00 | Корпоратив | Гастроли\n"
            + "2026-11-06 16:00 | Большой выбор\n"
            + "- 2026-11-08 16:00 | Концерт Саруханова\n"
            + "[[/reminders]]";
    ReminderBatch.Result r = ReminderBatch.parse(raw, zone, now);
    check(r.items.size() == 3, "three items");
    check(r.skipped == 0, "nothing skipped");
    check(r.items.get(0).title.equals("Большой выбор"), "sorted by time");
    check(r.items.get(0).at == at(zone, 2026, 11, 6, 16, 0), "time in zone");
    check(r.items.get(1).body.equals("Гастроли"), "body kept");
    check(r.items.get(2).title.equals("Концерт Саруханова"), "bullet prefix ok");

    // Прошедшие, повторы, мусор и неверные даты не попадают в список
    String messy =
        "[[reminders]]\n"
            + "2026-11-04 10:00 | Вчера\n"
            + "2026-11-09 10:00 | Дубль\n"
            + "2026-11-09 10:00 | дубль\n"
            + "завтра в десять | Без даты\n"
            + "2026-13-40 10:00 | Неверная дата\n"
            + "2026-11-10 10:00 |   \n"
            + "[[/reminders]]";
    ReminderBatch.Result m = ReminderBatch.parse(messy, zone, now);
    check(m.items.size() == 1, "only valid future unique");
    check(m.skipped == 5, "skipped counted: " + m.skipped);

    check(ReminderBatch.parse("Просто текст", zone, now).items.isEmpty(), "no block");
    check(
        ReminderBatch.parse("[[reminders]]\n2026-11-09 10:00 | Оборван", zone, now).items.isEmpty(),
        "unterminated block ignored");

    // Предел количества
    StringBuilder many = new StringBuilder("[[reminders]]\n");
    for (int i = 0; i < 60; i++)
      many.append(String.format("2027-01-%02d 10:%02d | Событие %d\n", 1 + i % 28, i % 60, i));
    many.append("[[/reminders]]");
    check(
        ReminderBatch.parse(many.toString(), zone, now).items.size() <= ReminderBatch.MAX_ITEMS,
        "max items");

    // Длинное название обрезается
    String longTitle = "я".repeat(120);
    ReminderBatch.Result l =
        ReminderBatch.parse("[[reminders]]\n2026-12-01 09:00 | " + longTitle + "\n[[/reminders]]", zone, now);
    check(l.items.size() == 1 && l.items.get(0).title.length() == 80, "title cut to 80");

    // Текст без блока, в том числе оборванного
    check(ReminderBatch.strip(raw).equals("Нашёл три дня. Создать напоминания?"), "strip block");
    check(
        ReminderBatch.strip("Список\n[[reminders]]\n2026-11-09 10:00 | Оборван").equals("Список"),
        "strip unterminated");
    check(ReminderBatch.strip("Без блока").equals("Без блока"), "strip no block");

    // Запрос пользователя
    check(ReminderBatch.wants("Занеси все даты в напоминания и отними 2 часа"), "wants");
    check(ReminderBatch.wants("Напомни мне завтра"), "wants capital");
    check(!ReminderBatch.wants("не надо напоминаний"), "no wants negative");
    check(!ReminderBatch.wants("Что на фото?"), "no wants plain");

    // Несколько дат в тексте заметки
    check(ReminderBatch.manyDates("06.11 Выбор 18:00\n07.11 Корпоратив 17:00"), "two dates");
    check(ReminderBatch.manyDates("1.12.26 и 15/12/2026"), "dates with year and slash");
    check(!ReminderBatch.manyDates("Только 06.11 в театр"), "one date");
    check(!ReminderBatch.manyDates("Цена 12.50 и 3.99 рубля"), "prices are not dates: ");
    check(!ReminderBatch.manyDates("Версия 1.2.3.4 и 99.99"), "versions are not dates");
    check(!ReminderBatch.manyDates(""), "empty");
    String request = ReminderBatch.requestFor("Театр", "06.11 18:00");
    check(request.startsWith("Создай напоминания по датам из заметки «Театр»"), "request title");
    check(request.endsWith("06.11 18:00"), "request body");
    check(ReminderBatch.requestFor("", "x").contains("заметки."), "request without title");
    check(ReminderBatch.requestFor("", "я".repeat(9000)).length() < 4200, "request is cut");

    // Подпись
    check(
        ReminderBatch.label(at(zone, 2026, 11, 6, 16, 0), zone).equals("06.11.2026 пт 16:00"),
        "label: " + ReminderBatch.label(at(zone, 2026, 11, 6, 16, 0), zone));

    // Хранение в столбце draft и обратно
    org.json.JSONObject json = ReminderBatch.toJson(r);
    check(ReminderBatch.isBatch(json), "json kind");
    check(!ReminderBatch.isBatch(new org.json.JSONObject()), "plain draft is not batch");
    check(!ReminderBatch.isBatch(null), "null is not batch");
    List<ReminderBatch.Item> back = ReminderBatch.items(new org.json.JSONObject(json.toString()));
    check(back.size() == 3 && back.get(1).title.equals("Корпоратив"), "json round trip");
    check(ReminderBatch.guidance().contains("[[reminders]]"), "guidance names the block");
    check(ReminderBatch.guidance().contains("[[reminders separate]]"), "guidance names separate");

    // Одна заметка — несколько дат (этап 4)
    check(!r.separate && !ReminderBatch.separate(json), "one affair by default");
    check(json.has("separate"), "separate stored");
    check(!ReminderBatch.separate(new org.json.JSONObject()), "old drafts are one note");
    String sep =
        "Разные дела.\n[[reminders separate]]\n2026-11-07 15:00 | Врач\n2026-11-08 10:00 | Шиномонтаж\n[[/reminders]]";
    ReminderBatch.Result s = ReminderBatch.parse(sep, zone, now);
    check(s.separate && s.items.size() == 2, "separate block parsed");
    check(ReminderBatch.separate(ReminderBatch.toJson(s)), "separate kept in json");
    check(ReminderBatch.strip(sep).equals("Разные дела."), "strip separate block");
    check(ReminderBatch.source(new org.json.JSONObject().put("note", 42)) == 42, "source note");
    check(ReminderBatch.source(json) == 0 && ReminderBatch.source(null) == 0, "no source note");
    long[] t = ReminderBatch.times(r.items);
    check(t.length == 3 && t[0] == r.items.get(0).at && t[2] == r.items.get(2).at, "times in order");
    String[] mixed = ReminderBatch.combined(r.items, zone);
    check(mixed[0].equals("Большой выбор и ещё 2"), "combined title for different titles: " + mixed[0]);
    check(mixed[1].split("\n").length == 3, "line per date");
    check(mixed[1].contains("— Корпоратив. Гастроли"), "line has title and body: " + mixed[1]);
    ReminderBatch.Result course =
        ReminderBatch.parse(
            "[[reminders]]\n2026-11-10 19:00 | Английский | урок 1\n2026-11-12 19:00 | английский\n[[/reminders]]",
            zone, now);
    String[] same = ReminderBatch.combined(course.items, zone);
    check(same[0].equals("Английский"), "combined title for same titles");
    check(same[1].equals("• 10.11.2026 вт 19:00 — урок 1\n• 12.11.2026 чт 19:00"), "combined body: " + same[1]);
    check(ReminderBatch.combined(new java.util.ArrayList<>(), zone)[0].isEmpty(), "combined empty");
    long hour = 3_600_000L, a = now + hour, b = now + 2 * hour, c = now + 3 * hour;
    long[] fromNone = ReminderBatch.mergedTimes(0, 0, "", new long[] {b, a}, now);
    check(fromNone.length == 2 && fromNone[0] == a, "merge into note without reminder");
    long[] fromSingle = ReminderBatch.mergedTimes(c, 0, "", new long[] {a, b}, now);
    check(fromSingle.length == 3 && fromSingle[2] == c, "single future reminder kept");
    check(ReminderBatch.mergedTimes(now - hour, 0, "", new long[] {a}, now).length == 1, "past single dropped");
    long[] fromList =
        ReminderBatch.mergedTimes(a, ReminderPolicy.REPEAT_LIST, "[" + (now - hour) + "," + a + "]", new long[] {a, c}, now);
    check(fromList.length == 3 && fromList[0] == now - hour && fromList[2] == c, "list merged without duplicates");
    check(ReminderBatch.canMerge(0) && ReminderBatch.canMerge(ReminderPolicy.REPEAT_LIST), "can merge");
    check(!ReminderBatch.canMerge(ReminderPolicy.REPEAT_WEEKLY), "periodic note gets a new note");

    System.out.println("Reminder batch tests passed: " + count);
  }
}
