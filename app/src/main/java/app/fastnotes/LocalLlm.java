package app.fastnotes;

import android.content.*;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

public final class LocalLlm {
  private static final AtomicBoolean BUSY = new AtomicBoolean();
  private static volatile boolean loaded;
  private static volatile String stage = "";
  private static volatile boolean stageAicore;

  /** Приёмник этапов генерации для движков, которые сообщают о ходе работы. */
  interface Stage {
    void report(int step);
  }

  static String stage() {
    return stage;
  }

  private static void reportStage(int step) {
    stage = switch (step) {
      case 1 -> stageAicore ? "Проверяю Gemini Nano…" : "Загружаю модель GGUF…";
      case 2 -> "Обрабатываю вопрос и контекст…";
      case 3 -> "Генерирую ответ на телефоне…";
      default -> "";
    };
    ChatRunner.changed();
  }

  public static boolean enabled(Context c) {
    return c.getSharedPreferences("settings", 0).getBoolean("localLlm", false);
  }

  /**
   * Фоновые проверки, менеджер и выжимки идут через Polza.AI, когда локальный режим выключен
   * или выбран Gemini Nano: AICore не отвечает фоновым запросам. GGUF по-прежнему запрещает
   * облачные запросы.
   */
  public static boolean cloudBackground(Context c) {
    return !enabled(c) || aicore(c);
  }

  /** Выбран Google AICore (Gemini Nano) вместо импортированной GGUF-модели. */
  public static boolean aicore(Context c) {
    return "aicore".equals(c.getSharedPreferences("settings", 0).getString("localEngine", "gguf"));
  }

  /** Локальный движок можно запускать: для AICore модель проверяется в самом запросе. */
  public static boolean ready(Context c) {
    return aicore(c) || hasModel(c);
  }

  /** Идентификатор модели для кеша ответов: меняется вместе с движком и файлом GGUF. */
  public static String modelId(Context c) {
    return aicore(c)
        ? AiCore.MODEL_ID
        : "local-gguf:" + model(c).lastModified() + ":" + model(c).length();
  }

  public static boolean busy() {
    return BUSY.get();
  }

  public static File model(Context c) {
    return new File(c.getFilesDir(), "models/model.gguf");
  }

  public static boolean hasModel(Context c) {
    return model(c).isFile() && model(c).length() > 8;
  }

  private static synchronized void load() {
    if (!loaded) {
      System.loadLibrary("fastnotes_llm");
      loaded = true;
    }
  }

  public static void cancel() {
    if (loaded) cancelNative();
    if (stageAicore) AiCore.cancel();
  }

  private static native void cancelNative();

  private static native byte[] generateNative(
      String file, String[] roles, byte[][] texts, int maxTokens, int context, int threads);

  public static void importModel(Context c, Uri uri) throws Exception {
    if (!BUSY.compareAndSet(false, true))
      throw new IOException("Дождитесь завершения локального ответа");
    File target = model(c), part = new File(c.getFilesDir(), "models/import.part");
    try {
      if (!target.getParentFile().exists() && !target.getParentFile().mkdirs())
        throw new IOException("Не удалось создать папку моделей");
      android.os.storage.StorageManager storage =
          c.getSystemService(android.os.storage.StorageManager.class);
      long available = storage.getAllocatableBytes(storage.getUuidForPath(c.getFilesDir()));
      try (InputStream in = c.getContentResolver().openInputStream(uri);
          OutputStream out = new FileOutputStream(part)) {
        if (in == null) throw new IOException("Не удалось открыть файл");
        byte[] header = new byte[8];
        int position = 0, n;
        while (position < header.length
            && (n = in.read(header, position, header.length - position)) != -1) position += n;
        GgufHeader.validate(header, position);
        out.write(header);
        long total = 8;
        byte[] buffer = new byte[1024 * 1024];
        while ((n = in.read(buffer)) != -1) {
          total += n;
          if (total > 2L * 1024 * 1024 * 1024)
            throw new IOException("Экспериментальный лимит модели — 2 ГБ");
          if (total + 64L * 1024 * 1024 > available)
            throw new IOException("Недостаточно места для GGUF");
          out.write(buffer, 0, n);
        }
        if (total < 24) throw new IOException("GGUF-файл обрезан");
      }
      java.nio.file.Files.move(
          part.toPath(),
          target.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } finally {
      part.delete();
      BUSY.set(false);
    }
  }

  public static JSONObject complete(Context c, JSONObject payload) throws Exception {
    boolean aicore = aicore(c);
    if (!aicore && !hasModel(c)) throw new IOException("Выберите GGUF-модель в настройках");
    if (!BUSY.compareAndSet(false, true))
      throw new IOException("Локальный движок занят. Дождитесь завершения операции.");
    stageAicore = aicore;
    try {
      if (aicore)
        return AiCore.complete(
            payload.getJSONArray("messages"),
            payload.optInt("max_tokens", 256),
            LocalLlm::reportStage);
      android.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo();
      c.getSystemService(android.app.ActivityManager.class).getMemoryInfo(memory);
      if (memory.lowMemory || memory.availMem < model(c).length() + 256L * 1024 * 1024)
        throw new IOException(
            "Сейчас недостаточно свободной памяти. Закройте другие приложения или выберите меньшую"
                + " GGUF.");
      try {
        load();
      } catch (UnsatisfiedLinkError e) {
        throw new IOException("Локальный движок недоступен на этом устройстве", e);
      }
      JSONArray messages = payload.getJSONArray("messages");
      List<String> roles = new ArrayList<>();
      List<byte[]> texts = new ArrayList<>();
      for (int i = 0; i < messages.length(); i++) {
        JSONObject item = messages.getJSONObject(i);
        Object content = item.get("content");
        if (!(content instanceof String))
          throw new IOException("Локальный GGUF-режим пока поддерживает только текст");
        roles.add(item.getString("role"));
        texts.add(((String) content).getBytes(StandardCharsets.UTF_8));
      }
      int maximum = Math.min(256, Math.max(64, payload.optInt("max_tokens", 256)));
      byte[] bytes =
          generateNative(
              model(c).getAbsolutePath(),
              roles.toArray(new String[0]),
              texts.toArray(new byte[0][]),
              maximum,
              4096,
              Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));
      String answer = new String(bytes, StandardCharsets.UTF_8).trim();
      if (answer.isEmpty())
        throw new IOException(
            "Локальная модель вернула пустой ответ. Попробуйте другую instruct-модель.");
      return new JSONObject()
          .put(
              "choices",
              new JSONArray()
                  .put(
                      new JSONObject()
                          .put("message", new JSONObject().put("content", answer))
                          .put("finish_reason", "stop")));
    } catch (UnsatisfiedLinkError e) {
      throw new IOException("Не удалось запустить нативный движок GGUF", e);
    } catch (IllegalStateException e) {
      throw new IOException(e.getMessage(), e);
    } finally {
      stage = "";
      BUSY.set(false);
    }
  }
}
