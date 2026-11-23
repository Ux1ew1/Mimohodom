package app.fastnotes;

import java.util.ArrayList;
import java.util.List;

/**
 * Разбор лёгкой разметки ответов модели: **жирный**, *курсив*, ~~зачёркнутый~~, `код`,
 * ограждённые блоки кода, заголовки #, списки, цитаты и ссылки [текст](url).
 * Служебные токены [[note:N]] пропускаются как обычный текст — их подменяет вызывающий код.
 * Незакрытые и пустые конструкции остаются обычным текстом, разбор никогда не падает.
 * Класс чистый (без Android): возвращает текст без маркеров и список диапазонов стилей,
 * которые активность превращает в Spannable.
 */
public final class Markup {
  public static final int BOLD = 1;
  public static final int ITALIC = 2;
  public static final int STRIKE = 4;
  public static final int CODE = 8;
  public static final int HEADING = 16;
  public static final int QUOTE = 32;
  public static final int LINK = 64;

  public static final class Span {
    public final int start, end, flags;
    public final String url;

    Span(int start, int end, int flags, String url) {
      this.start = start;
      this.end = end;
      this.flags = flags;
      this.url = url;
    }
  }

  public static final class Result {
    public final String text;
    public final List<Span> spans;

    Result(String text, List<Span> spans) {
      this.text = text;
      this.spans = spans;
    }
  }

  private Markup() {}

  public static Result parse(String raw) {
    if (raw == null || raw.isEmpty()) return new Result(raw == null ? "" : raw, new ArrayList<>());
    StringBuilder out = new StringBuilder(raw.length());
    List<Span> spans = new ArrayList<>();
    String[] lines = raw.split("\n", -1);
    boolean fenced = false;
    for (int li = 0; li < lines.length; li++) {
      String line = lines[li];
      String trimmed = line.trim();
      if (fenced) {
        if (trimmed.startsWith("```")) {
          fenced = false;
          continue; // закрывающее ограждение не выводим
        }
        int start = out.length();
        out.append(line);
        spans.add(new Span(start, out.length(), CODE, null));
        if (li < lines.length - 1) out.append('\n');
        continue;
      }
      if (trimmed.startsWith("```")) {
        fenced = true; // незакрытое ограждение тянется до конца текста
        continue;
      }
      String body = line;
      int flags = 0;
      if (body.startsWith("> ") || body.equals(">")) {
        flags |= QUOTE;
        body = body.length() > 1 ? body.substring(2) : "";
      }
      int hashes = 0;
      while (hashes < body.length() && body.charAt(hashes) == '#') hashes++;
      if (hashes > 0 && hashes <= 6 && body.length() > hashes && body.charAt(hashes) == ' ') {
        flags |= BOLD | HEADING;
        body = body.substring(hashes + 1);
      } else if (body.length() >= 2
          && (body.charAt(0) == '-' || body.charAt(0) == '*' || body.charAt(0) == '+')
          && body.charAt(1) == ' ') {
        body = "• " + body.substring(2);
      }
      int lineStart = out.length();
      inline(body, 0, body.length(), out, spans, 0);
      if (flags != 0) spans.add(new Span(lineStart, out.length(), flags, null));
      if (li < lines.length - 1) out.append('\n');
    }
    return new Result(out.toString(), spans);
  }

  /** Строка без блочных конструкций: код, жирный, курсив, зачёркнутый, ссылки, экранирование. */
  private static void inline(
      String s, int from, int to, StringBuilder out, List<Span> spans, int depth) {
    int i = from;
    while (i < to) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < to && "\\*`_~[]#".indexOf(s.charAt(i + 1)) >= 0) {
        out.append(s.charAt(i + 1));
        i += 2;
        continue;
      }
      if (c == '`') {
        int close = s.indexOf('`', i + 1);
        if (close > i + 1 && close < to) {
          span(s, i + 1, close, CODE, null, out, spans, depth);
          i = close + 1;
          continue;
        }
      }
      if (c == '*' || c == '_') {
        int len = runLength(s, i, to, c);
        int use = Math.min(len, 3);
        // _ внутри слова (some_var) разметкой не считается
        boolean intra = c == '_' && i > from && isWord(s.charAt(i - 1));
        if (!intra) {
          boolean matched = false;
          for (; use >= 1; use--) {
            // Одиночная звезда из длинной серии (****) маркером не считается.
            if (use == 1 && len > 1) break;
            String mark = repeat(c, use);
            int close = findClosing(s, i + use, to, mark, c == '_');
            if (close < 0) continue;
            int flags = use == 3 ? BOLD | ITALIC : use == 2 ? BOLD : ITALIC;
            span(s, i + use, close, flags, null, out, spans, depth);
            i = close + use;
            matched = true;
            break;
          }
          if (matched) continue;
        }
        out.append(repeat(c, Math.min(len, 3)));
        i += Math.min(len, 3);
        continue;
      }
      if (c == '~' && i + 1 < to && s.charAt(i + 1) == '~') {
        int close = s.indexOf("~~", i + 2);
        if (close > i + 2 && close + 2 <= to) {
          span(s, i + 2, close, STRIKE, null, out, spans, depth);
          i = close + 2;
          continue;
        }
      }
      if (c == '[') {
        int mid = s.indexOf("](", i + 1);
        if (mid > i + 1 && mid + 2 < to) {
          int close = s.indexOf(')', mid + 2);
          if (close > mid + 2 && close < to) {
            String url = s.substring(mid + 2, close);
            span(s, i + 1, mid, LINK, url, out, spans, depth);
            i = close + 1;
            continue;
          }
        }
      }
      out.append(c);
      i++;
    }
  }

  /** Диапазон размеченного содержимого: рекурсивно разбирает внутренность и добавляет стиль. */
  private static void span(
      String s, int from, int to, int flags, String url,
      StringBuilder out, List<Span> spans, int depth) {
    int start = out.length();
    if (flags == CODE || depth >= 4) {
      out.append(s, from, to); // внутри кода разметки нет, глубже 4 уровней не спускаемся
    } else {
      inline(s, from, to, out, spans, depth + 1);
    }
    if (out.length() > start) spans.add(new Span(start, out.length(), flags, url));
  }

  private static int runLength(String s, int i, int to, char c) {
    int n = 0;
    while (i + n < to && s.charAt(i + n) == c) n++;
    return n;
  }

  private static String repeat(char c, int n) {
    StringBuilder b = new StringBuilder(n);
    for (int i = 0; i < n; i++) b.append(c);
    return b.toString();
  }

  private static boolean isWord(char c) {
    return Character.isLetterOrDigit(c);
  }

  /**
   * Ищет закрывающий маркер: непустое содержимое, для подчёркивания закрытие не внутри слова.
   */
  private static int findClosing(String s, int from, int to, String mark, boolean underscore) {
    int i = from;
    while (true) {
      int at = s.indexOf(mark, i);
      if (at < 0 || at + mark.length() > to) return -1;
      if (at == from) { // пустое содержимое — не разметка
        i = at + mark.length();
        continue;
      }
      if (underscore && at + mark.length() < to && isWord(s.charAt(at + mark.length()))) {
        i = at + 1;
        continue;
      }
      return at;
    }
  }
}
