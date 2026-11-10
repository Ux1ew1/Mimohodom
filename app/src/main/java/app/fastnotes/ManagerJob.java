package app.fastnotes;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

final class ManagerJob {
  static void work(
      Context context,
      Store store,
      String key,
      String model,
      BooleanSupplier enabled,
      Object lock,
      boolean manual)
      throws Exception {
    var prefs = context.getSharedPreferences("settings", 0);
    boolean perNote = manual || prefs.getBoolean("backgroundAi", false);
    ManagerStore manager = new ManagerStore(store);
    List<Note> batch = manager.batch(manual);
    JSONObject groupSnapshot = manager.groupSnapshot(batch);
    if (batch.isEmpty()) {
      status(
          context,
          store.totalNotes() == 0
              ? "Нет заметок для проверки."
              : manager.failedCount() > 0
                  ? "Все доступные записи ожидают ручного повтора после ошибки. Нажмите «Проверить"
                        + " заметки»."
                  : "Нет доступных заметок для проверки. Стопки и их исходники уже просмотрены и"
                        + " исключены из анализа.");
      return;
    }
    long runId;
    synchronized (lock) {
      if (!enabled.getAsBoolean()) return;
      batch.removeIf(n -> manager.top(n.id) != 0);
      if (batch.isEmpty()) {
        status(context, "Нет доступных заметок для проверки. Все выбранные записи уже в стопках.");
        return;
      }
      if (!ReviewBudget.reserve(context)) {
        status(context, "Дневной лимит: 4 пакета. Следующая проверка — завтра.");
        return;
      }
      runId = manager.claim(batch);
    }
    if (!enabled.getAsBoolean()) return;
    status(
        context,
        "Проверяем "
            + batch.size()
            + " из "
            + store.totalNotes()
            + " заметок: "
            + (perNote ? "названия, напоминания, связи и объединения…" : "связи и объединения…"));
    JSONObject payload =
        perNote
            ? ReviewAnalysis.payload(
                batch, model, System.currentTimeMillis(), ZoneId.systemDefault())
            : ManagerAnalysis.payload(batch, model);
    JSONObject response = Polza.request("chat/completions", key, payload);
    List<JSONObject> proposals;
    Map<Long, Analysis.Result> results = Collections.emptyMap();
    if (perNote) {
      ReviewAnalysis.Result parsed =
          ReviewAnalysis.parse(response, batch, System.currentTimeMillis());
      proposals = parsed.proposals;
      results = parsed.notes;
    } else {
      JSONObject choice = response.getJSONArray("choices").getJSONObject(0);
      if ("length".equals(choice.optString("finish_reason")))
        throw new ReviewAnalysis.Failure(
            "Ответ модели оборвался по лимиту 1200 токенов. Попробуйте другую модель или более"
                + " короткие заметки.");
      proposals =
          ManagerAnalysis.parse(choice.getJSONObject("message").getString("content"), batch);
    }
    proposals = TopicSelection.proposals(batch, proposals);
    for (JSONObject p : proposals) ManagerStore.attachGroups(p, groupSnapshot);
    synchronized (lock) {
      if (!enabled.getAsBoolean()) return;
      ManagerStore.ReviewOutcome outcome = manager.finishReview(runId, batch, proposals, results);
      int merges = 0;
      for (JSONObject p : manager.pending()) if (p.optString("type").equals("merge")) merges++;
      int failed = manager.failedCount();
      status(
          context,
          "Просмотрено записей: "
              + batch.size()
              + " из "
              + store.totalNotes()
              + ". Переименовано: "
              + outcome.renamed
              + ". Новых предложений напоминаний: "
              + outcome.reminders
              + ". Новых предложений менеджера: "
              + outcome.added
              + ". Объединений к просмотру: "
              + merges
              + "."
              + (batch.size() < 2
                  ? " Для объединения нужны минимум две доступные заметки."
                  : " Объединения и напоминания требуют подтверждения.")
              + (failed > 0
                  ? " После ошибки пропущено: "
                      + failed
                      + ". «Проверить сейчас» включает их в ручной повтор."
                  : "")
              + (batch.size() < store.totalNotes()
                  ? " Проверена часть коллекции; за запрос — до 8 записей."
                  : "")
              + (perNote && outcome.reviewed < batch.size()
                  ? " Часть записей изменилась во время запроса; их правки сохранены."
                  : ""));
      if (outcome.added + outcome.reminders > 0) notify(context, outcome.added + outcome.reminders);
    }
  }

  private static void status(Context context, String text) {
    ReviewLog.record(context, text);
  }

  private static void notify(Context context, int count) {
    var prefs = context.getSharedPreferences("settings", 0);
    long now = System.currentTimeMillis();
    if (now - prefs.getLong("managerNotified", 0) < 86400000L) return;
    if (Build.VERSION.SDK_INT >= 33
        && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return;
    NotificationManager nm = context.getSystemService(NotificationManager.class);
    if (!nm.areNotificationsEnabled()) return;
    nm.createNotificationChannel(
        new NotificationChannel(
            "manager", "Обзор заметок", NotificationManager.IMPORTANCE_DEFAULT));
    PendingIntent open =
        PendingIntent.getActivity(
            context,
            7205,
            new Intent(context, MainActivity.class).putExtra("manager", true),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    nm.notify(
        7205,
        new Notification.Builder(context, "manager")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Предложения помощника")
            .setContentText("Новых предложений: " + count)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build());
    prefs.edit().putLong("managerNotified", now).apply();
  }
}
