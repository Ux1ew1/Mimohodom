package app.fastnotes;

import android.content.Context;
import android.os.*;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
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
    send(context, chat, key, model, query, maxTokens, image, "");
  }

  /** Текст запроса, когда пользователь приложил картинки и ничего не написал. */
  public static final String NOTE_QUERY = "Создай краткую заметку по изображению";

  /**
   * {@code image} — картинки, приложенные к этому сообщению (имена через запятую). Они остаются в
   * чате: в каждый следующий запрос уходят последние картинки чата, так что присылать их заново
   * не нужно. {@code imageModel} — модель для запросов с картинками; пусто — обычная модель.
   */
  public static synchronized void send(
      Context context,
      long chat,
      String key,
      String model,
      String query,
      int maxTokens,
      String image,
      String imageModel) {
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
            // Чат начат помощником (вопрос «как дела»): помнит факты из ответов.
            boolean checkin = ChatOrigin.AGENT.equals(store.chatOrigin(chat));
            JSONArray history = store.messages(chat);
            String stored = ChatImages.attach(query, ImageList.parse(image));
            String currentContent = stored;
            boolean fresh = !image.isEmpty();
            if (history.length() > 0) {
              JSONObject last = history.getJSONObject(history.length() - 1);
              String lastBody = last.getString("content");
              // Повтор после ошибки: то же сообщение уже записано, второй раз не добавляем.
              if (last.getString("role").equals("user")
                  && (lastBody.equals(stored)
                      || (!fresh && ChatImages.split(lastBody).text.equals(query)))) {
                currentContent = lastBody;
                history.remove(history.length() - 1);
              } else store.message(chat, "user", stored, new JSONArray());
            } else store.message(chat, "user", stored, new JSONArray());
            var settings = app.getSharedPreferences("settings", 0);
            // Картинки теперь принадлежат чату (записаны в сообщении), черновик вложения не нужен.
            if (fresh && image.equals(settings.getString("chatImage." + chat, "")))
              settings.edit().remove("chatImage." + chat).apply();
            changed();
            long planNote = store.chatNote(chat);
            if (planNote > 0) {
              planReply(store, chat, planNote, key, model, query, history);
              return;
            }
            List<String> contents = new ArrayList<>();
            for (int i = 0; i < history.length(); i++)
              contents.add(history.getJSONObject(i).getString("content"));
            contents.add(currentContent);
            List<String> chatImages = ChatImages.all(contents);
            List<String> current = ChatImages.split(currentContent).images;
            boolean noteFlow =
                !current.isEmpty() && (query.equals(NOTE_QUERY) || Polza.wantsNote(query));
            String visionModel = imageModel.trim().isEmpty() ? model : imageModel.trim();
            Polza.Answer answer;
            long created = 0;
            if (noteFlow) {
              Vision.Result result =
                  ImageFiles.analyze(app, key, visionModel, ImageList.join(current), query);
              store.getWritableDatabase().beginTransaction();
              try {
                if (!store.chatExists(chat)) throw new java.io.IOException("Чат удалён");
                long id = store.saveVision(result, ImageList.join(current));
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
              answer.usage = "Изображения проанализированы. Заметка сохранена.";
            } else {
              // Диалог, начатый помощником: он продолжает расспрашивать по ситуации.
              String guidance = checkin ? CheckIn.followUp(local) : "";
              // Чат помнит картинки: последние из них идут в запрос, даже если их прислали давно.
              List<String> picked =
                  local
                      ? new ArrayList<>()
                      : ChatImages.select(
                          chatImages,
                          ImageFiles.sizes(app, chatImages),
                          ChatImages.MAX_IN_REQUEST,
                          ChatImages.MAX_REQUEST_BYTES);
              JSONArray parts = new JSONArray();
              for (String name : picked) {
                parts.put(
                    new JSONObject()
                        .put("type", "text")
                        .put(
                            "text",
                            "Изображение №"
                                + (chatImages.indexOf(name) + 1)
                                + (current.contains(name)
                                    ? " (приложено к последнему сообщению):"
                                    : ":")));
                parts.put(Vision.imagePart(ImageFiles.base64(app, name)));
              }
              if (!picked.isEmpty())
                guidance =
                    (guidance.isEmpty() ? "" : guidance + "\n")
                        + ChatImages.guidance(picked.size(), chatImages.size());
              java.util.Set<String> visible = new java.util.HashSet<>(picked);
              JSONArray clean = new JSONArray();
              for (int i = 0; i < history.length(); i++) {
                JSONObject h = history.getJSONObject(i);
                clean.put(
                    new JSONObject()
                        .put("role", h.getString("role"))
                        .put(
                            "content",
                            ChatImages.forModel(h.getString("content"), chatImages, visible)));
              }
              answer =
                  Polza.ask(
                      store,
                      key,
                      picked.isEmpty() ? model : visionModel,
                      query,
                      clean,
                      picked.isEmpty()
                          // Пачка напоминаний длиннее обычного ответа: блок не должен оборваться.
                          ? (ReminderBatch.wants(query) ? Math.max(maxTokens, 3000) : maxTokens)
                          : ChatImages.answerTokens(maxTokens),
                      guidance,
                      parts,
                      picked.isEmpty() ? "" : ChatImages.questionHint());
              if (!picked.isEmpty())
                answer.usage =
                    (answer.usage == null || answer.usage.isEmpty() ? "" : answer.usage + " · ")
                        + "ИИ видит картинок чата: "
                        + picked.size()
                        + " из "
                        + chatImages.size();
            }
            if (!noteFlow && store.chatExists(chat)) {
              long memoNote = 0;
              boolean memoUpdated = false;
              String memoBody = "", memoTitle = "", memoRef = "";
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
                } else if (checkin && answer.draft != null) {
                  // Диалог помощника: заметку записываем сами. Свою прежнюю запись из этого чата
                  // обновляем, пока пользователь её не правил; если правил, оставляем предложение.
                  var prefs = app.getSharedPreferences("settings", 0);
                  String ref = prefs.getString("checkInNote." + chat, "");
                  Note previous = null;
                  boolean edited = false;
                  int colon = ref.indexOf(':');
                  if (colon > 0) {
                    Note n = store.note(Long.parseLong(ref.substring(0, colon)));
                    if (n != null && n.updated == Long.parseLong(ref.substring(colon + 1)))
                      previous = n;
                    else edited = n != null;
                  }
                  if (!edited) {
                    memoTitle = answer.draft.getString("title");
                    memoBody = answer.draft.getString("body");
                    long id =
                        store.save(
                            previous == null ? 0 : previous.id,
                            memoTitle,
                            memoBody,
                            previous == null ? 0 : previous.reminder);
                    long revision = store.note(id).updated;
                    store.applyMetadata(id, revision, answer.draft.optJSONObject("metadata"));
                    memoNote = id;
                    memoUpdated = previous != null;
                    memoRef = id + ":" + revision;
                    answer.sources.put(new JSONObject().put("id", id).put("title", memoTitle));
                    answer.draft = null;
                  }
                }
                if (answer.reminders != null && query.contains(ReminderBatch.REQUEST)) {
                  // Запрос «напоминания по датам из заметки»: даты можно будет добавить в неё.
                  var sourcePrefs = app.getSharedPreferences("settings", 0);
                  long source = sourcePrefs.getLong("reminderSource", 0);
                  if (source > 0) {
                    answer.reminders.put("note", source);
                    sourcePrefs.edit().remove("reminderSource").apply();
                  }
                }
                store.message(
                    chat,
                    "assistant",
                    checkin ? ChatMemo.attach(answer.text, answer.facts) : answer.text,
                    answer.sources,
                    // Предложение создать напоминания по датам хранится там же, где черновик.
                    answer.reminders != null ? answer.reminders : answer.draft);
                if (memoNote > 0) {
                  List<String> bodies = new ArrayList<>();
                  for (int i = 0; i < history.length(); i++)
                    bodies.add(history.getJSONObject(i).getString("content"));
                  bodies.add(ChatMemo.attach(answer.text, answer.facts));
                  List<String> facts = ChatMemo.all(bodies);
                  if (facts.isEmpty()) facts = ChatMemo.fromNote(memoBody);
                  store.message(
                      chat,
                      "assistant",
                      ChatMemo.recap(facts, memoNote, memoUpdated),
                      new JSONArray()
                          .put(new JSONObject().put("id", memoNote).put("title", memoTitle)),
                      null);
                  app.getSharedPreferences("settings", 0)
                      .edit()
                      .putString("checkInNote." + chat, memoRef)
                      .apply();
                  if (!memoUpdated) created = memoNote;
                }
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

  /**
   * Ответ в чате по заметке (docs/AGENT-PLANNER.md, 2.5): агент видит свежий текст заметки и её
   * план, задаёт вопрос с вариантами или присылает план. План не записывается сам — он ложится в
   * черновик сообщения, сохраняет его пользователь кнопкой.
   */
  private static void planReply(
      Store store, long chat, long noteId, String key, String model, String query, JSONArray history)
      throws Exception {
    Note note = store.note(noteId);
    if (note == null) throw new java.io.IOException("Заметка этого чата удалена.");
    java.time.ZoneId zone = java.time.ZoneId.systemDefault();
    PlanTree current = PlanTree.parse(store.plan(noteId));
    JSONArray past = new JSONArray();
    for (int i = 0; i < history.length(); i++) {
      JSONObject h = history.getJSONObject(i);
      past.put(
          new JSONObject()
              .put("role", h.getString("role"))
              .put("content", ChatImages.split(h.getString("content")).text));
    }
    JSONObject payload =
        PlanChat.payload(
            model,
            note.title,
            note.body,
            current,
            new ManagerStore(store).noteAnswers(noteId),
            past,
            query,
            System.currentTimeMillis(),
            zone);
    JSONObject response = store.complete(key, payload);
    JSONObject choice = response.getJSONArray("choices").getJSONObject(0);
    String text = choice.getJSONObject("message").optString("content", "");
    if (text.trim().isEmpty())
      throw new java.io.IOException("Модель вернула пустой ответ. Попробуйте ещё раз.");
    if ("length".equals(choice.optString("finish_reason")) && !text.trim().endsWith("}"))
      throw new java.io.IOException(
          "Ответ модели оборвался. Попросите план короче или выберите другую модель.");
    PlanChat.Reply reply = PlanChat.parse(text, zone, System.currentTimeMillis());
    JSONObject draft = null;
    // План и правка заметки не применяются сами: они ложатся в черновик, применяет пользователь.
    if (reply.plan != null || reply.edit != null) {
      draft = new JSONObject().put("note", noteId);
      if (reply.plan != null) {
        reply.plan.keepDone(current);
        draft.put("plan", reply.plan.toJson());
      }
      if (reply.edit != null) draft.put("edit", reply.edit.toJson());
    }
    if (!store.chatExists(chat)) return;
    store.message(chat, "assistant", PlanChat.store(reply), new JSONArray(), draft);
    StringBuilder used = new StringBuilder(store.localMode() ? "Локальная модель" : "Чат по заметке");
    JSONObject tokens = response.optJSONObject("usage");
    if (tokens != null && tokens.has("total_tokens"))
      used.append(" · ").append(tokens.optInt("total_tokens")).append(" токенов");
    if (tokens != null && tokens.has("cost_rub"))
      used.append(" · ").append(tokens.optString("cost_rub")).append(" ₽");
    usage = used.toString();
    usageChat = chat;
  }
}
