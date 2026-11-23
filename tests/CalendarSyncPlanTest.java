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

  static CalendarSyncPlan.Event event(long id, long noteId, String signature) {
    return new CalendarSyncPlan.Event(id, CalendarSyncPlan.syncId(noteId), signature, 0);
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

    Map<String, String> none = new HashMap<>();
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
    check(first.actions.get(0).syncId.equals("note-1") && first.actions.get(0).signature.equals(sig), "insert carries signature");
    check(first.forget.isEmpty(), "nothing to forget");

    // Ничего не изменилось
    List<CalendarSyncPlan.Event> events = new ArrayList<>();
    events.add(event(100, 1, sig));
    events.add(event(104, 4, CalendarSyncPlan.signature(notes.get(3))));
    Map<String, String> known = new HashMap<>();
    known.put("n.1", sig);
    known.put("n.4", CalendarSyncPlan.signature(notes.get(3)));
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
    check(del.forget.contains("n.1"), "forget cleared note");

    // Заметку удалили совсем
    List<Note> removed = new ArrayList<>(notes);
    removed.remove(0);
    CalendarSyncPlan.Plan gone = CalendarSyncPlan.plan(removed, events, known, NOW);
    check(kinds(gone, CalendarSyncPlan.Kind.DELETE).size() == 1, "delete on removed note");
    check(gone.forget.contains("n.1"), "forget removed note");

    // Пользователь удалил событие в календаре: не воскрешаем
    List<CalendarSyncPlan.Event> userDeleted = new ArrayList<>();
    userDeleted.add(event(104, 4, CalendarSyncPlan.signature(notes.get(3))));
    check(CalendarSyncPlan.plan(notes, userDeleted, known, NOW).actions.isEmpty(), "do not resurrect");
    // …но после правки заметки создаём снова
    check(kinds(CalendarSyncPlan.plan(edited, userDeleted, known, NOW), CalendarSyncPlan.Kind.INSERT).size() == 1, "insert after edit");

    // Одноразовое событие уже в календаре и состарилось: не трогаем и не удаляем
    List<Note> aged = new ArrayList<>();
    aged.add(note(3, "Старое", "x", NOW - 3 * DAY, 0));
    List<CalendarSyncPlan.Event> agedEvents = new ArrayList<>();
    agedEvents.add(event(103, 3, CalendarSyncPlan.signature(aged.get(0))));
    check(CalendarSyncPlan.plan(aged, agedEvents, none, NOW).actions.isEmpty(), "aged event stays");

    // Дубликаты и чужие события
    List<CalendarSyncPlan.Event> dup = new ArrayList<>();
    dup.add(event(100, 1, sig));
    dup.add(event(101, 1, sig));
    dup.add(event(900, -1, ""));
    List<Note> one = new ArrayList<>();
    one.add(a);
    CalendarSyncPlan.Plan dups = CalendarSyncPlan.plan(one, dup, none, NOW);
    check(dups.actions.size() == 1 && dups.actions.get(0).kind == CalendarSyncPlan.Kind.DELETE && dups.actions.get(0).eventId == 101, "duplicate removed, foreign ignored");

    // Передвинули повтор: событие обновляется на новое время
    List<Note> moved = new ArrayList<>();
    moved.add(note(4, "Повтор", "x", NOW + 2 * DAY, 2));
    List<CalendarSyncPlan.Event> beforeMove = new ArrayList<>();
    beforeMove.add(event(104, 4, CalendarSyncPlan.signature(notes.get(3))));
    check(kinds(CalendarSyncPlan.plan(moved, beforeMove, known, NOW), CalendarSyncPlan.Kind.UPDATE).size() == 1, "series moved");

    listDates();
    System.out.println("CalendarSyncPlanTest: " + count + " проверок прошли");
  }

  static void listDates() {
    long old = NOW - 3 * DAY, first = NOW + HOUR, second = NOW + DAY, third = NOW + 3 * DAY;
    Note n = note(7, "Несколько дат", "подробности", first, ReminderPolicy.REPEAT_LIST);
    n.reminderTimes = ReminderPolicy.encodeTimes(new long[] {old, first, second, third});
    String firstId = CalendarSyncPlan.dateId(n.id, first);
    String secondId = CalendarSyncPlan.dateId(n.id, second);
    String thirdId = CalendarSyncPlan.dateId(n.id, third);
    String legacyId = CalendarSyncPlan.syncId(n.id);
    String legacyKey = CalendarSyncPlan.memoryKey(legacyId);
    String firstKey = CalendarSyncPlan.memoryKey(firstId);
    String secondKey = CalendarSyncPlan.memoryKey(secondId);
    check(CalendarSyncPlan.noteIdOf(firstId) == n.id, "parse date-specific id");
    check(CalendarSyncPlan.noteIdOf("note-7@0") == -1, "invalid date id");

    CalendarSyncPlan.Plan empty = CalendarSyncPlan.plan(List.of(n), List.of(), Map.of(), NOW);
    List<CalendarSyncPlan.Action> inserts = kinds(empty, CalendarSyncPlan.Kind.INSERT);
    check(inserts.size() == 3, "insert three future dates, skip old date");
    check(inserts.get(0).link.begin == first && inserts.get(1).link.begin == second
        && inserts.get(2).link.begin == third, "each event has its own start");
    check(inserts.stream().allMatch(action -> action.link.rrule == null), "list dates are one-time events");

    String legacySig = CalendarSyncPlan.signature(n);
    CalendarSyncPlan.Event legacy = new CalendarSyncPlan.Event(70, legacyId, legacySig, first);
    Map<String, String> known = new HashMap<>();
    known.put(legacyKey, legacySig);
    CalendarSyncPlan.Plan migrated = CalendarSyncPlan.plan(List.of(n), List.of(legacy), known, NOW);
    List<CalendarSyncPlan.Action> updates = kinds(migrated, CalendarSyncPlan.Kind.UPDATE);
    check(updates.size() == 1 && updates.get(0).syncId.equals(firstId)
        && updates.get(0).eventId == 70, "reuse exported event during upgrade");
    check(kinds(migrated, CalendarSyncPlan.Kind.INSERT).size() == 2
        && kinds(migrated, CalendarSyncPlan.Kind.DELETE).isEmpty(), "fill missing dates without duplicating");
    check(migrated.forget.contains(legacyKey), "retire old memory key");

    List<CalendarSyncPlan.Event> exported = new ArrayList<>();
    known.clear();
    for (CalendarSyncPlan.Action action : migrated.actions) {
      exported.add(new CalendarSyncPlan.Event(70 + exported.size(), action.syncId,
          action.signature, action.link.begin));
      known.put(CalendarSyncPlan.memoryKey(action.syncId), action.signature);
    }
    check(CalendarSyncPlan.plan(List.of(n), exported, known, NOW).actions.isEmpty(), "repeat sync is a no-op");
    n.reminder = second;
    check(CalendarSyncPlan.plan(List.of(n), exported, known, first + 1).actions.isEmpty(),
        "alarm advance leaves every calendar event at its original start");

    List<CalendarSyncPlan.Event> deleted = new ArrayList<>(exported);
    deleted.removeIf(event -> event.syncId.equals(secondId));
    check(CalendarSyncPlan.plan(List.of(n), deleted, known, NOW).actions.isEmpty(),
        "one deleted date is not recreated over other dates");
    CalendarSyncPlan.Plan deletedLegacy = CalendarSyncPlan.plan(
        List.of(n), List.of(), Map.of(legacyKey, legacySig), NOW);
    check(deletedLegacy.remember.get(firstKey).equals(known.get(firstKey))
        && deletedLegacy.forget.contains(legacyKey), "migrate manual deletion of the old event");
    check(kinds(deletedLegacy, CalendarSyncPlan.Kind.INSERT).stream()
        .map(action -> action.syncId).toList().equals(List.of(secondId, thirdId)),
        "legacy deletion suppresses only its original date, even after alarm advance");

    n.reminderTimes = ReminderPolicy.encodeTimes(new long[] {first, third});
    CalendarSyncPlan.Plan changed = CalendarSyncPlan.plan(List.of(n), exported, known, NOW);
    check(changed.actions.size() == 1 && changed.actions.get(0).kind == CalendarSyncPlan.Kind.DELETE
        && changed.actions.get(0).syncId.equals(secondId), "remove only excluded date");
    check(changed.forget.contains(secondKey), "forget excluded date");

    n.repeat = ReminderPolicy.REPEAT_NONE;
    n.reminder = third;
    CalendarSyncPlan.Plan single = CalendarSyncPlan.plan(List.of(n), exported, known, NOW);
    check(kinds(single, CalendarSyncPlan.Kind.INSERT).isEmpty()
        && kinds(single, CalendarSyncPlan.Kind.UPDATE).get(0).eventId == exported.get(2).eventId
        && kinds(single, CalendarSyncPlan.Kind.DELETE).size() == 2,
        "shrinking to one date reuses the matching event");

    n.repeat = ReminderPolicy.REPEAT_LIST;
    n.reminderTimes = "";
    CalendarSyncPlan.Plan fallback = CalendarSyncPlan.plan(List.of(n), List.of(), Map.of(), NOW);
    check(fallback.actions.size() == 1 && fallback.actions.get(0).link.begin == third,
        "older incomplete data keeps its saved single reminder");
  }
}
