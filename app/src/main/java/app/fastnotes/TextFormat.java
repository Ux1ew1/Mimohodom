package app.fastnotes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Formatting in the note body, using offsets in the stored text. */
final class TextFormat {
  static final int ITALIC = 1, BOLD = 2, STRIKE = 4;
  static final int HIDDEN = 8, BULLET = 16, CHECK = 32, CHECKED = 64;

  record Result(String text, int start, int end) {}

  record Decoration(int start, int end, int kind) {}

  private static final Pattern NUMBER = Pattern.compile("[0-9]+[.)] ");

  private TextFormat() {}

  static List<Decoration> decorations(String text) {
    List<Decoration> result = new ArrayList<>();
    for (int start = 0; start < text.length();) {
      int end = lineEnd(text, start);
      String line = text.substring(start, end);
      Prefix prefix = prefixOf(line);
      int icon = switch (prefix.kind) {
        case "bullet" -> BULLET;
        case "check" -> line.charAt(prefix.start + 3) == ' ' ? CHECK : CHECKED;
        default -> 0;
      };
      if (icon != 0)
        result.add(new Decoration(start + prefix.start, start + prefix.markerEnd, icon));
      int bodyStart = prefix.end;
      String body = line.substring(bodyStart);
      if (body.indexOf('*') >= 0 || body.indexOf('~') >= 0 || body.indexOf('\\') >= 0) {
        Inline inline = inline(body);
        for (Decoration span : inline.decorations)
          result.add(new Decoration(
              start + bodyStart + span.start, start + bodyStart + span.end, span.kind));
      }
      start = end + 1;
    }
    return result;
  }

  /** A cursor targets the text on its logical line. A selection targets only its text. */
  static Result wrap(String text, int start, int end, int style) {
    Selection selection = new Selection(text, start, end);
    List<InlineEdit> edits = new ArrayList<>();
    boolean remove = true;
    for (int at = lineStart(text, selection.from); at <= selection.last(); ) {
      int endOfLine = lineEnd(text, at);
      String line = text.substring(at, endOfLine);
      int bodyStart = at + prefixOf(line).end;
      Inline parsed = inline(text.substring(bodyStart, endOfLine));
      int left = selection.cursor() ? 0 : parsed.position(selection.from - bodyStart);
      int right = selection.cursor() ? parsed.text.length() : parsed.position(selection.to - bodyStart);
      while (left < right && space(parsed.text.charAt(left))) left++;
      while (right > left && space(parsed.text.charAt(right - 1))) right--;
      if (left < right) {
        edits.add(new InlineEdit(bodyStart, endOfLine, parsed, left, right));
        for (int i = left; i < right; i++) {
          if (!space(parsed.text.charAt(i)) && (parsed.styles[i] & style) == 0) remove = false;
        }
      }
      at = endOfLine + 1;
    }
    List<Edit> replacements = new ArrayList<>();
    for (InlineEdit edit : edits) {
      Inline parsed = edit.inline;
      for (int i = edit.left; i < edit.right; i++) {
        if (remove) parsed.styles[i] &= ~style;
        else parsed.styles[i] |= style;
      }
      Encoded encoded = encode(parsed.text, parsed.styles);
      replacements.add(new Edit(edit.start, edit.end, encoded.text, (at, rightEdge) -> {
        int pos = parsed.position(at);
        boolean atTextEnd = selection.cursor() && pos == parsed.text.length();
        return rightEdge || atTextEnd ? encoded.before[pos] : encoded.after[pos];
      }));
    }
    return replace(text, selection, replacements);
  }

  /** Switches the list marker for whole lines, preserving the body and its inline formatting. */
  static Result prefix(String text, int start, int end, String kind) {
    Selection selection = new Selection(text, start, end);
    List<LinePrefix> lines = new ArrayList<>();
    boolean remove = true;
    for (int at = lineStart(text, selection.from); at <= selection.last(); ) {
      int endOfLine = lineEnd(text, at);
      String line = text.substring(at, endOfLine);
      Prefix prefix = prefixOf(line);
      if (!line.trim().isEmpty()) {
        lines.add(new LinePrefix(at, prefix));
        remove &= kind.equals(prefix.kind);
      }
      at = endOfLine + 1;
    }
    List<Edit> edits = new ArrayList<>();
    int number = 0;
    for (LinePrefix line : lines) {
      Prefix prefix = line.prefix;
      number++;
      if (!remove && kind.equals(prefix.kind) && !kind.equals("number")) continue;
      String marker = remove ? "" : switch (kind) {
        case "bullet" -> "- ";
        case "check" -> "- [ ] ";
        case "number" -> number + ". ";
        default -> throw new IllegalArgumentException(kind);
      };
      edits.add(new Edit(line.start + prefix.start, line.start + prefix.end,
          marker, (at, rightEdge) -> at == 0 && prefix.end > prefix.start ? 0 : marker.length()));
    }
    return replace(text, selection, edits);
  }

