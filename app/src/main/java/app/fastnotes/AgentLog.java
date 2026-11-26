package app.fastnotes;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;
import org.json.*;

/**
 * Журнал агента: таблица {@code agent_log} (SQLite 14). Каждое применённое действие помощника
 * пишется в той же транзакции, что и само действие; «Отменить» работает сутки ({@link
 * AgentJournal#UNDO_WINDOW}). Журнал только на этом устройстве, в обмен не идёт.
 */
final class AgentLog {
  private AgentLog() {}

  static void create(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS agent_log(id INTEGER PRIMARY KEY AUTOINCREMENT,created INTEGER"
            + " NOT NULL,action TEXT NOT NULL,data TEXT NOT NULL,undo TEXT NOT NULL DEFAULT"
            + " '{}',state TEXT NOT NULL DEFAULT 'done')");
    db.execSQL("CREATE INDEX IF NOT EXISTS agent_log_created ON agent_log(created DESC)");
  }

  static long record(SQLiteDatabase db, String action, JSONObject data) {
    ContentValues v = new ContentValues();
    v.put("created", System.currentTimeMillis());
    v.put("action", action);
    v.put("data", data.toString());
    v.put("undo", "{}");
    v.put("state", AgentJournal.DONE);
    return db.insert("agent_log", null, v);
  }

  static final class Entry {
    long id, created;
    String action, state;
    JSONObject data;

    String text() {
      return AgentJournal.describe(action, data);
    }

    boolean undoable(long now) {
      return AgentJournal.canUndo(state, created, now);
    }
  }

  /** Последние записи, новые сверху. {@code sinceMs} > 0 — только моложе этого возраста. */
  static List<Entry> recent(Store store, int limit, long sinceMs) {
    List<Entry> out = new ArrayList<>();
    long from = sinceMs > 0 ? System.currentTimeMillis() - sinceMs : 0;
    try (Cursor c =
        store
            .getReadableDatabase()
            .rawQuery(
                "SELECT id,created,action,data,state FROM agent_log WHERE created>=? ORDER BY"
                    + " created DESC,id DESC LIMIT ?",
                new String[] {"" + from, "" + limit})) {
      while (c.moveToNext()) {
        Entry e = new Entry();
        e.id = c.getLong(0);
        e.created = c.getLong(1);
        e.action = c.getString(2);
        e.state = c.getString(4);
        try {
          e.data = new JSONObject(c.getString(3));
        } catch (JSONException bad) {
          e.data = new JSONObject();
        }
        out.add(e);
      }
    }
    return out;
  }

  /** Сколько действий можно отменить прямо сейчас. */
  static int undoable(Store store) {
    int count = 0;
    long now = System.currentTimeMillis();
    for (Entry e : recent(store, 50, AgentJournal.UNDO_WINDOW)) if (e.undoable(now)) count++;
    return count;
  }

  static void prune(Store store, long now) {
    store
        .getWritableDatabase()
        .execSQL("DELETE FROM agent_log WHERE created<?", new Object[] {now - AgentJournal.KEEP});
  }

  /** Итог отмены: текст для пользователя и заметки, ушедшие в корзину или вернувшиеся из неё. */
  static final class Outcome {
    String message = "";
    final List<Long> trashed = new ArrayList<>(), restored = new ArrayList<>();
    /** Расписания до/после отмены пакета: будильники переставляются только после commit. */
    final List<Note> reminderBefore = new ArrayList<>(), reminderAfter = new ArrayList<>();
  }

  /**
   * Отменяет действие. Если вернуть уже нельзя (заметку изменили или удалили, прошли сутки),
   * бросает {@link IllegalStateException} с причиной и ничего не меняет. Напоминания заметок из
   * {@link Outcome} снимает и ставит вызывающий.
   */
  static Outcome undo(Store store, long id) {
    Outcome out = new Outcome();
    SQLiteDatabase db = store.getWritableDatabase();
    db.beginTransaction();
    try (Cursor c =
        db.rawQuery(
            "SELECT created,action,data,state FROM agent_log WHERE id=?", new String[] {"" + id})) {
      if (!c.moveToFirst()) throw new IllegalStateException("Запись журнала не найдена");
      if (!AgentJournal.canUndo(c.getString(3), c.getLong(0), System.currentTimeMillis()))
        throw new IllegalStateException(
            AgentJournal.UNDONE.equals(c.getString(3))
                ? "Уже отменено"
                : "Отменить можно только в течение суток");
      String action = c.getString(1);
      JSONObject data = new JSONObject(c.getString(2));
      out.message =
          switch (action) {
            case AgentJournal.NAME -> undoName(store, data);
            case AgentJournal.CREATE -> undoCreate(store, db, data, out);
            case AgentJournal.GROUP -> undoGroup(store, db, data, out);
            case AgentJournal.EXTEND -> undoExtend(store, db, data);
            case AgentJournal.ANSWER -> undoAnswer(db, data);
            case AgentJournal.COMMAND_BATCH -> {
              CommandExecutor.undoInTransaction(store, db, data, out);
              yield out.message;
            }
            default -> throw new IllegalStateException("Это действие нельзя отменить");
          };
      db.execSQL(
          "UPDATE agent_log SET state=? WHERE id=?", new Object[] {AgentJournal.UNDONE, id});
      db.setTransactionSuccessful();
      return out;
    } catch (JSONException e) {
      throw new IllegalStateException("Запись журнала повреждена");
    } finally {
      db.endTransaction();
    }
  }

