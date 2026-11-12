package app.fastnotes;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.util.Base64;
import androidx.exifinterface.media.ExifInterface;
import java.io.*;
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
