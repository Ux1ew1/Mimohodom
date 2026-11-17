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

    System.out.println("Reminder batch tests passed: " + count);
  }
}
