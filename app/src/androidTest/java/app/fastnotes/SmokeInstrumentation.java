package app.fastnotes;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.*;
import android.os.Bundle;
import org.json.JSONArray;

/** Run on a disposable emulator: creates and removes its own records only. */
public final class SmokeInstrumentation extends Instrumentation {
  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    long id = 0, chat = 0;
    try (Store db = new Store(getTargetContext())) {
      ManagerStorageTest.run(getTargetContext());
      id = db.save(0, "", "Проверочная заметка\nТекст", 0);
      Note note = db.note(id);
      require(note.title.equals("Проверочная заметка"), "Auto title");
      require(note.autoTitle, "Title provenance");
      require(
          new org.json.JSONObject(note.metadata).getLong("created_at") == note.created,
          "Metadata created with note");
      long beforeMetadata = note.updated;
      require(
          db.applyMetadata(
              id,
              note.updated,
              new org.json.JSONObject().put("aliases", new JSONArray().put("нейротестпоиск"))),
          "AI search metadata accepted");
      long metadataId = id;
      require(
          db.searchNotes("нейротестпоиск").stream().anyMatch(found -> found.id == metadataId),
          "FTS searches hidden AI alias");
      require(db.note(id).updated == beforeMetadata, "AI metadata preserves edit timestamp");
      db.image(id, "fixture.jpg");
      require(db.note(id).image.equals("fixture.jpg"), "Attachment persists");
      db.image(id, "");
      require(db.note(id).image.isEmpty(), "Attachment removal persists");
      Vision.Result vision = new Vision.Result();
      vision.title = "По изображению";
      vision.summary = "Описание";
      vision.reminder = System.currentTimeMillis() + 3600000;
      vision.reason = "Дата на изображении";
      long generated = db.saveVision(vision, "fixture.jpg");
      require(
          db.note(generated).image.equals("fixture.jpg")
              && db.note(generated).suggestion == vision.reminder
              && db.note(generated).reminder == 0,
          "Generated note retains image and unconfirmed proposal");
      db.delete(generated);
      java.io.File imageFixture =
          java.io.File.createTempFile("vision-fixture", ".png", getTargetContext().getCacheDir());
      try {
        android.graphics.Bitmap bitmap =
            android.graphics.Bitmap.createBitmap(
                2000, 1000, android.graphics.Bitmap.Config.ARGB_8888);
        try (java.io.OutputStream out = new java.io.FileOutputStream(imageFixture)) {
          bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally {
          bitmap.recycle();
        }
        String imported =
            ImageFiles.importImage(getTargetContext(), android.net.Uri.fromFile(imageFixture));
        java.io.File compressed = ImageFiles.file(getTargetContext(), imported);
        try {
          android.graphics.BitmapFactory.Options bounds =
              new android.graphics.BitmapFactory.Options();
          bounds.inJustDecodeBounds = true;
          android.graphics.BitmapFactory.decodeFile(compressed.getPath(), bounds);
          require(
              bounds.outWidth <= 1600 && bounds.outHeight <= 1600 && compressed.length() <= 2000000,
              "Image preparation bounded");
        } finally {
          compressed.delete();
        }
      } finally {
        imageFixture.delete();
      }
      require(db.claimAnalysis(note), "Claim once");
      require(!db.claimAnalysis(note), "Do not duplicate paid request");
      Analysis.Result proposal = new Analysis.Result();
      proposal.title = "Заголовок ИИ";
      proposal.reminder = System.currentTimeMillis() + 7200000;
      proposal.reason = "Дата из текста";
      require(db.applyAnalysis(note, proposal), "Apply proposal");
      require(
          db.note(id).reminder == 0 && db.note(id).suggestion == proposal.reminder,
          "Proposal does not create alarm");
      require(
          db.note(id).created == note.created && db.note(id).updated == note.updated,
          "AI preserves user timestamps");
      db.dismissSuggestion(id);
      require(db.note(id).suggestion == 0, "Dismiss proposal");
      long created = note.created;
      db.save(id, "Обновлено", "Новый текст", System.currentTimeMillis() + 3600000);
      require(!db.applyAnalysis(note, proposal), "Stale result rejected");
      require(
          !db.applyMetadata(
              id,
              note.updated,
              new org.json.JSONObject().put("aliases", new JSONArray().put("устаревший"))),
          "Stale metadata rejected");
      require(
          db.searchNotes("нейротестпоиск").isEmpty(), "Editing clears old AI aliases from index");
      note = db.note(id);
      require(
          note.created == created && note.body.equals("Новый текст"), "Edit preserves creation");
      Reminders.schedule(getTargetContext(), note);
      Reminders.cancel(getTargetContext(), id);
      chat = db.newChat("Smoke test");
      db.message(chat, "user", "Вопрос", new JSONArray());
      db.message(chat, "assistant", "Ответ", new JSONArray());
      require(db.messages(chat).length() == 2, "History persisted");
      org.json.JSONObject proposed =
          new org.json.JSONObject().put("title", "План").put("body", "Содержание из переписки");
      db.message(chat, "assistant", "Можно сохранить", new JSONArray(), proposed);
      org.json.JSONObject proposalMessage = db.messages(chat).getJSONObject(2);
      long proposalId = proposalMessage.getLong("id");
      require(!proposalMessage.getString("draft").isEmpty(), "Draft survives reloading history");
      long accepted = db.acceptDraft(proposalId, "Исправленный план", "Исправленный текст");
      require(db.note(accepted).title.equals("Исправленный план"), "Reviewed draft saved");
      try {
        db.acceptDraft(proposalId, "Дубль", "Дубль");
        throw new AssertionError("Duplicate draft accepted");
      } catch (IllegalStateException expected) {
      }
      db.delete(accepted);
      db.deleteChat(chat);
      require(db.messages(chat).length() == 0, "Cascade deletion");
      chat = 0;
      db.cache("smoke-test", "answer");
      require("answer".equals(db.cached("smoke-test")), "Cache roundtrip");
      db.getWritableDatabase().delete("cache", "cache_key=?", new String[] {"smoke-test"});
      db.delete(id);
      require(db.note(id) == null, "Note deletion");
      id = 0;
      Intent launch =
          new Intent(getTargetContext(), MainActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      Activity activity = startActivitySync(launch);
      waitForIdleSync();
      require(activity != null, "Launch");
      runOnMainSync(activity::finish);
      String migrationName = "migration-" + System.nanoTime() + ".db";
      try {
        try (android.database.sqlite.SQLiteDatabase legacy =
            getTargetContext().openOrCreateDatabase(migrationName, 0, null)) {
          legacy.execSQL(
              "CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,body"
                  + " TEXT NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,reminder"
                  + " INTEGER NOT NULL DEFAULT 0)");
          legacy.execSQL("INSERT INTO notes VALUES(1,'Мой заголовок','Текст',1000,2000,0)");
          legacy.setVersion(1);
          legacy.execSQL(
              "CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,chat INTEGER NOT"
                  + " NULL,role TEXT NOT NULL,body TEXT NOT NULL,sources TEXT NOT NULL DEFAULT"
                  + " '[]',created INTEGER NOT NULL)");
        }
        try (Store migrated = new Store(getTargetContext(), migrationName)) {
          Note retained = migrated.note(1);
          require(
              retained.title.equals("Мой заголовок")
                  && retained.created == 1000
                  && !retained.autoTitle,
              "v1 migration preserves data and titles");
          require(
              migrated.pendingAnalysis().size() == 1,
              "Migrated notes eligible for reminder suggestions");
          require(
              retained.folder == FolderPolicy.ALL,
              "Migrated notes start in All notes");
          FolderStore folders = new FolderStore(migrated);
          long folder = folders.create("Проверка", false);
          require(folder > 0 && folders.create("  ПРОВЕРКА ", false) == folder, "Folder names are unique");
          folders.move(1, folder);
          require(migrated.note(1).folder == folder, "Note moves into a folder");
          folders.delete(folder);
          require(
              migrated.note(1) != null && migrated.note(1).folder == FolderPolicy.ALL,
              "Deleting a folder keeps its notes");
          long first =
              migrated.save(
                  0, "Видео А", "Сводка А\n\nВидео: https://www.youtube.com/watch?v=aaaaaaaaaaa", 0);
          long second =
              migrated.save(
                  0, "Видео Б", "Сводка Б\n\nВидео: https://www.youtube.com/watch?v=bbbbbbbbbbb", 0);
          Note a = migrated.note(first), b = migrated.note(second);
          require(migrated.videoNotes().size() == 2, "Video notes are found by their source line");
          require(
              !migrated.mergeVideo(second, b.updated + 1, first, a.updated, "Т", "тело")
                  && migrated.note(second) != null
                  && migrated.note(first).body.startsWith("Сводка А"),
              "Merge with a stale revision changes nothing");
          require(
              migrated.mergeVideo(
                  second, b.updated, first, a.updated, "Общая тема", "Общее по теме\nтекст"),
              "Video merge succeeds");
          require(
              migrated.note(second) == null
                  && migrated.note(first).title.equals("Общая тема")
                  && migrated.note(first).body.startsWith("Общее по теме"),
              "Merged note replaces the source note");
        }
      } finally {
        getTargetContext().deleteDatabase(migrationName);
      }
      result.putString(
          "stream",
          "PASS: storage, editing, deletion, chat cascade, cache, reminder scheduling, launch\n");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable e) {
      result.putString("stream", "FAIL: " + e + "\n");
      finish(Activity.RESULT_CANCELED, result);
    } finally {
      try (Store db = new Store(getTargetContext())) {
        if (id != 0) {
          Reminders.cancel(getTargetContext(), id);
          db.delete(id);
        }
        if (chat != 0) db.deleteChat(chat);
      }
    }
  }

  private static void require(boolean ok, String name) {
    if (!ok) throw new AssertionError(name);
  }
}
