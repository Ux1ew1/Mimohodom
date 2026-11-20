package app.fastnotes;

import java.time.*;
import java.util.*;
import org.json.*;

/**
 * Одно действие агента из словаря (docs/AGENT-PLANNER.md, 2.1). Модель ничего не выполняет сама:
 * она присылает действия, этот класс проверяет поля и пределы, а приложение применяет только
 * проверенные действия и только по правилам — само ставит лишь {@link Kind#NAME}, остальное
 * после нажатия пользователя. Чистая Java без Android, проверяется {@code tests/AgentActionTest}.
 */
public final class AgentAction {
  public enum Kind {
    NAME("name"),
    CREATE("create"),
    GROUP("group"),
    TRASH("trash"),
    REMIND("remind"),
    ASK("ask"),
    PLAN("plan"),
    CHECK_IN("check_in");

    public final String wire;

    Kind(String wire) {
      this.wire = wire;
    }

    public static Kind of(String wire) {
      for (Kind k : values()) if (k.wire.equals(wire)) return k;
      return null;
    }
  }

  public static final int MAX_SOURCES = 8,
      MAX_TITLE = 160,
      MAX_BODY = 24000,
      MAX_REASON = 1000,
      MAX_QUESTION = 300,
      MAX_OPTIONS = 4,
      MAX_OPTION = 40,
      MAX_TIMES = 20,
      MAX_FOLDER = 60,
      MAX_ASK_KIND = 24;

  /** Причины, по которым агент может предложить заметку в корзину (решение 2). */
  public static final List<String> TRASH_REASONS = List.of("empty", "duplicate", "merged");

  /** Старые виды предложений менеджера, которые понимает приложение до этапа 3. */
  public static final List<String> LEGACY = List.of("merge", "link", "task", "question", "clarify");

  public final Kind kind;
  /** Заметка действия (name, remind, ask, plan, check_in); 0 — нет. */
  public long note;
  /** Исходные заметки (create, group) или заметки для корзины (trash), по возрастанию номера. */
  public final List<Long> notes = new ArrayList<>();

  public String title = "", body = "", reason = "", question = "", folder = "", askKind = "";
  public final List<String> options = new ArrayList<>();
  /** Моменты напоминания (миллисекунды), по возрастанию, без повторов. */
  public final List<Long> times = new ArrayList<>();

  public int lead = -1, repeat = ReminderPolicy.REPEAT_NONE;
  /** Даты — разные дела, каждой нужна своя заметка (2.3). */
  public boolean separate;
  /** Дерево плана в виде ответа модели {@code {"nodes":[...]}}; разбирает {@link PlanTree}. */
  public JSONObject tree;
  /**
   * Старый вид предложения (merge/link/task/question/clarify), из которого получено действие, или
   * тот, в котором его покажет колокольчик; пусто — показать нельзя.
   */
  public String legacy = "";

  private AgentAction(Kind kind) {
    this.kind = kind;
  }

  /** Применяется само, без кнопки (решение пользователя 1). */
  public boolean automatic() {
    return kind == Kind.NAME;
  }

  /** Что известно о входе запроса: какие заметки были в нём и какие пришли целиком. */
  public static final class Scope {
    public final Set<Long> known = new HashSet<>(), complete = new HashSet<>();
    public final long now;
    public final ZoneId zone;

    public Scope(long now, ZoneId zone) {
      this.now = now;
      this.zone = zone;
    }

    public Scope note(long id, boolean whole) {
      known.add(id);
      if (whole) complete.add(id);
      return this;
    }
  }

