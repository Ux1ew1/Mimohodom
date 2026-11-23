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
    super(context, name, null, 21);
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
    addFolders(db);
    addSync(db);
    addChatSync(db);
    addSuggestionLead(db);
    addPlans(db);
    addTrash(db);
    AgentLog.create(db);
    addReminderTimes(db);
    addChatOrigin(db);
    addTaskKinds(db);
    addSemanticIndex(db);
    addSuggestionTimes(db);
    addAttention(db);
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
    if (oldV < 8) addFolders(db);
    if (oldV < 9) addSync(db);
    if (oldV < 10) addChatSync(db);
    if (oldV < 11) addSuggestionLead(db);
    if (oldV < 12) addPlans(db);
    if (oldV < 13) addTrash(db);
    if (oldV < 14) AgentLog.create(db);
    if (oldV < 15) addReminderTimes(db);
    if (oldV < 16) {
      addChatOrigin(db);
      moveCheckInMarks(db);
    }
    if (oldV < 17) addTaskKinds(db);
    if (oldV < 18) addSemanticIndex(db);
    if (oldV < 19) addSuggestionTimes(db);
    if (oldV < 20) addAttention(db);
    if (oldV < 21) {
      /* Страховка после случая на телефоне: база версии 20 без suggestion_times (миграция 19
         когда-то не отработала), каждый save() падал на неизвестном столбце. Обе функции
         идемпотентны, поэтому просто гарантируем наличие колонок. */
      addSuggestionTimes(db);
      addAttention(db);
    }
  }

  /**
   * Баллы внимания помощника (docs/ATTENTION-POINTS.md, эксперимент): {@code attention} — запас
   * 0..8 или -1 («не назначены»: функция выключена или заметка создана до неё), {@code
   * attention_at}/{@code attention_down} — время последнего плюса и последнего минуса (гейты «не
   * чаще раза в 20 часов»). Как {@code suggestion_times}: в обмен не идёт, триггеров нет, изменение
   * баллов не помечает заметку для компьютера. Безопасно вызывается повторно.
   */
  private void addAttention(SQLiteDatabase db) {
    if (!hasColumn(db, "attention"))
      db.execSQL("ALTER TABLE notes ADD COLUMN attention INTEGER NOT NULL DEFAULT -1");
    if (!hasColumn(db, "attention_at"))
      db.execSQL("ALTER TABLE notes ADD COLUMN attention_at INTEGER NOT NULL DEFAULT 0");
    if (!hasColumn(db, "attention_down"))
      db.execSQL("ALTER TABLE notes ADD COLUMN attention_down INTEGER NOT NULL DEFAULT 0");
  }

  /**
   * Все даты предложения ИИ, если их несколько (JSON-массив моментов). Как и само предложение,
   * поле локальное: в обмен не уходит и триггера не имеет. Безопасно вызывается повторно.
   */
  private void addSuggestionTimes(SQLiteDatabase db) {
    if (!hasColumn(db, "suggestion_times"))
      db.execSQL("ALTER TABLE notes ADD COLUMN suggestion_times TEXT NOT NULL DEFAULT ''");
  }

  /**
   * Класс заметки из проверки агента ({@link TaskKind}: info, simple, event, complex) — в строке
   * проверки заметки, только на этом устройстве (в обмен не идёт). Безопасно вызывается повторно.
   */
  private void addTaskKinds(SQLiteDatabase db) {
    if (!hasColumn(db, "manager_review", "kind"))
      db.execSQL("ALTER TABLE manager_review ADD COLUMN kind TEXT NOT NULL DEFAULT ''");
  }

  /** Векторы только на этом устройстве; fingerprint позволяет не считать неизменённые заметки. */
  private void addSemanticIndex(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS note_embeddings(note_id INTEGER PRIMARY KEY REFERENCES"
            + " notes(id) ON DELETE CASCADE,fingerprint TEXT NOT NULL,vector BLOB NOT NULL)");
  }

  /**
   * Кто начал чат ({@link ChatOrigin}: user, agent, note) вместо настройки {@code checkInChat.<id>},
   * чтобы цвет и подпись чата совпадали на всех устройствах. Смена происхождения или заметки чата
   * уходит в обмен. Безопасно вызывается повторно.
   */
  private void addChatOrigin(SQLiteDatabase db) {
    if (!hasColumn(db, "chats", "origin"))
      db.execSQL("ALTER TABLE chats ADD COLUMN origin TEXT NOT NULL DEFAULT 'user'");
    String stamp =
        "UPDATE sync_meta SET seq=seq+1,hlc=max(hlc+1,CAST((julianday('now')-2440587.5)*86400000"
            + " AS INTEGER)) WHERE id=1;";
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS chats_sync_link AFTER UPDATE OF note,origin ON chats WHEN"
            + " new.sync_seq=old.sync_seq AND (new.note<>old.note OR new.origin<>old.origin) BEGIN "
            + stamp
            + "UPDATE chats SET sync_ts=(SELECT hlc FROM sync_meta WHERE id=1),sync_seq=(SELECT seq"
            + " FROM sync_meta WHERE id=1),sync_from='' WHERE id=new.id;END");
    db.execSQL("UPDATE chats SET origin='note' WHERE note<>0 AND origin='user'");
  }

  /**
   * Перенос прежних отметок «чат начат помощником» из настроек в {@code chats.origin}. Сами ключи
   * остаются (их больше никто не читает): если обновление базы не завершится, отметки не пропадут.
   */
  private void moveCheckInMarks(SQLiteDatabase db) {
    SharedPreferences prefs = appContext.getSharedPreferences("settings", 0);
    for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
      if (!e.getKey().startsWith("checkInChat.") || !Boolean.TRUE.equals(e.getValue())) continue;
      try {
        long id = Long.parseLong(e.getKey().substring("checkInChat.".length()));
        db.execSQL("UPDATE chats SET origin='agent' WHERE id=?", new Object[] {id});
      } catch (NumberFormatException ignored) {
        // повреждённый ключ пропускаем
      }
    }
  }

  /**
   * Одна заметка — несколько дат: все даты серии {@link ReminderPolicy#REPEAT_LIST} (JSON-массив
   * моментов), в {@code reminder} — ближайшая. Правка списка уходит в обмен, как и остальные поля
   * напоминания. Безопасно вызывается повторно.
   */
  private void addReminderTimes(SQLiteDatabase db) {
    if (!hasColumn(db, "reminder_times"))
      db.execSQL("ALTER TABLE notes ADD COLUMN reminder_times TEXT NOT NULL DEFAULT ''");
    String stamp =
        "UPDATE sync_meta SET seq=seq+1,hlc=max(hlc+1,CAST((julianday('now')-2440587.5)*86400000"
            + " AS INTEGER)) WHERE id=1;";
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS notes_sync_times AFTER UPDATE OF reminder_times ON notes WHEN"
            + " new.sync_seq=old.sync_seq AND new.reminder_times<>old.reminder_times BEGIN "
            + stamp
            + "UPDATE notes SET uid=CASE WHEN new.uid='' THEN lower(hex(randomblob(16))) ELSE"
            + " new.uid END,sync_ts=(SELECT hlc FROM sync_meta WHERE id=1),sync_seq=(SELECT seq"
            + " FROM sync_meta WHERE id=1),sync_from='' WHERE id=new.id;END");
  }

  /**
   * Корзина: время, когда заметку удалили (0 — не в корзине). Заметка в корзине не видна нигде,
   * кроме экрана корзины, и через 30 дней удаляется насовсем (TrashPolicy). Перемещение в корзину
   * уходит в обмен, не сдвигая время правки. Безопасно вызывается повторно.
   */
  private void addTrash(SQLiteDatabase db) {
    if (!hasColumn(db, "trashed"))
      db.execSQL("ALTER TABLE notes ADD COLUMN trashed INTEGER NOT NULL DEFAULT 0");
    String stamp =
        "UPDATE sync_meta SET seq=seq+1,hlc=max(hlc+1,CAST((julianday('now')-2440587.5)*86400000"
            + " AS INTEGER)) WHERE id=1;";
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS notes_sync_trash AFTER UPDATE OF trashed ON notes WHEN"
            + " new.sync_seq=old.sync_seq AND new.trashed<>old.trashed BEGIN "
            + stamp
            + "UPDATE notes SET uid=CASE WHEN new.uid='' THEN lower(hex(randomblob(16))) ELSE"
            + " new.uid END,sync_ts=(SELECT hlc FROM sync_meta WHERE id=1),sync_seq=(SELECT seq"
            + " FROM sync_meta WHERE id=1),sync_from='' WHERE id=new.id;END");
  }

  /**
   * Дерево плана у заметки (JSON PlanTree, пусто — плана нет) и привязка чата к заметке (0 — обычный
   * чат). Безопасно вызывается повторно.
   */
  private void addPlans(SQLiteDatabase db) {
    if (!hasColumn(db, "plan"))
      db.execSQL("ALTER TABLE notes ADD COLUMN plan TEXT NOT NULL DEFAULT ''");
    if (!hasColumn(db, "chats", "note"))
      db.execSQL("ALTER TABLE chats ADD COLUMN note INTEGER NOT NULL DEFAULT 0");
    // Отметка в плане уходит в обмен, но не сдвигает время правки заметки: иначе каждая галочка
    // поднимала бы заметку в списке и заново отправляла её на фоновую проверку ИИ.
    String stamp =
        "UPDATE sync_meta SET seq=seq+1,hlc=max(hlc+1,CAST((julianday('now')-2440587.5)*86400000"
            + " AS INTEGER)) WHERE id=1;";
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS notes_sync_plan AFTER UPDATE OF plan ON notes WHEN"
            + " new.sync_seq=old.sync_seq AND new.plan<>old.plan BEGIN "
            + stamp
            + "UPDATE notes SET uid=CASE WHEN new.uid='' THEN lower(hex(randomblob(16))) ELSE"
            + " new.uid END,sync_ts=(SELECT hlc FROM sync_meta WHERE id=1),sync_seq=(SELECT seq"
            + " FROM sync_meta WHERE id=1),sync_from='' WHERE id=new.id;END");
  }

  /** За сколько минут ИИ предлагает напомнить. Безопасно вызывается повторно. */
  private void addSuggestionLead(SQLiteDatabase db) {
    if (!hasColumn(db, "suggestion_lead"))
      db.execSQL("ALTER TABLE notes ADD COLUMN suggestion_lead INTEGER NOT NULL DEFAULT -1");
  }

  /** Папки: таблица и номер папки у заметки (0 — «Все заметки»). Безопасно вызывается повторно. */
  private void addFolders(SQLiteDatabase db) {
    if (!hasColumn(db, "folder"))
      db.execSQL("ALTER TABLE notes ADD COLUMN folder INTEGER NOT NULL DEFAULT 0");
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS folders(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT"
            + " NULL,key TEXT NOT NULL UNIQUE,created INTEGER NOT NULL,auto INTEGER NOT NULL"
            + " DEFAULT 0)");
    db.execSQL("CREATE INDEX IF NOT EXISTS notes_folder ON notes(folder,updated DESC)");
  }

  /**
   * Синхронизация с компьютерами (docs/SYNC.md): у заметок и папок есть постоянный uid, время и
   * номер изменения; удаления остаются надгробиями. Всё ведут триггеры, поэтому любой путь записи в
   * приложении (редактор, чат, помощник) учитывается без правок кода. Новая заметка остаётся без
   * uid (sync_seq = 0), пока её не пронумеруют при первой отправке или правке: триггер на вставку
   * здесь невозможен, он дважды вставил бы строку в индекс поиска. Метки меняют только
   * изменения содержимого — фоновый разбор ИИ и предложения напоминаний ts не двигают, чтобы не
   * перебивать свежую правку с другого устройства. Безопасно вызывается повторно.
   */
  private void addSync(SQLiteDatabase db) {
    for (String table : new String[] {"notes", "folders"}) {
      if (!hasColumn(db, table, "uid"))
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN uid TEXT NOT NULL DEFAULT ''");
      if (!hasColumn(db, table, "sync_ts"))
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN sync_ts INTEGER NOT NULL DEFAULT 0");
      if (!hasColumn(db, table, "sync_seq"))
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN sync_seq INTEGER NOT NULL DEFAULT 0");
      if (!hasColumn(db, table, "sync_from"))
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN sync_from TEXT NOT NULL DEFAULT ''");
    }
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS tombstones(kind TEXT NOT NULL,uid TEXT NOT NULL,ts INTEGER NOT"
            + " NULL,seq INTEGER NOT NULL,from_peer TEXT NOT NULL DEFAULT '',PRIMARY KEY(kind,uid))");
    db.execSQL("CREATE INDEX IF NOT EXISTS tombstones_seq ON tombstones(seq)");
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS sync_meta(id INTEGER PRIMARY KEY,hlc INTEGER NOT NULL,seq"
            + " INTEGER NOT NULL,device TEXT NOT NULL,name TEXT NOT NULL)");
    byte[] id = new byte[16];
    new java.security.SecureRandom().nextBytes(id);
    StringBuilder device = new StringBuilder();
    for (byte b : id) device.append(String.format(Locale.ROOT, "%02x", b & 0xff));
    db.execSQL(
        "INSERT OR IGNORE INTO sync_meta(id,hlc,seq,device,name) VALUES(1,?,0,?,?)",
        new Object[] {System.currentTimeMillis(), device.toString(), deviceLabel()});
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS sync_peers(device TEXT PRIMARY KEY,name TEXT NOT NULL,platform"
            + " TEXT NOT NULL DEFAULT '',secret BLOB NOT NULL,hosts TEXT NOT NULL DEFAULT"
            + " '[]',sent_seq INTEGER NOT NULL DEFAULT 0,got_seq INTEGER NOT NULL DEFAULT"
            + " 0,last_sync INTEGER NOT NULL DEFAULT 0)");
    // Уже существующие записи получают uid и номера: первый обмен отправит всё.
    db.execSQL(
        "UPDATE notes SET uid=lower(hex(randomblob(16))),sync_ts=updated,sync_seq=id WHERE"
            + " uid=''");
    List<Object[]> folders = new ArrayList<>();
    try (Cursor c = db.rawQuery("SELECT id,key,created FROM folders WHERE uid=''", null)) {
      while (c.moveToNext())
        folders.add(new Object[] {c.getLong(0), c.getString(1), c.getLong(2)});
    }
    for (Object[] f : folders)
      db.execSQL(
          "UPDATE folders SET uid=?,sync_ts=?,sync_seq=? WHERE id=?",
          new Object[] {FolderPolicy.uid((String) f[1]), f[2], f[0], f[0]});
    db.execSQL(
        "UPDATE sync_meta SET seq=max(seq,(SELECT ifnull(max(sync_seq),0) FROM notes),(SELECT"
            + " ifnull(max(sync_seq),0) FROM folders)),hlc=max(hlc,(SELECT ifnull(max(sync_ts),0)"
            + " FROM notes),(SELECT ifnull(max(sync_ts),0) FROM folders)) WHERE id=1");
    db.execSQL("CREATE INDEX IF NOT EXISTS notes_sync_seq ON notes(sync_seq)");
    String stamp =
        "UPDATE sync_meta SET seq=seq+1,hlc=max(hlc+1,CAST((julianday('now')-2440587.5)*86400000"
            + " AS INTEGER)) WHERE id=1;";
    String now = "(SELECT hlc FROM sync_meta WHERE id=1),(SELECT seq FROM sync_meta WHERE id=1)";
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS notes_sync_upd AFTER UPDATE OF"
            + " title,body,created,updated,reminder,repeat_mode,lead_minutes,image,folder ON notes"
            + " WHEN new.sync_seq=old.sync_seq AND (new.title<>old.title OR new.body<>old.body OR"
            + " new.created<>old.created OR new.updated<>old.updated OR new.reminder<>old.reminder"
            + " OR new.repeat_mode<>old.repeat_mode OR new.lead_minutes<>old.lead_minutes OR"
            + " new.image<>old.image OR new.folder<>old.folder) BEGIN "
            + stamp
            + "UPDATE notes SET uid=CASE WHEN new.uid='' THEN lower(hex(randomblob(16))) ELSE"
            + " new.uid END,sync_ts=(SELECT hlc FROM sync_meta WHERE id=1),sync_seq=(SELECT seq"
            + " FROM sync_meta WHERE id=1),sync_from='' WHERE id=new.id;END");
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS notes_sync_del AFTER DELETE ON notes WHEN old.uid<>''"
            + " BEGIN "
            + stamp
            + "INSERT OR REPLACE INTO tombstones(kind,uid,ts,seq,from_peer) VALUES('note',old.uid,"
            + now
            + ",'');END");
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS folders_sync_ins AFTER INSERT ON folders WHEN"
            + " new.sync_seq=0 BEGIN "
            + stamp
            + "UPDATE folders SET uid=CASE WHEN new.uid='' THEN 'f'||lower(hex(randomblob(15))) ELSE"
            + " new.uid END,sync_ts=(SELECT hlc FROM sync_meta WHERE id=1),sync_seq=(SELECT seq FROM"
            + " sync_meta WHERE id=1),sync_from='' WHERE id=new.id;END");
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS folders_sync_upd AFTER UPDATE OF name,key,auto,created ON"
            + " folders WHEN new.sync_seq=old.sync_seq AND (new.name<>old.name OR new.key<>old.key"
            + " OR new.auto<>old.auto OR new.created<>old.created) BEGIN "
            + stamp
            + "UPDATE folders SET sync_ts=(SELECT hlc FROM sync_meta WHERE id=1),sync_seq=(SELECT"
            + " seq FROM sync_meta WHERE id=1),sync_from='' WHERE id=new.id;END");
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS folders_sync_del AFTER DELETE ON folders WHEN"
            + " old.uid<>'' BEGIN "
            + stamp
            + "INSERT OR REPLACE INTO tombstones(kind,uid,ts,seq,from_peer) VALUES('folder',old.uid,"
            + now
            + ",'');END");
  }

  /**
   * Синхронизация чатов, сообщений и стопок (docs/SYNC.md, «Чаты и стопки»). У чатов и сообщений
   * постоянный uid, время и номер изменения; всё ведут триггеры. Стопки (note_groups,
   * note_members) триггеров не имеют: изменившиеся находятся сравнением с таблицей stack_sync при
   * отправке и приёме. Удаление сообщений отдельно не передаётся, они уходят вместе с чатом.
   * Должна вызываться после addSync, безопасна при повторном вызове.
   */
  private void addChatSync(SQLiteDatabase db) {
    for (String table : new String[] {"chats", "messages"}) {
      if (!hasColumn(db, table, "uid"))
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN uid TEXT NOT NULL DEFAULT ''");
      if (!hasColumn(db, table, "sync_ts"))
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN sync_ts INTEGER NOT NULL DEFAULT 0");
      if (!hasColumn(db, table, "sync_seq"))
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN sync_seq INTEGER NOT NULL DEFAULT 0");
      if (!hasColumn(db, table, "sync_from"))
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN sync_from TEXT NOT NULL DEFAULT ''");
      db.execSQL("CREATE INDEX IF NOT EXISTS " + table + "_sync_seq ON " + table + "(sync_seq)");
    }
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS stack_sync(top TEXT PRIMARY KEY,members TEXT NOT NULL,ts"
            + " INTEGER NOT NULL,seq INTEGER NOT NULL,from_peer TEXT NOT NULL DEFAULT ''"
            + ",deleted INTEGER NOT NULL DEFAULT 0)");
    db.execSQL("CREATE INDEX IF NOT EXISTS stack_sync_seq ON stack_sync(seq)");
    // Уже существующие чаты и сообщения получают uid и разные номера выше текущего счётчика.
    db.execSQL(
        "UPDATE chats SET uid=lower(hex(randomblob(16))),sync_ts=created,sync_seq=(SELECT seq FROM"
            + " sync_meta WHERE id=1)+id WHERE uid=''");
    db.execSQL(
        "UPDATE messages SET uid=lower(hex(randomblob(16))),sync_ts=created,sync_seq=(SELECT seq"
            + " FROM sync_meta WHERE id=1)+(SELECT ifnull(max(id),0) FROM chats)+id WHERE uid=''");
    db.execSQL(
        "UPDATE sync_meta SET seq=max(seq,(SELECT ifnull(max(sync_seq),0) FROM chats),(SELECT"
            + " ifnull(max(sync_seq),0) FROM messages)),hlc=max(hlc,(SELECT ifnull(max(sync_ts),0)"
            + " FROM chats),(SELECT ifnull(max(sync_ts),0) FROM messages)) WHERE id=1");
    String stamp =
        "UPDATE sync_meta SET seq=seq+1,hlc=max(hlc+1,CAST((julianday('now')-2440587.5)*86400000"
            + " AS INTEGER)) WHERE id=1;";
    String now = "(SELECT hlc FROM sync_meta WHERE id=1),(SELECT seq FROM sync_meta WHERE id=1)";
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS chats_sync_ins AFTER INSERT ON chats WHEN new.sync_seq=0"
            + " BEGIN "
            + stamp
            + "UPDATE chats SET uid=CASE WHEN new.uid='' THEN lower(hex(randomblob(16))) ELSE"
            + " new.uid END,sync_ts=(SELECT hlc FROM sync_meta WHERE id=1),sync_seq=(SELECT seq FROM"
            + " sync_meta WHERE id=1),sync_from='' WHERE id=new.id;END");
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS chats_sync_upd AFTER UPDATE OF title ON chats WHEN"
            + " new.sync_seq=old.sync_seq AND new.title<>old.title BEGIN "
            + stamp
            + "UPDATE chats SET sync_ts=(SELECT hlc FROM sync_meta WHERE id=1),sync_seq=(SELECT seq"
            + " FROM sync_meta WHERE id=1),sync_from='' WHERE id=new.id;END");
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS chats_sync_del AFTER DELETE ON chats WHEN old.uid<>'' BEGIN "
            + stamp
            + "INSERT OR REPLACE INTO tombstones(kind,uid,ts,seq,from_peer) VALUES('chat',old.uid,"
            + now
            + ",'');END");
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS messages_sync_ins AFTER INSERT ON messages WHEN"
            + " new.sync_seq=0 BEGIN "
            + stamp
            + "UPDATE messages SET uid=CASE WHEN new.uid='' THEN lower(hex(randomblob(16))) ELSE"
            + " new.uid END,sync_ts=(SELECT hlc FROM sync_meta WHERE id=1),sync_seq=(SELECT seq FROM"
            + " sync_meta WHERE id=1),sync_from='' WHERE id=new.id;END");
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS messages_sync_upd AFTER UPDATE OF body,sources,draft ON"
            + " messages WHEN new.sync_seq=old.sync_seq AND (new.body<>old.body OR"
            + " new.sources<>old.sources OR new.draft<>old.draft) BEGIN "
            + stamp
            + "UPDATE messages SET sync_ts=(SELECT hlc FROM sync_meta WHERE id=1),sync_seq=(SELECT"
            + " seq FROM sync_meta WHERE id=1),sync_from='' WHERE id=new.id;END");
  }

  private static String deviceLabel() {
    String model = android.os.Build.MODEL == null ? "" : android.os.Build.MODEL.trim();
    return model.isEmpty() ? "Телефон" : model;
  }

  private boolean hasColumn(SQLiteDatabase db, String table, String column) {
    try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
      while (c.moveToNext()) if (column.equals(c.getString(1))) return true;
    }
    return false;
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
    n.folder = c.getLong(16);
    int leadColumn = c.getColumnIndex("suggestion_lead");
    if (leadColumn >= 0) n.suggestionLead = c.getInt(leadColumn);
    int suggestionTimesColumn = c.getColumnIndex("suggestion_times");
    if (suggestionTimesColumn >= 0) n.suggestionTimes = c.getString(suggestionTimesColumn);
    int trashColumn = c.getColumnIndex("trashed");
    if (trashColumn >= 0) n.trashed = c.getLong(trashColumn);
    int timesColumn = c.getColumnIndex("reminder_times");
    if (timesColumn >= 0) n.reminderTimes = c.getString(timesColumn);
    int attentionColumn = c.getColumnIndex("attention");
    if (attentionColumn >= 0) n.attention = c.getInt(attentionColumn);
    return n;
  }

  public List<Note> notes() {
    List<Note> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT * FROM notes WHERE trashed=0 ORDER BY updated DESC,id DESC", null)) {
      while (c.moveToNext()) out.add(read(c));
    }
    return out;
  }

  @Override
  public int totalNotes() {
    try (Cursor c = getReadableDatabase().rawQuery("SELECT count(*) FROM notes WHERE trashed=0", null)) {
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
    return searchNotes(query, FolderPolicy.ALL);
  }

  public List<Note> searchNotes(String query, long folder) {
    String match = SearchQuery.build(query);
    List<Note> result = new ArrayList<>();
    if (match.length() == 0) return result;
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT n.* FROM notes_fts JOIN notes n ON n.id=notes_fts.rowid WHERE notes_fts"
                    + " MATCH ? AND n.trashed=0 AND (?='0' OR n.folder=?)"
                    + " ORDER BY n.updated DESC,n.id DESC LIMIT 200",
                new String[] {match, Long.toString(folder), Long.toString(folder)})) {
      while (c.moveToNext()) result.add(read(c));
    }
    return result;
  }

  @Override
  public List<Note> candidates(String query) {
    List<Note> result = searchNotes(query);
    Set<Long> ids = new HashSet<>();
    // Этот путь используется фоновым запросом чата: индекс не считается на потоке интерфейса.
    SemanticIndex.Ranked semantic = SemanticIndex.rank(this, query, notes());
    for (Note n : result) {
      ids.add(n.id);
      n.semanticScore = semantic.scores.getOrDefault(n.id, 0.0);
    }
    int added = 0;
    for (Note n : semantic.matches)
      if (ids.add(n.id) && added++ < 24) result.add(n);
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
                  "SELECT * FROM notes WHERE reminder>=? AND trashed=0 ORDER BY reminder LIMIT 16",
                  new String[] {"" + System.currentTimeMillis()})) {
        while (c.moveToNext()) {
          Note n = read(c);
          n.semanticScore = semantic.scores.getOrDefault(n.id, 0.0);
          if (ids.add(n.id)) result.add(n);
        }
      }
    if (result.isEmpty())
      try (Cursor c =
          getReadableDatabase()
              .rawQuery("SELECT * FROM notes WHERE trashed=0 ORDER BY updated DESC LIMIT 24", null)) {
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

  /**
   * Добавляет резервную копию одной транзакцией. Существующие заметки не изменяются; неизменённые
   * совпадения пропускаются. В колоду попадают только новые записи, чтобы импорт не перестраивал
   * колоды, которые пользователь уже изменил. Изображения, чаты и предложения ИИ не импортируются.
   */
  public NotesImport.Result importNotes(NotesImport.Backup backup) {
    SQLiteDatabase db = getWritableDatabase();
    NotesImport.Result result = new NotesImport.Result();
    db.beginTransaction();
    try {
      FolderStore folderStore = new FolderStore(this);
      Map<String, Long> destinationFolders = new HashMap<>();
      Map<Long, String> existingFolderNames = new HashMap<>();
      existingFolderNames.put(FolderPolicy.ALL, "");
      for (FolderStore.Folder folder : folderStore.list()) {
        destinationFolders.put(FolderPolicy.key(folder.name), folder.id);
        existingFolderNames.put(folder.id, folder.name);
      }
      Map<Long, Long> folderMap = new HashMap<>();
      folderMap.put(FolderPolicy.ALL, FolderPolicy.ALL);
      Map<Long, String> sourceFolderNames = new HashMap<>();
      sourceFolderNames.put(FolderPolicy.ALL, "");
      for (NotesImport.Folder folder : backup.folders) {
        String key = FolderPolicy.key(folder.name);
        sourceFolderNames.put(folder.sourceId, folder.name);
        Long destination = destinationFolders.get(key);
        if (destination == null) {
          destination = folderStore.create(folder.name, folder.auto);
          if (destination <= 0) throw new IllegalStateException("Не удалось создать папку");
          destinationFolders.put(key, destination);
          result.foldersAdded++;
        }
        folderMap.put(folder.sourceId, destination);
      }

      Set<String> known = new HashSet<>();
      for (Note note : notes()) {
        NotesImport.Entry entry = NotesImport.existing(note, plan(note.id));
        known.add(
            NotesImport.fingerprint(
                entry, existingFolderNames.getOrDefault(note.folder, "")));
      }
      Map<Long, Long> newNotes = new HashMap<>();
      for (NotesImport.Entry entry : backup.notes) {
        String folderName = sourceFolderNames.getOrDefault(entry.folder, "");
        if (!known.add(NotesImport.fingerprint(entry, folderName))) {
          result.duplicates++;
          continue;
        }
        ContentValues values = new ContentValues();
        values.put("title", entry.title);
        values.put("body", entry.body);
        values.put("created", entry.created);
        values.put("updated", entry.updated);
        values.put("reminder", entry.reminder);
        values.put("title_auto", 0);
        values.put("repeat_mode", entry.reminder > 0 ? entry.repeat : 0);
        values.put("lead_minutes", entry.reminder > 0 ? entry.lead : -1);
        values.put("reminder_times", entry.reminderTimes);
        values.put("folder", folderMap.getOrDefault(entry.folder, FolderPolicy.ALL));
        values.put("plan", entry.plan);
        values.put("metadata", entry.metadata.toString());
        values.put("search_text", Metadata.searchText(entry.metadata));
        long id = db.insertOrThrow("notes", null, values);
        newNotes.put(entry.sourceId, id);
        result.noteIds.add(id);
        result.added++;
      }

      for (NotesImport.Group group : backup.groups) {
        Long top = newNotes.get(group.top);
        if (top == null) continue;
        List<Long> members = new ArrayList<>();
        for (long source : group.members) {
          Long note = newNotes.get(source);
          if (note != null) members.add(note);
        }
        if (members.isEmpty()) continue;
        ContentValues values = new ContentValues();
        values.put("top", top);
        values.put("expanded", group.expanded ? 1 : 0);
        db.insertOrThrow("note_groups", null, values);
        for (long member : members) {
          ContentValues link = new ContentValues();
          link.put("note", member);
          link.put("top", top);
          db.insertOrThrow("note_members", null, link);
        }
        result.groupsAdded++;
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    return result;
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
                "SELECT count(*) FROM notes WHERE suggestion>? AND trashed=0",
                new String[] {"" + System.currentTimeMillis()})) {
      c.moveToFirst();
      return c.getInt(0);
    }
  }

  /** Как {@link #save(long, String, String, long, int, int, String)}; список дат берётся у заметки. */
  public long save(
      long id, String title, String body, long reminder, int repeat, int lead) {
    Note existing = id == 0 ? null : note(id);
    return save(id, title, body, reminder, repeat, lead, existing == null ? "" : existing.reminderTimes);
  }

  /**
   * Сохраняет заметку. {@code times} — все даты серии {@link ReminderPolicy#REPEAT_LIST}; у других
   * повторов и без напоминания список очищается.
   */
  public long save(
      long id, String title, String body, long reminder, int repeat, int lead, String times) {
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
    v.put("suggestion_lead", -1);
    v.put("suggestion_times", "");
    v.put("ai_failed", 0);
    v.put("title", Note.autoTitle(title, body));
    v.put("body", body.trim());
    v.put("updated", now);
    v.put("reminder", reminder);
    v.put("repeat_mode", reminder == 0 ? 0 : repeat);
    v.put("lead_minutes", reminder == 0 ? -1 : lead);
    v.put(
        "reminder_times",
        reminder != 0 && repeat == ReminderPolicy.REPEAT_LIST
            ? ReminderPolicy.encodeTimes(ReminderPolicy.parseTimes(times))
            : "");
    JSONObject metadata =
        Metadata.base(
            Note.autoTitle(title, body), body.trim(), old == null ? now : old.created, now);
    v.put("metadata", metadata.toString());
    v.put("search_text", Metadata.searchText(metadata));
    if (id == 0) {
      v.put("created", now);
      // Эксперимент «баллы внимания»: стартовый запас назначается только новым заметкам и
      // только при включённой настройке; старые и пришедшие по обмену остаются на -1.
      if (attentionEnabled()) {
        v.put("attention", AttentionPolicy.initial(reminder != 0));
        v.put("attention_at", now);
      }
      // Новая заметка всегда попадает в «Все заметки»: папку явно выбирает только редактор.
      return getWritableDatabase().insertOrThrow("notes", null, v);
    }
    if (getWritableDatabase().update("notes", v, "id=?", new String[] {"" + id}) != 1)
      throw new IllegalStateException("Заметка уже удалена");
    return id;
  }

  /** Удаляет заметку насовсем, мимо корзины (слияние видео, удаление из корзины). */
  public void delete(long id) {
    getWritableDatabase().delete("notes", "id=?", new String[] {"" + id});
    new ManagerStore(this).cleanup();
  }

  /**
   * Кладёт заметку в корзину и возвращает номера всех заметок, ушедших в корзину. Исходная заметка
   * колоды выходит из неё. У верхней заметки колоды {@code wholeStack} решает: колода целиком (все
   * исходные получают то же время и вернутся вместе с верхней) или только верхняя, а исходные
   * возвращаются в список отдельными заметками.
   */
  public List<Long> trash(long id, boolean wholeStack) {
    SQLiteDatabase db = getWritableDatabase();
    List<Long> out = new ArrayList<>();
    db.beginTransaction();
    try {
      Note n = note(id);
      if (n == null || n.trashed > 0) return out;
      ManagerStore manager = new ManagerStore(this);
      long top = manager.top(id), now = System.currentTimeMillis();
      if (top != 0 && top != id) {
        db.delete("note_members", "note=?", new String[] {"" + id});
        manager.cleanup();
      } else if (top == id && wholeStack) {
        for (long member : manager.members(id)) {
          Note m = note(member);
          if (m != null && m.trashed == 0) out.add(member);
        }
      } else if (top == id) db.delete("note_groups", "top=?", new String[] {"" + id});
      out.add(0, id);
      for (long target : out)
        db.execSQL("UPDATE notes SET trashed=? WHERE id=? AND trashed=0", new Object[] {now, target});
      db.setTransactionSuccessful();
      return out;
    } finally {
      db.endTransaction();
    }
  }

  /** Возвращает заметку из корзины; верхняя заметка колоды возвращается вместе с исходными. */
  public List<Long> restore(long id) {
    SQLiteDatabase db = getWritableDatabase();
    List<Long> out = new ArrayList<>();
    db.beginTransaction();
    try {
      Note n = note(id);
      if (n == null || n.trashed == 0) return out;
      out.add(id);
      for (long member : new ManagerStore(this).members(id)) {
        Note m = note(member);
        if (m != null && m.trashed == n.trashed) out.add(member);
      }
      for (long target : out)
        db.execSQL("UPDATE notes SET trashed=0 WHERE id=?", new Object[] {target});
      db.setTransactionSuccessful();
      return out;
    } finally {
      db.endTransaction();
    }
  }

  /**
   * Заметки корзины, новые сверху. Исходные заметки колоды, удалённой целиком, не показываются
   * отдельно: они вернутся и удалятся вместе с верхней.
   */
  public List<Note> trashed() {
    List<Note> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT * FROM notes WHERE trashed>0 AND id NOT IN (SELECT m.note FROM note_members"
                    + " m JOIN notes t ON t.id=m.top WHERE t.trashed>0) ORDER BY trashed DESC,id"
                    + " DESC",
                null)) {
      while (c.moveToNext()) out.add(read(c));
    }
    return out;
  }

  public int trashCount() {
    return trashed().size();
  }

  /** Удаляет заметку из корзины насовсем (с исходными колоды); возвращает удалённые заметки. */
  public List<Note> purge(long id) {
    SQLiteDatabase db = getWritableDatabase();
    List<Note> out = new ArrayList<>();
    db.beginTransaction();
    try {
      Note n = note(id);
      if (n == null || n.trashed == 0) return out;
      for (long member : new ManagerStore(this).members(id)) {
        Note m = note(member);
        if (m != null && m.trashed == n.trashed) out.add(m);
      }
      out.add(0, n);
      for (Note gone : out) delete(gone.id);
      db.setTransactionSuccessful();
      return out;
    } finally {
      db.endTransaction();
    }
  }

  /** Удаляет насовсем всё, что пролежало в корзине 30 дней ({@code all} — всю корзину сразу). */
  public List<Note> purgeTrash(long now, boolean all) {
    List<Note> out = new ArrayList<>();
    for (Note n : trashed())
      if (all || TrashPolicy.expired(n.trashed, now)) out.addAll(purge(n.id));
    // Исходные, оставшиеся в корзине без верхней (например, после обмена), — по своему сроку.
    List<Long> loose = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT id,trashed FROM notes WHERE trashed>0", null)) {
      while (c.moveToNext())
        if (all || TrashPolicy.expired(c.getLong(1), now)) loose.add(c.getLong(0));
    }
    for (long id : loose) {
      Note n = note(id);
      if (n != null) {
        delete(id);
        out.add(n);
      }
    }
    return out;
  }

  /** Имена всех файлов изображений, на которые ссылаются заметки (в поле может быть список). */
  public Set<String> imageNames() {
    Set<String> out = new HashSet<>();
    try (Cursor c = getReadableDatabase().rawQuery("SELECT DISTINCT image FROM notes WHERE image<>''", null)) {
      while (c.moveToNext()) out.addAll(ImageList.parse(c.getString(0)));
    }
    return out;
  }

  /** Картинки, приложенные к сообщениям чатов: чат помнит их, пока он существует. */
  public Set<String> chatImageNames() {
    Set<String> out = new HashSet<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT body FROM messages WHERE instr(body,'[[img:')>0", null)) {
      while (c.moveToNext()) out.addAll(ChatImages.split(c.getString(0)).images);
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
    proposal.lead = result.lead;
    proposal.metadata = result.metadata;
    applyAnalysis(note(id), proposal);
    getWritableDatabase()
        .execSQL("UPDATE notes SET ai_checked=updated WHERE id=?", new Object[] {id});
    return id;
  }

  /** Сохраняет сводку видео как заметку с темами для поиска; без платного фонового разбора. */
  public long saveVideo(VideoAnalysis.Result result) {
    long id = save(0, result.title, result.body, 0);
    applyMetadata(id, note(id).updated, result.metadata);
    getWritableDatabase()
        .execSQL("UPDATE notes SET ai_checked=updated WHERE id=?", new Object[] {id});
    return id;
  }

  /** Заметки со ссылкой на видео, кроме входящих в стопки: кандидаты для общей заметки. */
  public List<Note> videoNotes() {
    List<Note> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT * FROM notes WHERE instr(body,?)>0 AND trashed=0"
                    + " AND id NOT IN (SELECT top FROM note_groups)"
                    + " AND id NOT IN (SELECT note FROM note_members)"
                    + " ORDER BY updated DESC LIMIT 100",
                new String[] {"Видео: https://www.youtube.com/watch?v="})) {
      while (c.moveToNext()) out.add(read(c));
    }
    return out;
  }

  /**
   * Переносит сводку видео {@code src} в заметку {@code dst} с готовым текстом и удаляет {@code
   * src}. Всё или ничего: если любая из заметок изменилась после чтения, ничего не меняется.
   */
  public boolean mergeVideo(
      long src, long srcRevision, long dst, long dstRevision, String title, String body) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      Note from = note(src), into = note(dst);
      if (from == null
          || into == null
          || from.updated != srcRevision
          || into.updated != dstRevision
          || src == dst) return false;
      save(dst, title, body, into.reminder, into.repeat, into.lead);
      Note saved = note(dst);
      applyMetadata(dst, saved.updated, Metadata.read(from.metadata).optJSONObject("ai"));
      db.execSQL("UPDATE notes SET ai_checked=updated WHERE id=?", new Object[] {dst});
      delete(src);
      db.setTransactionSuccessful();
      return true;
    } finally {
      db.endTransaction();
    }
  }

  /** Номер самой ранней заметки, в тексте которой есть эта строка, или 0. */
  public long noteIdContaining(String text) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id FROM notes WHERE instr(body,?)>0 AND trashed=0 ORDER BY id LIMIT 1",
                new String[] {text})) {
      return c.moveToFirst() ? c.getLong(0) : 0;
    }
  }

  public List<Note> pendingAnalysis() {
    List<Note> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT * FROM notes WHERE ai_checked<>updated AND trashed=0"
                    // Эксперимент: заметка без баллов внимания помощника не проверяется.
                    + (attentionEnabled() ? " AND attention<>0" : "")
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
    boolean named = note.autoTitle && !result.title.isEmpty();
    if (named) {
      v.put("title", result.title);
      v.put("title_auto", 0);
    }
    if (note.reminder == 0 && result.reminder > System.currentTimeMillis()) {
      v.put("suggestion", result.reminder);
      v.put("suggestion_reason", result.reason);
      v.put("suggestion_lead", result.lead);
      v.put("suggestion_times", ReminderPolicy.encodeTimes(result.times));
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
    boolean applied =
        getWritableDatabase()
                .update(
                    "notes",
                    v,
                    "id=? AND updated=?",
                    new String[] {"" + note.id, "" + note.updated})
            == 1;
    // Название — единственное, что агент делает сам; пишем в журнал, чтобы его можно было вернуть.
    if (applied && named && !result.title.equals(note.title))
      try {
        AgentLog.record(
            getWritableDatabase(),
            AgentJournal.NAME,
            AgentJournal.nameData(note.id, result.title, note.title));
      } catch (JSONException ignored) {
      }
    return applied;
  }

  /**
   * «Отменить» для названия от агента: возвращает прежнее, если пользователь не переименовал
   * заметку сам. Прежнее название становится авторским, чтобы проверка не поставила новое снова.
   * Время правки не меняется, очередь проверки считает новое состояние уже просмотренным.
   */
  public boolean undoAgentTitle(long id, String expected, String before) {
    Note note = note(id);
    if (note == null || note.trashed > 0 || !note.title.equals(expected)) return false;
    String title = Note.autoTitle(before, note.body);
    JSONObject metadata = Metadata.base(title, note.body, note.created, note.updated);
    Metadata.withAI(metadata, Metadata.read(note.metadata).optJSONObject("ai"), note.updated);
    ContentValues v = new ContentValues();
    v.put("title", title);
    v.put("title_auto", 0);
    v.put("metadata", metadata.toString());
    v.put("search_text", Metadata.searchText(metadata));
    SQLiteDatabase db = getWritableDatabase();
    if (db.update("notes", v, "id=? AND title=?", new String[] {"" + id, expected}) != 1)
      return false;
    Note after = note(id);
    db.execSQL(
        "UPDATE manager_review SET revision=? WHERE note=?",
        new Object[] {ManagerAnalysis.fingerprint(after), id});
    return true;
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

  /** Для CommandExecutor: вызывается только внутри уже открытой общей транзакции. */
  public boolean applyMetadataInTransaction(long id, long revision, JSONObject enrichment) {
    return applyMetadataLocked(id, revision, enrichment);
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
    v.put("suggestion_lead", -1);
    v.put("suggestion_times", "");
    getWritableDatabase().update("notes", v, "id=?", new String[] {"" + id});
  }

  /** Эксперимент «баллы внимания помощника» (docs/ATTENTION-POINTS.md) включён в настройках. */
  public boolean attentionEnabled() {
    return appContext.getSharedPreferences("settings", 0).getBoolean("attentionPoints", false);
  }

  /**
   * Изменяет баллы внимания заметки на {@code delta} с гейтом «не чаще раза в {@link
   * AttentionPolicy#GAP} на знак»: минусы и плюсы ограничиваются раздельно (последний минус —
   * {@code attention_down}, последний плюс — {@code attention_at}), чтобы открытие заметки не
   * глушило «Отклонить» и наоборот. Ничего не делает при выключенном эксперименте и у заметок без
   * назначенных баллов. Возвращает true, если баллы изменились.
   */
  public boolean attentionAdjust(long id, int delta) {
    if (delta == 0 || !attentionEnabled()) return false;
    SQLiteDatabase db = getWritableDatabase();
    long now = System.currentTimeMillis();
    try (Cursor c =
        db.rawQuery(
            "SELECT attention,attention_at,attention_down FROM notes WHERE id=?",
            new String[] {"" + id})) {
      if (!c.moveToFirst()) return false;
      int attention = c.getInt(0);
      long lastSameSign = delta < 0 ? c.getLong(2) : c.getLong(1);
      if (!AttentionPolicy.due(attention, lastSameSign, now)) return false;
      int next = AttentionPolicy.apply(attention, delta);
      ContentValues v = new ContentValues();
      v.put("attention", next);
      v.put(delta < 0 ? "attention_down" : "attention_at", now);
      return db.update("notes", v, "id=?", new String[] {"" + id}) == 1;
    }
  }

  /**
   * Награда без гейта за сильный сигнал интереса: принятое предложение ИИ (+2), ответ на вопрос
   * помощника (+1). Гейты не двигает: открытие после ответа по-прежнему может дать свой балл.
   */
  public boolean attentionReward(long id, int delta) {
    if (delta <= 0 || !attentionEnabled()) return false;
    SQLiteDatabase db = getWritableDatabase();
    try (Cursor c =
        db.rawQuery("SELECT attention FROM notes WHERE id=?", new String[] {"" + id})) {
      if (!c.moveToFirst()) return false;
      int attention = c.getInt(0);
      if (attention < 0) return false;
      int next = AttentionPolicy.apply(attention, delta);
      if (next == attention) return false;
      ContentValues v = new ContentValues();
      v.put("attention", next);
      return db.update("notes", v, "id=?", new String[] {"" + id}) == 1;
    }
  }

  /**
   * Разовый бонус первой классификации проверки (событие, большая задача, план). Вызывается из
   * {@link ManagerStore#finishReview}, только когда класс появился впервые; без гейта.
   */
  public void attentionClassBonus(long id, String kind) {
    if (!attentionEnabled()) return;
    SQLiteDatabase db = getWritableDatabase();
    try (Cursor c =
        db.rawQuery("SELECT attention FROM notes WHERE id=?", new String[] {"" + id})) {
      if (!c.moveToFirst()) return;
      int attention = c.getInt(0);
      if (attention < 0) return;
      int next = AttentionPolicy.classBonus(attention, kind, !plan(id).isEmpty());
      if (next == attention) return;
      ContentValues v = new ContentValues();
      v.put("attention", next);
      db.update("notes", v, "id=?", new String[] {"" + id});
    }
  }

  /**
   * Регулярные списания, вызывается при запуске приложения: неделя без касаний — минус балл (не
   * чаще раза в неделю); вопрос помощника без ответа три дня — минус балл (один раз на вопрос,
   * отметка в настройках). Только при включённом эксперименте.
   */
  public void attentionSweep(long now) {
    if (!attentionEnabled()) return;
    SQLiteDatabase db = getWritableDatabase();
    long weekAgo = now - AttentionPolicy.WEEK;
    db.execSQL(
        "UPDATE notes SET attention=attention-1,attention_down=? WHERE attention>0 AND trashed=0"
            + " AND attention_down<? AND attention_at<? AND updated<? AND created<?",
        new Object[] {now, weekAgo, weekAgo, weekAgo, weekAgo});
    SharedPreferences prefs = appContext.getSharedPreferences("settings", 0);
    List<Object[]> stale = new ArrayList<>();
    try (Cursor c =
        db.rawQuery(
            "SELECT id,data FROM manager_proposals WHERE state='pending' AND identity LIKE"
                + " 'clarify|%' AND created<?",
            new String[] {"" + (now - AttentionPolicy.QUESTION_WAIT)})) {
      while (c.moveToNext()) stale.add(new Object[] {c.getLong(0), c.getString(1)});
    }
    for (Object[] row : stale) {
      String key = "attentionQ." + row[0];
      if (prefs.getBoolean(key, false)) continue;
      try {
        long note = ManagerStore.firstSource(new JSONObject((String) row[1]));
        if (note > 0 && attentionAdjust(note, -1)) prefs.edit().putBoolean(key, true).apply();
      } catch (Exception ignored) {
        // повреждённый вопрос пропускаем
      }
    }
  }

  public void retryFailed() {
    getWritableDatabase().execSQL("UPDATE notes SET ai_checked=0,ai_failed=0 WHERE ai_failed=1");
  }

  public long newChat(String title) {
    return newChat(title, ChatOrigin.USER, 0);
  }

  /** Новый чат с происхождением ({@link ChatOrigin}) и заметкой (0 — без заметки) одной записью. */
  public long newChat(String title, String origin, long note) {
    ContentValues v = new ContentValues();
    v.put("title", title);
    v.put("created", System.currentTimeMillis());
    v.put("origin", ChatOrigin.clean(origin));
    v.put("note", note);
    return getWritableDatabase().insertOrThrow("chats", null, v);
  }

  /** Кто начал чат: {@link ChatOrigin#USER}, {@link ChatOrigin#AGENT} или {@link ChatOrigin#NOTE}. */
  public String chatOrigin(long chat) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT origin FROM chats WHERE id=?", new String[] {"" + chat})) {
      return c.moveToFirst() ? ChatOrigin.clean(c.getString(0)) : ChatOrigin.USER;
    }
  }

  /** Сохранённый план заметки (JSON PlanTree) или пустая строка. */
  public String plan(long note) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT plan FROM notes WHERE id=?", new String[] {"" + note})) {
      return c.moveToFirst() ? c.getString(0) : "";
    }
  }

  /** Данные планов на экране «Сегодня» и для ручной сводки; схема не меняется. */
  public List<DayDigest.PlanSource> dayPlanSources(long start, long end) {
    List<DayDigest.PlanSource> out = new ArrayList<>();
    try (Cursor rows = getReadableDatabase().rawQuery(
        "SELECT id,title,body,plan FROM notes WHERE trashed=0 AND plan<>'' ORDER BY updated DESC,id DESC", null)) {
      while (rows.moveToNext()) {
        List<PlanTree.Node> steps = PlanTree.parse(rows.getString(3)).scheduledSteps(start, end);
        if (!steps.isEmpty())
          out.add(new DayDigest.PlanSource(rows.getLong(0), rows.getString(1), rows.getString(2), steps));
      }
    }
    return out;
  }

  public List<Note> dayReminders(long start, long end) {
    List<Note> notes = new ArrayList<>();
    try (Cursor c = getReadableDatabase().rawQuery(
        "SELECT * FROM notes WHERE trashed=0 AND ((reminder>=? AND reminder<?) OR (repeat_mode=? AND reminder>0)) ORDER BY reminder,id",
        new String[]{"" + start, "" + end, "" + ReminderPolicy.REPEAT_LIST})) {
      while (c.moveToNext()) notes.add(read(c));
    }
    return notes;
  }

  /** Одна транзакция с повторной проверкой расписания после выбора времени. */
  public Note changeSchedule(Note expected, long reminder, String times) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      Note n = note(expected.id);
      if (n == null || n.trashed != 0 || n.reminder != expected.reminder
          || n.repeat != expected.repeat || !n.reminderTimes.equals(expected.reminderTimes)) return null;
      ContentValues schedule = new ContentValues();
      schedule.put("reminder", reminder);
      schedule.put("reminder_times", times);
      db.update("notes", schedule, "id=?", new String[]{Long.toString(n.id)});
      Note changed = note(n.id);
      db.setTransactionSuccessful();
      return changed;
    } finally { db.endTransaction(); }
  }

  public boolean markScheduledStep(long id, PlanTree.Node shown) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      Note n = note(id);
      if (n == null || n.trashed != 0) return false;
      PlanTree plan = PlanTree.parse(plan(id));
      PlanTree.Node current = plan.find(shown.id);
      if (current == null || !current.text.equals(shown.text) || !current.checkable()) return false;
      current.done = !shown.done;
      savePlan(id, plan.toJson());
      db.setTransactionSuccessful();
      return true;
    } finally { db.endTransaction(); }
  }

  /**
   * Записывает план. Время правки заметки не трогаем; в обмен заметку отправит триггер
   * {@code notes_sync_plan}.
   */
  public void savePlan(long note, String plan) {
    ContentValues v = new ContentValues();
    v.put("plan", plan == null ? "" : plan);
    if (getWritableDatabase().update("notes", v, "id=?", new String[] {"" + note}) != 1)
      throw new IllegalStateException("Заметка уже удалена");
  }

  /**
   * «Применить» в чате по заметке: правка (название, дописать, даты) и/или план из черновика
   * сообщения одной транзакцией, черновик закрывается. Отметки плана, поставленные после ответа
   * агента, сохраняются. Возвращает заметку до правки — по ней вызывающий переставит будильник.
   */
  public Note applyNoteDraft(long message, long noteId, NoteEdit edit, PlanTree plan) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      Note old = note(noteId);
      if (old == null || old.trashed > 0) throw new IllegalStateException("Заметку удалили");
      if (edit != null) {
        NoteEdit.Result r =
            edit.apply(
                old.title,
                old.body,
                old.reminder,
                old.repeat,
                old.reminderTimes,
                System.currentTimeMillis());
        boolean changed =
            !r.title.equals(old.title)
                || !r.body.equals(old.body.trim())
                || r.reminder != old.reminder
                || r.repeat != old.repeat
                || !r.times.equals(old.reminderTimes);
        if (changed) save(noteId, r.title, r.body, r.reminder, r.repeat, old.lead, r.times);
      }
      if (plan != null) {
        plan.keepDone(PlanTree.parse(plan(noteId)));
        savePlan(noteId, plan.toJson());
      }
      dismissDraft(message);
      db.setTransactionSuccessful();
      return old;
    } finally {
      db.endTransaction();
    }
  }

  /** Отметки «План 3/8» всех заметок с планом одним запросом — для списка заметок. */
  public Map<Long, String> planBadges() {
    Map<Long, String> out = new HashMap<>();
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT id,plan FROM notes WHERE plan<>''", null)) {
      while (c.moveToNext()) {
        String badge = PlanTree.parse(c.getString(1)).badge();
        if (!badge.isEmpty()) out.put(c.getLong(0), badge);
      }
    }
    return out;
  }

  /**
   * Чат по заметке, если он уже есть, иначе 0. Чаты «Как продвигается?» помощника тоже привязаны к
   * заметке, но это не её чат.
   */
  public long noteChat(long note) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id FROM chats WHERE note=? AND origin<>'agent' ORDER BY id LIMIT 1",
                new String[] {"" + note})) {
      return c.moveToFirst() ? c.getLong(0) : 0;
    }
  }

  public long newNoteChat(long note, String title) {
    return newChat(title, ChatOrigin.NOTE, note);
  }

  /** Заметка, к которой привязан чат, или 0. */
  public long chatNote(long chat) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT note FROM chats WHERE id=?", new String[] {"" + chat})) {
      return c.moveToFirst() ? c.getLong(0) : 0;
    }
  }

  public List<String[]> chats() {
    List<String[]> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT id,title,created,note,origin FROM chats ORDER BY id DESC", null)) {
      while (c.moveToNext())
        out.add(
            new String[] {
              c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)
            });
    }
    return out;
  }

  /**
   * Лента чатов: каждый чат с последним сообщением (текст, роль, черновик), временем последней
   * активности и числом ответов пользователя; свежие сверху.
   */
  public List<ChatFeed.Row> chatFeed() {
    List<ChatFeed.Row> out = new ArrayList<>();
    String last = "FROM messages m WHERE m.chat=c.id ORDER BY m.created DESC, m.id DESC LIMIT 1";
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT c.id,c.title,c.created,c.note,c.origin,"
                    + "(SELECT body " + last + "),(SELECT role " + last + "),(SELECT draft " + last
                    + "),COALESCE((SELECT MAX(created) FROM messages m WHERE m.chat=c.id),c.created) AS t,"
                    + "(SELECT count(*) FROM messages m WHERE m.chat=c.id AND m.role='user')"
                    + " FROM chats c ORDER BY t DESC, c.id DESC",
                null)) {
      while (c.moveToNext()) {
        ChatFeed.Row r = new ChatFeed.Row();
        r.id = c.getLong(0);
        r.title = c.isNull(1) ? "" : c.getString(1);
        r.created = c.getLong(2);
        r.note = c.isNull(3) ? 0 : c.getLong(3);
        r.origin = c.isNull(4) ? ChatOrigin.USER : c.getString(4);
        r.body = c.isNull(5) ? "" : c.getString(5);
        r.role = c.isNull(6) ? "" : c.getString(6);
        r.draft = c.isNull(7) ? "" : c.getString(7);
        r.time = c.getLong(8);
        r.userMessages = c.getInt(9);
        out.add(r);
      }
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
                "SELECT role,body,sources,id,draft FROM messages WHERE chat=? ORDER BY created,id",
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

  /** Время последнего сообщения пользователя в любом чате или 0. */
  public long lastUserMessage() {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT MAX(created) FROM messages WHERE role='user'", null)) {
      return c.moveToFirst() ? c.getLong(0) : 0;
    }
  }

  /** Все ответы пользователя в чате одной строкой (для памяти помощника) или пустая строка. */
  public String userText(long chat) {
    StringBuilder out = new StringBuilder();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT body FROM messages WHERE chat=? AND role='user' ORDER BY created,id",
                new String[] {"" + chat})) {
      while (c.moveToNext()) out.append(out.length() == 0 ? "" : "; ").append(c.getString(0));
    }
    return out.toString();
  }

  /** Первое сообщение чата — вопрос, который помощник задал сам. */
  public String firstMessage(long chat) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT body FROM messages WHERE chat=? ORDER BY created,id LIMIT 1",
                new String[] {"" + chat})) {
      return c.moveToFirst() ? c.getString(0) : "";
    }
  }

  /** Сколько чатов помощник начал с момента {@code since} (лимит «не больше 4 в день»). */
  public int agentChatsSince(long since) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT count(*) FROM chats WHERE origin='agent' AND created>=?",
                new String[] {"" + since})) {
      c.moveToFirst();
      return c.getInt(0);
    }
  }

  /**
   * «Как продвигается?»: чат помощника, привязанный к заметке, с вопросом и вариантами ответа
   * кнопками; черновик {@code {"kind":"progress","note":id}} говорит, что варианты обрабатываются
   * без модели. Одна транзакция.
   */
  public long newProgressChat(long note, String title, String question) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      long id = newChat(title, ChatOrigin.AGENT, note);
      PlanChat.Reply ask = new PlanChat.Reply();
      ask.say = question;
      ask.options.addAll(ProgressCheck.OPTIONS);
      message(
          id,
          "assistant",
          PlanChat.store(ask),
          new JSONArray(),
          new JSONObject().put("kind", "progress").put("note", note));
      db.setTransactionSuccessful();
      return id;
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    } finally {
      db.endTransaction();
    }
  }

  /** Начинает чат с вопроса помощника одной транзакцией. */
  public long newAssistantChat(String title, String question) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      long id = newChat(title, ChatOrigin.AGENT, 0);
      message(id, "assistant", question, new JSONArray());
      db.setTransactionSuccessful();
      return id;
    } finally {
      db.endTransaction();
    }
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

  /** Как принять пачку дат: по заметке на дату, одна новая заметка со всеми датами, в исходную. */
  public static final int BATCH_SEPARATE = 0, BATCH_ONE = 1, BATCH_INTO = 2;

  /**
   * Принимает предложенные даты из сообщения, кроме отмеченных как лишние (номера в {@code skip}):
   * {@link #BATCH_SEPARATE} — по заметке с напоминанием на каждую дату; {@link #BATCH_ONE} — одна
   * новая заметка со всеми датами (серия по списку); {@link #BATCH_INTO} — даты добавляются в
   * заметку, из которой пришёл запрос. Одна транзакция: либо всё, либо ничего. Возвращает созданные
   * или изменённые заметки; будильники ставит вызывающий.
   */
  public List<Note> acceptReminders(long message, java.util.Set<Integer> skip, int mode) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try (Cursor c =
        db.rawQuery(
            "SELECT sources,draft,body FROM messages WHERE id=?", new String[] {"" + message})) {
      if (!c.moveToFirst() || c.getString(1).isEmpty())
        throw new IllegalStateException("Предложение уже обработано");
      JSONObject draft = new JSONObject(c.getString(1));
      if (!ReminderBatch.isBatch(draft))
        throw new IllegalStateException("Это не предложение напоминаний");
      List<ReminderBatch.Item> items = ReminderBatch.items(draft);
      JSONArray sources = new JSONArray(c.getString(0));
      List<Note> created = new ArrayList<>();
      long now = System.currentTimeMillis();
      List<ReminderBatch.Item> chosen = new ArrayList<>();
      for (int i = 0; i < items.size(); i++)
        if (!skip.contains(i) && items.get(i).at > now) chosen.add(items.get(i));
      if (chosen.isEmpty()) throw new IllegalStateException("Нет напоминаний с будущим временем");
      String outcome;
      if (mode == BATCH_INTO) {
        Note target = note(ReminderBatch.source(draft));
        if (target == null || target.trashed > 0 || !ReminderBatch.canMerge(target.repeat))
          throw new IllegalStateException("Заметку удалили или у неё свой повтор");
        long[] times =
            ReminderBatch.mergedTimes(
                target.reminder, target.repeat, target.reminderTimes, ReminderBatch.times(chosen), now);
        boolean list = times.length > 1;
        save(
            target.id,
            target.title,
            target.body,
            list ? ReminderPolicy.currentInList(times, now) : times[0],
            list ? ReminderPolicy.REPEAT_LIST : ReminderPolicy.REPEAT_NONE,
            target.lead,
            ReminderPolicy.encodeTimes(times));
        created.add(note(target.id));
        outcome = "Добавлено дат в заметку: " + chosen.size() + ".";
      } else if (mode == BATCH_ONE && chosen.size() > 1) {
        String[] text = ReminderBatch.combined(chosen, java.time.ZoneId.systemDefault());
        long[] times = ReminderBatch.times(chosen);
        long id =
            save(
                0,
                text[0],
                text[1],
                ReminderPolicy.currentInList(times, now),
                ReminderPolicy.REPEAT_LIST,
                -1,
                ReminderPolicy.encodeTimes(times));
        created.add(note(id));
        outcome = "Создана заметка с напоминаниями: " + ReminderPolicy.dateCount(times.length) + ".";
      } else {
        for (ReminderBatch.Item item : chosen) {
          long id = save(0, item.title, item.body.isEmpty() ? item.title : item.body, item.at);
          created.add(note(id));
        }
        outcome = "Создано напоминаний: " + created.size() + ".";
      }
      for (Note note : created)
        sources.put(new JSONObject().put("id", note.id).put("title", note.title));
      ContentValues v = new ContentValues();
      v.put("draft", "");
      v.put("sources", sources.toString());
      v.put("body", c.getString(2) + "\n\n" + outcome);
      db.update("messages", v, "id=?", new String[] {"" + message});
      db.setTransactionSuccessful();
      return created;
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    } finally {
      db.endTransaction();
    }
  }

  /** Выбор в предпросмотре переживает render/поворот, но не воскрешает уже закрытый черновик. */
  public void updateDraft(long message, JSONObject draft) {
    ContentValues v = new ContentValues();
    v.put("draft", draft.toString());
    if (getWritableDatabase().update("messages", v,
        "id=? AND role='assistant' AND draft<>''", new String[] {"" + message}) != 1)
      throw new IllegalStateException("Черновик уже применён или закрыт");
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
