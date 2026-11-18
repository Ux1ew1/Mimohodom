package app.fastnotes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Проверяет правила очистки файлов изображений без Android. */
public final class ImageCleanupTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static final String A = "0b9c4f3e-7a1d-4e2b-9c55-1a2b3c4d5e6f.jpg",
      B = "11111111-2222-3333-4444-555555555555.jpg",
      C = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.jpg";

  public static void main(String[] args) {
    long now = 10_000_000L, grace = 600_000L;

    check(ImageCleanup.managed(A), "uuid jpg is ours");
    check(!ImageCleanup.managed("notes.txt"), "foreign file is not ours");
    check(!ImageCleanup.managed("image.jpg"), "plain jpg is not ours");
    check(!ImageCleanup.managed("../" + A), "path traversal is not ours");
    check(!ImageCleanup.managed(A.toUpperCase()), "uppercase is not ours");
    check(!ImageCleanup.managed(null), "null is not ours");
    check(!ImageCleanup.managed(""), "empty is not ours");

    Map<String, Long> files = new HashMap<>();
    files.put(A, now - 2 * grace);
    files.put(B, now - 2 * grace);
    files.put(C, now - 2 * grace);
    Set<String> used = new HashSet<>();
    used.add(A);
    List<String> orphans = ImageCleanup.orphans(files, used, now, grace);
    check(orphans.equals(List.of(B, C)), "unreferenced old files are orphans");

    used.add(B);
    check(ImageCleanup.orphans(files, used, now, grace).equals(List.of(C)), "referenced stays");
    used.add(C);
    check(ImageCleanup.orphans(files, used, now, grace).isEmpty(), "nothing to remove");

    files.put(B, now - grace / 2);
    check(
        ImageCleanup.orphans(files, new HashSet<>(), now, grace).equals(List.of(A, C)),
        "a fresh file is protected by the grace period");
    files.put(B, now - grace);
    check(
        ImageCleanup.orphans(files, new HashSet<>(), now, grace).contains(B),
        "a file exactly as old as the grace period can go");
    check(
        ImageCleanup.orphans(files, new HashSet<>(), now, 0).size() == 3,
        "zero grace removes every orphan");

    files.put("readme.txt", 0L);
    files.put("123.jpg", 0L);
    check(
        !ImageCleanup.orphans(files, new HashSet<>(), now, 0).contains("readme.txt")
            && !ImageCleanup.orphans(files, new HashSet<>(), now, 0).contains("123.jpg"),
        "foreign files are never removed");
    check(ImageCleanup.orphans(new HashMap<>(), used, now, grace).isEmpty(), "empty folder");

    check(ImageCleanup.size(0).equals("0 КБ"), "size zero");
    check(ImageCleanup.size(512 * 1024).equals("512 КБ"), "size in kilobytes");
    check(ImageCleanup.size(1024 * 1024).equals("1,0 МБ"), "size one megabyte");
    check(ImageCleanup.size(8_800_000).equals("8,4 МБ"), "size uses a decimal comma");
    check(ImageCleanup.files(1).equals("1 файл"), "files 1");
    check(ImageCleanup.files(3).equals("3 файла"), "files 3");
    check(ImageCleanup.files(5).equals("5 файлов"), "files 5");
    check(ImageCleanup.files(12).equals("12 файлов"), "files 12");
    check(ImageCleanup.files(21).equals("21 файл"), "files 21");

    System.out.println("ImageCleanupTest: " + count + " checks passed");
  }
}