  /** Разбирает действие нового словаря {@code {"action":"group",...}}; неверное — {@code null}. */
  public static AgentAction parse(JSONObject raw, Scope scope) {
    if (raw == null) return null;
    Kind kind = Kind.of(raw.optString("action", raw.optString("type")).trim());
    if (kind == null) return null;
    AgentAction a = new AgentAction(kind);
    a.reason = raw.optString("reason", "").trim();
    if (a.reason.length() > MAX_REASON) return null;
    switch (kind) {
      case NAME -> {
        if (!a.readNote(raw, scope, true) || !a.readTitle(raw, true)) return null;
      }
      case CREATE -> {
        if (!a.readNotes(raw, "sources", scope, 0) || !a.readTitle(raw, true) || !a.readBody(raw))
          return null;
        a.folder = raw.optString("folder", "").trim();
        if (a.folder.length() > MAX_FOLDER) return null;
        a.legacy = a.notes.isEmpty() ? "" : "task";
      }
      case GROUP -> {
        if (!a.readNotes(raw, "sources", scope, 2) || !a.readTitle(raw, true) || !a.readBody(raw))
          return null;
        a.legacy = scope.complete.containsAll(a.notes) ? "merge" : "link";
      }
      case TRASH -> {
        if (!a.readNotes(raw, "notes", scope, 1)) return null;
        String why = raw.optString("reason", "").trim();
        if (!TRASH_REASONS.contains(why)) return null;
      }
      case REMIND -> {
        if (!a.readNote(raw, scope, true) || !a.readTimes(raw, scope)) return null;
        a.lead = raw.optInt("lead", -1);
        if (a.lead != -1 && Arrays.stream(ReminderPolicy.LEADS).noneMatch(l -> l == a.lead))
          return null;
        a.repeat = raw.optInt("repeat", ReminderPolicy.REPEAT_NONE);
        if (a.repeat < ReminderPolicy.REPEAT_NONE || a.repeat > ReminderPolicy.REPEAT_YEARLY)
          return null;
        if (a.repeat != ReminderPolicy.REPEAT_NONE && a.times.size() != 1) return null;
        a.separate = raw.optBoolean("separate", false);
      }
      case ASK -> {
        if (!a.readNote(raw, scope, true) || !a.readQuestion(raw) || !a.readOptions(raw, 0))
          return null;
        a.askKind = raw.optString("kind", "").trim();
        if (a.askKind.length() > MAX_ASK_KIND) return null;
        a.legacy = a.options.size() >= 2 ? "clarify" : "";
      }
      case PLAN -> {
        if (!a.readNote(raw, scope, true)) return null;
        Object tree = raw.opt("tree");
        try {
          if (tree instanceof JSONArray rows) a.tree = new JSONObject().put("nodes", rows);
          else if (tree instanceof JSONObject o) a.tree = o;
          else return null;
        } catch (JSONException e) {
          return null;
        }
        if (PlanTree.fromModel(a.tree, scope.zone).isEmpty()) return null;
      }
      case CHECK_IN -> {
        if (!a.readNote(raw, scope, false) || !a.readQuestion(raw) || !a.readOptions(raw, 0))
          return null;
      }
    }
    return a;
  }

  /**
   * Старое предложение менеджера {@code {"type":"merge",...}} как действие: merge/link → group,
   * task/question → create, clarify → ask. Правила проверки прежние (как в ManagerAnalysis до
   * этапа 2), поэтому старые ответы модели разбираются так же.
   */
  public static AgentAction legacy(JSONObject p, Scope scope) {
    if (p == null) return null;
    String type = p.optString("type"), reason = p.optString("reason").trim();
    if (!LEGACY.contains(type) || reason.isEmpty() || reason.length() > MAX_REASON) return null;
    JSONArray ids = p.optJSONArray("sources");
    if (ids == null || ids.length() == 0 || ids.length() > MAX_SOURCES) return null;
    TreeSet<Long> sorted = new TreeSet<>();
    for (int j = 0; j < ids.length(); j++) {
      long id = ids.optLong(j, -1);
      if (!scope.known.contains(id)
          || !sorted.add(id)
          || (type.equals("merge") && !scope.complete.contains(id))) return null;
    }
    if ((type.equals("merge") || type.equals("link")) && sorted.size() < 2) return null;
    Kind kind =
        switch (type) {
          case "merge", "link" -> Kind.GROUP;
          case "clarify" -> Kind.ASK;
          default -> Kind.CREATE;
        };
    AgentAction a = new AgentAction(kind);
    a.legacy = type;
    a.reason = reason;
    if (type.equals("clarify")) {
      if (sorted.size() != 1 || reason.length() > MAX_QUESTION) return null;
      a.note = sorted.first();
      a.question = reason;
      if (!a.readOptions(p, 2)) return null;
    } else a.notes.addAll(sorted);
    String title = p.optString("title").trim(), body = p.optString("body").trim();
    if (!type.equals("link") && !type.equals("clarify") && (title.isEmpty() || body.isEmpty()))
      return null;
    if (title.length() > MAX_TITLE || body.length() > MAX_BODY) return null;
    a.title = title;
    a.body = body;
    return a;
  }

