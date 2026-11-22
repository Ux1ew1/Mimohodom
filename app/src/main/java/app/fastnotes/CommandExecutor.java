package app.fastnotes;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;
import org.json.*;

/** Атомарное применение и отмена одного пакета команды; будильники меняет вызывающий после commit. */
public final class CommandExecutor {
  private CommandExecutor() {}

  public static final class Change {
    public final Note before, after;

    Change(Note before, Note after) {
      this.before = before;
      this.after = after;
    }
  }

  public static final class Outcome {
    public long journal;
    public final List<Change> reminders = new ArrayList<>();
    public final List<Long> created = new ArrayList<>();
  }

  /** Повторное чтение черновика и всех ревизий происходит внутри одной транзакции. */
  public static synchronized Outcome apply(Store store, long messageId) {
    SQLiteDatabase db = store.getWritableDatabase();
    db.beginTransaction();
    try {
      String stored;
      try (Cursor c = db.rawQuery("SELECT draft FROM messages WHERE id=? AND role='assistant'",
          new String[] {Long.toString(messageId)})) {
        if (!c.moveToFirst() || c.isNull(0) || c.getString(0).isEmpty())
          throw new IllegalStateException("Черновик уже применён или закрыт");
        stored = c.getString(0);
      }
      CommandDraft draft;
      try {
        draft = CommandDraft.fromJson(new JSONObject(stored));
      } catch (JSONException e) {
        throw new IllegalStateException("Черновик команды повреждён");
      }
      long now = System.currentTimeMillis();
      String future = draft.futureError(now);
      if (!future.isEmpty()) throw new IllegalStateException(future);

      // Ни одной записи до проверки всех исходников и целевых папок.
      for (CommandDraft.NoteState expected : draft.notes.values()) {
        Note current = store.note(expected.id);
        if (current == null || current.trashed != 0)
          throw new IllegalStateException("Исходная заметка удалена или попала в корзину");
        if (current.updated != expected.updated)
          throw new IllegalStateException("Заметка «" + expected.title + "» изменилась после предпросмотра");
      }
      for (CommandDraft.FolderState expected : draft.folders) resolveFolder(db, expected, false);
      for (AgentAction action : draft.actions)
        if (action.kind == AgentAction.Kind.REMIND) {
          CommandDraft.NoteState expected = draft.notes.get(action.note);
          if (expected == null || (expected.repeat != ReminderPolicy.REPEAT_NONE
              && expected.repeat != ReminderPolicy.REPEAT_LIST))
            throw new IllegalStateException("Повторяющиеся серии эта команда не меняет");
        }

      Outcome out = new Outcome();
      JSONArray createdNotes = new JSONArray(), moved = new JSONArray(), reminders = new JSONArray();
      Set<Long> createdFolders = new LinkedHashSet<>();
      Map<String, Long> targets = new HashMap<>();
      for (AgentAction action : draft.actions) {
        if (action.kind == AgentAction.Kind.CREATE) {
          long id = store.save(0, action.title, action.body, 0);
          Note made = store.note(id);
          if (action.metadata.length() > 0
              && !store.applyMetadataInTransaction(id, made.updated, action.metadata))
            throw new IllegalStateException("Не удалось сохранить метаданные новой заметки");
          long folder = 0;
          if (!action.folder.isEmpty()) {
            CommandDraft.FolderState expected = draft.folder(action.folder);
            if (expected == null) throw new IllegalStateException("Целевая папка не найдена в черновике");
            folder = ensureFolder(db, store, expected, targets, createdFolders);
            ContentValues value = new ContentValues();
            value.put("folder", folder);
            if (db.update("notes", value, "id=?", new String[] {Long.toString(id)}) != 1)
              throw new IllegalStateException("Не удалось поместить новую заметку в папку");
          }
          made = store.note(id);
          out.created.add(id);
          createdNotes.put(new JSONObject().put("note", id).put("title", made.title)
              .put("after_updated", made.updated).put("after_folder", made.folder));
        } else if (action.kind == AgentAction.Kind.FOLDER) {
          CommandDraft.FolderState expected = draft.folder(action.folder);
          if (expected == null) throw new IllegalStateException("Целевая папка не найдена в черновике");
          long folder = ensureFolder(db, store, expected, targets, createdFolders);
          for (long id : action.notes) {
            Note before = store.note(id);
            ContentValues value = new ContentValues();
            value.put("folder", folder);
            if (db.update("notes", value, "id=? AND updated=? AND trashed=0",
                new String[] {Long.toString(id), Long.toString(before.updated)}) != 1)
              throw new IllegalStateException("Заметка изменилась во время применения");
            moved.put(new JSONObject().put("note", id).put("title", before.title)
                .put("before", before.folder).put("after", folder));
          }
        } else if (action.kind == AgentAction.Kind.REMIND) {
          Note before = store.note(action.note);
          CommandDraft.NoteState expected = draft.notes.get(action.note);
          TreeSet<Long> times = expected.schedule();
          times.addAll(action.times);
          long[] exact = times.stream().mapToLong(Long::longValue).toArray();
          boolean list = expected.repeat == ReminderPolicy.REPEAT_LIST || exact.length > 1;
          long reminder = list ? ReminderPolicy.currentInList(exact, now) : exact[0];
          String encoded = list ? ReminderPolicy.encodeTimes(exact) : "";
          ContentValues value = new ContentValues();
          value.put("reminder", reminder);
          value.put("reminder_times", encoded);
          value.put("repeat_mode", list ? ReminderPolicy.REPEAT_LIST : ReminderPolicy.REPEAT_NONE);
          // -1 в ответе — «не менять»: прежний свой интервал заметки не должен сбрасываться.
          value.put("lead_minutes", action.lead == -1 ? before.lead : action.lead);
          if (db.update("notes", value, "id=? AND updated=? AND trashed=0",
              new String[] {Long.toString(action.note), Long.toString(before.updated)}) != 1)
            throw new IllegalStateException("Расписание изменилось во время применения");
          Note after = store.note(action.note);
          out.reminders.add(new Change(before, after));
          reminders.put(new JSONObject().put("note", action.note)
              .put("before_reminder", before.reminder).put("before_times", before.reminderTimes)
              .put("before_repeat", before.repeat).put("before_lead", before.lead)
              .put("after_reminder", after.reminder).put("after_times", after.reminderTimes)
              .put("after_repeat", after.repeat).put("after_lead", after.lead));
        }
      }
      if (createdNotes.length() == 0 && moved.length() == 0 && reminders.length() == 0)
        throw new IllegalStateException("Пакет не содержит изменений");
      JSONArray folderIds = new JSONArray();
      for (long id : createdFolders) folderIds.put(id);
      JSONObject journal = new JSONObject().put("command", draft.command)
          .put("created_notes", createdNotes).put("folders", moved).put("reminders", reminders)
          .put("created_folders", folderIds);
      if (!createdFolders.isEmpty()) journal.put("created_folder", createdFolders.iterator().next());
      out.journal = AgentLog.record(db, AgentJournal.COMMAND_BATCH, journal);
      if (out.journal <= 0) throw new IllegalStateException("Не удалось записать журнал команды");
      store.dismissDraft(messageId);
      db.setTransactionSuccessful();
      return out;
    } catch (JSONException e) {
      throw new IllegalStateException("Не удалось подготовить журнал команды");
    } finally {
      db.endTransaction();
    }
  }

