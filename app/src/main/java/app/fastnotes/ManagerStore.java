package app.fastnotes;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;
import org.json.*;

/** Persistent review queue, proposals and flat groups, sharing Store's transactions. */
public final class ManagerStore {
  private final Store store;
  private final SQLiteDatabase db;

  public ManagerStore(Store store) {
    this.store = store;
    db = store.getWritableDatabase();
  }

  public static void create(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE manager_review(note INTEGER PRIMARY KEY REFERENCES notes(id) ON DELETE"
            + " CASCADE,revision TEXT NOT NULL,attempted INTEGER NOT NULL,failed INTEGER NOT NULL"
            + " DEFAULT 1)");
    db.execSQL(
        "CREATE TABLE manager_proposals(id INTEGER PRIMARY KEY AUTOINCREMENT,identity TEXT NOT NULL"
            + " UNIQUE,data TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'pending',created INTEGER NOT"
            + " NULL,result INTEGER)");
    db.execSQL(
        "CREATE TABLE note_groups(top INTEGER PRIMARY KEY REFERENCES notes(id) ON DELETE"
            + " CASCADE,expanded INTEGER NOT NULL DEFAULT 0)");
    db.execSQL(
        "CREATE TABLE note_members(note INTEGER PRIMARY KEY REFERENCES notes(id) ON DELETE"
            + " CASCADE,top INTEGER NOT NULL REFERENCES note_groups(top) ON DELETE CASCADE)");
    db.execSQL(
        "CREATE TABLE manager_runs(id INTEGER PRIMARY KEY AUTOINCREMENT,started INTEGER NOT"
            + " NULL,state TEXT NOT NULL)");
  }

  public List<Note> batch() {
    return batch(false);
  }

  public List<Note> batch(boolean manual) {
    List<Note> seeds = new ArrayList<>();
    try (Cursor c =
        db.rawQuery(
            "SELECT n.id,r.revision,r.failed FROM notes n LEFT JOIN manager_review r ON r.note=n.id"
                + " ORDER BY CASE WHEN r.note IS NULL OR n.updated>r.attempted THEN 0 ELSE 1"
                + " END,COALESCE(r.attempted,0),n.id",
            null)) {
      while (c.moveToNext()) {
        Note n = store.note(c.getLong(0));
        if (n != null
            && (manual
                || c.isNull(1)
                || !ManagerAnalysis.fingerprint(n).equals(c.getString(1))
                || c.getInt(2) == 0)) seeds.add(n);
      }
    }
    LinkedHashMap<Long, Note> selected = new LinkedHashMap<>();
    // Two rotating seeds guarantee progress even when one topic dominates search results.
    for (int i = 0; i < Math.min(2, seeds.size()); i++) selected.put(seeds.get(i).id, seeds.get(i));
    for (Note seed : new ArrayList<>(selected.values())) {
      for (Note n :
          store.searchNotes(seed.title + " " + Retrieval.excerpt(seed.searchText, "", 200))) {
        if (selected.size() >= 8) break;
        if (manual || !failedUnchanged(n)) selected.put(n.id, n);
      }
    }
    for (Note n : seeds) {
      if (selected.size() >= 8) break;
      selected.put(n.id, n);
    }
    return new ArrayList<>(selected.values());
  }

  private boolean failedUnchanged(Note n) {
    try (Cursor c =
        db.rawQuery(
            "SELECT revision,failed FROM manager_review WHERE note=?", new String[] {"" + n.id})) {
      return c.moveToFirst()
          && c.getInt(1) != 0
          && c.getString(0).equals(ManagerAnalysis.fingerprint(n));
    }
  }

  public long claim(List<Note> notes) {
    db.beginTransaction();
    try {
      long now = System.currentTimeMillis();
      ContentValues run = new ContentValues();
      run.put("started", now);
      run.put("state", "uncertain");
      long id = db.insertOrThrow("manager_runs", null, run);
      for (Note n : notes) {
        ContentValues v = new ContentValues();
        v.put("note", n.id);
        v.put("revision", ManagerAnalysis.fingerprint(n));
        v.put("attempted", now);
        v.put("failed", 1);
        db.insertWithOnConflict("manager_review", null, v, SQLiteDatabase.CONFLICT_REPLACE);
      }
      db.setTransactionSuccessful();
      return id;
    } finally {
      db.endTransaction();
    }
  }

