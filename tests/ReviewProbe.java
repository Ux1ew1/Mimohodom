package app.fastnotes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/**
 * Платная проверка общей проверки заметок (scripts/probe-review-polza.ps1): {@code payload <модель>
 * <файл>} пишет тот же запрос, что отправляет приложение (ReviewAnalysis), для восьми типичных
 * заметок; {@code parse <файл ответа>} разбирает ответ теми же правилами, что приложение.
 */
public final class ReviewProbe {
  static List<Note> notes(long now) {
    String[][] data = {
      {"", "Код домофона 4521, подъезд 2"},
      {"Хлеб", "купить хлеб и молоко"},
      {"Врач", "Записаться к терапевту, приём 25 ноября"},
      {"Выезд команды", "Организовать выезд команды на базу в июне: человек 15, транспорт, еда,"
          + " палатки, программа на два дня. Узнать цены у «Сосен» и «Озёрного»."},
      {"", "Zola одежда на МТС"},
      {"День рождения Пети", "У Пети день рождения 3 декабря, подарок — что-то для рыбалки"},
      {"Ремонт кухни", "Поменять столешницу, плитку над мойкой, вызвать электрика, выбрать"
          + " вытяжку. Бюджет до 150 тысяч."},
      {"Позвонить маме", "позвонить маме в выходные"},
    };
    List<Note> out = new ArrayList<>();
    for (int i = 0; i < data.length; i++) {
      Note n = new Note(i + 1, Note.autoTitle(data[i][0], data[i][1]), data[i][1], now - (10 - i) * 86_400_000L, now - (10 - i) * 86_400_000L, 0);
      n.autoTitle = data[i][0].isEmpty();
      out.add(n);
    }
    return out;
  }

  public static void main(String[] args) throws Exception {
    long now = System.currentTimeMillis();
    ZoneId zone = ZoneId.of("Europe/Moscow");
    List<Note> notes = notes(now);
    String only = System.getenv("PROBE_ONLY");
    if (only != null && !only.isEmpty()) notes = notes.subList(0, Integer.parseInt(only));
    if (args[0].equals("payload")) {
      JSONObject payload = ReviewAnalysis.payload(notes, args[1], now, zone);
      payload = ManagerAnalysis.withContext(payload, List.of(), Map.of(), List.of());
      Files.writeString(Path.of(args[2]), payload.toString(), StandardCharsets.UTF_8);
      System.out.println("max_tokens=" + payload.optInt("max_tokens") + " prompt_chars="
          + payload.getJSONArray("messages").getJSONObject(0).getString("content").length());
      return;
    }
    JSONObject response = new JSONObject(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8));
    Set<Long> ids = new HashSet<>();
    for (Note n : notes) ids.add(n.id);
    try {
      ReviewAnalysis.Result r = ReviewAnalysis.parse(response, notes, now);
      System.out.println("parse: OK, notes=" + r.notes.size() + ", proposals=" + r.proposals.size());
      for (JSONObject p : r.proposals)
        System.out.println("  " + p.optString("type") + ": " + p.optString("reason") + " " + p.optJSONArray("options"));
    } catch (Exception e) {
      System.out.println("parse: FAILED — " + ReviewAnalysis.error(e) + " (" + e + ")");
    }
    String text = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "");
    System.out.println("kinds: " + TaskKind.parse(text, ids));
  }
}
