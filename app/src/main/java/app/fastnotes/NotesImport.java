package app.fastnotes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Разбор резервной JSON-копии без Android. Записи из файла не являются инструкциями. */
public final class NotesImport {
  public static final int MAX_VERSION = 4;
  public static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
  public static final int MAX_NOTES = 10_000;
  public static final int MAX_FOLDERS = 1_000;

  private NotesImport() {}

  public static final class Entry {
    public long sourceId;
    public String title = "", body = "";
    public long created, updated, reminder;
    public int repeat, lead = -1;
    public String reminderTimes = "", plan = "";
    public long folder;
    public JSONObject metadata = new JSONObject();
  }

  public static final class Folder {
    public long sourceId;
    public String name = "";
    public boolean auto;
  }

  public static final class Group {
    public long top;
    public boolean expanded;
    public final List<Long> members = new ArrayList<>();
  }

  public static final class Backup {
    public final List<Entry> notes = new ArrayList<>();
    public final List<Folder> folders = new ArrayList<>();
    public final List<Group> groups = new ArrayList<>();

    public int reminders() {
      int count = 0;
      for (Entry note : notes) if (note.reminder > 0) count++;
      return count;
    }

    public int plans() {
      int count = 0;
      for (Entry note : notes) if (!note.plan.isEmpty()) count++;
      return count;
    }
  }

  public static final class Result {
    public int added, duplicates, foldersAdded, groupsAdded;
    public final List<Long> noteIds = new ArrayList<>();

    public String message() {
      String text = added == 0 ? "Новых заметок нет" : "Импортировано заметок: " + added;
      if (duplicates > 0) text += ". Совпадения пропущены: " + duplicates;
      return text;
    }
  }

  /** Совместимость с экспортом версий 1–4: новые поля необязательны, более новый формат отклоняется. */
  public static Backup parse(String raw) throws JSONException {
    JSONObject root;
    try {
      root = new JSONObject(raw);
    } catch (JSONException e) {
      throw new JSONException("Это не JSON-резервная копия Мимоходом");
    }
    long version = number(root, "version", 1);
    if (version < 1 || version > MAX_VERSION)
      throw new JSONException("Неподдерживаемая версия резервной копии: " + version);
    JSONArray notes = root.getJSONArray("notes");
    if (notes.length() == 0) throw new JSONException("В файле нет заметок");
    if (notes.length() > MAX_NOTES) throw new JSONException("В файле больше 10000 заметок");

    Backup out = new Backup();
    Set<Long> folderIds = new HashSet<>();
    JSONArray folders = root.optJSONArray("folders");
    if (folders != null && folders.length() > MAX_FOLDERS)
      throw new JSONException("В файле больше 1000 папок");
    for (int i = 0; folders != null && i < folders.length(); i++) {
      JSONObject row = folders.getJSONObject(i);
      long id = number(row, "id", 0);
      if (id <= 0 || !folderIds.add(id)) throw new JSONException("Некорректный номер папки");
      String name = FolderPolicy.clean(row.optString("name", ""));
      if (!FolderPolicy.valid(name)) continue;
      Folder folder = new Folder();
      folder.sourceId = id;
      folder.name = name;
      folder.auto = bool(row, "auto");
      out.folders.add(folder);
    }

    Set<Long> noteIds = new HashSet<>();
    for (int i = 0; i < notes.length(); i++) {
      JSONObject row = notes.getJSONObject(i);
      Entry entry = new Entry();
      entry.sourceId = number(row, "id", 0);
      if (entry.sourceId <= 0 || !noteIds.add(entry.sourceId))
        throw new JSONException("Некорректный или повторный номер заметки");
      entry.body = row.optString("body", "").trim();
      entry.title = Note.autoTitle(row.optString("title", ""), entry.body);
      entry.created = Math.max(0, number(row, "created", 0));
      entry.updated = Math.max(0, number(row, "updated", entry.created));
      entry.reminder = Math.max(0, number(row, "reminder", 0));
      long repeat = number(row, "repeat", 0);
      entry.repeat = entry.reminder > 0 && repeat >= 0 && repeat <= ReminderPolicy.REPEAT_LIST
          ? (int) repeat : 0;
      long lead = number(row, "lead", -1);
      entry.lead = entry.reminder > 0 && lead >= -1 && lead <= Integer.MAX_VALUE ? (int) lead : -1;
      if (entry.repeat == ReminderPolicy.REPEAT_LIST) {
        entry.reminderTimes = ReminderPolicy.encodeTimes(
            ReminderPolicy.parseTimes(row.optString("reminderTimes", "")));
        if (entry.reminderTimes.isEmpty())
          entry.reminderTimes = ReminderPolicy.encodeTimes(new long[] {entry.reminder});
      }
      entry.folder = Math.max(0, number(row, "folder", 0));
      entry.plan = PlanTree.parse(row.optString("plan", "")).toJson();
      entry.metadata = Metadata.base(entry.title, entry.body, entry.created, entry.updated);
      JSONObject exported = row.optJSONObject("metadata");
      if (exported != null)
        entry.metadata = Metadata.withAI(entry.metadata, exported.optJSONObject("ai"), entry.updated);
      out.notes.add(entry);
    }

    Map<Long, Group> groups = new HashMap<>();
    JSONArray groupRows = root.optJSONArray("groups");
    for (int i = 0; groupRows != null && i < groupRows.length(); i++) {
      JSONObject row = groupRows.getJSONObject(i);
      long top = number(row, "top", 0);
      if (!noteIds.contains(top) || groups.containsKey(top)) continue;
      Group group = new Group();
      group.top = top;
      group.expanded = bool(row, "expanded");
      groups.put(top, group);
    }
    Set<Long> claimed = new HashSet<>();
    JSONArray members = root.optJSONArray("members");
    for (int i = 0; members != null && i < members.length(); i++) {
      JSONObject row = members.getJSONObject(i);
      long note = number(row, "note", 0), top = number(row, "top", 0);
      Group group = groups.get(top);
      if (group == null || note == top || !noteIds.contains(note) || !claimed.add(note)) continue;
      group.members.add(note);
    }
    for (Group group : groups.values()) if (!group.members.isEmpty()) out.groups.add(group);
    out.groups.sort((a, b) -> Long.compare(a.top, b.top));
    return out;
  }

