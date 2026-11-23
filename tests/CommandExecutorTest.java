package app.fastnotes;

import java.time.*;
import java.util.*;
import org.json.*;

/** Инварианты пакета, который CommandExecutor проверяет перед SQLite-транзакцией. */
public final class CommandExecutorTest {
  static int count;
  static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
  static final long NOW = LocalDateTime.of(2026, 11, 22, 12, 0).atZone(ZONE).toInstant().toEpochMilli();
  static final long AT = LocalDateTime.of(2026, 11, 23, 10, 0).atZone(ZONE).toInstant().toEpochMilli();

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static AgentAction action(String json) {
    AgentAction.Scope scope = new AgentAction.Scope(NOW, ZONE).note(1, true);
    AgentAction out = AgentAction.parse(new JSONObject(json), scope);
    if (out == null) throw new AssertionError("action did not parse: " + json);
    return out;
  }

  static Note note() {
    Note n = new Note(1, "Дело", "Текст", 10, 123456, 0);
    n.folder = 7;
    n.lead = 15;
    return n;
  }

  public static void main(String[] args) throws Exception {
    snapshot();
    schedules();
    integrity();
    selection();
    journal();
    System.out.println("PASS: " + count + " command executor invariant checks");
  }

  static void selection() throws Exception {
    Note second = new Note(2, "Другая тема", "Текст", 10, 123457, 0);
    second.folder = 7;
    AgentAction.Scope scope = new AgentAction.Scope(NOW, ZONE).note(1, true).note(2, true);
    AgentAction folder = AgentAction.parse(new JSONObject()
        .put("action", "folder").put("notes", new JSONArray().put(1).put(2)).put("folder", "Дом"), scope);
    CommandDraft draft = CommandDraft.create("перенеси", List.of(folder), List.of(note(), second),
        List.of(), NOW, ZONE, 0);
    check(draft.selected.equals(new TreeSet<>(List.of(1L, 2L))), "all proposed notes selected by default");
    draft.setSelected(2, false);
    check(draft.selectedActions().size() == 1
        && draft.selectedActions().get(0).notes.equals(List.of(1L)), "unchecked note removed from action copy");
    check(draft.required(1) && !draft.required(2), "unchecked note no longer blocks revisions");
    check(draft.preview().get(0).contains("Переместить 1 заметку"), "preview follows selected count");
    JSONObject persisted = draft.toJson();
    CommandDraft copy = CommandDraft.fromJson(persisted);
    check(copy.isSelected(1) && !copy.isSelected(2), "selection survives draft persistence");
    JSONObject legacy = new JSONObject(persisted.toString());
    legacy.remove("selected");
    check(CommandDraft.fromJson(legacy).selected.equals(new TreeSet<>(List.of(1L, 2L))),
        "legacy draft selects all notes");
    copy.setSelected(1, false);
    check(!copy.hasSelectedChanges() && copy.selectedActions().isEmpty()
        && !copy.futureError(NOW).isEmpty(), "empty selection cannot enter transaction");
    JSONObject broken = new JSONObject(persisted.toString());
    broken.put("selected", new JSONArray().put(99));
    boolean rejected = false;
    try { CommandDraft.fromJson(broken); } catch (JSONException expected) { rejected = true; }
    check(rejected, "unknown selected ID rejected");
  }

  static void snapshot() throws Exception {
    Note original = note();
    original.reminder = AT;
    original.reminderTimes = "";
    CommandDraft draft = CommandDraft.create(
        "напомни",
        List.of(action("{\"action\":\"remind\",\"note\":1,\"times\":[\"2026-11-24 10:00\"],\"lead\":60}")),
        List.of(original), List.of(), NOW, ZONE, 0);
    CommandDraft.NoteState state = draft.notes.get(1L);
    check(state.updated == 123456 && state.folder == 7, "revision and folder snapshotted");
    check(state.reminder == AT && state.repeat == ReminderPolicy.REPEAT_NONE && state.lead == 15, "old schedule snapshotted");
    check(state.schedule().equals(new TreeSet<>(List.of(AT))), "old schedule reconstructed");
    CommandDraft.NoteState restored = CommandDraft.fromJson(draft.toJson()).notes.get(1L);
    check(restored.updated == state.updated && restored.note().folder == 7, "revision survives draft persistence");
    check(draft.futureError(NOW).isEmpty(), "fresh draft can enter transaction");
    check(!draft.futureError(AT + 86_400_000L).isEmpty(), "stale future date blocks whole transaction");
  }

  static void schedules() {
    Note n = note();
    n.repeat = ReminderPolicy.REPEAT_LIST;
    long[] old = new long[40];
    for (int i = 0; i < old.length; i++) old[i] = AT + i * 60_000L;
    n.reminder = old[0];
    n.reminderTimes = ReminderPolicy.encodeTimes(old);
    AgentAction extra = action("{\"action\":\"remind\",\"note\":1,\"times\":[\"2026-11-25 10:00\"]}");
    CommandDraft full = CommandDraft.create("x", List.of(extra), List.of(n), List.of(), NOW, ZONE, 0);
    check(full.actions.isEmpty() && full.rejected == 1, "forty-one dates rejected instead of silent truncation");

    n.reminderTimes = ReminderPolicy.encodeTimes(Arrays.copyOf(old, 39));
    AgentAction another = action("{\"action\":\"remind\",\"note\":1,\"times\":[\"2026-11-25 10:00\"]}");
    CommandDraft fits = CommandDraft.create("x", List.of(another), List.of(n), List.of(), NOW, ZONE, 0);
    check(fits.actions.size() == 1, "fortieth date remains allowed");
    check(fits.actions.get(0).times.size() == 1, "new time kept exactly once");
  }

  static void integrity() throws Exception {
    AgentAction folder = action("{\"action\":\"folder\",\"notes\":[1],\"folder\":\"Дом\"}");
    CommandDraft draft = CommandDraft.create("перенеси", List.of(folder), List.of(note()), List.of(), NOW, ZONE, 2);
    JSONObject json = draft.toJson();
    check(CommandDraft.isDraft(json) && json.getInt("rejected") == 2, "partial rejection warning persisted");
    CommandDraft copy = CommandDraft.fromJson(json);
    check(copy.actions.size() == 1 && copy.folders.get(0).id == 0, "new folder state persisted");
    check(copy.notes.get(1L).title.equals("Дело"), "affected note title persisted for preview");
    check(copy.preview().get(0).contains("новую папку"), "local preview needs no model wording");
    copy.actions.add(action("{\"action\":\"folder\",\"notes\":[1],\"folder\":\"Дом\"}"));
    // Сериализация не исполняет действие и не меняет исходный снимок.
    check(draft.actions.size() == 1 && draft.notes.get(1L).folder == 7, "preview manipulation has no data side effect");
  }

  static void journal() {
    long created = NOW;
    check(AgentJournal.canUndo(AgentJournal.DONE, created, created + AgentJournal.UNDO_WINDOW - 1), "undo available within a day");
    check(!AgentJournal.canUndo(AgentJournal.DONE, created, created + AgentJournal.UNDO_WINDOW), "undo closed after a day");
    check(!AgentJournal.canUndo(AgentJournal.UNDONE, created, created), "second undo impossible");
    String description = AgentJournal.describe(AgentJournal.COMMAND_BATCH, new JSONObject()
        .put("command", "Перенеси в Дом")
        .put("created_notes", new JSONArray())
        .put("folders", new JSONArray().put(new JSONObject()))
        .put("reminders", new JSONArray().put(new JSONObject())));
    check(description.contains("Команда") && description.contains("Перенеси"), "one journal row describes package");
  }
}
