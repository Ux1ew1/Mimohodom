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
      Context context, Store store, String key, String model, BooleanSupplier enabled, Object lock)
      throws Exception {
    var prefs = context.getSharedPreferences("settings", 0);
    ManagerStore manager = new ManagerStore(store);
    List<Note> batch = manager.batch();
    JSONObject groupSnapshot = manager.groupSnapshot(batch);
    if (batch.isEmpty()) {
      status(context, "Нет доступных записей. Неудачные проверки можно повторить вручную.");
      return;
    }
    long runId;
    synchronized (lock) {
      if (!enabled.getAsBoolean()) return;
      String today = LocalDate.now().toString();
      int count =
          today.equals(prefs.getString("analysisDay", "")) ? prefs.getInt("analysisCount", 0) : 0;
      if (count >= 4) {
        status(context, "Дневной лимит: 4 пакета. Следующая проверка — завтра.");
        return;
      }
      if (!prefs.edit().putString("analysisDay", today).putInt("analysisCount", count + 1).commit())
        return;
      runId = manager.claim(batch);
    }
    if (!enabled.getAsBoolean()) return;
    JSONObject payload = ManagerAnalysis.payload(batch, model);
    if (prefs.getBoolean("backgroundAi", false)) {
      String extra =
          Analysis.payload(batch, model, System.currentTimeMillis(), ZoneId.systemDefault())
              .getJSONArray("messages")
              .getJSONObject(0)
              .getString("content");
      JSONObject instruction = payload.getJSONArray("messages").getJSONObject(0);
      instruction.put(
          "content",
          instruction.getString("content")
              + " Дополнительно добавь в тот же JSON поле notes. "
              + extra
              + " Итоговый ответ обязательно содержит ОБА массива: suggestions и notes."
              + " needs_title=false: заголовки не меняй.");
      // Include the dates needed to interpret the optional reminder suggestions.
      JSONArray data =
          new JSONArray(payload.getJSONArray("messages").getJSONObject(1).getString("content"));
      for (int i = 0; i < batch.size(); i++) {
        Note n = batch.get(i);
        data.getJSONObject(i)
            .put(
                "created",
                Instant.ofEpochMilli(n.created).atZone(ZoneId.systemDefault()).toString())
            .put(
                "updated",
                Instant.ofEpochMilli(n.updated).atZone(ZoneId.systemDefault()).toString())
            .put("needs_title", false)
            .put("has_reminder", n.reminder > 0);
      }
      payload.getJSONArray("messages").getJSONObject(1).put("content", data.toString());
    }
    JSONObject response = Polza.request("chat/completions", key, payload);
    String text =
        response
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content");
    List<JSONObject> proposals = ManagerAnalysis.parse(text, batch);
    for (JSONObject p : proposals) {
      JSONObject groups = new JSONObject();
      JSONArray sources = p.getJSONArray("sources");
      StringBuilder identity = new StringBuilder(p.getString("identity"));
      for (int i = 0; i < sources.length(); i++) {
        String id = "" + sources.getJSONObject(i).getLong("id");
        String revision = groupSnapshot.getString(id);
        groups.put(id, revision);
        identity.append('|').append(revision.length()).append(':').append(revision);
      }
      p.put("groups", groups).put("identity", identity.toString());
    }
    synchronized (lock) {
      if (!enabled.getAsBoolean()) return;
      int added = manager.finish(runId, batch, proposals);
      if (prefs.getBoolean("backgroundAi", false)) {
        try {
          Map<Long, Analysis.Result> results =
              Analysis.parse(text, batch, System.currentTimeMillis());
          for (Note n : batch) {
            Analysis.Result result = results.get(n.id);
            if (result != null) {
              result.title = "";
              store.applyAnalysis(n, result);
            }
          }
        } catch (Exception ignored) {
          /* Optional per-note output does not invalidate manager output. */
        }
      }
      status(
          context,
          "Просмотрено записей: "
              + batch.size()
              + ". Новых предложений: "
              + added
              + ". Это часть коллекции; обзор продолжается постепенно.");
      if (added > 0) notify(context, added);
    }
  }

  private static void status(Context context, String text) {
    context
        .getSharedPreferences("settings", 0)
        .edit()
        .putString("analysisStatus", text)
        .putLong("analysisTime", System.currentTimeMillis())
        .apply();
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
