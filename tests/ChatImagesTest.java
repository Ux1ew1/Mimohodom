package app.fastnotes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Проверяет память чата о картинках без Android. */
public final class ChatImagesTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  public static void main(String[] args) {
    String one = ChatImages.attach("Что на фото?", List.of("a.jpg"));
    check(one.equals("Что на фото?\n[[img:a.jpg]]"), "attach one");
    check(ChatImages.attach("Текст", List.of()).equals("Текст"), "attach none keeps text");
    check(
        ChatImages.attach("Текст", List.of("a.jpg", "a.jpg", " b.jpg "))
            .equals("Текст\n[[img:a.jpg,b.jpg]]"),
        "attach dedups and trims");

    ChatImages.Split split = ChatImages.split(one);
    check(split.text.equals("Что на фото?"), "split text");
    check(split.images.equals(List.of("a.jpg")), "split images");
    check(ChatImages.split("Просто текст").images.isEmpty(), "plain text has no images");
    check(ChatImages.split("Просто текст").text.equals("Просто текст"), "plain text kept");
    check(ChatImages.split("[[img:a.jpg,b.jpg]]").text.isEmpty(), "images only");
    check(
        ChatImages.split("[[img:a.jpg]]").images.equals(List.of("a.jpg")),
        "marker without text");
    // Служебный блок с чужими символами не принимается за картинки: имена не выходят из папки.
    check(ChatImages.split("[[img:../x.jpg]]").images.isEmpty(), "path-like names rejected");
    check(
        ChatImages.split("a [[memo]]x[[/memo]]\n[[img:c.jpg]]").images.equals(List.of("c.jpg")),
        "marker after other blocks");

    List<String> chat =
        List.of(
            ChatImages.attach("первое", List.of("a.jpg", "b.jpg")),
            "ответ",
            "вопрос без картинок",
            ChatImages.attach("ещё", List.of("b.jpg", "c.jpg")));
    check(ChatImages.all(chat).equals(List.of("a.jpg", "b.jpg", "c.jpg")), "all keeps order, no repeats");

    Map<String, Long> sizes = new LinkedHashMap<>();
    sizes.put("a.jpg", 1_000_000L);
    sizes.put("b.jpg", 1_000_000L);
    sizes.put("c.jpg", 1_000_000L);
    sizes.put("d.jpg", 1_000_000L);
    List<String> names = List.of("a.jpg", "b.jpg", "c.jpg", "d.jpg");
    check(
        ChatImages.select(names, sizes, 6, 10_000_000L).equals(names), "everything fits");
    check(
        ChatImages.select(names, sizes, 2, 10_000_000L).equals(List.of("c.jpg", "d.jpg")),
        "newest by count, chronological");
    check(
        ChatImages.select(names, sizes, 6, 2_500_000L).equals(List.of("c.jpg", "d.jpg")),
        "newest by bytes");
    sizes.put("d.jpg", 9_000_000L);
    check(
        ChatImages.select(names, sizes, 6, 5_000_000L).equals(List.of("a.jpg", "b.jpg", "c.jpg")),
        "oversized newest is skipped, older kept");
    sizes.remove("c.jpg");
    check(
        ChatImages.select(List.of("c.jpg"), sizes, 6, 5_000_000L).isEmpty(),
        "missing file is skipped");
    check(ChatImages.select(List.of(), sizes, 6, 5_000_000L).isEmpty(), "nothing to select");

    List<String> all = ChatImages.all(chat);
    String model = ChatImages.forModel(chat.get(3), all, Set.of("b.jpg", "c.jpg"));
    check(model.equals("ещё\n[Приложено: изображения №2, №3]"), "model text numbered: " + model);
    String partial = ChatImages.forModel(chat.get(0), all, Set.of("b.jpg"));
    check(partial.contains("№1, №2") && partial.contains("не показана"), "hidden noted: " + partial);
    check(ChatImages.forModel("обычный", all, Set.of()).equals("обычный"), "plain text unchanged");
    check(
        ChatImages.forModel(ChatImages.attach("", List.of("a.jpg")), all, Set.of("a.jpg"))
            .equals("[Приложено: изображение №1]"),
        "single image wording");
    check(
        ChatImages.forModel(ChatImages.attach("", List.of("a.jpg")), all, Set.of("a.jpg"))
            .equals("[Приложено: изображение №1]"),
        "images only message");

    String guidance = ChatImages.guidance(3, 5);
    check(guidance.contains("3 из 5") && guidance.contains("№1"), "guidance counts");
    System.out.println("ChatImagesTest: " + count + " checks passed");
  }
}
