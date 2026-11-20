package app.fastnotes;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Запись обмена: заметка, папка, чат, сообщение, стопка или надгробие (docs/SYNC.md). Без
 * зависимости от Android.
 */
public final class SyncRecord {
  public String kind = "note", uid = "";
  public long ts;
  public boolean deleted;
  // заметка
  public String title = "", body = "", folder = "", metadata = "";
  public long created, updated, reminder;
  public int repeat, lead = -1;
  public List<String> images = new ArrayList<>();
  public boolean titleAuto, aiChecked;
  /** План заметки (JSON PlanTree). null — поля не было в записи: старая версия, свой план не трогаем. */
  public String plan;
  // папка
  public String name = "", key = "";
  public boolean auto;
  // чат (title и created общие с заметкой) и сообщение (body и created общие)
  public boolean checkin;
  public String chat = "", role = "", draft = "";
  public JSONArray sources = new JSONArray();
  // стопка: uid исходных заметок (uid записи — верхняя заметка)
  public List<String> members = new ArrayList<>();

  public static SyncRecord tomb(String kind, String uid, long ts) {
    SyncRecord r = new SyncRecord();
    r.kind = kind;
    r.uid = uid;
    r.ts = ts;
    r.deleted = true;
    return r;
  }

  public boolean isFolder() {
    return "folder".equals(kind);
  }

  public boolean isNote() {
    return "note".equals(kind);
  }

  public boolean isChat() {
    return "chat".equals(kind);
  }

  public boolean isMessage() {
    return "message".equals(kind);
  }

  public boolean isStack() {
    return "stack".equals(kind);
  }

  public JSONObject toJson() {
    try {
      JSONObject o = new JSONObject().put("kind", kind).put("uid", uid).put("ts", ts);
      if (deleted) return o.put("deleted", true);
      o.put("deleted", false);
      if (isFolder())
        return o.put("name", name).put("key", key).put("created", created).put("auto", auto);
      if (isChat()) return o.put("title", title).put("created", created).put("checkin", checkin);
      if (isMessage())
        return o.put("chat", chat)
            .put("role", role)
            .put("body", body)
            .put("sources", sources)
            .put("draft", draft)
            .put("created", created);
      if (isStack()) return o.put("members", new JSONArray(members));
      return o.put("title", title)
          .put("body", body)
          .put("created", created)
          .put("updated", updated)
          .put("reminder", reminder)
          .put("repeat", repeat)
          .put("lead", lead)
          .put("images", new JSONArray(images))
          .put("folder", folder)
          .put("titleAuto", titleAuto)
          .put("aiChecked", aiChecked)
          .put("metadata", metadata)
          .putOpt("plan", plan);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  public static SyncRecord fromJson(JSONObject o) {
    SyncRecord r = new SyncRecord();
    r.kind = o.optString("kind", "note");
    r.uid = o.optString("uid", "");
    r.ts = o.optLong("ts", 0);
    r.deleted = o.optBoolean("deleted", false);
    r.title = o.optString("title", "");
    r.body = o.optString("body", "");
    r.created = o.optLong("created", 0);
    r.updated = o.optLong("updated", 0);
    r.reminder = o.optLong("reminder", 0);
    r.repeat = o.optInt("repeat", 0);
    r.lead = o.optInt("lead", -1);
    JSONArray images = o.optJSONArray("images");
    if (images != null)
      for (int i = 0; i < images.length(); i++) {
        String name = images.optString(i, "");
        if (!name.isEmpty()) r.images.add(name);
      }
    r.folder = o.optString("folder", "");
    r.titleAuto = o.optBoolean("titleAuto", false);
    r.aiChecked = o.optBoolean("aiChecked", false);
    r.metadata = o.optString("metadata", "");
    r.plan = o.has("plan") ? o.optString("plan", "") : null;
    r.name = o.optString("name", "");
    r.key = o.optString("key", "");
    r.auto = o.optBoolean("auto", false);
    r.checkin = o.optBoolean("checkin", false);
    r.chat = o.optString("chat", "");
    r.role = o.optString("role", "");
    r.draft = o.optString("draft", "");
    JSONArray sources = o.optJSONArray("sources");
    if (sources != null) r.sources = sources;
    JSONArray members = o.optJSONArray("members");
    if (members != null)
      for (int i = 0; i < members.length(); i++) {
        String uid = members.optString(i, "");
        if (!uid.isEmpty()) r.members.add(uid);
      }
    return r;
  }

  /**
   * Заменяет в тексте каждую ссылку {@code [[note:X]]} результатом {@code f(X)}. Номера заметок у
   * устройств свои, поэтому на проводе вместо них uid (см. «Чаты и стопки» в docs/SYNC.md).
   */
  public static String mapLinks(String text, java.util.function.Function<String, String> f) {
    StringBuilder out = new StringBuilder(text.length());
    int at = 0;
    while (true) {
      int start = text.indexOf("[[note:", at);
      if (start < 0) break;
      out.append(text, at, start);
      int from = start + 7, end = text.indexOf("]]", from);
      if (end > from && end - from <= 40 && !containsBetween(text, '[', from, end)) {
        out.append("[[note:").append(f.apply(text.substring(from, end))).append("]]");
        at = end + 2;
      } else {
        out.append("[[note:");
        at = from;
      }
    }
    return out.append(text, at, text.length()).toString();
  }

  private static boolean containsBetween(String text, char c, int from, int to) {
    int i = text.indexOf(c, from);
    return i >= 0 && i < to;
  }

  /** Безопасное имя файла картинки: без путей и странных символов. */
  public static boolean safeFileName(String name) {
    if (name == null || name.isEmpty() || name.length() > 80 || name.contains("..")) return false;
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      boolean ok =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '.'
              || c == '_'
              || c == '-';
      if (!ok) return false;
    }
    return true;
  }
}
