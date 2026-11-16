package app.fastnotes;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Диспетчер консольных команд агента. Каждая команда — действие пользователя или проверка состояния;
 * результат — JSON {@code {ok, text, data, error, hint, ms}}.
 */
final class AgentBridge {
  private AgentBridge() {}

  /** Ошибка с подсказкой, что сделать дальше. */
  static final class AgentError extends RuntimeException {
    final String hint;

    AgentError(String message, String hint) {
      super(message);
      this.hint = hint;
    }
  }

  static final class Reply {
    final String text;
    JSONObject data;

    Reply(String text) {
      this.text = text;
    }

    Reply data(JSONObject value) {
      data = value;
      return this;
    }
  }

  private static final int OUTPUT_LIMIT = 400_000;
  private static final Deque<String> history = new ArrayDeque<>();

  /** Справочник: имя, использование, описание. */
  static final String[][] HELP = {
    {"help", "[префикс]", "список команд"},
    {"ping", "", "версия приложения, устройство, есть ли экран"},
    {"state", "", "страница, вкладка, открытая заметка/чат, окна, фоновые задачи"},
    {"history", "[--lines=30]", "последние команды моста и их итог"},
    {"crash.last", "", "стек последнего падения приложения (если было)"},
    {"crash.clear", "", "забыть записанное падение"},
    {"ui.dump", "[--all] [--json]", "элементы экрана с @номерами, текстом, границами и признаками"},
    {"ui.read", "", "видимые тексты в порядке чтения: что видит пользователь"},
    {"ui.tap", "<цель> [--nth=N] [--exact] [--click] [--settle=мс]", "нажать: @N, desc:…, text:…, hint:… или текст"},
    {"ui.longpress", "<цель> [те же опции]", "долгое нажатие (меню, выбор)"},
    {"ui.tapxy", "<x> <y>", "касание по экранным координатам в пикселях"},
    {"ui.type", "\"текст\" [--target=цель] [--replace]", "ввести текст в поле как с клавиатуры (по умолчанию — в сфокусированное)"},
    {"ui.clear", "[цель]", "очистить поле ввода"},
    {"ui.key", "back|enter|delete|tab|KEYCODE_… [--direct]", "системная кнопка или клавиша; back закрывает диалог/страницу"},
    {"ui.back", "[--direct]", "то же, что ui.key back"},
    {"ui.swipe", "left|right|up|down [--amount=0.6] [--target=цель] [--ms=350] | <x1> <y1> <x2> <y2>", "жест пальцем; left/right листают вкладки"},
    {"ui.scroll", "down|up|left|right [--amount=0.5] [--target=цель]", "прокрутить содержимое (down — показать то, что ниже)"},
    {"ui.wait", "<цель> [--gone] [--timeout=10000]", "ждать появления (или исчезновения) элемента"},
    {"wait.idle", "[--timeout=30000]", "ждать конца ответа чата, анализа картинки и видео"},
    {"app.render", "", "перерисовать текущий экран (после прямой записи в базу)"},
    {"app.recreate", "", "пересоздать активность, как при повороте экрана"},
    {"chat.send", "\"сообщение\"", "в открытом чате ввести текст и нажать «Отправить» (ответ ждите через wait.idle)"},
    {"chat.messages", "[id]", "сообщения чата из базы (по умолчанию — последний чат)"},
    {"chats.list", "", "чаты из базы"},
    {"notes.list", "[--limit=50]", "заметки из базы, новые сверху"},
    {"notes.get", "<id>", "заметка целиком"},
    {"notes.search", "\"запрос\" [--limit=20]", "поиск по индексу, как в приложении"},
    {"notes.create", "\"название\" \"текст\" [--reminder=мс]", "создать заметку в базе (интерфейс покажет её после app.render)"},
    {"notes.seed", "[--count=20]", "создать тестовые заметки с меткой [agent]"},
    {"notes.unseed", "", "удалить только заметки с меткой [agent]"},
    {"reminders.list", "", "заметки с напоминаниями"},
    {"folders.list", "", "папки из базы"},
    {"prefs.get", "[часть имени]", "настройки (ключи и токены скрыты)"},
    {"prefs.set", "<имя> <значение> [--type=string|bool|int|long|float]", "записать настройку (после — app.recreate)"},
    {"prefs.remove", "<имя>", "удалить настройку"},
    {"db.query", "\"SELECT …\" [--limit=100]", "SQL только на чтение по базе заметок"},
  };

