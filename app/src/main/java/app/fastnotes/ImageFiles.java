package app.fastnotes;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.util.Base64;
import androidx.exifinterface.media.ExifInterface;
import java.io.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ImageFiles {
  public static File file(Context c, String name) throws IOException {
    File dir = new File(c.getFilesDir(), "images");
    if (!dir.exists() && !dir.mkdirs())
      throw new IOException("Не удалось создать папку изображений");
    File file = new File(dir, name);
    if (name.isEmpty() || !file.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile()))
      throw new IOException("Некорректное изображение");
    return file;
  }

  public static String importImage(Context c, Uri uri) throws Exception {
    File raw = File.createTempFile("image-", ".tmp", c.getCacheDir());
    Bitmap bitmap = null;
    try {
      try (InputStream in = c.getContentResolver().openInputStream(uri);
          OutputStream out = new FileOutputStream(raw)) {
        if (in == null) throw new IOException("Не удалось открыть изображение");
        byte[] buffer = new byte[8192];
        int size = 0, n;
        while ((n = in.read(buffer)) != -1) {
          size += n;
          if (size > 25_000_000) throw new IOException("Выберите изображение размером до 25 МБ");
          out.write(buffer, 0, n);
        }
      }
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(raw.getPath(), options);
      if (options.outWidth <= 0 || options.outHeight <= 0)
        throw new IOException("Не удалось прочитать изображение");
      options.inSampleSize = 1;
      while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1600)
        options.inSampleSize *= 2;
      options.inJustDecodeBounds = false;
      bitmap = BitmapFactory.decodeFile(raw.getPath(), options);
      if (bitmap == null) throw new IOException("Формат изображения не поддерживается");
      Matrix matrix = new Matrix();
      try {
        int orientation =
            new ExifInterface(raw.getPath())
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        switch (orientation) {
          case 2 -> matrix.setScale(-1, 1);
          case 3 -> matrix.setRotate(180);
          case 4 -> matrix.setScale(1, -1);
          case 5 -> {
            matrix.setRotate(90);
            matrix.postScale(-1, 1);
          }
          case 6 -> matrix.setRotate(90);
          case 7 -> {
            matrix.setRotate(270);
            matrix.postScale(-1, 1);
          }
          case 8 -> matrix.setRotate(270);
          default -> {}
        }
      } catch (IOException ignored) {
      }
      if (!matrix.isIdentity()) {
        Bitmap rotated =
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) {
          bitmap.recycle();
          bitmap = rotated;
        }
      }
      Bitmap flattened =
          Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(flattened);
      canvas.drawColor(Color.WHITE);
      canvas.drawBitmap(bitmap, 0, 0, null);
      bitmap.recycle();
      bitmap = flattened;
      String name = UUID.randomUUID() + ".jpg";
      File destination = file(c, name);
      try (OutputStream out = new FileOutputStream(destination)) {
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out))
          throw new IOException("Не удалось сохранить изображение");
      }
      if (destination.length() > 2_000_000) {
        destination.delete();
        throw new IOException("Изображение слишком большое после сжатия");
      }
      return name;
    } finally {
      if (bitmap != null) bitmap.recycle();
      raw.delete();
    }
  }

  /**
   * Поворачивает изображение на {@code quarterTurns} четвертей оборота по часовой стрелке и
   * обрезает по долям {левый, верхний, правый, нижний} уже повёрнутого кадра. Сохраняет новый
   * файл и возвращает его имя; прежний файл не трогает.
   */
  public static String edit(Context c, String name, int quarterTurns, float[] crop)
      throws Exception {
    Bitmap source = BitmapFactory.decodeFile(file(c, name).getPath());
    if (source == null) throw new IOException("Не удалось прочитать изображение");
    Bitmap turned = source, cropped = null;
    try {
      int turns = CropMath.turns(quarterTurns);
      if (turns != 0) {
        Matrix matrix = new Matrix();
        matrix.postRotate(90f * turns);
        turned =
            Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
      }
      Bitmap result = turned;
      if (!CropMath.isFull(crop)) {
        int[] box = CropMath.pixels(turned.getWidth(), turned.getHeight(), crop);
        cropped = Bitmap.createBitmap(turned, box[0], box[1], box[2], box[3]);
        result = cropped;
      }
      String edited = UUID.randomUUID() + ".jpg";
      File destination = file(c, edited);
      boolean saved = false;
      for (int quality : new int[] {90, 80, 65}) {
        try (OutputStream out = new FileOutputStream(destination)) {
          if (!result.compress(Bitmap.CompressFormat.JPEG, quality, out))
            throw new IOException("Не удалось сохранить изображение");
        }
        if (destination.length() <= 2_000_000) {
          saved = true;
          break;
        }
      }
      if (!saved) {
        destination.delete();
        throw new IOException("Изображение слишком большое после сжатия");
      }
      return edited;
    } finally {
      if (cropped != null) cropped.recycle();
      if (turned != source) turned.recycle();
      source.recycle();
    }
  }

  /** Файлы, нужные заметкам, черновикам редактора и чата. */
  public static synchronized Set<String> referenced(Context c) {
    Set<String> out = new HashSet<>();
    try (Store store = new Store(c)) {
      out.addAll(store.imageNames());
    }
    for (Map.Entry<String, ?> e : c.getSharedPreferences("settings", 0).getAll().entrySet()) {
      String key = e.getKey();
      boolean draft = key.startsWith("draft.") && key.endsWith(".image");
      if ((draft || key.startsWith("chatImage.")) && e.getValue() instanceof String value)
        if (!value.isEmpty()) out.add(value);
    }
    return out;
  }

  private static boolean busy() {
    return ImageRunner.running || ChatRunner.running != 0;
  }

  /** Удаляет файлы, на которые никто не ссылается и которым больше {@code graceMs}. */
  public static synchronized int cleanup(Context c, long graceMs) {
    if (busy()) return 0;
    File dir = new File(c.getFilesDir(), "images");
    File[] listed = dir.listFiles();
    if (listed == null) return 0;
    Map<String, Long> modified = new HashMap<>();
    for (File f : listed) modified.put(f.getName(), f.lastModified());
    int removed = 0;
    for (String name :
        ImageCleanup.orphans(modified, referenced(c), System.currentTimeMillis(), graceMs))
      if (new File(dir, name).delete()) removed++;
    if (removed > 0) ImageThumbs.clear();
    return removed;
  }

  /** Удаляет файл, если он больше нигде не нужен: после удаления заметки или замены картинки. */
  public static synchronized void release(Context c, String name) {
    if (!ImageCleanup.managed(name) || busy()) return;
    try {
      if (!referenced(c).contains(name) && file(c, name).delete()) ImageThumbs.clear();
    } catch (IOException ignored) {
    }
  }

  /** Число файлов и их размер в байтах. */
  public static long[] usage(Context c) {
    File[] listed = new File(c.getFilesDir(), "images").listFiles();
    long count = 0, bytes = 0;
    if (listed != null)
      for (File f : listed)
        if (ImageCleanup.managed(f.getName())) {
          count++;
          bytes += f.length();
        }
    return new long[] {count, bytes};
  }

  public static String base64(Context c, String name) throws IOException {
    File file = file(c, name);
    if (file.length() > 2_000_000) throw new IOException("Слишком большое изображение");
    try (InputStream in = new FileInputStream(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
      return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }
  }

  public static Vision.Result analyze(
      Context c, String key, String model, String image, String question) throws Exception {
    if (LocalLlm.enabled(c))
      throw new IOException(
          "Локальная модель пока поддерживает только текст. Для картинок выберите Polza.AI в"
              + " настройках.");
    org.json.JSONObject response =
        Polza.request(
            "chat/completions",
            key,
            Vision.payload(
                model,
                base64(c, image),
                question,
                System.currentTimeMillis(),
                java.time.ZoneId.systemDefault()));
    return Vision.parse(
        response
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content"),
        System.currentTimeMillis());
  }
}
