package app.fastnotes;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;

/**
 * SQLite-хранилище для синхронизации с компьютерами (docs/SYNC.md). Служебные поля заметок и
 * папок ({@code uid, sync_ts, sync_seq, sync_from}) и надгробия ведут триггеры из {@code
 * Store.addSync}; здесь — отбор записей для отправки и слияние чужих. Правила слияния повторяют
 * desktop/core/src/sync/engine.rs.
 */
public final class SqliteSyncStore implements SyncStore {
  private final Store store;

  public SqliteSyncStore(Store store) {
    this.store = store;
  }

  private SQLiteDatabase db() {
    return store.getWritableDatabase();
  }

  // ---------- служебное ----------

  private long scalar(String sql, String... args) {
    try (Cursor c = db().rawQuery(sql, args)) {
      return c.moveToFirst() ? c.getLong(0) : 0;
    }
  }

  private String text(String sql, String... args) {
    try (Cursor c = db().rawQuery(sql, args)) {
      return c.moveToFirst() ? c.getString(0) : "";
    }
  }

  @Override
  public String deviceId() {
    return text("SELECT device FROM sync_meta WHERE id=1");
  }

  @Override
  public String deviceName() {
    return text("SELECT name FROM sync_meta WHERE id=1");
  }

  private long currentSeq() {
    return scalar("SELECT seq FROM sync_meta WHERE id=1");
  }

  /** Новый seq для записи, принятой от соседа; часы подтягиваются к его ts. */
  private long stampRemote(long remoteTs) {
    db().execSQL(
            "UPDATE sync_meta SET seq=seq+1, hlc=max(hlc, ?) WHERE id=1", new Object[] {remoteTs});
    return currentSeq();
  }

  /** Новый (ts, seq) для локального изменения, как в триггерах. */
  private long[] stampLocal() {
    db().execSQL(
            "UPDATE sync_meta SET seq=seq+1,"
                + " hlc=max(hlc+1, CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)) WHERE"
                + " id=1");
    return new long[] {scalar("SELECT hlc FROM sync_meta WHERE id=1"), currentSeq()};
  }

  // ---------- узлы ----------

  private static Peer readPeer(Cursor c) {
    Peer p = new Peer();
    p.device = c.getString(0);
    p.name = c.getString(1);
    p.platform = c.getString(2);
    p.secret = c.getBlob(3);
    try {
      JSONArray hosts = new JSONArray(c.getString(4));
      for (int i = 0; i < hosts.length(); i++) p.hosts.add(hosts.getString(i));
    } catch (JSONException ignored) {
    }
    p.sentSeq = c.getLong(5);
    p.gotSeq = c.getLong(6);
    p.lastSync = c.getLong(7);
    return p;
  }

  private static final String PEER_COLS =
      "device,name,platform,secret,hosts,sent_seq,got_seq,last_sync";

  @Override
  public Peer peer(String device) {
    try (Cursor c =
        db().rawQuery("SELECT " + PEER_COLS + " FROM sync_peers WHERE device=?", new String[] {device})) {
      return c.moveToFirst() ? readPeer(c) : null;
    }
  }

  @Override
  public List<Peer> peers() {
    List<Peer> out = new ArrayList<>();
    try (Cursor c = db().rawQuery("SELECT " + PEER_COLS + " FROM sync_peers ORDER BY name", null)) {
      while (c.moveToNext()) out.add(readPeer(c));
    }
    return out;
  }

  @Override
  public void savePeer(Peer p) {
    ContentValues v = new ContentValues();
    v.put("device", p.device);
    v.put("name", p.name);
    v.put("platform", p.platform);
    v.put("secret", p.secret);
    v.put("hosts", new JSONArray(p.hosts).toString());
    v.put("sent_seq", p.sentSeq);
    v.put("got_seq", p.gotSeq);
    v.put("last_sync", p.lastSync);
    db().insertWithOnConflict("sync_peers", null, v, SQLiteDatabase.CONFLICT_REPLACE);
  }

  @Override
  public void deletePeer(String device) {
    db().delete("sync_peers", "device=?", new String[] {device});
  }

  @Override
  public void setHosts(String device, List<String> hosts) {
    db().execSQL(
            "UPDATE sync_peers SET hosts=? WHERE device=?",
            new Object[] {new JSONArray(hosts).toString(), device});
  }

