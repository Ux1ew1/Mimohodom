package app.fastnotes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Разбор строки консольной команды моста агента (без Android, проверяется scripts/test-agent.ps1).
 *
 * <p>Формат: {@code имя арг1 "арг 2" --ключ=значение --флаг}. В двойных кавычках работают
 * {@code \" \\ \n \t \r}, в одинарных всё буквально. Слово, начинающееся с кавычки, никогда не считается опцией
 * (а {@code --ключ="значение с пробелом"} остаётся опцией),
 * а всё после одиночного {@code --} — позиционные аргументы. Значения опций пишутся только через
 * {@code =}, поэтому {@code --флаг} не съедает следующее слово.
 */
final class AgentCommand {
  final String name;
  final List<String> args;
  final Map<String, String> options;

  private AgentCommand(String name, List<String> args, Map<String, String> options) {
    this.name = name;
    this.args = args;
    this.options = options;
  }

  static AgentCommand parse(String line) {
    List<String> words = new ArrayList<>();
    List<Boolean> quoted = new ArrayList<>();
    scan(line, words, quoted);
    if (words.isEmpty()) throw new IllegalArgumentException("пустая команда");
    List<String> args = new ArrayList<>();
    Map<String, String> options = new LinkedHashMap<>();
    boolean literal = false;
    for (int i = 1; i < words.size(); i++) {
      String word = words.get(i);
      boolean plainWord = !literal && !quoted.get(i);
      if (plainWord && word.equals("--")) {
        literal = true;
      } else if (plainWord && word.startsWith("--") && word.length() > 2) {
        int eq = word.indexOf('=');
        if (eq < 0) options.put(word.substring(2), "true");
        else options.put(word.substring(2, eq), word.substring(eq + 1));
      } else args.add(word);
    }
    return new AgentCommand(words.get(0), args, options);
  }

  static List<String> tokenize(String line) {
    List<String> words = new ArrayList<>();
    scan(line, words, new ArrayList<>());
    return words;
  }

  /** Слова строки и признак «слово было в кавычках». */
  private static void scan(String line, List<String> words, List<Boolean> quoted) {
    StringBuilder word = new StringBuilder();
    boolean inWord = false, wasQuoted = false;
    int i = 0, n = line.length();
    while (i < n) {
      char c = line.charAt(i);
      if (c == '"') {
        if (!inWord) wasQuoted = true;
        inWord = true;
        i++;
        boolean closed = false;
        while (i < n) {
          char d = line.charAt(i++);
          if (d == '"') {
            closed = true;
            break;
          }
          if (d == '\\' && i < n) {
            char e = line.charAt(i++);
            word.append(e == 'n' ? '\n' : e == 't' ? '\t' : e == 'r' ? '\r' : e);
          } else word.append(d);
        }
        if (!closed) throw new IllegalArgumentException("не закрыта двойная кавычка");
      } else if (c == '\'') {
        if (!inWord) wasQuoted = true;
        inWord = true;
        int end = line.indexOf('\'', i + 1);
        if (end < 0) throw new IllegalArgumentException("не закрыта одинарная кавычка");
        word.append(line, i + 1, end);
        i = end + 1;
      } else if (Character.isWhitespace(c)) {
        if (inWord) {
          words.add(word.toString());
          quoted.add(wasQuoted);
          word.setLength(0);
          inWord = false;
          wasQuoted = false;
        }
        i++;
      } else {
        inWord = true;
        word.append(c);
        i++;
      }
    }
    if (inWord) {
      words.add(word.toString());
      quoted.add(wasQuoted);
    }
  }

  /** Обратная операция: слово в кавычках, которое {@link #parse} вернёт как позиционный аргумент. */
  static String quote(String value) {
    StringBuilder out = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') out.append('\\').append(c);
      else if (c == '\n') out.append("\\n");
      else if (c == '\t') out.append("\\t");
      else if (c == '\r') out.append("\\r");
      else out.append(c);
    }
    return out.append('"').toString();
  }

  String arg(int index) {
    if (index >= args.size())
      throw new IllegalArgumentException("не хватает аргумента №" + (index + 1) + " для " + name);
    return args.get(index);
  }

  String arg(int index, String fallback) {
    return index < args.size() ? args.get(index) : fallback;
  }

  boolean flag(String key) {
    String value = options.get(key);
    return value != null && !value.equals("false") && !value.equals("0");
  }

  String option(String key, String fallback) {
    String value = options.get(key);
    return value == null ? fallback : value;
  }

  long number(String key, long fallback) {
    String value = options.get(key);
    if (value == null) return fallback;
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("--" + key + " должен быть числом, а не «" + value + "»");
    }
  }

  long idArg(int index) {
    String value = arg(index);
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("нужен числовой номер, а не «" + value + "»");
    }
  }
}
