package app.fastnotes;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.genai.common.DownloadCallback;
import com.google.mlkit.genai.common.FeatureStatus;
import com.google.mlkit.genai.common.GenAiException;
import com.google.mlkit.genai.prompt.Candidate;
import com.google.mlkit.genai.prompt.Content;
import com.google.mlkit.genai.prompt.Feature;
import com.google.mlkit.genai.prompt.GenerateContentRequest;
import com.google.mlkit.genai.prompt.GenerateContentResponse;
import com.google.mlkit.genai.prompt.Generation;
import com.google.mlkit.genai.prompt.SystemInstruction;
import com.google.mlkit.genai.prompt.java.GenerativeModelFutures;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Gemini Nano через Google AICore (ML Kit GenAI Prompt API). Работает на устройстве, ключ и сеть не
 * нужны. Модель отвечает только пока приложение на экране: AICore блокирует фоновые запросы.
 */
final class AiCore {
  static final String MODEL_ID = "aicore:gemini-nano";
  private static final int MAX_OUTPUT = 256;
  private static final long QUICK_SECONDS = 30, GENERATE_SECONDS = 180;

  private static GenerativeModelFutures futures;
  private static volatile ListenableFuture<?> running;
  private static volatile boolean cancelled;

  private AiCore() {}

  private static synchronized GenerativeModelFutures model() {
    if (futures == null) futures = GenerativeModelFutures.from(Generation.INSTANCE.getClient());
    return futures;
  }

  static void cancel() {
    cancelled = true;
    ListenableFuture<?> current = running;
    if (current != null) current.cancel(true);
  }

