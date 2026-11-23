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
    /** Добавить новые записи в существующую колоду и обновить её сводку (этап 3). */
    EXTEND("extend"),
    TRASH("trash"),
    REMIND("remind"),
    /** Переместить явно выбранные заметки в новую или существующую папку (команды в чате). */
    FOLDER("folder"),
    /** Снять все напоминания только у заметок с полностью выполненным планом. */
    CLEAR_REMINDERS("clear_reminders"),
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

  public static final int MAX_SOURCES = 8, MAX_COMMAND_SOURCES = 24,
      MAX_TITLE = 160,
      MAX_BODY = 24000,
      MAX_REASON = 1000,
      MAX_QUESTION = 300,
      MAX_OPTIONS = 4,
      MAX_OPTION = 40,
      MAX_TIMES = 40,
      MAX_FOLDER = 60,
      MAX_ASK_KIND = 24;

  /** Причины, по которым агент может предложить заметку в корзину (решение 2). */
  public static final List<String> TRASH_REASONS = List.of("empty", "duplicate", "merged");

  /** Прежние виды предложений менеджера (до этапа 3); merge и link теперь становятся group. */
  public static final List<String> LEGACY = List.of("merge", "link", "task", "question", "clarify");

  public final Kind kind;
  /** Заметка действия (name, remind, ask, plan, check_in); 0 — нет. */
  public long note;
  /** Исходные заметки (create, group, extend) или заметки для корзины (trash), по возрастанию. */
  public final List<Long> notes = new ArrayList<>();
  /** Колода (номер её верхней заметки) для extend; 0 — нет. */
  public long stack;
  /**
   * Для group: какие исходные модель считает пустыми, дублями или целиком вошедшими в сводку. Их
   * можно отправить в корзину галочкой в карточке (по умолчанию выключена, решение 2).
   */
  public final List<Long> trash = new ArrayList<>();

  public String title = "", body = "", reason = "", question = "", folder = "", askKind = "";
  /** Проверенные поисковые подсказки для create; не меняют авторский текст как инструкции. */
  public JSONObject metadata = new JSONObject();
  public final List<String> options = new ArrayList<>();
  /** Моменты напоминания (миллисекунды), по возрастанию, без повторов. */
  public final List<Long> times = new ArrayList<>();

  public int lead = -1, repeat = ReminderPolicy.REPEAT_NONE;
  /** Даты — разные дела, каждой нужна своя заметка (2.3). */
  public boolean separate;
  /** Дерево плана в виде ответа модели {@code {"nodes":[...]}}; разбирает {@link PlanTree}. */
  public JSONObject tree;
  /**
   * Вид карточки колокольчика (group, extend, task, question, clarify), в которой действие
   * показывается; пусто — показать пока нельзя.
   */
  public String legacy = "";

  private AgentAction(Kind kind) {
    this.kind = kind;
  }

  /** Применяется само, без кнопки (решение пользователя 1). */
  public boolean automatic() {
    return kind == Kind.NAME;
  }

  /**
   * Что известно о входе запроса: какие заметки были в нём, какие пришли целиком и какие колоды
   * (верхние заметки) показаны модели для extend.
   */
  public static final class Scope {
    public final Set<Long> known = new HashSet<>(), complete = new HashSet<>();
    public final Set<Long> stacks = new HashSet<>();
    /** Планы, в которых все проверяемые пункты отмечены выполненными. */
    public final Set<Long> completed = new HashSet<>();
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

    public Scope completed(long id) {
      completed.add(id);
      return this;
    }

    public Scope stack(long top) {
      stacks.add(top);
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
        a.metadata = raw.optJSONObject("metadata");
        if (a.metadata == null) a.metadata = new JSONObject();
        a.legacy = a.notes.isEmpty() ? "" : "task";
      }
      case GROUP -> {
        // Текст сводки модель не пишет: его готовит отдельный запрос по нажатию пользователя.
        if (!a.readNotes(raw, "sources", scope, 2) || !a.readTitle(raw, false)) return null;
        a.body = raw.optString("body", "").trim();
        if (a.body.length() > MAX_BODY) return null;
        JSONArray extra = raw.optJSONArray("trash");
        for (int i = 0; extra != null && i < extra.length(); i++) {
          long id = extra.optLong(i, -1);
          if (a.notes.contains(id) && !a.trash.contains(id)) a.trash.add(id);
        }
        Collections.sort(a.trash);
        a.legacy = "group";
      }
      case EXTEND -> {
        a.stack = raw.optLong("stack", -1);
        if (!scope.stacks.contains(a.stack) || !a.readNotes(raw, "sources", scope, 1)) return null;
        if (a.notes.contains(a.stack)) return null;
        a.legacy = "extend";
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
      case FOLDER -> {
        if (!a.readNotes(raw, "notes", scope, 1)) return null;
        a.folder = raw.optString("folder", "").trim();
        if (a.folder.isEmpty() || a.folder.length() > MAX_FOLDER || !FolderPolicy.valid(a.folder))
          return null;
      }
      case CLEAR_REMINDERS -> {
        if (!a.readNotes(raw, "notes", scope, 1)) return null;
        for (long id : a.notes) if (!scope.completed.contains(id)) return null;
      }
      case ASK -> {
        if (!a.readNote(raw, scope, true) || !a.readQuestion(raw) || !a.readOptions(raw, 0))
          return null;
        a.askKind = raw.optString("kind", "").trim();
        if (a.askKind.length() > MAX_ASK_KIND) return null;
        // Вопрос по заметке показывается всегда: варианты кнопками (0–4) и свой ответ.
        a.legacy = "clarify";
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
    boolean deck = type.equals("merge") || type.equals("link");
    Kind kind =
        switch (type) {
          case "merge", "link" -> Kind.GROUP;
          case "clarify" -> Kind.ASK;
          default -> Kind.CREATE;
        };
    AgentAction a = new AgentAction(kind);
    a.legacy = deck ? "group" : type;
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
    if (kind == Kind.EXTEND) {
      List<Long> all = new ArrayList<>(notes);
      all.add(stack);
      Collections.sort(all);
      return all;
    }
    if (!notes.isEmpty()) return Collections.unmodifiableList(notes);
    return note == 0 ? List.of() : List.of(note);
  }

  /** Сериализация разрешённых команд в messages.draft; другие виды не выдаются за команду. */
  public JSONObject commandJson() throws JSONException {
    if (kind != Kind.CREATE && kind != Kind.REMIND && kind != Kind.FOLDER
        && kind != Kind.CLEAR_REMINDERS) throw new JSONException("Не команда: " + kind.wire);
    JSONObject out = new JSONObject().put("action", kind.wire).put("reason", reason);
    if (kind == Kind.CREATE) {
      JSONArray ids = new JSONArray();
      for (long id : notes) ids.put(id);
      out.put("sources", ids)
          .put("title", title)
          .put("body", body)
          .put("folder", folder)
          .put("metadata", metadata);
    } else if (kind == Kind.REMIND) {
      JSONArray dates = new JSONArray();
      for (long at : times)
        dates.put(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm XXX", Locale.ROOT)
            .format(java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())));
      // Миллисекунды сохраняются отдельно, чтобы сериализация не зависела от смены часового пояса.
      JSONArray exact = new JSONArray();
      for (long at : times) exact.put(at);
      out.put("note", note).put("times", exact).put("lead", lead).put("repeat", repeat)
          .put("local_times", dates);
    } else {
      JSONArray ids = new JSONArray();
      for (long id : notes) ids.put(id);
      out.put("notes", ids);
      if (kind == Kind.FOLDER) out.put("folder", folder);
    }
    return out;
  }

  /** Действие в виде прежнего предложения колокольчика без отметок версий; иначе {@code null}. */
  public JSONObject proposal() throws JSONException {
    if (legacy.isEmpty()) return null;
    JSONArray ids = new JSONArray();
    for (long id : sources()) ids.put(id);
    JSONArray choices = new JSONArray();
    for (String o : options) choices.put(o);
    JSONObject out =
        new JSONObject()
            .put("type", legacy)
            .put("reason", legacy.equals("clarify") ? question : reason)
            .put("title", title)
            .put("body", body)
            .put("sources", ids)
            .put("options", choices);
    if (kind == Kind.GROUP) {
      JSONArray extra = new JSONArray();
      for (long id : trash) extra.put(id);
      out.put("trash", extra);
    }
    if (kind == Kind.EXTEND) out.put("stack", stack);
    return out;
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
    int limit = kind == Kind.FOLDER || kind == Kind.CLEAR_REMINDERS ? MAX_COMMAND_SOURCES : MAX_SOURCES;
    if (ids.length() > limit) return false;
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
