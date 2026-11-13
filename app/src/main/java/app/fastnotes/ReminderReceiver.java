package app.fastnotes;

import android.content.*;

public final class ReminderReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent intent) {
    long id = intent.getLongExtra("id", 0);
    if (Reminders.ACTION_SNOOZE.equals(intent.getAction())) {
      Reminders.snooze(
          c, id, intent.getLongExtra("reminder", 0), intent.getIntExtra("kind", 0));
      return;
    }
    boolean snoozed = intent.getBooleanExtra("snoozed", false);
    try (Store store = new Store(c)) {
      Note n = store.note(id);
      if (snoozed) {
        // Заметку удалили или перенесли событие, пока оповещение было отложено.
        if (n == null || n.reminder == 0 || n.reminder != Reminders.snoozedReminder(c, id)) {
          Reminders.clearSnooze(c, id);
          return;
        }
        if (Reminders.fire(c, n, true)) Reminders.clearSnooze(c, id);
        return;
      }
      if (n == null
          || n.reminder == 0
          || Reminders.trigger(c, n) > System.currentTimeMillis() + 1000) return;
      Reminders.fire(c, n, false);
    }
  }
}
