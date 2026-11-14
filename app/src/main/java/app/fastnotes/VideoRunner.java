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
  /** Заметка, в которую объединили видео, и ошибка объединения (отдельно от ошибки сводки). */
  public static volatile long merged;

  public static volatile String mergeError = "";

  public static void listen(Listener value) {
    listener = new WeakReference<>(value);
  }

  /** Забирает результат последнего запуска и очищает его, чтобы не показывать дважды. */
  public static synchronized Outcome take() {
    Outcome o =
        new Outcome(new ArrayList<>(created), new ArrayList<>(existing), error, merged, mergeError);
    created.clear();
    existing.clear();
    error = "";
    merged = 0;
    mergeError = "";
    return o;
  }

  public static final class Outcome {
    public final List<Long> created, existing;
    public final String error, mergeError;
    public final long merged;

    Outcome(
        List<Long> created, List<Long> existing, String error, long merged, String mergeError) {
      this.created = created;
      this.existing = existing;
      this.error = error;
      this.merged = merged;
      this.mergeError = mergeError;
    }

    public boolean empty() {
      return created.isEmpty()
          && existing.isEmpty()
          && error.isEmpty()
          && merged == 0
          && mergeError.isEmpty();
    }
  }

  /**
   * Переносит сводку видео {@code src} в заметку {@code dst} разделом и обновляет общую сводку одним
   * текстовым запросом. Если запрос не удался, заметки остаются прежними.
   */
  public static synchronized boolean merge(
      Context c, long src, long dst, String key, String model) {
    if (running) return false;
    running = true;
    merged = 0;
    mergeError = "";
    Context app = c.getApplicationContext();
    new Thread(
            () -> {
              try (Store db = new Store(app)) {
                Note into = db.note(dst), from = db.note(src);
                if (into == null || from == null)
                  throw new java.io.IOException("Заметка уже удалена. Объединять нечего.");
                VideoMerge.Parsed a = VideoMerge.split(into), b = VideoMerge.split(from);
                List<VideoMerge.Section> all = new ArrayList<>(a.sections);
                all.addAll(b.sections);
                if (all.size() > VideoMerge.MAX_SECTIONS)
                  throw new java.io.IOException("В заметке уже слишком много видео.");
                VideoMerge.Overall overall =
                    VideoMerge.parseResponse(
                        Polza.request(
                            "chat/completions",
                            key,
                            VideoMerge.payload(model, all),
                            READ_TIMEOUT_MS));
                // Своё название заметки не трогаем: заменяем его, только если оно осталось от видео.
                boolean ours =
                    a.overall.isEmpty() && into.title.equals(a.sections.get(0).title);
                String title = ours && !overall.title.isEmpty() ? overall.title : into.title;
                if (!db.mergeVideo(
                    src,
                    from.updated,
                    dst,
                    into.updated,
                    title,
                    VideoMerge.compose(overall.text, all)))
                  throw new java.io.IOException(
                      "Заметка изменилась во время объединения. Заметки остались прежними.");
                merged = dst;
              } catch (Exception e) {
                mergeError = mergeMessage(e);
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
            "video-merge")
        .start();
    return true;
  }

  static String mergeMessage(Exception e) {
    if (e instanceof SocketTimeoutException)
      return "Модель не успела ответить. Запрос мог быть оплачен; повтор — вручную. Заметки остались"
          + " отдельными.";
    String text = e.getMessage() == null ? "" : e.getMessage();
    if (text.startsWith("Заметка")
        || text.startsWith("В заметке")
        || text.startsWith("Модель не вернула")
        || text.startsWith("Проверьте API-ключ")
        || text.startsWith("Недостаточно средств")
        || text.startsWith("Превышен лимит")
        || text.startsWith("Polza.AI вернул")) return text;
    return "Не удалось объединить заметки. Проверьте интернет и баланс. Запрос мог быть оплачен;"
        + " повтор — вручную. Заметки остались отдельными.";
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
    merged = 0;
    mergeError = "";
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
