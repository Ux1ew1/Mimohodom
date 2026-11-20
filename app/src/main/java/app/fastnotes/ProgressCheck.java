package app.fastnotes;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * «Как продвигается?» (docs/AGENT-PLANNER.md, 2.4, этап 5): помощник сам выбирает дело с близким
 * сроком или давно без движения и спрашивает о нём в чате с вариантами «Сделано / В процессе /
 * Отложить / Не актуально». Вопрос составляется без запроса к модели. Здесь выбор дел, лимит чатов в
 * день, тексты и сроки следующего вопроса. Чистая Java без Android.
 */
public final class ProgressCheck {
  /** Чатов, начатых помощником, в сутки — не больше (решение пользователя 4). */
  public static final int MAX_PER_DAY = 4;

  public static final String DONE = "Сделано", ACTIVE = "В процессе", LATER = "Отложить",
      DROP = "Не актуально";
  public static final List<String> OPTIONS = List.of(DONE, ACTIVE, LATER, DROP);

  static final long DAY = 86_400_000L;
  /** Срок близко: событие в пределах двух суток до или после «сейчас». */
  static final long NEAR = 2 * DAY;
  /** Давно без движения: заметку не меняли неделю. */
  static final long QUIET = 7 * DAY;
  /** Заметки старше двух месяцев без срока не тревожим: скорее всего, это архив. */
  static final long OLD = 60 * DAY;

  private ProgressCheck() {}

  /** Дело-кандидат: что известно о заметке для выбора. */
  public static final class Candidate {
    public long id, reminder, updated, created;
    public String title = "", kind = "";
    /** В плане есть невыполненные пункты. */
    public boolean planOpen;
    /** Повтор напоминания (серия): о повторяющихся делах «как продвигается» не спрашиваем. */
    public int repeat;
    /** Раньше этого времени о заметке не спрашивать (ответ «В процессе», «Отложить» и т. п.). */
    public long nextAsk;
  }

  /** Сколько дел спрашивать за один ежедневный обход (с учётом общего лимита в сутки). */
  public static final int PER_RUN = 2;

  /**
   * Время ежедневного обхода дел: сегодня в {@code minutes} от полуночи, если оно ещё впереди и
   * сегодня обхода не было ({@code lastDay} — номер дня, {@code LocalDate.toEpochDay}), иначе завтра.
   */
  public static long nextDaily(long now, int minutes, long lastDay, ZoneId zone) {
    int at = Math.max(0, Math.min(minutes, 24 * 60 - 1));
    java.time.LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
    long slot = today.atTime(at / 60, at % 60).atZone(zone).toInstant().toEpochMilli();
    if (slot > now && today.toEpochDay() != lastDay) return slot;
    return today.plusDays(1).atTime(at / 60, at % 60).atZone(zone).toInstant().toEpochMilli();
  }

  /** Сколько ещё чатов помощник может начать сегодня. */
  public static int left(int startedToday) {
    return Math.max(0, MAX_PER_DAY - startedToday);
  }

  /** Можно ли сегодня начать ещё один чат: начатых помощником сегодня меньше {@link #MAX_PER_DAY}. */
  public static boolean allowed(int startedToday) {
    return startedToday < MAX_PER_DAY;
  }

  /** Срок дела близко (разовое напоминание в пределах двух суток от «сейчас»). */
  static boolean near(Candidate c, long now) {
    return c.reminder > 0
        && !ReminderPolicy.periodic(c.repeat)
        && Math.abs(c.reminder - now) <= NEAR;
  }

  /** Дело давно без движения: класс дела или открытый план, неделя без правок, не старше двух месяцев. */
  static boolean quiet(Candidate c, long now) {
    return (TaskKind.task(c.kind) || c.planOpen)
        && c.updated <= now - QUIET
        && c.created >= now - OLD
        && (c.reminder == 0 || c.reminder > now + NEAR);
  }

  /**
   * До {@code limit} дел, о которых стоит спросить: сначала с близким сроком (ближайшие к «сейчас»),
   * потом давно без движения (самые давние). Сведения ({@link TaskKind#INFO}), серии и заметки, о
   * которых недавно спрашивали, пропускаются.
   */
  public static List<Candidate> pick(List<Candidate> all, long now, int limit) {
    List<Candidate> soon = new ArrayList<>(), stale = new ArrayList<>();
    for (Candidate c : all) {
      if (c.nextAsk > now || TaskKind.INFO.equals(c.kind)) continue;
      if (near(c, now)) soon.add(c);
      else if (quiet(c, now)) stale.add(c);
    }
    soon.sort(Comparator.comparingLong(c -> Math.abs(c.reminder - now)));
    stale.sort(Comparator.comparingLong(c -> c.updated));
    List<Candidate> out = new ArrayList<>(soon);
    out.addAll(stale);
    return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
  }

  /** Вопрос помощника о деле. */
  public static String question(Candidate c, long now, ZoneId zone) {
    String name = "«" + CheckIn.line(c.title, 60) + "»";
    if (c.reminder > 0 && c.reminder <= now) return "Срок " + name + " прошёл. Как с этим делом?";
    if (near(c, now)) return "Скоро " + name + " — " + when(c.reminder, now, zone) + ". Как подготовка?";
    return "Как продвигается " + name + "?";
  }

  private static String when(long at, long now, ZoneId zone) {
    ZonedDateTime t = Instant.ofEpochMilli(at).atZone(zone);
    long days = t.toLocalDate().toEpochDay() - Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay();
    String day = days == 0 ? "сегодня" : days == 1 ? "завтра" : "послезавтра";
    return day + String.format(Locale.ROOT, " в %02d:%02d", t.getHour(), t.getMinute());
  }

  /** Когда снова можно спросить о заметке после ответа (после самого вопроса — пустой ответ). */
  public static long nextAsk(String answer, long now) {
    if (DONE.equals(answer) || DROP.equals(answer)) return Long.MAX_VALUE;
    if (LATER.equals(answer)) return now + 7 * DAY;
    return now + 3 * DAY; // «В процессе» или вопрос без ответа
  }

  /** Ответ — один из вариантов кнопками (сравнение без регистра и пробелов по краям). */
  public static String option(String text) {
    String t = text == null ? "" : text.trim();
    for (String o : OPTIONS) if (o.equalsIgnoreCase(t)) return o;
    return "";
  }

  /** Ответ помощника на выбранный вариант; что предложить дальше, решает вызывающий. */
  public static String reply(String answer, boolean canPostpone) {
    return switch (answer) {
      case DONE -> "Отлично! Отметить выполненным или убрать заметку в корзину?";
      case DROP -> "Понял. Убрать заметку в корзину?";
      case LATER ->
          canPostpone
              ? "Хорошо. Перенести напоминание?"
              : "Хорошо, вернусь к этому через неделю.";
      default -> "Хорошо, спрошу через несколько дней.";
    };
  }

  /**
   * Перенос разового напоминания на {@code days} дней: будущее событие сдвигается на эти дни,
   * прошедшее — на ближайшее то же время суток после «сейчас» плюс оставшиеся дни.
   */
  public static long postponed(long reminder, int days, long now) {
    if (reminder <= 0) return 0;
    if (reminder > now) return reminder + days * DAY;
    long base = reminder;
    while (base <= now) base += DAY;
    return base + (days - 1) * DAY;
  }

  /** Перенести можно только разовое напоминание (у серии свои правила). */
  public static boolean canPostpone(long reminder, int repeat) {
    return reminder > 0 && repeat == ReminderPolicy.REPEAT_NONE;
  }
}
