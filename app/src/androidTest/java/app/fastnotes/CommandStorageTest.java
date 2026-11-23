package app.fastnotes;

import android.content.Context;
import android.database.Cursor;
import java.time.ZoneId;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Настоящий Android SQLite: смысловой индекс, очистка расписания и обратимая отмена. */
final class CommandStorageTest {
  private static int checks;

  private CommandStorageTest() {}

  static int run(Context context) throws Exception {
    checks = 0;
    String name = "command-test-" + System.nanoTime() + ".db";
    try (Store store = new Store(context, name)) {
      semantic(store);
      clearAndUndo(store);
    } finally {
      context.deleteDatabase(name);
    }
    return checks;
  }

  private static void semantic(Store store) {
    long id = store.save(0, "Летняя поездка", "Поездка к морю в июле", 0);
    require(store.candidates("Что я записывал про отпуск?").stream().anyMatch(n -> n.id == id),
        "semantic query finds sea trip");
    String first = fingerprint(store, id);
    require(first.equals(SemanticEmbedding.fingerprint(store.note(id))),
        "stored fingerprint matches note");
    store.candidates("отпуск");
    require(first.equals(fingerprint(store, id)), "unchanged note keeps stored vector");
    store.save(id, "Летняя поездка", "Поездка к морю в июле, взять билеты", 0);
    store.candidates("отпуск");
    String changed = fingerprint(store, id);
    require(!first.equals(changed) && changed.equals(SemanticEmbedding.fingerprint(store.note(id))),
        "edited note receives a fresh vector");
    try (Cursor c = store.getReadableDatabase().rawQuery(
        "SELECT count(*) FROM note_embeddings", null)) {
      require(c.moveToFirst() && c.getInt(0) == 1, "one note keeps one vector row");
    }
  }

  private static String fingerprint(Store store, long id) {
    try (Cursor c = store.getReadableDatabase().rawQuery(
        "SELECT fingerprint FROM note_embeddings WHERE note_id=?",
        new String[] {Long.toString(id)})) {
      require(c.moveToFirst(), "vector row exists");
      return c.getString(0);
    }
  }

  private static void clearAndUndo(Store store) throws Exception {
    long now = System.currentTimeMillis();
    long first = now + 86_400_000L, second = now + 2 * 86_400_000L;
    long id = store.save(0, "Завершённое дело", "Все шаги сделаны", first,
        ReminderPolicy.REPEAT_LIST, 15, ReminderPolicy.encodeTimes(new long[] {first, second}));
    PlanTree plan = new PlanTree();
    PlanTree.Node step = new PlanTree.Node();
    step.id = "1";
    step.text = "Готово";
    step.done = true;
    plan.nodes.add(step);
    store.savePlan(id, plan.toJson());
    Note before = store.note(id);
    long message = draft(store, before, "удали напоминания у выполненных дел");
    CommandExecutor.Outcome applied = CommandExecutor.apply(store, message);
    Note cleared = store.note(id);
    require(applied.journal > 0 && applied.reminders.size() == 1,
        "one journal row and one schedule change");
    require(cleared.reminder == 0 && cleared.reminderTimes.isEmpty()
        && cleared.repeat == ReminderPolicy.REPEAT_NONE && cleared.lead == 15,
        "clear removes every date and repeat but preserves lead");
    AgentLog.undo(store, applied.journal);
    Note restored = store.note(id);
    require(restored.reminder == before.reminder && restored.reminderTimes.equals(before.reminderTimes)
        && restored.repeat == before.repeat && restored.lead == before.lead,
        "undo restores the exact old schedule");
    boolean twice = false;
    try {
      AgentLog.undo(store, applied.journal);
    } catch (IllegalStateException expected) {
      twice = true;
    }
    require(twice, "second undo is rejected");

    Note current = store.note(id);
    long conflictMessage = draft(store, current, "очисти выполненное");
    CommandExecutor.Outcome conflict = CommandExecutor.apply(store, conflictMessage);
    store.save(id, current.title, current.body, now + 3 * 86_400_000L,
        ReminderPolicy.REPEAT_NONE, 15, "");
    boolean conflicted = false;
    try {
      AgentLog.undo(store, conflict.journal);
    } catch (IllegalStateException expected) {
      conflicted = true;
    }
    require(conflicted && store.note(id).reminder == now + 3 * 86_400_000L,
        "later user schedule blocks undo without partial restoration");

    Note unfinished = store.note(id);
    long staleMessage = draft(store, unfinished, "очисти выполненное");
    plan.nodes.get(0).done = false;
    store.savePlan(id, plan.toJson());
    boolean rejected = false;
    try {
      CommandExecutor.apply(store, staleMessage);
    } catch (IllegalStateException expected) {
      rejected = true;
    }
    require(rejected && store.note(id).reminder == unfinished.reminder,
        "newly unfinished plan rejects clear inside transaction");
  }

  private static long draft(Store store, Note note, String command) throws Exception {
    AgentAction.Scope scope = new AgentAction.Scope(System.currentTimeMillis(), ZoneId.systemDefault())
        .note(note.id, true).completed(note.id);
    AgentAction action = AgentAction.parse(new JSONObject()
        .put("action", "clear_reminders").put("notes", new JSONArray().put(note.id)), scope);
    require(action != null, "clear action parses");
    CommandDraft draft = CommandDraft.create(command, List.of(action), List.of(note), List.of(),
        System.currentTimeMillis(), ZoneId.systemDefault(), 0);
    require(draft.actions.size() == 1 && !draft.preview().isEmpty(), "clear preview is exact");
    long chat = store.newChat("Проверка команды");
    store.message(chat, "assistant", "Предпросмотр", new JSONArray(), draft.toJson());
    return store.messages(chat).getJSONObject(0).getLong("id");
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    checks++;
  }
}