  public int finish(long run, List<Note> notes, List<JSONObject> proposals) throws Exception {
    db.beginTransaction();
    try {
      int added = 0;
      for (JSONObject p : proposals) {
        if (!current(p)) continue;
        if (p.getString("type").equals("merge") || p.getString("type").equals("link")) {
          Set<Long> distinctGroups = new HashSet<>();
          JSONArray sources = p.getJSONArray("sources");
          for (int i = 0; i < sources.length(); i++) {
            long id = sources.getJSONObject(i).getLong("id"), group = top(id);
            distinctGroups.add(group == 0 ? id : group);
          }
          if (distinctGroups.size() < 2) continue;
        }
        ContentValues v = new ContentValues();
        v.put("identity", p.getString("identity"));
        v.put("data", p.toString());
        v.put("created", System.currentTimeMillis());
        if (db.insertWithOnConflict("manager_proposals", null, v, SQLiteDatabase.CONFLICT_IGNORE)
            != -1) added++;
      }
      for (Note n : notes)
        db.execSQL(
            "UPDATE manager_review SET failed=0 WHERE note=? AND revision=?",
            new Object[] {n.id, ManagerAnalysis.fingerprint(n)});
      db.execSQL("UPDATE manager_runs SET state='complete' WHERE id=?", new Object[] {run});
      db.setTransactionSuccessful();
      return added;
    } finally {
      db.endTransaction();
    }
  }

  public void retryFailed() {
    db.execSQL("DELETE FROM manager_review WHERE failed=1");
  }

  public static void attachGroups(JSONObject proposal, JSONObject snapshot) throws Exception {
    JSONObject groups = new JSONObject();
    JSONArray sources = proposal.getJSONArray("sources");
    StringBuilder identity = new StringBuilder(proposal.getString("type"));
    for (int i = 0; i < sources.length(); i++) {
      JSONObject source = sources.getJSONObject(i);
      String id = "" + source.getLong("id"), revision = source.getString("revision");
      identity
          .append('|')
          .append(id)
          .append(':')
          .append(revision.length())
          .append(':')
          .append(revision);
    }
    for (int i = 0; i < sources.length(); i++) {
      String id = "" + sources.getJSONObject(i).getLong("id"), revision = snapshot.getString(id);
      groups.put(id, revision);
      identity.append('|').append(revision.length()).append(':').append(revision);
    }
    proposal.put("groups", groups).put("identity", identity.toString());
  }

  public static final class ReviewOutcome {
    public int renamed, reminders, added, reviewed;
  }

  public ReviewOutcome finishReview(
      long run, List<Note> notes, List<JSONObject> proposals, Map<Long, Analysis.Result> analysis)
      throws Exception {
    db.beginTransaction();
    try {
      ReviewOutcome outcome = new ReviewOutcome();
      List<JSONObject> valid = new ArrayList<>();
      for (JSONObject p : proposals) if (current(p)) valid.add(p);
      List<Note> unchanged = new ArrayList<>();
      for (Note snapshot : notes) {
        Note current = store.note(snapshot.id);
        if (current == null
            || !ManagerAnalysis.fingerprint(current).equals(ManagerAnalysis.fingerprint(snapshot)))
          continue;
        Analysis.Result result = analysis.get(snapshot.id);
        if (result != null && store.applyAnalysis(snapshot, result)) {
          Note after = store.note(snapshot.id);
          if (!after.title.equals(current.title)) outcome.renamed++;
          if (after.suggestion > 0 && after.suggestion != current.suggestion) outcome.reminders++;
          outcome.reviewed++;
        }
        unchanged.add(store.note(snapshot.id));
      }
      // Only our own title changes are rebased. User changes were rejected above, under this
      // transaction.
      JSONObject groups = groupSnapshot(unchanged);
      for (JSONObject p : valid) {
        JSONArray sources = p.getJSONArray("sources");
        for (int i = 0; i < sources.length(); i++) {
          JSONObject source = sources.getJSONObject(i);
          source.put("revision", ManagerAnalysis.fingerprint(store.note(source.getLong("id"))));
        }
        attachGroups(p, groups);
      }
      outcome.added = finish(run, notes, valid);
      for (Note n : unchanged)
        db.execSQL(
            "UPDATE manager_review SET revision=?,failed=0 WHERE note=?",
            new Object[] {ManagerAnalysis.fingerprint(n), n.id});
      db.setTransactionSuccessful();
      return outcome;
    } finally {
      db.endTransaction();
    }
  }

  public int failedCount() {
    int count = 0;
    for (Note n : store.notes()) if (failedUnchanged(n)) count++;
    return count;
  }