  private static <T> T await(ListenableFuture<T> future, long seconds) throws Exception {
    try {
      return future.get(seconds, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      if (cause instanceof Exception) throw (Exception) cause;
      throw e;
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new IOException("Gemini Nano не ответила вовремя. Попробуйте ещё раз.", e);
    }
  }

  /** Возвращает FeatureStatus: UNAVAILABLE, DOWNLOADABLE, DOWNLOADING или AVAILABLE. */
  static int status() throws IOException {
    try {
      return await(model().checkStatus(), QUICK_SECONDS);
    } catch (Exception e) {
      throw friendly(e);
    }
  }

  static String describe(int status) {
    switch (status) {
      case FeatureStatus.AVAILABLE:
        return "Gemini Nano уже скачана и готова к работе";
      case FeatureStatus.DOWNLOADABLE:
        return "Gemini Nano поддерживается, но модель ещё не скачана";
      case FeatureStatus.DOWNLOADING:
        return "Gemini Nano скачивается. Подождите и проверьте снова";
      default:
        return "Gemini Nano недоступна на этом устройстве или в этой версии системы";
    }
  }

  interface Progress {
    void report(String text);
  }

  /** Скачивает модель через AICore. Блокирует поток до завершения: вызывайте не из UI. */
  static void download(Progress progress) throws IOException {
    AtomicLong total = new AtomicLong();
    try {
      await(
          model()
              .download(
                  new DownloadCallback() {
                    @Override
                    public void onDownloadStarted(long bytesToDownload) {
                      total.set(bytesToDownload);
                      progress.report(
                          "Скачивание Gemini Nano: всего " + bytesToDownload / 1024 / 1024 + " МБ");
                    }

                    @Override
                    public void onDownloadProgress(long totalBytesDownloaded) {
                      long all = total.get();
                      progress.report(
                          all > 0
                              ? "Скачано Gemini Nano: "
                                  + Math.min(100, totalBytesDownloaded * 100 / all)
                                  + "% ("
                                  + totalBytesDownloaded / 1024 / 1024
                                  + " из "
                                  + all / 1024 / 1024
                                  + " МБ)"
                              : "Скачано " + totalBytesDownloaded / 1024 / 1024 + " МБ");
                    }
                  }),
          30 * 60);
    } catch (Exception e) {
      throw friendly(e);
    }
  }

  static JSONObject complete(JSONArray messages, int requestedTokens, LocalLlm.Stage stage)
      throws Exception {
    cancelled = false;
    try {
      AiCorePrompt prompt = AiCorePrompt.from(messages);
      GenerativeModelFutures model = model();
      stage.report(1);
      int status = await(model.checkStatus(), QUICK_SECONDS);
      if (status != FeatureStatus.AVAILABLE) throw new IOException(describe(status));
      boolean separateSystem =
          await(model.getAvailableFeatures(), QUICK_SECONDS).contains(Feature.SYSTEM_PROMPT);
      int limit = await(model.getTokenLimit(), QUICK_SECONDS);
      int output = Math.min(MAX_OUTPUT, Math.max(64, requestedTokens));

      stage.report(2);
      GenerateContentRequest request = null;
      for (int attempt = 0; attempt < 8 && request == null; attempt++) {
        GenerateContentRequest candidate = request(prompt, separateSystem, output);
        int tokens = await(model.countTokens(candidate), QUICK_SECONDS).getTotalTokens();
        if (tokens + output <= limit) {
          request = candidate;
          break;
        }
        AiCorePrompt smaller = prompt.withoutOldestExchange();
        if (smaller == null) smaller = prompt.withSystemScaled(0.75);
        if (smaller == null) break;
        prompt = smaller;
      }
      if (request == null)
        throw new IOException("Запрос слишком большой для Gemini Nano. Задайте вопрос короче.");
      if (cancelled) throw new IOException("Генерация остановлена");

      stage.report(3);
      ListenableFuture<GenerateContentResponse> pending = model.generateContent(request);
      running = pending;
      GenerateContentResponse response;
      try {
        response = await(pending, GENERATE_SECONDS);
      } catch (CancellationException e) {
        throw new IOException("Генерация остановлена", e);
      } finally {
        running = null;
      }
      List<Candidate> candidates = response.getCandidates();
      if (candidates.isEmpty() || candidates.get(0).getText() == null)
        throw new IOException("Gemini Nano вернула пустой ответ. Попробуйте переформулировать.");
      String answer = candidates.get(0).getText().trim();
      if (answer.isEmpty())
        throw new IOException("Gemini Nano вернула пустой ответ. Попробуйте переформулировать.");
      Integer reason = candidates.get(0).getFinishReason();
      return new JSONObject()
          .put(
              "choices",
              new JSONArray()
                  .put(
                      new JSONObject()
                          .put("message", new JSONObject().put("content", answer))
                          .put(
                              "finish_reason",
                              reason != null && reason == Candidate.FinishReason.MAX_TOKENS
                                  ? "length"
                                  : "stop")));
    } catch (CancellationException e) {
      throw new IOException("Генерация остановлена", e);
    } catch (Exception e) {
      if (cancelled) throw new IOException("Генерация остановлена", e);
      throw friendly(e);
    } finally {
      running = null;
    }
  }

  private static GenerateContentRequest request(
      AiCorePrompt prompt, boolean separateSystem, int output) {
    List<AiCorePrompt.Turn> turns = separateSystem ? prompt.turns : prompt.turnsWithInlineSystem();
    List<Content> contents = new ArrayList<>();
    for (AiCorePrompt.Turn turn : turns)
      contents.add(
          new Content.Builder()
              .role(turn.model ? Content.Role.MODEL : Content.Role.USER)
              .text(turn.text)
              .build());
    GenerateContentRequest.Builder builder = new GenerateContentRequest.Builder(contents);
    if (separateSystem && !prompt.system.isEmpty())
      builder.setSystemInstruction(new SystemInstruction(prompt.system));
    builder.setMaxOutputTokens(output);
    builder.setTemperature(0.4f);
    builder.setCandidateCount(1);
    return builder.build();
  }

  /** Переводит коды AICore в понятные пользователю сообщения. */
  static IOException friendly(Exception e) {
    if (e instanceof IOException) return (IOException) e;
    if (e instanceof GenAiException) {
      GenAiException ai = (GenAiException) e;
      String text;
      switch (ai.getErrorCode()) {
        case GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED:
          text = "Gemini Nano отвечает только при открытом приложении. Вернитесь в Мимоходом.";
          break;
        case GenAiException.ErrorCode.BUSY:
          text = "Gemini Nano занята. Подождите немного и повторите.";
          break;
        case GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED:
          text = "Исчерпана квота AICore для приложения. Попробуйте позже.";
          break;
        case GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE:
          text = "Не хватает места для модели Gemini Nano. Освободите память телефона.";
          break;
        case GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE:
        case GenAiException.ErrorCode.AICORE_INCOMPATIBLE:
          text = "Обновите Google AICore в Google Play и систему телефона.";
          break;
        case GenAiException.ErrorCode.NOT_AVAILABLE:
        case GenAiException.ErrorCode.NOT_SUPPORTED:
          text = "Gemini Nano недоступна на этом устройстве.";
          break;
        case GenAiException.ErrorCode.REQUEST_TOO_LARGE:
          text = "Запрос слишком большой для Gemini Nano. Задайте вопрос короче.";
          break;
        case GenAiException.ErrorCode.CANCELLED:
          text = "Генерация остановлена";
          break;
        default:
          text = "Ошибка Gemini Nano (код " + ai.getErrorCode() + ")";
      }
      return new IOException(text, e);
    }
    return new IOException(
        e.getMessage() == null ? "Не удалось обратиться к Gemini Nano" : e.getMessage(), e);
  }
}
