package app.fastnotes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Разбирает сообщения в формате chat/completions для Gemini Nano (Google AICore). Класс не зависит
 * от Android и ML Kit, поэтому проверяется обычными JVM-тестами.
 */
final class AiCorePrompt {
  static final class Turn {
    final boolean model;
    final String text;

    Turn(boolean model, String text) {
      this.model = model;
      this.text = text;
    }
  }

  /** Короче этого предела системный текст не сокращается: иначе пропадёт смысл инструкции. */
  private static final int MIN_SYSTEM = 400;

  final String system;
  final List<Turn> turns;

  AiCorePrompt(String system, List<Turn> turns) {
    this.system = system;
    this.turns = turns;
  }

  static AiCorePrompt from(JSONArray messages) throws IOException, JSONException {
    StringBuilder system = new StringBuilder();
    List<Turn> turns = new ArrayList<>();
    for (int i = 0; i < messages.length(); i++) {
      JSONObject item = messages.getJSONObject(i);
      Object content = item.get("content");
      if (!(content instanceof String))
        throw new IOException("Gemini Nano в этом режиме поддерживает только текст");
      String text = ((String) content).trim();
      if (text.isEmpty()) continue;
      String role = item.getString("role");
      if (role.equals("system")) {
        if (system.length() > 0) system.append("\n\n");
        system.append(text);
        continue;
      }
      boolean model = role.equals("assistant");
      if (!model && !role.equals("user")) continue;
      Turn last = turns.isEmpty() ? null : turns.get(turns.size() - 1);
      if (last != null && last.model == model) {
        turns.set(turns.size() - 1, new Turn(model, last.text + "\n\n" + text));
      } else if (last != null || !model) {
        // Диалог должен начинаться с пользователя.
        turns.add(new Turn(model, text));
      }
    }
    if (turns.isEmpty() || turns.get(turns.size() - 1).model)
      throw new IOException("Нет вопроса пользователя для Gemini Nano");
    return new AiCorePrompt(system.toString(), turns);
  }

  /** Убирает самую старую пару «вопрос — ответ». Последний вопрос пользователя остаётся всегда. */
  AiCorePrompt withoutOldestExchange() {
    if (turns.size() < 3) return null;
    return new AiCorePrompt(system, new ArrayList<>(turns.subList(2, turns.size())));
  }

  /** Укорачивает системный текст с данными заметок. Возвращает null, когда сокращать больше нечего. */
  AiCorePrompt withSystemScaled(double ratio) {
    int target = (int) (system.length() * ratio);
    if (system.length() <= MIN_SYSTEM) return null;
    target = Math.max(MIN_SYSTEM, target);
    int cut = target;
    // Не режем суррогатную пару пополам.
    if (cut > 0 && Character.isHighSurrogate(system.charAt(cut - 1))) cut--;
    return new AiCorePrompt(system.substring(0, cut).trim() + "…", turns);
  }

  /** Для устройств без отдельного системного запроса: инструкция идёт в начало первого вопроса. */
  List<Turn> turnsWithInlineSystem() {
    List<Turn> result = new ArrayList<>(turns);
    if (!system.isEmpty()) {
      Turn first = result.get(0);
      result.set(0, new Turn(false, system + "\n\n" + first.text));
    }
    return result;
  }
}
