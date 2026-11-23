package app.fastnotes;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/**
 * Проактивные вопросы помощника. Будильник в нужное время запускает задачу с сетью; задача просит
 * модель составить один вопрос по ситуации (ближайшие события, заметки, прошлые ответы), создаёт
 * чат с этим вопросом и показывает уведомление. Дальше диалог идёт в обычном ИИ-чате. Один платный
 * запрос за раз, без автоматических повторов.
 */
public final class CheckInJob extends JobService {
  public static final String ACTION = "app.fastnotes.CHECKIN";
  public static final String CHANNEL = "checkin";
  private static final int JOB = 7205;
  private static final AtomicBoolean RUNNING = new AtomicBoolean();
  private static final Object LOCK = new Object();

  // Настройки (SharedPreferences "settings")
  static final String ON = "checkInOn", MINUTES = "checkInMinutes", EVERY = "checkInEvery";
  private static final String LAST = "checkInLast",
      CHAT = "checkInChat",
      MISSED = "checkInMissed",
      SLOT = "checkInSlot",
      // Сроки двух расписаний и день последнего обхода дел («Как продвигается?»).
      AI_SLOT = "checkInAiSlot",
      PROGRESS_SLOT = "progressSlot",
      PROGRESS_DAY = "progressDay",
      STATUS = "checkInStatus";

  // ---- Настройки ----

