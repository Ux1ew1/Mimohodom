package app.fastnotes;

/**
 * Текстовые преобразования редактора для радиальной панели форматирования: обёртки
 * (**жирный**, *курсив*, ~~зачёркнутый~~) и префиксы строк (маркированный и нумерованный
 * списки, чекбоксы). Синтаксис общий с разбором ответов модели ({@link Markup}) и планом
 * заметки (- [ ]). Повторное применение снимает формат. Класс чистый, без Android.
 */
public final class TextFormat {
  public static final class Result {
    public final String text;
    public final int start, end;

    Result(String text, int start, int end) {
      this.text = text;
      this.start = start;
      this.end = end;
    }
  }

  private TextFormat() {}

  private static int clamp(CharSequence text, int at) {
    return Math.max(0, Math.min(at, text.length()));
  }

  /**
   * Оборачивает выделение маркером; если оно уже обёрнуто — снимает. Без выделения вставляет
   * пару маркеров и ставит курсор между ними.
   */
  public static Result wrap(String text, int start, int end, String mark) {
    start = clamp(text, Math.min(start, end));
    end = clamp(text, Math.max(start, Math.max(end, start)));
    end = Math.max(start, clamp(text, end));
    int m = mark.length();
    if (start == end) {
      String out = text.substring(0, start) + mark + mark + text.substring(end);
      return new Result(out, start + m, start + m);
    }
    boolean wrapped =
        start >= m
            && text.length() - end >= m
            && text.startsWith(mark, start - m)
            && text.startsWith(mark, end);
    if (wrapped) {
      String out =
          text.substring(0, start - m) + text.substring(start, end) + text.substring(end + m);
      return new Result(out, start - m, end - m);
    }
    String inner = text.substring(start, end);
    if (inner.startsWith(mark) && inner.endsWith(mark) && inner.length() > 2 * m) {
      // Маркеры внутри выделения: снимаем их.
      String out =
          text.substring(0, start) + inner.substring(m, inner.length() - m) + text.substring(end);
      return new Result(out, start, end - 2 * m);
    }
    String out = text.substring(0, start) + mark + inner + mark + text.substring(end);
    return new Result(out, start + m, end + m);
  }

  private static int lineStart(String text, int at) {
    int i = text.lastIndexOf('\n', Math.max(0, at - 1));
    return i < 0 ? 0 : i + 1;
  }

  private static int lineEnd(String text, int at) {
    int i = text.indexOf('\n', at);
    return i < 0 ? text.length() : i;
  }

  /** Префикс строк: {@code kind} — bullet («- »), number («1. »), check («- [ ] »). */
  public static Result prefix(String text, int start, int end, String kind) {
    start = clamp(text, start);
    end = clamp(text, Math.max(start, end));
    int from = lineStart(text, start);
    int to = lineEnd(text, end);
    String[] lines = text.substring(from, to).split("\n", -1);
    boolean all = true;
    for (String line : lines) {
      if (line.isEmpty()) continue;
      if (!hasPrefix(line, kind)) {
        all = false;
        break;
      }
    }
    StringBuilder out = new StringBuilder(text.length() + 32);
    out.append(text, 0, from);
    int delta = 0; // сдвиг позиций после начала выделения
    int deltaEnd = 0; // сдвиг конца выделения
    int number = 0;
    for (int li = 0; li < lines.length; li++) {
      String line = lines[li];
      String next;
      int change;
      if (all) {
        next = stripPrefix(line, kind);
        change = next.length() - line.length();
      } else if (line.isEmpty() || hasPrefix(line, kind)) {
        next = line;
        change = 0;
      } else {
        number++;
        String add = kind.equals("number") ? number + ". " : kind.equals("check") ? "- [ ] " : "- ";
        next = add + line;
        change = add.length();
      }
      out.append(next);
      if (li < lines.length - 1) out.append('\n');
      if (li == 0) delta = change;
      deltaEnd += change;
    }
    out.append(text.substring(to));
    int newStart = clamp(out, start + (all ? Math.max(delta, from - start) : delta));
    int newEnd = clamp(out, Math.max(newStart, end + deltaEnd));
    return new Result(out.toString(), newStart, newEnd);
  }

  static boolean hasPrefix(String line, String kind) {
    switch (kind) {
      case "bullet":
        return line.startsWith("- ") || line.startsWith("• ");
      case "number":
        return line.matches("^\\d+[.)] .*");
      case "check":
        return line.startsWith("- [ ] ") || line.startsWith("- [x] ") || line.startsWith("- [X] ");
      default:
        return false;
    }
  }

  static String stripPrefix(String line, String kind) {
    switch (kind) {
      case "bullet":
        return line.startsWith("- ") || line.startsWith("• ") ? line.substring(2) : line;
      case "number":
        return line.replaceFirst("^\\d+[.)] ", "");
      case "check":
        if (line.startsWith("- [ ] ") || line.startsWith("- [x] ")) return line.substring(6);
        if (line.startsWith("- [X] ")) return line.substring(6);
        return line;
      default:
        return line;
    }
  }
}