  static String execute(Context context, String line) {
    long started = SystemClock.uptimeMillis();
    JSONObject out = new JSONObject();
    String label = line.length() > 200 ? line.substring(0, 200) + "…" : line;
    try {
      AgentCommand command = AgentCommand.parse(line);
      Reply reply = dispatch(context, command);
      out.put("ok", true);
      out.put("text", reply.text);
      if (reply.data != null) out.put("data", reply.data);
    } catch (AgentError error) {
      fail(out, error.getMessage(), error.hint);
    } catch (IllegalArgumentException error) {
      fail(out, error.getMessage(), "справка: help; формат: имя арг \"арг с пробелом\" --опция=значение");
    } catch (Throwable error) {
      StringBuilder stack = new StringBuilder();
      StackTraceElement[] trace = error.getStackTrace();
      for (int i = 0; i < Math.min(8, trace.length); i++) stack.append("\n  at ").append(trace[i]);
      fail(out, error.getClass().getSimpleName() + ": " + error.getMessage(), "внутренняя ошибка моста или приложения" + stack);
    }
    long took = SystemClock.uptimeMillis() - started;
    try {
      out.put("ms", took);
    } catch (Exception ignored) {
    }
    boolean ok = out.optBoolean("ok");
    synchronized (history) {
      history.addLast((ok ? "ok  " : "ERR ") + took + "мс  " + label);
      while (history.size() > 100) history.removeFirst();
    }
    Log.i(AgentProvider.TAG, (ok ? "ok " : "ERR ") + took + "ms " + label + (ok ? "" : " → " + out.optString("error")));
    String json = out.toString();
    if (json.length() > OUTPUT_LIMIT) {
      try {
        String text = out.optString("text", "");
        out.remove("data");
        out.put("text", text.substring(0, Math.min(text.length(), OUTPUT_LIMIT - 2000)) + "\n…(вывод обрезан)");
        out.put("truncated", true);
      } catch (Exception ignored) {
      }
      json = out.toString();
    }
    return json;
  }

  private static void fail(JSONObject out, String message, String hint) {
    try {
      out.put("ok", false);
      out.put("error", message == null ? "ошибка" : message);
      if (hint != null) out.put("hint", hint);
    } catch (Exception ignored) {
    }
  }

  // ===== Потоки =====

