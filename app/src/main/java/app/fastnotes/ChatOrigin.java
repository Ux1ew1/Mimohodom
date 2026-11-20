package app.fastnotes;

/**
 * Кто начал чат: пользователь, помощник (вопрос «как дела», бывшая настройка {@code
 * checkInChat.<id>}) или чат по заметке. Хранится в {@code chats.origin} и уходит в обмен, поэтому
 * цвет и подпись в списке чатов одинаковы на всех устройствах (docs/AGENT-PLANNER.md, 2.6). Чистая
 * Java без Android.
 */
public final class ChatOrigin {
  public static final String USER = "user", AGENT = "agent", NOTE = "note";

  private ChatOrigin() {}

  /** Одно из трёх значений; неизвестное или пустое — {@link #USER}. */
  public static String clean(String raw) {
    if (AGENT.equals(raw)) return AGENT;
    if (NOTE.equals(raw)) return NOTE;
    return USER;
  }

  /** Признак {@code checkin} записи {@code chat}: его понимает компьютер (схема v4). */
  public static boolean checkin(String origin) {
    return AGENT.equals(clean(origin));
  }

  /**
   * Происхождение чата из записи обмена. {@code remote} — поле {@code origin} записи или null, если
   * его нет (компьютер, телефон до 0.10.11): тогда решает {@code checkin}, а без него остаётся своё
   * значение ({@code local}, null — чата ещё нет). Компьютер не знает чатов по заметке, поэтому
   * свою пометку «по заметке» запись без поля не снимает.
   */
  public static String fromRecord(String remote, boolean checkin, String local) {
    if (remote != null && !remote.isEmpty()) return clean(remote);
    if (checkin) return AGENT;
    return local == null ? USER : clean(local);
  }

  /** Подпись в списке чатов или пусто для обычного чата. */
  public static String label(String origin) {
    return switch (clean(origin)) {
      case AGENT -> "От помощника";
      case NOTE -> "По заметке";
      default -> "";
    };
  }
}
