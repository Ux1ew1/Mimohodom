package app.fastnotes;

public final class FolderTest {
  private static int checks;

  private static void check(boolean ok, String name) {
    if (!ok) throw new AssertionError(name);
    checks++;
  }

  public static void main(String[] args) {
    check(FolderPolicy.clean("  Видео   про  Android  ").equals("Видео про Android"), "Spaces collapse");
    check(FolderPolicy.clean(null).isEmpty() && FolderPolicy.clean(" \n\t ").isEmpty(), "Blank names are empty");
    check(FolderPolicy.clean("Идеи\nи планы").equals("Идеи и планы"), "Line breaks become spaces");
    String long50 = "я".repeat(50);
    check(FolderPolicy.clean(long50).codePointCount(0, FolderPolicy.clean(long50).length()) == FolderPolicy.MAX_NAME, "Long names are cut");
    check(FolderPolicy.key("Работа").equals(FolderPolicy.key("  рАБОТА ")), "Cyrillic case-insensitive key");
    check(!FolderPolicy.key("Работа").equals(FolderPolicy.key("Работа 2")), "Different names differ");
    check(FolderPolicy.reserved("Все заметки") && FolderPolicy.reserved("  все  ЗАМЕТКИ"), "All notes name reserved");
    check(!FolderPolicy.valid("") && !FolderPolicy.valid("Все заметки") && FolderPolicy.valid("Рецепты"), "Validity");
    check(FolderPolicy.ALL == 0, "All notes is zero");
    System.out.println("PASS: " + checks + " folder policy checks");
  }
}
