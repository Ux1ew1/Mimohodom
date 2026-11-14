package app.fastnotes;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;

/** Папки заметок. Заметка лежит не больше чем в одной папке; без папки — только в «Все заметки». */
public final class FolderStore {
  public static final class Folder {
    public final long id;
    public final String name;
    public final boolean auto;
    public final int notes;

    Folder(long id, String name, boolean auto, int notes) {
      this.id = id;
      this.name = name;
      this.auto = auto;
      this.notes = notes;
    }
  }

  private final SQLiteDatabase db;

  public FolderStore(Store store) {
    db = store.getWritableDatabase();
  }

  /** Все папки по алфавиту без учёта регистра, с числом заметок в каждой. */
  public List<Folder> list() {
    List<Folder> out = new ArrayList<>();
    try (Cursor c =
        db.rawQuery(
            "SELECT f.id,f.name,f.auto,(SELECT count(*) FROM notes n WHERE n.folder=f.id) FROM"
                + " folders f ORDER BY f.key",
            null)) {
      while (c.moveToNext())
        out.add(new Folder(c.getLong(0), c.getString(1), c.getInt(2) == 1, c.getInt(3)));
    }
    return out;
  }

  public Folder folder(long id) {
    try (Cursor c =
        db.rawQuery(
            "SELECT f.id,f.name,f.auto,(SELECT count(*) FROM notes n WHERE n.folder=f.id) FROM"
                + " folders f WHERE f.id=?",
            new String[] {"" + id})) {
      return c.moveToFirst()
          ? new Folder(c.getLong(0), c.getString(1), c.getInt(2) == 1, c.getInt(3))
          : null;
    }
  }

  public boolean exists(long id) {
    return id != FolderPolicy.ALL && folder(id) != null;
  }

  /** Создаёт папку и возвращает её номер; если такая уже есть, возвращает существующую. 0 — имя недопустимо. */
  public long create(String name, boolean auto) {
    if (!FolderPolicy.valid(name)) return 0;
    String key = FolderPolicy.key(name);
    try (Cursor c =
        db.rawQuery("SELECT id FROM folders WHERE key=?", new String[] {key})) {
      if (c.moveToFirst()) return c.getLong(0);
    }
    ContentValues v = new ContentValues();
    v.put("name", FolderPolicy.clean(name));
    v.put("key", key);
    v.put("created", System.currentTimeMillis());
    v.put("auto", auto ? 1 : 0);
    return db.insertOrThrow("folders", null, v);
  }

  /** Переименовывает папку. false — имя недопустимо, занято другой папкой или папки нет. */
  public boolean rename(long id, String name) {
    if (!FolderPolicy.valid(name)) return false;
    String key = FolderPolicy.key(name);
    try (Cursor c =
        db.rawQuery("SELECT id FROM folders WHERE key=? AND id<>?", new String[] {key, "" + id})) {
      if (c.moveToFirst()) return false;
    }
    ContentValues v = new ContentValues();
    v.put("name", FolderPolicy.clean(name));
    v.put("key", key);
    return db.update("folders", v, "id=?", new String[] {"" + id}) == 1;
  }

  /** Удаляет папку; заметки из неё возвращаются в «Все заметки» и не удаляются. */
  public void delete(long id) {
    db.beginTransaction();
    try {
      db.execSQL("UPDATE notes SET folder=0 WHERE folder=?", new Object[] {id});
      db.delete("folders", "id=?", new String[] {"" + id});
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  /**
   * Кладёт заметку в папку (0 — вернуть в «Все заметки»). Итог стопки переносит и все её исходные
   * заметки. Время изменения заметки не меняется.
   */
  public void move(long noteId, long folder) {
    if (folder != FolderPolicy.ALL && !exists(folder)) return;
    db.execSQL(
        "UPDATE notes SET folder=? WHERE id=? OR id IN(SELECT note FROM note_members WHERE top=?)",
        new Object[] {folder, noteId, noteId});
  }
}
