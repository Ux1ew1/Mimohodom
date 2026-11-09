package app.fastnotes;

import android.content.Context;
import android.os.*;
import java.lang.ref.WeakReference;
import java.util.concurrent.*;
import org.json.*;

/** A request survives Activity recreation; never retries a paid request automatically. */
public final class ChatRunner {
  public interface Listener {
    void changed();
  }

  private static final ExecutorService worker = Executors.newSingleThreadExecutor();
  private static WeakReference<Listener> listener = new WeakReference<>(null);
  public static volatile long running = 0, errorChat = 0, usageChat = 0;
  public static volatile String error = "", usage = "";
  public static final java.util.concurrent.atomic.AtomicLong createdNote =
      new java.util.concurrent.atomic.AtomicLong();

  public static void listen(Listener l) {
    listener = new WeakReference<>(l);
  }

  static void changed() {
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              Listener l = listener.get();
              if (l != null) l.changed();
            });
  }

  public static synchronized void send(
      Context context, long chat, String key, String model, String query, int maxTokens) {
    send(context, chat, key, model, query, maxTokens, "");
  }

  public static synchronized void send(
      Context context,
      long chat,
      String key,
      String model,
      String query,
      int maxTokens,
      String image) {
    if (running != 0) return;
    running = chat;
    error = "";
    errorChat = 0;
    usage = "";
    Context app = context.getApplicationContext();
    boolean local = LocalLlm.enabled(context);
    worker.execute(
        () -> {
          try (Store store = new Store(app, local)) {
            JSONArray history = store.messages(chat);
            if (history.length() > 0) {
              JSONObject last = history.getJSONObject(history.length() - 1);
              if (last.getString("role").equals("user") && last.getString("content").equals(query))
                history.remove(history.length() - 1);
              else store.message(chat, "user", query, new JSONArray());
            } else store.message(chat, "user", query, new JSONArray());
            changed();
            Polza.Answer answer;
            long created = 0;
            if (!image.isEmpty()) {
              Vision.Result result = ImageFiles.analyze(app, key, model, image, query);
              store.getWritableDatabase().beginTransaction();
              try {
                if (!store.chatExists(chat)) throw new java.io.IOException("Чат удалён");
                long id = store.saveVision(result, image);
                created = id;
                answer = new Polza.Answer();
                answer.text =
                    result.summary
                        + "\n\nСоздана заметка [[note:"
                        + id
                        + "]]"
                        + (result.reminder > 0 ? ". В ней доступно предложение напоминания." : ".");
                answer.sources =
                    new JSONArray().put(new JSONObject().put("id", id).put("title", result.title));
                store.message(chat, "assistant", answer.text, answer.sources);
                store.getWritableDatabase().setTransactionSuccessful();
              } finally {
                store.getWritableDatabase().endTransaction();
              }
              var prefs = app.getSharedPreferences("settings", 0);
              if (image.equals(prefs.getString("chatImage." + chat, "")))
                prefs.edit().remove("chatImage." + chat).apply();
              answer.usage = "Изображение проанализировано. Заметка сохранена.";
            } else answer = Polza.ask(store, key, model, query, history, maxTokens);
            if (image.isEmpty() && store.chatExists(chat)) {
              store.getWritableDatabase().beginTransaction();
              try {
                for (int i = 0; i < answer.metadataUpdates.length(); i++) {
                  JSONObject update = answer.metadataUpdates.getJSONObject(i);
                  store.applyMetadata(
                      update.getLong("id"),
                      update.getLong("revision"),
                      update.getJSONObject("metadata"));
                }
                if (answer.draft != null && Polza.wantsNote(query)) {
                  long id =
                      store.save(
                          0, answer.draft.getString("title"), answer.draft.getString("body"), 0);
                  store.applyMetadata(
                      id, store.note(id).updated, answer.draft.optJSONObject("metadata"));
                  created = id;
                  answer.sources.put(
                      new JSONObject().put("id", id).put("title", answer.draft.getString("title")));
                  answer.text += "\n\nЗаметка создана [[note:" + id + "]].";
                  answer.draft = null;
                }
                store.message(chat, "assistant", answer.text, answer.sources, answer.draft);
                store.getWritableDatabase().setTransactionSuccessful();
              } finally {
                store.getWritableDatabase().endTransaction();
              }
            }
            if (created > 0) createdNote.set(created);
            usage = answer.usage;
            usageChat = chat;
          } catch (java.net.SocketTimeoutException e) {
            error =
                "Время ожидания истекло. Ответ не получен; запрос мог быть обработан и оплачен."
                    + " Повтор — только вручную.";
            errorChat = chat;
          } catch (Exception e) {
            error =
                e instanceof java.io.IOException
                    ? e.getMessage()
                    : "Не удалось получить ответ. Проверьте подключение и выберите модель с"
                        + " поддержкой изображений, если прикреплена картинка.";
            errorChat = chat;
          } finally {
            running = 0;
            changed();
          }
        });
  }
}
