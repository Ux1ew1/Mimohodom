package app.fastnotes;

import android.content.Context;
import android.database.Cursor;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
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
      collectionAndIncognito(store);
      planFromReview(store);
    } finally {
      context.deleteDatabase(name);
    }
    collectionDialogue(context);
    return checks;
  }

  private static void planFromReview(Store store) throws Exception {
    String body = "Лена уже заказала стол. После доставки собрать его. Игорю проверить крепления.";
    long id = store.save(0, "Сборка стола", body, 0);
    long withPlan = store.save(0, "Полка", "Заказать и собрать полку.", 0);
    PlanTree existing = new PlanTree();
    existing.add(null, PlanTree.STEP, "Заказать полку");
    store.savePlan(withPlan, existing.toJson());
    ManagerStore manager = new ManagerStore(store);
    List<Note> batch = List.of(store.note(id), store.note(withPlan));
    manager.finishReview(manager.claim(batch), batch, List.of(), Map.of(),
        Map.of(id, TaskKind.EVENT), new JSONObject()
            .put(Long.toString(id), "В заметке три шага, удобно собрать их в чек-лист.")
            .put(Long.toString(withPlan), "Предложение для заметки с планом"));
    require(manager.pending().stream().filter(p -> "plan".equals(p.optString("type"))).count() == 1,
        "Review skips an existing plan");
    JSONObject suggestion = manager.pending().stream().filter(p -> "plan".equals(p.optString("type")))
        .findFirst().orElseThrow();
    require(ManagerStore.firstSource(suggestion) == id, "Review offers a plan for a known note");

    long chat = store.newNoteChat(id, "План: Сборка стола");
    JSONObject model = new JSONObject().put("say", "Подготовил чек-лист.").put("note", id)
        .put("plan", new JSONObject().put("nodes", new JSONArray()
            .put(new JSONObject().put("kind", "step").put("text", "Заказать стол")
                .put("who", "Лена").put("done", true))
            .put(new JSONObject().put("kind", "step").put("text", "Собрать стол после доставки"))
            .put(new JSONObject().put("kind", "step").put("text", "Проверить крепления")
                .put("who", "Игорь").put("at", "2026-12-04 15:00"))));
    JSONObject choice = new JSONObject().put("finish_reason", "stop")
        .put("message", new JSONObject().put("content", model.toString()));
    JSONObject response = new JSONObject().put("choices", new JSONArray().put(choice));
    Polza.Answer reply = ChatRunner.agentReply(store, "test", PlanChat.START, new JSONArray(),
        id, 2000, payload -> {
          require(payload.toString().contains(body), "Agent sees the source plan text");
          return response;
        });
    require(store.plan(id).isEmpty(), "Agent leaves the plan in preview");
    store.message(chat, "assistant", reply.text, reply.sources, reply.draft);
    JSONObject message = store.messages(chat).getJSONObject(0);
    JSONObject draft = new JSONObject(message.getString("draft"));
    PlanTree preview = PlanTree.parse(draft.getString("plan"));
    require(preview.checkable() == 3 && preview.done() == 1, "Preview keeps all steps and progress");
    store.applyNoteDraft(message.getLong("id"), id, null, preview);
    PlanTree saved = PlanTree.parse(store.plan(id));
    require(saved.checkable() == 3 && saved.done() == 1 && store.note(id).body.equals(body),
        "Confirmation saves the checklist beside the original text");
    require(saved.all().stream().anyMatch(node -> node.who.equals("Игорь") && node.at > 0),
        "Deadline and assignee survive saving");

    choice.put("finish_reason", "length");
    try {
      ChatRunner.agentReply(store, "test", PlanChat.START, new JSONArray(), id, 2000,
          payload -> response);
      throw new AssertionError("A truncated plan must not become a draft");
    } catch (java.io.IOException expected) {
      require(expected.getMessage().contains("оборвался"), "Truncated plan can be retried");
    }
  }

  private static void collectionDialogue(Context context) throws Exception {
    String name = "dialogue-test-" + System.nanoTime() + ".db";
    JSONObject response = new JSONObject();
    JSONObject[] request = new JSONObject[1];
    try (Store store = new Store(context, name)) {
      long linked = store.save(0, "Приложение", "Показывать ход выполнения", 0);
      long source = store.save(0, "Интерфейс приложения", "Переключать модели в чате", 0);
      long privateNote = store.save(0, "Секрет приложения", "не-должно-попасть-в-запрос", 0);
      FolderStore folders = new FolderStore(store);
      long folder = folders.create("Личное", false);
      folders.move(privateNote, folder);
      folders.setIncognito(folder, true);
      String command = "Собери все заметки про приложение в одну";
      JSONArray history = new JSONArray()
          .put(new JSONObject().put("role", "user").put("content", command))
          .put(new JSONObject().put("role", "assistant").put("content", "Когда собрать?"));
      JSONObject result = new JSONObject().put("say", "Подготовил сводку").put("actions", new JSONArray())
          .put("collection", new JSONObject().put("title", "Приложение")
              .put("body", "Показывать ход выполнения и переключать модели.")
              .put("sources", new JSONArray().put(linked).put(source)));
      response.put("choices", new JSONArray().put(new JSONObject()
          .put("message", new JSONObject().put("content", result.toString()))));
      Polza.Answer answer = ChatRunner.agentReply(store, "qwen/qwen4-max", "Сейчас",
          history, linked, 2000, payload -> {
            request[0] = payload;
            return response;
          });
      String sent = request[0].toString();
      require(sent.contains(command) && sent.contains("Сейчас"), "Short reply includes the original task");
      require(sent.contains("Переключать модели") && !sent.contains("не-должно-попасть-в-запрос"),
          "Linked chat sends public topic notes and excludes private notes");
      require(answer.draft != null && CollectionDraft.KIND.equals(answer.draft.optString("kind")),
          "Linked chat produces a collection preview");
      String query = "Какие заметки по приложению есть ?";
      String prose = "По приложению есть заметка [[note:" + source + "]] о выборе моделей.";
      Polza.Answer readOnly = modelReply(store, "qwen/qwen4-max", query, prose);
      require(prose.equals(readOnly.text) && readOnly.draft == null,
          "Plain answer to a question about notes is displayed without a draft");
      Polza.Answer withQuestion = modelReply(store, "qwen/qwen4-max", query,
          new JSONObject().put("say", prose).put("question", "Собрать сводку?").toString());
      require(withQuestion.text.equals(prose + "\n\nСобрать сводку?") && withQuestion.draft == null,
          "A follow-up question preserves the answer above it");
      Polza.Answer nullable = modelReply(store, "google/gemma-3-27b-it", query,
          new JSONObject().put("say", prose).put("question", JSONObject.NULL)
              .put("actions", JSONObject.NULL).toString());
      require(nullable.text.equals(prose) && nullable.draft == null, "Android ignores JSON null fields");
      boolean empty = false;
      try {
        modelReply(store, "google/gemma-3-27b-it", query, JSONObject.NULL);
      } catch (java.io.IOException expected) {
        empty = expected.getMessage().contains("Модель не вернула текст ответа");
      }
      require(empty, "An absent model response uses the retry flow");
    } finally {
      context.deleteDatabase(name);
    }
  }

  private static Polza.Answer modelReply(Store store, String model, String query, Object content)
      throws Exception {
    return ChatRunner.agentReply(store, model, query, new JSONArray(), 0, 2000,
        payload -> new JSONObject().put("choices", new JSONArray().put(new JSONObject()
            .put("message", new JSONObject().put("content", content)))));
  }

  private static void collectionAndIncognito(Store store) throws Exception {
    long first = store.save(0, "Интерфейс приложения", "Переключение моделей", 0);
    long second = store.save(0, "Команды приложения", "Предпросмотр сводки", 0);
    long chat = store.newChat("Приложение");
    JSONObject proposal = new JSONObject().put("title", "Доработки приложения")
        .put("body", "Добавить переключение моделей и предпросмотр [[note:" + first + "]].")
        .put("sources", new JSONArray().put(first).put(second));
    JSONObject draft = CollectionDraft.create(store, proposal, List.of(store.note(first), store.note(second)),
        null, false);
    require(!draft.getString("body").contains("[[note:"), "Collection strips note links");
    store.message(chat, "assistant", "Предпросмотр", new JSONArray(), draft);
    long id = store.messages(chat).getJSONObject(0).getLong("id");
    long top = CollectionDraft.apply(store, id);
    ManagerStore manager = new ManagerStore(store);
    require(manager.top(first) == top && manager.top(second) == top, "Sources are in the collection deck");
    long third = store.save(0, "Новая заметка", "Отмена сводки", 0);
    proposal.put("sources", new JSONArray().put(first).put(third))
        .put("body", "Добавить переключение, предпросмотр и отмену.");
    draft = CollectionDraft.create(store, proposal, List.of(store.note(first), store.note(third)),
        null, false);
    require(draft.getLong("top") == top, "Collection proposes extending the existing deck");
    store.message(chat, "assistant", "Предпросмотр", new JSONArray(), draft);
    id = store.messages(chat).getJSONObject(1).getLong("id");
    require(CollectionDraft.apply(store, id) == top && manager.top(third) == top, "Extend reuses the cover");
    long undo;
    try (Cursor c = store.getReadableDatabase().rawQuery(
        "SELECT id FROM agent_log ORDER BY id DESC LIMIT 1", null)) {
      require(c.moveToFirst(), "Collection is journaled");
      undo = c.getLong(0);
    }
    AgentLog.undo(store, undo);
    require(manager.top(third) == 0 && manager.top(first) == top, "Undo restores original deck membership");
    FolderStore folders = new FolderStore(store);
    long folder = folders.create("Инкогнито", false);
    folders.move(third, folder);
    folders.setIncognito(folder, true);
    require(store.note(third) != null && !store.aiAllowed(third), "Private note stays available to user");
    require(store.candidates("Отмена сводки").stream().noneMatch(n -> n.id == third),
        "Private note stays out of AI retrieval");
    require(store.pendingAnalysis().stream().noneMatch(n -> n.id == third),
        "Private note stays out of background analysis");
    SyncRecord exported = new SqliteSyncStore(store).collect(0, "", 100, true).records.stream()
        .filter(r -> Boolean.TRUE.equals(r.incognito)).findFirst().orElseThrow();
    require(Boolean.TRUE.equals(SyncRecord.fromJson(exported.toJson()).incognito),
        "Incognito is serialized with the synchronized folder");
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
