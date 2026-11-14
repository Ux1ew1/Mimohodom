package app.fastnotes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Правила очистки файлов изображений без зависимости от Android. */
public final class ImageCleanup {
  /** Файлы приложения называются UUID.jpg; чужие файлы в папке не трогаем. */
  private static final Pattern MANAGED =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg$");

  private ImageCleanup() {}

  public static boolean managed(String name) {
    return name != null && MANAGED.matcher(name).matches();
  }

  /**
   * Имена файлов, которые можно удалить: наши, не упомянутые ни в заметках, ни в черновиках, и
   * старше времени ожидания. Ожидание защищает файл, который только что импортирован и ещё не
   * записан в базу или черновик.
   */
  public static List<String> orphans(
      Map<String, Long> modified, Set<String> referenced, long now, long graceMs) {
    List<String> out = new ArrayList<>();
    for (Map.Entry<String, Long> file : modified.entrySet()) {
      String name = file.getKey();
      if (!managed(name) || referenced.contains(name)) continue;
      if (now - file.getValue() < graceMs) continue;
      out.add(name);
    }
    out.sort(String::compareTo);
    return out;
  }

  /** «120 КБ», «8,4 МБ» с русской запятой. */
  public static String size(long bytes) {
    if (bytes < 1024 * 1024)
      return Math.max(0, Math.round(bytes / 1024f)) + " КБ";
    return String.format(Locale.US, "%.1f", bytes / 1024f / 1024f).replace('.', ',') + " МБ";
  }

  /** «1 файл», «2 файла», «5 файлов». */
  public static String files(int count) {
    int hundreds = count % 100, units = count % 10;
    String word =
        hundreds >= 11 && hundreds <= 14
            ? "файлов"
            : units == 1 ? "файл" : units >= 2 && units <= 4 ? "файла" : "файлов";
    return count + " " + word;
  }
}
