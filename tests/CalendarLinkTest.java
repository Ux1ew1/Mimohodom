package app.fastnotes;

/** Проверяет перенос заметки в поля события календаря без Android. */
public final class CalendarLinkTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  public static void main(String[] args) {
    long t = 1_800_000_000_000L;

    check(CalendarLink.of("Встреча", "текст", 0, 0) == null, "no reminder, no event");

    CalendarLink one = CalendarLink.of("Встреча", "Принести документы", t, 0);
    check(one.title.equals("Встреча"), "title");
    check(one.description.equals("Принести документы"), "description");
    check(one.begin == t && one.end == t + CalendarLink.DEFAULT_LENGTH_MS, "one hour");
    check(one.rrule == null, "single event has no rule");

    CalendarLink untitled = CalendarLink.of("", "Позвонить маме\nспросить про дачу", t, 0);
    check(untitled.title.equals("Позвонить маме"), "title from first line");
    check(untitled.description.equals("спросить про дачу"), "first line not repeated");
    check(CalendarLink.of("", "Одна строка", t, 0).description.isEmpty(), "single line body");

    check(CalendarLink.of("a", "b", t, ReminderPolicy.REPEAT_DAILY).rrule.equals("FREQ=DAILY"), "daily");
    check(CalendarLink.of("a", "b", t, ReminderPolicy.REPEAT_WEEKLY).rrule.equals("FREQ=WEEKLY"), "weekly");
    check(CalendarLink.of("a", "b", t, ReminderPolicy.REPEAT_MONTHLY).rrule.equals("FREQ=MONTHLY"), "monthly");
    check(CalendarLink.of("a", "b", t, ReminderPolicy.REPEAT_YEARLY).rrule.equals("FREQ=YEARLY"), "yearly");
    check(CalendarLink.of("a", "b", t, 99).rrule == null, "unknown repeat");

    CalendarLink empty = CalendarLink.of("", "", t, 0);
    check(empty.title.equals("Новая заметка"), "empty note title");

    String longBody = "я".repeat(5000);
    String cut = CalendarLink.of("a", longBody, t, 0).description;
    check(cut.codePointCount(0, cut.length()) == CalendarLink.MAX_DESCRIPTION, "long text is cut");
    check(cut.endsWith("…"), "cut mark");

    String emoji = "😀".repeat(CalendarLink.MAX_DESCRIPTION + 5);
    String cutEmoji = CalendarLink.of("a", emoji, t, 0).description;
    check(!Character.isLowSurrogate(cutEmoji.charAt(cutEmoji.length() - 2)) || cutEmoji.length() > 1, "no broken pair");
    check(cutEmoji.codePointCount(0, cutEmoji.length()) == CalendarLink.MAX_DESCRIPTION, "emoji cut by code points");

    System.out.println("CalendarLinkTest: " + count + " проверок прошли");
  }
}
