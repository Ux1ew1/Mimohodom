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
                + " WHERE n.trashed=0"
                // Эксперимент «баллы внимания»: заметка на нуле помощника не интересует.
                + " AND (?='0' OR n.attention<>0)"
                + " ORDER BY CASE WHEN r.note IS NULL OR n.updated>r.attempted THEN 0 ELSE 1"
                + " END,COALESCE(r.attempted,0),n.id",
            new String[] {store.attentionEnabled() ? "1" : "0"})) {
      while (c.moveToNext()) {
        Note n = store.note(c.getLong(0));
        if (n != null
            && (manual
                || c.isNull(1)
                || !ManagerAnalysis.fingerprint(n).equals(c.getString(1))
                || c.getInt(2) == 0)) seeds.add(n);
      }
    }
    seeds.removeIf(n -> top(n.id) != 0);
    return TopicSelection.select(seeds);
  }

  public List<Note> summarySources(JSONObject p) throws Exception {
    TreeSet<Long> ids = new TreeSet<>();
    JSONArray sources = p.getJSONArray("sources");
    for (int i = 0; i < sources.length(); i++) {
      long id = sources.getJSONObject(i).getLong("id");
      ids.add(id);
      long group = top(id);
      if (group != 0) {
        ids.add(group);
        ids.addAll(members(group));
      }
    }
    List<Note> out = new ArrayList<>();
    for (long id : ids) {
      Note n = store.note(id);
      if (n == null) throw new IllegalStateException("Исходники изменились");
      out.add(n);
    }
    return out;
  }

  public JSONObject summaryProgress(long id) throws Exception {
    try (Cursor c =
        db.rawQuery("SELECT data FROM manager_proposals WHERE id=?", new String[] {"" + id})) {
      if (!c.moveToFirst()) throw new IllegalStateException("Предложение не найдено");
      JSONObject data = new JSONObject(c.getString(0));
      JSONObject summary = data.optJSONObject("_summary");
      return summary == null ? new JSONObject() : summary;
    }
  }

  public void saveSummary(JSONObject p, JSONObject summary) throws Exception {
    db.beginTransaction();
    try {
      if (!current(p))
        throw new IllegalStateException("Исходники изменились. Нужна новая проверка.");
      JSONObject data = new JSONObject(p.toString()).put("_summary", summary);
      ContentValues values = new ContentValues();
      values.put("data", data.toString());
      if (db.update(
              "manager_proposals",
              values,
              "id=? AND state IN ('pending','reviewed')",
              new String[] {"" + p.getLong("id")})
          != 1) throw new IllegalStateException("Предложение уже закрыто");
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
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
        v.put("revision", ManagerAnalysis.fingerprint(n));
        v.put("attempted", now);
        v.put("failed", 1);
        // Обновляем строку, а не заменяем: класс заметки (kind) прошлой проверки сохраняется.
        if (db.update("manager_review", v, "note=?", new String[] {"" + n.id}) == 0) {
          v.put("note", n.id);
          db.insertOrThrow("manager_review", null, v);
        }
      }
      db.setTransactionSuccessful();
      return id;
    } finally {
      db.endTransaction();
    }
  }

  /**
   * Группы записей, которые уже предлагались (ждут ответа, отклонены или приняты), пока их записи
   * не менялись. Нужны, чтобы не предлагать одно и то же в другом виде.
   */
  public List<Set<Long>> knownGroups() {
    List<Set<Long>> out = new ArrayList<>();
    try (Cursor c =
        db.rawQuery(
            "SELECT data FROM manager_proposals WHERE state IN"
                + " ('pending','reviewed','dismissed','accepted')",
            null)) {
      while (c.moveToNext()) {
        try {
          JSONObject p = new JSONObject(c.getString(0));
          String type = p.optString("type");
          if (!TopicSelection.deck(type) && !type.equals("extend")) continue;
          if (!current(p)) continue;
          Set<Long> ids = new TreeSet<>();
          JSONArray sources = p.getJSONArray("sources");
          for (int i = 0; i < sources.length(); i++) ids.add(sources.getJSONObject(i).getLong("id"));
          out.add(ids);
        } catch (Exception ignored) {
        }
      }
    }
    return out;
  }

  /** Известные группы, в которых хотя бы две записи из переданных: для подсказки модели. */
  public List<Set<Long>> knownGroups(List<Note> batch) {
    Set<Long> ids = new HashSet<>();
    for (Note n : batch) ids.add(n.id);
    List<Set<Long>> out = new ArrayList<>();
    for (Set<Long> group : knownGroups()) {
      Set<Long> inside = new TreeSet<>(group);
      inside.retainAll(ids);
      if (inside.size() >= 2) out.add(inside);
    }
    return out;
  }

  /** Новая группа пересекается с уже предлагавшейся не меньше чем по двум записям. */
  static boolean overlaps(Set<Long> group, List<Set<Long>> known) {
    for (Set<Long> old : known) {
      int shared = 0;
      for (long id : group) if (old.contains(id)) shared++;
      if (shared >= 2) return true;
    }
    return false;
  }

  /**
   * Ответы пользователя на вопросы агента по заметкам из пачки: номер заметки → «вопрос → ответ;
   * …». С этапа 5 ответ хранится по заметке и идёт в следующие проверки и после правки заметки —
   * чтобы агент не переспрашивал (docs/AGENT-PLANNER.md, 2.4).
   */
  public Map<Long, String> answers(List<Note> batch) {
    Map<Long, String> out = new LinkedHashMap<>();
    for (Note n : batch) {
      List<String> lines = noteAnswers(n.id);
      if (!lines.isEmpty()) out.put(n.id, String.join("; ", lines));
    }
    return out;
  }

  /** Ответы на вопросы агента по одной заметке, старые первыми, не больше пяти последних. */
  public List<String> noteAnswers(long note) {
    List<String> out = new ArrayList<>();
    try (Cursor c =
        db.rawQuery(
            "SELECT data FROM manager_proposals WHERE state='accepted' AND identity LIKE"
                + " 'clarify|%' ORDER BY id",
            null)) {
      while (c.moveToNext()) {
        try {
          JSONObject p = new JSONObject(c.getString(0));
          String answer = p.optString("_answer", "");
          if (answer.isEmpty() || firstSource(p) != note) continue;
          out.add(CheckIn.line(p.optString("reason"), 120) + " → " + CheckIn.line(answer, 200));
        } catch (Exception ignored) {
        }
      }
    }
    return out.size() > 5 ? new ArrayList<>(out.subList(out.size() - 5, out.size())) : out;
  }

  /** Открытый вопрос агента по заметке (один на заметку) или null. */
  public JSONObject pendingQuestion(long note) {
    for (JSONObject p : pending())
      if (p.optString("type").equals("clarify") && firstSource(p) == note) return p;
    return null;
  }

  /** Заметки с открытым вопросом агента — для пометки на карточках списка. */
  public Set<Long> questionNotes() {
    Set<Long> out = new HashSet<>();
    for (JSONObject p : pending()) if (p.optString("type").equals("clarify")) out.add(firstSource(p));
    return out;
  }

  static long firstSource(JSONObject p) {
    JSONArray sources = p.optJSONArray("sources");
    JSONObject first = sources == null ? null : sources.optJSONObject(0);
    return first == null ? 0 : first.optLong("id");
  }

  /** Класс заметки из последней проверки ({@link TaskKind}) или пусто. */
  public String kind(long note) {
    try (Cursor c =
        db.rawQuery("SELECT kind FROM manager_review WHERE note=?", new String[] {"" + note})) {
      return c.moveToFirst() ? TaskKind.clean(c.getString(0)) : "";
    }
  }

  /** Классы всех заметок, которые их получили: номер → класс. */
  public Map<Long, String> kinds() {
    Map<Long, String> out = new HashMap<>();
    try (Cursor c = db.rawQuery("SELECT note,kind FROM manager_review WHERE kind<>''", null)) {
      while (c.moveToNext()) out.put(c.getLong(0), TaskKind.clean(c.getString(1)));
    }
    return out;
  }

  /** Пользователь выбрал вариант ответа на уточнение: запоминаем его и закрываем предложение. */
  boolean replaceAnswer(AgentLog.Entry entry, String answer) {
    db.beginTransaction();
    try {
      AgentLog.undo(store, entry.id);
      if (!answerClarify(entry.data.optLong("proposal"), answer)) return false;
      db.setTransactionSuccessful();
      return true;
    } catch (IllegalStateException changed) {
      return false;
    } finally { db.endTransaction(); }
  }

  public boolean answerClarify(long id, String answer) {
    db.beginTransaction();
    try (Cursor c =
        db.rawQuery(
            "SELECT data FROM manager_proposals WHERE id=? AND state='pending'",
            new String[] {"" + id})) {
      if (!c.moveToFirst()) return false;
      JSONObject data = new JSONObject(c.getString(0)).put("_answer", answer.trim());
      ContentValues v = new ContentValues();
      v.put("data", data.toString());
      v.put("state", "accepted");
      boolean done = db.update("manager_proposals", v, "id=?", new String[] {"" + id}) == 1;
      JSONArray sources = data.optJSONArray("sources");
      if (done) {
        long note =
            sources == null || sources.length() == 0 ? 0 : sources.getJSONObject(0).getLong("id");
        AgentLog.record(
            db,
            AgentJournal.ANSWER,
            AgentJournal.answerData(note, data.optString("reason"), answer.trim(), id));
        // Баллы внимания: ответ на вопрос помощника — явный интерес к заметке.
        if (note > 0) store.attentionReward(note, 1);
      }
      db.setTransactionSuccessful();
      return done;
    } catch (JSONException e) {
      return false;
    } finally {
      db.endTransaction();
    }
  }

  public int finish(long run, List<Note> notes, List<JSONObject> proposals) throws Exception {
    db.beginTransaction();
    try {
      int added = 0;
      List<Set<Long>> known = knownGroups();
      for (JSONObject p : proposals) {
        if (!current(p)) continue;
        // Один открытый вопрос на заметку: новый ждёт, пока пользователь не ответит на прежний.
        if (p.getString("type").equals("clarify") && pendingQuestion(firstSource(p)) != null)
          continue;
        // Предложение разобрать план даётся один раз на заметку; устаревшее можно повторить.
        if (p.getString("type").equals("plan"))
          db.delete(
              "manager_proposals",
              "identity=? AND state='stale'",
              new String[] {p.getString("identity")});
        if (TopicSelection.deck(p.getString("type"))) {
          Set<Long> distinctGroups = new HashSet<>();
          JSONArray sources = p.getJSONArray("sources");
          for (int i = 0; i < sources.length(); i++) {
            long id = sources.getJSONObject(i).getLong("id"), group = top(id);
            distinctGroups.add(group == 0 ? id : group);
          }
          if (distinctGroups.size() < 2) continue;
          Set<Long> ids = new TreeSet<>();
          for (int i = 0; i < sources.length(); i++) ids.add(sources.getJSONObject(i).getLong("id"));
          if (overlaps(ids, known)) continue;
          known.add(ids);
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
    return finishReview(run, notes, proposals, analysis, Collections.emptyMap());
  }

  /**
   * То же и классы заметок ({@link TaskKind}) из того же ответа модели: записываются заметкам, не
   * изменившимся за время запроса; большой задаче (complex) без плана предлагается «Разобрать план».
   */
  public ReviewOutcome finishReview(
      long run,
      List<Note> notes,
      List<JSONObject> proposals,
      Map<Long, Analysis.Result> analysis,
      Map<Long, String> kinds)
      throws Exception {
    return finishReview(run, notes, proposals, analysis, kinds, null);
  }

  public ReviewOutcome finishReview(
      long run, List<Note> notes, List<JSONObject> proposals, Map<Long, Analysis.Result> analysis,
      Map<Long, String> kinds, JSONObject planning) throws Exception {
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
      // Колода из extend во входе не была: её состояние берём сейчас, под той же транзакцией.
      for (JSONObject p : valid) {
        JSONArray sources = p.getJSONArray("sources");
        for (int i = 0; i < sources.length(); i++) {
          String key = "" + sources.getJSONObject(i).getLong("id");
          if (!groups.has(key)) groups.put(key, groupRevision(Long.parseLong(key)));
        }
      }
      for (JSONObject p : valid) {
        JSONArray sources = p.getJSONArray("sources");
        for (int i = 0; i < sources.length(); i++) {
          JSONObject source = sources.getJSONObject(i);
          source.put("revision", ManagerAnalysis.fingerprint(store.note(source.getLong("id"))));
        }
        attachGroups(p, groups);
      }
      for (Note n : unchanged) {
        String kind = TaskKind.clean(kinds.get(n.id));
        String reason = ManagerAnalysis.planReason(planning, kind, n.id);
        if (reason.isEmpty() || !store.plan(n.id).isEmpty()) continue;
        valid.add(
            new JSONObject()
                .put("type", "plan")
                .put("reason", reason)
                .put("title", n.title)
                .put("body", "")
                .put("options", new JSONArray())
                .put(
                    "sources",
                    new JSONArray()
                        .put(
                            new JSONObject()
                                .put("id", n.id)
                                .put("revision", ManagerAnalysis.fingerprint(n))))
                .put("identity", "plan|" + n.id));
      }
      outcome.added = finish(run, notes, valid);
      for (Note n : unchanged) {
        db.execSQL(
            "UPDATE manager_review SET revision=?,failed=0 WHERE note=?",
            new Object[] {ManagerAnalysis.fingerprint(n), n.id});
        String kind = TaskKind.clean(kinds.get(n.id));
        if (!kind.isEmpty()) {
          String before = kind(n.id);
          db.execSQL("UPDATE manager_review SET kind=? WHERE note=?", new Object[] {kind, n.id});
          // Баллы внимания: разовый бонус первой классификации (событие, большая задача).
          if (before.isEmpty()) store.attentionClassBonus(n.id, kind);
        }
      }
      db.setTransactionSuccessful();
      return outcome;
    } finally {
      db.endTransaction();
    }
  }

  public int failedCount() {
    int count = 0;
    for (Note n : store.notes()) if (top(n.id) == 0 && failedUnchanged(n)) count++;
    return count;
  }

  public boolean current(JSONObject p) throws Exception {
    JSONArray sources = p.getJSONArray("sources");
    for (int i = 0; i < sources.length(); i++) {
      JSONObject source = sources.getJSONObject(i);
      Note n = store.note(source.getLong("id"));
      if (n == null
          || n.trashed > 0
          || !ManagerAnalysis.fingerprint(n).equals(source.getString("revision")))
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
            "SELECT id,data,state FROM manager_proposals WHERE state IN ('pending','reviewed')"
                + " ORDER BY created DESC,id DESC",
            null)) {
      while (c.moveToNext()) {
        try {
          JSONObject p = new JSONObject(c.getString(1)).put("id", c.getLong(0));
          if (c.getString(2).equals("reviewed") && !p.optString("type").equals("link")) continue;
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
        "UPDATE manager_proposals SET state=? WHERE id=? AND state IN ('pending','reviewed')",
        new Object[] {state, id});
  }

  /** Итог «Принять»: созданная или обновлённая заметка и исходные, ушедшие в корзину. */
  public static final class Accepted {
    public long note;
    public final List<Long> trashed = new ArrayList<>();
  }

  public long accept(long id, String title, String body) throws Exception {
    return accept(id, title, body, false).note;
  }

  /**
   * Принимает предложение по нажатию пользователя. Колода (group и прежние merge/link), а также
   * заметка-дело из нескольких записей: сверху новая заметка, исходные прячутся под ней. extend:
   * сводка колоды заменяется новой, новые записи ложатся в колоду. {@code trashExtras} —
   * галочка «Пустые и дубли — в корзину» (только для колоды, {@link #trashCandidates}).
   */
  public Accepted accept(long id, String title, String body, boolean trashExtras)
      throws Exception {
    Accepted out = new Accepted();
    db.beginTransaction();
    try (Cursor c =
        db.rawQuery(
            "SELECT data,state,result FROM manager_proposals WHERE id=?", new String[] {"" + id})) {
      if (!c.moveToFirst()) throw new IllegalStateException("Предложение не найдено");
      if (c.getString(1).equals("accepted")) {
        out.note = c.getLong(2);
        return out;
      }
      JSONObject p = new JSONObject(c.getString(0));
      String type = p.getString("type");
      if (!(c.getString(1).equals("pending")
              || (c.getString(1).equals("reviewed") && type.equals("link")))
          || !current(p))
        throw new IllegalStateException("Исходные заметки изменились. Нужна новая проверка.");
      if (body.trim().isEmpty()) throw new IllegalStateException("Добавьте текст заметки");
      long result;
      if (type.equals("extend")) result = extendDeck(p, id, title, body);
      else {
        Map<Long, String> extras = trashExtras && TopicSelection.deck(type)
            ? trashCandidates(p)
            : Collections.emptyMap();
        result = store.save(0, title, body, 0);
        JSONArray sources = p.getJSONArray("sources");
        // Заметка-дело из нескольких записей тоже прячет их в колоду (этап 3).
        if (TopicSelection.deck(type) || sources.length() >= 2) {
          LinkedHashSet<Long> members = new LinkedHashSet<>();
          // Прежние колоды, которые войдут в новую: журнал вернёт их по «Отменить».
          Map<Long, List<Long>> before = new LinkedHashMap<>();
          for (int i = 0; i < sources.length(); i++) {
            long source = sources.getJSONObject(i).getLong("id"), top = top(source);
            members.add(source);
            if (top != 0) {
              members.add(top);
              members.addAll(members(top));
              before.putIfAbsent(top, members(top));
            }
          }
          for (long member : members)
            db.delete("note_groups", "top=?", new String[] {"" + member});
          attachDeck(result, members);
          // Галочка «Пустые и дубли — в корзину»: только в корзину, вернуть можно 30 дней.
          for (long extra : extras.keySet())
            if (members.contains(extra) && !store.trash(extra, false).isEmpty())
              out.trashed.add(extra);
          AgentLog.record(
              db,
              AgentJournal.GROUP,
              AgentJournal.groupData(
                  result, store.note(result).title, members, before, id, out.trashed));
        } else
          AgentLog.record(
              db,
              AgentJournal.CREATE,
              AgentJournal.createData(result, store.note(result).title, id));
      }
      db.execSQL(
          "UPDATE manager_proposals SET state='accepted',result=? WHERE id=?",
          new Object[] {result, id});
      db.setTransactionSuccessful();
      out.note = result;
      return out;
    } finally {
      db.endTransaction();
    }
  }

  /** extend: новая сводка в верхнюю заметку колоды, новые записи — в колоду. */
  private long extendDeck(JSONObject p, long proposal, String title, String body)
      throws Exception {
    long top = p.getLong("stack");
    Note head = store.note(top);
    if (head == null || head.trashed > 0 || top(top) != top)
      throw new IllegalStateException("Колоды уже нет. Нужна новая проверка.");
    List<Long> added = extendAdded(p);
    for (long note : added)
      if (top(note) != 0) throw new IllegalStateException("Заметка уже в другой колоде.");
    store.save(top, title, body, head.reminder, head.repeat, head.lead);
    for (long note : added)
      db.execSQL(
          "INSERT OR REPLACE INTO note_members(note,top) VALUES(?,?)", new Object[] {note, top});
    Note after = store.note(top);
    AgentLog.record(
        db,
        AgentJournal.EXTEND,
        AgentJournal.extendData(top, after.title, after.body, head.title, head.body, added, proposal));
    return top;
  }

  /** Новые записи предложения extend (его исходные без самой колоды). */
  public static List<Long> extendAdded(JSONObject p) throws JSONException {
    long top = p.getLong("stack");
    List<Long> out = new ArrayList<>();
    JSONArray sources = p.getJSONArray("sources");
    for (int i = 0; i < sources.length(); i++) {
      long id = sources.getJSONObject(i).getLong("id");
      if (id != top) out.add(id);
    }
    return out;
  }

  /** Исходные для выжимки extend: только новые записи (прежняя сводка идёт как начало). */
  public List<Note> extendSources(JSONObject p) throws Exception {
    List<Note> out = new ArrayList<>();
    for (long id : extendAdded(p)) {
      Note n = store.note(id);
      if (n == null) throw new IllegalStateException("Исходники изменились");
      out.add(n);
    }
    return out;
  }

  /**
   * Что галочка «Пустые и дубли — в корзину» отправит в корзину: номер → причина ({@link
   * DeckPolicy}). Пусто — галочку не показываем.
   */
  public LinkedHashMap<Long, String> trashCandidates(JSONObject p) throws Exception {
    List<Note> notes = new ArrayList<>();
    JSONArray sources = p.getJSONArray("sources");
    for (int i = 0; i < sources.length(); i++) {
      Note n = store.note(sources.getJSONObject(i).getLong("id"));
      if (n != null && n.trashed == 0) notes.add(n);
    }
    Set<Long> model = new HashSet<>();
    JSONArray extra = p.optJSONArray("trash");
    for (int i = 0; extra != null && i < extra.length(); i++) model.add(extra.optLong(i));
    return DeckPolicy.trashCandidates(notes, model);
  }

  /**
   * Колоды по теме пачки для подсказки модели: верхние заметки (не в корзине), похожие хотя бы на
   * одну запись пачки, самые похожие первыми, не больше пяти.
   */
  public List<Note> stacksFor(List<Note> batch) {
    List<Note> tops = new ArrayList<>();
    Map<Long, Double> scores = new HashMap<>();
    try (Cursor c =
        db.rawQuery(
            "SELECT g.top FROM note_groups g JOIN notes n ON n.id=g.top WHERE n.trashed=0", null)) {
      while (c.moveToNext()) {
        Note top = store.note(c.getLong(0));
        if (top == null) continue;
        double best = 0;
        for (Note n : batch) best = Math.max(best, TopicSelection.similarity(top, n));
        if (best >= 0.18) {
          tops.add(top);
          scores.put(top.id, best);
        }
      }
    }
    tops.sort(
        (x, y) -> {
          int by = Double.compare(scores.get(y.id), scores.get(x.id));
          return by != 0 ? by : Long.compare(x.id, y.id);
        });
    return tops.size() > 5 ? new ArrayList<>(tops.subList(0, 5)) : tops;
  }

  /** Названия записей внутри колод (до восьми на колоду): модели видно, что уже сгруппировано. */
  public Map<Long, List<String>> stackMembers(Collection<Long> tops) {
    Map<Long, List<String>> out = new HashMap<>();
    if (tops.isEmpty()) return out;
    Map<Long, List<Long>> members = membersMap();
    for (long top : tops) {
      List<Long> ids = members.get(top);
      if (ids == null || ids.isEmpty()) continue;
      List<String> titles = new ArrayList<>();
      for (long id : ids) {
        Note n = store.note(id);
        if (n != null && n.trashed == 0) titles.add(n.title);
        if (titles.size() == 8) break;
      }
      if (ids.size() > titles.size() && !titles.isEmpty())
        titles.set(titles.size() - 1, titles.get(titles.size() - 1) + " (и ещё " + (ids.size() - 7) + ")");
      out.put(top, titles);
    }
    return out;
  }

  /** Добавляет верхнюю заметку и состав колоды в транзакции вызывающего кода. */
  void attachDeck(long top, Collection<Long> members) {
    db.execSQL("INSERT INTO note_groups(top) VALUES(?)", new Object[] {top});
    for (long member : members)
      db.execSQL(
          "INSERT OR REPLACE INTO note_members(note,top) VALUES(?,?)", new Object[] {member, top});
  }

  /** «Разобрать колоду»: исходные возвращаются в список, сводка остаётся обычной заметкой. */
  public void dissolve(long top) {
    db.delete("note_groups", "top=?", new String[] {"" + top});
  }

  /** «Убрать из колоды»: заметка возвращается в список; пустая колода распадается. */
  public void removeMember(long note) {
    db.delete("note_members", "note=?", new String[] {"" + note});
    cleanup();
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

  /**
   * Верхняя заметка каждой стопки одним запросом: и для самой верхней, и для исходных. Заметок вне
   * стопок в карте нет (для них {@link #top} вернул бы 0). Нужна списку, чтобы не спрашивать базу
   * про каждую заметку отдельно.
   */
  public Map<Long, Long> topMap() {
    Map<Long, Long> out = new HashMap<>();
    try (Cursor c = db.rawQuery("SELECT note, top FROM note_members", null)) {
      while (c.moveToNext()) out.put(c.getLong(0), c.getLong(1));
    }
    try (Cursor c = db.rawQuery("SELECT top FROM note_groups", null)) {
      while (c.moveToNext()) out.put(c.getLong(0), c.getLong(0));
    }
    return out;
  }

  /** Состав всех стопок одним запросом: верхняя заметка → исходные по возрастанию номера. */
  public Map<Long, List<Long>> membersMap() {
    Map<Long, List<Long>> out = new HashMap<>();
    try (Cursor c = db.rawQuery("SELECT top, note FROM note_members ORDER BY top, note", null)) {
      while (c.moveToNext()) out.computeIfAbsent(c.getLong(0), k -> new ArrayList<>()).add(c.getLong(1));
    }
    return out;
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
