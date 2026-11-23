package app.fastnotes;

/**
 * Мелкий ремонт JSON из ответов моделей перед разбором. Некоторые модели (google/gemma-3-27b-it)
 * пишут числовые ключи без кавычек: {@code {"kinds":{41:"info"}}}. Библиотека org.json на компьютере
 * такое принимает, а в Android — нет («Names must be strings»), и проверка падала целиком. Здесь
 * такие ключи берутся в кавычки; текст внутри строк не трогается. Чистая Java без зависимостей.
 */
public final class JsonRepair {
  private JsonRepair() {}

  /** Берёт в кавычки ключи-числа вне строк: {@code {41:"a", 7 :"b"}} → {@code {"41":"a", "7" :"b"}}. */
  public static String quoteNumericKeys(String json) {
    if (json == null) return "";
    StringBuilder out = new StringBuilder(json.length() + 16);
    boolean inString = false, escaped = false;
    char last = 0; // последний значимый символ вне строк
    int i = 0, n = json.length();
    while (i < n) {
      char c = json.charAt(i);
      if (inString) {
        out.append(c);
        if (escaped) escaped = false;
        else if (c == '\\') escaped = true;
        else if (c == '"') {
          inString = false;
          last = '"';
        }
        i++;
        continue;
      }
      if (c == '"') {
        inString = true;
        out.append(c);
        i++;
        continue;
      }
      if ((last == '{' || last == ',') && (Character.isDigit(c) || c == '-')) {
        int j = i + (c == '-' ? 1 : 0);
        while (j < n && Character.isDigit(json.charAt(j))) j++;
        int k = j;
        while (k < n && Character.isWhitespace(json.charAt(k))) k++;
        if (j > i + (c == '-' ? 1 : 0) && k < n && json.charAt(k) == ':') {
          out.append('"').append(json, i, j).append('"');
          last = '"';
          i = j;
          continue;
        }
      }
      out.append(c);
      if (!Character.isWhitespace(c)) last = c;
      i++;
    }
    return out.toString();
  }
}
