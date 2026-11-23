package app.fastnotes;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.json.*;

/** Сериализация предпросмотра команды в существующий messages.draft, без новой таблицы. */
public final class CommandDraft {
  public static final String KIND = "command";
  private static final DateTimeFormatter SHOWN =
      DateTimeFormatter.ofPattern("dd.MM.yyyy 'в' HH:mm", Locale.forLanguageTag("ru"));

  public static boolean isDraft(JSONObject value) {
    return value != null && KIND.equals(value.optString("kind"))
        && value.optInt("version") == 1;
  }

  public static final class NoteState {
    public final long id, updated, folder, reminder;
    public final String title, times;
    public final int repeat, lead;
    public boolean completed;

    NoteState(Note n) {
      id = n.id;
      updated = n.updated;
      folder = n.folder;
      reminder = n.reminder;
      title = n.title;
      times = n.reminderTimes;
      repeat = n.repeat;
      lead = n.lead;
    }

    NoteState(JSONObject o) {
      id = o.optLong("id", -1);
      updated = o.optLong("updated", -1);
      folder = o.optLong("folder");
      reminder = o.optLong("reminder");
      title = o.optString("title");
      times = o.optString("times");
      repeat = o.optInt("repeat");
      lead = o.optInt("lead", -1);
      completed = o.optBoolean("completed", false);
    }

    JSONObject json() throws JSONException {
      return new JSONObject().put("id", id).put("updated", updated).put("folder", folder)
          .put("reminder", reminder).put("title", title).put("times", times)
          .put("repeat", repeat).put("lead", lead).put("completed", completed);
    }

    public Note note() {
      Note n = new Note(id, title, "", 0, updated, reminder);
      n.folder = folder;
      n.reminderTimes = times;
      n.repeat = repeat;
      n.lead = lead;
      return n;
    }

    TreeSet<Long> schedule() {
      TreeSet<Long> out = new TreeSet<>();
      if (repeat == ReminderPolicy.REPEAT_LIST)
        for (long at : ReminderPolicy.parseTimes(times)) out.add(at);
      else if (reminder > 0) out.add(reminder);
      return out;
    }
  }

  public static final class FolderState {
    public final long id;
    public final String name;

    FolderState(long id, String name) {
      this.id = id;
      this.name = FolderPolicy.clean(name);
    }

    FolderState(JSONObject o) {
      this(o.optLong("id"), o.optString("name"));
    }

    JSONObject json() throws JSONException {
      return new JSONObject().put("id", id).put("name", name);
    }

    public boolean existing() {
      return id > 0;
    }
  }

  public String command = "";
  public long created;
  public ZoneId zone = ZoneId.systemDefault();
  public final List<AgentAction> actions = new ArrayList<>();
  public final Map<Long, NoteState> notes = new LinkedHashMap<>();
  public final List<FolderState> folders = new ArrayList<>();
  public int rejected;

  private CommandDraft() {}

  /** Снимок делается сразу после ответа модели, ещё до показа карточки предпросмотра. */
  public static CommandDraft create(
      String command, List<AgentAction> proposed, List<Note> selected,
      List<CommandAnalysis.FolderInfo> existing, long now, ZoneId zone, int rejected) {
    CommandDraft out = new CommandDraft();
    out.command = command;
    out.created = now;
    out.zone = zone;
    out.rejected = rejected;
    Map<Long, Note> byId = new HashMap<>();
    for (Note n : selected) byId.put(n.id, n);
    Set<Long> changed = new HashSet<>();
    for (AgentAction action : proposed) {
      boolean valid = true;
      if (action.kind == AgentAction.Kind.REMIND) {
        NoteState source = byId.containsKey(action.note) ? new NoteState(byId.get(action.note)) : null;
        if (source == null || (source.repeat != ReminderPolicy.REPEAT_NONE
            && source.repeat != ReminderPolicy.REPEAT_LIST)) valid = false;
        if (source != null) {
          TreeSet<Long> old = source.schedule();
          action.times.removeIf(at -> at <= now || old.contains(at));
          if (action.times.isEmpty() || old.size() + action.times.size() > ReminderPolicy.MAX_TIMES)
            valid = false;
        }
        if (!changed.add(action.note)) valid = false;
      } else if (action.kind == AgentAction.Kind.FOLDER) {
        for (long id : action.notes) if (!changed.add(id)) valid = false;
      } else if (action.kind == AgentAction.Kind.CLEAR_REMINDERS) {
        for (long id : action.notes) {
          NoteState source = byId.containsKey(id) ? new NoteState(byId.get(id)) : null;
          if (source == null || source.schedule().isEmpty() || !changed.add(id)) valid = false;
        }
      }
      for (long id : action.sources())
        if (!byId.containsKey(id)) valid = false;
        else {
          NoteState state = new NoteState(byId.get(id));
          state.completed = action.kind == AgentAction.Kind.CLEAR_REMINDERS;
          out.notes.put(id, state);
        }
      if (!valid) {
        out.rejected++;
        out.notes.keySet().removeIf(id -> !used(proposed, id));
        continue;
      }
      out.actions.add(action);
      String folder = action.kind == AgentAction.Kind.FOLDER ? action.folder
          : action.kind == AgentAction.Kind.CREATE ? action.folder : "";
      if (!folder.isEmpty()) out.addFolder(folder, existing);
    }
    out.notes.keySet().removeIf(id -> !used(out.actions, id));
    return out;
  }

  private static boolean used(List<AgentAction> actions, long id) {
    for (AgentAction action : actions) if (action.sources().contains(id)) return true;
    return false;
  }

