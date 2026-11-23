package app.fastnotes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Правила синхронизации заметок с календарём без Android: что добавить, обновить и удалить.
 *
 * <p>Состояние хранит сам календарь: у события есть {@code _SYNC_ID = "note-<номер заметки>"} и
 * отпечаток заметки в {@code SYNC_DATA1}. Приложение помнит только отпечаток, с которым событие
 * было записано в последний раз, чтобы не воскрешать событие, которое пользователь удалил в
 * календаре сам.
 */
public final class CalendarSyncPlan {
  /** Событие без повтора, которое закончилось больше суток назад, заново не создаётся. */
  public static final long GRACE_MS = 24L * 60 * 60 * 1000;

  private static final String PREFIX = "note-";

  public enum Kind {
    INSERT,
    UPDATE,
    DELETE
  }

  /** Событие, найденное в календаре приложения. */
  public static final class Event {
    public final long eventId, noteId;
    public final String signature;

    public Event(long eventId, long noteId, String signature) {
      this.eventId = eventId;
      this.noteId = noteId;
      this.signature = signature == null ? "" : signature;
    }
  }

  public static final class Action {
    public final Kind kind;
    public final long noteId, eventId;
    public final String signature;

    Action(Kind kind, long noteId, long eventId, String signature) {
      this.kind = kind;
      this.noteId = noteId;
      this.eventId = eventId;
      this.signature = signature;
    }
  }

  public static final class Plan {
    public final List<Action> actions = new ArrayList<>();
    /** Заметки, про которые приложению больше не нужно помнить отпечаток. */
    public final Set<Long> forget = new HashSet<>();
  }

  private CalendarSyncPlan() {}

  public static String syncId(long noteId) {
    return PREFIX + noteId;
  }

  /** Номер заметки из {@code _SYNC_ID} или -1, если идентификатор чужой. */
  public static long noteIdOf(String syncId) {
    if (syncId == null || !syncId.startsWith(PREFIX)) return -1;
    try {
      long id = Long.parseLong(syncId.substring(PREFIX.length()));
      return id > 0 ? id : -1;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * Отпечаток того, что попадает в событие: название, текст, время и повтор. Интервал «за N минут»
   * в событие не попадает (оповещает приложение), поэтому в отпечаток не входит.
   */
  public static String signature(Note n) {
    String raw =
        (n.title == null ? "" : n.title)
            + '\u0000'
            + (n.body == null ? "" : n.body)
            + '\u0000'
            + n.reminder
            + '\u0000'
            + n.repeat;
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (int i = 0; i < 8; i++) out.append(String.format("%02x", hash[i] & 0xff));
      return out.toString();
    } catch (NoSuchAlgorithmException e) {
      return Integer.toHexString(raw.hashCode());
    }
  }

  /** Нужно ли заново создавать событие: есть время, и оно не старше суток либо повторяется. */
  public static boolean wanted(Note n, long now) {
    // Серия по списку дат после последней даты больше не повторяется — как разовое событие.
    return n.reminder > 0 && (ReminderPolicy.periodic(n.repeat) || n.reminder + GRACE_MS >= now);
  }

  /**
   * Сравнивает заметки с событиями календаря.
   *
   * @param events события календаря приложения (одно событие на заметку; лишние удаляются)
   * @param known отпечатки, с которыми приложение записывало события в прошлый раз
   */
  public static Plan plan(List<Note> notes, List<Event> events, Map<Long, String> known, long now) {
    Plan plan = new Plan();
    Map<Long, Event> byNote = new LinkedHashMap<>();
    for (Event e : events) {
      if (e.noteId <= 0) continue;
      if (byNote.containsKey(e.noteId)) plan.actions.add(new Action(Kind.DELETE, e.noteId, e.eventId, ""));
      else byNote.put(e.noteId, e);
    }
    Set<Long> live = new HashSet<>();
    for (Note n : notes) {
      if (n.reminder <= 0) continue;
      live.add(n.id);
      String sig = signature(n);
      Event ev = byNote.get(n.id);
      if (ev == null) {
        // Совпадает с прежней записью, а события нет: пользователь удалил его в календаре.
        if (sig.equals(known.get(n.id))) continue;
        if (wanted(n, now)) plan.actions.add(new Action(Kind.INSERT, n.id, 0, sig));
      } else if (!sig.equals(ev.signature)) {
        plan.actions.add(new Action(Kind.UPDATE, n.id, ev.eventId, sig));
      }
    }
    for (Event e : byNote.values())
      if (!live.contains(e.noteId)) plan.actions.add(new Action(Kind.DELETE, e.noteId, e.eventId, ""));
    for (Long id : known.keySet()) if (!live.contains(id)) plan.forget.add(id);
    return plan;
  }
}
