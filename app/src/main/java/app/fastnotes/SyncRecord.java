package app.fastnotes;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Запись обмена: заметка, папка или надгробие (docs/SYNC.md). Без зависимости от Android. */
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
  // папка
  public String name = "", key = "";
  public boolean auto;

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

  public JSONObject toJson() {
    try {
      JSONObject o = new JSONObject().put("kind", kind).put("uid", uid).put("ts", ts);
      if (deleted) return o.put("deleted", true);
      o.put("deleted", false);
      if (isFolder())
        return o.put("name", name).put("key", key).put("created", created).put("auto", auto);
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
          .put("metadata", metadata);
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
    r.name = o.optString("name", "");
    r.key = o.optString("key", "");
    r.auto = o.optBoolean("auto", false);
    return r;
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