  /** Номера заметок, на которые действие ссылается (для проверки, что они не изменились). */
  public List<Long> sources() {
    if (!notes.isEmpty()) return Collections.unmodifiableList(notes);
    return note == 0 ? List.of() : List.of(note);
  }

  /** Действие в виде прежнего предложения колокольчика без отметок версий; иначе {@code null}. */
  public JSONObject proposal() throws JSONException {
    if (legacy.isEmpty()) return null;
    JSONArray ids = new JSONArray();
    for (long id : sources()) ids.put(id);
    JSONArray choices = new JSONArray();
    for (String o : options) choices.put(o);
    return new JSONObject()
        .put("type", legacy)
        .put("reason", legacy.equals("clarify") ? question : reason)
        .put("title", title)
        .put("body", body)
        .put("sources", ids)
        .put("options", choices);
  }

  // --- разбор полей ---

  private boolean readNote(JSONObject raw, Scope scope, boolean required) {
    if (!raw.has("note") || raw.isNull("note")) return !required;
    note = raw.optLong("note", -1);
    if (!required && note == 0) return true;
    return scope.known.contains(note);
  }

  private boolean readNotes(JSONObject raw, String field, Scope scope, int min) {
    JSONArray ids = raw.optJSONArray(field);
    if (ids == null) return min == 0 && !raw.has(field);
    if (ids.length() > MAX_SOURCES) return false;
    TreeSet<Long> sorted = new TreeSet<>();
    for (int i = 0; i < ids.length(); i++) {
      long id = ids.optLong(i, -1);
      if (!scope.known.contains(id) || !sorted.add(id)) return false;
    }
    notes.addAll(sorted);
    return sorted.size() >= min;
  }

  private boolean readTitle(JSONObject raw, boolean required) {
    title = raw.optString("title", "").trim();
    return (!required || !title.isEmpty()) && title.length() <= MAX_TITLE;
  }

  private boolean readBody(JSONObject raw) {
    body = raw.optString("body", "").trim();
    return !body.isEmpty() && body.length() <= MAX_BODY;
  }

  private boolean readQuestion(JSONObject raw) {
    question = raw.optString("question", "").trim();
    return !question.isEmpty() && question.length() <= MAX_QUESTION;
  }

  private boolean readOptions(JSONObject raw, int min) {
    JSONArray rows = raw.optJSONArray("options");
    Set<String> seen = new HashSet<>();
    for (int i = 0; rows != null && i < rows.length() && options.size() < MAX_OPTIONS; i++) {
      String option = rows.optString(i, "").trim();
      if (!option.isEmpty()
          && option.length() <= MAX_OPTION
          && seen.add(option.toLowerCase(Locale.ROOT))) options.add(option);
    }
    return options.size() >= min;
  }

  private boolean readTimes(JSONObject raw, Scope scope) {
    JSONArray rows = raw.optJSONArray("times");
    if (rows == null || rows.length() == 0 || rows.length() > MAX_TIMES) return false;
    TreeSet<Long> sorted = new TreeSet<>();
    for (int i = 0; i < rows.length(); i++) {
      long at = moment(rows.opt(i), scope.zone);
      if (at <= scope.now) return false;
      sorted.add(at);
    }
    times.addAll(sorted);
    return true;
  }

  /**
   * Момент из ответа модели: число миллисекунд, ISO со смещением или местное «ГГГГ-ММ-ДДTЧЧ:ММ»
   * (и с пробелом); только дата — 09:00, как у сроков без времени. Неверное — 0.
   */
  static long moment(Object value, ZoneId zone) {
    if (value instanceof Number n) return n.longValue();
    if (!(value instanceof String s)) return 0;
    String text = s.trim().replace(' ', 'T');
    try {
      return OffsetDateTime.parse(text).toInstant().toEpochMilli();
    } catch (DateTimeException ignored) {
    }
    try {
      return LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli();
    } catch (DateTimeException ignored) {
    }
    try {
      return LocalDate.parse(text).atTime(9, 0).atZone(zone).toInstant().toEpochMilli();
    } catch (DateTimeException ignored) {
    }
    return 0;
  }
}
