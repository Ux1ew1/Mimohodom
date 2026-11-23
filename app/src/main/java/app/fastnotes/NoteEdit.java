package app.fastnotes;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Правка заметки, предложенная в чате по заметке (docs/AGENT-PLANNER.md, 2.6): новое название,
 * текст, который дописать в конец, и даты напоминания (добавить к прежним или заменить их). Модель
 * ничего не меняет сама: правка ложится в черновик сообщения и применяется кнопкой «Применить».
 * Чистая Java без Android.
 */
public final class NoteEdit {
  public static final int MAX_TITLE = 160, MAX_APPEND = 4000;

  public String title = "", append = "";
  /** Даты напоминания (мс), только будущие на момент ответа модели, по порядку. */
  public long[] remind = new long[0];
  /** true — даты заменяют прежние напоминания, false — добавляются к ним. */
  public boolean replace;
  /** Сколько дат модели отброшено: прошедшие или неразборчивые. */
  public int skipped;

  public boolean isEmpty() {
    return title.isEmpty() && append.isEmpty() && remind.length == 0;
  }

  /**
   * Разбор поля {@code edit} ответа модели: {@code {"title","append","remind":["ГГГГ-ММ-ДД
   * ЧЧ:ММ"],"replace":false}}. Пустая правка — null.
   */
  public static NoteEdit fromModel(JSONObject o, ZoneId zone, long now) {
    if (o == null) return null;
    NoteEdit e = new NoteEdit();
    e.title = oneLine(PlanTree.clip(o.optString("title", ""), MAX_TITLE));
    e.append = clipText(o.optString("append", ""), MAX_APPEND);
    JSONArray raw = o.optJSONArray("remind");
    long[] times = new long[0];
    for (int i = 0; raw != null && i < raw.length(); i++) {
      long at = PlanTree.time(raw.optString(i, ""), zone);
      if (at <= now || times.length >= ReminderPolicy.MAX_TIMES) e.skipped++;
      else times = ReminderPolicy.withTime(times, at);
    }
    e.remind = times;
    e.replace = o.optBoolean("replace", false);
    return e.isEmpty() ? null : e;
  }

  /** Вид для черновика сообщения. */
  public JSONObject toJson() {
    try {
      JSONArray times = new JSONArray();
      for (long t : remind) times.put(t);
      return new JSONObject()
          .put("title", title)
          .put("append", append)
          .put("remind", times)
          .put("replace", replace);
    } catch (org.json.JSONException ex) {
      throw new IllegalStateException(ex);
    }
  }

  /** Обратное к {@link #toJson}; повреждённая или пустая правка — null. */
  public static NoteEdit fromJson(JSONObject o) {
    if (o == null) return null;
    NoteEdit e = new NoteEdit();
    e.title = oneLine(PlanTree.clip(o.optString("title", ""), MAX_TITLE));
    e.append = clipText(o.optString("append", ""), MAX_APPEND);
    JSONArray raw = o.optJSONArray("remind");
    long[] times = new long[0];
    for (int i = 0; raw != null && i < raw.length(); i++) {
      long t = raw.optLong(i, 0);
      if (t > 0) times = ReminderPolicy.withTime(times, t);
    }
    e.remind = times;
    e.replace = o.optBoolean("replace", false);
    return e.isEmpty() ? null : e;
  }

  /** Строки для карточки в чате: что именно изменится. */
  public List<String> describe(ZoneId zone) {
    List<String> out = new ArrayList<>();
    if (!title.isEmpty()) out.add("Название: «" + title + "»");
    if (!append.isEmpty()) {
      String shown = append.length() > 160 ? append.substring(0, 160).trim() + "…" : append;
      out.add("Дописать в конец: «" + shown + "»");
    }
    if (remind.length > 0) {
      StringBuilder dates = new StringBuilder();
      for (int i = 0; i < remind.length && i < 6; i++)
        dates.append(i > 0 ? ", " : "").append(ReminderBatch.label(remind[i], zone));
      if (remind.length > 6) dates.append(" и ещё ").append(remind.length - 6);
      out.add((replace ? "Напоминание вместо прежнего: " : "Напоминание: ") + dates);
    }
    return out;
  }

  /** Заметка после правки. */
  public static final class Result {
    public String title, body, times;
    public long reminder;
    public int repeat;
    /** Даты правки уже прошли к моменту нажатия: напоминание не менялось. */
    public boolean datesPassed;
  }

  /**
   * Применяет правку к полям заметки. Даты, прошедшие к моменту нажатия, отбрасываются. У заметки с
   * повтором по правилу (каждый день …) новые даты заменяют повтор: смешать их нельзя. Одна дата —
   * обычное напоминание, несколько — серия по списку дат.
   */
  public Result apply(String title, String body, long reminder, int repeat, String times, long now) {
    Result r = new Result();
    r.title = this.title.isEmpty() ? title : this.title;
    String base = body == null ? "" : body.trim();
    r.body = append.isEmpty() ? base : base.isEmpty() ? append : base + "\n\n" + append;
    r.reminder = reminder;
    r.repeat = repeat;
    r.times = times == null ? "" : times;
    long[] future = java.util.Arrays.stream(remind).filter(t -> t > now).toArray();
    if (remind.length > 0 && future.length == 0) r.datesPassed = true;
    if (future.length == 0) return r;
    long[] all =
        replace || ReminderPolicy.periodic(repeat)
            ? future
            : ReminderBatch.mergedTimes(reminder, repeat, times, future, now);
    if (all.length == 1) {
      r.reminder = all[0];
      r.repeat = ReminderPolicy.REPEAT_NONE;
      r.times = "";
    } else {
      r.reminder = ReminderPolicy.currentInList(all, now);
      r.repeat = ReminderPolicy.REPEAT_LIST;
      r.times = ReminderPolicy.encodeTimes(all);
    }
    return r;
  }

  private static String oneLine(String s) {
    return s.replaceAll("\\s+", " ").trim();
  }

  private static String clipText(String raw, int max) {
    String t = raw == null ? "" : raw.trim();
    if (t.length() <= max) return t;
    int end = max;
    if (Character.isHighSurrogate(t.charAt(end - 1))) end--;
    return t.substring(0, end).trim() + "…";
  }
}
