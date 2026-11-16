package app.fastnotes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Проверяет план синхронизации заметок с календарём без Android. */
public final class CalendarSyncPlanTest {
  static int count;
  static final long NOW = 1_800_000_000_000L;
  static final long HOUR = 3_600_000L;
  static final long DAY = 24 * HOUR;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static Note note(long id, String title, String body, long reminder, int repeat) {
    Note n = new Note(id, title, body, NOW - DAY, NOW - DAY, reminder);
    n.repeat = repeat;
    return n;
  }

  static List<CalendarSyncPlan.Action> kinds(
      CalendarSyncPlan.Plan plan, CalendarSyncPlan.Kind kind) {
    List<CalendarSyncPlan.Action> out = new ArrayList<>();
    for (CalendarSyncPlan.Action a : plan.actions) if (a.kind == kind) out.add(a);
    return out;
  }

  public static void main(String[] args) {
    // Идентификаторы
    check(CalendarSyncPlan.syncId(7).equals("note-7"), "sync id");
    check(CalendarSyncPlan.noteIdOf("note-7") == 7, "parse sync id");
    check(CalendarSyncPlan.noteIdOf("note-0") == -1, "zero id");
    check(CalendarSyncPlan.noteIdOf("note-x") == -1, "bad number");
    check(CalendarSyncPlan.noteIdOf("other-7") == -1, "foreign id");
    check(CalendarSyncPlan.noteIdOf(null) == -1, "null id");

    // Отпечаток
    Note a = note(1, "Встреча", "текст", NOW + HOUR, 0);
    String sig = CalendarSyncPlan.signature(a);
    check(sig.length() == 16, "signature length");
    check(sig.equals(CalendarSyncPlan.signature(note(1, "Встреча", "текст", NOW + HOUR, 0))), "stable");
    check(!sig.equals(CalendarSyncPlan.signature(note(1, "Встреча!", "текст", NOW + HOUR, 0))), "title");
    check(!sig.equals(CalendarSyncPlan.signature(note(1, "Встреча", "текст2", NOW + HOUR, 0))), "body");
    check(!sig.equals(CalendarSyncPlan.signature(note(1, "Встреча", "текст", NOW + 2 * HOUR, 0))), "time");
    check(!sig.equals(CalendarSyncPlan.signature(note(1, "Встреча", "текст", NOW + HOUR, 1))), "repeat");
    Note leadOnly = note(1, "Встреча", "текст", NOW + HOUR, 0);
    leadOnly.lead = 30;
    check(sig.equals(CalendarSyncPlan.signature(leadOnly)), "lead does not change the event");
    check(!CalendarSyncPlan.signature(note(1, "ab", "c", NOW, 0)).equals(CalendarSyncPlan.signature(note(1, "a", "bc", NOW, 0))), "field boundary");

    // Нужно ли создавать
    check(CalendarSyncPlan.wanted(a, NOW), "future wanted");
    check(!CalendarSyncPlan.wanted(note(1, "", "", 0, 0), NOW), "no reminder");
    check(CalendarSyncPlan.wanted(note(1, "", "", NOW - HOUR, 0), NOW), "just passed");
    check(!CalendarSyncPlan.wanted(note(1, "", "", NOW - 2 * DAY, 0), NOW), "old one-time");
    check(CalendarSyncPlan.wanted(note(1, "", "", NOW - 2 * DAY, 2), NOW), "old but repeating");

    Map<Long, String> none = new HashMap<>();
    List<CalendarSyncPlan.Event> noEvents = new ArrayList<>();

    // Первая синхронизация: добавляем только нужное
    List<Note> notes = new ArrayList<>();
    notes.add(a);
    notes.add(note(2, "Без даты", "x", 0, 0));
    notes.add(note(3, "Старое", "x", NOW - 3 * DAY, 0));
    notes.add(note(4, "Повтор", "x", NOW - 3 * DAY, 2));
    CalendarSyncPlan.Plan first = CalendarSyncPlan.plan(notes, noEvents, none, NOW);
    check(first.actions.size() == 2, "insert future and repeating only");
    check(kinds(first, CalendarSyncPlan.Kind.INSERT).size() == 2, "both inserts");
    check(first.actions.get(0).noteId == 1 && first.actions.get(0).signature.equals(sig), "insert carries signature");
    check(first.forget.isEmpty(), "nothing to forget");

    // Ничего не изменилось
    List<CalendarSyncPlan.Event> events = new ArrayList<>();
    events.add(new CalendarSyncPlan.Event(100, 1, sig));
    events.add(new CalendarSyncPlan.Event(104, 4, CalendarSyncPlan.signature(notes.get(3))));
    Map<Long, String> known = new HashMap<>();
    known.put(1L, sig);
    known.put(4L, CalendarSyncPlan.signature(notes.get(3)));
    check(CalendarSyncPlan.plan(notes, events, known, NOW).actions.isEmpty(), "up to date");

    // Правка заметки: обновляем событие
    List<Note> edited = new ArrayList<>(notes);
    edited.set(0, note(1, "Встреча", "новый текст", NOW + HOUR, 0));
    CalendarSyncPlan.Plan upd = CalendarSyncPlan.plan(edited, events, known, NOW);
    check(upd.actions.size() == 1 && upd.actions.get(0).kind == CalendarSyncPlan.Kind.UPDATE, "update on edit");
    check(upd.actions.get(0).eventId == 100, "update targets event");

    // Напоминание убрали: событие удаляется, отпечаток забывается
    List<Note> cleared = new ArrayList<>(notes);
    cleared.set(0, note(1, "Встреча", "текст", 0, 0));
    CalendarSyncPlan.Plan del = CalendarSyncPlan.plan(cleared, events, known, NOW);
    check(del.actions.size() == 1 && del.actions.get(0).kind == CalendarSyncPlan.Kind.DELETE, "delete on cleared reminder");
    check(del.actions.get(0).eventId == 100, "delete targets event");
    check(del.forget.contains(1L), "forget cleared note");

    // Заметку удалили совсем
    List<Note> removed = new ArrayList<>(notes);
    removed.remove(0);
    CalendarSyncPlan.Plan gone = CalendarSyncPlan.plan(removed, events, known, NOW);
    check(kinds(gone, CalendarSyncPlan.Kind.DELETE).size() == 1, "delete on removed note");
    check(gone.forget.contains(1L), "forget removed note");

    // Пользователь удалил событие в календаре: не воскрешаем
    List<CalendarSyncPlan.Event> userDeleted = new ArrayList<>();
    userDeleted.add(new CalendarSyncPlan.Event(104, 4, CalendarSyncPlan.signature(notes.get(3))));
    check(CalendarSyncPlan.plan(notes, userDeleted, known, NOW).actions.isEmpty(), "do not resurrect");
    // …но после правки заметки создаём снова
    check(kinds(CalendarSyncPlan.plan(edited, userDeleted, known, NOW), CalendarSyncPlan.Kind.INSERT).size() == 1, "insert after edit");

    // Одноразовое событие уже в календаре и состарилось: не трогаем и не удаляем
    List<Note> aged = new ArrayList<>();
    aged.add(note(3, "Старое", "x", NOW - 3 * DAY, 0));
    List<CalendarSyncPlan.Event> agedEvents = new ArrayList<>();
    agedEvents.add(new CalendarSyncPlan.Event(103, 3, CalendarSyncPlan.signature(aged.get(0))));
    check(CalendarSyncPlan.plan(aged, agedEvents, none, NOW).actions.isEmpty(), "aged event stays");

    // Дубликаты и чужие события
    List<CalendarSyncPlan.Event> dup = new ArrayList<>();
    dup.add(new CalendarSyncPlan.Event(100, 1, sig));
    dup.add(new CalendarSyncPlan.Event(101, 1, sig));
    dup.add(new CalendarSyncPlan.Event(900, -1, ""));
    List<Note> one = new ArrayList<>();
    one.add(a);
    CalendarSyncPlan.Plan dups = CalendarSyncPlan.plan(one, dup, none, NOW);
    check(dups.actions.size() == 1 && dups.actions.get(0).kind == CalendarSyncPlan.Kind.DELETE && dups.actions.get(0).eventId == 101, "duplicate removed, foreign ignored");

    // Передвинули повтор: событие обновляется на новое время
    List<Note> moved = new ArrayList<>();
    moved.add(note(4, "Повтор", "x", NOW + 2 * DAY, 2));
    List<CalendarSyncPlan.Event> beforeMove = new ArrayList<>();
    beforeMove.add(new CalendarSyncPlan.Event(104, 4, CalendarSyncPlan.signature(notes.get(3))));
    check(kinds(CalendarSyncPlan.plan(moved, beforeMove, known, NOW), CalendarSyncPlan.Kind.UPDATE).size() == 1, "series moved");

    System.out.println("CalendarSyncPlanTest: " + count + " проверок прошли");
  }
}
