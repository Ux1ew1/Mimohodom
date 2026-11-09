package app.fastnotes;

import java.util.*;

/** Platform-independent retrieval; can move into a shared module for other clients. */
public final class Retrieval {
  public static final int MAX_NOTES = 8, MAX_CONTEXT_CHARS = 12000;
  private static final Set<String> STOP =
      new HashSet<>(
          Arrays.asList(
              "как", "какой", "какие", "есть", "мне", "для", "это", "что", "все", "про", "мои",
              "или", "его", "где", "они", "the", "and"));

  static String normalize(String s) {
    return s.toLowerCase(Locale.ROOT).replace('ё', 'е');
  }

  static List<String> terms(String text) {
    List<String> out = new ArrayList<>();
    for (String w : normalize(text).split("[^\\p{L}\\p{N}]+"))
      if (w.length() > 2 && !STOP.contains(w))
        out.add(w.length() > 5 ? w.substring(0, w.length() - 2) : w);
    String q = normalize(text);
    if (q.contains("рецепт") || q.contains("приготов"))
      out.addAll(
          Arrays.asList(
              "ингредиент",
              "мука",
              "сахар",
              "духовк",
              "варить",
              "жарить",
              "салат",
              "суп",
              "грамм"));
    if (q.contains("праздник"))
      out.addAll(Arrays.asList("рождени", "юбилей", "годовщин", "праздник", "новый год", "свадьб"));
    return out;
  }

  public static List<Note> select(List<Note> notes, String question, long now) {
    List<String> words = terms(question);
    String q = normalize(question);
    boolean upcoming =
        q.contains("ближайш")
            || q.contains("напомин")
            || q.contains("завтра")
            || q.contains("сегодня");
    boolean holiday = q.contains("праздник");
    Map<Long, Integer> scores = new HashMap<>();
    for (Note n : notes) {
      int score = 0;
      String title = normalize(n.title),
          body = normalize(n.body),
          metadata = normalize(n.searchText);
      for (String w : words) {
        if (title.contains(w)) score += 5;
        if (body.contains(w)) score += 2;
        if (metadata.contains(w)) score += 3;
      }
      if (upcoming && n.reminder > 0 && n.reminder >= now && (!holiday || score > 0)) score += 4;
      scores.put(n.id, score);
    }
    List<Note> result = new ArrayList<>(notes);
    result.sort(
        (a, b) -> {
          if (upcoming) {
            boolean ar = a.reminder > 0 && a.reminder >= now && scores.get(a.id) > 0;
            boolean br = b.reminder > 0 && b.reminder >= now && scores.get(b.id) > 0;
            if (ar != br) return ar ? -1 : 1;
            if (ar && a.reminder != b.reminder) return Long.compare(a.reminder, b.reminder);
          }
          int cmp = Integer.compare(scores.get(b.id), scores.get(a.id));
          return cmp != 0 ? cmp : Long.compare(b.updated, a.updated);
        });
    boolean any = result.stream().anyMatch(n -> scores.get(n.id) > 0);
    if (any) result.removeIf(n -> scores.get(n.id) == 0);
    return new ArrayList<>(result.subList(0, Math.min(MAX_NOTES, result.size())));
  }

  public static String excerpt(String body, String query, int limit) {
    if (limit < 3) return "";
    if (body.length() <= limit) return body;
    int start = 0;
    String normalized = normalize(body);
    for (String term : terms(query)) {
      int index = normalized.indexOf(term);
      if (index >= 0) {
        start = Math.max(0, index - 180);
        break;
      }
    }
    if (start > 0 && Character.isLowSurrogate(body.charAt(start))) start--;
    int end = Math.min(body.length(), start + limit - 2);
    if (end < body.length() && Character.isLowSurrogate(body.charAt(end))) end--;
    return (start > 0 ? "…" : "") + body.substring(start, end) + "…";
  }
}
