package app.fastnotes;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class CollectionDraft {
  static final String KIND = "collection";
  private static final String REBUILD = "Пересобери сводку по выбранным ID: ";

  private CollectionDraft() {}

  static String rebuildQuery(List<String> ids, String query) {
    return REBUILD + String.join(",", ids) + "\n" + query;
  }

  static List<Long> requestedSources(String query) {
    List<Long> ids = new ArrayList<>();
    if (!query.startsWith(REBUILD)) return ids;
    int end = query.indexOf('\n');
    if (end < 0) return ids;
    String selection = query.substring(REBUILD.length(), end);
    if (!selection.matches("[0-9]{1,18}(,[0-9]{1,18}){0,23}")) return ids;
    for (String id : selection.split(",")) ids.add(Long.parseLong(id));
    return ids;
  }

  static String standalone(String body) {
    return body.replaceAll("(?i)\\[\\[note:[^]\\r\\n]*]](?:[ \\t]*[,;][ \\t]*(?=\\[\\[note:))?", "")
        .replaceAll("(?m)^\\s*[-•]?[ \\t]*Источники[ \\t]*:[ \\t]*[.,]?[ \\t]*$", "")
        .replaceAll("[ \\t]+([.,;])", "$1").trim();
  }

  static JSONObject create(Store store, JSONObject proposal, List<Note> scope, PlanTree plan,
      boolean limited) throws Exception {
    JSONArray ids = proposal.optJSONArray("sources");
    String title = standalone(proposal.optString("title")).replace('\n', ' ').trim();
    String body = standalone(proposal.optString("body"));
    if (title.isEmpty() || body.isEmpty() || ids == null || ids.length() == 0 || ids.length() > 24)
      throw new java.io.IOException("Модель не подготовила сводку с допустимыми источниками.");
    JSONArray sources = new JSONArray();
    LinkedHashSet<Long> seen = new LinkedHashSet<>(), decks = new LinkedHashSet<>();
    ManagerStore groups = new ManagerStore(store);
    for (int i = 0; i < ids.length(); i++) {
      long id = ids.getLong(i);
      if (!seen.add(id)) continue;
      Note n = scope.stream().filter(note -> note.id == id).findFirst().orElse(null);
      if (n == null || !store.aiAllowed(id))
        throw new java.io.IOException("Один из источников недоступен. Повторите сбор.");
      long top = groups.top(id);
      if (top > 0 && store.aiAllowed(top)) decks.add(top);
      sources.put(new JSONObject().put("id", id).put("title", n.title)
          .put("updated", n.updated).put("folder", n.folder).put("top", top));
    }
    long chosen = proposal.optLong("top");
    if (decks.size() > 1 && !decks.contains(chosen)) {
      StringBuilder question = new StringBuilder("Какую колоду дополнить?");
      for (long id : decks) question.append("\n• ").append(store.note(id).title);
      return new JSONObject().put("question", question.toString());
    }
    long top = decks.contains(chosen) ? chosen : decks.isEmpty() ? 0 : decks.iterator().next();
    Note head = top == 0 ? null : store.note(top);
    JSONObject result = new JSONObject().put("kind", KIND).put("title", title)
        .put("body", body).put("sources", sources).put("top", top).put("limited", limited);
    if (head != null) {
      result.put("head", head.title).put("revision", head.updated).put("folder", head.folder)
          .put("members", new JSONArray(groups.members(top)));
    }
    if (plan != null) result.put("plan", plan.toJson());
    return result;
  }

  static long apply(Store store, long message) throws Exception {
    SQLiteDatabase db = store.getWritableDatabase();
    db.beginTransaction();
    try {
      JSONObject draft;
      try (Cursor c = db.rawQuery("SELECT draft FROM messages WHERE id=?",
          new String[] {Long.toString(message)})) {
        if (!c.moveToFirst() || c.getString(0).isEmpty())
          throw new IllegalStateException("Предпросмотр уже закрыт.");
        draft = new JSONObject(c.getString(0));
      }
      if (!KIND.equals(draft.optString("kind"))) throw new IllegalStateException("Нет сводки.");
      if (!store.ownsDraft(draft)) throw new IllegalStateException("Откройте черновик на исходном устройстве.");
      ManagerStore groups = new ManagerStore(store);
      long top = draft.optLong("top");
      Note head = top == 0 ? null : store.note(top);
      if (top > 0 && (head == null || !store.aiAllowed(top) || groups.top(top) != top
          || head.updated != draft.optLong("revision") || head.folder != draft.optLong("folder")
          || !new JSONArray(groups.members(top)).toString().equals(draft.optString("members"))))
        throw new IllegalStateException("Колода изменилась. Подготовьте новую сводку.");
      List<Long> added = new ArrayList<>();
      JSONArray sources = draft.getJSONArray("sources");
      for (int i = 0; i < sources.length(); i++) {
        JSONObject source = sources.getJSONObject(i);
        long id = source.getLong("id");
        Note current = store.note(id);
        if (current == null || !store.aiAllowed(id) || current.updated != source.getLong("updated")
            || current.folder != source.getLong("folder") || groups.top(id) != source.getLong("top"))
          throw new IllegalStateException("Исходные заметки изменились. Подготовьте новую сводку.");
        if (groups.top(id) == 0) added.add(id);
      }
      if (top == 0 && added.isEmpty())
        throw new IllegalStateException("Нет доступных исходников для новой колоды.");
      String title = standalone(draft.getString("title")), body = standalone(draft.getString("body"));
      if (title.isEmpty() || body.isEmpty()) throw new IllegalStateException("Сводка пуста.");
      String oldPlan = top == 0 ? "" : store.plan(top);
      long result = top == 0 ? store.save(0, title, body, 0)
          : store.save(top, title, body, head.reminder, head.repeat, head.lead, head.reminderTimes);
      if (top == 0) db.execSQL("INSERT INTO note_groups(top) VALUES(?)", new Object[] {result});
      for (long id : added)
        db.execSQL("INSERT INTO note_members(note,top) VALUES(?,?)", new Object[] {id, result});
      if (draft.has("plan")) {
        PlanTree plan = PlanTree.parse(draft.getString("plan"));
        plan.keepDone(PlanTree.parse(oldPlan));
        store.savePlan(result, plan.toJson());
      }
      Note after = store.note(result);
      JSONObject log = head == null
          ? AgentJournal.groupData(result, after.title, added, java.util.Map.of(), 0)
          : AgentJournal.extendData(result, after.title, after.body, head.title, head.body, added, 0);
      log.put("collection_revision", after.updated).put("collection_plan", store.plan(result))
          .put("previous_plan", oldPlan).put("collection_members", new JSONArray(groups.members(result)));
      AgentLog.record(db, head == null ? AgentJournal.GROUP : AgentJournal.EXTEND, log);
      store.dismissDraft(message);
      db.setTransactionSuccessful();
      return result;
    } finally {
      db.endTransaction();
    }
  }
}