  @Override
  public void progress(String device, long sent, long got, long when) {
    if (sent >= 0)
      db().execSQL("UPDATE sync_peers SET sent_seq=? WHERE device=?", new Object[] {sent, device});
    if (got >= 0)
      db().execSQL("UPDATE sync_peers SET got_seq=? WHERE device=?", new Object[] {got, device});
    if (when >= 0)
      db().execSQL("UPDATE sync_peers SET last_sync=? WHERE device=?", new Object[] {when, device});
  }

  // ---------- отправка ----------

  private static final class Item {
    final long seq;
    final SyncRecord record;

    Item(long seq, SyncRecord record) {
      this.seq = seq;
      this.record = record;
    }
  }

  /**
   * Нумерует заметки без uid (созданные после последней отправки или принятые как копии при
   * конфликте). Триггера на вставку нет: он дважды вставил бы строку в индекс поиска.
   */
  void stampPending() {
    List<Long> ids = new ArrayList<>();
    try (Cursor c = db().rawQuery("SELECT id FROM notes WHERE sync_seq=0 ORDER BY id", null)) {
      while (c.moveToNext()) ids.add(c.getLong(0));
    }
    for (long id : ids) {
      long[] stamp = stampLocal();
      db().execSQL(
              "UPDATE notes SET uid=CASE WHEN uid='' THEN lower(hex(randomblob(16))) ELSE uid END,"
                  + "sync_ts=?,sync_seq=?,sync_from='' WHERE id=?",
              new Object[] {stamp[0], stamp[1], id});
    }
  }

  @Override
  public Batch collect(long since, String exclude, int limit, boolean allFolders) {
    stampPending();
    long snapshot = currentSeq();
    List<Item> items = new ArrayList<>();
    try (Cursor c =
        db().rawQuery(
                "SELECT n.uid,n.title,n.body,n.created,n.updated,n.reminder,n.repeat_mode,"
                    + "n.lead_minutes,n.image,n.title_auto,"
                    + "(n.ai_checked=n.updated AND n.ai_failed=0 AND n.ai_checked<>0),n.metadata,"
                    + "n.sync_ts,n.sync_seq,ifnull((SELECT uid FROM folders f WHERE f.id=n.folder),'')"
                    + " FROM notes n WHERE n.sync_seq>? AND n.sync_seq<=? AND n.sync_from<>?"
                    + " ORDER BY n.sync_seq LIMIT ?",
                new String[] {"" + since, "" + snapshot, exclude, "" + (limit + 1)})) {
      while (c.moveToNext()) {
        SyncRecord r = new SyncRecord();
        r.uid = c.getString(0);
        r.title = c.getString(1);
        r.body = c.getString(2);
        r.created = c.getLong(3);
        r.updated = c.getLong(4);
        r.reminder = c.getLong(5);
        r.repeat = c.getInt(6);
        r.lead = c.getInt(7);
        r.images = ImageList.parse(c.getString(8));
        r.titleAuto = c.getInt(9) == 1;
        r.aiChecked = c.getInt(10) == 1;
        r.metadata = c.getString(11);
        r.ts = c.getLong(12);
        r.folder = c.getString(14);
        items.add(new Item(c.getLong(13), r));
      }
    }
    try (Cursor c =
        db().rawQuery(
                "SELECT uid,ts,seq FROM tombstones WHERE kind='note' AND seq>? AND seq<=? AND"
                    + " from_peer<>? ORDER BY seq LIMIT ?",
                new String[] {"" + since, "" + snapshot, exclude, "" + (limit + 1)})) {
      while (c.moveToNext())
        items.add(new Item(c.getLong(2), SyncRecord.tomb("note", c.getString(0), c.getLong(1))));
    }
    Collections.sort(items, Comparator.comparingLong(i -> i.seq));
    Batch batch = new Batch();
    batch.more = items.size() > limit;
    if (batch.more) items = new ArrayList<>(items.subList(0, limit));
    batch.next = batch.more ? items.get(items.size() - 1).seq : snapshot;
    // Папки идут первыми: заметки должны найти свои папки.
    String where =
        allFolders ? "" : " WHERE sync_seq>? AND sync_seq<=? AND sync_from<>?";
    String[] args = allFolders ? null : new String[] {"" + since, "" + snapshot, exclude};
    try (Cursor c =
        db().rawQuery("SELECT uid,name,key,created,auto,sync_ts FROM folders" + where, args)) {
      while (c.moveToNext()) {
        SyncRecord r = new SyncRecord();
        r.kind = "folder";
        r.uid = c.getString(0);
        r.name = c.getString(1);
        r.key = c.getString(2);
        r.created = c.getLong(3);
        r.auto = c.getInt(4) == 1;
        r.ts = c.getLong(5);
        batch.records.add(r);
      }
    }
    try (Cursor c =
        db().rawQuery(
                allFolders
                    ? "SELECT uid,ts FROM tombstones WHERE kind='folder'"
                    : "SELECT uid,ts FROM tombstones WHERE kind='folder' AND seq>? AND seq<=? AND"
                        + " from_peer<>?",
                args)) {
      while (c.moveToNext())
        batch.records.add(SyncRecord.tomb("folder", c.getString(0), c.getLong(1)));
    }
    for (Item i : items) batch.records.add(i.record);
    return batch;
  }

