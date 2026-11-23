package app.fastnotes;

import java.util.*;

/**
 * Правила колоды без Android (этап 3): какие исходные заметки можно предложить в корзину галочкой
 * «Пустые и дубли — в корзину» при сборке колоды (решение 2). Удалять можно только пустые, точные
 * дубли и те, что целиком вошли в сводку; заметки с картинками и напоминаниями не трогаем, и хотя
 * бы одна исходная всегда остаётся в колоде.
 */
public final class DeckPolicy {
  public static final String EMPTY = "пустая", DUPLICATE = "дубль", MERGED = "целиком в сводке";

  private DeckPolicy() {}

  /**
   * Кандидаты в корзину: номер заметки → причина, в порядке исходных. {@code model} — номера,
   * которые модель назвала пустыми, дублями или вошедшими в сводку (чужие номера не учитываются).
   */
  public static LinkedHashMap<Long, String> trashCandidates(
      List<Note> sources, Collection<Long> model) {
    LinkedHashMap<Long, String> out = new LinkedHashMap<>();
    Map<String, Long> seen = new HashMap<>();
    for (Note n : sources) {
      if (!n.image.isEmpty() || n.reminder > 0) {
        seen.putIfAbsent(key(n), n.id);
        continue;
      }
      if (empty(n)) out.put(n.id, EMPTY);
      else if (seen.containsKey(key(n))) out.put(n.id, DUPLICATE);
      else {
        seen.put(key(n), n.id);
        if (model != null && model.contains(n.id)) out.put(n.id, MERGED);
      }
    }
    // В колоде должна остаться хотя бы одна исходная заметка: первая не уходит.
    if (!sources.isEmpty() && out.size() == sources.size()) out.remove(sources.get(0).id);
    return out;
  }

  /** Пустая: нет текста, картинок и своего названия. */
  public static boolean empty(Note n) {
    return n.body.trim().isEmpty()
        && n.image.isEmpty()
        && (n.autoTitle || n.title.trim().isEmpty());
  }

  /** Сравнение для точных дублей: регистр, «ё» и пробелы не важны. */
  static String key(Note n) {
    return normalize(n.title) + "\n" + normalize(n.body) + "\n" + n.image;
  }

  static String normalize(String text) {
    return text.toLowerCase(Locale.ROOT).replace('ё', 'е').trim().replaceAll("\\s+", " ");
  }

  /** «В корзину: «Список» (дубль), «Новая заметка» (пустая)». */
  public static String describe(Map<Long, String> candidates, Map<Long, String> titles) {
    StringBuilder out = new StringBuilder();
    for (Map.Entry<Long, String> e : candidates.entrySet()) {
      if (out.length() > 0) out.append(", ");
      out.append('«')
          .append(titles.getOrDefault(e.getKey(), "Заметка"))
          .append("» (")
          .append(e.getValue())
          .append(')');
    }
    return out.toString();
  }
}
