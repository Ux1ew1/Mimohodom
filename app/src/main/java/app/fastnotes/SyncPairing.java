package app.fastnotes;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Разбор кода сопряжения {@code mimohodom://pair?v=1&id=…&n=…&s=…&h=…} (docs/SYNC.md). */
public final class SyncPairing {
  public static final class Code {
    public String device, name;
    public byte[] secret;
    public List<String> hosts = new ArrayList<>();
  }

  private SyncPairing() {}

  /** Выбрасывает {@link IllegalArgumentException} с понятным текстом, если код не подходит. */
  public static Code parse(String text) {
    String t = text == null ? "" : text.trim();
    String prefix = "mimohodom://pair?";
    int at = t.indexOf(prefix);
    if (at < 0) throw new IllegalArgumentException("Это не код сопряжения «Мимоходом»");
    String query = t.substring(at + prefix.length()).split("\\s", 2)[0];
    String v = "", id = "", n = "", s = "", h = "";
    for (String part : query.split("&")) {
      int eq = part.indexOf('=');
      if (eq < 0) continue;
      String key = part.substring(0, eq), value = part.substring(eq + 1);
      switch (key) {
        case "v" -> v = value;
        case "id" -> id = value;
        case "n" -> n = decode(value);
        case "s" -> s = value;
        case "h" -> h = value;
        default -> {}
      }
    }
    if (!v.equals("1"))
      throw new IllegalArgumentException(
          "Версия кода сопряжения не поддерживается: обновите приложение");
    byte[] secret;
    try {
      secret = Base64.getUrlDecoder().decode(s);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Код сопряжения повреждён");
    }
    if (id.length() != 32 || secret.length < 16)
      throw new IllegalArgumentException("Код сопряжения повреждён");
    Code code = new Code();
    code.device = id;
    code.name = n.isEmpty() ? "Компьютер" : n;
    code.secret = secret;
    for (String host : h.split(",")) if (!host.trim().isEmpty()) code.hosts.add(host.trim());
    return code;
  }

  private static String decode(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
    } catch (Exception e) {
      return value;
    }
  }
}
