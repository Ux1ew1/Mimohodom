package app.fastnotes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Правила синхронизации заметок с календарём без Android: что добавить, обновить и удалить.
 *
 * <p>У обычного события идентификатор {@code note-<id>}, у даты из списка —
 * {@code note-<id>@<время>}. Отпечаток события в {@code SYNC_DATA1} и его последняя выгруженная
 * копия в настройках позволяют учитывать ручное удаление событий из календаря.
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
    public final long eventId, begin;
    public final String syncId, signature;

    public Event(long eventId, String syncId, String signature, long begin) {
      this.eventId = eventId;
      this.syncId = syncId;
      this.signature = signature;
      this.begin = begin;
    }
  }

  public static final class Action {
    public final Kind kind;
    public final long eventId;
    public final String syncId, signature;
    public final CalendarLink link;

    Action(Kind kind, long eventId, String syncId, String signature, CalendarLink link) {
      this.kind = kind;
      this.eventId = eventId;
      this.syncId = syncId;
      this.signature = signature;
      this.link = link;
    }
  }

  public static final class Plan {
    public final List<Action> actions = new ArrayList<>();
    /** Ручные удаления, перенесённые со старого ключа заметки на ключ отдельной даты. */
    public final Map<String, String> remember = new LinkedHashMap<>();
    public final Set<String> forget = new HashSet<>();
  }

  private CalendarSyncPlan() {}

  public static String syncId(long noteId) {
    return PREFIX + noteId;
  }

  static String dateId(long noteId, long at) {
    return syncId(noteId) + "@" + at;
  }

  static String memoryKey(String syncId) {
    return "n." + syncId.substring(PREFIX.length());
  }

  /** Номер заметки из {@code _SYNC_ID} или -1, если идентификатор чужой. */
  public static long noteIdOf(String syncId) {
    if (syncId == null || !syncId.startsWith(PREFIX)) return -1;
    try {
      int separator = syncId.indexOf('@');
      if (separator < 0) {
        long id = Long.parseLong(syncId.substring(PREFIX.length()));
        return id > 0 ? id : -1;
      }
      long id = Long.parseLong(syncId.substring(PREFIX.length(), separator));
      long at = Long.parseLong(syncId.substring(separator + 1));
      return id > 0 && at > 0 ? id : -1;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * Отпечаток того, что попадает в событие: название, текст, время и повтор. Интервал «за N минут»
   * в событие не попадает (оповещает приложение), поэтому в отпечаток не входит.
   */
  public static String signature(Note n) {
    return signature(n, n.reminder, n.repeat);
  }

  private static String signature(Note n, long at, int repeat) {
    String raw =
        (n.title == null ? "" : n.title)
            + '\u0000'
            + (n.body == null ? "" : n.body)
            + '\u0000'
            + at
            + '\u0000'
            + repeat;
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (int i = 0; i < 8; i++) out.append(String.format("%02x", hash[i] & 0xff));
      return out.toString();
    } catch (NoSuchAlgorithmException e) {
      return Integer.toHexString(raw.hashCode());
    }
  }

  public static boolean wanted(Note n, long now) {
    return wanted(n.reminder, n.repeat, now);
  }

  private static boolean wanted(long at, int repeat, long now) {
    return at > 0 && (ReminderPolicy.periodic(repeat) || at >= now - GRACE_MS);
  }

  /** Сверяет события календаря с текущими датами заметок и последними выгруженными отпечатками. */
  public static Plan plan(List<Note> notes, List<Event> events, Map<String, ?> known, long now) {
    Plan plan = new Plan();
    Map<String, Event> unmatched = new LinkedHashMap<>();
    for (Event e : events) {
      if (noteIdOf(e.syncId) > 0 && unmatched.putIfAbsent(e.syncId, e) != null) {
        delete(plan, e);
      }
    }
    Set<String> live = new HashSet<>();
    for (Note n : notes) {
      if (n.reminder <= 0) continue;
      String id = syncId(n.id);
      long[] dates = n.repeat == ReminderPolicy.REPEAT_LIST
          ? ReminderPolicy.parseTimes(n.reminderTimes) : new long[0];
      if (dates.length == 0) {
        Event event = unmatched.remove(id);
        if (event == null && !ReminderPolicy.periodic(n.repeat)) {
          event = unmatched.remove(dateId(n.id, n.reminder));
        }
        live.add(memoryKey(id));
        reconcile(plan, n, n.reminder, n.repeat, id, event, known.get(memoryKey(id)), now);
        continue;
      }

      Event legacy = unmatched.get(id);
      Object legacySignature = known.get(memoryKey(id));
      for (long at : dates) {
        String occurrence = dateId(n.id, at);
        String key = memoryKey(occurrence);
        live.add(key);
        Event event = unmatched.remove(occurrence);
        if (event == null && legacy != null && legacy.begin == at) {
          event = unmatched.remove(id);
        }
        Object remembered = known.get(key);
        if (event == null && remembered == null && legacySignature instanceof String
            && signature(n, at, ReminderPolicy.REPEAT_LIST).equals(legacySignature)) {
          String sig = signature(n, at, ReminderPolicy.REPEAT_NONE);
          plan.remember.put(key, sig);
          remembered = sig;
        }
        reconcile(plan, n, at, ReminderPolicy.REPEAT_NONE, occurrence, event, remembered, now);
      }
    }
    for (Event event : unmatched.values()) delete(plan, event);
    for (String key : known.keySet()) {
      if (key.startsWith("n.") && !live.contains(key)) plan.forget.add(key);
    }
    return plan;
  }

  private static void reconcile(
      Plan plan, Note n, long at, int repeat, String id, Event event, Object remembered, long now) {
    String sig = signature(n, at, repeat);
    if (event == null) {
      // Если этот отпечаток уже выгружался, событие удалили в календаре вручную.
      if (sig.equals(remembered) || !wanted(at, repeat, now)) return;
    } else if (id.equals(event.syncId) && sig.equals(event.signature)) {
      return;
    }
    Kind kind = event == null ? Kind.INSERT : Kind.UPDATE;
    plan.actions.add(new Action(
        kind, event == null ? 0 : event.eventId, id, sig, CalendarLink.of(n.title, n.body, at, repeat)));
  }

  private static void delete(Plan plan, Event event) {
    plan.actions.add(new Action(Kind.DELETE, event.eventId, event.syncId, "", null));
  }
}
