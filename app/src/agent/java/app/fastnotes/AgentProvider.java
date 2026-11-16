package app.fastnotes;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Base64;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Точка входа моста: {@code adb shell content call --uri content://app.fastnotes.agent --method run
 * --arg b64:<команда>}. Ответ — Bundle с ключом {@code r}: JSON в base64url.
 *
 * <p>Провайдер доступен только держателям разрешения DUMP (оболочка adb) и дополнительно проверяет
 * uid вызывающего. Существует только в сборке {@code agent}, в обычных debug и release его нет.
 */
public final class AgentProvider extends ContentProvider {
  static final String TAG = "AgentBridge";
  static final String CRASH_FILE = "agent-crash.txt";
  static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static volatile WeakReference<Activity> resumed = new WeakReference<>(null);

  /** Активность на переднем плане или {@code null}, если приложение свёрнуто. */
  static Activity activity() {
    Activity a = resumed.get();
    return a != null && !a.isDestroyed() && !a.isFinishing() ? a : null;
  }

  @Override
  public boolean onCreate() {
    Context context = getContext();
    if (context == null) return false;
    Context app = context.getApplicationContext();
    if (app instanceof Application application) application.registerActivityLifecycleCallbacks(new Lifecycle());
    installCrashLog(context);
    Log.i(TAG, "мост запущен, pid " + Process.myPid());
    return true;
  }

  @Override
  public Bundle call(String method, String arg, Bundle extras) {
    Bundle reply = new Bundle();
    int uid = Binder.getCallingUid();
    if (uid != 0 && uid != 1000 && uid != 2000 && uid != Process.myUid()) {
      reply.putString("r", encode("{\"ok\":false,\"error\":\"доступ только из adb shell\"}"));
      return reply;
    }
    String json;
    try {
      if (!"run".equals(method)) throw new IllegalArgumentException("метод должен быть run");
      String line = arg == null ? "" : arg;
      if (line.startsWith("b64:"))
        line = new String(Base64.decode(line.substring(4), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8);
      json = AgentBridge.execute(getContext(), line);
    } catch (Throwable error) {
      try {
        json = new JSONObject().put("ok", false).put("error", String.valueOf(error.getMessage())).toString();
      } catch (Exception impossible) {
        json = "{\"ok\":false}";
      }
    }
    reply.putString("r", encode(json));
    return reply;
  }

  private static String encode(String json) {
    return Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
  }

  private static void installCrashLog(Context context) {
    Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    File file = new File(context.getFilesDir(), CRASH_FILE);
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          try (FileOutputStream out = new FileOutputStream(file);
              PrintWriter writer = new PrintWriter(out)) {
            writer.println("Падение " + new java.util.Date() + " в потоке " + thread.getName());
            error.printStackTrace(writer);
          } catch (Throwable ignored) {
            StringWriter fallback = new StringWriter();
            error.printStackTrace(new PrintWriter(fallback));
            Log.e(TAG, fallback.toString());
          }
          if (previous != null) previous.uncaughtException(thread, error);
        });
  }

  private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
    @Override
    public void onActivityResumed(Activity activity) {
      resumed = new WeakReference<>(activity);
    }

    @Override
    public void onActivityPaused(Activity activity) {
      if (resumed.get() == activity) resumed = new WeakReference<>(null);
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle state) {}

    @Override
    public void onActivityStarted(Activity activity) {}

    @Override
    public void onActivityStopped(Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

    @Override
    public void onActivityDestroyed(Activity activity) {}
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
    return null;
  }

  @Override
  public String getType(Uri uri) {
    return null;
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    return null;
  }

  @Override
  public int delete(Uri uri, String selection, String[] args) {
    return 0;
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] args) {
    return 0;
  }
}
