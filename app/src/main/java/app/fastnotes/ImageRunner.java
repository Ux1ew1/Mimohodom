package app.fastnotes;

import android.content.Context;
import android.os.*;
import java.lang.ref.WeakReference;

public final class ImageRunner {
  public interface Listener {
    void imageFinished();
  }

  private static WeakReference<Listener> listener = new WeakReference<>(null);
  public static volatile boolean running;
  public static volatile long source, note;
  public static volatile String error = "";

  public static void listen(Listener value) {
    listener = new WeakReference<>(value);
  }

  public static synchronized boolean start(
      Context c, long origin, String key, String model, String image) {
    if (running) return false;
    running = true;
    source = origin;
    note = 0;
    error = "";
    Context app = c.getApplicationContext();
    new Thread(
            () -> {
              try (Store db = new Store(app)) {
                Vision.Result result =
                    ImageFiles.analyze(
                        app,
                        key,
                        model,
                        image,
                        "Создай краткую заметку и предложи напоминание, только если на картинке"
                            + " есть дата и время события.");
                long saved;
                db.getWritableDatabase().beginTransaction();
                try {
                  saved = db.saveVision(result, image);
                  db.getWritableDatabase().setTransactionSuccessful();
                } finally {
                  db.getWritableDatabase().endTransaction();
                }
                note = saved;
              } catch (Exception e) {
                error =
                    "Не удалось разобрать изображение. Проверьте интернет, баланс и поддержку"
                        + " изображений выбранной моделью. Запрос мог быть оплачен; повтор —"
                        + " вручную.";
              } finally {
                running = false;
                new Handler(Looper.getMainLooper())
                    .post(
                        () -> {
                          Listener l = listener.get();
                          if (l != null) l.imageFinished();
                        });
              }
            },
            "image-note")
        .start();
    return true;
  }
}
