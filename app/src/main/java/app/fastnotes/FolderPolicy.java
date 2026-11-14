package app.fastnotes;

import java.util.Locale;

/** Правила имён папок без Android: очистка, ключ уникальности и пределы. */
public final class FolderPolicy {
  /** Условный номер «Все заметки»: заметка без папки. */
  public static final long ALL = 0;

  public static final int MAX_NAME = 40;

  private FolderPolicy() {}

  /** Убирает лишние пробелы и обрезает имя; пустая строка означает недопустимое имя. */
  public static String clean(String name) {
    if (name == null) return "";
    String s = name.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
    if (s.codePointCount(0, s.length()) > MAX_NAME)
      s = s.substring(0, s.offsetByCodePoints(0, MAX_NAME)).trim();
    return s;
  }

  /** Ключ сравнения: одинаковые имена без учёта регистра (в том числе кириллицы) совпадают. */
  public static String key(String name) {
    return clean(name).toLowerCase(Locale.ROOT);
  }

  /** «Все заметки» занято: пользователь не должен создать папку с таким же именем. */
  public static boolean reserved(String name) {
    return key(name).equals("все заметки");
  }

  public static boolean valid(String name) {
    return !clean(name).isEmpty() && !reserved(name);
  }
}
