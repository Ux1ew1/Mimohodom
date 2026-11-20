package app.fastnotes;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** Лента чатов (0.10.22): строка последнего сообщения, группы по дням, время, фильтры. */
public final class ChatFeedTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static long at(int y, int m, int d, int h, int min, ZoneId zone) {
    return LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli();
  }

  static ChatFeed.Row row(String origin, long note, String role, String draft, int users) {
    ChatFeed.Row r = new ChatFeed.Row();
    r.origin = origin;
    r.note = note;
    r.role = role;
    r.draft = draft;
    r.userMessages = users;
    return r;
  }

  public static void main(String[] args) {
    // Строка последнего сообщения без служебных меток.
    check(ChatFeed.preview("Приём у врача 5 мая [[note:12]].").equals("Приём у врача 5 мая."), "note link");
    check(
        ChatFeed.preview("Записал.\n[[memo]]\nврач в пятницу\n[[/memo]]").equals("Записал."),
        "memo block");
    check(ChatFeed.preview("[[recap]]Итог. Всё готово").equals("Итог. Всё готово"), "recap mark");
    check(
        ChatFeed.preview("Как продвигается?\n[[ask:Сделано|В процессе]]").equals("Как продвигается?"),
        "options");
    check(ChatFeed.preview("[[img:a.jpg,b.jpg]]").equals("Изображение"), "only images");
    check(ChatFeed.preview("Что на фото?\n[[img:a.jpg]]").equals("Что на фото?"), "text and images");
    check(ChatFeed.preview("Строка\n\n  вторая").equals("Строка вторая"), "one line");
    check(ChatFeed.preview(null).isEmpty() && ChatFeed.preview("").isEmpty(), "empty");
    String longText = "а".repeat(400);
    String cut = ChatFeed.preview(longText);
    check(cut.length() == ChatFeed.MAX_PREVIEW && cut.endsWith("…"), "long text cut");

    // Группы и время.
    ZoneId zone = ZoneId.of("Europe/Moscow");
    long now = at(2026, 11, 21, 15, 0, zone);
    check(ChatFeed.group(at(2026, 11, 21, 0, 5, zone), now, zone).equals("Сегодня"), "today");
    check(ChatFeed.group(at(2026, 11, 20, 23, 59, zone), now, zone).equals("Вчера"), "yesterday");
    check(ChatFeed.group(at(2026, 11, 17, 12, 0, zone), now, zone).equals("Ноябрь"), "this month");
    check(ChatFeed.group(at(2026, 3, 1, 12, 0, zone), now, zone).equals("Март"), "this year");
    check(ChatFeed.group(at(2025, 12, 31, 12, 0, zone), now, zone).equals("Декабрь 2025"), "last year");
    check(ChatFeed.time(at(2026, 11, 21, 9, 5, zone), now, zone).equals("09:05"), "time today");
    check(ChatFeed.time(at(2026, 11, 20, 18, 40, zone), now, zone).equals("18:40"), "time yesterday");
    check(ChatFeed.time(at(2026, 11, 17, 12, 0, zone), now, zone).equals("17 нояб."), "date");
    check(ChatFeed.time(at(2026, 5, 3, 12, 0, zone), now, zone).equals("3 мая"), "May genitive");
    check(ChatFeed.time(at(2025, 1, 9, 12, 0, zone), now, zone).equals("9 янв. 2025"), "date with year");

    // Фильтры.
    ChatFeed.Row mine = row("user", 0, "assistant", "", 1);
    ChatFeed.Row note = row("note", 5, "assistant", "", 1);
    ChatFeed.Row linked = row("user", 7, "assistant", "", 1);
    ChatFeed.Row agent = row("agent", 7, "assistant", "{\"kind\":\"progress\",\"note\":7}", 0);
    check(ChatFeed.matches(ChatFeed.ALL, agent) && ChatFeed.matches(ChatFeed.ALL, mine), "all");
    check(ChatFeed.matches(ChatFeed.MINE, mine) && !ChatFeed.matches(ChatFeed.MINE, note), "mine");
    check(!ChatFeed.matches(ChatFeed.MINE, agent) && !ChatFeed.matches(ChatFeed.MINE, linked), "mine excludes");
    check(ChatFeed.matches(ChatFeed.NOTES, note) && ChatFeed.matches(ChatFeed.NOTES, linked), "notes");
    check(!ChatFeed.matches(ChatFeed.NOTES, agent), "agent chat bound to note is not a note chat");

    // Помощник ждёт ответа.
    check(ChatFeed.awaits(agent) && ChatFeed.progress(agent), "progress question awaits");
    check(ChatFeed.awaits(row("agent", 0, "assistant", "", 0)), "unanswered question awaits");
    check(!ChatFeed.awaits(row("agent", 0, "assistant", "", 1)), "answered free question");
    check(!ChatFeed.awaits(row("agent", 0, "user", "", 1)), "user wrote last");
    ChatFeed.Row after = row("agent", 7, "assistant", "{\"kind\":\"progress_trash\",\"note\":7}", 1);
    check(!ChatFeed.awaits(after) && !ChatFeed.progress(after), "after answer");
    check(!ChatFeed.awaits(mine), "user chat never awaits");

    System.out.println("ChatFeed tests passed: " + count);
  }
}