  // ---------- приём ----------

  @Override
  public Stats apply(String peer, long sentSeq, List<SyncRecord> records) {
    Stats stats = new Stats();
    SQLiteDatabase db = db();
    db.beginTransaction();
    try {
      for (SyncRecord r : records)
        if (r.isFolder()) {
          if (applyFolder(peer, r)) stats.applied++;
          else stats.ignored++;
        }
      for (SyncRecord r : records)
        if (!r.isFolder()) {
          switch (applyNote(peer, sentSeq, r, stats)) {
            case 0 -> stats.ignored++;
            case 1 -> stats.applied++;
            default -> {
              stats.applied++;
              stats.conflicts++;
            }
          }
        }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    return stats;
  }

  private long tombstoneTs(String kind, String uid) {
    try (Cursor c =
        db().rawQuery(
                "SELECT ts FROM tombstones WHERE kind=? AND uid=?", new String[] {kind, uid})) {
      return c.moveToFirst() ? c.getLong(0) : Long.MIN_VALUE;
    }
  }

  private void putTombstone(String kind, String uid, long ts, String peer) {
    long seq = stampRemote(ts);
    db().execSQL(
            "INSERT OR REPLACE INTO tombstones(kind,uid,ts,seq,from_peer) VALUES(?,?,?,?,?)",
            new Object[] {kind, uid, ts, seq, peer});
  }

  private boolean applyFolder(String peer, SyncRecord r) {
    SQLiteDatabase db = db();
    long localId = -1, localTs = 0;
    try (Cursor c =
        db.rawQuery("SELECT id,sync_ts FROM folders WHERE uid=?", new String[] {r.uid})) {
      if (c.moveToFirst()) {
        localId = c.getLong(0);
        localTs = c.getLong(1);
      }
    }
    if (r.deleted) {
      if (localId >= 0) {
        if (r.ts <= localTs) return false;
        // Заметки возвращаются в «Все заметки» без нового ts: их версии приходят от соседа.
        db.execSQL("UPDATE notes SET folder=0 WHERE folder=?", new Object[] {localId});
        db.delete("folders", "id=?", new String[] {"" + localId});
        putTombstone("folder", r.uid, r.ts, peer);
        return true;
      }
      if (tombstoneTs("folder", r.uid) < r.ts) {
        putTombstone("folder", r.uid, r.ts, peer);
        return true;
      }
      return false;
    }
    if (!FolderPolicy.valid(r.name)) return false;
    long tomb = tombstoneTs("folder", r.uid);
    if (tomb != Long.MIN_VALUE) {
      if (tomb >= r.ts) return false;
      db.execSQL("DELETE FROM tombstones WHERE kind='folder' AND uid=?", new Object[] {r.uid});
    }
    String name = FolderPolicy.clean(r.name), key = FolderPolicy.key(name);
    int n = 2;
    while (true) {
      String other = null;
      try (Cursor c = db.rawQuery("SELECT uid FROM folders WHERE key=?", new String[] {key})) {
        if (c.moveToFirst()) other = c.getString(0);
      }
      if (other == null || other.equals(r.uid)) break;
      name = FolderPolicy.clean(r.name) + " (" + n++ + ")";
      key = FolderPolicy.key(name);
    }
    long seq = stampRemote(r.ts);
    if (localId >= 0) {
      if (r.ts <= localTs) return false;
      db.execSQL(
          "UPDATE folders SET name=?,key=?,auto=?,sync_ts=?,sync_seq=?,sync_from=? WHERE id=?",
          new Object[] {name, key, r.auto ? 1 : 0, r.ts, seq, peer, localId});
    } else {
      ContentValues v = new ContentValues();
      v.put("uid", r.uid);
      v.put("name", name);
      v.put("key", key);
      v.put("created", r.created);
      v.put("auto", r.auto ? 1 : 0);
      v.put("sync_ts", r.ts);
      v.put("sync_seq", seq);
      v.put("sync_from", peer);
      db.insertOrThrow("folders", null, v);
    }
    return true;
  }

  /** 0 — пропущено, 1 — принято, 2 — принято с копией при конфликте. */
  private int applyNote(String peer, long sentSeq, SyncRecord r, Stats stats) {
    SQLiteDatabase db = db();
    long localId = -1, localTs = 0, localSeq = 0, localFolder = 0;
    String localTitle = "", localBody = "", localImage = "";
    try (Cursor c =
        db.rawQuery(
            "SELECT id,sync_ts,sync_seq,title,body,image,folder FROM notes WHERE uid=?",
            new String[] {r.uid})) {
      if (c.moveToFirst()) {
        localId = c.getLong(0);
        localTs = c.getLong(1);
        localSeq = c.getLong(2);
        localTitle = c.getString(3);
        localBody = c.getString(4);
        localImage = c.getString(5);
        localFolder = c.getLong(6);
      }
    }
    if (r.deleted) {
      if (localId >= 0) {
        if (r.ts <= localTs) return 0;
        db.delete("notes", "id=?", new String[] {"" + localId});
        putTombstone("note", r.uid, r.ts, peer);
        stats.removed.add(localId);
        return 1;
      }
      if (tombstoneTs("note", r.uid) < r.ts) {
        putTombstone("note", r.uid, r.ts, peer);
        return 1;
      }
      return 0;
    }
    long tomb = tombstoneTs("note", r.uid);
    if (tomb != Long.MIN_VALUE) {
      if (tomb >= r.ts) return 0;
      db.execSQL("DELETE FROM tombstones WHERE kind='note' AND uid=?", new Object[] {r.uid});
    }
    long folder = 0;
    if (!r.folder.isEmpty())
      try (Cursor c = db.rawQuery("SELECT id FROM folders WHERE uid=?", new String[] {r.folder})) {
        if (c.moveToFirst()) folder = c.getLong(0);
      }
    List<String> names = new ArrayList<>();
    for (String name : r.images)
      if (SyncRecord.safeFileName(name) && names.size() < ImageList.MAX) names.add(name);
    String metadata = r.metadata;
    if (Metadata.read(metadata).length() == 0)
      metadata = Metadata.base(r.title, r.body, r.created, r.updated).toString();
    ContentValues v = new ContentValues();
    v.put("title", r.title);
    v.put("body", r.body);
    v.put("created", r.created);
    v.put("updated", r.updated);
    v.put("reminder", r.reminder);
    v.put("repeat_mode", r.repeat);
    v.put("lead_minutes", r.lead);
    v.put("image", ImageList.join(names));
    v.put("folder", folder);
    v.put("title_auto", r.titleAuto ? 1 : 0);
    v.put("ai_checked", r.aiChecked ? r.updated : 0);
    v.put("ai_failed", 0);
    v.put("suggestion", 0);
    v.put("suggestion_reason", "");
    v.put("metadata", metadata);
    v.put("search_text", Metadata.searchText(Metadata.read(metadata)));
    v.put("sync_ts", r.ts);
    v.put("sync_from", peer);
    boolean conflict = false;
    if (localId >= 0) {
      if (r.ts <= localTs) return 0;
      // Побеждает чужая запись. Если мы тоже правили после прошлой отправки — сохраняем копию.
      if (localSeq > sentSeq && (!localTitle.equals(r.title) || !localBody.equals(r.body))) {
        ContentValues copy = new ContentValues();
        String title = localTitle + " (копия при конфликте)";
        long now = System.currentTimeMillis();
        copy.put("title", title);
        copy.put("body", localBody);
        copy.put("created", now);
        copy.put("updated", now);
        copy.put("image", localImage);
        copy.put("folder", localFolder);
        JSONObjectHolder.fill(copy, title, localBody, now);
        db.insertOrThrow("notes", null, copy); // uid и ts выставит триггер
        conflict = true;
      }
      v.put("sync_seq", stampRemote(r.ts));
      db.update("notes", v, "id=?", new String[] {"" + localId});
    } else {
      v.put("uid", r.uid);
      v.put("sync_seq", stampRemote(r.ts));
      db.insertOrThrow("notes", null, v);
    }
    return conflict ? 2 : 1;
  }

  /** Метаданные копии заметки, не засоряя applyNote. */
  private static final class JSONObjectHolder {
    static void fill(ContentValues v, String title, String body, long now) {
      org.json.JSONObject meta = Metadata.base(title, body, now, now);
      v.put("metadata", meta.toString());
      v.put("search_text", Metadata.searchText(meta));
    }
  }
}
