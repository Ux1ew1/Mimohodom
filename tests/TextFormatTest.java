package app.fastnotes;

/** Текстовые преобразования редактора: обёртки и префиксы строк с переключением. */
public final class TextFormatTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static void eq(TextFormat.Result r, String text, int start, int end, String name) {
    check(r.text.equals(text), name + " text: <" + r.text + ">");
    check(r.start == start && r.end == end, name + " cursor " + r.start + ".." + r.end);
    count++; // пара проверок выше — одна по смыслу
  }

  public static void main(String[] args) {
    // Обёртка выделения и снятие повторным применением.
    eq(TextFormat.wrap("купить молоко", 7, 13, "**"), "купить **молоко**", 9, 15, "bold wrap");
    eq(TextFormat.wrap("купить **молоко**", 9, 15, "**"), "купить молоко", 7, 13, "bold unwrap");
    eq(TextFormat.wrap("тихо *сказал* он", 6, 12, "*"), "тихо сказал он", 5, 11, "italic unwrap");
    eq(TextFormat.wrap("~~старое~~ новое", 2, 8, "~~"), "старое новое", 0, 6, "strike unwrap");

    // Без выделения: пара маркеров, курсор между ними.
    eq(TextFormat.wrap("текст", 5, 5, "**"), "текст****", 7, 7, "empty wrap");

    // Маркеры внутри выделения снимаются.
    eq(TextFormat.wrap("а **молоко** б", 2, 12, "**"), "а молоко б", 2, 8, "inner unwrap");

    // Маркированный список: добавить, повтор — снять.
    eq(TextFormat.prefix("раз\nдва", 0, 7, "bullet"), "- раз\n- два", 2, 11, "bullet add");
    eq(TextFormat.prefix("- раз\n- два", 2, 11, "bullet"), "раз\nдва", 0, 7, "bullet remove");
    eq(TextFormat.prefix("раз\n\nдва", 0, 8, "bullet"), "- раз\n\n- два", 2, 12, "bullet skip empty");

    // Частично размеченные строки: добавляем недостающее, а не снимаем.
    eq(TextFormat.prefix("- раз\nдва", 0, 9, "bullet"), "- раз\n- два", 0, 11, "bullet mixed");

    // Нумерованный список: номера по порядку, повтор снимает.
    eq(TextFormat.prefix("раз\nдва\nтри", 0, 11, "number"), "1. раз\n2. два\n3. три", 3, 20, "number add");
    eq(TextFormat.prefix("1. раз\n2. два", 0, 13, "number"), "раз\nдва", 0, 7, "number remove");

    // Чекбоксы: добавить, повтор снимает; отмеченные тоже чистятся.
    eq(TextFormat.prefix("сделать", 0, 7, "check"), "- [ ] сделать", 6, 13, "check add");
    eq(TextFormat.prefix("- [ ] сделать", 0, 13, "check"), "сделать", 0, 7, "check remove");
    eq(TextFormat.prefix("- [x] сделать", 0, 13, "check"), "сделать", 0, 7, "check done remove");

    // Курсоры на краях текста не падают.
    eq(TextFormat.wrap("", 0, 0, "**"), "****", 2, 2, "empty text");
    eq(TextFormat.prefix("один", 4, 4, "bullet"), "- один", 6, 6, "cursor at end");

    System.out.println("TextFormatTest OK: " + count);
  }
}
