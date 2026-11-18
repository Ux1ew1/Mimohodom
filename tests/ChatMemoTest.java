package app.fastnotes;

import java.util.List;

/** Проверяет память диалога помощника без Android. */
public final class ChatMemoTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  public static void main(String[] args) {
    // Разбор ответа модели
    String raw = "Хорошо, с кем вы работаете?\n[[memo]]\n- Смены в буфете: 14, 15, 21, 22\n• смены в БУФЕТЕ 14, 15, 21, 22!\n3) Работает с Ольгой\n[[/memo]]";
    List<String> facts = ChatMemo.parse(raw);
    check(facts.size() == 2, "duplicates and bullets: " + facts);
    check(facts.get(0).equals("Смены в буфете: 14, 15, 21, 22"), "bullet removed");
    check(facts.get(1).equals("Работает с Ольгой"), "numbered bullet removed");
    check(ChatMemo.strip(raw).equals("Хорошо, с кем вы работаете?"), "memo block stripped");
    check(ChatMemo.parse("просто текст").isEmpty(), "no block");
    check(ChatMemo.parse(null).isEmpty(), "null");
    check(ChatMemo.strip("текст [[memo]]\nфакт без конца").equals("текст"), "unclosed block stripped");
    check(ChatMemo.parse("[[memo]]\nфакт без конца").get(0).equals("факт без конца"), "unclosed block parsed");
    check(ChatMemo.parse("[[memo]]\n---\n!!!\n[[/memo]]").isEmpty(), "lines without letters dropped");
    check(ChatMemo.parse("[[memo]]\n[[note:3]] Встреча в 10\n[[/memo]]").get(0).equals("note:3 Встреча в 10"), "markers stripped");
    StringBuilder many = new StringBuilder("[[memo]]");
    for (int i = 0; i < 9; i++) many.append("\nфакт номер ").append(i);
    check(ChatMemo.parse(many.toString()).size() == ChatMemo.MAX_FACTS_PER_MESSAGE, "facts capped per message");
    String longFact = ChatMemo.parse("[[memo]]\n" + "слово ".repeat(60)).get(0);
    check(longFact.length() <= ChatMemo.MAX_FACT + 1 && longFact.endsWith("…"), "long fact cut");

    // Хранение и разбор
    String stored = ChatMemo.attach("Понял.", List.of("Смена 14 и 15", "С Ольгой"));
    ChatMemo.Split split = ChatMemo.split(stored);
    check(split.text.equals("Понял.") && split.facts.size() == 2 && !split.recap, "attach then split");
    check(ChatMemo.attach("Текст", List.of()).equals("Текст"), "nothing to attach");
    check(ChatMemo.split("Обычный ответ").facts.isEmpty(), "plain message has no facts");

    // Итог
    List<String> bodies =
        List.of(
            ChatMemo.attach("Когда смена?", List.of("Смены 14, 15, 21, 22")),
            "14,15,21,22",
            ChatMemo.attach("С кем?", List.of("смены 14, 15, 21, 22", "С Ольгой")),
            ChatMemo.RECAP + "Итог. Запомнил:\n• старый факт");
    List<String> all = ChatMemo.all(bodies);
    check(all.size() == 2 && all.get(1).equals("С Ольгой"), "all facts deduplicated, recap skipped: " + all);
    String recap = ChatMemo.recap(all, 7, false);
    check(recap.startsWith(ChatMemo.RECAP + "Итог. Запомнил:\n• Смены 14, 15, 21, 22\n• С Ольгой"), "recap lists facts");
    check(recap.endsWith("Записал в заметку [[note:7]]."), "recap links the note");
    check(ChatMemo.recap(all, 7, true).contains("Обновил заметку [[note:7]]"), "recap of an update");
    ChatMemo.Split back = ChatMemo.split(recap);
    check(back.recap && back.text.startsWith("Итог") && back.facts.isEmpty(), "recap is recognised");
    check(ChatMemo.recap(List.of(), 3, false).equals(ChatMemo.RECAP + "Итог\n\nЗаписал в заметку [[note:3]]."), "recap without facts");
    List<String> twenty = new java.util.ArrayList<>();
    for (int i = 0; i < 20; i++) twenty.add("факт " + i);
    String capped = ChatMemo.recap(twenty, 1, false);
    check(capped.contains("• и ещё 8") && !capped.contains("факт 12"), "recap capped");
    check(ChatMemo.fromNote("Смены в буфете\n\n- 14, 15\nСмены в буфете").size() == 2, "fallback facts from the note");

    System.out.println("ChatMemo tests passed: " + count);
  }
}
