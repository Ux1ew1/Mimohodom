package app.fastnotes;

/**
 * Превращает заметку с напоминанием в поля события календаря. Без зависимости от Android, чтобы
 * правила проверялись JVM-тестом. Само окно «Новое событие» открывает {@code MainActivity}.
 */
public final class CalendarLink {
  public static final long DEFAULT_LENGTH_MS = 60L * 60 * 1000;
  public static final int MAX_DESCRIPTION = 2000;

  public final String title, description, rrule;
  public final long begin, end;

  private CalendarLink(String title, String description, String rrule, long begin, long end) {
    this.title = title;
    this.description = description;
    this.rrule = rrule;
    this.begin = begin;
    this.end = end;
  }

  /** Событие по заметке или {@code null}, если напоминания нет. */
  public static CalendarLink of(String title, String body, long reminder, int repeatMode) {
    if (reminder <= 0) return null;
    String safeTitle = Note.autoTitle(title == null ? "" : title, body == null ? "" : body);
    return new CalendarLink(
        safeTitle,
        description(title, body),
        rrule(repeatMode),
        reminder,
        reminder + DEFAULT_LENGTH_MS);
  }

  /**
   * Текст заметки для описания. Если название пустое, первая строка уже стала названием и в
   * описание не повторяется. Длинный текст обрезается по границе символа.
   */
  static String description(String title, String body) {
    String text = body == null ? "" : body.trim();
    if ((title == null || title.trim().isEmpty()) && !text.isEmpty()) {
      String[] parts = text.split("\\R", 2);
      text = parts.length > 1 ? parts[1].trim() : "";
    }
    if (text.codePointCount(0, text.length()) <= MAX_DESCRIPTION) return text;
    int end = text.offsetByCodePoints(0, MAX_DESCRIPTION - 1);
    return text.substring(0, end) + "…";
  }

  /** Правило повтора для календаря или {@code null}, если событие одноразовое. */
  static String rrule(int repeatMode) {
    return switch (repeatMode) {
      case ReminderPolicy.REPEAT_DAILY -> "FREQ=DAILY";
      case ReminderPolicy.REPEAT_WEEKLY -> "FREQ=WEEKLY";
      case ReminderPolicy.REPEAT_MONTHLY -> "FREQ=MONTHLY";
      case ReminderPolicy.REPEAT_YEARLY -> "FREQ=YEARLY";
      default -> null;
    };
  }
}
