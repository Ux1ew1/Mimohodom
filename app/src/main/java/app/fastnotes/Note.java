package app.fastnotes;

public final class Note {
  public long id, created, updated, reminder;
  public String title, body;
  public String image = "";
  public String metadata = "{}", searchText = "";
  public boolean autoTitle;
  public long suggestion;
  public String suggestionReason = "";

  public Note(long id, String title, String body, long created, long updated, long reminder) {
    this.id = id;
    this.title = title;
    this.body = body;
    this.created = created;
    this.updated = updated;
    this.reminder = reminder;
  }

  public static String autoTitle(String title, String body) {
    if (!title.trim().isEmpty()) return title.trim();
    String line = body.trim().split("\\R", 2)[0].trim();
    if (line.isEmpty()) return "Новая заметка";
    int end = line.offsetByCodePoints(0, Math.min(52, line.codePointCount(0, line.length())));
    return line.substring(0, end) + (end < line.length() ? "…" : "");
  }
}
