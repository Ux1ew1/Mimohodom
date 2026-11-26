package app.fastnotes;

import static app.fastnotes.TextFormat.*;

/** Текстовые преобразования редактора: обёртки и префиксы строк с переключением. */
public final class TextFormatTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static void eq(TextFormat.Result r, String text, int start, int end, String name) {
    check(r.text().equals(text), name + " text: <" + r.text() + ">");
    check(r.start() == start && r.end() == end, name + " cursor " + r.start() + ".." + r.end());
  }

  static void span(String raw, int start, int end, int kind) {
    check(decorations(raw).contains(new Decoration(start, end, kind)),
        "missing span " + kind + ": " + start + ".." + end + " in <" + raw + ">");
  }

  public static void main(String[] args) {
    eq(wrap("купить молоко", 7, 13, BOLD), "купить **молоко**", 9, 15, "bold wrap");
    eq(wrap("купить **молоко**", 9, 15, BOLD), "купить молоко", 7, 13, "bold unwrap");
    eq(wrap("тихо *сказал* он", 6, 12, ITALIC), "тихо сказал он", 5, 11, "italic unwrap");
    eq(wrap("~~старое~~ новое", 2, 8, STRIKE), "старое новое", 0, 6, "strike unwrap");
    eq(wrap("а **молоко** б", 2, 12, BOLD), "а молоко б", 2, 8, "inner unwrap");
    eq(wrap("текст\nещё", 5, 5, BOLD), "**текст**\nещё", 7, 7, "cursor inside style at line end");
    eq(wrap("текст\nещё", 6, 6, BOLD), "текст\n**ещё**", 8, 8, "cursor after newline");
    eq(wrap("- [ ] текст", 11, 11, BOLD), "- [ ] **текст**", 13, 13, "style checklist body");
    eq(wrap("abc\ndef\nghi", 6, 1, ITALIC), "a*bc*\n*de*f\nghi", 9, 2, "backward multiline");
    eq(wrap("abc\ndef", 0, 4, BOLD), "**abc**\ndef", 2, 8, "next line excluded");
    eq(wrap("***текст***", 11, 11, BOLD), "*текст*", 6, 6, "keep italic");
    eq(wrap("*текст*", 1, 6, BOLD), "***текст***", 3, 8, "combine bold and italic");
    eq(wrap("abc", 0, 0, STRIKE), "~~abc~~", 2, 2, "cursor at start");
    eq(wrap("**abc**", 3, 4, BOLD), "**a**b**c**", 5, 6, "remove style from part");
    eq(wrap("**abc def**", 6, 9, BOLD), "**abc** def", 8, 11, "remove style after a space");
    eq(wrap("", 0, 0, BOLD), "", 0, 0, "empty text");
    eq(wrap("a\n  \nb", 3, 3, BOLD), "a\n  \nb", 3, 3, "blank line");
    eq(wrap("2 * 3", 5, 5, BOLD), "**2 \\* 3**", 8, 8, "preserve literal star");
    eq(wrap("**2 \\* 3**", 10, 10, BOLD), "2 \\* 3", 6, 6, "remove bold around literal star");

    eq(prefix("раз\nдва", 0, 7, "bullet"), "- раз\n- два", 2, 11, "bullet add");
    eq(prefix("- раз\n- два", 2, 11, "bullet"), "раз\nдва", 0, 7, "bullet remove");
    eq(prefix("раз\n\nдва", 0, 8, "bullet"), "- раз\n\n- два", 2, 12, "bullet skip empty");
    eq(prefix("- раз\nдва", 0, 9, "bullet"), "- раз\n- два", 0, 11, "bullet mixed");
    eq(prefix("раз\nдва\nтри", 0, 11, "number"), "1. раз\n2. два\n3. три", 3, 20, "number add");
    eq(prefix("1. раз\n2. два", 0, 13, "number"), "раз\nдва", 0, 7, "number remove");
    eq(prefix("сделать", 0, 7, "check"), "- [ ] сделать", 6, 13, "check add");
    eq(prefix("- [ ] сделать", 0, 13, "check"), "сделать", 0, 7, "check remove");
    eq(prefix("- [x] сделать", 0, 13, "check"), "сделать", 0, 7, "check done remove");
    eq(prefix("один", 4, 4, "bullet"), "- один", 6, 6, "cursor at end");
    eq(prefix("a\nb", 2, 0, "check"), "- [ ] a\nb", 8, 6, "reverse to next line start");
    eq(prefix("- a\n- [x] *b*\n\n9. c", 0, 19, "number"),
        "1. a\n2. *b*\n\n3. c", 0, 17, "switch mixed list types");
    eq(prefix("  - [x] a", 9, 9, "bullet"), "  - a", 5, 5, "switch indented checkbox");
    eq(prefix("\nitem", 0, 0, "check"), "\nitem", 0, 0, "blank line with checkbox action");

    String checklist = "- [ ] ***да***\n- [X] ~~нет~~\n- ещё";
    span(checklist, 0, 5, CHECK);
    span(checklist, 6, 9, HIDDEN);
    span(checklist, 9, 11, ITALIC);
    span(checklist, 9, 11, BOLD);
    span(checklist, 15, 20, CHECKED);
    span(checklist, 23, 26, STRIKE);
    span(checklist, 29, 30, BULLET);
    check(toggledCheck(checklist, 0) == 'x', "check off to on");
    check(toggledCheck(checklist, 15) == ' ', "check on to off");
    check(toggledCheck(checklist, 1) == 0, "reject non-checkbox");
    check(decorations("****\n-\n*незакрыто").isEmpty(), "unmatched markers remain visible");
    check(decorations("2 * 3 * 4").isEmpty(), "multiplication remains visible");
    String crossing = wrap("**abc**def", 4, 9, ITALIC).text();
    span(crossing, 2, crossing.indexOf("**d"), BOLD);
    span(crossing, crossing.indexOf("c"), crossing.lastIndexOf('*'), ITALIC);

    System.out.println("TextFormatTest OK: " + count);
  }
}
