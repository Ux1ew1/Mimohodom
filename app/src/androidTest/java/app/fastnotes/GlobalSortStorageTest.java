package app.fastnotes;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import java.util.Arrays;

/** Global sort runs against the real schema in its own disposable database. */
final class GlobalSortStorageTest {
  static void run(Context context) throws Exception {
    String database = "global-sort-test-" + System.nanoTime() + ".db";
    try (Store store = new Store(context, database)) {
      FolderStore folders = new FolderStore(store);
      ManagerStore manager = new ManagerStore(store);
      long old = folders.create("Старая папка", false);
      long different = folders.create("Другая папка", false);
      long archiveNameCollision = folders.create("Архив сводок колод", false);
      long summary = store.save(0, "Старая сводка", "Руками правленная сводка", 0);
      long trip = store.save(0, "Отпуск", "Поездка к морю, отель и аэропорт", 0);
      long trip2 = store.save(0, "Поездка к морю", "Отпуск, отель, билеты и чемодан", 0);
      manager.attachDeck(summary, Arrays.asList(trip, trip2));
      folders.move(summary, old);
      long repair = store.save(0, "Ремонт кухни", "Плитка, сантехника, обои и краска", 0);
      long repair2 = store.save(0, "Ремонт и плитка", "Сантехника, обои и краска для кухни", 0);
      long isolated = store.save(0, "Зюзя", "Бебека", 0);
      folders.move(repair, old);
      folders.move(repair2, different);
      folders.move(isolated, old);
      long privateNote = privateNote(store, folders);

      GlobalSort.Result notes = GlobalSort.run(store, GlobalSort.Scope.NOTES, GlobalSort.Summaries.ARCHIVE);
      require(notes.decks == 0 && notes.moved == 0, "Notes-only keeps matching notes in separate folders");
      require(manager.top(trip) == summary && store.note(isolated).folder == old, "Notes-only preserves decks and folders");

      GlobalSort.Result withFolders = GlobalSort.run(
          store, GlobalSort.Scope.NOTES_AND_FOLDERS, GlobalSort.Summaries.ARCHIVE);
      long repairDeck = manager.top(repair);
      long repairFolder = store.note(repair).folder;
      require(withFolders.decks == 1 && repairDeck != 0 && manager.top(repair2) == repairDeck,
          "Folder scope groups related notes across previous folders");
      require("Ремонт".equals(folders.folder(repairFolder).name)
          && store.note(repairDeck).folder == repairFolder && store.note(repair2).folder == repairFolder,
          "Moves the deck and both sources to their topic folder");
      require(store.note(isolated).folder == FolderPolicy.ALL && store.note(trip).folder == old
          && manager.top(trip) == summary, "Returns unmatched notes to All and preserves previous decks");

      store.getWritableDatabase().execSQL("CREATE TEMP TRIGGER stop_global_sort BEFORE INSERT ON note_groups"
          + " BEGIN SELECT RAISE(ABORT,'Test rollback'); END");
      boolean aborted = false;
      try {
        GlobalSort.run(store, GlobalSort.Scope.ALL, GlobalSort.Summaries.ARCHIVE);
      } catch (SQLiteException expected) {
        aborted = true;
      }
      require(aborted && manager.top(trip) == summary && manager.top(repair) == repairDeck
          && store.note(summary).folder == old, "Failure rolls back both dissolution and archiving");
      store.getWritableDatabase().execSQL("DROP TRIGGER stop_global_sort");

      GlobalSort.Result archived = GlobalSort.run(store, GlobalSort.Scope.ALL, GlobalSort.Summaries.ARCHIVE);
      long archive = store.note(summary).folder;
      long newDeck = manager.top(trip);
      require(archived.archived == 2 && archive != old && archive != archiveNameCollision
          && store.note(repairDeck).folder == archive && manager.top(summary) == 0,
          "Archives only old deck heads in a dedicated folder");
      require(newDeck != 0 && newDeck != summary && manager.top(trip2) == newDeck && store.note(trip).trashed == 0,
          "Disbands old decks and builds a new semantic deck from live originals");
      require("Руками правленная сводка".equals(store.note(summary).body), "Archive retains summary text");
      require(folders.rename(archive, "Мой архив"), "Archive can be renamed");

      GlobalSort.Result trashed = GlobalSort.run(store, GlobalSort.Scope.ALL, GlobalSort.Summaries.TRASH);
      require(trashed.trashed.contains(newDeck) && store.note(newDeck).trashed > 0
          && store.note(trip).trashed == 0, "Trash mode removes only summary heads");
      require(store.note(summary).folder == archive && store.note(summary).trashed == 0 && manager.top(summary) == 0,
          "A renamed archive stays outside subsequent sorts");
      require(store.restore(newDeck).size() == 1 && manager.top(newDeck) == 0, "A trashed summary is recoverable");
      if (privateNote != 0)
        require(manager.top(privateNote) == 0
            && "Личная папка".equals(folders.folder(store.note(privateNote).folder).name),
            "Incognito notes stay in their folder across all scopes");

      long first = store.save(0, "Фиалки на даче", "Полив, удобрение и пересадка фиалок", 0);
      long second = store.save(0, "Полив фиалок на даче", "Удобрение и пересадка фиалок", 0);
      folders.move(first, old);
      folders.move(second, old);
      GlobalSort.run(store, GlobalSort.Scope.NOTES, GlobalSort.Summaries.ARCHIVE);
      long top = manager.top(first);
      require(top != 0 && manager.top(second) == top && store.note(top).folder == old,
          "Notes-only builds a new deck within an existing folder");
    } finally {
      context.deleteDatabase(database);
    }
  }

  private static long privateNote(Store store, FolderStore folders) {
    try (Cursor cursor = store.getReadableDatabase().rawQuery("SELECT * FROM folders LIMIT 0", null)) {
      if (cursor.getColumnIndex("incognito") < 0) return 0;
    }
    long folder = folders.create("Личная папка", false);
    store.getWritableDatabase().execSQL("UPDATE folders SET incognito=1 WHERE id=?", new Object[] {folder});
    long note = store.save(0, "Отпуск", "Поездка к морю, отель и аэропорт", 0);
    folders.move(note, folder);
    return note;
  }

  private static void require(boolean condition, String description) {
    if (!condition) throw new AssertionError(description);
  }
}
