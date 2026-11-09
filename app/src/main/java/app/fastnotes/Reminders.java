package app.fastnotes;

import android.app.*;
import android.content.*;
import android.net.Uri;

public final class Reminders {
  public static final String CHANNEL = "reminders";
  public static final int[] LEAD_MINUTES = {0, 5, 10, 15, 30, 60, 120, 1440};

  public static int leadMinutes(Context c) {
    int value = c.getSharedPreferences("settings", 0).getInt("reminderLead", 0);
    for (int allowed : LEAD_MINUTES) if (value == allowed) return value;
    return 0;
  }

  public static long trigger(Context c, Note n) {
    return n.reminder - leadMinutes(c) * 60000L;
  }

  public static boolean delivered(Context c, Note n) {
    return c.getSharedPreferences("deliveredReminders", 0).getLong("note." + n.id, 0) == n.reminder;
  }

  public static void channel(Context c) {
    c.getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel(CHANNEL, "Напоминания", NotificationManager.IMPORTANCE_HIGH));
  }

  private static PendingIntent intent(Context c, long id) {
    return PendingIntent.getBroadcast(
        c,
        0,
        new Intent(c, ReminderReceiver.class)
            .setData(Uri.parse("fastnotes://reminder/" + id))
            .putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  public static void cancel(Context c, long id) {
    c.getSystemService(AlarmManager.class).cancel(intent(c, id));
    c.getSystemService(NotificationManager.class).cancel("note-" + id, 1);
  }

  public static void schedule(Context c, Note n) {
    if (n.reminder > 0 && delivered(c, n)) return;
    cancel(c, n.id);
    if (n.reminder <= System.currentTimeMillis() || delivered(c, n)) return;
    c.getSystemService(AlarmManager.class)
        .setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            Math.max(System.currentTimeMillis() + 1000, trigger(c, n)),
            intent(c, n.id));
  }
}
