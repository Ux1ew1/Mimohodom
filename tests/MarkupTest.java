package app.fastnotes;

import java.util.List;

/** Разметка ответов модели: стили, краевые случаи, устойчивость к сломанному вводу. */
public final class MarkupTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static Markup.Span only(String raw) {
    Markup.Result r = Markup.parse(raw);
    check(r.spans.size() == 1, "one span in: " + raw);
    return r.spans.get(0);
  }

  static boolean has(Markup.Result r, int flags, String covered) {
    for (Markup.Span s : r.spans)
      if (s.flags == flags && r.text.substring(s.start, s.end).equals(covered)) return true;
    return false;
  }

  public static void main(String[] args) {
    // Жирный, курсив, зачёркнутый, код.
    Markup.Result r = Markup.parse("Это **важно** знать");
    check(r.text.equals("Это важно знать"), "bold text");
    check(has(r, Markup.BOLD, "важно"), "bold span");

    r = Markup.parse("тихо *сказал* он");
    check(has(r, Markup.ITALIC, "сказал"), "italic span");

    r = Markup.parse("~~удалено~~ осталось");
    check(r.text.equals("удалено осталось") && has(r, Markup.STRIKE, "удалено"), "strike");

    r = Markup.parse("вызов `store.notes()` вернёт");
    check(has(r, Markup.CODE, "store.notes()"), "code span");

    // Жирный+курсив тремя звёздами.
    r = Markup.parse("***очень*** важно");
    check(has(r, Markup.BOLD | Markup.ITALIC, "очень"), "bold italic");

    // Вложенность: курсив внутри жирного.
    r = Markup.parse("**жирный и *наклонный* текст**");
    check(r.text.equals("жирный и наклонный текст"), "nested text");
    check(has(r, Markup.BOLD, "жирный и наклонный текст"), "nested bold");
    check(has(r, Markup.ITALIC, "наклонный"), "nested italic");

    // Подчёркивания: __жирный__, _курсив_, но не some_var_name.
    r = Markup.parse("__важно__ и _мягко_");
    check(has(r, Markup.BOLD, "важно") && has(r, Markup.ITALIC, "мягко"), "underscores");
    r = Markup.parse("поле some_var_name не трогаем");
    check(r.spans.isEmpty() && r.text.equals("поле some_var_name не трогаем"), "snake_case safe");

    // Незакрытые и пустые конструкции — обычный текст.
    r = Markup.parse("оборвалось **на середине");
    check(r.spans.isEmpty() && r.text.equals("оборвалось **на середине"), "unclosed bold");
    r = Markup.parse("пусто **** тут");
    check(r.spans.isEmpty() && r.text.equals("пусто **** тут"), "empty bold");
    r = Markup.parse("одинокая * звезда");
    check(r.spans.isEmpty(), "lone star");

    // Заголовки.
    r = Markup.parse("## План на день\nпункт");
    check(r.text.equals("План на день\nпункт"), "heading text");
    check(has(r, Markup.BOLD | Markup.HEADING, "План на день"), "heading span");

    // Списки: маркеры в «• », нумерованные без изменений.
    r = Markup.parse("- раз\n* два\n+ три\n1. четыре");
    check(r.text.equals("• раз\n• два\n• три\n1. четыре"), "list markers");

    // Цитата.
    r = Markup.parse("> слова автора\nмои");
    check(has(r, Markup.QUOTE, "слова автора"), "quote span");

    // Ссылка.
    r = Markup.parse("смотри [документ](https://example.com/a) тут");
    check(r.text.equals("смотри документ тут"), "link text");
    Markup.Span link = null;
    for (Markup.Span s : r.spans) if (s.flags == Markup.LINK) link = s;
    check(link != null && "https://example.com/a".equals(link.url), "link url");
    check(r.text.substring(link.start, link.end).equals("документ"), "link cover");

    // Ограждённый блок кода: разметка внутри не действует, ограждения исчезают.
    r = Markup.parse("до\n```java\nint **x** = 1;\n```\nпосле");
    check(r.text.equals("до\nint **x** = 1;\nпосле"), "fenced text");
    check(has(r, Markup.CODE, "int **x** = 1;"), "fenced span");
    check(!has(r, Markup.BOLD, "x"), "fenced no inner");

    // Незакрытое ограждение тянется до конца, без падения.
    r = Markup.parse("текст\n```\nкод без конца");
    check(r.text.equals("текст\nкод без конца") && has(r, Markup.CODE, "код без конца"), "unclosed fence");

    // Экранирование.
    r = Markup.parse("не \\*жирный\\* текст");
    check(r.spans.isEmpty() && r.text.equals("не *жирный* текст"), "escaped star");

    // Служебный токен заметки проходит как текст (его подменяет активность).
    r = Markup.parse("смотри [[note:12]] и **жирное**");
    check(r.text.equals("смотри [[note:12]] и жирное"), "note token intact");
    check(has(r, Markup.BOLD, "жирное"), "note token bold sibling");

    // Пустой и null ввод.
    check(Markup.parse("").text.isEmpty(), "empty");
    check(Markup.parse(null).text.isEmpty(), "null");

    // Мусорный ввод не роняет разбор.
    Markup.parse("**`*_~[```\n~~](`### > **");
    check(true, "garbage survives");

    // Реальный обрыв ответа модели по лимиту токенов.
    r = Markup.parse("1. Первый пункт\n2. Второй с **важ");
    check(r.text.contains("**важ"), "truncated answer safe");

    System.out.println("MarkupTest OK: " + count);
  }
}
