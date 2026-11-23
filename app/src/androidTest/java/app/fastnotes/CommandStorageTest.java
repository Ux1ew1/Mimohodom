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
    responseShapes();
    String name = "command-test-" + System.nanoTime() + ".db";
    try (Store store = new Store(context, name)) {
      semantic(store);
      selectionAndUndo(store);
      clearAndUndo(store);
      suggestionSeries(store);
    } finally {
      context.deleteDatabase(name);
    }
    return checks;
  }

  private static void responseShapes() throws Exception {
    AgentAction.Scope scope = new AgentAction.Scope(System.currentTimeMillis(), ZoneId.systemDefault())
        .note(1, true).note(2, true);
    CommandAnalysis.Reply objects = CommandAnalysis.parse(
        "{\"say\":\"x\",\"actions\":[{\"action\":\"folder\",\"notes\":[{\"id\":2},{\"id\":1}],"
            + "\"folder\":{\"id\":0,\"name\":\"Приложение\"}}]}", scope);
    require(objects.actions.size() == 1 && objects.actions.get(0).folder.equals("Приложение")
        && objects.actions.get(0).notes.equals(List.of(1L, 2L)), "Android parses object folder and note IDs");
    CommandAnalysis.Reply single = CommandAnalysis.parse(
        "{\"say\":\"x\",\"actions\":{\"action\":\"folder\",\"notes\":[1],"
            + "\"folder_name\":\"Приложение\"}}", scope);
    require(single.actions.size() == 1 && single.actions.get(0).folder.equals("Приложение"),
        "Android parses single action and folder_name");
    CommandAnalysis.Reply direct = CommandAnalysis.parse(
        "{\"action\":\"folder\",\"note_ids\":[2],\"folder\":\"Приложение\"}", scope);
    require(direct.actions.size() == 1 && direct.actions.get(0).notes.equals(List.of(2L)),
        "Android parses direct action and note_ids");
    boolean diagnostic = false;
    try {
      CommandAnalysis.parse("{\"say\":\"x\",\"actions\":\"folder\"}", scope);
    } catch (org.json.JSONException expected) {
      diagnostic = expected.getMessage().contains("actions");
    }
    require(diagnostic, "Android reports malformed actions type");
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

  private static void selectionAndUndo(Store store) throws Exception {
    long first = store.save(0, "Приложение: идея", "Нужно сделать выбор", 0);
    long second = store.save(0, "Другая тема", "Не относится к приложению", 0);
    Note one = store.note(first), two = store.note(second);
    AgentAction.Scope scope = new AgentAction.Scope(System.currentTimeMillis(), ZoneId.systemDefault())
        .note(first, true).note(second, true);
    AgentAction action = AgentAction.parse(new JSONObject().put("action", "folder")
        .put("notes", new JSONArray().put(first).put(second)).put("folder", "Приложение"), scope);
    require(action != null, "folder selection action parses");
    CommandDraft draft = CommandDraft.create("перенеси связанные заметки", List.of(action),
        List.of(one, two), List.of(), System.currentTimeMillis(), ZoneId.systemDefault(), 0);
    long chat = store.newChat("Выбор заметок");
    store.message(chat, "assistant", "Предпросмотр", new JSONArray(), draft.toJson());
    long message = store.messages(chat).getJSONObject(0).getLong("id");
    draft.setSelected(second, false);
    store.updateDraft(message, draft.toJson());
    store.save(second, "Другая тема", "Уже изменено пользователем", 0);
    CommandExecutor.Outcome applied = CommandExecutor.apply(store, message);
    Note moved = store.note(first), untouched = store.note(second);
    require(applied.journal > 0 && moved.folder > 0 && untouched.folder == 0,
        "only checked note moves to new folder");
    require(untouched.body.equals("Уже изменено пользователем"),
        "unchecked note edit neither blocks nor rolls back");
    try (Cursor c = store.getReadableDatabase().rawQuery(
        "SELECT data FROM agent_log WHERE id=?", new String[] {Long.toString(applied.journal)})) {
      require(c.moveToFirst() && new JSONObject(c.getString(0)).getJSONArray("folders").length() == 1,
          "journal contains only the checked move");
    }
    AgentLog.undo(store, applied.journal);
    require(store.note(first).folder == 0 && store.note(second).folder == 0
        && store.note(second).body.equals("Уже изменено пользователем"),
        "undo restores checked move and preserves unchecked edit");
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

  /** Предложение ИИ с несколькими датами: вся серия хранится, очищается отказом и правкой. */
  private static void suggestionSeries(Store store) {
    long now = System.currentTimeMillis();
    long first = now + 86_400_000L, second = now + 2 * 86_400_000L, third = now + 3 * 86_400_000L;
    long id = store.save(0, "Буфет", "На 25.11, 28.11, 29.11 пирог со шпинатом", 0);
    Analysis.Result result = new Analysis.Result();
    result.reminder = first;
    result.times = new long[] {first, second, third};
    result.reason = "Три даты буфета";
    result.lead = 1440;
    require(store.applyAnalysis(store.note(id), result), "multi-date suggestion stored");
    Note shown = store.note(id);
    require(shown.suggestion == first && shown.suggestionLead == 1440
        && java.util.Arrays.equals(ReminderPolicy.parseTimes(shown.suggestionTimes),
            new long[] {first, second, third}),
        "series dates readable from the note");
    store.dismissSuggestion(id);
    require(store.note(id).suggestion == 0 && store.note(id).suggestionTimes.isEmpty(),
        "dismiss clears the whole series");
    require(store.applyAnalysis(store.note(id), result), "suggestion restored after dismiss");
    store.save(id, "Буфет", "На 25.11, 28.11, 29.11 пирог со шпинатом", 0);
    Note edited = store.note(id);
    require(edited.suggestion == 0 && edited.suggestionTimes.isEmpty(),
        "user edit clears the whole series");
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    checks++;
  }
}