  static char toggledCheck(CharSequence text, int markerStart) {
    if (markerStart < 0 || markerStart > text.length() - 5
        || text.charAt(markerStart) != '-' || text.charAt(markerStart + 1) != ' '
        || text.charAt(markerStart + 2) != '[' || text.charAt(markerStart + 4) != ']') return 0;
    return switch (text.charAt(markerStart + 3)) {
      case ' ' -> 'x';
      case 'x', 'X' -> ' ';
      default -> 0;
    };
  }

  private record Prefix(String kind, int start, int markerEnd, int end) {}

  private record LinePrefix(int start, Prefix prefix) {}

  private static Prefix prefixOf(String line) {
    int at = 0;
    while (at < line.length() && (line.charAt(at) == ' ' || line.charAt(at) == '\t')) at++;
    if (toggledCheck(line, at) != 0
        && (at + 5 == line.length() || line.charAt(at + 5) == ' '))
      return new Prefix("check", at, at + 5, Math.min(at + 6, line.length()));
    if (line.startsWith("- ", at) || line.startsWith("• ", at))
      return new Prefix("bullet", at, at + 1, at + 2);
    Matcher number = NUMBER.matcher(line).region(at, line.length());
    if (number.lookingAt()) return new Prefix("number", at, number.end() - 1, number.end());
    return new Prefix("", at, at, at);
  }

  private record Inline(String text, int[] styles, int[] positions, List<Decoration> decorations) {
    int position(int raw) {
      return positions[Math.max(0, Math.min(raw, positions.length - 1))];
    }
  }

  private record InlineEdit(int start, int end, Inline inline, int left, int right) {}

  private static final class Marker {
    final int start, length;
    int matched;

    Marker(int start, int length) {
      this.start = start;
      this.length = length;
    }
  }

  private static Inline inline(String raw) {
    int length = raw.length();
    int[] changes = new int[length + 1];
    boolean[] hidden = new boolean[length];
    List<Decoration> decorations = new ArrayList<>();
    List<Marker> markers = new ArrayList<>();
    Marker[] open = new Marker[3];
    for (int at = 0; at < length;) {
      char ch = raw.charAt(at);
      if (ch == '\\' && at + 1 < length && escapable(raw.charAt(at + 1))) {
        hidden[at] = true;
        at += 2;
        continue;
      }
      if (ch != '*' && ch != '~') {
        at++;
        continue;
      }
      int end = at + 1;
      while (end < length && raw.charAt(end) == ch) end++;
      int run = end - at;
      int flags = ch == '*' && run <= 3 ? run : ch == '~' && run == 2 ? STRIKE : 0;
      if (flags != 0) {
        Marker marker = new Marker(at, run);
        markers.add(marker);
        for (int i = 0; i < open.length; i++) {
          int flag = 1 << i;
          if ((flags & flag) == 0) continue;
          Marker first = open[i];
          if (first == null) {
            if (end < length && !space(raw.charAt(end))) open[i] = marker;
          } else if (at > 0 && !space(raw.charAt(at - 1))) {
            int content = first.start + first.length;
            if (content < at) {
              changes[content] ^= flag;
              changes[at] ^= flag;
              decorations.add(new Decoration(content, at, flag));
              first.matched |= flag;
              marker.matched |= flag;
            }
            open[i] = null;
          }
        }
      }
      at = end;
    }
    for (Marker marker : markers) {
      int count = marker.matched == STRIKE ? 2 : marker.matched;
      Arrays.fill(hidden, marker.start, marker.start + count, true);
    }
    int[] positions = new int[length + 1];
    int[] styles = new int[length];
    StringBuilder plain = new StringBuilder(length);
    int active = 0;
    for (int at = 0; at < length; at++) {
      active ^= changes[at];
      positions[at] = plain.length();
      if (hidden[at]) {
        if (at == 0 || !hidden[at - 1]) {
          int end = at + 1;
          while (end < length && hidden[end]) end++;
          decorations.add(new Decoration(at, end, HIDDEN));
        }
      } else {
        styles[plain.length()] = active;
        plain.append(raw.charAt(at));
      }
    }
    positions[length] = plain.length();
    return new Inline(plain.toString(), styles, positions, decorations);
  }

