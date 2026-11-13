package app.fastnotes;

import android.content.*;

public final class ReminderReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent intent) {
    long id = intent.getLongExtra("id", 0);
    if (Reminders.ACTION_DIGEST.equals(intent.getAction())) {
      try (Store store = new Store(c)) {
        Reminders.sendDigest(c, store.notes());
      } finally {
        // Следующая сводка завтра в то же время.
        Reminders.scheduleDigest(c);
      }
      return;
    }
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
        long stored = Reminders.snoozedReminder(c, id);
        // У повторяющегося напоминания событие уже передвинулось на следующее время.
        boolean same = n != null && (n.reminder == stored || (n.repeat != 0 && n.reminder > stored));
        if (n == null || n.reminder == 0 || !same) {
          Reminders.clearSnooze(c, id);
          return;
        }
        // Показываем событие, для которого отложили оповещение, а не следующее повторение.
        Note shown = new Note(n.id, n.title, n.body, n.created, n.updated, stored);
        shown.repeat = n.repeat;
        if (Reminders.fire(c, shown, true)) Reminders.clearSnooze(c, id);
        return;
      }
      if (n == null
          || n.reminder == 0
          || Reminders.trigger(c, n) > System.currentTimeMillis() + 1000) return;
      Reminders.fire(c, n, false);
    }
  }
}
