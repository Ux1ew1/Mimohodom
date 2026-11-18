package app.fastnotes;

import java.util.Locale;

/** Разбор содержимого «Поделиться» без зависимости от Android. */
public final class ShareParser {
  public static final int MAX_BODY = 100_000, MAX_TITLE = 200;

  private ShareParser() {}

  public static boolean isImage(String type) {
    return type != null && type.toLowerCase(Locale.ROOT).startsWith("image/");
  }

  public static boolean isText(String type) {
    return type != null && type.toLowerCase(Locale.ROOT).startsWith("text/");
  }

  /**
   * Заголовок и текст новой заметки: {заголовок, текст} или null, если сохранять нечего. Тема
   * письма или страницы становится заголовком; без темы заголовок возьмётся из первой строки, а
   * у картинки без текста будет «Изображение».
   */
  public static String[] note(String subject, String text, boolean hasImage) {
    String title = subject == null ? "" : subject.trim().replaceAll("\\s+", " ");
    if (title.length() > MAX_TITLE) title = title.substring(0, MAX_TITLE).trim();
    String body = text == null ? "" : text.trim();
    if (body.length() > MAX_BODY) body = body.substring(0, MAX_BODY);
    if (body.isEmpty() && title.isEmpty() && !hasImage) return null;
    if (body.isEmpty() && title.isEmpty()) title = "Изображение";
    return new String[] {title, body};
  }
}
