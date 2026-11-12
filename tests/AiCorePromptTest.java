package app.fastnotes;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Проверяет разбор сообщений для Gemini Nano без Android и без обращения к модели. */
public final class AiCorePromptTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static JSONObject message(String role, Object content) {
    return new JSONObject().put("role", role).put("content", content);
  }

  static boolean fails(JSONArray messages) throws Exception {
    try {
      AiCorePrompt.from(messages);
      return false;
    } catch (IOException expected) {
      return true;
    }
  }

  public static void main(String[] args) throws Exception {
    AiCorePrompt basic =
        AiCorePrompt.from(
            new JSONArray()
                .put(message("system", "Инструкция"))
                .put(message("user", "Привет"))
                .put(message("assistant", "Здравствуйте"))
                .put(message("user", "Что у меня записано?")));
    check(basic.system.equals("Инструкция"), "system text");
    check(basic.turns.size() == 3, "three turns");
    check(!basic.turns.get(0).model && basic.turns.get(1).model, "roles alternate");
    check(basic.turns.get(2).text.equals("Что у меня записано?"), "last user turn");

    AiCorePrompt merged =
        AiCorePrompt.from(
            new JSONArray()
                .put(message("system", "A"))
                .put(message("system", "B"))
                .put(message("user", "один"))
                .put(message("user", "два")));
    check(merged.system.equals("A\n\nB"), "system parts joined");
    check(merged.turns.size() == 1 && merged.turns.get(0).text.equals("один\n\nдва"), "merged user");

    AiCorePrompt leading =
        AiCorePrompt.from(
            new JSONArray().put(message("assistant", "Хвост")).put(message("user", "Вопрос")));
    check(leading.turns.size() == 1 && !leading.turns.get(0).model, "leading model turn dropped");

    check(fails(new JSONArray().put(message("system", "только система"))), "needs user turn");
    check(
        fails(new JSONArray().put(message("user", "q")).put(message("assistant", "a"))),
        "must end with user");
    check(
        fails(new JSONArray().put(message("user", new JSONArray().put("картинка")))),
        "text only");
    check(fails(new JSONArray().put(message("user", "   "))), "blank user ignored");

    AiCorePrompt many =
        AiCorePrompt.from(
            new JSONArray()
                .put(message("user", "1"))
                .put(message("assistant", "2"))
                .put(message("user", "3"))
                .put(message("assistant", "4"))
                .put(message("user", "5")));
    AiCorePrompt shorter = many.withoutOldestExchange();
    check(shorter.turns.size() == 3 && shorter.turns.get(0).text.equals("3"), "oldest pair dropped");
    check(shorter.withoutOldestExchange().turns.size() == 1, "down to last question");
    check(shorter.withoutOldestExchange().withoutOldestExchange() == null, "last question kept");

    StringBuilder long_ = new StringBuilder();
    for (int i = 0; i < 100; i++) long_.append("Заметка номер ").append(i).append(". ");
    AiCorePrompt big =
        new AiCorePrompt(long_.toString(), java.util.List.of(new AiCorePrompt.Turn(false, "q")));
    AiCorePrompt scaled = big.withSystemScaled(0.5);
    check(scaled.system.length() < big.system.length() * 0.6, "system scaled down");
    check(scaled.system.endsWith("…"), "scaled marker");
    check(scaled.turns.size() == 1, "turns kept when scaling");
    AiCorePrompt tiny = new AiCorePrompt("коротко", big.turns);
    check(tiny.withSystemScaled(0.5) == null, "short system not scaled");
    AiCorePrompt emoji = new AiCorePrompt("😀".repeat(500), big.turns);
    String cut = emoji.withSystemScaled(0.55).system;
    check(!Character.isHighSurrogate(cut.charAt(cut.length() - 2)), "surrogate pair not split");

    var inline = basic.turnsWithInlineSystem();
    check(inline.size() == 3 && inline.get(0).text.startsWith("Инструкция\n\nПривет"), "inline system");
    check(inline.get(1).model, "inline keeps roles");
    check(basic.turns.get(0).text.equals("Привет"), "original untouched");
    System.out.println("AiCorePromptTest: " + count + " checks passed");
  }
}
