package app.fastnotes;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SearchQuery {
  private static Set<String> prefixes(String query) {
    List<String> terms = Retrieval.terms(query);
    if (terms.isEmpty())
      terms = Arrays.asList(Retrieval.normalize(query).split("[^\\p{L}\\p{N}]+"));
    Set<String> seen = new LinkedHashSet<>();
    for (String term : terms)
      for (String token : Retrieval.normalize(term).split("[^\\p{L}\\p{N}]+")) {
        if (token.length() < 2 || !seen.add(token)) continue;
        if (seen.size() >= 20) return seen;
      }
    return seen;
  }

  public static String build(String query) {
    StringJoiner match = new StringJoiner(" OR ");
    for (String prefix : prefixes(query)) match.add(prefix + "*");
    return match.toString();
  }

  static Pattern highlight(String query) {
    StringJoiner alternatives = new StringJoiner("|");
    // Префиксы содержат только буквы и цифры. В тексте встречаются и е, и ё.
    for (String prefix : prefixes(query)) alternatives.add(prefix.replace("е", "[её]"));
    if (alternatives.length() == 0) return null;
    return Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alternatives + ")[\\p{L}\\p{N}]*",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }

  static String preview(String body, Pattern pattern) {
    int start = 0;
    if (pattern != null) {
      Matcher match = pattern.matcher(body);
      if (match.find()) start = match.start();
      if (start <= 18 && body.lastIndexOf('\n', start) < 0) start = 0;
    }
    int end = start + Math.min(320, body.length() - start);
    if (end < body.length() && Character.isLowSurrogate(body.charAt(end))) end--;
    return (start == 0 ? "" : "…") + body.substring(start, end) + (end == body.length() ? "" : "…");
  }
}
