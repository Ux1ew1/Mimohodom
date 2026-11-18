package app.fastnotes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Память диалога, который начал помощник, без зависимости от Android. Модель в конце ответа
 * перечисляет, что нового узнала, блоком {@code [[memo]]…[[/memo]]} (по строке на факт). Факты
 * хранятся в тексте сообщения и показываются серым; когда заметка записана, приложение само
 * добавляет сообщение-итог.
 */
public final class ChatMemo {
  public static final String RECAP = "[[recap]]";
  public static final int MAX_FACTS_PER_MESSAGE = 4, MAX_FACT = 140, MAX_RECAP_FACTS = 12;

  private static final Pattern BLOCK =
      Pattern.compile("\\[\\[memo]](.*?)(?:\\[\\[/memo]]|$)", Pattern.DOTALL);

  private ChatMemo() {}

  /** Разобранное сообщение: текст без служебных блоков, факты и признак итога. */
  public static final class Split {
    public String text = "";
    public List<String> facts = new ArrayList<>();
    public boolean recap;
  }

  /** Факты из ответа модели (первый блок memo). */
  public static List<String> parse(String raw) {
    List<String> facts = new ArrayList<>();
    if (raw == null) return facts;
    Matcher m = BLOCK.matcher(raw);
    if (!m.find()) return facts;
    for (String line : m.group(1).split("\\R")) {
      String fact = fact(line);
      if (!fact.isEmpty() && !contains(facts, fact)) facts.add(fact);
      if (facts.size() >= MAX_FACTS_PER_MESSAGE) break;
    }
    return facts;
  }

  /** Ответ без блоков memo. */
  public static String strip(String raw) {
    return raw == null ? "" : BLOCK.matcher(raw).replaceAll("").trim();
  }

  /** Одна строка факта: без маркера списка, служебных меток и лишних пробелов. */
  static String fact(String line) {
    String value =
        line.replace("[[", "")
            .replace("]]", "")
            .replaceAll("^\\s*(?:[-•*–—]|\\d+[.)])\\s*", "")
            .replaceAll("\\s+", " ")
            .trim();
    boolean letters = false;
    for (int i = 0; i < value.length() && !letters; i++) letters = Character.isLetterOrDigit(value.charAt(i));
    if (!letters) return "";
    if (value.codePointCount(0, value.length()) > MAX_FACT)
      value = value.substring(0, value.offsetByCodePoints(0, MAX_FACT)).trim() + "…";
    return value;
  }

  private static String key(String fact) {
    return fact.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
  }

  /** Есть ли такой факт в списке (без учёта регистра и знаков препинания). */
  public static boolean contains(List<String> facts, String fact) {
    String k = key(fact);
    for (String f : facts) if (key(f).equals(k)) return true;
    return false;
  }

  /** Сообщение для хранения: текст и факты в конце. */
  public static String attach(String text, List<String> facts) {
    if (facts == null || facts.isEmpty()) return text;
    StringBuilder out = new StringBuilder(text).append("\n[[memo]]");
    for (String fact : facts) out.append('\n').append(fact);
    return out.append("\n[[/memo]]").toString();
  }

  public static Split split(String body) {
    Split out = new Split();
    if (body == null) return out;
    String text = body;
    if (text.startsWith(RECAP)) {
      out.recap = true;
      text = text.substring(RECAP.length()).trim();
    }
    out.facts = parse(text);
    out.text = strip(text);
    return out;
  }

  /** Все факты диалога по порядку, без повторов. Сообщения-итоги пропускаются. */
  public static List<String> all(List<String> bodies) {
    List<String> facts = new ArrayList<>();
    for (String body : bodies) {
      Split s = split(body);
      if (s.recap) continue;
      for (String f : s.facts) if (!contains(facts, f)) facts.add(f);
    }
    return facts;
  }

  /** Сообщение-итог: что запомнил и в какую заметку записал. */
  public static String recap(List<String> facts, long note, boolean updated) {
    StringBuilder out = new StringBuilder(RECAP).append("Итог");
    if (facts != null && !facts.isEmpty()) {
      out.append(". Запомнил:");
      int shown = Math.min(facts.size(), MAX_RECAP_FACTS);
      for (int i = 0; i < shown; i++) out.append("\n• ").append(facts.get(i));
      if (facts.size() > shown) out.append("\n• и ещё ").append(facts.size() - shown);
    }
    out.append("\n\n").append(updated ? "Обновил заметку" : "Записал в заметку");
    return out.append(" [[note:").append(note).append("]].").toString();
  }

  /** Строки текста заметки как запасные факты, если модель не прислала memo. */
  public static List<String> fromNote(String body) {
    List<String> facts = new ArrayList<>();
    if (body == null) return facts;
    for (String line : body.split("\\R")) {
      String f = fact(line);
      if (!f.isEmpty() && !contains(facts, f)) facts.add(f);
      if (facts.size() >= 6) break;
    }
    return facts;
  }
}