  /** Существующая папка должна остаться той же; новую до транзакции никто не должен был создать. */
  private static long resolveFolder(SQLiteDatabase db, CommandDraft.FolderState expected, boolean forWrite) {
    try (Cursor c = db.rawQuery("SELECT id,name FROM folders WHERE key=?",
        new String[] {FolderPolicy.key(expected.name)})) {
      if (expected.existing()) {
        if (!c.moveToFirst() || c.getLong(0) != expected.id || !c.getString(1).equals(expected.name))
          throw new IllegalStateException("Целевая папка удалена или переименована");
        return expected.id;
      }
      if (c.moveToFirst()) {
        if (forWrite && c.getLong(0) == expected.id) return expected.id;
        throw new IllegalStateException("Папка «" + expected.name + "» появилась после предпросмотра");
      }
      return 0;
    }
  }

  private static long ensureFolder(
      SQLiteDatabase db, Store store, CommandDraft.FolderState expected,
      Map<String, Long> cache, Set<Long> created) {
    String key = FolderPolicy.key(expected.name);
    Long known = cache.get(key);
    if (known != null) return known;
    long id = resolveFolder(db, expected, true);
    if (id == 0) {
      id = new FolderStore(store).create(expected.name, false);
      if (id <= 0 || id != expected.id && expected.existing())
        throw new IllegalStateException("Не удалось создать папку");
      created.add(id);
    }
    cache.put(key, id);
    return id;
  }

