package app.fastnotes;

import android.content.*;

public final class BootReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent i) {
    String action = i.getAction();
    if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
        && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
    PendingResult pending = goAsync();
    new Thread(
            () -> {
              try (Store store = new Store(c)) {
                for (Note n : store.notes())
                  if (n.reminder > System.currentTimeMillis()) Reminders.schedule(c, n);
              } finally {
                pending.finish();
              }
            },
            "restore-reminders")
        .start();
  }
}
