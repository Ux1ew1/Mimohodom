package app.fastnotes;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Explicit, resumable summary requests. Failed requests are never retried automatically. */
final class SummaryRunner {
  private static final AtomicBoolean RUNNING = new AtomicBoolean();

  interface Listener {
    void done(JSONObject result, String error);
  }

  static boolean start(
      Context context, JSONObject proposal, String key, String model, Listener listener) {
    if (!RUNNING.compareAndSet(false, true)) return false;
    Context app = context.getApplicationContext();
    new Thread(
            () -> {
              JSONObject result = null;
              String error = null;
              try (Store store = new Store(app)) {
                ManagerStore manager = new ManagerStore(store);
                if (!manager.current(proposal))
                  throw new IllegalStateException(
                      "Исходники изменились. Запустите новую проверку.");
                List<Note> sources = manager.summarySources(proposal);
                for (Note source : sources)
                  if (manager.top(source.id) != 0)
                    throw new IllegalStateException(
                        "Эти заметки уже в стопке и исключены из повторного анализа.");
                StringBuilder version = new StringBuilder("summary-v1");
                for (Note n : sources)
                  version
                      .append('|')
                      .append(n.id)
                      .append(':')
                      .append(ManagerAnalysis.fingerprint(n));
                String revision = version.toString();
                JSONObject progress = manager.summaryProgress(proposal.getLong("id"));
                if (!revision.equals(progress.optString("revision")))
                  progress = new JSONObject().put("revision", revision).put("cursor", 0);
                List<String> fragments = SummaryAnalysis.fragments(sources);
                int cursor = progress.optInt("cursor");
                while (cursor < fragments.size()) {
                  if (LocalLlm.enabled(app))
                    throw new IllegalStateException(
                        "Выбран локальный режим. Облачная выжимка остановлена.");
                  if (!manager.current(proposal))
                    throw new IllegalStateException(
                        "Исходники изменились. Запустите новую проверку.");
                  JSONObject payload =
                      SummaryAnalysis.payload(model, progress.optString("body"), fragments, cursor);
                  if (!ReviewBudget.reserve(app))
                    throw new IllegalStateException(
                        "Достигнут дневной лимит. Продолжите завтра или снимите лимит в помощнике."
                            + " Готовая часть выжимки сохранена.");
                  ReviewLog.record(
                      app,
                      "Готовим выжимку: обработано фрагментов "
                          + cursor
                          + " из "
                          + fragments.size()
                          + ". Исходников: "
                          + sources.size()
                          + ".");
                  JSONObject summary =
                      SummaryAnalysis.parse(Polza.request("chat/completions", key, payload));
                  cursor += SummaryAnalysis.consumed(payload);
                  progress
                      .put("cursor", cursor)
                      .put("ready", cursor == fragments.size())
                      .put("title", summary.getString("title"))
                      .put("body", summary.getString("body"));
                  manager.saveSummary(proposal, progress);
                }
                if (progress.optString("body").isEmpty())
                  throw new IllegalStateException("В исходниках нет текста для выжимки.");
                result = progress;
                ReviewLog.record(
                    app,
                    "Выжимка готова. Откройте предложение и подтвердите создание стопки."
                        + " Исходников: "
                        + sources.size()
                        + ".");
              } catch (Exception e) {
                error =
                    e instanceof IllegalStateException ? e.getMessage() : ReviewAnalysis.error(e);
                ReviewLog.record(app, "Не удалось подготовить выжимку. " + error);
              } finally {
                RUNNING.set(false);
              }
              JSONObject ready = result;
              String failure = error;
              new Handler(Looper.getMainLooper()).post(() -> listener.done(ready, failure));
            },
            "note-summary")
        .start();
    return true;
  }
}
