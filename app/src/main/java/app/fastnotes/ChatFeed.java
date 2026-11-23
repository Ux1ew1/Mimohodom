package app.fastnotes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.regex.Pattern;

/**
 * Лента чатов (страница «Чаты», 0.10.22): строка последнего сообщения без служебных меток,
 * группы по дням, подпись времени, фильтры и признак «помощник ждёт ответа». Чистая Java без
 * Android.
 */
public final class ChatFeed {
  public static final int ALL = 0, MINE = 1, NOTES = 2;
  public static final int MAX_PREVIEW = 140;

  private static final String[] MONTHS = {
    "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
    "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"
  };
  private static final String[] SHORT = {
    "янв.", "февр.", "мар.", "апр.", "мая", "июн.",
    "июл.", "авг.", "сент.", "окт.", "нояб.", "дек."
  };
  private static final Pattern MEMO = Pattern.compile("\\[\\[memo]].*?(?:\\[\\[/memo]]|$)", Pattern.DOTALL);
  private static final Pattern MARK = Pattern.compile("\\[\\[[^\\]\\n]*]]");
  private static final Pattern IMAGES = Pattern.compile("\\[\\[img:[^\\]\\n]*]]");

  private ChatFeed() {}

  /** Строка чата для ленты. */
  public static final class Row {
    public long id, note, created, time;
    public String title = "", origin = ChatOrigin.USER, body = "", role = "", draft = "";
    public int userMessages;
  }

  /**
   * Текст последнего сообщения одной строкой: без памяти, итога, вариантов ответа, ссылок на
   * заметки и картинок; только картинки — «Изображение».
   */
  public static String preview(String body) {
    if (body == null) return "";
    String b = body;
    boolean images = IMAGES.matcher(b).find();
    if (b.startsWith(ChatMemo.RECAP)) b = b.substring(ChatMemo.RECAP.length());
    b = MEMO.matcher(b).replaceAll(" ");
    b = MARK.matcher(b).replaceAll(" ");
    b = b.replaceAll("\\s+", " ").trim();
    b = b.replaceAll(" ([.,:;!?])", "$1");
    if (b.isEmpty() && images) return "Изображение";
    if (b.length() > MAX_PREVIEW) b = b.substring(0, MAX_PREVIEW - 1).trim() + "…";
    return b;
  }

  /** «Сегодня», «Вчера», затем месяц («Ноябрь»), с годом, если он не текущий. */
  public static String group(long at, long now, ZoneId zone) {
    LocalDate day = day(at, zone), today = day(now, zone);
    if (day.equals(today)) return "Сегодня";
    if (day.equals(today.minusDays(1))) return "Вчера";
    String month = MONTHS[day.getMonthValue() - 1];
    return day.getYear() == today.getYear() ? month : month + " " + day.getYear();
  }

  /** Время справа: «14:20» за сегодня и вчера, иначе «17 нояб.» (с годом, если не текущий). */
  public static String time(long at, long now, ZoneId zone) {
    ZonedDateTime t = Instant.ofEpochMilli(at).atZone(zone);
    LocalDate today = day(now, zone);
    LocalDate day = t.toLocalDate();
    if (!day.isBefore(today.minusDays(1)) && !day.isAfter(today))
      return String.format(java.util.Locale.ROOT, "%02d:%02d", t.getHour(), t.getMinute());
    String out = day.getDayOfMonth() + " " + SHORT[day.getMonthValue() - 1];
    return day.getYear() == today.getYear() ? out : out + " " + day.getYear();
  }

  /** Чат по заметке: такое происхождение или привязка к заметке (кроме чатов помощника). */
  public static boolean byNote(Row r) {
    String origin = ChatOrigin.clean(r.origin);
    return !ChatOrigin.AGENT.equals(origin) && (ChatOrigin.NOTE.equals(origin) || r.note != 0);
  }

  public static boolean byAgent(Row r) {
    return ChatOrigin.AGENT.equals(ChatOrigin.clean(r.origin));
  }

  /** Фильтр ленты: все, свои (без помощника и заметок) или по заметкам. */
  public static boolean matches(int filter, Row r) {
    if (filter == MINE) return !byAgent(r) && !byNote(r);
    if (filter == NOTES) return byNote(r);
    return true;
  }

  /**
   * Помощник ждёт ответа: чат помощника, последнее сообщение его, и это вопрос «Как
   * продвигается?» с кнопками или вопрос, на который пользователь ещё ни разу не ответил.
   */
  public static boolean awaits(Row r) {
    if (!byAgent(r) || !"assistant".equals(r.role)) return false;
    return progress(r) || r.userMessages == 0;
  }

  /** Последнее сообщение — неотвеченный «Как продвигается?» с вариантами кнопками. */
  public static boolean progress(Row r) {
    return r.draft != null && r.draft.contains("\"kind\":\"progress\"");
  }

  private static LocalDate day(long at, ZoneId zone) {
    return Instant.ofEpochMilli(at).atZone(zone).toLocalDate();
  }
}
