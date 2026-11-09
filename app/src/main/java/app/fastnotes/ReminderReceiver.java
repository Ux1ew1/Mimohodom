package app.fastnotes;

import android.app.*;
import android.content.*;
import android.net.Uri;

public final class ReminderReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent intent) {
    long id = intent.getLongExtra("id", 0);
    if (android.os.Build.VERSION.SDK_INT >= 33
        && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
    try (Store store = new Store(c)) {
      Note n = store.note(id);
      if (n == null
          || n.reminder == 0
          || Reminders.trigger(c, n) > System.currentTimeMillis() + 1000
          || Reminders.delivered(c, n)) return;
      Reminders.channel(c);
      PendingIntent open =
          PendingIntent.getActivity(
              c,
              0,
              new Intent(c, MainActivity.class)
                  .setData(Uri.parse("fastnotes://note/" + id))
                  .putExtra("note", id),
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      Notification notification =
          new Notification.Builder(c, Reminders.CHANNEL)
              .setSmallIcon(R.drawable.ic_notification)
              .setContentTitle(n.title)
              .setContentText(
                  "Событие: "
                      + new java.text.SimpleDateFormat("d MMM, HH:mm", new java.util.Locale("ru"))
                          .format(new java.util.Date(n.reminder)))
              .setStyle(
                  new Notification.BigTextStyle()
                      .bigText(
                          "Событие: "
                              + new java.text.SimpleDateFormat(
                                      "d MMM, HH:mm", new java.util.Locale("ru"))
                                  .format(new java.util.Date(n.reminder))
                              + "\n"
                              + n.body))
              .setContentIntent(open)
              .setAutoCancel(true)
              .build();
      c.getSystemService(NotificationManager.class).notify("note-" + id, 1, notification);
      c.getSharedPreferences("deliveredReminders", 0)
          .edit()
          .putLong("note." + id, n.reminder)
          .apply();
    }
  }
}
