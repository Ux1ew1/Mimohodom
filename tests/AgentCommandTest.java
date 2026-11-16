package app.fastnotes;

import java.util.List;

/** Проверяет разбор консольных команд моста агента без Android. */
public final class AgentCommandTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static void same(Object expected, Object actual, String name) {
    check(expected.equals(actual), name + ": ожидалось " + expected + ", получено " + actual);
  }

  public static void main(String[] args) {
    // Слова и кавычки
    same(List.of("a", "b c", "d"), AgentCommand.tokenize("a \"b c\" d"), "двойные кавычки");
    same(List.of("a", "b \"c\""), AgentCommand.tokenize("a \"b \\\"c\\\"\""), "экранированная кавычка");
    same(List.of("a\\b"), AgentCommand.tokenize("'a\\b'"), "одинарные кавычки буквально");
    same(List.of("x", "line1\nline2"), AgentCommand.tokenize("x \"line1\\nline2\""), "перенос строки");
    same(List.of("", "z"), AgentCommand.tokenize("\"\" z"), "пустое слово в кавычках");
    same(List.of("ab"), AgentCommand.tokenize("a\"b\""), "склеивание слова и кавычек");
    same(List.of("Сохранить заметку"), AgentCommand.tokenize("  \"Сохранить заметку\"  "), "кириллица");
    check(AgentCommand.tokenize("   ").isEmpty(), "пробелы дают пустой список");
    try {
      AgentCommand.tokenize("a \"b");
      check(false, "незакрытая кавычка должна падать");
    } catch (IllegalArgumentException expected) {
      check(true, "незакрытая кавычка");
    }
    try {
      AgentCommand.parse("  ");
      check(false, "пустая команда должна падать");
    } catch (IllegalArgumentException expected) {
      check(true, "пустая команда");
    }

    // Команда, аргументы и опции
    AgentCommand tap = AgentCommand.parse("ui.tap \"Сохранить заметку\" --nth=2 --exact");
    same("ui.tap", tap.name, "имя команды");
    same(List.of("Сохранить заметку"), tap.args, "позиционные аргументы");
    same(2L, tap.number("nth", 0), "числовая опция");
    check(tap.flag("exact"), "флаг без значения включён");
    check(!tap.flag("missing"), "отсутствующий флаг выключен");
    same("x", tap.option("missing", "x"), "значение по умолчанию");
    check(!AgentCommand.parse("c --a=false").flag("a"), "--a=false выключает флаг");
    same("a=b", AgentCommand.parse("c --k=a=b").option("k", ""), "знак = внутри значения");
    same("", AgentCommand.parse("c --k=").option("k", "x"), "пустое значение опции");

    // Слово в кавычках и после «--» не опция
    same(List.of("--nth=1"), AgentCommand.parse("ui.type \"--nth=1\"").args, "кавычки отключают опции");
    same(List.of("--nth=1", "b"), AgentCommand.parse("ui.type -- --nth=1 b").args, "терминатор --");
    same("--", AgentCommand.parse("ui.type \"--\"").args.get(0), "«--» в кавычках — текст");
    same(1, AgentCommand.parse("c -").args.size(), "одиночный дефис — аргумент");
    AgentCommand spaced = AgentCommand.parse("ui.type \"текст\" --target=\"desc:Поиск по заметкам\" --replace");
    same("desc:Поиск по заметкам", spaced.option("target", ""), "значение опции в кавычках");
    same(List.of("текст"), spaced.args, "значение опции не попало в аргументы");
    check(spaced.flag("replace"), "флаг после опции с кавычками");

    // Ошибки аргументов
    try {
      AgentCommand.parse("notes.get").arg(0);
      check(false, "нет аргумента");
    } catch (IllegalArgumentException expected) {
      check(expected.getMessage().contains("notes.get"), "сообщение называет команду");
    }
    try {
      AgentCommand.parse("notes.get abc").idArg(0);
      check(false, "нечисловой номер");
    } catch (IllegalArgumentException expected) {
      check(true, "нечисловой номер");
    }
    try {
      AgentCommand.parse("c --limit=abc").number("limit", 1);
      check(false, "нечисловая опция");
    } catch (IllegalArgumentException expected) {
      check(true, "нечисловая опция");
    }

    // quote обратим для любых строк, в том числе с дефисами и кавычками
    String[] samples = {
      "", "plain", "two words", "--flag", "--", "a\"b", "back\\slash", "it's", "tab\tend",
      "line1\nline2", "кириллица и emoji 😀", "\"already\"", "x=y --z"
    };
    for (String sample : samples) {
      AgentCommand round = AgentCommand.parse("cmd " + AgentCommand.quote(sample));
      same(List.of(sample), round.args, "quote round-trip «" + sample + "»");
      check(round.options.isEmpty(), "quote не создаёт опций из «" + sample + "»");
    }

    System.out.println("AgentCommandTest: " + count + " проверок пройдено");
  }
}
