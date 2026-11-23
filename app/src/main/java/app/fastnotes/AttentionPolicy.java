package app.fastnotes;

/**
 * Правила баллов внимания помощника (docs/ATTENTION-POINTS.md, эксперимент): у заметки запас
 * 0..8. Пока баллы есть, помощник работает с заметкой как обычно; на нуле оставляет её в покое.
 * Баллы назначаются только при включённой настройке и только новым заметкам: {@link #UNSET}
 * означает «функция выключена или заметка создана до неё» — такие обслуживаются как раньше.
 * Чистая Java без Android.
 */
public final class AttentionPolicy {
  /** Баллы ещё не назначены. */
  public static final int UNSET = -1;
  public static final int MAX = 8, BASE = 5;
  /** Не чаще одного изменения одного знака за этот срок: серия событий не обнуляет заметку за день. */
  public static final long GAP = 20L * 3_600_000;
  /** Шаг еженедельного списания за заметку, которую давно не открывали. */
  public static final long WEEK = 7L * 24 * 3_600_000;
  /** Вопрос помощника без ответа дольше этого срока — минус балл (один раз на вопрос). */
  public static final long QUESTION_WAIT = 3L * 24 * 3_600_000;

  private AttentionPolicy() {}

  /**
   * Стартовый запас при создании заметки: база плюс балл за напоминание. Бонус за класс
   * (событие, большая задача) добавляет первая проверка — {@link #classBonus}.
   */
  public static int initial(boolean hasReminder) {
    return BASE + (hasReminder ? 1 : 0);
  }

  public static int clamp(int value) {
    return Math.max(0, Math.min(MAX, value));
  }

  /**
   * Разовый бонус первой классификации: событие ({@code event}) +1, большая задача
   * ({@code complex}) или готовый план +1. Заметку без назначенных баллов не трогает.
   */
  public static int classBonus(int attention, String kind, boolean hasPlan) {
    if (attention < 0) return attention;
    int bonus = 0;
    if ("event".equals(kind)) bonus++;
    if ("complex".equals(kind) || hasPlan) bonus++;
    return clamp(attention + bonus);
  }

  /**
   * Итог изменения на {@code delta}. Заметка на нуле, к которой пользователь вернулся сам
   * (открыл, изменил, ответил, принял предложение), поднимается сразу до 2 — выход из
   * «молчания» через явный интерес, без отдельной кнопки.
   */
  public static int apply(int attention, int delta) {
    if (attention == 0 && delta > 0) return Math.max(2, clamp(delta));
    return clamp(attention + delta);
  }

  /**
   * Изменение разрешено, если баллы назначены и прошлое изменение того же знака было не раньше
   * {@link #GAP} назад ({@code lastSameSign} — время последнего плюса или последнего минуса).
   */
  public static boolean due(int attention, long lastSameSign, long now) {
    return attention >= 0 && now - lastSameSign >= GAP;
  }
}
