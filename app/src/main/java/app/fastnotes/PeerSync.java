package app.fastnotes;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Обмен заметками с компьютерами по Wi-Fi (docs/SYNC.md). Здесь — запуск в фоне, итог для экрана
 * настроек и действия после обмена: напоминания и календарь. Правила слияния — в {@link
 * SqliteSyncStore}, протокол — в {@link SyncClient}.
 */
public final class PeerSync {
  private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
  private static final String STATUS = "peerSyncStatus", LAST = "peerSyncLast";
  private static final long MIN_GAP_MS = 90_000;
  private static volatile Runnable listener;
  private static volatile boolean running, lastChanged;

  private PeerSync() {}

  private static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("settings", 0);
  }

  /** Экран подписывается на завершение обмена (вызывается не в главном потоке). */
  public static void listen(Runnable onFinished) {
    listener = onFinished;
  }

  public static boolean running() {
    return running;
  }

  /** Принёс ли последний обмен изменения в заметки (экрану тогда нужно обновиться). */
  public static boolean changedLastRun() {
    return lastChanged;
  }

  public static String status(Context c) {
    return prefs(c).getString(STATUS, "");
  }

  public static List<SyncStore.Peer> peers(Context c) {
    return new SqliteSyncStore(new Store(c)).peers();
  }

  /** Одна строка для хаба настроек. */
  public static String summary(Context c) {
    int n;
    try {
      n = peers(c).size();
    } catch (RuntimeException e) {
      return "Заметки между телефоном и компьютером";
    }
    if (n == 0) return "Не подключено · телефон и компьютер по Wi-Fi";
    long last = prefs(c).getLong(LAST, 0);
    String when = last == 0 ? "обмена ещё не было" : "обмен " + time(last);
    return (n == 1 ? "Компьютер подключён" : "Подключено компьютеров: " + n) + " · " + when;
  }

  public static String time(long ms) {
    return new SimpleDateFormat("d MMM, HH:mm", new Locale("ru")).format(new Date(ms));
  }

  /** Обмен со всеми узлами в фоне. Без {@code force} не чаще раза в полторы минуты. */
  public static void runAsync(Context c, boolean force) {
    Context app = c.getApplicationContext();
    if (!force && System.currentTimeMillis() - prefs(app).getLong(LAST, 0) < MIN_GAP_MS) return;
    if (running) return;
    WORKER.execute(() -> run(app, null));
  }

  /**
   * Сохраняет компьютер по коду сопряжения и сразу обменивается: первый обмен подтверждает
   * сопряжение на той стороне. Если не вышло, узел забывается. {@code done} получает итог.
   */
  public static void pair(Context c, String code, Consumer<String> done) {
    Context app = c.getApplicationContext();
    final SyncPairing.Code parsed;
    try {
      parsed = SyncPairing.parse(code);
    } catch (IllegalArgumentException e) {
      done.accept(e.getMessage());
      return;
    }
    WORKER.execute(
        () -> {
          SqliteSyncStore store = new SqliteSyncStore(new Store(app));
          if (parsed.device.equals(store.deviceId())) {
            done.accept("Это код этого же устройства");
            return;
          }
          SyncStore.Peer peer = new SyncStore.Peer();
          peer.device = parsed.device;
          peer.name = parsed.name;
          peer.platform = "desktop";
          peer.secret = parsed.secret;
          peer.hosts = new ArrayList<>(parsed.hosts);
          store.savePeer(peer);
          String result = run(app, parsed.device);
          if (store.peer(parsed.device) != null && result.startsWith("!")) {
            store.deletePeer(parsed.device);
            done.accept(result.substring(1));
          } else done.accept(result);
        });
  }

  public static void forget(Context c, String device) {
    WORKER.execute(() -> new SqliteSyncStore(new Store(c.getApplicationContext())).deletePeer(device));
  }

  /**
   * Возвращает итог для людей; текст, начинающийся с «!», — ошибка. {@code only} — обмен с одним
   * узлом (при сопряжении), иначе со всеми.
   */
  private static synchronized String run(Context app, String only) {
    running = true;
    StringBuilder out = new StringBuilder();
    boolean failed = false, changed = false;
    List<Long> removed = new ArrayList<>();
    try {
      Store db = new Store(app);
      SqliteSyncStore store = new SqliteSyncStore(db);
      SyncClient client = new SyncClient(store, new AppFiles(app, db), "android");
      for (SyncStore.Peer peer : store.peers()) {
        if (only != null && !only.equals(peer.device)) continue;
        try {
          SyncClient.Report r = client.sync(peer.device);
          changed |= r.stats.applied > 0;
          removed.addAll(r.stats.removed);
          if (out.length() > 0) out.append('\n');
          out.append(peer.name)
              .append(": отправлено ")
              .append(r.sent)
              .append(", получено ")
              .append(r.received);
          if (r.imagesDown + r.imagesUp > 0)
            out.append(", картинок ").append(r.imagesDown).append("↓ ").append(r.imagesUp).append("↑");
          if (r.conflicts > 0)
            out.append(", конфликтов ").append(r.conflicts).append(" (копии сохранены)");
        } catch (SyncClient.SyncException e) {
          failed = true;
          if (out.length() > 0) out.append('\n');
          out.append(e.getMessage());
        }
      }
      if (changed) afterChanges(app, db, removed);
    } catch (RuntimeException e) {
      failed = true;
      out.append("Сбой синхронизации: ").append(e.getMessage());
    } finally {
      running = false;
    }
    lastChanged = changed;
    String text = out.length() == 0 ? "Нет подключённых устройств" : out.toString();
    SharedPreferences.Editor edit = prefs(app).edit().putString(STATUS, text);
    if (!failed) edit.putLong(LAST, System.currentTimeMillis());
    edit.apply();
    Runnable l = listener;
    if (l != null) l.run();
    return (failed ? "!" : "") + text;
  }

  /** Напоминания и календарь следуют за заметками, пришедшими с другого устройства. */
  private static void afterChanges(Context app, Store db, List<Long> removed) {
    for (long id : removed) Reminders.cancel(app, id);
    for (Note n : db.notes()) if (n.reminder > 0) Reminders.schedule(app, n);
    CalendarSync.runAsync(app);
  }

  /** Файлы картинок приложения для обмена. */
  private static final class AppFiles implements SyncClient.Files {
    private final Context context;
    private final Store store;

    AppFiles(Context context, Store store) {
      this.context = context;
      this.store = store;
    }

    @Override
    public List<String> missing() {
      List<String> out = new ArrayList<>();
      for (String name : store.imageNames()) {
        try {
          if (!ImageFiles.file(context, name).exists()) out.add(name);
        } catch (IOException ignored) {
        }
      }
      return out;
    }

    @Override
    public byte[] read(String name) {
      try {
        File f = ImageFiles.file(context, name);
        return f.exists() ? Files.readAllBytes(f.toPath()) : null;
      } catch (IOException e) {
        return null;
      }
    }

    @Override
    public void write(String name, byte[] bytes) {
      try {
        File target = ImageFiles.file(context, name);
        File temp = new File(target.getParentFile(), name + ".part");
        try (FileOutputStream out = new FileOutputStream(temp)) {
          out.write(bytes);
        }
        if (!temp.renameTo(target)) temp.delete();
      } catch (IOException ignored) {
      }
    }
  }
}
