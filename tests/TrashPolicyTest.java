package app.fastnotes;

/** Правила корзины без Android. */
public final class TrashPolicyTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  public static void main(String[] args) {
    long day = TrashPolicy.DAY_MS, t = 1_800_000_000_000L;
    check(!TrashPolicy.expired(0, t), "not in trash");
    check(!TrashPolicy.expired(t, t), "just trashed");
    check(!TrashPolicy.expired(t, t + 30 * day - 1), "one ms before 30 days");
    check(TrashPolicy.expired(t, t + 30 * day), "exactly 30 days");
    check(TrashPolicy.expired(t, t + 400 * day), "long ago");
    check(!TrashPolicy.expired(t, t - day), "clock moved back");

    check(TrashPolicy.daysLeft(t, t) == 30, "full term");
    check(TrashPolicy.daysLeft(t, t + 1) == 30, "started day counts");
    check(TrashPolicy.daysLeft(t, t + day) == 29, "one day passed");
    check(TrashPolicy.daysLeft(t, t + 29 * day + 1) == 1, "last day");
    check(TrashPolicy.daysLeft(t, t + 30 * day) == 0, "due");
    check(TrashPolicy.daysLeft(t, t - 5 * day) == 30, "future trash time capped");

    check(TrashPolicy.label(t, t).equals("Удалится через 30 дней"), "30 days");
    check(TrashPolicy.label(t, t + 8 * day).equals("Удалится через 22 дня"), "22 days");
    check(TrashPolicy.label(t, t + 9 * day).equals("Удалится через 21 день"), "21 day");
    check(TrashPolicy.label(t, t + 19 * day).equals("Удалится через 11 дней"), "11 days");
    check(TrashPolicy.label(t, t + 29 * day).equals("Удалится завтра"), "tomorrow");
    check(TrashPolicy.label(t, t + 31 * day).equals("Удалится сегодня"), "today");
    System.out.println("TrashPolicy tests passed: " + count);
  }
}
