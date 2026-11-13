package app.fastnotes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Фоновая загрузка уменьшенных изображений с кэшем в памяти: интерфейс не ждёт диск. */
public final class ImageThumbs {
  private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final LruCache<String, Bitmap> CACHE =
      new LruCache<>((int) (Runtime.getRuntime().maxMemory() / 1024 / 16)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
          return value.getByteCount() / 1024;
        }
      };

  private ImageThumbs() {}

  private static String key(String name, int maxPx) {
    return name + "@" + maxPx;
  }

  /** Подставляет изображение в {@code view}; устаревший ответ после смены картинки игнорируется. */
  public static void load(Context context, ImageView view, String name, int maxPx) {
    String key = key(name, maxPx);
    view.setTag(key);
    Bitmap cached = CACHE.get(key);
    if (cached != null) {
      view.setImageBitmap(cached);
      return;
    }
    view.setImageDrawable(null);
    request(
        context,
        name,
        maxPx,
        bitmap -> {
          if (key.equals(view.getTag())) view.setImageBitmap(bitmap);
        });
  }

  /** Декодирует в фоне и вызывает {@code done} на главном потоке; при ошибке не вызывает. */
  public static void request(Context context, String name, int maxPx, Consumer<Bitmap> done) {
    Context app = context.getApplicationContext();
    String key = key(name, maxPx);
    Bitmap cached = CACHE.get(key);
    if (cached != null) {
      MAIN.post(() -> done.accept(cached));
      return;
    }
    POOL.execute(
        () -> {
          try {
            String path = ImageFiles.file(app, name).getPath();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2)
                >= maxPx) options.inSampleSize *= 2;
            Bitmap bitmap = BitmapFactory.decodeFile(path, options);
            if (bitmap == null) return;
            CACHE.put(key, bitmap);
            MAIN.post(() -> done.accept(bitmap));
          } catch (Exception | OutOfMemoryError ignored) {
          }
        });
  }

  /** Сбрасывает кэш при очистке файлов: устаревшие имена больше не нужны. */
  public static void clear() {
    CACHE.evictAll();
  }
}