  private static String undoName(Store store, JSONObject data) {
    if (!store.undoAgentTitle(
        data.optLong("note"), data.optString("title"), data.optString("before")))
      throw new IllegalStateException("Название уже изменили или заметку удалили");
    return "Название возвращено";
  }

  private static String undoCreate(Store store, SQLiteDatabase db, JSONObject data, Outcome out) {
    Note n = store.note(data.optLong("note"));
    if (n == null || n.trashed > 0) throw new IllegalStateException("Заметки уже нет");
    out.trashed.addAll(store.trash(n.id, false));
    closeProposal(db, data.optLong("proposal"));
    return "Заметка в корзине";
  }

  private static String undoGroup(Store store, SQLiteDatabase db, JSONObject data, Outcome out) {
    long top = data.optLong("note");
    Note n = store.note(top);
    if (n == null || n.trashed > 0) throw new IllegalStateException("Колоды уже нет");
    ManagerStore manager = new ManagerStore(store);
    if (manager.top(top) != top) throw new IllegalStateException("Колоду уже разобрали");
    checkCollection(store, manager, n, data);
    // Ушедшие в корзину по галочке возвращаются обычными заметками (если их не удалили насовсем).
    for (long gone : AgentJournal.ids(data, "trashed")) {
      Note g = store.note(gone);
      if (g != null && g.trashed > 0) out.restored.addAll(store.restore(gone));
    }
    out.trashed.addAll(store.trash(top, false));
    // Прежние стопки собираются заново из того, что осталось на месте и сейчас ни в какой стопке.
    for (Map.Entry<Long, List<Long>> old : AgentJournal.before(data).entrySet()) {
      Note head = store.note(old.getKey());
      if (head == null || head.trashed > 0 || manager.top(head.id) != 0) continue;
      List<Long> back = new ArrayList<>();
      for (long member : old.getValue()) {
        Note m = store.note(member);
        if (m != null && m.trashed == 0 && member != head.id && manager.top(member) == 0)
          back.add(member);
      }
      if (back.isEmpty()) continue;
      db.execSQL("INSERT OR IGNORE INTO note_groups(top) VALUES(?)", new Object[] {head.id});
      for (long member : back)
        db.execSQL(
            "INSERT OR REPLACE INTO note_members(note,top) VALUES(?,?)",
            new Object[] {member, head.id});
    }
    closeProposal(db, data.optLong("proposal"));
    return "Колода разобрана, сводка в корзине";
  }

  private static String undoExtend(Store store, SQLiteDatabase db, JSONObject data) {
    long top = data.optLong("note");
    Note n = store.note(top);
    if (n == null || n.trashed > 0 || new ManagerStore(store).top(top) != top)
      throw new IllegalStateException("Колоды уже нет");
    if (!n.title.equals(data.optString("title")) || !n.body.equals(data.optString("body")))
      throw new IllegalStateException("Сводку колоды уже изменили");
    checkCollection(store, new ManagerStore(store), n, data);
    store.save(top, data.optString("was_title"), data.optString("was_body"), n.reminder, n.repeat,
        n.lead, n.reminderTimes);
    if (data.has("previous_plan")) store.savePlan(top, data.optString("previous_plan"));
    for (long added : AgentJournal.ids(data, "added"))
      db.execSQL(
          "DELETE FROM note_members WHERE note=? AND top=?", new Object[] {added, top});
    new ManagerStore(store).cleanup();
    closeProposal(db, data.optLong("proposal"));
    return "Сводка колоды возвращена, добавленные заметки — в списке";
  }

  private static void checkCollection(Store store, ManagerStore manager, Note n, JSONObject data) {
    if (data.has("collection_revision")
        && (n.updated != data.optLong("collection_revision")
            || !store.plan(n.id).equals(data.optString("collection_plan"))
            || !new JSONArray(manager.members(n.id)).toString()
                .equals(data.optString("collection_members"))))
      throw new IllegalStateException("Сводка или состав колоды изменились");
  }

  private static String undoAnswer(SQLiteDatabase db, JSONObject data) throws JSONException {
    long proposal = data.optLong("proposal");
    try (Cursor c =
        db.rawQuery(
            "SELECT data FROM manager_proposals WHERE id=? AND state='accepted'",
            new String[] {"" + proposal})) {
      if (!c.moveToFirst()) throw new IllegalStateException("Вопрос уже закрыт");
      JSONObject p = new JSONObject(c.getString(0));
      p.remove("_answer");
      ContentValues v = new ContentValues();
      v.put("data", p.toString());
      v.put("state", "pending");
      db.update("manager_proposals", v, "id=?", new String[] {"" + proposal});
    }
    return "Ответ забыт, вопрос снова в колокольчике";
  }

  /** Отменённое предложение больше не показывается. */
  private static void closeProposal(SQLiteDatabase db, long proposal) {
    if (proposal > 0)
      db.execSQL(
          "UPDATE manager_proposals SET state='dismissed' WHERE id=? AND state='accepted'",
          new Object[] {proposal});
  }
}