  private record Encoded(String text, int[] before, int[] after) {}

  private static Encoded encode(String plain, int[] styles) {
    // Keep markers attached to words when a style stops or starts beside a space.
    for (int at = 0; at < plain.length();) {
      if (!space(plain.charAt(at))) {
        at++;
        continue;
      }
      int end = at + 1;
      while (end < plain.length() && space(plain.charAt(end))) end++;
      int shared = at == 0 || end == plain.length() ? 0 : styles[at - 1] & styles[end];
      Arrays.fill(styles, at, end, shared);
      at = end;
    }
    StringBuilder raw = new StringBuilder(plain.length());
    int[] before = new int[plain.length() + 1], after = new int[before.length];
    int previous = 0;
    for (int pos = 0; pos <= plain.length(); pos++) {
      before[pos] = raw.length();
      int style = pos == plain.length() ? 0 : styles[pos];
      int change = style ^ previous;
      // One, two and three stars toggle italic, bold and both, respectively.
      if ((change & STRIKE & previous) != 0) raw.append("~~");
      switch (change & (BOLD | ITALIC)) {
        case ITALIC -> raw.append('*');
        case BOLD -> raw.append("**");
        case BOLD | ITALIC -> raw.append("***");
      }
      if ((change & STRIKE & style) != 0) raw.append("~~");
      after[pos] = raw.length();
      if (pos < plain.length()) {
        char ch = plain.charAt(pos);
        if (ch == '*' || ch == '~' || ch == '\\'
            && (pos + 1 < plain.length() && escapable(plain.charAt(pos + 1))
                || style != (pos + 1 == plain.length() ? 0 : styles[pos + 1]))) raw.append('\\');
        raw.append(ch);
      }
      previous = style;
    }
    return new Encoded(raw.toString(), before, after);
  }

  private static boolean escapable(char ch) {
    return ch == '*' || ch == '~' || ch == '\\';
  }

  private static boolean space(char ch) {
    return Character.isWhitespace(ch) || Character.isSpaceChar(ch);
  }

  private static int lineStart(String text, int at) {
    return text.lastIndexOf('\n', at - 1) + 1;
  }

  private static int lineEnd(String text, int at) {
    int end = text.indexOf('\n', at);
    return end < 0 ? text.length() : end;
  }

  private record Selection(int from, int to, boolean reversed) {
    Selection(String text, int start, int end) {
      this(clamp(text, Math.min(start, end)), clamp(text, Math.max(start, end)), start > end);
    }

    private static int clamp(String text, int at) {
      return Math.max(0, Math.min(at, text.length()));
    }

    boolean cursor() {
      return from == to;
    }

    int last() {
      return cursor() ? to : to - 1;
    }
  }

  private interface Position {
    int map(int offset, boolean rightEdge);
  }

  private record Edit(int start, int end, String text, Position position) {}

  private static Result replace(String text, Selection selection, List<Edit> edits) {
    if (edits.isEmpty())
      return new Result(text, selection.reversed ? selection.to : selection.from,
          selection.reversed ? selection.from : selection.to);
    StringBuilder result = new StringBuilder(text.length());
    int previous = 0;
    for (Edit edit : edits) {
      result.append(text, previous, edit.start).append(edit.text);
      previous = edit.end;
    }
    result.append(text, previous, text.length());
    int start = translate(edits, selection.from, false);
    int end = selection.cursor() ? start : Math.max(start, translate(edits, selection.to, true));
    return new Result(result.toString(), selection.reversed ? end : start,
        selection.reversed ? start : end);
  }

  private static int translate(List<Edit> edits, int offset, boolean rightEdge) {
    int shift = 0;
    for (Edit edit : edits) {
      if (offset < edit.start) break;
      if (offset <= edit.end)
        return edit.start + shift + edit.position.map(offset - edit.start, rightEdge);
      shift += edit.text.length() - (edit.end - edit.start);
    }
    return offset + shift;
  }
}
