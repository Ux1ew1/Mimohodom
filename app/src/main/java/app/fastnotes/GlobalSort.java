package app.fastnotes;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;
import org.json.JSONException;

/** Applies one complete local reorganisation in a single SQLite transaction. */
final class GlobalSort {
  private static final String ARCHIVE_NAME = "Архив сводок колод";
  // Sync keeps a folder UID through renames. Separate UIDs leave a user's same-named folder alone.
  private static final String ARCHIVE_UID_PREFIX = FolderPolicy.uid("global-sort-archive:").substring(0, 24);

  enum Scope { NOTES, NOTES_AND_FOLDERS, ALL }
  enum Summaries { ARCHIVE, TRASH }

  static final class Result {
    int decks, dissolved, moved, archived;
    final List<Long> trashed = new ArrayList<>();
  }

  private static final class Folders {
    final Map<String, Long> destinations = new HashMap<>();
    final List<String> names = new ArrayList<>();
    final Set<String> usedNames = new HashSet<>();
    final Set<String> usedUids = new HashSet<>();
    final Set<String> excludedNames = new HashSet<>();
    final Set<Long> excluded = new HashSet<>();
    long archive;

    Folders(SQLiteDatabase db) {
      try (Cursor c = db.rawQuery("SELECT * FROM folders ORDER BY key", null)) {
        int idColumn = c.getColumnIndexOrThrow("id");
        int nameColumn = c.getColumnIndexOrThrow("name");
        int keyColumn = c.getColumnIndexOrThrow("key");
        int uidColumn = c.getColumnIndexOrThrow("uid");
        int privateColumn = c.getColumnIndex("incognito");
        while (c.moveToNext()) {
          long id = c.getLong(idColumn);
          String name = c.getString(nameColumn), key = c.getString(keyColumn), uid = c.getString(uidColumn);
          boolean privateFolder = privateColumn >= 0 && c.getInt(privateColumn) != 0;
          boolean archiveFolder = uid.startsWith(ARCHIVE_UID_PREFIX);
          usedNames.add(key);
          usedUids.add(uid);
          if (privateFolder || archiveFolder) {
            excludedNames.add(key);
            excluded.add(id);
            if (archiveFolder && !privateFolder && archive == 0) archive = id;
          } else {
            names.add(name);
            destinations.put(key, id);
          }
        }
      }
      excludedNames.add(FolderPolicy.key(ARCHIVE_NAME));
    }

    long archive(FolderStore folders) {
      if (archive != 0) return archive;
      for (int i = 1; ; i++) {
        String name = i == 1 ? ARCHIVE_NAME : ARCHIVE_NAME + " " + i;
        String uid = ARCHIVE_UID_PREFIX + String.format(Locale.ROOT, "%08x", i);
        if (usedNames.contains(FolderPolicy.key(name)) || usedUids.contains(uid)) continue;
        archive = folders.create(name, true, uid);
        return archive;
      }
    }
  }

  private GlobalSort() {}

  static Result run(Store store, Scope scope, Summaries summaries) throws JSONException {
    SQLiteDatabase db = store.getWritableDatabase();
    Result out = new Result();
    db.beginTransactionNonExclusive();
    try {
      FolderStore folders = new FolderStore(store);
      Folders context = new Folders(db);
      ManagerStore manager = new ManagerStore(store);
      Map<Long, Long> tops = manager.topMap();
      List<Note> notes = store.notes();
      Set<Long> protectedDecks = new HashSet<>();
      for (Note n : notes) {
        Long top = tops.get(n.id);
        if (top != null && context.excluded.contains(n.folder)) protectedDecks.add(top);
      }
      List<Note> eligible = new ArrayList<>();
      for (Note n : notes) {
        if (context.excluded.contains(n.folder)) continue;
        Long top = tops.get(n.id);
        if (top != null && (scope != Scope.ALL || protectedDecks.contains(top))) continue;
        if (top != null && top == n.id) {
          if (summaries == Summaries.TRASH) out.trashed.addAll(store.trash(n.id, false));
          else {
            manager.dissolve(n.id);
            folders.move(n.id, context.archive(folders));
            out.archived++;
          }
          out.dissolved++;
        } else eligible.add(n);
      }

      boolean sortFolders = scope != Scope.NOTES;
      List<GlobalSortPlan.Placement> plan =
          GlobalSortPlan.build(eligible, context.names, context.excludedNames, sortFolders);
      for (GlobalSortPlan.Placement placement : plan) {
        List<Note> members = placement.notes;
        Note first = members.get(0);
        // Choose folders from the content of the collection without using previous assignments.
        long folder = sortFolders ? destination(folders, context, placement.folder) : first.folder;
        long top = first.id;
        if (members.size() > 1) {
          top = createDeck(store, manager, db, members);
          out.decks++;
        }
        for (Note n : members) if (n.folder != folder) out.moved++;
        if (top != first.id || first.folder != folder) folders.move(top, folder);
      }
      db.setTransactionSuccessful();
      return out;
    } finally {
      db.endTransaction();
    }
  }

  private static long destination(FolderStore folders, Folders context, String name) {
    if (name.isEmpty()) return FolderPolicy.ALL;
    return context.destinations.computeIfAbsent(FolderPolicy.key(name), k -> folders.create(name, true));
  }

  private static long createDeck(Store store, ManagerStore manager, SQLiteDatabase db, List<Note> notes)
      throws JSONException {
    List<Long> members = new ArrayList<>();
    StringBuilder body = new StringBuilder();
    for (Note note : notes) {
      members.add(note.id);
      if (body.length() > 0) body.append('\n');
      body.append("- ").append(note.title.replaceAll("\\s+", " ").trim());
      String excerpt = Retrieval.excerpt(note.body.replaceAll("\\s+", " "), "", 140);
      if (!excerpt.isEmpty() && !excerpt.equals(note.title)) body.append(" — ").append(excerpt);
    }
    String title = notes.get(0).title;
    long top = store.save(0, title, body.toString(), 0);
    manager.attachDeck(top, members);
    AgentLog.record(db, AgentJournal.GROUP,
        AgentJournal.groupData(top, title, members, Collections.emptyMap(), 0));
    return top;
  }
}
