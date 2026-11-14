package app.fastnotes;

import java.util.List;

/** Проверяет список картинок заметки без Android. */
public final class ImageListTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  public static void main(String[] args) {
    check(ImageList.parse("").isEmpty(), "empty");
    check(ImageList.parse(null).isEmpty(), "null");
    check(ImageList.parse("a.jpg").equals(List.of("a.jpg")), "legacy single name");
    check(ImageList.parse(" a.jpg , b.jpg ,, ").equals(List.of("a.jpg", "b.jpg")), "trims and skips blanks");
    check(ImageList.parse("a.jpg,b.jpg,a.jpg").equals(List.of("a.jpg", "b.jpg")), "drops duplicates");
    check(ImageList.join(List.of("a.jpg", "b.jpg")).equals("a.jpg,b.jpg"), "join");
    check(ImageList.join(List.of()).isEmpty(), "join empty");
    check(ImageList.count("a.jpg,b.jpg,c.jpg") == 3, "count");
    check(ImageList.count("") == 0, "count empty");
    check(ImageList.cover("a.jpg,b.jpg").equals("a.jpg"), "cover is first");
    check(ImageList.cover("").isEmpty(), "no cover");
    check(ImageList.cover("a.jpg").equals("a.jpg"), "legacy cover");

    check(ImageList.add("", "a.jpg").equals("a.jpg"), "add to empty");
    check(ImageList.add("a.jpg", "b.jpg").equals("a.jpg,b.jpg"), "add appends");
    check(ImageList.add("a.jpg,b.jpg", "a.jpg").equals("a.jpg,b.jpg"), "add ignores duplicate");
    check(ImageList.add("a.jpg", "").equals("a.jpg"), "add ignores blank");
    check(ImageList.add("a.jpg", null).equals("a.jpg"), "add ignores null");
    String eight = "1,2,3,4,5,6,7,8";
    check(ImageList.full(eight) && !ImageList.full("1,2,3"), "full at the limit");
    check(ImageList.add(eight, "9").equals(eight), "add stops at the limit");
    check(ImageList.add("1,2,3,4,5,6,7", "8").equals(eight), "eighth image is allowed");

    check(ImageList.remove("a.jpg,b.jpg,c.jpg", "b.jpg").equals("a.jpg,c.jpg"), "remove middle");
    check(ImageList.remove("a.jpg", "a.jpg").isEmpty(), "remove last leaves empty");
    check(ImageList.remove("a.jpg", "z.jpg").equals("a.jpg"), "remove unknown");

    check(ImageList.replace("a.jpg,b.jpg,c.jpg", "b.jpg", "x.jpg").equals("a.jpg,x.jpg,c.jpg"), "replace keeps position");
    check(ImageList.replace("a.jpg", "a.jpg", "x.jpg").equals("x.jpg"), "replace the only image");
    check(ImageList.replace("a.jpg,b.jpg", "z.jpg", "x.jpg").equals("a.jpg,b.jpg"), "replace unknown");
    check(ImageList.replace("a.jpg,b.jpg", "a.jpg", "").equals("a.jpg,b.jpg"), "replace with blank");
    check(ImageList.replace("a.jpg,b.jpg", "a.jpg", "b.jpg").equals("b.jpg"), "replace with an existing name collapses");

    check(ImageList.move("a.jpg,b.jpg,c.jpg", "c.jpg", -1).equals("a.jpg,c.jpg,b.jpg"), "move left");
    check(ImageList.move("a.jpg,b.jpg,c.jpg", "a.jpg", 1).equals("b.jpg,a.jpg,c.jpg"), "move right");
    check(ImageList.move("a.jpg,b.jpg,c.jpg", "a.jpg", -1).equals("a.jpg,b.jpg,c.jpg"), "move past the start");
    check(ImageList.move("a.jpg,b.jpg,c.jpg", "c.jpg", 5).equals("a.jpg,b.jpg,c.jpg"), "move past the end");
    check(ImageList.move("a.jpg,b.jpg,c.jpg", "z.jpg", 1).equals("a.jpg,b.jpg,c.jpg"), "move unknown");
    check(ImageList.move("a.jpg,b.jpg,c.jpg", "c.jpg", -2).equals("c.jpg,a.jpg,b.jpg"), "move to the cover");

    check(ImageList.removed("a.jpg,b.jpg", "a.jpg").equals(List.of("b.jpg")), "removed one");
    check(ImageList.removed("a.jpg", "a.jpg,b.jpg").isEmpty(), "added is not removed");
    check(ImageList.removed("a.jpg,b.jpg", "").equals(List.of("a.jpg", "b.jpg")), "all removed");
    check(ImageList.removed("", "a.jpg").isEmpty(), "nothing before");
    check(ImageList.removed("a.jpg,b.jpg", "b.jpg,a.jpg").isEmpty(), "reorder removes nothing");

    System.out.println("ImageListTest: " + count + " checks passed");
  }
}
