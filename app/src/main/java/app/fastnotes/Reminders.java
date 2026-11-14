package app.fastnotes;

import android.app.*;
import android.content.*;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public final class Reminders {
  public static final String CHANNEL = "reminders";
  public static final String DIGEST_CHANNEL = "digest";
  public static final String ACTION_SNOOZE = "app.fastnotes.SNOOZE";
  public static final String ACTION_DIGEST = "app.fastnotes.DIGEST";
  public static final int[] LEAD_MINUTES = {0, 5, 10, 15, 30, 60, 120, 1440};
  private static final String SNOOZED = "snoozedReminders";

  public static int leadMinutes(Context c) {
    int value = c.getSharedPreferences("settings", 0).getInt("reminderLead", 0);
    for (int allowed : LEAD_MINUTES) if (value == allowed) return value;
    return 0;
  }

  /** Интервал «за N минут» для заметки: свой, если задан, иначе общий. */
  public static int leadFor(Context c, Note n) {
    for (int allowed : LEAD_MINUTES) if (allowed == n.lead) return allowed;
    return leadMinutes(c);
  }

  public static long trigger(Context c, Note n) {
    return n.reminder - leadFor(c, n) * 60000L;
  }

  public static boolean delivered(Context c, Note n) {
    return c.getSharedPreferences("deliveredReminders", 0).getLong("note." + n.id, 0) == n.reminder;
  }

  public static void channel(Context c) {
    c.getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel(CHANNEL, "Напоминания", NotificationManager.IMPORTANCE_HIGH));
  }

  // ---- Состояние для экрана настроек ----

  /** Уведомления разрешены в системе, и канал напоминаний не отключён. */
  public static boolean notificationsAllowed(Context c) {
    NotificationManager manager = c.getSystemService(NotificationManager.class);
    if (!manager.areNotificationsEnabled()) return false;
    NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
    return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
  }

  /** Можно ли ставить точные будильники (до Android 12 всегда да). */
  public static boolean exactAllowed(Context c) {
    return Build.VERSION.SDK_INT < 31
        || c.getSystemService(AlarmManager.class).canScheduleExactAlarms();
  }

  /** Приложение исключено из оптимизации батареи. */
  public static boolean batteryUnrestricted(Context c) {
    return c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName());
  }

  // ---- Планирование ----

  private static PendingIntent intent(Context c, long id) {
    return PendingIntent.getBroadcast(
        c,
        0,
        new Intent(c, ReminderReceiver.class)
            .setData(Uri.parse("fastnotes://reminder/" + id))
            .putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private static PendingIntent snoozeIntent(Context c, long id) {
    return PendingIntent.getBroadcast(
        c,
        0,
        new Intent(c, ReminderReceiver.class)
            .setData(Uri.parse("fastnotes://snoozed/" + id))
            .putExtra("id", id)
            .putExtra("snoozed", true),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private static void arm(Context c, long at, PendingIntent pending) {
    AlarmManager alarm = c.getSystemService(AlarmManager.class);
    try {
      if (exactAllowed(c)) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
      else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
    } catch (SecurityException e) {
      alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
    }
  }

  public static void cancel(Context c, long id) {
    c.getSystemService(AlarmManager.class).cancel(intent(c, id));
    clearSnooze(c, id);
    c.getSystemService(NotificationManager.class).cancel("note-" + id, 1);
  }

  public static void schedule(Context c, Note n) {
    if (n.reminder > 0 && delivered(c, n)) return;
    cancel(c, n.id);
    arm(c, n);
  }

  /** Ставит будильник, не трогая показанное уведомление и отложенное оповещение. */
  private static void arm(Context c, Note n) {
    if (n.reminder <= System.currentTimeMillis() || delivered(c, n)) return;
    arm(c, Math.max(System.currentTimeMillis() + 1000, trigger(c, n)), intent(c, n.id));
  }

  /** Передвигает повторяющееся напоминание на ближайшее будущее время и ставит будильник. */
  private static void advance(Context c, Note n) {
    long next =
        ReminderPolicy.nextOccurrence(
            n.reminder, n.repeat, System.currentTimeMillis(), ZoneId.systemDefault());
    if (next <= n.reminder) return;
    try (Store store = new Store(c)) {
      if (!store.advanceReminder(n.id, n.reminder, next)) return;
      Note updated = store.note(n.id);
      if (updated == null) return;
      c.getSystemService(AlarmManager.class).cancel(intent(c, n.id));
      arm(c, updated);
    }
  }

  // ---- Откладывание ----

  /** Откладывает оповещение, не меняя время самого события. */
  public static void snooze(Context c, long id, long reminder, int kind) {
    long at = ReminderPolicy.snoozeAt(kind, System.currentTimeMillis(), ZoneId.systemDefault());
    c.getSharedPreferences(SNOOZED, 0)
        .edit()
        .putString("note." + id, ReminderPolicy.encodeSnooze(reminder, at))
        .apply();
    arm(c, at, snoozeIntent(c, id));
    c.getSystemService(NotificationManager.class).cancel("note-" + id, 1);
  }

  /** Время события, для которого отложено оповещение, или 0. */
  public static long snoozedReminder(Context c, long id) {
    long[] value =
        ReminderPolicy.decodeSnooze(
            c.getSharedPreferences(SNOOZED, 0).getString("note." + id, null));
    return value == null ? 0 : value[0];
  }

  public static void clearSnooze(Context c, long id) {
    c.getSystemService(AlarmManager.class).cancel(snoozeIntent(c, id));
    c.getSharedPreferences(SNOOZED, 0).edit().remove("note." + id).apply();
  }

  /** После перезагрузки или смены разрешения заново ставит отложенные оповещения. */
  public static void restoreSnoozed(Context c) {
    for (Map.Entry<String, ?> e : c.getSharedPreferences(SNOOZED, 0).getAll().entrySet()) {
      long[] value = ReminderPolicy.decodeSnooze(String.valueOf(e.getValue()));
      if (value == null || !e.getKey().startsWith("note.")) continue;
      long id;
      try {
        id = Long.parseLong(e.getKey().substring(5));
      } catch (NumberFormatException ex) {
        continue;
      }
      arm(c, Math.max(value[1], System.currentTimeMillis() + 1000), snoozeIntent(c, id));
    }
  }

  // ---- Доставка ----

  /**
   * Показывает уведомление и помечает напоминание доставленным. Без разрешения ничего не
   * помечает, чтобы напоминание дошло после включения уведомлений.
   */
  public static synchronized boolean fire(Context c, Note n, boolean snoozed) {
    if (!snoozed && delivered(c, n)) return false;
    if (!notificationsAllowed(c)) return false;
    channel(c);
    c.getSystemService(NotificationManager.class)
        .notify("note-" + n.id, 1, build(c, n, snoozed).build());
    if (!snoozed) {
      c.getSharedPreferences("deliveredReminders", 0)
          .edit()
          .putLong("note." + n.id, n.reminder)
          .apply();
      if (n.repeat != 0) advance(c, n);
    }
    return true;
  }

  /** Показывает пропущенные напоминания: тех, чьё время прошло, пока устройство было выключено. */
  public static int deliverMissed(Context c, List<Note> notes) {
    long now = System.currentTimeMillis();
    int shown = 0;
    for (Note n : notes) {
      boolean delivered = delivered(c, n);
      if (ReminderPolicy.missed(n.reminder, trigger(c, n), now, delivered)) {
        if (fire(c, n, false)) shown++;
      } else if (n.repeat != 0
          && n.reminder > 0
          && n.reminder + ReminderPolicy.MISSED_GRACE_MS <= now) {
        // Серия давно прошла или уже доставлена: тихо переносим на следующее время.
        advance(c, n);
      }
    }
    return shown;
  }

  /** Проверочное уведомление из настроек. */
  public static boolean test(Context c) {
    if (!notificationsAllowed(c)) return false;
    channel(c);
    c.getSystemService(NotificationManager.class)
        .notify(
            "test",
            1,
            new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Проверочное уведомление")
                .setContentText("Так будут приходить напоминания.")
                .setAutoCancel(true)
                .build());
    return true;
  }

  // ---- Утренняя сводка ----

  public static boolean digestEnabled(Context c) {
    return c.getSharedPreferences("settings", 0).getBoolean("digestOn", false);
  }

  public static int digestMinutes(Context c) {
    int value =
        c.getSharedPreferences("settings", 0)
            .getInt("digestMinutes", ReminderPolicy.DIGEST_DEFAULT_MINUTES);
    return Math.max(0, Math.min(value, 24 * 60 - 1));
  }

  private static PendingIntent digestIntent(Context c) {
    return PendingIntent.getBroadcast(
        c,
        0,
        new Intent(c, ReminderReceiver.class)
            .setAction(ACTION_DIGEST)
            .setData(Uri.parse("fastnotes://digest")),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  /** Ставит или снимает ежедневную сводку в соответствии с настройками. */
  public static void scheduleDigest(Context c) {
    PendingIntent pending = digestIntent(c);
    if (!digestEnabled(c)) {
      c.getSystemService(AlarmManager.class).cancel(pending);
      return;
    }
    arm(
        c,
        ReminderPolicy.nextDigestAt(
            System.currentTimeMillis(), digestMinutes(c), ZoneId.systemDefault()),
        pending);
  }

  /** Одно тихое уведомление со списком событий на сегодня. Без событий ничего не показывает. */
  public static boolean sendDigest(Context c, List<Note> notes) {
    long now = System.currentTimeMillis();
    ZoneId zone = ZoneId.systemDefault();
    java.util.ArrayList<Note> today = new java.util.ArrayList<>();
    for (Note n : notes) if (ReminderPolicy.upcomingToday(n.reminder, now, zone)) today.add(n);
    if (today.isEmpty() || !notificationsAllowed(c)) return false;
    today.sort((a, b) -> Long.compare(a.reminder, b.reminder));
    NotificationManager manager = c.getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel(
            DIGEST_CHANNEL, "Утренняя сводка", NotificationManager.IMPORTANCE_LOW));
    java.text.SimpleDateFormat time = new java.text.SimpleDateFormat("HH:mm");
    Notification.InboxStyle style = new Notification.InboxStyle();
    int shown = Math.min(today.size(), 6);
    for (int i = 0; i < shown; i++)
      style.addLine(time.format(new java.util.Date(today.get(i).reminder)) + "  " + today.get(i).title);
    if (today.size() > shown) style.setSummaryText("и ещё " + (today.size() - shown));
    Note first = today.get(0);
    manager.notify(
        "digest",
        1,
        new Notification.Builder(c, DIGEST_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Сегодня: " + ReminderPolicy.eventCount(today.size()))
            .setContentText(time.format(new java.util.Date(first.reminder)) + "  " + first.title)
            .setStyle(style)
            .setContentIntent(
                PendingIntent.getActivity(
                    c,
                    0,
                    new Intent(c, MainActivity.class).setData(Uri.parse("fastnotes://digest")),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build());
    return true;
  }

  private static Notification.Builder build(Context c, Note n, boolean snoozed) {
    long now = System.currentTimeMillis();
    String line = ReminderPolicy.eventLine(n.reminder, now, snoozed, TimeZone.getDefault());
    PendingIntent open =
        PendingIntent.getActivity(
            c,
            0,
            new Intent(c, MainActivity.class)
                .setData(Uri.parse("fastnotes://note/" + n.id))
                .putExtra("note", n.id),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder builder =
        new Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(n.title)
            .setContentText(line)
            .setStyle(new Notification.BigTextStyle().bigText(line + "\n" + n.body))
            .setContentIntent(open)
            .setAutoCancel(true);
    for (int kind :
        new int[] {
          ReminderPolicy.SNOOZE_10_MIN, ReminderPolicy.SNOOZE_HOUR, ReminderPolicy.SNOOZE_TOMORROW
        })
      builder.addAction(
          new Notification.Action.Builder(
                  Icon.createWithResource(c, R.drawable.ic_notification),
                  ReminderPolicy.snoozeLabel(kind),
                  PendingIntent.getBroadcast(
                      c,
                      0,
                      new Intent(c, ReminderReceiver.class)
                          .setAction(ACTION_SNOOZE)
                          .setData(Uri.parse("fastnotes://snooze/" + n.id + "/" + kind))
                          .putExtra("id", n.id)
                          .putExtra("reminder", n.reminder)
                          .putExtra("kind", kind),
                      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
              .build());
    return builder;
  }
}