  /** Снимок существующей заметки для сравнения с записью из файла. */
  public static Entry existing(Note note, String plan) {
    Entry out = new Entry();
    out.sourceId = note.id;
    out.title = note.title;
    out.body = note.body;
    out.created = note.created;
    out.updated = note.updated;
    out.reminder = note.reminder;
    out.repeat = note.repeat;
    out.lead = note.lead;
    out.reminderTimes = note.reminderTimes;
    out.plan = PlanTree.parse(plan).toJson();
    out.folder = note.folder;
    return out;
  }

  /** Метаданные ИИ не входят в отпечаток: их обновление не меняет пользовательское содержимое. */
  public static String fingerprint(Entry entry, String folderName) {
    try {
      JSONObject value = new JSONObject()
          .put("title", entry.title)
          .put("body", entry.body)
          .put("created", entry.created)
          .put("updated", entry.updated)
          .put("reminder", entry.reminder)
          .put("repeat", entry.repeat)
          .put("lead", entry.lead)
          .put("times", ReminderPolicy.encodeTimes(ReminderPolicy.parseTimes(entry.reminderTimes)))
          .put("folder", FolderPolicy.key(folderName))
          .put("plan", entry.plan);
      byte[] hash = MessageDigest.getInstance("SHA-256")
          .digest(canonical(value).getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (byte b : hash) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
      return out.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Сортировка ключей делает отпечаток независимым от порядка полей JSON. */
  static String canonical(JSONObject value) {
    return canonicalObject(value).toString();
  }

  private static Object canonicalObject(Object value) {
    if (value instanceof JSONObject) {
      JSONObject source = (JSONObject) value, out = new JSONObject();
      Map<String, Object> sorted = new TreeMap<>();
      Iterator<String> keys = source.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        sorted.put(key, canonicalObject(source.opt(key)));
      }
      for (Map.Entry<String, Object> row : sorted.entrySet()) {
        try {
          out.put(row.getKey(), row.getValue());
        } catch (JSONException e) {
          throw new IllegalStateException(e);
        }
      }
      return out;
    }
    if (value instanceof JSONArray) {
      JSONArray source = (JSONArray) value, out = new JSONArray();
      for (int i = 0; i < source.length(); i++) out.put(canonicalObject(source.opt(i)));
      return out;
    }
    return value == null ? JSONObject.NULL : value;
  }

  static long number(JSONObject row, String key, long fallback) throws JSONException {
    Object value = row.opt(key);
    if (value == null || value == JSONObject.NULL) return fallback;
    if (value instanceof Number) return ((Number) value).longValue();
    if (value instanceof String) {
      try {
        return Long.parseLong(((String) value).trim());
      } catch (NumberFormatException e) {
        throw new JSONException("Поле " + key + " должно быть числом");
      }
    }
    throw new JSONException("Поле " + key + " должно быть числом");
  }

  static boolean bool(JSONObject row, String key) throws JSONException {
    Object value = row.opt(key);
    if (value == null || value == JSONObject.NULL) return false;
    if (value instanceof Boolean) return (Boolean) value;
    return number(row, key, 0) != 0;
  }
}
