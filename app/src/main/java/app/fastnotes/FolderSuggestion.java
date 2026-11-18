package app.fastnotes;

import java.util.*;
import org.json.*;

/**
 * Предложения папок по темам, которые ИИ уже записал в подсказки заметок. Без Android и без новых
 * запросов к модели: только сравнение тем. Ничего не создаётся без подтверждения пользователя.
 */
public final class FolderSuggestion {
  public static final int MIN_NOTES = 2, MAX_SUGGESTIONS = 5;
  private static final Set<String> TOO_GENERAL =
      new HashSet<>(
          Arrays.asList(
              "видео", "youtube", "ютуб", "заметка", "заметки", "разное", "другое", "общее"));

  public static final class Suggestion {
    public final String name;
    /** Номер уже существующей папки с таким названием или 0, если папку надо создать. */
    public final long existing;

    public final List<Long> notes;

    Suggestion(String name, long existing, List<Long> notes) {
      this.name = name;
      this.existing = existing;
      this.notes = notes;
    }
  }

  private FolderSuggestion() {}

  /** Название папки из темы: лишнее убрано, первая буква заглавная. */
  public static String display(String topic) {
    String name = FolderPolicy.clean(topic);
    if (name.isEmpty()) return "";
    String word = name.split(" ", 2)[0];
    // Названия вроде iPhone или Moonlight не трогаем: заглавная только у слов целиком в нижнем регистре.
    if (!word.equals(word.toLowerCase(Locale.ROOT))) return name;
    int first = name.codePointAt(0);
    return new StringBuilder()
        .appendCodePoint(Character.toUpperCase(first))
        .append(name.substring(Character.charCount(first)))
        .toString();
  }

  /** Тема подходит для названия папки: не слишком общая, не занятое имя, 3–40 символов. */
  public static boolean usable(String topic) {
    String name = FolderPolicy.clean(topic);
    String key = FolderPolicy.key(name);
    return FolderPolicy.valid(name)
        && name.codePointCount(0, name.length()) >= 3
        && name.equals(topic.trim().replaceAll("\\s+", " "))
        && !TOO_GENERAL.contains(key);
  }

  static List<String> topics(Note n) {
    List<String> out = new ArrayList<>();
    JSONObject ai = Metadata.read(n.metadata).optJSONObject("ai");
    JSONArray list = ai == null ? null : ai.optJSONArray("topics");
    if (list != null)
      for (int i = 0; i < list.length(); i++) {
        String topic = list.optString(i, "").trim();
        if (usable(topic)) out.add(topic);
      }
    return out;
  }

  /**
   * Папки для заметок без папки: тема, общая у {@value #MIN_NOTES} и более заметок, или тема, которая
   * совпала с названием существующей папки (хватает одной заметки). Каждая заметка попадает не более
   * чем в одно предложение. {@code folders} — ключи существующих папок (см. {@link
   * FolderPolicy#key}) и их номера, {@code dismissed} — ключи тем, которые пользователь скрыл.
   */
  public static List<Suggestion> suggest(
      List<Note> notes, Map<String, Long> folders, Set<String> dismissed) {
    Map<String, String> names = new LinkedHashMap<>();
    Map<String, LinkedHashSet<Long>> members = new LinkedHashMap<>();
    for (Note n : notes) {
      if (n.folder != FolderPolicy.ALL) continue;
      for (String topic : topics(n)) {
        String key = FolderPolicy.key(topic);
        if (dismissed.contains(key)) continue;
        names.putIfAbsent(key, display(topic));
        members.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(n.id);
      }
    }
    List<String> keys = new ArrayList<>(members.keySet());
    keys.sort(
        (a, b) -> {
          int bySize = Integer.compare(members.get(b).size(), members.get(a).size());
          return bySize != 0 ? bySize : names.get(a).compareTo(names.get(b));
        });
    Set<Long> used = new HashSet<>();
    List<Suggestion> out = new ArrayList<>();
    for (String key : keys) {
      List<Long> free = new ArrayList<>();
      for (long id : members.get(key)) if (!used.contains(id)) free.add(id);
      long existing = folders.getOrDefault(key, 0L);
      if (free.size() < (existing > 0 ? 1 : MIN_NOTES)) continue;
      used.addAll(free);
      out.add(new Suggestion(names.get(key), existing, free));
      if (out.size() == MAX_SUGGESTIONS) break;
    }
    return out;
  }

  /** Папка для одной свежей заметки (например, сводки видео) по её первой подходящей теме. */
  public static Suggestion forNote(Note n, Map<String, Long> folders, Set<String> dismissed) {
    if (n.folder != FolderPolicy.ALL) return null;
    for (String topic : topics(n)) {
      String key = FolderPolicy.key(topic);
      if (dismissed.contains(key)) continue;
      return new Suggestion(
          display(topic), folders.getOrDefault(key, 0L), new ArrayList<>(Arrays.asList(n.id)));
    }
    return null;
  }
}
