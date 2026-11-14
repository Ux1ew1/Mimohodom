package app.fastnotes;

/** Проверяет разбор «Поделиться» и арифметику обрезки без Android. */
public final class ImageInputTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static boolean same(int[] a, int... b) {
    return java.util.Arrays.equals(a, b);
  }

  static boolean near(float[] a, float... b) {
    if (a.length != b.length) return false;
    for (int i = 0; i < a.length; i++) if (Math.abs(a[i] - b[i]) > 1e-5f) return false;
    return true;
  }

  public static void main(String[] args) {
    // Типы содержимого
    check(ShareParser.isImage("image/jpeg"), "jpeg is an image");
    check(ShareParser.isImage("IMAGE/PNG"), "type is case insensitive");
    check(!ShareParser.isImage("text/plain"), "text is not an image");
    check(!ShareParser.isImage(null), "null is not an image");
    check(ShareParser.isText("text/plain"), "plain text");
    check(!ShareParser.isText("application/pdf"), "pdf is not text");

    // Заметка из «Поделиться»
    String[] text = ShareParser.note(null, "  Купить молоко  ", false);
    check(text != null && text[0].isEmpty() && text[1].equals("Купить молоко"), "text only");
    String[] subject = ShareParser.note("  Статья\n про   ИИ ", "https://example.org", false);
    check(
        subject != null && subject[0].equals("Статья про ИИ") && subject[1].equals("https://example.org"),
        "subject becomes the title");
    String[] bare = ShareParser.note(null, null, true);
    check(bare != null && bare[0].equals("Изображение") && bare[1].isEmpty(), "bare image");
    String[] caption = ShareParser.note(null, "Подпись к фото", true);
    check(caption != null && caption[0].isEmpty() && caption[1].equals("Подпись к фото"), "image with text");
    check(ShareParser.note(null, null, false) == null, "nothing to save");
    check(ShareParser.note("   ", "  \n ", false) == null, "blank is nothing to save");
    String[] subjectOnly = ShareParser.note("Только тема", "", false);
    check(subjectOnly != null && subjectOnly[0].equals("Только тема"), "subject only is saved");
    String huge = "я".repeat(ShareParser.MAX_BODY + 500);
    check(ShareParser.note(null, huge, false)[1].length() == ShareParser.MAX_BODY, "long text is capped");
    check(
        ShareParser.note("т".repeat(500), "x", false)[0].length() == ShareParser.MAX_TITLE,
        "long title is capped");

    // Повороты
    check(CropMath.turns(0) == 0 && CropMath.turns(5) == 1 && CropMath.turns(-1) == 3, "turns");
    check(CropMath.turns(-4) == 0 && CropMath.turns(-5) == 3, "negative turns wrap");
    check(same(CropMath.rotatedSize(1600, 900, 0), 1600, 900), "no turn keeps size");
    check(same(CropMath.rotatedSize(1600, 900, 1), 900, 1600), "quarter turn swaps size");
    check(same(CropMath.rotatedSize(1600, 900, 2), 1600, 900), "half turn keeps size");
    check(same(CropMath.rotatedSize(1600, 900, -1), 900, 1600), "negative turn swaps size");

    // Обрезка
    check(near(CropMath.clamp(0.1f, 0.2f, 0.9f, 0.8f), 0.1f, 0.2f, 0.9f, 0.8f), "valid crop kept");
    float[] swapped = CropMath.clamp(0.9f, 0.9f, 0.1f, 0.1f);
    check(swapped[2] > swapped[0] && swapped[3] > swapped[1], "swapped corners become a valid box");
    check(near(CropMath.clamp(-1f, -1f, 2f, 2f), 0f, 0f, 1f, 1f), "out of range is clamped");
    float[] tiny = CropMath.clamp(0.5f, 0.5f, 0.5f, 0.5f);
    check(
        tiny[2] - tiny[0] >= CropMath.MIN_FRACTION - 1e-6f
            && tiny[3] - tiny[1] >= CropMath.MIN_FRACTION - 1e-6f,
        "minimum size is enforced");
    check(CropMath.isFull(null), "null is no crop");
    check(CropMath.isFull(new float[] {0, 0, 1, 1}), "full frame is no crop");
    check(CropMath.isFull(new float[] {0.0005f, 0, 0.9995f, 1}), "almost full is no crop");
    check(!CropMath.isFull(new float[] {0.1f, 0, 1, 1}), "real crop is detected");
    check(same(CropMath.pixels(1000, 500, null), 0, 0, 1000, 500), "no crop covers everything");
    check(same(CropMath.pixels(1000, 500, new float[] {0.1f, 0.2f, 0.6f, 0.8f}), 100, 100, 500, 300), "pixel box");
    int[] edge = CropMath.pixels(1000, 500, new float[] {0.99f, 0.99f, 1f, 1f});
    check(edge[0] + edge[2] <= 1000 && edge[1] + edge[3] <= 500 && edge[2] >= 1 && edge[3] >= 1, "edge box inside image");
    int[] small = CropMath.pixels(10, 10, new float[] {0.5f, 0.5f, 0.5f, 0.5f});
    check(small[2] >= 1 && small[3] >= 1 && small[0] + small[2] <= 10, "tiny image keeps one pixel");
    int[] one = CropMath.pixels(1, 1, new float[] {0f, 0f, 1f, 1f});
    check(same(one, 0, 0, 1, 1), "one pixel image");

    System.out.println("ImageInputTest: " + count + " checks passed");
  }
}