  private static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("settings", 0);
  }

  public static boolean enabled(Context c) {
    return prefs(c).getBoolean(ON, false);
  }

  public static int minutes(Context c) {
    return Math.max(
        0, Math.min(prefs(c).getInt(MINUTES, CheckIn.DEFAULT_MINUTES), 24 * 60 - 1));
  }

  public static int every(Context c) {
    return CheckIn.every(prefs(c).getInt(EVERY, CheckIn.DEFAULT_EVERY));
  }

  /** Настройка: раньше этого времени не спрашивать «Как продвигается?» о заметке. */
  public static String progressKey(long note) {
    return "progressNext." + note;
  }

  /**
   * Дела для «Как продвигается?» (до {@code limit}): заметки вне корзины и вне колод (кроме верхней), с
   * классом из проверки агента, открытым планом и сроком — выбор по правилам {@link ProgressCheck}.
   */
  static List<ProgressCheck.Candidate> progressCandidates(
      Context c, Store store, long now, int limit) {
    ManagerStore manager = new ManagerStore(store);
    Map<Long, String> kinds = manager.kinds();
    Map<Long, Long> tops = manager.topMap();
    // Эксперимент «баллы внимания»: о заметках на нуле «Как продвигается?» не спрашивает.
    Set<Long> muted = new HashSet<>();
    if (store.attentionEnabled())
      try (android.database.Cursor rows =
          store.getReadableDatabase().rawQuery("SELECT id FROM notes WHERE attention=0", null)) {
        while (rows.moveToNext()) muted.add(rows.getLong(0));
      }
    List<ProgressCheck.Candidate> all = new ArrayList<>();
    for (Note n : store.notes()) {
      if (muted.contains(n.id)) continue;
      Long top = tops.get(n.id);
      if (top != null && top != n.id) continue;
      ProgressCheck.Candidate k = new ProgressCheck.Candidate();
      k.id = n.id;
      k.title = n.title;
      k.kind = kinds.getOrDefault(n.id, "");
      k.reminder = n.reminder;
      k.repeat = n.repeat;
      k.updated = n.updated;
      k.created = n.created;
      PlanTree plan = PlanTree.parse(store.plan(n.id));
      k.planOpen = plan.checkable() > plan.done();
      k.nextAsk = prefs(c).getLong(progressKey(n.id), 0);
      all.add(k);
    }
    return ProgressCheck.pick(all, now, limit);
  }

  /** Сообщение о последнем запуске для экрана настроек. */
  public static String status(Context c) {
    return prefs(c).getString(STATUS, "");
  }

  private static void status(Context c, String value) {
    prefs(c).edit().putString(STATUS, value).apply();
  }

  /** При включении начинаем с чистого листа: прежнее молчание не учитывается. */
  public static void reset(Context c) {
    prefs(c).edit().remove(CHAT).putInt(MISSED, 0).remove(STATUS).apply();
  }

  // ---- Расписание ----

  private static PendingIntent alarmIntent(Context c) {
    return PendingIntent.getBroadcast(
        c,
        0,
        new Intent(c, ReminderReceiver.class)
            .setAction(ACTION)
            .setData(Uri.parse("fastnotes://checkin")),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  /** Сколько вопросов подряд осталось без ответа, не считая последнего, на который ещё ждём. */
  private static int confirmedMisses(Context c, Store store) {
    long previous = prefs(c).getLong(CHAT, 0);
    if (previous == 0 || answered(store, previous)) return 0;
    return prefs(c).getInt(MISSED, 0);
  }

  private static boolean answered(Store store, long chat) {
    // Удалённый чат — тоже реакция: пользователь не хочет этот вопрос.
    return !store.chatExists(chat) || !store.userText(chat).isEmpty();
  }

  /**
   * Ставит или снимает будильник ближайшего вопроса. Два расписания: «Как продвигается?» —
   * каждый день в выбранное время, без модели и ключа; вопрос от ИИ — с выбранной частотой и только
   * через Polza.AI. Будильник один — на ближайший из сроков.
   */
  public static void arm(Context c) {
    AlarmManager alarm = c.getSystemService(AlarmManager.class);
    PendingIntent pending = alarmIntent(c);
    if (!enabled(c)) {
      alarm.cancel(pending);
      prefs(c).edit().remove(SLOT).remove(AI_SLOT).remove(PROGRESS_SLOT).apply();
      return;
    }
    long now = System.currentTimeMillis();
    ZoneId zone = ZoneId.systemDefault();
    long progressAt =
        ProgressCheck.nextDaily(now, minutes(c), prefs(c).getLong(PROGRESS_DAY, -1), zone);
    long aiAt = -1;
    if (LocalLlm.cloudBackground(c)) {
      int missed;
      try (Store store = new Store(c)) {
        missed = confirmedMisses(c, store);
      }
      aiAt = CheckIn.nextAt(now, minutes(c), every(c), prefs(c).getLong(LAST, 0), missed, zone);
      if (aiAt < 0)
        status(c, "Вопросы от ИИ на паузе: вы не отвечали на последние. Ответьте на любой из них"
            + " в чате, и они продолжатся. «Как продвигается?» работает.");
    }
    long at = aiAt < 0 ? progressAt : Math.min(aiAt, progressAt);
    prefs(c)
        .edit()
        .putLong(SLOT, at)
        .putLong(AI_SLOT, aiAt)
        .putLong(PROGRESS_SLOT, progressAt)
        .apply();
    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
  }

  /**
   * Будильник сработал: какие из вопросов наступили, запоминаем до перестановки будильника, затем
   * ставим следующий срок и запускаем задачу (сеть нужна только вопросу от ИИ).
   */
  static void onAlarm(Context c) {
    long due = prefs(c).getLong(SLOT, 0);
    long ai = prefs(c).getLong(AI_SLOT, -1), progress = prefs(c).getLong(PROGRESS_SLOT, -1);
    boolean aiDue = ai > 0 && ai <= due, progressDue = progress > 0 && progress <= due;
    arm(c);
    if (!enabled(c) || due == 0 || (!aiDue && !progressDue)) return;
    enqueue(c, due, aiDue, progressDue);
  }

  private static boolean enqueue(Context c, long due, boolean ai, boolean progress) {
    PersistableBundle extras = new PersistableBundle();
    extras.putLong("due", due);
    extras.putBoolean("ai", ai);
    extras.putBoolean("progress", progress);
    return c.getSystemService(JobScheduler.class)
            .schedule(
                new JobInfo.Builder(JOB, new ComponentName(c, CheckInJob.class))
                    .setRequiredNetworkType(
                        ai ? JobInfo.NETWORK_TYPE_ANY : JobInfo.NETWORK_TYPE_NONE)
                    .setExtras(extras)
                    .setMinimumLatency(1)
                    .build())
        == JobScheduler.RESULT_SUCCESS;
  }

  // ---- Задача ----

  @Override
  public boolean onStartJob(JobParameters params) {
    if (!RUNNING.compareAndSet(false, true)) return false;
    long due = params.getExtras().getLong("due", 0);
    boolean ai = params.getExtras().getBoolean("ai", true);
    boolean progress = params.getExtras().getBoolean("progress", false);
    new Thread(
            () -> {
              try {
                runScheduled(getApplicationContext(), due, ai, progress);
              } finally {
                RUNNING.set(false);
                new Handler(getMainLooper()).post(() -> jobFinished(params, false));
              }
            },
            "check-in")
        .start();
    return true;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    return false; // Платный запрос повторно не отправляем.
  }

  private static void runScheduled(Context c, long due, boolean ai, boolean progress) {
    long now = System.currentTimeMillis();
    if (!enabled(c)) return;
    try (Store store = new Store(c)) {
      if (CheckIn.stale(due, now)) {
        status(c, "Вопрос пропущен: телефон долго был недоступен.");
        return;
      }
      if (CheckIn.busy(store.lastUserMessage(), now)) {
        status(c, "Вопрос пропущен: вы как раз писали в чате.");
        return;
      }
      if (!Reminders.notificationsAllowed(c)) {
        status(c, "Вопрос не отправлен: уведомления выключены.");
        return;
      }
      if (progress) {
        prefs(c)
            .edit()
            .putLong(
                PROGRESS_DAY,
                java.time.Instant.ofEpochMilli(now)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .toEpochDay())
            .apply();
        for (long chat : composeProgress(c, store, ProgressCheck.PER_RUN))
          notifyChat(c, chat, PlanChat.split(store.firstMessage(chat)).text);
      }
      if (ai) {
        long chat = composeQuestion(c, store);
        if (chat > 0) notifyChat(c, chat, PlanChat.split(store.firstMessage(chat)).text);
      }
    } catch (Exception e) {
      status(c, "Не удалось задать вопрос. " + describe(e));
    } finally {
      arm(c);
    }
  }

  private static long dayStart(long now, ZoneId zone) {
    return java.time.Instant.ofEpochMilli(now)
        .atZone(zone)
        .toLocalDate()
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli();
  }

  /** «Спросить сейчас»: сначала «Как продвигается?» о подходящем деле, иначе вопрос от ИИ. */
  public static long compose(Context c, Store store) throws Exception {
    List<Long> chats = composeProgress(c, store, 1);
    return chats.isEmpty() ? composeQuestion(c, store) : chats.get(0);
  }

  /**
   * «Как продвигается?» о делах с близким сроком или давно без движения (до {@code max} чатов, в
   * пределах 4 чатов помощника в сутки). Без модели и без ключа. Возвращает созданные чаты.
   */
  public static List<Long> composeProgress(Context c, Store store, int max) {
    synchronized (LOCK) {
      List<Long> out = new ArrayList<>();
      long now = System.currentTimeMillis();
      ZoneId zone = ZoneId.systemDefault();
      int left = Math.min(max, ProgressCheck.left(store.agentChatsSince(dayStart(now, zone))));
      if (left <= 0) return out;
      for (ProgressCheck.Candidate task : progressCandidates(c, store, now, left)) {
        String question = ProgressCheck.question(task, now, zone);
        out.add(store.newProgressChat(task.id, "Как дела: " + task.title, question));
        prefs(c).edit().putLong(progressKey(task.id), ProgressCheck.nextAsk("", now)).apply();
        status(
            c,
            "Последний вопрос: «Как продвигается?» о «"
                + CheckIn.line(task.title, 40)
                + "», "
                + new java.text.SimpleDateFormat("d MMM, HH:mm", new Locale("ru"))
                    .format(new Date(now)));
      }
      return out;
    }
  }

  /**
   * Просит модель составить вопрос о планах и создаёт чат с ним. Выполняется вне основного потока.
   * Возвращает номер чата или 0, если задавать нечего.
   */
  public static long composeQuestion(Context c, Store store) throws Exception {
    synchronized (LOCK) {
      if (!LocalLlm.cloudBackground(c)) {
        status(c, "Вопросы от ИИ на паузе: выбран локальный GGUF. «Как продвигается?» работает.");
        return 0;
      }
      String key = Secrets.read(c), model = prefs(c).getString("model", "").trim();
      if (key.isEmpty() || model.isEmpty()) {
        status(c, "Для вопросов от ИИ сохраните ключ и модель Polza.AI.");
        return 0;
      }
      long now = System.currentTimeMillis();
      ZoneId zone = ZoneId.systemDefault();
      // Прошлый вопрос остался без ответа — запоминаем молчание, а не стираем его.
      long previous = prefs(c).getLong(CHAT, 0);
      int streak = previous == 0 || answered(store, previous) ? 0 : prefs(c).getInt(MISSED, 0) + 1;
      if (streak >= CheckIn.MAX_MISSED) {
        prefs(c).edit().putInt(MISSED, streak).apply();
        return 0;
      }
      // Не больше 4 чатов в день, начатых помощником (решение пользователя 4).
      if (!ProgressCheck.allowed(store.agentChatsSince(dayStart(now, zone)))) {
        status(c, "Сегодня помощник уже начал 4 чата — следующий вопрос завтра.");
        return 0;
      }
      JSONObject payload =
          new JSONObject()
              .put("model", model)
              .put("max_tokens", 220)
              .put(
                  "messages",
                  new JSONArray()
                      .put(new JSONObject().put("role", "system").put("content", CheckIn.system()))
                      .put(
                          new JSONObject()
                              .put("role", "user")
                              .put("content", prompt(c, store, now, zone))));
      JSONObject response = Polza.request("chat/completions", key, payload);
      String question =
          CheckIn.clean(
              response
                  .getJSONArray("choices")
                  .getJSONObject(0)
                  .getJSONObject("message")
                  .optString("content", ""));
      if (question.isEmpty()) throw new java.io.IOException("Модель не составила вопрос.");
      long chat = store.newAssistantChat(Note.autoTitle("", question), question);
      prefs(c)
          .edit()
          .putLong(LAST, now)
          .putLong(CHAT, chat)
          .putInt(MISSED, streak)
          .putString(
              STATUS,
              "Последний вопрос: "
                  + new java.text.SimpleDateFormat("d MMM, HH:mm", new Locale("ru"))
                      .format(new Date(now)))
          .apply();
      return chat;
    }
  }

  private static String prompt(Context c, Store store, long now, ZoneId zone) {
    List<Note> notes = store.notes();
    List<Note> upcoming = new ArrayList<>();
    for (Note n : notes)
      if (n.reminder > now && n.reminder < now + 14L * 24 * 3_600_000) upcoming.add(n);
    upcoming.sort((a, b) -> Long.compare(a.reminder, b.reminder));
    List<String> events = new ArrayList<>();
    for (Note n : upcoming) events.add(CheckIn.eventLine(n.reminder, now, zone, n.title));
    List<String> recent = new ArrayList<>();
    for (Note n : notes) {
      if (recent.size() >= 6) break;
      recent.add(CheckIn.recentLine(n.title, Retrieval.excerpt(n.body, "", 160)));
    }
    List<String> past = new ArrayList<>();
    for (String[] chat : store.chats()) {
      long id = Long.parseLong(chat[0]);
      if (past.size() >= 3) break;
      if (ChatOrigin.AGENT.equals(chat.length > 4 ? chat[4] : ""))
        past.add(CheckIn.pastLine(PlanChat.split(store.firstMessage(id)).text, store.userText(id)));
    }
    return CheckIn.prompt(now, zone, events, recent, past);
  }

  private static String describe(Exception e) {
    if (e instanceof java.net.SocketTimeoutException) return "Сервер не ответил вовремя.";
    if (e instanceof java.net.UnknownHostException) return "Нет подключения к Polza.AI.";
    if (e instanceof java.io.IOException && e.getMessage() != null) return e.getMessage();
    return "Попробуйте позже или нажмите «Спросить сейчас».";
  }

  /** Текст ошибки для ручного запуска из настроек. */
  public static String errorText(Exception e) {
    return describe(e);
  }

  // ---- Уведомление ----

  public static void notifyChat(Context c, long chat, String question) {
    NotificationManager manager = c.getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel(CHANNEL, "Вопросы помощника", NotificationManager.IMPORTANCE_DEFAULT));
    PendingIntent open =
        PendingIntent.getActivity(
            c,
            JOB,
            new Intent(c, MainActivity.class)
                .setData(Uri.parse("fastnotes://checkin/" + chat))
                .putExtra("chat", chat),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    manager.notify(
        "checkin",
        1,
        new Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Помощник спрашивает")
            .setContentText(question)
            .setStyle(new Notification.BigTextStyle().bigText(question))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build());
  }
}