  /** Вызывается из AgentLog.undo в его транзакции; сначала проверяются все конфликты отмены. */
  static void undoInTransaction(Store store, SQLiteDatabase db, JSONObject data, AgentLog.Outcome out)
      throws JSONException {
    JSONArray created = data.optJSONArray("created_notes"), moved = data.optJSONArray("folders");
    JSONArray reminders = data.optJSONArray("reminders");
    for (int i = 0; created != null && i < created.length(); i++) {
      JSONObject row = created.getJSONObject(i);
      Note n = store.note(row.getLong("note"));
      if (n == null || n.trashed != 0 || n.updated != row.getLong("after_updated")
          || n.folder != row.getLong("after_folder"))
        throw new IllegalStateException("Созданную заметку уже изменили или удалили");
    }
    for (int i = 0; moved != null && i < moved.length(); i++) {
      JSONObject row = moved.getJSONObject(i);
      Note n = store.note(row.getLong("note"));
      if (n == null || n.trashed != 0 || n.folder != row.getLong("after"))
        throw new IllegalStateException("Папку одной из заметок уже изменили");
    }
    for (int i = 0; reminders != null && i < reminders.length(); i++) {
      JSONObject row = reminders.getJSONObject(i);
      Note n = store.note(row.getLong("note"));
      if (n == null || n.trashed != 0 || !sameSchedule(n, row, "after"))
        throw new IllegalStateException("Добавленное напоминание уже изменили");
    }

    long now = System.currentTimeMillis();
    for (int i = 0; created != null && i < created.length(); i++) {
      long id = created.getJSONObject(i).getLong("note");
      db.execSQL("UPDATE notes SET trashed=? WHERE id=? AND trashed=0", new Object[] {now, id});
      out.trashed.add(id);
    }
    for (int i = 0; moved != null && i < moved.length(); i++) {
      JSONObject row = moved.getJSONObject(i);
      db.execSQL("UPDATE notes SET folder=? WHERE id=? AND folder=?",
          new Object[] {row.getLong("before"), row.getLong("note"), row.getLong("after")});
    }
    for (int i = 0; reminders != null && i < reminders.length(); i++) {
      JSONObject row = reminders.getJSONObject(i);
      Note before = store.note(row.getLong("note"));
      ContentValues value = new ContentValues();
      value.put("reminder", row.getLong("before_reminder"));
      value.put("reminder_times", row.getString("before_times"));
      value.put("repeat_mode", row.getInt("before_repeat"));
      value.put("lead_minutes", row.getInt("before_lead"));
      db.update("notes", value, "id=?", new String[] {Long.toString(row.getLong("note"))});
      Note after = store.note(row.getLong("note"));
      out.reminderBefore.add(before);
      out.reminderAfter.add(after);
    }
    Set<Long> folders = new LinkedHashSet<>();
    long single = data.optLong("created_folder", 0);
    if (single > 0) folders.add(single);
    JSONArray many = data.optJSONArray("created_folders");
    for (int i = 0; many != null && i < many.length(); i++) folders.add(many.getLong(i));
    for (long folder : folders) {
      FolderStore.Folder current = new FolderStore(store).folder(folder);
      if (current != null && current.notes == 0) db.delete("folders", "id=?", new String[] {Long.toString(folder)});
    }
    out.message = "Команда отменена";
  }

  private static boolean sameSchedule(Note n, JSONObject row, String prefix) {
    return n.reminder == row.optLong(prefix + "_reminder")
        && n.reminderTimes.equals(row.optString(prefix + "_times"))
        && n.repeat == row.optInt(prefix + "_repeat")
        && n.lead == row.optInt(prefix + "_lead", -1);
  }
}
