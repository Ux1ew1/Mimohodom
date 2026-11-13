package app.fastnotes;

import android.app.AlarmManager;
import android.content.*;

public final class BootReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent i) {
    String action = i.getAction();
    if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
        && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
        && !AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action))
      return;
    PendingResult pending = goAsync();
    new Thread(
            () -> {
              try (Store store = new Store(c)) {
                java.util.List<Note> notes = store.notes();
                for (Note n : notes)
                  if (n.reminder > System.currentTimeMillis()) Reminders.schedule(c, n);
                Reminders.restoreSnoozed(c);
                Reminders.scheduleDigest(c);
                // Напоминания, время которых прошло, пока телефон был выключен.
                Reminders.deliverMissed(c, notes);
              } finally {
                pending.finish();
              }
            },
            "restore-reminders")
        .start();
  }
}