  private void addFolder(String name, List<CommandAnalysis.FolderInfo> existing) {
    String key = FolderPolicy.key(name);
    for (FolderState folder : folders)
      if (FolderPolicy.key(folder.name).equals(key)) return;
    long id = 0;
    String clean = FolderPolicy.clean(name);
    for (CommandAnalysis.FolderInfo folder : existing)
      if (FolderPolicy.key(folder.name).equals(key)) {
        id = folder.id;
        clean = folder.name;
        break;
      }
    folders.add(new FolderState(id, clean));
  }

  public FolderState folder(String name) {
    String key = FolderPolicy.key(name);
    for (FolderState folder : folders) if (FolderPolicy.key(folder.name).equals(key)) return folder;
    return null;
  }

  /** Локальные строки предпросмотра: модель не формулирует, что именно приложение изменит. */
  public List<String> preview() {
    List<String> out = new ArrayList<>();
    for (AgentAction action : actions) {
      if (action.kind == AgentAction.Kind.CREATE) {
        String line = "Создать заметку «" + action.title + "»";
        FolderState target = action.folder.isEmpty() ? null : folder(action.folder);
        if (target != null)
          line += " в " + (target.existing() ? "существующую" : "новую")
              + " папку «" + target.name + "»";
        out.add(line);
      } else if (action.kind == AgentAction.Kind.REMIND) {
        NoteState source = notes.get(action.note);
        for (long at : action.times)
          out.add("Добавить заметке «" + (source == null ? "#" + action.note : source.title)
              + "» напоминание " + SHOWN.format(Instant.ofEpochMilli(at).atZone(zone)));
      } else if (action.kind == AgentAction.Kind.FOLDER) {
        FolderState target = folder(action.folder);
        out.add("Переместить " + action.notes.size() + " " + notesWord(action.notes.size())
            + " в " + (target != null && target.existing() ? "существующую" : "новую")
            + " папку «" + action.folder + "»");
      } else if (action.kind == AgentAction.Kind.CLEAR_REMINDERS) {
        for (long id : action.notes) {
          NoteState source = notes.get(id);
          if (source == null) continue;
          for (long at : source.schedule())
            out.add("Удалить у выполненной заметки «" + source.title + "» "
                + (source.repeat != ReminderPolicy.REPEAT_NONE
                    && source.repeat != ReminderPolicy.REPEAT_LIST ? "повторяющееся " : "")
                + "напоминание " + SHOWN.format(Instant.ofEpochMilli(at).atZone(zone)));
        }
      }
    }
    return out;
  }

  /** Проверка, которая повторяется и непосредственно перед записью. */
  public String futureError(long now) {
    if (actions.isEmpty()) return "В черновике нет допустимых действий";
    for (AgentAction action : actions)
      if (action.kind == AgentAction.Kind.REMIND)
        for (long at : action.times)
          if (at <= now) return "Предложенное напоминание уже стало прошедшим";
    return "";
  }

  public JSONObject toJson() throws JSONException {
    JSONArray actions = new JSONArray();
    for (AgentAction action : this.actions) actions.put(action.commandJson());
    JSONArray notes = new JSONArray();
    for (NoteState note : this.notes.values()) notes.put(note.json());
    JSONArray folders = new JSONArray();
    for (FolderState folder : this.folders) folders.put(folder.json());
    return new JSONObject().put("kind", KIND).put("version", 1).put("command", command)
        .put("created", created).put("zone", zone.getId()).put("actions", actions)
        .put("notes", notes).put("folders", folders).put("rejected", rejected);
  }

  public static CommandDraft fromJson(JSONObject root) throws JSONException {
    if (!isDraft(root)) throw new JSONException("Это не черновик команды");
    CommandDraft out = new CommandDraft();
    out.command = root.optString("command");
    out.created = root.optLong("created");
    try {
      out.zone = ZoneId.of(root.optString("zone", ZoneId.systemDefault().getId()));
    } catch (DateTimeException e) {
      throw new JSONException("Неверный часовой пояс черновика");
    }
    out.rejected = root.optInt("rejected");
    AgentAction.Scope scope = new AgentAction.Scope(out.created, out.zone);
    JSONArray notes = root.optJSONArray("notes");
    for (int i = 0; notes != null && i < notes.length(); i++) {
      NoteState note = new NoteState(notes.getJSONObject(i));
      if (note.id <= 0 || note.updated < 0 || out.notes.put(note.id, note) != null)
        throw new JSONException("Повреждён снимок заметки");
      scope.note(note.id, true);
      if (note.completed) scope.completed(note.id);
    }
    JSONArray folders = root.optJSONArray("folders");
    for (int i = 0; folders != null && i < folders.length(); i++)
      out.folders.add(new FolderState(folders.getJSONObject(i)));
    JSONArray actions = root.optJSONArray("actions");
    if (actions == null || actions.length() == 0 || actions.length() > CommandAnalysis.MAX_ACTIONS)
      throw new JSONException("Пустой или слишком большой пакет");
    for (int i = 0; i < actions.length(); i++) {
      AgentAction action = AgentAction.parse(actions.optJSONObject(i), scope);
      if (action == null || (action.kind != AgentAction.Kind.CREATE
          && action.kind != AgentAction.Kind.REMIND && action.kind != AgentAction.Kind.FOLDER
          && action.kind != AgentAction.Kind.CLEAR_REMINDERS))
        throw new JSONException("Недопустимое действие в черновике");
      if (action.kind == AgentAction.Kind.REMIND && action.repeat != ReminderPolicy.REPEAT_NONE)
        throw new JSONException("Повторы не поддерживаются");
      action.metadata = Metadata.ai(action.metadata);
      out.actions.add(action);
    }
    return out;
  }

  private static String notesWord(int n) {
    if (n % 10 == 1 && n % 100 != 11) return "заметку";
    if (n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14)) return "заметки";
    return "заметок";
  }
}
