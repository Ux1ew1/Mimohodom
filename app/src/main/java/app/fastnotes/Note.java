package app.fastnotes;

public final class Note {
  public long id, created, updated, reminder;
  public String title, body;
  public String image = "";
  public String metadata = "{}", searchText = "";
  public boolean autoTitle;
  public long suggestion;
  /** Повтор напоминания: 0 — нет, иначе {@link ReminderPolicy#REPEAT_DAILY} и далее. */
  public int repeat;
  /** Своё «напомнить за N минут» или -1, если действует общая настройка. */
  public int lead = -1;
  public String suggestionReason = "";
  /**
   * Все даты предложения ИИ (JSON-массив моментов, {@link ReminderPolicy#parseTimes}), если их
   * несколько; при одной дате пусто — хватает {@link #suggestion}.
   */
  public String suggestionTimes = "";
  /** За сколько минут ИИ предлагает напомнить о предложенном событии; -1 — без предложения. */
  public int suggestionLead = -1;
  /** Номер папки или {@link FolderPolicy#ALL}, если заметка лежит только в «Все заметки». */
  public long folder;
  /**
   * Все даты серии {@link ReminderPolicy#REPEAT_LIST} (JSON-массив моментов, {@link
   * ReminderPolicy#parseTimes}); у остальных заметок пусто.
   */
  public String reminderTimes = "";
  /** Когда заметку положили в корзину; 0 — не в корзине. */
  public long trashed;
  /** Локальная смысловая близость к текущему запросу; в БД и синхронизацию не попадает. */
  public double semanticScore;
  /**
   * Баллы внимания помощника 0..8 (docs/ATTENTION-POINTS.md) или -1 — «не назначены»: эксперимент
   * выключен или заметка создана до него. Только на этом устройстве, в обмен не идёт.
   */
  public int attention = -1;

  public Note(long id, String title, String body, long created, long updated, long reminder) {
    this.id = id;
    this.title = title;
    this.body = body;
    this.created = created;
    this.updated = updated;
    this.reminder = reminder;
  }

  public static String autoTitle(String title, String body) {
    if (!title.trim().isEmpty()) return title.trim();
    String line = body.trim().split("\\R", 2)[0].trim();
    if (line.isEmpty()) return "Новая заметка";
    int end = line.offsetByCodePoints(0, Math.min(52, line.codePointCount(0, line.length())));
    return line.substring(0, end) + (end < line.length() ? "…" : "");
  }
}
