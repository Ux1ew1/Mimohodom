package app.fastnotes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Список картинок заметки в одном текстовом поле: имена файлов через запятую. Одно имя — это
 * список из одной картинки, поэтому прежние заметки читаются без миграции. Первая картинка —
 * обложка в списке заметок. Без зависимости от Android.
 */
public final class ImageList {
  public static final int MAX = 8;
  private static final String SEPARATOR = ",";

  private ImageList() {}

  /** Имена по порядку без пустых и повторов. */
  public static List<String> parse(String value) {
    Set<String> names = new LinkedHashSet<>();
    if (value != null)
      for (String part : value.split(SEPARATOR)) {
        String name = part.trim();
        if (!name.isEmpty()) names.add(name);
      }
    return new ArrayList<>(names);
  }

  public static String join(List<String> names) {
    return String.join(SEPARATOR, new LinkedHashSet<>(names));
  }

  public static int count(String value) {
    return parse(value).size();
  }

  public static boolean full(String value) {
    return count(value) >= MAX;
  }

  /** Первая картинка или пустая строка. */
  public static String cover(String value) {
    List<String> names = parse(value);
    return names.isEmpty() ? "" : names.get(0);
  }

  /** Добавляет в конец; повтор и переполнение возвращают список без изменений. */
  public static String add(String value, String name) {
    List<String> names = parse(value);
    if (name == null || name.trim().isEmpty() || names.size() >= MAX || names.contains(name.trim()))
      return join(names);
    names.add(name.trim());
    return join(names);
  }

  public static String remove(String value, String name) {
    List<String> names = parse(value);
    names.remove(name);
    return join(names);
  }

  /** Заменяет имя на то же место списка; если имени нет, список не меняется. */
  public static String replace(String value, String old, String replacement) {
    List<String> names = parse(value);
    int index = names.indexOf(old);
    if (index < 0 || replacement == null || replacement.trim().isEmpty()) return join(names);
    names.remove(index);
    names.remove(replacement.trim());
    names.add(Math.min(index, names.size()), replacement.trim());
    return join(names);
  }

  /** Сдвигает картинку на {@code delta} мест, не выходя за границы. */
  public static String move(String value, String name, int delta) {
    List<String> names = parse(value);
    int index = names.indexOf(name);
    if (index < 0) return join(names);
    int target = Math.max(0, Math.min(names.size() - 1, index + delta));
    names.remove(index);
    names.add(target, name);
    return join(names);
  }

  /** Имена, которые были в {@code before}, но исчезли из {@code after}. */
  public static List<String> removed(String before, String after) {
    List<String> gone = parse(before);
    gone.removeAll(parse(after));
    return gone;
  }
}