  public boolean current(JSONObject p) throws Exception {
    JSONArray sources = p.getJSONArray("sources");
    for (int i = 0; i < sources.length(); i++) {
      JSONObject source = sources.getJSONObject(i);
      Note n = store.note(source.getLong("id"));
      if (n == null || !ManagerAnalysis.fingerprint(n).equals(source.getString("revision")))
        return false;
    }
    JSONObject groups = p.optJSONObject("groups");
    if (groups != null) {
      Iterator<String> keys = groups.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        if (!groupRevision(Long.parseLong(key)).equals(groups.getString(key))) return false;
      }
    }
    return true;
  }

  public JSONObject groupSnapshot(List<Note> notes) throws Exception {
    JSONObject out = new JSONObject();
    for (Note n : notes) out.put("" + n.id, groupRevision(n.id));
    return out;
  }

  private String groupRevision(long id) {
    long top = top(id);
    StringBuilder out = new StringBuilder("" + top);
    if (top != 0) {
      Note summary = store.note(top);
      if (summary != null) out.append('|').append(ManagerAnalysis.fingerprint(summary));
      for (long member : members(top)) {
        Note n = store.note(member);
        if (n != null)
          out.append('|').append(member).append(':').append(ManagerAnalysis.fingerprint(n));
      }
    }
    return out.toString();
  }

  public List<JSONObject> pending() {
    List<JSONObject> out = new ArrayList<>();
    try (Cursor c =
        db.rawQuery(
            "SELECT id,data FROM manager_proposals WHERE state='pending' ORDER BY created DESC,id"
                + " DESC",
            null)) {
      while (c.moveToNext()) {
        try {
          JSONObject p = new JSONObject(c.getString(1)).put("id", c.getLong(0));
          if (current(p)) out.add(p);
          else state(c.getLong(0), "stale");
        } catch (Exception e) {
          state(c.getLong(0), "stale");
        }
      }
    }
    return out;
  }

  public void state(long id, String state) {
    db.execSQL(
        "UPDATE manager_proposals SET state=? WHERE id=? AND state='pending'",
        new Object[] {state, id});
  }

  public long accept(long id, String title, String body) throws Exception {
    db.beginTransaction();
    try (Cursor c =
        db.rawQuery(
            "SELECT data,state,result FROM manager_proposals WHERE id=?", new String[] {"" + id})) {
      if (!c.moveToFirst()) throw new IllegalStateException("Предложение не найдено");
      if (c.getString(1).equals("accepted")) return c.getLong(2);
      JSONObject p = new JSONObject(c.getString(0));
      if (!c.getString(1).equals("pending") || !current(p))
        throw new IllegalStateException("Исходные заметки изменились. Нужна новая проверка.");
      if (body.trim().isEmpty()) throw new IllegalStateException("Добавьте текст заметки");
      long result = store.save(0, title, body, 0);
      if (p.getString("type").equals("merge")) {
        LinkedHashSet<Long> members = new LinkedHashSet<>();
        JSONArray sources = p.getJSONArray("sources");
        for (int i = 0; i < sources.length(); i++) {
          long source = sources.getJSONObject(i).getLong("id"), top = top(source);
          members.add(source);
          if (top != 0) {
            members.add(top);
            members.addAll(members(top));
          }
        }
        for (long member : members) db.delete("note_groups", "top=?", new String[] {"" + member});
        db.execSQL("INSERT INTO note_groups(top) VALUES(?)", new Object[] {result});
        for (long member : members)
          db.execSQL(
              "INSERT OR REPLACE INTO note_members(note,top) VALUES(?,?)",
              new Object[] {member, result});
      }
      db.execSQL(
          "UPDATE manager_proposals SET state='accepted',result=? WHERE id=?",
          new Object[] {result, id});
      db.setTransactionSuccessful();
      return result;
    } finally {
      db.endTransaction();
    }
  }

  public long top(long id) {
    try (Cursor c =
        db.rawQuery(
            "SELECT top FROM note_groups WHERE top=? UNION SELECT top FROM note_members WHERE"
                + " note=?",
            new String[] {"" + id, "" + id})) {
      return c.moveToFirst() ? c.getLong(0) : 0;
    }
  }

  public List<Long> members(long top) {
    List<Long> out = new ArrayList<>();
    try (Cursor c =
        db.rawQuery(
            "SELECT note FROM note_members WHERE top=? ORDER BY note", new String[] {"" + top})) {
      while (c.moveToNext()) out.add(c.getLong(0));
    }
    return out;
  }

  public boolean expanded(long top) {
    try (Cursor c =
        db.rawQuery("SELECT expanded FROM note_groups WHERE top=?", new String[] {"" + top})) {
      return c.moveToFirst() && c.getInt(0) != 0;
    }
  }

  public void toggle(long top) {
    db.execSQL("UPDATE note_groups SET expanded=1-expanded WHERE top=?", new Object[] {top});
  }

  public void cleanup() {
    db.execSQL(
        "DELETE FROM note_groups WHERE NOT EXISTS(SELECT 1 FROM note_members m WHERE"
            + " m.top=note_groups.top)");
  }

  public JSONArray export(String table) throws Exception {
    if (!Arrays.asList("note_groups", "note_members", "manager_proposals").contains(table))
      throw new IllegalArgumentException();
    JSONArray rows = new JSONArray();
    try (Cursor c = db.rawQuery("SELECT * FROM " + table, null)) {
      while (c.moveToNext()) {
        JSONObject row = new JSONObject();
        for (int i = 0; i < c.getColumnCount(); i++)
          row.put(c.getColumnName(i), c.isNull(i) ? JSONObject.NULL : c.getString(i));
        rows.put(row);
      }
    }
    return rows;
  }
}
