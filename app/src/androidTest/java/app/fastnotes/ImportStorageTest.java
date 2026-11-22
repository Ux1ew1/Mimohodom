package app.fastnotes;

import android.content.Context;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Транзакционный импорт на настоящем Android SQLite в отдельной временной базе. */
final class ImportStorageTest {
  static void run(Context context) throws Exception {
    String name = "import-test-" + System.nanoTime() + ".db";
    try (Store store = new Store(context, name)) {
      long localId = store.save(0, "Моя запись", "Не менять", 0);
      Note localBefore = store.note(localId);

      PlanTree plan = new PlanTree();
      plan.add(null, PlanTree.STEP, "Проверить перенос");
      JSONObject backupJson =
          new JSONObject()
              .put("version", 4)
              .put(
                  "folders",
                  new JSONArray().put(new JSONObject().put("id", 10).put("name", "Переезд")))
              .put(
                  "notes",
                  new JSONArray()
                      .put(
                          new JSONObject()
                              .put("id", localId)
                              .put("title", "Импорт с конфликтом ID")
                              .put("body", "Это другая запись")
                              .put("created", 1000)
                              .put("updated", 2000))
                      .put(
                          new JSONObject()
                              .put("id", localId + 1)
                              .put("title", "Событие")
                              .put("body", "Несколько дат")
                              .put("created", 3000)
                              .put("updated", 4000)
                              .put("reminder", 5000)
                              .put("repeat", ReminderPolicy.REPEAT_LIST)
                              .put("lead", 30)
                              .put("reminderTimes", "[5000,6000]")
                              .put("folder", 10)
                              .put("plan", plan.toJson())
                              .put(
                                  "metadata",
                                  new JSONObject()
                                      .put(
                                          "ai",
                                          new JSONObject()
                                              .put(
                                                  "aliases",
                                                  new JSONArray().put("уникальныйпсевдоним")))))
                      .put(
                          new JSONObject()
                              .put("id", localId + 2)
                              .put("title", "Исходная")
                              .put("body", "Внутри колоды")
                              .put("created", 7000)
                              .put("updated", 8000)))
              .put(
                  "groups",
                  new JSONArray().put(new JSONObject().put("top", localId + 1).put("expanded", true)))
              .put(
                  "members",
                  new JSONArray().put(new JSONObject().put("note", localId + 2).put("top", localId + 1)));

      NotesImport.Backup backup = NotesImport.parse(backupJson.toString());
      NotesImport.Result result = store.importNotes(backup);
      require(result.added == 3 && result.duplicates == 0, "three notes imported despite source ID conflict");
      require(result.foldersAdded == 1 && result.groupsAdded == 1, "folder and group imported");
      require(store.totalNotes() == 4, "local note retained");
      Note localAfter = store.note(localId);
      require(
          localAfter.title.equals(localBefore.title)
              && localAfter.body.equals(localBefore.body)
              && localAfter.updated == localBefore.updated,
          "existing local note unchanged");
      require(result.noteIds.stream().noneMatch(id -> id == localId), "source ID does not overwrite destination ID");

      Note imported = store.note(result.noteIds.get(1));
      require(imported.title.equals("Событие") && imported.created == 3000 && imported.updated == 4000, "content and dates");
      require(imported.reminder == 5000 && imported.lead == 30, "reminder imported");
      require(imported.reminderTimes.equals("[5000,6000]"), "reminder list imported");
      require(imported.folder == new FolderStore(store).list().get(0).id, "folder mapped");
      require(PlanTree.parse(store.plan(imported.id)).nodes.size() == 1, "plan imported");
      require(store.searchNotes("уникальныйпсевдоним").stream().anyMatch(n -> n.id == imported.id), "AI alias searchable");
      ManagerStore manager = new ManagerStore(store);
      List<Long> members = manager.members(imported.id);
      require(members.size() == 1 && members.get(0).equals(result.noteIds.get(2)), "group links mapped");
      require(manager.expanded(imported.id), "group expansion imported");

      NotesImport.Result repeated = store.importNotes(NotesImport.parse(backupJson.toString()));
      require(repeated.added == 0 && repeated.duplicates == 3, "repeat import deduplicated");
      require(repeated.foldersAdded == 0 && repeated.groupsAdded == 0, "repeat creates no folders or groups");
      require(store.totalNotes() == 4 && manager.members(imported.id).size() == 1, "repeat leaves data intact");

      int beforeRollback = store.totalNotes();
      NotesImport.Backup broken = NotesImport.parse(backupJson.toString());
      for (NotesImport.Entry entry : broken.notes) entry.body += " — новая редакция";
      broken.notes.get(1).metadata = null;
      boolean failed = false;
      try {
        store.importNotes(broken);
      } catch (RuntimeException expected) {
        failed = true;
      }
      require(failed, "second insert failure surfaces");
      require(store.totalNotes() == beforeRollback, "failure rolls back first insert");
      require(new FolderStore(store).list().size() == 1, "failure rolls back folders and groups");
    } finally {
      context.deleteDatabase(name);
    }
  }

  private static void require(boolean value, String label) {
    if (!value) throw new AssertionError(label);
  }
}
