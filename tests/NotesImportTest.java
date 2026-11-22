package app.fastnotes;

import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Разбор и дедупликация резервных JSON-копий без Android. */
public final class NotesImportTest {
  private static int count;

  private static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  private static JSONObject note(long id, String title, String body) throws Exception {
    return new JSONObject().put("id", id).put("title", title).put("body", body);
  }

  private static void rejected(String raw, String name) {
    try {
      NotesImport.parse(raw);
      throw new AssertionError(name);
    } catch (org.json.JSONException expected) {
      count++;
    }
  }

  public static void main(String[] args) throws Exception {
    PlanTree plan = new PlanTree();
    PlanTree.Node step = plan.add(null, PlanTree.STEP, "Собрать документы");
    plan.add(step.id, PlanTree.ITEM, "Паспорт").done = true;
    String storedPlan = plan.toJson();
    JSONObject full =
        new JSONObject()
            .put("version", 4)
            .put(
                "folders",
                new JSONArray()
                    .put(new JSONObject().put("id", 8).put("name", "  Дом  ").put("auto", true))
                    .put(new JSONObject().put("id", 9).put("name", "   ")))
            .put(
                "notes",
                new JSONArray()
                    .put(
                        note(41, "Поездка", "Купить билеты")
                            .put("created", 1000)
                            .put("updated", 2000)
                            .put("reminder", 5000)
                            .put("repeat", 5)
                            .put("lead", 60)
                            .put("reminderTimes", "[7000,5000,7000,0,ошибка]")
                            .put("folder", 8)
                            .put("plan", storedPlan)
                            .put(
                                "metadata",
                                new JSONObject()
                                    .put(
                                        "ai",
                                        new JSONObject()
                                            .put("aliases", new JSONArray().put("вокзал"))
                                            .put("unknown", "не переносить"))))
                    .put(note(42, "", "Первая строка\nПродолжение").put("folder", 999))
                    .put(note(43, "Без плана", "Текст").put("plan", "сломано")))
            .put(
                "groups",
                new JSONArray()
                    .put(new JSONObject().put("top", "41").put("expanded", 1))
                    .put(new JSONObject().put("top", 999))
                    .put(new JSONObject().put("top", 41)))
            .put(
                "members",
                new JSONArray()
                    .put(new JSONObject().put("note", "42").put("top", "41"))
                    .put(new JSONObject().put("note", 43).put("top", 999))
                    .put(new JSONObject().put("note", 41).put("top", 41))
                    .put(new JSONObject().put("note", 42).put("top", 41)));

    NotesImport.Backup backup = NotesImport.parse(full.toString());
    check(backup.notes.size() == 3, "full v4 notes");
    check(backup.folders.size() == 1 && backup.folders.get(0).name.equals("Дом"), "folders cleaned");
    check(backup.folders.get(0).auto, "folder flag");
    NotesImport.Entry first = backup.notes.get(0);
    check(first.sourceId == 41 && first.created == 1000 && first.updated == 2000, "timestamps");
    check(first.reminder == 5000 && first.repeat == 5 && first.lead == 60, "reminder fields");
    check(first.reminderTimes.equals("[5000,7000]"), "dates sorted and deduplicated");
    check(first.folder == 8 && first.plan.equals(storedPlan), "folder and plan retained");
    check(first.metadata.getJSONObject("ai").getJSONArray("aliases").getString(0).equals("вокзал"), "AI alias");
    check(!first.metadata.getJSONObject("ai").has("unknown"), "unknown AI field removed");
    check(backup.notes.get(1).title.equals("Первая строка"), "automatic title");
    check(backup.notes.get(1).folder == 999, "missing folder resolved during storage import");
    check(backup.notes.get(2).plan.isEmpty(), "broken plan discarded");
    check(backup.groups.size() == 1 && backup.groups.get(0).expanded, "one valid expanded group");
    check(backup.groups.get(0).members.equals(Arrays.asList(42L)), "invalid and duplicate members removed");
    check(backup.reminders() == 1 && backup.plans() == 1, "preview counters");

    NotesImport.Backup old =
        NotesImport.parse(
            new JSONObject().put("notes", new JSONArray().put(note(1, "Старое", "Версия 1"))).toString());
    check(old.notes.get(0).created == 0 && old.notes.get(0).lead == -1, "v1 defaults");
    check(old.folders.isEmpty() && old.groups.isEmpty(), "v1 optional sections");

    NotesImport.Entry fingerprint = NotesImport.parse(full.toString()).notes.get(0);
    String before = NotesImport.fingerprint(fingerprint, "Дом");
    fingerprint.metadata.put("ai", new JSONObject().put("summary", "Другой ответ ИИ"));
    check(NotesImport.fingerprint(fingerprint, "дом").equals(before), "fingerprint ignores AI and folder case");
    fingerprint.body += "!";
    check(!NotesImport.fingerprint(fingerprint, "Дом").equals(before), "fingerprint detects user edit");

    rejected("не json", "broken JSON");
    rejected("{\"version\":4,\"notes\":[]}", "empty backup");
    rejected(new JSONObject().put("version", 5).put("notes", new JSONArray().put(note(1, "А", "Б"))).toString(), "future version");
    rejected(new JSONObject().put("notes", new JSONArray().put(note(1, "А", "Б")).put(note(1, "В", "Г"))).toString(), "duplicate note id");
    rejected(new JSONObject().put("notes", new JSONArray().put(note(0, "А", "Б"))).toString(), "zero note id");
    rejected(new JSONObject().put("notes", new JSONArray().put(note(1, "А", "Б").put("created", "сейчас"))).toString(), "non-numeric field");
    rejected(new JSONObject().put("notes", new JSONArray().put(note(1, "А", "Б"))).put("folders", new JSONArray().put(new JSONObject().put("id", 1).put("name", "А")).put(new JSONObject().put("id", 1).put("name", "Б"))).toString(), "duplicate folder id");

    JSONArray tooManyNotes = new JSONArray();
    for (int i = 1; i <= NotesImport.MAX_NOTES + 1; i++) tooManyNotes.put(note(i, "А", "Б"));
    rejected(new JSONObject().put("notes", tooManyNotes).toString(), "note limit");
    JSONArray tooManyFolders = new JSONArray();
    for (int i = 1; i <= NotesImport.MAX_FOLDERS + 1; i++)
      tooManyFolders.put(new JSONObject().put("id", i).put("name", "Папка " + i));
    rejected(new JSONObject().put("notes", new JSONArray().put(note(1, "А", "Б"))).put("folders", tooManyFolders).toString(), "folder limit");
    check(NotesImport.MAX_FILE_BYTES == 32 * 1024 * 1024, "file size limit contract");

    System.out.println("NotesImportTest: " + count + " checks passed");
  }
}
