package app.fastnotes;

import java.util.*;
import org.json.*;

/** Authoritative note fields and unverified AI search hints are deliberately separate. */
public final class Metadata {
  private static final String[] ARRAYS = {"aliases", "topics", "people", "places", "dates"};

  public static JSONObject base(String title, String body, long created, long updated) {
    try {
      JSONArray keywords = new JSONArray();
      Map<String, Integer> counts = new LinkedHashMap<>();
      for (String word :
          (title + " " + Retrieval.excerpt(body, "", 5000))
              .toLowerCase(Locale.ROOT)
              .replace('ё', 'е')
              .split("[^\\p{L}\\p{N}]+"))
        if (word.length() >= 4 && word.length() <= 48)
          counts.put(word, counts.getOrDefault(word, 0) + 1);
      List<String> words = new ArrayList<>(counts.keySet());
      words.sort((a, b) -> Integer.compare(counts.get(b), counts.get(a)));
      for (int i = 0; i < Math.min(16, words.size()); i++) keywords.put(words.get(i));
      return new JSONObject()
          .put("version", 1)
          .put("title", title)
          .put("description", Retrieval.excerpt(body, "", 280))
          .put("created_at", created)
          .put("updated_at", updated)
          .put("keywords", keywords);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  public static JSONObject ai(JSONObject source) {
    JSONObject clean = new JSONObject();
    if (source == null) return clean;
    try {
      for (String key : new String[] {"search_title", "summary"}) {
        Object value = source.opt(key);
        if (value instanceof String) {
          String text = ((String) value).trim();
          if (!text.isEmpty() && text.length() <= (key.equals("summary") ? 400 : 120))
            clean.put(key, text);
        }
      }
      for (String key : ARRAYS) {
        JSONArray input = source.optJSONArray(key), out = new JSONArray();
        Set<String> seen = new HashSet<>();
        if (input != null)
          for (int i = 0; i < input.length() && out.length() < 8; i++) {
            Object value = input.opt(i);
            if (value instanceof String) {
              String text = ((String) value).trim();
              if (!text.isEmpty() && text.length() <= 80 && seen.add(text.toLowerCase(Locale.ROOT)))
                out.put(text);
            }
          }
        if (out.length() > 0) clean.put(key, out);
      }
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    return clean;
  }

  public static String searchText(JSONObject data) {
    StringBuilder out = new StringBuilder();
    append(out, data);
    Object extra = data.opt("ai");
    if (extra instanceof JSONObject) append(out, (JSONObject) extra);
    return out.toString().trim().replace('ё', 'е').replace('Ё', 'Е');
  }

  private static void append(StringBuilder out, JSONObject data) {
    for (String key :
        new String[] {
          "title",
          "description",
          "keywords",
          "search_title",
          "summary",
          "aliases",
          "topics",
          "people",
          "places",
          "dates"
        }) {
      Object value = data.opt(key);
      if (value instanceof String) out.append(value).append(' ');
      if (value instanceof JSONArray) {
        JSONArray array = (JSONArray) value;
        for (int i = 0; i < array.length(); i++)
          if (array.opt(i) instanceof String) out.append(array.optString(i)).append(' ');
      }
    }
  }

  public static JSONObject read(String json) {
    try {
      return new JSONObject(json);
    } catch (JSONException e) {
      return new JSONObject();
    }
  }

  public static JSONObject hints(String json, int budget) {
    JSONObject input = read(json).optJSONObject("ai"), out = new JSONObject();
    if (input == null) return out;
    try {
      for (String key : new String[] {"summary", "search_title"}) {
        String value = input.optString(key, "");
        if (value.isEmpty()) continue;
        out.put(key, Retrieval.excerpt(value, "", Math.max(20, budget / 3)));
        if (out.toString().length() > budget) out.remove(key);
      }
      for (String key : ARRAYS) {
        JSONArray array = input.optJSONArray(key);
        if (array == null) continue;
        JSONArray selected = new JSONArray();
        out.put(key, selected);
        for (int i = 0; i < array.length() && i < 2; i++) {
          selected.put(Retrieval.excerpt(array.optString(i), "", 60));
          if (out.toString().length() > budget) {
            selected.remove(selected.length() - 1);
            break;
          }
        }
        if (selected.length() == 0) out.remove(key);
      }
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  public static JSONObject withAI(JSONObject base, JSONObject incoming, long revision) {
    try {
      JSONObject merged =
          read(base.optJSONObject("ai") == null ? "{}" : base.optJSONObject("ai").toString());
      JSONObject clean = ai(incoming);
      Iterator<String> keys = clean.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        Object value = clean.get(key);
        if (value instanceof JSONArray && merged.optJSONArray(key) != null) {
          JSONArray combined = new JSONArray();
          Set<String> seen = new HashSet<>();
          for (JSONArray list : new JSONArray[] {(JSONArray) value, merged.getJSONArray(key)})
            for (int i = 0; i < list.length() && combined.length() < 8; i++) {
              String text = list.optString(i);
              if (seen.add(text.toLowerCase(Locale.ROOT))) combined.put(text);
            }
          merged.put(key, combined);
        } else merged.put(key, value);
      }
      base.put("ai", ai(merged)).put("ai_revision", revision);
      return base;
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }
}
