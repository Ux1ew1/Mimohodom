package app.fastnotes;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

public final class BackgroundAnalysis extends JobService {
  private static final int PERIODIC = 7201, ONCE = 7202, MANUAL = 7204;
  private static final AtomicBoolean RUNNING = new AtomicBoolean();
  private static final Object COMMIT_LOCK = new Object();

  private static final class Run {
    volatile boolean stopped;
    boolean manual;
  }

  private final Map<Integer, Run> runs = new HashMap<>();

  public static boolean isRunning() {
    return RUNNING.get();
  }

  public static void configure(Context context) {
    var scheduler = context.getSystemService(JobScheduler.class);
    if (LocalLlm.enabled(context)
        || !(context.getSharedPreferences("settings", 0).getBoolean("backgroundAi", false)
            || context.getSharedPreferences("settings", 0).getBoolean("managerAi", false))) {
      synchronized (COMMIT_LOCK) {
        scheduler.cancel(PERIODIC);
        scheduler.cancel(ONCE);
        scheduler.cancel(MANUAL);
      }
      return;
    }
    if (scheduler.getPendingJob(PERIODIC) == null)
      scheduler.schedule(
          new JobInfo.Builder(PERIODIC, new ComponentName(context, BackgroundAnalysis.class))
              .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
              .setRequiresBatteryNotLow(true)
              .setPersisted(true)
              .setPeriodic(6 * 60 * 60 * 1000L)
              .build());
  }

  public static boolean enqueue(Context context) {
    configure(context);
    if (LocalLlm.enabled(context)
        || !(context.getSharedPreferences("settings", 0).getBoolean("backgroundAi", false)
            || context.getSharedPreferences("settings", 0).getBoolean("managerAi", false)))
      return false;
    JobScheduler scheduler = context.getSystemService(JobScheduler.class);
    if (scheduler.getPendingJob(ONCE) != null) return true;
    return scheduler.schedule(
            new JobInfo.Builder(ONCE, new ComponentName(context, BackgroundAnalysis.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true)
                .setMinimumLatency(1000)
                .build())
        == JobScheduler.RESULT_SUCCESS;
  }

  public static boolean enqueueManual(Context context) {
    JobScheduler scheduler = context.getSystemService(JobScheduler.class);
    if (isRunning() || scheduler.getPendingJob(MANUAL) != null) return false;
    PersistableBundle extras = new PersistableBundle();
    extras.putBoolean("manual", true);
    return scheduler.schedule(
            new JobInfo.Builder(MANUAL, new ComponentName(context, BackgroundAnalysis.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(extras)
                .setMinimumLatency(1)
                .build())
        == JobScheduler.RESULT_SUCCESS;
  }

  @Override
  public boolean onStartJob(JobParameters params) {
    if (!RUNNING.compareAndSet(false, true)) return false;
    Run run = new Run();
    run.manual = params.getExtras().getBoolean("manual", false);
    runs.put(params.getJobId(), run);
    new Thread(
            () -> {
              try {
                work(run);
              } finally {
                RUNNING.set(false);
                new Handler(getMainLooper())
                    .post(
                        () -> {
                          runs.remove(params.getJobId());
                          if (!run.stopped) jobFinished(params, false);
                        });
              }
            },
            "note-analysis")
        .start();
    return true;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    synchronized (COMMIT_LOCK) {
      Run run = runs.get(params.getJobId());
      if (run != null) run.stopped = true;
    }
    return false; // Never automatically retry an ambiguous paid request.
  }

  private boolean enabled(Run run) {
    return !run.stopped
        && !LocalLlm.enabled(this)
        && (run.manual
            || getSharedPreferences("settings", 0).getBoolean("backgroundAi", false)
            || getSharedPreferences("settings", 0).getBoolean("managerAi", false));
  }

  private void work(Run run) {
    var prefs = getSharedPreferences("settings", 0);
    if (LocalLlm.enabled(this)) {
      status("Локальный GGUF: фоновая проверка пока приостановлена. Используйте текстовый чат.");
      return;
    }
    List<Note> claimed = new ArrayList<>();
    try (Store store = new Store(this)) {
      if (!enabled(run)) return;
      String key = Secrets.read(this), model = prefs.getString("model", "");
      if (key.isEmpty() || model.isEmpty()) {
        status("Для фоновой проверки сохраните ключ и модель.");
        return;
      }
      if (prefs.getBoolean("managerAi", false)) {
        ManagerJob.work(
            this,
            store,
            key,
            model,
            () -> enabled(run) && prefs.getBoolean("managerAi", false),
            COMMIT_LOCK);
        return;
      }
      String today = LocalDate.now().toString();
      int count =
          today.equals(prefs.getString("analysisDay", "")) ? prefs.getInt("analysisCount", 0) : 0;
      if (count >= 4) {
        status("Дневной лимит: 4 пакета. Следующая проверка — завтра.");
        return;
      }
      synchronized (COMMIT_LOCK) {
        if (!enabled(run)) return;
        for (Note n : store.pendingAnalysis()) if (store.claimAnalysis(n)) claimed.add(n);
        if (claimed.isEmpty()) {
          status("Нет новых заметок для проверки.");
          return;
        }
        // Persist before sending: process death must not cause an automatic duplicate charge.
        if (!prefs
            .edit()
            .putString("analysisDay", today)
            .putInt("analysisCount", count + 1)
            .commit()) return;
      }
      if (!enabled(run)) return;
      JSONObject response =
          Polza.request(
              "chat/completions",
              key,
              Analysis.payload(claimed, model, System.currentTimeMillis(), ZoneId.systemDefault()));
      String text =
          response
              .getJSONArray("choices")
              .getJSONObject(0)
              .getJSONObject("message")
              .getString("content");
      Map<Long, Analysis.Result> results =
          Analysis.parse(text, claimed, System.currentTimeMillis());
      long firstSuggestion = 0;
      int applied = 0;
      synchronized (COMMIT_LOCK) {
        if (!enabled(run)) return;
        for (Note n : claimed) {
          Analysis.Result result = results.get(n.id);
          if (result != null && store.applyAnalysis(n, result)) {
            applied++;
            if (result.reminder > System.currentTimeMillis() && firstSuggestion == 0)
              firstSuggestion = n.id;
          }
        }
        status("Проверено заметок: " + applied + ". Предложения доступны в заметках.");
        if (firstSuggestion != 0) notifySuggestion(firstSuggestion);
      }
    } catch (Exception e) {
      status("Проверка не завершена. Повторите вручную в настройках; запрос мог быть оплачен.");
    }
  }

  private void status(String value) {
    getSharedPreferences("settings", 0)
        .edit()
        .putString("analysisStatus", value)
        .putLong("analysisTime", System.currentTimeMillis())
        .apply();
  }

  private void notifySuggestion(long id) {
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return;
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel(
            "suggestions", "Предложения помощника", NotificationManager.IMPORTANCE_DEFAULT));
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            7203,
            new Intent(this, MainActivity.class).putExtra("note", id),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    manager.notify(
        7203,
        new Notification.Builder(this, "suggestions")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Предложить напоминание?")
            .setContentText(
                "Помощник нашёл дату и время. Откройте заметку, чтобы проверить и подтвердить.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build());
  }
}
