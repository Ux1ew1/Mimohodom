package app.fastnotes;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Чтение и подготовка данных для проверки: заметки, чаты, настройки, SQL только на чтение. */
final class AgentData {
  private AgentData() {}

  /** Метка заметок, созданных мостом: по ней {@code notes.unseed} удаляет только их. */
  static final String SEED = "[agent] ";

  private static Store store;

  static synchronized Store store(Context context) {
    if (store == null) store = new Store(context.getApplicationContext());
    return store;
  }

  static JSONObject noteJson(Note n, int bodyLimit) throws Exception {
    JSONObject o = new JSONObject();
    o.put("id", n.id);
    o.put("title", n.title);
    String body = n.body == null ? "" : n.body;
    o.put("body", body.length() > bodyLimit ? body.substring(0, bodyLimit) + "…" : body);
    o.put("bodyLength", body.length());
    o.put("created", n.created);
    o.put("updated", n.updated);
    o.put("reminder", n.reminder);
    o.put("repeat", n.repeat);
    o.put("lead", n.lead);
    o.put("folder", n.folder);
    o.put("image", n.image);
    o.put("autoTitle", n.autoTitle);
    o.put("suggestion", n.suggestion);
    return o;
  }

  static String noteLine(Note n) {
    String body = (n.body == null ? "" : n.body).replace('\n', ' ');
    if (body.length() > 80) body = body.substring(0, 80) + "…";
    return "#" + n.id + " «" + n.title + "»"
        + (n.folder > 0 ? " папка=" + n.folder : "")
        + (n.reminder > 0 ? " напоминание=" + new java.util.Date(n.reminder) : "")
        + (n.image == null || n.image.isEmpty() ? "" : " картинки=" + n.image)
        + " — " + body;
  }

  static JSONArray notesJson(List<Note> notes, int limit, int bodyLimit) throws Exception {
    JSONArray array = new JSONArray();
    for (int i = 0; i < notes.size() && i < limit; i++) array.put(noteJson(notes.get(i), bodyLimit));
    return array;
  }

  // ===== Настройки =====

  static boolean sensitive(String key) {
    String lower = key.toLowerCase(Locale.ROOT);
    return lower.contains("key") || lower.contains("token") || lower.contains("secret") || lower.contains("password");
  }

  static JSONObject prefsJson(Context context, String only) throws Exception {
    SharedPreferences prefs = context.getSharedPreferences("settings", 0);
    JSONObject out = new JSONObject();
    for (Map.Entry<String, ?> e : new java.util.TreeMap<>(prefs.getAll()).entrySet()) {
      if (only != null && !e.getKey().contains(only)) continue;
      out.put(e.getKey(), sensitive(e.getKey()) ? "***" : String.valueOf(e.getValue()));
    }
    return out;
  }

  static void setPref(Context context, String key, String value, String type) {
    if (sensitive(key)) throw new IllegalArgumentException("ключи и токены через мост не меняются: " + key);
    SharedPreferences.Editor edit = context.getSharedPreferences("settings", 0).edit();
    switch (type) {
      case "bool" -> edit.putBoolean(key, Boolean.parseBoolean(value));
      case "int" -> edit.putInt(key, Integer.parseInt(value));
      case "long" -> edit.putLong(key, Long.parseLong(value));
      case "float" -> edit.putFloat(key, Float.parseFloat(value));
      case "string" -> edit.putString(key, value);
      default -> throw new IllegalArgumentException("--type: string, bool, int, long или float");
    }
    edit.commit();
  }

  // ===== SQL только на чтение =====

  static JSONObject query(Context context, String sql, int limit) throws Exception {
    String trimmed = sql.trim();
    String lower = trimmed.toLowerCase(Locale.ROOT);
    if (!(lower.startsWith("select") || lower.startsWith("with") || lower.startsWith("pragma table_info")))
      throw new IllegalArgumentException("разрешены только SELECT, WITH и PRAGMA table_info");
    if (trimmed.endsWith(";")) trimmed = trimmed.substring(0, trimmed.length() - 1);
    if (trimmed.contains(";")) throw new IllegalArgumentException("одна команда за раз, без «;» внутри");
    store(context).getReadableDatabase(); // база создана и мигрирована
    String path = context.getDatabasePath("fastnotes.db").getPath();
    JSONObject out = new JSONObject();
    JSONArray rows = new JSONArray(), columns = new JSONArray();
    // Отдельное соединение только на чтение: запись запрещена самой базой.
    try (SQLiteDatabase db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY);
        Cursor c = db.rawQuery(trimmed, null)) {
      for (String name : c.getColumnNames()) columns.put(name);
      int count = 0;
      while (c.moveToNext()) {
        if (count++ >= limit) {
          out.put("truncated", true);
          break;
        }
        JSONObject row = new JSONObject();
        for (int i = 0; i < c.getColumnCount(); i++) {
          String name = c.getColumnName(i);
          switch (c.getType(i)) {
            case Cursor.FIELD_TYPE_NULL -> row.put(name, JSONObject.NULL);
            case Cursor.FIELD_TYPE_INTEGER -> row.put(name, c.getLong(i));
            case Cursor.FIELD_TYPE_FLOAT -> row.put(name, c.getDouble(i));
            case Cursor.FIELD_TYPE_BLOB -> row.put(name, "<blob " + c.getBlob(i).length + " байт>");
            default -> row.put(name, c.getString(i));
          }
        }
        rows.put(row);
      }
    }
    out.put("columns", columns);
    out.put("rows", rows);
    return out;
  }
}
