package app.fastnotes;

import java.util.*;

public final class SearchQuery {
  public static String build(String query) {
    List<String> terms = Retrieval.terms(query);
    if (terms.isEmpty())
      terms = Arrays.asList(Retrieval.normalize(query).split("[^\\p{L}\\p{N}]+"));
    StringBuilder match = new StringBuilder();
    Set<String> seen = new HashSet<>();
    for (String term : terms)
      for (String token : Retrieval.normalize(term).split("[^\\p{L}\\p{N}]+")) {
        if (token.length() < 2 || !seen.add(token)) continue;
        if (match.length() > 0) match.append(" OR ");
        match.append(token).append('*');
        if (seen.size() >= 20) return match.toString();
      }
    return match.toString();
  }
}
