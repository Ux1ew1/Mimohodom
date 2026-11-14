package app.fastnotes;

import android.content.Context;
import android.os.*;
import java.lang.ref.WeakReference;
import java.net.SocketTimeoutException;
import java.util.*;

/**
 * Сводки видео YouTube: по одной заметке на ролик, по очереди. Модель сама смотрит видео по ссылке.
 * Автоматических повторов нет: запрос платный, повтор только вручную.
 */
public final class VideoRunner {
  public interface Listener {
    void videoFinished();
  }

  /** Читаемое ожидание ответа: длинное видео модель обрабатывает дольше обычного запроса. */
  private static final int READ_TIMEOUT_MS = 240_000;

  private static WeakReference<Listener> listener = new WeakReference<>(null);
  public static volatile boolean running;
  public static volatile int total, done;
  /** Созданные заметки и уже существовавшие (повторная ссылка) за последний запуск. */
  public static final List<Long> created = Collections.synchronizedList(new ArrayList<>());

  public static final List<Long> existing = Collections.synchronizedList(new ArrayList<>());
  public static volatile String error = "";

  public static void listen(Listener value) {
    listener = new WeakReference<>(value);
  }

  /** Забирает результат последнего запуска и очищает его, чтобы не показывать дважды. */
  public static synchronized Outcome take() {
    Outcome o = new Outcome(new ArrayList<>(created), new ArrayList<>(existing), error);
    created.clear();
    existing.clear();
    error = "";
    return o;
  }

  public static final class Outcome {
    public final List<Long> created, existing;
    public final String error;

    Outcome(List<Long> created, List<Long> existing, String error) {
      this.created = created;
      this.existing = existing;
      this.error = error;
    }

    public boolean empty() {
      return created.isEmpty() && existing.isEmpty() && error.isEmpty();
    }
  }

  public static synchronized boolean start(
      Context c, List<String> links, String key, String model) {
    if (running || links.isEmpty()) return false;
    running = true;
    total = links.size();
    done = 0;
    created.clear();
    existing.clear();
    error = "";
    Context app = c.getApplicationContext();
    List<String> queue = new ArrayList<>(links);
    new Thread(
            () -> {
              try (Store db = new Store(app)) {
                for (String link : queue) {
                  long old = db.noteIdContaining(link);
                  if (old > 0) {
                    existing.add(old);
                    done++;
                    continue;
                  }
                  try {
                    VideoAnalysis.Result result =
                        VideoAnalysis.parseResponse(
                            Polza.request(
                                "chat/completions",
                                key,
                                VideoAnalysis.payload(model, link),
                                READ_TIMEOUT_MS),
                            link);
                    long id = db.saveVideo(result);
                    created.add(id);
                    BackgroundAnalysis.enqueue(app);
                    done++;
                  } catch (Exception e) {
                    error = message(e, done, queue.size());
                    break;
                  }
                }
              } catch (Exception e) {
                error = "Не удалось сохранить сводку видео.";
              } finally {
                running = false;
                new Handler(Looper.getMainLooper())
                    .post(
                        () -> {
                          Listener l = listener.get();
                          if (l != null) l.videoFinished();
                        });
              }
            },
            "video-summary")
        .start();
    return true;
  }

  static String message(Exception e, int done, int total) {
    String rest = total - done > 1 ? " Остальные ссылки не обработаны." : "";
    if (e instanceof SocketTimeoutException)
      return "Модель не успела ответить: видео слишком длинное или сервис занят. Запрос мог быть"
          + " оплачен; повтор — вручную."
          + rest;
    String text = e.getMessage() == null ? "" : e.getMessage();
    boolean polza =
        text.startsWith("Проверьте API-ключ")
            || text.startsWith("Недостаточно средств")
            || text.startsWith("Превышен лимит")
            || text.startsWith("Polza.AI вернул")
            || text.startsWith("Модель не вернула");
    if (polza) return text + rest;
    return "Не удалось сделать сводку видео. Проверьте интернет, баланс и поддержку видео выбранной"
        + " моделью. Запрос мог быть оплачен; повтор — вручную."
        + rest;
  }
}
