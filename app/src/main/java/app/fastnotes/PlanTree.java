package app.fastnotes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Дерево плана одной заметки (docs/AGENT-PLANNER.md, 2.5): шаги, время, люди, что взять, место,
 * пометки. Хранится в столбце {@code notes.plan} как JSON {@code {"v":1,"nodes":[...]}} с короткими
 * ключами; модель видит и присылает его в развёрнутом виде ({@link #forModel}, {@link #fromModel}).
 * Чистая Java без Android, проверяется на JVM.
 */
public final class PlanTree {
  /** Виды пунктов. Текст у всех свободный, вид задаёт значок и поведение. */
  public static final String STEP = "step",
      TIME = "time",
      PERSON = "person",
      ITEM = "item",
      PLACE = "place",
      NOTE = "note";

  public static final List<String> KINDS = List.of(STEP, TIME, PERSON, ITEM, PLACE, NOTE);
  public static final int MAX_NODES = 120, MAX_DEPTH = 3, MAX_TEXT = 200, MAX_WHO = 60;
  private static final DateTimeFormatter MODEL_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
  private static final DateTimeFormatter SHOWN_TIME =
      DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("ru"));

  public static final class Node {
    public String id = "", kind = STEP, text = "", who = "";
    public boolean done;
    public long at;
    public final List<Node> children = new ArrayList<>();

    /** Шаги и «что взять» отмечаются галочкой. */
    public boolean checkable() {
      return STEP.equals(kind) || ITEM.equals(kind);
    }
  }

  public final List<Node> nodes = new ArrayList<>();
  private int nextId = 1;

  public boolean isEmpty() {
    return nodes.isEmpty();
  }

  public static String label(String kind) {
    return switch (kind) {
      case TIME -> "Когда";
      case PERSON -> "Кто";
      case ITEM -> "Взять";
      case PLACE -> "Где";
      case NOTE -> "Заметка";
      default -> "Шаг";
    };
  }

  // ---------- хранение ----------

  /** Разбирает сохранённый план; испорченный или пустой текст даёт пустое дерево. */
  public static PlanTree parse(String stored) {
    PlanTree tree = new PlanTree();
    if (stored == null || stored.trim().isEmpty()) return tree;
    try {
      JSONArray rows = new JSONObject(stored).optJSONArray("nodes");
      int[] budget = {MAX_NODES};
      tree.readStored(rows, tree.nodes, 1, budget);
      tree.renumber();
    } catch (Exception e) {
      tree.nodes.clear();
    }
    return tree;
  }

  private void readStored(JSONArray rows, List<Node> into, int depth, int[] budget) {
    for (int i = 0; rows != null && i < rows.length() && budget[0] > 0; i++) {
      JSONObject o = rows.optJSONObject(i);
      if (o == null) continue;
      Node n = new Node();
      n.id = o.optString("id", "");
      n.kind = kind(o.optString("k", STEP));
      n.text = clip(o.optString("t", ""), MAX_TEXT);
      n.who = clip(o.optString("w", ""), MAX_WHO);
      n.done = o.optBoolean("d", false);
      n.at = TIME.equals(n.kind) ? Math.max(0, o.optLong("at", 0)) : 0;
      if (n.text.isEmpty()) continue;
      budget[0]--;
      into.add(n);
      if (depth < MAX_DEPTH) readStored(o.optJSONArray("c"), n.children, depth + 1, budget);
    }
  }

  public String toJson() {
    if (nodes.isEmpty()) return "";
    try {
      return new JSONObject().put("v", 1).put("nodes", writeStored(nodes)).toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static JSONArray writeStored(List<Node> list) throws Exception {
    JSONArray out = new JSONArray();
    for (Node n : list) {
      JSONObject o = new JSONObject().put("id", n.id).put("k", n.kind).put("t", n.text);
      if (!n.who.isEmpty()) o.put("w", n.who);
      if (n.done) o.put("d", true);
      if (n.at > 0) o.put("at", n.at);
      if (!n.children.isEmpty()) o.put("c", writeStored(n.children));
      out.put(o);
    }
    return out;
  }

  /** Даёт пунктам без номера или с повтором новые номера; номера нужны интерфейсу для правок. */
  private void renumber() {
    Map<String, Boolean> seen = new HashMap<>();
    List<Node> all = all();
    for (Node n : all)
      if (n.id.matches("n\\d{1,6}")) nextId = Math.max(nextId, Integer.parseInt(n.id.substring(1)) + 1);
    for (Node n : all) {
      if (!n.id.matches("n\\d{1,6}") || seen.containsKey(n.id)) n.id = "n" + nextId++;
      seen.put(n.id, true);
    }
  }

  // ---------- модель ----------

  /**
   * Разбирает план из ответа модели: {@code {"nodes":[{"kind","text","who","at","done","children"}]}}.
   * Неизвестный вид становится шагом, пустые пункты отбрасываются, глубина и число пунктов
   * ограничены. Время — «ГГГГ-ММ-ДД ЧЧ:ММ» или только дата (тогда 09:00).
   */
  public static PlanTree fromModel(JSONObject plan, ZoneId zone) {
    PlanTree tree = new PlanTree();
    if (plan == null) return tree;
    int[] budget = {MAX_NODES};
    tree.readModel(plan.optJSONArray("nodes"), tree.nodes, 1, budget, zone);
    tree.renumber();
    return tree;
  }

  private void readModel(JSONArray rows, List<Node> into, int depth, int[] budget, ZoneId zone) {
    for (int i = 0; rows != null && i < rows.length() && budget[0] > 0; i++) {
      JSONObject o = rows.optJSONObject(i);
      if (o == null) continue;
      Node n = new Node();
      n.kind = kind(o.optString("kind", STEP));
      n.text = clip(o.optString("text", ""), MAX_TEXT);
      n.who = clip(o.optString("who", ""), MAX_WHO);
      n.done = o.optBoolean("done", false) && n.checkable();
      if (TIME.equals(n.kind)) n.at = time(o.optString("at", ""), zone);
      if (n.text.isEmpty()) continue;
      budget[0]--;
      into.add(n);
      JSONArray children = o.optJSONArray("children");
      if (depth < MAX_DEPTH) readModel(children, n.children, depth + 1, budget, zone);
      else flatten(children, into, budget, zone); // слишком глубоко — поднимаем на уровень выше
    }
  }

  private void flatten(JSONArray rows, List<Node> into, int[] budget, ZoneId zone) {
    for (int i = 0; rows != null && i < rows.length() && budget[0] > 0; i++) {
      JSONObject o = rows.optJSONObject(i);
      if (o == null) continue;
      JSONObject copy = new JSONObject();
      try {
        for (String key : new String[] {"kind", "text", "who", "at", "done"})
          if (o.has(key)) copy.put(key, o.opt(key));
      } catch (Exception e) {
        continue; // на Android put объявляет JSONException; здесь он невозможен
      }
      readModel(new JSONArray().put(copy), into, MAX_DEPTH, budget, zone);
      flatten(o.optJSONArray("children"), into, budget, zone);
    }
  }

  /** План в том виде, в каком его видит модель: виды словами, время текстом. */
  public JSONObject forModel(ZoneId zone) {
    try {
      return new JSONObject().put("nodes", writeModel(nodes, zone));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static JSONArray writeModel(List<Node> list, ZoneId zone) throws Exception {
    JSONArray out = new JSONArray();
    for (Node n : list) {
      JSONObject o = new JSONObject().put("kind", n.kind).put("text", n.text);
      if (!n.who.isEmpty()) o.put("who", n.who);
      if (n.at > 0)
        o.put("at", MODEL_TIME.format(Instant.ofEpochMilli(n.at).atZone(zone)));
      if (n.done) o.put("done", true);
      if (!n.children.isEmpty()) o.put("children", writeModel(n.children, zone));
      out.put(o);
    }
    return out;
  }

  static long time(String raw, ZoneId zone) {
    String s = raw == null ? "" : raw.trim().replace('T', ' ');
    try {
      if (s.length() >= 16)
        return LocalDateTime.parse(s.substring(0, 16), MODEL_TIME).atZone(zone).toInstant().toEpochMilli();
      if (s.length() == 10)
        return LocalDate.parse(s).atTime(9, 0).atZone(zone).toInstant().toEpochMilli();
    } catch (Exception ignored) {
      // Неразборчивое время — пункт остаётся без даты.
    }
    return 0;
  }

  // ---------- правки ----------

  public List<Node> all() {
    List<Node> out = new ArrayList<>();
    collect(nodes, out);
    return out;
  }

  private static void collect(List<Node> list, List<Node> out) {
    for (Node n : list) {
      out.add(n);
      collect(n.children, out);
    }
  }

  public Node find(String id) {
    for (Node n : all()) if (n.id.equals(id)) return n;
    return null;
  }

  public boolean toggle(String id) {
    Node n = find(id);
    if (n == null || !n.checkable()) return false;
    n.done = !n.done;
    return true;
  }

  public boolean edit(String id, String text) {
    Node n = find(id);
    String clean = clip(text, MAX_TEXT);
    if (n == null || clean.isEmpty()) return false;
    n.text = clean;
    return true;
  }

  public boolean remove(String id) {
    return remove(nodes, id);
  }

  private static boolean remove(List<Node> list, String id) {
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i).id.equals(id)) {
        list.remove(i);
        return true;
      }
      if (remove(list.get(i).children, id)) return true;
    }
    return false;
  }

  /** Добавляет пункт в конец плана или внутрь пункта {@code parent}; null, если нельзя. */
  public Node add(String parent, String kind, String text) {
    String clean = clip(text, MAX_TEXT);
    if (clean.isEmpty() || all().size() >= MAX_NODES) return null;
    List<Node> into = nodes;
    if (parent != null) {
      Node p = find(parent);
      if (p == null || depth(nodes, parent, 1) >= MAX_DEPTH) return null;
      into = p.children;
    }
    Node n = new Node();
    n.id = "n" + nextId++;
    n.kind = kind(kind);
    n.text = clean;
    into.add(n);
    return n;
  }

  private static int depth(List<Node> list, String id, int level) {
    for (Node n : list) {
      if (n.id.equals(id)) return level;
      int inner = depth(n.children, id, level + 1);
      if (inner > 0) return inner;
    }
    return 0;
  }

  /**
   * Переносит отметки «сделано» из прежнего плана в новый, присланный моделью: совпадение по виду
   * и тексту без учёта регистра и пробелов. Модель могла забыть отметки — пользователь не должен
   * отмечать заново.
   */
  public void keepDone(PlanTree previous) {
    if (previous == null) return;
    Map<String, Boolean> done = new HashMap<>();
    for (Node n : previous.all()) if (n.done) done.put(key(n), true);
    for (Node n : all()) if (n.checkable() && done.containsKey(key(n))) n.done = true;
  }

  private static String key(Node n) {
    return n.kind + "|" + n.text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
  }

  // ---------- показ ----------

  public int checkable() {
    int count = 0;
    for (Node n : all()) if (n.checkable()) count++;
    return count;
  }

  public int done() {
    int count = 0;
    for (Node n : all()) if (n.checkable() && n.done) count++;
    return count;
  }

  /**
   * «Выполнено» на ответ «Сделано» (этап 5): отмечает все шаги и «что взять». Возвращает, сколько
   * пунктов отмечено сейчас.
   */
  public int completeAll() {
    int changed = 0;
    for (Node n : all())
      if (n.checkable() && !n.done) {
        n.done = true;
        changed++;
      }
    return changed;
  }

  /** «План 3/8» для карточки заметки; пусто, если плана нет. */
  public String badge() {
    if (nodes.isEmpty()) return "";
    int total = checkable();
    return total == 0 ? "План" : "План " + done() + "/" + total;
  }

  /** Строка пункта для показа: время и ответственный в начале и в конце. */
  public static String line(Node n, ZoneId zone) {
    StringBuilder s = new StringBuilder();
    if (n.at > 0) s.append(SHOWN_TIME.format(Instant.ofEpochMilli(n.at).atZone(zone))).append(" — ");
    s.append(n.text);
    if (!n.who.isEmpty()) s.append(" (").append(n.who).append(')');
    return s.toString();
  }

  /** План текстом с отступами — для просмотра черновика в чате и для экспорта. */
  public List<String> outline(ZoneId zone) {
    List<String> out = new ArrayList<>();
    outline(nodes, 0, zone, out);
    return out;
  }

  private static void outline(List<Node> list, int level, ZoneId zone, List<String> out) {
    for (Node n : list) {
      String mark =
          n.checkable() ? (n.done ? "☑ " : "☐ ") : (label(n.kind) + ": ");
      out.add("  ".repeat(level) + mark + line(n, zone));
      outline(n.children, level + 1, zone, out);
    }
  }

  private static String kind(String raw) {
    String k = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    return KINDS.contains(k) ? k : STEP;
  }

  static String clip(String raw, int max) {
    String s = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
    return s.length() <= max ? s : s.substring(0, max).trim();
  }
}
