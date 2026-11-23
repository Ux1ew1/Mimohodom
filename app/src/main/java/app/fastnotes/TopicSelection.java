package app.fastnotes;

import java.util.*;
import org.json.*;

/** Stable content-based neighbourhoods. No unrelated padding or timestamps in similarity. */
public final class TopicSelection {
  private static final Set<String> STOP =
      new HashSet<>(
          Arrays.asList(
              "сегод", "завтр", "после", "перед", "нужно", "надо", "сдела", "новый", "новая",
              "замен", "купит", "забра", "этого", "котор", "также", "замет", "время"));

  static Set<String> words(Note n) {
    Set<String> out = new TreeSet<>();
    for (String raw : Retrieval.normalize(n.title + " " + n.body).split("[^\\p{L}]+")) {
      if (raw.length() < 4) continue;
      String stem = raw.substring(0, Math.min(5, raw.length()));
      if (STOP.contains(stem)) continue;
      out.add(stem);
      if (raw.startsWith("машин")
          || raw.startsWith("автомоб")
          || raw.startsWith("дворник")
          || raw.startsWith("бардач")
          || raw.startsWith("пробег")
          || raw.equals("поло")
          || raw.startsWith("шиномонт")
          || raw.startsWith("шины")) out.add("#автомобиль");
    }
    return out;
  }

  private static Set<String> topics(Note n) {
    Set<String> out = new TreeSet<>();
    try {
      JSONObject ai = new JSONObject(n.metadata).optJSONObject("ai");
      JSONArray topics = ai == null ? null : ai.optJSONArray("topics");
      if (topics != null)
        for (int i = 0; i < topics.length(); i++) out.add(Retrieval.normalize(topics.getString(i)));
    } catch (Exception ignored) {
    }
    return out;
  }

  public static double similarity(Note a, Note b) {
    Set<String> x = words(a), y = words(b);
    if (x.isEmpty() || y.isEmpty()) return 0;
    int shared = 0;
    for (String word : x) if (y.contains(word)) shared++;
    double cosine = shared / Math.sqrt(x.size() * (double) y.size());
    if (x.contains("#автомобиль") && y.contains("#автомобиль")) cosine += 0.5;
    Set<String> sharedTopics = topics(a);
    sharedTopics.retainAll(topics(b));
    if (!sharedTopics.isEmpty()) cosine += 0.4;
    return cosine;
  }

  public static List<Note> select(List<Note> queue) {
    if (queue.isEmpty()) return new ArrayList<>();
    Note seed = queue.get(0);
    List<Note> related = new ArrayList<>();
    Map<Long, Double> scores = new HashMap<>();
    for (Note n : queue)
      if (n.id != seed.id) {
        double score = similarity(seed, n);
        scores.put(n.id, score);
        if (score >= 0.18) related.add(n);
      }
    related.sort(
        (a, b) -> {
          int score = Double.compare(scores.get(b.id), scores.get(a.id));
          return score != 0 ? score : Long.compare(a.id, b.id);
        });
    List<Note> selected = new ArrayList<>();
    selected.add(seed);
    for (Note n : related) {
      if (selected.size() == 8) break;
      selected.add(n);
    }
    selected.sort(Comparator.comparingLong(n -> n.id));
    return selected;
  }

  /** Карточка колоды: новая (group) или прежние merge/link из старых ответов и базы. */
  public static boolean deck(String type) {
    return type.equals("group") || type.equals("link") || type.equals("merge");
  }

  public static List<JSONObject> proposals(List<Note> batch, List<JSONObject> model)
      throws Exception {
    List<JSONObject> out = new ArrayList<>();
    JSONObject first = null;
    for (JSONObject p : model) {
      if (deck(p.optString("type"))) {
        if (first == null) first = p;
      } else if (out.size() < 3) out.add(p);
    }
    // Колоду предлагаем только по выводу модели и только из тех записей, которые она назвала:
    // одного сходства слов мало (общий магазин или оператор ещё не делают записи одной темой).
    // Одна колода за проверку; название и список «в корзину» от модели сохраняются.
    if (first != null) {
      String reason = first.optString("reason", "").trim();
      out.add(
          first
              .put("type", "group")
              .put(
                  "reason",
                  (reason.isEmpty() ? "Записи об одном и том же." : reason)
                      + " Проверьте состав: их можно собрать в одну колоду с выжимкой ИИ."));
    }
    return out;
  }
}
