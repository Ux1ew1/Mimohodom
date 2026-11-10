package app.fastnotes;

import java.util.*;
import org.json.*;

public final class SummaryTest {
  static int checks;

  static void check(boolean pass, String message) {
    if (!pass) throw new AssertionError(message);
    checks++;
  }

  static Note note(long id, String title, String body) {
    return new Note(id, title, body, 1, 1, 0);
  }

  static JSONObject response(String content, String finish) throws Exception {
    return new JSONObject()
        .put(
            "choices",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("finish_reason", finish)
                        .put("message", new JSONObject().put("content", content))));
  }

  public static void main(String[] args) throws Exception {
    List<Note> cars =
        Arrays.asList(
            note(1, "Замена масла", "Масло в машине 186612 км"),
            note(2, "Прогреть машину", "Прогреть машину"),
            note(3, "Дворник", "Заменить дворник на пассажирском"),
            note(4, "Розовая папка", "В Поло розовая папка в бардачке"));
    List<Note> archive = new ArrayList<>(cars);
    archive.add(note(5, "Десерты", "Тирамису эклер"));
    archive.add(note(6, "Покупки", "Купить молоко"));
    List<Note> selected = TopicSelection.select(archive);
    check(selected.size() == 4, "All car notes selected, unrelated notes excluded");
    for (int i = 0; i < 4; i++) {
      List<Note> rotated = new ArrayList<>(archive);
      Collections.swap(rotated, 0, i);
      check(
          TopicSelection.select(rotated).stream()
              .map(n -> n.id)
              .toList()
              .equals(selected.stream().map(n -> n.id).toList()),
          "Topic independent of rotating seed");
    }
    List<JSONObject> proposals = TopicSelection.proposals(selected, Collections.emptyList());
    check(
        proposals.size() == 1 && proposals.get(0).getJSONArray("sources").length() == 4,
        "Whole topic offered, not random pair");
    check(
        TopicSelection.select(Collections.singletonList(archive.get(4))).size() == 1,
        "No unrelated padding");
    List<Note> many = new ArrayList<>();
    for (int i = 0; i < 12; i++) many.add(note(i + 1, "Машина", "Дворник в машине " + i));
    check(TopicSelection.select(many).size() == 8, "Review stays bounded");
    Note holidayA = note(30, "Отпуск", "Билеты в Казань"),
        holidayB = note(31, "Гостиница", "Номер с завтраком");
    holidayA.metadata = "{\"ai\":{\"topics\":[\"Поездка в Казань\"]}}";
    holidayB.metadata = holidayA.metadata;
    check(
        TopicSelection.select(Arrays.asList(holidayA, holidayB)).size() == 2,
        "Shared metadata topic connects different vocabulary");
    check(
        TopicSelection.select(
                    Arrays.asList(
                        note(40, "Купить молоко", "Купить молоко сегодня"),
                        note(41, "Купить билет", "Купить билет сегодня")))
                .size()
            == 1,
        "Generic action and date do not define a topic");
    String longText = "а".repeat(5799) + "😀" + "б".repeat(18000);
    Note large = note(20, "PRIVATE TITLE", longText);
    List<String> fragments = SummaryAnalysis.fragments(Arrays.asList(large, archive.get(4)));
    check(
        fragments.stream()
            .map(f -> f.substring(f.indexOf("\nТекст:\n") + 8))
            .reduce("", String::concat)
            .equals(longText + archive.get(4).body),
        "Full original texts survive chunking");
    int cursor = 0, calls = 0;
    String previous = "";
    while (cursor < fragments.size()) {
      JSONObject payload = SummaryAnalysis.payload("test", previous, fragments, cursor);
      JSONObject input =
          new JSONObject(payload.getJSONArray("messages").getJSONObject(1).getString("content"));
      JSONArray parts = input.getJSONArray("fragments");
      int chars = previous.length();
      for (int i = 0; i < parts.length(); i++) {
        String part = parts.getString(i);
        chars += part.length();
        check(
            !Character.isHighSurrogate(part.charAt(part.length() - 1)),
            "No broken Unicode boundary");
      }
      check(chars <= 12000 && parts.length() <= 8, "Every summary call respects input cap");
      check(
          payload
              .getJSONArray("messages")
              .getJSONObject(0)
              .getString("content")
              .contains("Не перечисляй исходные заметки"),
          "Source titles are context, not output headings");
      check(payload.getInt("max_tokens") == 1200, "Output cap");
      cursor += SummaryAnalysis.consumed(payload);
      calls++;
      previous = "Краткая выжимка";
    }
    check(
        calls > 1 && cursor == fragments.size(), "Large group fully processed in several requests");
    JSONObject good =
        SummaryAnalysis.parse(
            response("{\"title\":\"Автомобиль\",\"body\":\"Заменить дворник.\"}", "stop"));
    check(good.getString("body").equals("Заменить дворник."), "Model digest parsed");
    for (String invalid : Arrays.asList("{}", "{\"title\":\"x\",\"body\":\"\"}", "not json")) {
      boolean failed = false;
      try {
        SummaryAnalysis.parse(response(invalid, "stop"));
      } catch (Exception e) {
        failed = true;
      }
      check(failed, "Malformed digest rejected");
    }
    boolean failed = false;
    try {
      SummaryAnalysis.parse(response(good.toString(), "length"));
    } catch (Exception e) {
      failed = true;
    }
    check(failed, "Truncated digest rejected");
    System.out.println("PASS: " + checks + " summary and topic checks");
  }
}
