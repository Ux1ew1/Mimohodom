package app.fastnotes;

import java.util.*;
import org.json.*;

public final class FolderSuggestionTest {
  private static int checks;

  private static void check(boolean ok, String name) {
    if (!ok) throw new AssertionError(name);
    checks++;
  }

  private static Note note(long id, long folder, String... topics) throws Exception {
    Note n = new Note(id, "Заметка " + id, "Текст " + id, 1000, 2000 + id, 0);
    n.folder = folder;
    if (topics.length > 0)
      n.metadata = new JSONObject().put("ai", new JSONObject().put("topics", new JSONArray(Arrays.asList(topics)))).toString();
    return n;
  }

  public static void main(String[] args) throws Exception {
    Map<String, Long> none = new HashMap<>();
    Set<String> noDismissed = new HashSet<>();
    check(FolderSuggestion.display("  стриминг   игр ").equals("Стриминг игр"), "Display name capitalised");
    check(FolderSuggestion.usable("Moonlight") && !FolderSuggestion.usable("ИИ") && !FolderSuggestion.usable("Видео") && !FolderSuggestion.usable("все заметки") && !FolderSuggestion.usable("я".repeat(60)), "Usable topics");

    List<Note> notes = Arrays.asList(
        note(1, 0, "стриминг игр", "Moonlight"),
        note(2, 0, "Стриминг игр", "Tailscale"),
        note(3, 0, "рецепты", "борщ"),
        note(4, 0, "стриминг игр"),
        note(5, 7, "стриминг игр"),
        note(6, 0));
    List<FolderSuggestion.Suggestion> found = FolderSuggestion.suggest(notes, none, noDismissed);
    check(found.size() == 1 && found.get(0).name.equals("Стриминг игр") && found.get(0).existing == 0, "One shared topic, folder to create");
    check(found.get(0).notes.equals(Arrays.asList(1L, 2L, 4L)), "Notes in a folder already are skipped");

    check(FolderSuggestion.suggest(Arrays.asList(note(1, 0, "рецепты"), note(2, 0, "борщ")), none, noDismissed).isEmpty(), "A single note does not make a folder");

    Map<String, Long> existing = new HashMap<>();
    existing.put(FolderPolicy.key("Рецепты"), 9L);
    List<FolderSuggestion.Suggestion> into = FolderSuggestion.suggest(Arrays.asList(note(3, 0, "рецепты")), existing, noDismissed);
    check(into.size() == 1 && into.get(0).existing == 9 && into.get(0).notes.equals(Arrays.asList(3L)), "One note matches an existing folder");

    Set<String> hidden = new HashSet<>(Arrays.asList(FolderPolicy.key("стриминг игр")));
    check(FolderSuggestion.suggest(notes, none, hidden).isEmpty(), "Dismissed topics stay hidden");

    List<Note> overlap = Arrays.asList(
        note(1, 0, "Moonlight", "стриминг"), note(2, 0, "Moonlight", "стриминг"), note(3, 0, "Moonlight"),
        note(4, 0, "стриминг"));
    List<FolderSuggestion.Suggestion> split = FolderSuggestion.suggest(overlap, none, noDismissed);
    Set<Long> seen = new HashSet<>();
    boolean once = true;
    for (FolderSuggestion.Suggestion s : split) for (long id : s.notes) once &= seen.add(id);
    check(once && split.get(0).name.equals("Moonlight"), "Each note appears in one suggestion, biggest topic first");

    List<Note> many = new ArrayList<>();
    for (int t = 0; t < 8; t++) { many.add(note(100 + t * 2, 0, "Тема" + t)); many.add(note(101 + t * 2, 0, "Тема" + t)); }
    check(FolderSuggestion.suggest(many, none, noDismissed).size() == FolderSuggestion.MAX_SUGGESTIONS, "Suggestions are limited");

    FolderSuggestion.Suggestion one = FolderSuggestion.forNote(note(7, 0, "Видео", "iPhone", "стриминг игр"), none, noDismissed);
    check(one != null && one.name.equals("iPhone"), "First usable topic names the folder, brand spelling kept");
    check(FolderSuggestion.forNote(note(8, 3, "стриминг игр"), none, noDismissed) == null, "A note with a folder gets no suggestion");
    check(FolderSuggestion.forNote(note(9, 0), none, noDismissed) == null, "A note without topics gets none");
    check(FolderSuggestion.forNote(note(10, 0, "рецепты"), existing, noDismissed).existing == 9, "Single note points at the existing folder");
    check(FolderSuggestion.forNote(note(11, 0, "рецепты"), none, hidden) != null && FolderSuggestion.forNote(note(12, 0, "стриминг игр"), none, hidden) == null, "Dismissed topic skipped for a single note");
    System.out.println("PASS: " + checks + " folder suggestion checks");
  }
}
