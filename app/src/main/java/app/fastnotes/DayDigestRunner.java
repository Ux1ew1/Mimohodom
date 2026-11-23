package app.fastnotes;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Один явно запущенный облачный запрос сводки. Нет автоповтора и фонового расписания. */
public final class DayDigestRunner {
  public interface Listener {
    void digestChanged();
  }

  private static final ExecutorService worker = Executors.newSingleThreadExecutor();
  private static WeakReference<Listener> listener = new WeakReference<>(null);

  public static volatile boolean running;
  public static volatile long day;
  public static volatile String error = "", usage = "";
  public static volatile DayDigest.Input input;
  public static volatile DayDigest.Result result;

  private DayDigestRunner() {}

  public static void listen(Listener value) {
    listener = new WeakReference<>(value);
  }

  public static synchronized void clear() {
    if (running) return;
    result = null;
    input = null;
    error = "";
    usage = "";
    day = 0;
  }

  public static synchronized boolean start(
      Context context, String key, String model, DayDigest.Input snapshot) {
    if (running || snapshot == null || snapshot.isEmpty()) return false;
    running = true;
    day = snapshot.start;
    input = snapshot;
    result = null;
    error = "";
    usage = "";
    Context app = context.getApplicationContext();
    worker.execute(
        () -> {
          try (Store store = new Store(app, false)) {
            JSONObject response = store.complete(key, DayDigest.payload(model, snapshot));
            DayDigest.Result parsed = DayDigest.parse(DayDigest.responseText(response), snapshot);
            if (day == snapshot.start) result = parsed;
            JSONObject tokens = response.optJSONObject("usage");
            StringBuilder used = new StringBuilder("Ручной запрос · заметок: " + snapshot.entries.size());
            if (tokens != null && tokens.has("total_tokens"))
              used.append(" · ").append(tokens.optInt("total_tokens")).append(" токенов");
            if (tokens != null && tokens.has("cost_rub"))
              used.append(" · ").append(tokens.optString("cost_rub")).append(" ₽");
            usage = used.toString();
          } catch (java.net.SocketTimeoutException e) {
            error =
                "Время ожидания истекло. Ответ не получен; запрос мог быть обработан и оплачен."
                    + " Повтор — только вручную.";
          } catch (Exception e) {
            error =
                e instanceof java.io.IOException
                    ? e.getMessage()
                    : "Не удалось разобрать сводку. Повторите запрос вручную или выберите другую модель.";
          } finally {
            running = false;
            new Handler(Looper.getMainLooper())
                .post(
                    () -> {
                      Listener current = listener.get();
                      if (current != null) current.digestChanged();
                    });
          }
        });
    return true;
  }
}