  /** Выполняет работу на главном потоке и ждёт; долгое ожидание — признак зависания интерфейса. */
  static <T> T onMain(Callable<T> job) throws Exception {
    if (Looper.myLooper() == Looper.getMainLooper()) return job.call();
    FutureTask<T> task = new FutureTask<>(job);
    AgentProvider.MAIN.post(task);
    try {
      return task.get(15, TimeUnit.SECONDS);
    } catch (TimeoutException timeout) {
      task.cancel(false);
      throw new AgentError("главный поток не отвечает 15 с", "вероятно, зависание интерфейса (ANR): см. logcat и crash.last");
    } catch (java.util.concurrent.ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof Exception e) throw e;
      throw error;
    }
  }

  /** Пауза после действия: анимации (вкладки идут 280 мс) и отложенные задачи главного потока. */
  static void settle(AgentCommand c, long fallback) throws Exception {
    long ms = c.number("settle", fallback);
    if (ms > 0) Thread.sleep(Math.min(ms, 10_000));
    onMain(() -> null);
    onMain(() -> null);
  }

  static Activity activity() {
    Activity a = AgentProvider.activity();
    if (a == null)
      throw new AgentError("приложение не на экране", "запустите: node scripts/agent.mjs launch");
    return a;
  }

  // ===== Диспетчер =====

  private static Reply dispatch(Context context, AgentCommand c) throws Exception {
    switch (c.name) {
      case "help":
        return help(c.arg(0, ""));
      case "ping":
        return ping(context);
      case "state":
        return state();
      case "history":
        return history((int) c.number("lines", 30));
      case "crash.last":
        return crashLast(context);
      case "crash.clear":
        new File(context.getFilesDir(), AgentProvider.CRASH_FILE).delete();
        return new Reply("запись о падении удалена");
      case "ui.dump":
        return dump(c);
      case "ui.read":
        return read();
      case "ui.tap":
        return tap(c, c.arg(0), false);
      case "ui.longpress":
        return tap(c, c.arg(0), true);
      case "ui.tapxy":
        return tapXy(c);
      case "ui.type":
        return type(c, c.arg(0), c.option("target", null), c.flag("replace"));
      case "ui.clear":
        return type(c, "", c.args.isEmpty() ? c.option("target", null) : c.arg(0), true);
      case "ui.key":
        return key(c, c.arg(0));
      case "ui.back":
        return key(c, "back");
      case "ui.swipe":
        return swipe(c, false);
      case "ui.scroll":
        return swipe(c, true);
      case "ui.wait":
        return waitFor(c);
      case "wait.idle":
        return waitIdle(c);
      case "app.render":
        return appCall("render");
      case "app.recreate":
        onMain(
            () -> {
              activity().recreate();
              return null;
            });
        settle(c, 800);
        return new Reply("активность пересоздана: " + stateSummary());
      case "chat.send":
        return chatSend(c);
      case "chat.messages":
        return chatMessages(context, c);
      case "chats.list":
        return chatsList(context);
      case "notes.list":
        return notesList(context, c);
      case "notes.get":
        return notesGet(context, c.idArg(0));
      case "notes.search":
        return notesSearch(context, c);
      case "notes.create":
        return notesCreate(context, c);
      case "notes.seed":
        return notesSeed(context, c);
      case "notes.unseed":
        return notesUnseed(context);
      case "reminders.list":
        return remindersList(context);
      case "folders.list":
        return query(context, "SELECT id,name,key,created,auto,(SELECT COUNT(*) FROM notes WHERE folder=folders.id) AS notes FROM folders ORDER BY id", 200);
      case "prefs.get":
        return prefsGet(context, c.arg(0, null));
      case "prefs.set":
        AgentData.setPref(context, c.arg(0), c.arg(1), c.option("type", "string"));
        return new Reply("записано " + c.arg(0) + " = " + c.arg(1) + " (" + c.option("type", "string") + "); app.recreate применит");
      case "prefs.remove":
        if (AgentData.sensitive(c.arg(0))) throw new IllegalArgumentException("ключи и токены через мост не меняются");
        context.getSharedPreferences("settings", 0).edit().remove(c.arg(0)).commit();
        return new Reply("удалено " + c.arg(0));
      case "db.query":
        return query(context, c.arg(0), (int) c.number("limit", 100));
      default:
        throw new AgentError("неизвестная команда: " + c.name, "список: help");
    }
  }

  // ===== Справка и состояние =====

  private static Reply help(String prefix) throws Exception {
    StringBuilder text = new StringBuilder();
    JSONArray array = new JSONArray();
    for (String[] row : HELP) {
      if (!prefix.isEmpty() && !row[0].startsWith(prefix)) continue;
      text.append(row[0]).append(row[1].isEmpty() ? "" : " " + row[1]).append("\n    ").append(row[2]).append('\n');
      array.put(new JSONObject().put("name", row[0]).put("usage", row[1]).put("about", row[2]));
    }
    if (text.length() == 0) throw new AgentError("нет команд с префиксом «" + prefix + "»", "список: help");
    text.append("\nЦель для ui.tap/ui.type: @N из ui.dump | desc:… | text:… | hint:… | class:… | просто текст (текст, описание или подсказка).");
    return new Reply(text.toString()).data(new JSONObject().put("commands", array));
  }

  private static Reply ping(Context context) throws Exception {
    JSONObject data = new JSONObject();
    android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
    data.put("package", context.getPackageName());
    data.put("versionName", info.versionName);
    data.put("versionCode", Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
    data.put("sdk", Build.VERSION.SDK_INT);
    data.put("device", Build.MANUFACTURER + " " + Build.MODEL);
    data.put("pid", android.os.Process.myPid());
    Activity a = AgentProvider.activity();
    data.put("foreground", a != null);
    return new Reply(
            "мост работает: " + context.getPackageName() + " " + info.versionName + ", Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL
                + (a == null ? ", приложение не на экране" : ", на экране " + a.getClass().getSimpleName()))
        .data(data);
  }

  private static Object field(Object owner, String name) {
    try {
      Field f = owner.getClass().getDeclaredField(name);
      f.setAccessible(true);
      return f.get(owner);
    } catch (Throwable error) {
      return null;
    }
  }

  static JSONObject stateJson() throws Exception {
    return onMain(
        () -> {
          JSONObject o = new JSONObject();
          Activity a = AgentProvider.activity();
          o.put("foreground", a != null);
          if (a instanceof MainActivity) {
            for (String name : new String[] {"page", "returnPage", "search"}) o.put(name, field(a, name));
            for (String name : new String[] {"tab", "editing", "chat", "folder"}) o.put(name, field(a, name));
            for (String name : new String[] {"searchOpen", "rendering", "pageAnimating", "editorActive", "keyboardUp"})
              o.put(name, field(a, name));
          }
          if (a != null) {
            JSONArray windows = new JSONArray();
            for (AgentUi.Win w : AgentUi.windows(a)) windows.put(w.kind);
            o.put("windows", windows);
            if (!AgentUi.windowsNote.isEmpty()) o.put("windowsNote", AgentUi.windowsNote);
          }
          o.put("chatRunning", ChatRunner.running);
          o.put("imageRunning", ImageRunner.running);
          o.put("videoRunning", VideoRunner.running);
          if (!ChatRunner.error.isEmpty()) o.put("chatError", ChatRunner.error);
          return o;
        });
  }

  static String stateSummary() {
    try {
      JSONObject s = stateJson();
      if (!s.optBoolean("foreground")) return "приложение не на экране";
      JSONArray windows = s.optJSONArray("windows");
      return "page=" + s.opt("page") + " tab=" + s.opt("tab")
          + (s.optLong("editing") > 0 ? " editing=" + s.optLong("editing") : "")
          + (s.optLong("chat") > 0 ? " chat=" + s.optLong("chat") : "")
          + (windows != null && windows.length() > 1 ? " окон=" + windows.length() : "");
    } catch (Exception error) {
      return "состояние недоступно: " + error.getMessage();
    }
  }

  private static Reply state() throws Exception {
    JSONObject s = stateJson();
    return new Reply(stateSummary() + (s.optLong("chatRunning") != 0 ? " · чат отвечает" : "")).data(s);
  }

  private static Reply history(int lines) {
    StringBuilder text = new StringBuilder();
    synchronized (history) {
      List<String> all = new ArrayList<>(history);
      for (int i = Math.max(0, all.size() - lines); i < all.size(); i++) text.append(all.get(i)).append('\n');
    }
    return new Reply(text.length() == 0 ? "команд пока не было" : text.toString());
  }

  private static Reply crashLast(Context context) throws Exception {
    File file = new File(context.getFilesDir(), AgentProvider.CRASH_FILE);
    if (!file.exists()) return new Reply("падений с запуска моста не записано");
    byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
    return new Reply(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
  }

  // ===== Экран =====

  private static Reply dump(AgentCommand c) throws Exception {
    boolean everything = c.flag("all");
    Activity a = activity();
    return onMain(
        () -> {
          List<AgentUi.Node> nodes = AgentUi.snapshot(a, everything);
          List<AgentUi.Win> windows = AgentUi.lastWindows();
          String text = "состояние: " + stateSummary() + "\n" + AgentUi.dumpText(nodes, windows, everything);
          if (!AgentUi.windowsNote.isEmpty()) text += "(" + AgentUi.windowsNote + ")\n";
          Reply reply = new Reply(text);
          if (c.flag("json")) {
            JSONArray array = new JSONArray();
            for (AgentUi.Node n : nodes) {
              if (!n.visible && !everything) continue;
              array.put(
                  new JSONObject()
                      .put("ref", n.ref).put("window", n.window).put("class", n.cls)
                      .put("text", n.text).put("desc", n.desc).put("hint", n.hint)
                      .put("x", n.x).put("y", n.y).put("w", n.w).put("h", n.h)
                      .put("clickable", n.clickable).put("editable", n.editable)
                      .put("focused", n.focused).put("enabled", n.enabled).put("visible", n.visible)
                      .put("inRef", n.clickRef));
            }
            reply.data(new JSONObject().put("nodes", array));
          }
          return reply;
        });
  }

  private static Reply read() throws Exception {
    Activity a = activity();
    return onMain(
        () -> {
          List<AgentUi.Node> nodes = AgentUi.snapshot(a, false);
          return new Reply(stateSummary() + "\n" + AgentUi.readText(nodes, AgentUi.lastWindows()));
        });
  }

  private static String visibleSummary(Activity a) {
    try {
      return onMain(
          () -> {
            String text = AgentUi.readText(AgentUi.snapshot(a, false), AgentUi.lastWindows());
            String[] lines = text.split("\n");
            StringBuilder out = new StringBuilder("на экране: ");
            for (int i = 0; i < Math.min(lines.length, 20); i++) out.append(i > 0 ? " | " : "").append(lines[i]);
            return out.toString();
          });
    } catch (Exception error) {
      return "";
    }
  }

  private static final class Pick {
    AgentUi.Node node;
    List<AgentUi.Node> matches;
    List<AgentUi.Win> windows;
  }

  private static Pick pick(Activity a, AgentCommand c, String target) throws Exception {
    return onMain(
        () -> {
          boolean byRef = target.startsWith("@");
          List<AgentUi.Node> nodes = byRef ? new ArrayList<>() : AgentUi.snapshot(a, false);
          List<AgentUi.Node> matches = AgentUi.find(nodes, target, c.flag("exact"));
          Pick p = new Pick();
          p.windows = byRef ? AgentUi.lastWindows() : AgentUi.lastWindows();
          p.matches = matches;
          int nth = (int) c.number("nth", 0);
          if (matches.isEmpty() || nth < 0 || nth >= matches.size()) {
            String why =
                byRef ? "элемент " + target + " исчез или не был в последнем ui.dump" : "нет элемента «" + target + "»" + (matches.isEmpty() ? "" : " с номером --nth=" + nth + " (найдено " + matches.size() + ")");
            throw new AgentError(why, byRef ? "сделайте ui.dump заново" : visibleSummary(a));
          }
          p.node = matches.get(nth);
          return p;
        });
  }

  private static String warning(Pick p, AgentCommand c) {
    if (p.matches.size() < 2) return "";
    StringBuilder out = new StringBuilder(" (совпадений " + p.matches.size() + ", взято №" + c.number("nth", 0) + "; другие: ");
    for (int i = 0; i < Math.min(4, p.matches.size()); i++) out.append(i > 0 ? "; " : "").append(p.matches.get(i).label());
    return out.append(" — уточните --nth/--exact/@N)").toString();
  }

  private static Reply tap(AgentCommand c, String target, boolean longPress) throws Exception {
    Activity a = activity();
    Pick p = pick(a, c, target);
    AgentUi.Node node = AgentUi.clickTarget(p.node);
    AgentUi.Win modal = AgentUi.topModal(p.windows);
    if (modal != null && node.window < modal.index)
      throw new AgentError("«" + node.label() + "» закрыт диалогом", "закройте диалог (ui.back) или нажмите его кнопку; " + visibleSummary(a));
    if (!node.enabled) throw new AgentError("«" + node.label() + "» отключён", "условие, при котором кнопка включается, не выполнено");
    String before = stateSummary();
    boolean revealed = onMain(() -> AgentUi.reveal(node));
    if (!revealed) throw new AgentError("«" + node.label() + "» вне экрана и не прокручивается к виду", "ui.scroll down|up и повторите");
    String mode = "touch";
    if (c.flag("click")) {
      mode = "click";
      boolean handled =
          onMain(
              () -> {
                View v = node.get();
                if (v == null) throw new AgentError("элемент исчез", "ui.dump заново");
                return longPress ? v.performLongClick() : v.performClick();
              });
      if (!handled) mode = "click(нет обработчика)";
    } else {
      float[] at = onMain(() -> AgentUi.center(node));
      View decor = p.windows.get(node.window).decor;
      long down = SystemClock.uptimeMillis();
      onMain(
          () -> {
            AgentUi.touch(decor, MotionEvent.ACTION_DOWN, down, at[0], at[1]);
            return null;
          });
      Thread.sleep(longPress ? 800 : 70);
      onMain(
          () -> {
            AgentUi.touch(decor, MotionEvent.ACTION_UP, down, at[0], at[1]);
            return null;
          });
    }
    settle(c, 400);
    String after = stateSummary();
    JSONObject data = new JSONObject().put("target", node.label()).put("mode", mode).put("before", before).put("after", after);
    return new Reply((longPress ? "долгое нажатие " : "нажато ") + node.label() + " [" + mode + "]" + warning(p, c) + "\n" + (before.equals(after) ? "состояние то же: " : "→ ") + after).data(data);
  }

  private static Reply tapXy(AgentCommand c) throws Exception {
    Activity a = activity();
    float x = Float.parseFloat(c.arg(0)), y = Float.parseFloat(c.arg(1));
    String before = stateSummary();
    View decor = onMain(() -> AgentUi.top(AgentUi.windows(a)).decor);
    long down = SystemClock.uptimeMillis();
    onMain(
        () -> {
          AgentUi.touch(decor, MotionEvent.ACTION_DOWN, down, x, y);
          return null;
        });
    Thread.sleep(70);
    onMain(
        () -> {
          AgentUi.touch(decor, MotionEvent.ACTION_UP, down, x, y);
          return null;
        });
    settle(c, 400);
    String after = stateSummary();
    return new Reply("касание (" + (int) x + "," + (int) y + ")\n" + (before.equals(after) ? "состояние то же: " : "→ ") + after);
  }

  private static EditText editField(Activity a, String target, AgentCommand c) throws Exception {
    return onMain(
        () -> {
          List<AgentUi.Node> nodes = AgentUi.snapshot(a, false);
          List<AgentUi.Win> windows = AgentUi.lastWindows();
          int topIndex = windows.size() - 1;
          if (target != null) {
            List<AgentUi.Node> found = AgentUi.find(nodes, target, c.flag("exact"));
            for (AgentUi.Node n : found)
              if (n.editable && n.get() instanceof EditText e) return e;
            throw new AgentError("поле «" + target + "» не найдено", visibleSummary(a));
          }
          View focus = windows.get(topIndex).decor.findFocus();
          if (focus instanceof EditText e && e.isShown()) return e;
          List<EditText> fields = new ArrayList<>();
          StringBuilder names = new StringBuilder();
          for (AgentUi.Node n : nodes)
            if (n.editable && n.visible && n.window == topIndex && n.get() instanceof EditText e) {
              fields.add(e);
              names.append(names.length() > 0 ? "; " : "").append(n.describe());
            }
          if (fields.size() == 1) return fields.get(0);
          throw new AgentError(
              fields.isEmpty() ? "на экране нет поля ввода" : "несколько полей, нет сфокусированного",
              fields.isEmpty() ? "откройте экран с полем" : "укажите --target=hint:… или --target=@N: " + names);
        });
  }

  private static Reply type(AgentCommand c, String value, String target, boolean replace) throws Exception {
    Activity a = activity();
    EditText field = editField(a, target, c);
    String result =
        onMain(
            () -> {
              String text = AgentUi.type(field, value, replace);
              return AgentUi.isSecret(field) ? "•••(" + text.length() + ")" : text;
            });
    settle(c, 200);
    return new Reply((value.isEmpty() && replace ? "очищено" : "введено " + value.length() + " симв.") + "; в поле теперь: " + AgentUi.shorten(result));
  }

  private static Reply key(AgentCommand c, String name) throws Exception {
    Activity a = activity();
    int code = AgentUi.keyCode(name);
    if (code == 0) throw new AgentError("неизвестная клавиша «" + name + "»", "back, enter, delete, tab, search, menu или KEYCODE_…");
    String before = stateSummary();
    String how =
        onMain(
            () -> {
              List<AgentUi.Win> windows = AgentUi.windows(a);
              AgentUi.Win top = AgentUi.top(windows);
              View focus = top.decor.findFocus();
              if (code == android.view.KeyEvent.KEYCODE_ENTER
                  && focus instanceof EditText e
                  && (e.getImeOptions() & EditorInfo.IME_MASK_ACTION) > EditorInfo.IME_ACTION_NONE
                  && (e.getInputType() & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0) {
                e.onEditorAction(e.getImeOptions() & EditorInfo.IME_MASK_ACTION);
                return "действие клавиатуры";
              }
              if (code == android.view.KeyEvent.KEYCODE_BACK && c.flag("direct") && top.kind.equals("экран")) {
                a.onBackPressed();
                return "onBackPressed";
              }
              AgentUi.key(top.decor, code);
              return "окно «" + top.kind + "»";
            });
    settle(c, 400);
    String after = stateSummary();
    return new Reply("клавиша " + name + " → " + how + "\n" + (before.equals(after) ? "состояние то же: " : "→ ") + after);
  }

  private static Reply swipe(AgentCommand c, boolean scroll) throws Exception {
    Activity a = activity();
    String word = c.arg(0).toLowerCase(java.util.Locale.ROOT);
    float[] pts = new float[4];
    View decor = onMain(() -> AgentUi.top(AgentUi.windows(a)).decor);
    if (!scroll && c.args.size() >= 4) {
      for (int i = 0; i < 4; i++) pts[i] = Float.parseFloat(c.arg(i));
    } else {
      float cx, cy, width, height;
      String target = c.option("target", null);
      if (target != null) {
        AgentUi.Node n = pick(a, c, target).node;
        float[] at = onMain(() -> AgentUi.center(n));
        cx = at[0]; cy = at[1]; width = Math.max(n.w, 100); height = Math.max(n.h, 100);
      } else {
        int[] origin = new int[2];
        decor.getLocationOnScreen(origin);
        width = decor.getWidth(); height = decor.getHeight();
        cx = origin[0] + width / 2f; cy = origin[1] + height / 2f;
      }
      float half = (float) Math.min(0.9, Math.max(0.1, Double.parseDouble(c.option("amount", scroll ? "0.5" : "0.6")))) / 2f;
      String finger = word;
      if (scroll)
        finger = switch (word) {
          case "down" -> "up";
          case "up" -> "down";
          case "left" -> "right";
          case "right" -> "left";
          default -> throw new IllegalArgumentException("направление: down, up, left или right");
        };
      switch (finger) {
        case "left" -> { pts[0] = cx + width * half; pts[1] = cy; pts[2] = cx - width * half; pts[3] = cy; }
        case "right" -> { pts[0] = cx - width * half; pts[1] = cy; pts[2] = cx + width * half; pts[3] = cy; }
        case "up" -> { pts[0] = cx; pts[1] = cy + height * half; pts[2] = cx; pts[3] = cy - height * half; }
        case "down" -> { pts[0] = cx; pts[1] = cy - height * half; pts[2] = cx; pts[3] = cy + height * half; }
        default -> throw new IllegalArgumentException("направление: left, right, up, down или четыре координаты");
      }
    }
    long total = Math.max(80, c.number("ms", 350));
    int steps = 14;
    String before = stateSummary();
    long down = SystemClock.uptimeMillis();
    onMain(() -> { AgentUi.touch(decor, MotionEvent.ACTION_DOWN, down, pts[0], pts[1]); return null; });
    for (int i = 1; i <= steps; i++) {
      float f = i / (float) steps;
      float x = pts[0] + (pts[2] - pts[0]) * f, y = pts[1] + (pts[3] - pts[1]) * f;
      Thread.sleep(total / steps);
      onMain(() -> { AgentUi.touch(decor, MotionEvent.ACTION_MOVE, down, x, y); return null; });
    }
    onMain(() -> { AgentUi.touch(decor, MotionEvent.ACTION_UP, down, pts[2], pts[3]); return null; });
    settle(c, 500);
    String after = stateSummary();
    return new Reply((scroll ? "прокрутка " : "жест ") + word + "\n" + (before.equals(after) ? "состояние то же: " : "→ ") + after);
  }

  private static Reply waitFor(AgentCommand c) throws Exception {
    Activity a = activity();
    String target = c.arg(0);
    boolean gone = c.flag("gone");
    long deadline = SystemClock.uptimeMillis() + Math.min(60_000, c.number("timeout", 10_000));
    while (true) {
      List<AgentUi.Node> hits =
          onMain(
              () -> {
                List<AgentUi.Node> found = new ArrayList<>();
                for (AgentUi.Node n : AgentUi.find(AgentUi.snapshot(a, false), target, c.flag("exact"))) if (n.visible) found.add(n);
                return found;
              });
      if (gone ? hits.isEmpty() : !hits.isEmpty())
        return new Reply((gone ? "исчезло: " : "появилось: ") + (gone ? target : hits.get(0).label()) + "\n" + stateSummary());
      if (SystemClock.uptimeMillis() > deadline)
        throw new AgentError((gone ? "не исчезло за " : "не появилось за ") + c.number("timeout", 10_000) + " мс: " + target, visibleSummary(a));
      Thread.sleep(200);
    }
  }

  private static Reply waitIdle(AgentCommand c) throws Exception {
    long deadline = SystemClock.uptimeMillis() + Math.min(180_000, c.number("timeout", 30_000));
    while (ChatRunner.running != 0 || ImageRunner.running || VideoRunner.running) {
      if (SystemClock.uptimeMillis() > deadline)
        throw new AgentError("фоновая работа не закончилась", "чат=" + ChatRunner.running + " картинка=" + ImageRunner.running + " видео=" + VideoRunner.running);
      Thread.sleep(300);
    }
    settle(c, 300);
    String error = ChatRunner.error.isEmpty() ? "" : "; ошибка чата: " + ChatRunner.error;
    return new Reply("фоновых задач нет" + error + "\n" + stateSummary());
  }

  private static Reply appCall(String method) throws Exception {
    Activity a = activity();
    onMain(
        () -> {
          Method m = a.getClass().getDeclaredMethod(method);
          m.setAccessible(true);
          m.invoke(a);
          return null;
        });
    Thread.sleep(300);
    return new Reply("выполнено " + method + ": " + stateSummary());
  }

  private static Reply chatSend(AgentCommand c) throws Exception {
    JSONObject s = stateJson();
    if (!"chat".equals(s.optString("page")))
      throw new AgentError("открыт не чат (page=" + s.optString("page") + ")", "ui.tap \"ИИ-чат\", затем откройте чат из списка или нажмите «Новый чат»; ui.dump покажет кнопки");
    String text = c.arg(0);
    type(AgentCommand.parse("ui.type x --replace --settle=100"), text, "hint:Сообщение", true);
    Reply sent = tap(AgentCommand.parse("ui.tap x --exact --settle=600"), "desc:Отправить", false);
    return new Reply("отправлено: " + AgentUi.shorten(text) + "\n" + sent.text + "\nответ ждите: wait.idle, затем chat.messages");
  }

  // ===== Данные =====

  private static Reply chatMessages(Context context, AgentCommand c) throws Exception {
    Store store = AgentData.store(context);
    long id = c.args.isEmpty() ? 0 : c.idArg(0);
    if (id == 0) {
      List<String[]> chats = store.chats();
      if (chats.isEmpty()) return new Reply("чатов нет");
      id = Long.parseLong(chats.get(0)[0]);
    }
    JSONArray messages = store.messages(id);
    StringBuilder text = new StringBuilder("чат " + id + ", сообщений " + messages.length() + "\n");
    for (int i = 0; i < messages.length(); i++) {
      JSONObject m = messages.getJSONObject(i);
      text.append(m.optString("role")).append(": ").append(m.optString("body", m.optString("content", m.toString()))).append('\n');
    }
    return new Reply(text.toString()).data(new JSONObject().put("chat", id).put("messages", messages));
  }

  private static Reply chatsList(Context context) throws Exception {
    JSONArray array = new JSONArray();
    StringBuilder text = new StringBuilder();
    for (String[] chat : AgentData.store(context).chats()) {
      array.put(new JSONObject().put("id", Long.parseLong(chat[0])).put("title", chat[1]).put("created", Long.parseLong(chat[2])));
      text.append('#').append(chat[0]).append(" «").append(chat[1]).append("»\n");
    }
    return new Reply(text.length() == 0 ? "чатов нет" : text.toString()).data(new JSONObject().put("chats", array));
  }

  private static Reply notesList(Context context, AgentCommand c) throws Exception {
    List<Note> all = AgentData.store(context).notes();
    int limit = (int) c.number("limit", 50);
    StringBuilder text = new StringBuilder("всего заметок: " + all.size() + "\n");
    for (int i = 0; i < Math.min(limit, all.size()); i++) text.append(AgentData.noteLine(all.get(i))).append('\n');
    return new Reply(text.toString()).data(new JSONObject().put("total", all.size()).put("notes", AgentData.notesJson(all, limit, 300)));
  }

  private static Reply notesGet(Context context, long id) throws Exception {
    Note n = AgentData.store(context).note(id);
    if (n == null) throw new AgentError("заметки #" + id + " нет", "notes.list");
    return new Reply(AgentData.noteLine(n) + "\n\n" + n.body).data(AgentData.noteJson(n, Integer.MAX_VALUE));
  }

  private static Reply notesSearch(Context context, AgentCommand c) throws Exception {
    List<Note> found = AgentData.store(context).searchNotes(c.arg(0));
    int limit = (int) c.number("limit", 20);
    StringBuilder text = new StringBuilder("найдено: " + found.size() + "\n");
    for (int i = 0; i < Math.min(limit, found.size()); i++) text.append(AgentData.noteLine(found.get(i))).append('\n');
    return new Reply(text.toString()).data(new JSONObject().put("total", found.size()).put("notes", AgentData.notesJson(found, limit, 300)));
  }

  private static Reply notesCreate(Context context, AgentCommand c) throws Exception {
    long id = AgentData.store(context).save(0, c.arg(0), c.arg(1, ""), c.number("reminder", 0));
    return new Reply("создана заметка #" + id + "; app.render покажет её в списке").data(new JSONObject().put("id", id));
  }

  private static Reply notesSeed(Context context, AgentCommand c) throws Exception {
    Store store = AgentData.store(context);
    int count = (int) Math.min(500, c.number("count", 20));
    JSONArray ids = new JSONArray();
    for (int i = 1; i <= count; i++)
      ids.put(store.save(0, AgentData.SEED + "Тест " + i, "Тестовая заметка номер " + i + ".\nВторая строка для проверки переносов и поиска: слово-" + (i % 7), 0));
    return new Reply("создано заметок: " + count + " (метка «[agent]»); удалить: notes.unseed").data(new JSONObject().put("ids", ids));
  }

  private static Reply notesUnseed(Context context) throws Exception {
    Store store = AgentData.store(context);
    int removed = 0;
    for (Note n : store.notes())
      if (n.title != null && n.title.startsWith(AgentData.SEED)) {
        store.delete(n.id);
        removed++;
      }
    return new Reply("удалено тестовых заметок: " + removed);
  }

  private static Reply remindersList(Context context) throws Exception {
    StringBuilder text = new StringBuilder();
    JSONArray array = new JSONArray();
    for (Note n : AgentData.store(context).notes())
      if (n.reminder > 0) {
        array.put(AgentData.noteJson(n, 120));
        text.append(AgentData.noteLine(n)).append(" повтор=").append(n.repeat).append(" за=").append(n.lead).append('\n');
      }
    return new Reply(text.length() == 0 ? "напоминаний нет" : text.toString()).data(new JSONObject().put("notes", array));
  }

  private static Reply prefsGet(Context context, String only) throws Exception {
    JSONObject prefs = AgentData.prefsJson(context, only);
    StringBuilder text = new StringBuilder();
    java.util.Iterator<String> keys = prefs.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      text.append(key).append(" = ").append(prefs.getString(key)).append('\n');
    }
    return new Reply(text.length() == 0 ? "настроек нет" : text.toString()).data(prefs);
  }

  private static Reply query(Context context, String sql, int limit) throws Exception {
    JSONObject result = AgentData.query(context, sql, limit);
    JSONArray rows = result.getJSONArray("rows");
    StringBuilder text = new StringBuilder("строк: " + rows.length() + (result.optBoolean("truncated") ? "+ (обрезано --limit)" : "") + "\n");
    for (int i = 0; i < rows.length(); i++) text.append(rows.getJSONObject(i)).append('\n');
    return new Reply(text.toString()).data(result);
  }
}
