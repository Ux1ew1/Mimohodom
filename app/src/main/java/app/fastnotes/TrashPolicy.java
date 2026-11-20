package app.fastnotes;

/**
 * Правила корзины (docs/AGENT-PLANNER.md, этап 1): каждая заметка лежит в корзине 30 дней со своего
 * времени удаления и потом удаляется насовсем. Чистая Java без Android.
 */
public final class TrashPolicy {
  public static final int DAYS = 30;
  public static final long DAY_MS = 24L * 60 * 60 * 1000, KEEP_MS = DAYS * DAY_MS;

  private TrashPolicy() {}

  /** Пора удалить насовсем: заметка в корзине 30 дней и больше. Часы назад не удаляют ничего. */
  public static boolean expired(long trashed, long now) {
    return trashed > 0 && now >= trashed && now - trashed >= KEEP_MS;
  }

  /** Сколько полных или начатых дней осталось до удаления, от 0 до 30. */
  public static int daysLeft(long trashed, long now) {
    if (trashed <= 0) return DAYS;
    long left = trashed + KEEP_MS - now;
    if (left <= 0) return 0;
    return (int) Math.min(DAYS, (left + DAY_MS - 1) / DAY_MS);
  }

  /** Подпись на карточке в корзине. */
  public static String label(long trashed, long now) {
    int days = daysLeft(trashed, now);
    if (days <= 0) return "Удалится сегодня";
    if (days == 1) return "Удалится завтра";
    return "Удалится через " + days + " " + plural(days, "день", "дня", "дней");
  }

  static String plural(int n, String one, String few, String many) {
    int mod100 = n % 100, mod10 = n % 10;
    if (mod100 >= 11 && mod100 <= 14) return many;
    if (mod10 == 1) return one;
    if (mod10 >= 2 && mod10 <= 4) return few;
    return many;
  }
}
