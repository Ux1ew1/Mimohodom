package app.fastnotes;

import java.util.*;

public final class CoreTest {
  private static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static Note note(long id, String title, String body, long reminder) {
    return new Note(id, title, body, 1000, id * 1000, reminder);
  }

  public static void main(String[] args) {
    check(Note.autoTitle("  Покупки  ", "молоко").equals("Покупки"), "Explicit title");
    check(
        Note.autoTitle("", "  Первая строка\nВторая").equals("Первая строка"), "First line title");
    check(Note.autoTitle(" ", " ").equals("Новая заметка"), "Empty fallback");
    String emojis = "😀".repeat(80);
    String title = Note.autoTitle("", emojis);
    check(title.codePointCount(0, title.length()) == 53, "Unicode title is not split");
    List<Note> notes =
        Arrays.asList(
            note(1, "Работа", "Отчёт", 0),
            note(2, "Блины", "мука сахар молоко", 0),
            note(3, "Поездка", "Билеты", 0));
    check(Retrieval.select(notes, "Какие есть рецепты?", 0).get(0).id == 2, "Recipe synonyms");
    check(Retrieval.select(notes, "Где билеты?", 0).get(0).id == 3, "Russian suffix matching");
    List<Note> dated =
        Arrays.asList(
            note(1, "Прошлое", "", 100), note(2, "Завтра", "", 500), note(3, "Скоро", "", 300));
    check(
        Retrieval.select(dated, "Ближайшее напоминание", 200).get(0).id == 3,
        "Nearest future reminder");
    check(Retrieval.select(Collections.emptyList(), "рецепт", 0).isEmpty(), "Empty archive");
    List<Note> many = new ArrayList<>();
    for (int i = 1; i < 50; i++) many.add(note(i, "Текст", "a".repeat(4000), 0));
    check(Retrieval.select(many, "текст", 0).size() == 8, "Selection cap");
    int total = 0;
    for (Note n : Retrieval.select(many, "текст", 0))
      total += Retrieval.excerpt(n.body, "текст", 1500).length();
    check(total <= 12000, "Context cap");
    String text = "x".repeat(4000) + "ингредиенты: мука и сахар" + "x".repeat(4000);
    String excerpt = Retrieval.excerpt(text, "рецепт", 1500);
    check(excerpt.contains("мука"), "Relevant passage from long note");
    check(excerpt.length() <= 1500, "Excerpt bounded");
    check(Retrieval.excerpt("коротко", "", 1500).equals("коротко"), "Short content intact");
    List<Note> events = new ArrayList<>();
    for (int i = 1; i <= 20; i++) events.add(note(i, "Покупки", "молоко", 300 + i));
    events.add(note(21, "День рождения мамы", "Подарок", 1000));
    check(
        Retrieval.select(events, "Какой ближайший праздник?", 200).get(0).id == 21,
        "Unrelated reminders do not hide holidays");
    String unicodeBody = "😀".repeat(400) + "рецепт" + "😀".repeat(2000);
    String unicodeExcerpt = Retrieval.excerpt(unicodeBody, "рецепт", 1500);
    check(unicodeExcerpt.length() <= 1500, "Unicode excerpt respects budget");
    check(
        !Character.isLowSurrogate(unicodeExcerpt.charAt(1)),
        "Unicode excerpt starts at code point");
    byte[] header = {'G', 'G', 'U', 'F', 3, 0, 0, 0};
    try {
      GgufHeader.validate(header, 8);
      check(true, "GGUF v3 header accepted");
      header[4] = 2;
      GgufHeader.validate(header, 8);
      check(true, "GGUF v2 header accepted");
    } catch (java.io.IOException e) {
      throw new AssertionError(e);
    }
    for (byte[] invalid :
        new byte[][] {
          {'G', 'G', 'U', 'F', 1, 0, 0, 0},
          {'Z', 'I', 'P', 0, 3, 0, 0, 0},
          {'G', 'G', 'U', 'F', 3, 1, 0, 0},
          {'G', 'G'}
        }) {
      try {
        GgufHeader.validate(invalid, invalid.length);
        throw new AssertionError("Invalid header accepted");
      } catch (java.io.IOException expected) {
        check(true, "Invalid GGUF header rejected");
      }
    }
    Note tagged = note(42, "Блины", "Мука и молоко", 0);
    tagged.searchText = "панкейки завтрак";
    check(
        Retrieval.select(Arrays.asList(tagged, note(43, "Покупки", "чай", 0)), "панкейки", 0)
                .get(0)
                .id
            == 42,
        "Hidden aliases affect ranking");
    check(SearchQuery.build("Рецепты").contains("реце"), "Search query prefix generated");
    check(
        !SearchQuery.build("\" OR 1=1; DROP TABLE notes --").contains("\"")
            && !SearchQuery.build("\" OR 1=1; DROP TABLE notes --").contains(";"),
        "FTS query grammar cannot be injected");
    check(SearchQuery.build("!!!").isEmpty(), "Punctuation is not executable FTS syntax");
    System.out.println("PASS: " + count + " core checks");
  }
}
