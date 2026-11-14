package app.fastnotes;

import java.net.URI;
import java.util.*;
import java.util.regex.*;

/** Распознавание ссылок на видео YouTube без зависимости от Android. */
public final class YoutubeLink {
  private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{11}");
  private static final Set<String> HOSTS =
      new HashSet<>(
          Arrays.asList(
              "youtube.com",
              "www.youtube.com",
              "m.youtube.com",
              "youtu.be",
              "www.youtu.be",
              "youtube-nocookie.com",
              "www.youtube-nocookie.com"));

  private YoutubeLink() {}

  /** Единый вид ссылки: так её сохраняют в заметке и по ней же ищут повторы. */
  public static String canonical(String id) {
    return "https://www.youtube.com/watch?v=" + id;
  }

  /** Номер видео (11 символов) из одной ссылки или пустая строка. */
  public static String id(String link) {
    try {
      URI uri = new URI(trimEnd(link));
      String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
      if (!HOSTS.contains(host)) return "";
      String path = uri.getPath() == null ? "" : uri.getPath();
      String found = "";
      if (host.endsWith("youtu.be")) {
        found = segment(path, 0);
      } else if (path.equals("/watch") || path.equals("/watch/")) {
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        for (String pair : query.split("&"))
          if (pair.startsWith("v=")) {
            found = pair.substring(2);
            break;
          }
      } else if (path.startsWith("/shorts/")
          || path.startsWith("/live/")
          || path.startsWith("/embed/")
          || path.startsWith("/v/")) {
        found = segment(path, 1);
      }
      return ID.matcher(found).matches() ? found : "";
    } catch (Exception e) {
      return "";
    }
  }

  /** Все разные видео из текста в порядке появления, ссылки приведены к единому виду. */
  public static List<String> all(String text) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    if (text == null) return new ArrayList<>();
    Matcher m = URL.matcher(text);
    while (m.find()) {
      String id = id(m.group());
      if (!id.isEmpty()) out.add(canonical(id));
    }
    return new ArrayList<>(out);
  }

  /** В тексте только ссылки и разделители, без своих слов: так выглядит вставленная ссылка. */
  public static boolean onlyLinks(String text) {
    if (all(text).isEmpty()) return false;
    String rest = URL.matcher(text).replaceAll(" ");
    for (int i = 0; i < rest.length(); i++) if (Character.isLetterOrDigit(rest.charAt(i))) return false;
    return true;
  }

  private static String segment(String path, int index) {
    String[] parts = path.split("/");
    int seen = -1;
    for (String part : parts) {
      if (part.isEmpty()) continue;
      if (++seen == index) return part;
    }
    return "";
  }

  private static String trimEnd(String link) {
    String s = link == null ? "" : link.trim();
    while (!s.isEmpty() && ".,;:!?)]}".indexOf(s.charAt(s.length() - 1)) >= 0)
      s = s.substring(0, s.length() - 1);
    return s;
  }
}
