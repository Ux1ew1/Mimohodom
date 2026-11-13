package app.fastnotes;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;
import org.json.*;

public final class Store extends SQLiteOpenHelper implements Polza.Memory {
  private final Context appContext;
  private final boolean local;

  public Store(Context context) {
    this(context, "fastnotes.db", LocalLlm.enabled(context));
  }

  public Store(Context context, boolean local) {
    this(context, "fastnotes.db", local);
  }

  Store(Context context, String name) {
    this(context, name, LocalLlm.enabled(context));
  }

  private Store(Context context, String name, boolean local) {
    super(context, name, null, 7);
    appContext = context.getApplicationContext();
    this.local = local;
    setWriteAheadLoggingEnabled(true);
  }

  @Override
  public void onConfigure(SQLiteDatabase db) {
    db.setForeignKeyConstraintsEnabled(true);
  }

  @Override
  public boolean localMode() {
    return local;
  }

  @Override
  public boolean aicoreMode() {
    return local && LocalLlm.aicore(appContext);
  }

  @Override
  public JSONObject complete(String key, JSONObject payload) throws Exception {
    return localMode()
        ? LocalLlm.complete(appContext, payload)
        : Polza.request("chat/completions", key, payload);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,body TEXT NOT"
            + " NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,reminder INTEGER NOT NULL"
            + " DEFAULT 0)");
    db.execSQL("CREATE INDEX notes_updated ON notes(updated DESC)");
    db.execSQL(
        "CREATE TABLE chats(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,created"
            + " INTEGER NOT NULL)");
    db.execSQL(
        "CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,chat INTEGER NOT NULL"
            + " REFERENCES chats(id) ON DELETE CASCADE,role TEXT NOT NULL,body TEXT NOT"
            + " NULL,sources TEXT NOT NULL DEFAULT '[]',created INTEGER NOT NULL)");
    db.execSQL("CREATE INDEX messages_chat ON messages(chat,id)");
    db.execSQL(
        "CREATE TABLE cache(cache_key TEXT PRIMARY KEY,body TEXT NOT NULL,created INTEGER NOT"
            + " NULL)");
    addAssistantColumns(db);
    db.execSQL("ALTER TABLE notes ADD COLUMN image TEXT NOT NULL DEFAULT ''");
    db.execSQL("ALTER TABLE messages ADD COLUMN draft TEXT NOT NULL DEFAULT ''");
    addMetadata(db);
    ManagerStore.create(db);
    addReminderOptions(db);
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
    if (oldV < 2) addAssistantColumns(db);
    if (oldV < 3) db.execSQL("ALTER TABLE notes ADD COLUMN image TEXT NOT NULL DEFAULT ''");
    if (oldV < 4) {
      db.execSQL("ALTER TABLE messages ADD COLUMN draft TEXT NOT NULL DEFAULT ''");
    }
    if (oldV < 5) addMetadata(db);
    if (oldV < 6) ManagerStore.create(db);
    if (oldV < 7) addReminderOptions(db);
  }

  /** Повтор и свой интервал напоминания. Безопасно вызывается повторно, если столбцы уже есть. */
  private void addReminderOptions(SQLiteDatabase db) {
    if (!hasColumn(db, "repeat_mode"))
      db.execSQL("ALTER TABLE notes ADD COLUMN repeat_mode INTEGER NOT NULL DEFAULT 0");
    if (!hasColumn(db, "lead_minutes"))
      db.execSQL("ALTER TABLE notes ADD COLUMN lead_minutes INTEGER NOT NULL DEFAULT -1");
  }

  private boolean hasColumn(SQLiteDatabase db, String column) {
    try (Cursor c = db.rawQuery("PRAGMA table_info(notes)", null)) {
      while (c.moveToNext()) if (column.equals(c.getString(1))) return true;
    }
    return false;
  }

  private void addMetadata(SQLiteDatabase db) {
    db.execSQL("ALTER TABLE notes ADD COLUMN metadata TEXT NOT NULL DEFAULT '{}'");
    db.execSQL("ALTER TABLE notes ADD COLUMN search_text TEXT NOT NULL DEFAULT ''");
    try (Cursor c = db.rawQuery("SELECT id,title,body,created,updated FROM notes", null)) {
      while (c.moveToNext()) {
        JSONObject metadata =
            Metadata.base(c.getString(1), c.getString(2), c.getLong(3), c.getLong(4));
        ContentValues v = new ContentValues();
        v.put("metadata", metadata.toString());
        v.put("search_text", Metadata.searchText(metadata));
        db.update("notes", v, "id=?", new String[] {c.getString(0)});
      }
    }
    db.execSQL("CREATE VIRTUAL TABLE notes_fts USING fts4(title,body,metadata,tokenize=unicode61)");
    db.execSQL(
        "INSERT INTO notes_fts(rowid,title,body,metadata) SELECT id,title,body,search_text FROM"
            + " notes");
    db.execSQL("CREATE TABLE search_state(id INTEGER PRIMARY KEY,revision INTEGER NOT NULL)");
    db.execSQL("INSERT INTO search_state VALUES(1,1)");
    db.execSQL("UPDATE notes SET ai_checked=0 WHERE ai_failed=0");
    db.execSQL(
        "CREATE TRIGGER notes_search_insert AFTER INSERT ON notes BEGIN INSERT INTO"
            + " notes_fts(rowid,title,body,metadata)"
            + " VALUES(new.id,new.title,new.body,new.search_text);UPDATE search_state SET"
            + " revision=revision+1 WHERE id=1;END");
    db.execSQL(
        "CREATE TRIGGER notes_search_update AFTER UPDATE ON notes BEGIN DELETE FROM notes_fts WHERE"
            + " rowid=old.id;INSERT INTO notes_fts(rowid,title,body,metadata)"
            + " VALUES(new.id,new.title,new.body,new.search_text);UPDATE search_state SET"
            + " revision=revision+1 WHERE id=1;END");
    db.execSQL(
        "CREATE TRIGGER notes_search_delete AFTER DELETE ON notes BEGIN DELETE FROM notes_fts WHERE"
            + " rowid=old.id;UPDATE search_state SET revision=revision+1 WHERE id=1;END");
  }

  private void addAssistantColumns(SQLiteDatabase db) {
    db.execSQL("ALTER TABLE notes ADD COLUMN title_auto INTEGER NOT NULL DEFAULT 0");
    db.execSQL("ALTER TABLE notes ADD COLUMN ai_checked INTEGER NOT NULL DEFAULT 0");
    db.execSQL("ALTER TABLE notes ADD COLUMN suggestion INTEGER NOT NULL DEFAULT 0");
    db.execSQL("ALTER TABLE notes ADD COLUMN suggestion_reason TEXT NOT NULL DEFAULT ''");
    db.execSQL("ALTER TABLE notes ADD COLUMN ai_failed INTEGER NOT NULL DEFAULT 0");
    db.execSQL("UPDATE notes SET title_auto=1 WHERE trim(title)=''");
  }

  private Note read(Cursor c) {
    Note n =
        new Note(
            c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getLong(5));
    n.autoTitle = c.getInt(6) == 1;
    n.suggestion = c.getLong(8);
    n.suggestionReason = c.getString(9);
    n.image = c.getString(11);
    n.metadata = c.getString(12);
    n.searchText = c.getString(13);
    n.repeat = c.getInt(14);
    n.lead = c.getInt(15);
    return n;
  }

  public List<Note> notes() {
    List<Note> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT * FROM notes ORDER BY updated DESC,id DESC", null)) {
      while (c.moveToNext()) out.add(read(c));
    }
    return out;
  }

  @Override
  public int totalNotes() {
    try (Cursor c = getReadableDatabase().rawQuery("SELECT count(*) FROM notes", null)) {
      c.moveToFirst();
      return c.getInt(0);
    }
  }

  @Override
  public String revision() {
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT revision FROM search_state WHERE id=1", null)) {
      c.moveToFirst();
      return c.getString(0);
    }
  }

  public List<Note> searchNotes(String query) {
    if (query.trim().isEmpty()) return notes();
    String match = SearchQuery.build(query);
    List<Note> result = new ArrayList<>();
    if (match.length() == 0) return result;
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT n.* FROM notes_fts JOIN notes n ON n.id=notes_fts.rowid WHERE notes_fts"
                    + " MATCH ? ORDER BY n.updated DESC LIMIT 200",
                new String[] {match})) {
      while (c.moveToNext()) result.add(read(c));
    }
    return result;
  }

  @Override
  public List<Note> candidates(String query) {
    List<Note> result = searchNotes(query);
    Set<Long> ids = new HashSet<>();
    for (Note n : result) ids.add(n.id);
    String normalized = Retrieval.normalize(query);
    boolean upcoming =
        normalized.contains("ближайш")
            || normalized.contains("напомин")
            || normalized.contains("сегодня")
            || normalized.contains("завтра");
    if (upcoming)
      try (Cursor c =
          getReadableDatabase()
              .rawQuery(
                  "SELECT * FROM notes WHERE reminder>=? ORDER BY reminder LIMIT 16",
                  new String[] {"" + System.currentTimeMillis()})) {
        while (c.moveToNext()) {
          Note n = read(c);
          if (ids.add(n.id)) result.add(n);
        }
      }
    if (result.isEmpty())
      try (Cursor c =
          getReadableDatabase()
              .rawQuery("SELECT * FROM notes ORDER BY updated DESC LIMIT 24", null)) {
        while (c.moveToNext()) result.add(read(c));
      }
    return result;
  }

  public Note note(long id) {
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT * FROM notes WHERE id=?", new String[] {"" + id})) {
      return c.moveToFirst() ? read(c) : null;
    }
  }

  /** Сохраняет текст и время напоминания; повтор и свой интервал уже существующей заметки не трогает. */
  public long save(long id, String title, String body, long reminder) {
    Note existing = id == 0 ? null : note(id);
    return save(
        id,
        title,
        body,
        reminder,
        existing == null ? 0 : existing.repeat,
        existing == null ? -1 : existing.lead);
  }

  /** Передвигает время повторяющегося напоминания, если оно ещё не менялось. Текст не трогает. */
  public boolean advanceReminder(long id, long expected, long next) {
    ContentValues v = new ContentValues();
    v.put("reminder", next);
    return getWritableDatabase()
            .update("notes", v, "id=? AND reminder=?", new String[] {"" + id, "" + expected})
        == 1;
  }

  /** Количество заметок с непринятым предложением напоминания, время которого ещё впереди. */
  public int pendingSuggestions() {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT count(*) FROM notes WHERE suggestion>?",
                new String[] {"" + System.currentTimeMillis()})) {
      c.moveToFirst();
      return c.getInt(0);
    }
  }

  public long save(
      long id, String title, String body, long reminder, int repeat, int lead) {
    ContentValues v = new ContentValues();
    long now = System.currentTimeMillis();
    Note old = id == 0 ? null : note(id);
    now = old == null ? now : Math.max(now, old.updated + 1);
    v.put(
        "title_auto",
        title.trim().isEmpty() || (old != null && old.autoTitle && old.title.equals(title.trim()))
            ? 1
            : 0);
    v.put("suggestion", 0);
    v.put("suggestion_reason", "");
    v.put("ai_failed", 0);
    v.put("title", Note.autoTitle(title, body));
    v.put("body", body.trim());
    v.put("updated", now);
    v.put("reminder", reminder);
    v.put("repeat_mode", reminder == 0 ? 0 : repeat);
    v.put("lead_minutes", reminder == 0 ? -1 : lead);
    JSONObject metadata =
        Metadata.base(
            Note.autoTitle(title, body), body.trim(), old == null ? now : old.created, now);
    v.put("metadata", metadata.toString());
    v.put("search_text", Metadata.searchText(metadata));
    if (id == 0) {
      v.put("created", now);
      return getWritableDatabase().insertOrThrow("notes", null, v);
    }
    if (getWritableDatabase().update("notes", v, "id=?", new String[] {"" + id}) != 1)
      throw new IllegalStateException("Заметка уже удалена");
    return id;
  }

  public void delete(long id) {
    getWritableDatabase().delete("notes", "id=?", new String[] {"" + id});
    new ManagerStore(this).cleanup();
  }

  /** Имена всех файлов изображений, на которые ссылаются заметки (в поле может быть список). */
  public Set<String> imageNames() {
    Set<String> out = new HashSet<>();
    try (Cursor c = getReadableDatabase().rawQuery("SELECT DISTINCT image FROM notes WHERE image<>''", null)) {
      while (c.moveToNext()) out.addAll(ImageList.parse(c.getString(0)));
    }
    return out;
  }

  public void image(long id, String image) {
    ContentValues v = new ContentValues();
    v.put("image", image);
    getWritableDatabase().update("notes", v, "id=?", new String[] {"" + id});
  }

  public long saveVision(Vision.Result result, String image) {
    long id = save(0, result.title, result.summary, 0);
    image(id, image);
    Analysis.Result proposal = new Analysis.Result();
    proposal.reminder = result.reminder;
    proposal.reason = result.reason;
    proposal.metadata = result.metadata;
    applyAnalysis(note(id), proposal);
    getWritableDatabase()
        .execSQL("UPDATE notes SET ai_checked=updated WHERE id=?", new Object[] {id});
    return id;
  }

  public List<Note> pendingAnalysis() {
    List<Note> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT * FROM notes WHERE ai_checked<>updated"
                    + " AND id NOT IN (SELECT top FROM note_groups)"
                    + " AND id NOT IN (SELECT note FROM note_members)"
                    + " ORDER BY created DESC LIMIT 5",
                null)) {
      while (c.moveToNext()) out.add(read(c));
    }
    return out;
  }

  public boolean claimAnalysis(Note note) {
    if (new ManagerStore(this).top(note.id) != 0) return false;
    ContentValues v = new ContentValues();
    v.put("ai_checked", note.updated);
    v.put("ai_failed", 1);
    return getWritableDatabase()
            .update(
                "notes",
                v,
                "id=? AND updated=? AND ai_checked<>updated",
                new String[] {"" + note.id, "" + note.updated})
        == 1;
  }

  public boolean applyAnalysis(Note note, Analysis.Result result) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      boolean applied = applyAnalysisLocked(note, result);
      db.setTransactionSuccessful();
      return applied;
    } finally {
      db.endTransaction();
    }
  }

  private boolean applyAnalysisLocked(Note snapshot, Analysis.Result result) {
    Note note = note(snapshot.id);
    if (note == null
        || note.updated != snapshot.updated
        || new ManagerStore(this).top(note.id) != 0) return false;
    ContentValues v = new ContentValues();
    v.put("ai_failed", 0);
    if (note.autoTitle && !result.title.isEmpty()) {
      v.put("title", result.title);
      v.put("title_auto", 0);
    }
    if (note.reminder == 0 && result.reminder > System.currentTimeMillis()) {
      v.put("suggestion", result.reminder);
      v.put("suggestion_reason", result.reason);
    }
    JSONObject metadata =
        Metadata.base(
            note.autoTitle && !result.title.isEmpty() ? result.title : note.title,
            note.body,
            note.created,
            note.updated);
    Metadata.withAI(metadata, Metadata.read(note.metadata).optJSONObject("ai"), note.updated);
    Metadata.withAI(metadata, result.metadata, note.updated);
    v.put("metadata", metadata.toString());
    v.put("search_text", Metadata.searchText(metadata));
    // Do not change the user's edit timestamp or overwrite a revision edited during the request.
    return getWritableDatabase()
            .update(
                "notes", v, "id=? AND updated=?", new String[] {"" + note.id, "" + note.updated})
        == 1;
  }

  public boolean applyMetadata(long id, long revision, JSONObject enrichment) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      boolean applied = applyMetadataLocked(id, revision, enrichment);
      db.setTransactionSuccessful();
      return applied;
    } finally {
      db.endTransaction();
    }
  }

  private boolean applyMetadataLocked(long id, long revision, JSONObject enrichment) {
    Note note = note(id);
    if (note == null || note.updated != revision) return false;
    if (Metadata.ai(enrichment).length() == 0) return false;
    JSONObject metadata = Metadata.withAI(Metadata.read(note.metadata), enrichment, revision);
    ContentValues v = new ContentValues();
    v.put("metadata", metadata.toString());
    v.put("search_text", Metadata.searchText(metadata));
    if (metadata.toString().equals(note.metadata)) return true;
    return getWritableDatabase()
            .update("notes", v, "id=? AND updated=?", new String[] {"" + id, "" + revision})
        == 1;
  }

  public void dismissSuggestion(long id) {
    ContentValues v = new ContentValues();
    v.put("suggestion", 0);
    v.put("suggestion_reason", "");
    getWritableDatabase().update("notes", v, "id=?", new String[] {"" + id});
  }

  public void retryFailed() {
    getWritableDatabase().execSQL("UPDATE notes SET ai_checked=0,ai_failed=0 WHERE ai_failed=1");
  }

  public long newChat(String title) {
    ContentValues v = new ContentValues();
    v.put("title", title);
    v.put("created", System.currentTimeMillis());
    return getWritableDatabase().insertOrThrow("chats", null, v);
  }

  public List<String[]> chats() {
    List<String[]> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT id,title,created FROM chats ORDER BY id DESC", null)) {
      while (c.moveToNext()) out.add(new String[] {c.getString(0), c.getString(1), c.getString(2)});
    }
    return out;
  }

  public void deleteChat(long id) {
    getWritableDatabase().delete("chats", "id=?", new String[] {"" + id});
  }

  public boolean chatExists(long id) {
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT id FROM chats WHERE id=?", new String[] {"" + id})) {
      return c.moveToFirst();
    }
  }

  public void message(long chat, String role, String body, JSONArray sources) {
    message(chat, role, body, sources, null);
  }

  public void message(long chat, String role, String body, JSONArray sources, JSONObject draft) {
    ContentValues v = new ContentValues();
    v.put("chat", chat);
    v.put("role", role);
    v.put("body", body);
    v.put("sources", sources.toString());
    v.put("created", System.currentTimeMillis());
    v.put("draft", draft == null ? "" : draft.toString());
    getWritableDatabase().insertOrThrow("messages", null, v);
  }

  public JSONArray messages(long chat) {
    JSONArray a = new JSONArray();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT role,body,sources,id,draft FROM messages WHERE chat=? ORDER BY id",
                new String[] {"" + chat})) {
      while (c.moveToNext()) {
        try {
          a.put(
              new JSONObject()
                  .put("role", c.getString(0))
                  .put("content", c.getString(1))
                  .put("id", c.getLong(3))
                  .put("draft", c.getString(4))
                  .put("sources", new JSONArray(c.getString(2))));
        } catch (JSONException e) {
          throw new IllegalStateException(e);
        }
      }
    }
    return a;
  }

  public long acceptDraft(long message, String title, String body) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try (Cursor c =
        db.rawQuery("SELECT sources,draft FROM messages WHERE id=?", new String[] {"" + message})) {
      if (!c.moveToFirst() || c.getString(1).isEmpty())
        throw new IllegalStateException("Предложение уже обработано");
      long id = save(0, title, body, 0);
      JSONObject draft = new JSONObject(c.getString(1));
      if (title.trim().equals(draft.optString("title"))
          && body.trim().equals(draft.optString("body")))
        applyMetadata(id, note(id).updated, draft.optJSONObject("metadata"));
      JSONArray sources = new JSONArray(c.getString(0));
      sources.put(new JSONObject().put("id", id).put("title", Note.autoTitle(title, body)));
      ContentValues v = new ContentValues();
      v.put("draft", "");
      v.put("sources", sources.toString());
      db.update("messages", v, "id=?", new String[] {"" + message});
      db.setTransactionSuccessful();
      return id;
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    } finally {
      db.endTransaction();
    }
  }

  public void dismissDraft(long message) {
    ContentValues v = new ContentValues();
    v.put("draft", "");
    getWritableDatabase().update("messages", v, "id=?", new String[] {"" + message});
  }

  public String cached(String key) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT body FROM cache WHERE cache_key=? AND created>?",
                new String[] {key, "" + (System.currentTimeMillis() - 86400000L)})) {
      return c.moveToFirst() ? c.getString(0) : null;
    }
  }

  public void cache(String key, String body) {
    SQLiteDatabase db = getWritableDatabase();
    db.delete("cache", "created<?", new String[] {"" + (System.currentTimeMillis() - 86400000L)});
    ContentValues v = new ContentValues();
    v.put("cache_key", key);
    v.put("body", body);
    v.put("created", System.currentTimeMillis());
    db.insertWithOnConflict("cache", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    db.execSQL(
        "DELETE FROM cache WHERE cache_key NOT IN (SELECT cache_key FROM cache ORDER BY created"
            + " DESC LIMIT 100)");
  }
}
