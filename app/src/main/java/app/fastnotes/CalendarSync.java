package app.fastnotes;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Односторонняя синхронизация: заметки с напоминанием становятся событиями отдельного локального
 * календаря «Мимоходом». Что менять, решает {@link CalendarSyncPlan}; здесь только работа с
 * {@code CalendarContract}. События календаря без звука: оповещает по-прежнему приложение.
 */
public final class CalendarSync {
  public static final int REQUEST_CODE = 43;
  private static final String ENABLED = "calendarSync";
  private static final String STATUS = "calendarSyncStatus";
  private static final String KNOWN = "calendarSynced";
  private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

  private CalendarSync() {}

  private static SharedPreferences settings(Context c) {
    return c.getSharedPreferences("settings", 0);
  }

  private static String account(Context c) {
    return c.getString(R.string.calendar_account);
  }

  public static boolean permitted(Context c) {
    return c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        && c.checkSelfPermission(Manifest.permission.WRITE_CALENDAR)
            == PackageManager.PERMISSION_GRANTED;
  }

  /** Синхронизация включена пользователем, и разрешение календаря выдано. */
  public static boolean enabled(Context c) {
    return settings(c).getBoolean(ENABLED, false) && permitted(c);
  }

  public static void setEnabled(Context c, boolean on) {
    settings(c).edit().putBoolean(ENABLED, on).apply();
  }

  /** Последний итог для экрана настроек. */
  public static String status(Context c) {
    return settings(c).getString(STATUS, "");
  }

  private static void status(Context c, String message) {
    settings(c).edit().putString(STATUS, message).apply();
  }

  /** Сверяет календарь с заметками в фоне; если синхронизация выключена, ничего не делает. */
  public static void runAsync(Context c) {
    if (!enabled(c)) return;
    Context app = c.getApplicationContext();
    WORKER.execute(() -> run(app));
  }

  /** Выключение: удаляет календарь «Мимоходом» вместе с событиями и забывает отпечатки. */
  public static void removeAsync(Context c) {
    Context app = c.getApplicationContext();
    WORKER.execute(() -> remove(app));
  }

  private static Uri asAdapter(Context c, Uri uri) {
    return uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account(c))
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build();
  }

  private static synchronized void run(Context c) {
    if (!enabled(c)) return;
    try {
      ContentResolver resolver = c.getContentResolver();
      long calendar = calendarId(c, resolver, true);
      if (calendar < 0) {
        status(c, "Не удалось создать календарь «Мимоходом»");
        return;
      }
      List<Note> notes;
      try (Store store = new Store(c)) {
        notes = store.notes();
      }
      Map<Long, Note> byId = new HashMap<>();
      for (Note n : notes) byId.put(n.id, n);
      SharedPreferences remembered = c.getSharedPreferences(KNOWN, 0);
      Map<Long, String> known = new HashMap<>();
      for (Map.Entry<String, ?> e : remembered.getAll().entrySet()) {
        if (!e.getKey().startsWith("n.") || !(e.getValue() instanceof String)) continue;
        try {
          known.put(Long.parseLong(e.getKey().substring(2)), (String) e.getValue());
        } catch (NumberFormatException ignored) {
          // Чужой ключ: пропускаем.
        }
      }
      CalendarSyncPlan.Plan plan =
          CalendarSyncPlan.plan(notes, events(resolver, calendar), known, System.currentTimeMillis());
      SharedPreferences.Editor memory = remembered.edit();
      int added = 0, updated = 0, removed = 0, failed = 0;
      for (CalendarSyncPlan.Action action : plan.actions) {
        try {
          if (action.kind == CalendarSyncPlan.Kind.DELETE) {
            resolver.delete(
                ContentUris.withAppendedId(asAdapter(c, Events.CONTENT_URI), action.eventId), null, null);
            removed++;
            continue;
          }
          Note n = byId.get(action.noteId);
          CalendarLink link = n == null ? null : CalendarLink.of(n.title, n.body, n.reminder, n.repeat);
          if (link == null) continue;
          ContentValues values = eventValues(calendar, action, link);
          if (action.kind == CalendarSyncPlan.Kind.INSERT) {
            if (resolver.insert(asAdapter(c, Events.CONTENT_URI), values) == null) {
              failed++;
              continue;
            }
            added++;
          } else {
            resolver.update(
                ContentUris.withAppendedId(asAdapter(c, Events.CONTENT_URI), action.eventId),
                values,
                null,
                null);
            updated++;
          }
          memory.putString("n." + action.noteId, action.signature);
        } catch (RuntimeException e) {
          failed++;
        }
      }
      for (Long id : plan.forget) memory.remove("n." + id);
      memory.apply();
      if (added + updated + removed + failed > 0 || status(c).isEmpty() || !status(c).startsWith("Синхронизировано")) {
        String time = new SimpleDateFormat("d MMM, HH:mm", new Locale("ru")).format(new Date());
        status(
            c,
            "Синхронизировано "
                + time
                + (added + updated + removed == 0 ? "" : ": +" + added + ", ~" + updated + ", −" + removed)
                + (failed == 0 ? "" : ". Ошибок: " + failed));
      }
    } catch (SecurityException e) {
      status(c, "Нет доступа к календарю: разрешите его в настройках Android");
    } catch (RuntimeException e) {
      status(c, "Ошибка календаря: " + e.getClass().getSimpleName());
    }
  }

  private static ContentValues eventValues(
      long calendar, CalendarSyncPlan.Action action, CalendarLink link) {
    ContentValues v = new ContentValues();
    v.put(Events.CALENDAR_ID, calendar);
    v.put(Events.TITLE, link.title);
    v.put(Events.DESCRIPTION, link.description);
    v.put(Events.DTSTART, link.begin);
    v.put(Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
    v.put(Events.ALL_DAY, 0);
    v.put(Events.HAS_ALARM, 0);
    if (link.rrule != null) {
      // У повторяющегося события в календаре нет конца, только длительность.
      v.put(Events.RRULE, link.rrule);
      v.put(Events.DURATION, "PT1H");
      v.putNull(Events.DTEND);
    } else {
      v.put(Events.DTEND, link.end);
      v.putNull(Events.RRULE);
      v.putNull(Events.DURATION);
    }
    v.put(Events._SYNC_ID, CalendarSyncPlan.syncId(action.noteId));
    v.put(Events.SYNC_DATA1, action.signature);
    return v;
  }

  /** Номер календаря текущего варианта; при {@code create} создаёт его, если нет. */
  private static long calendarId(Context c, ContentResolver resolver, boolean create) {
    String account = account(c);
    String[] args = {account, CalendarContract.ACCOUNT_TYPE_LOCAL};
    try (Cursor cursor =
        resolver.query(
            Calendars.CONTENT_URI,
            new String[] {Calendars._ID},
            Calendars.ACCOUNT_NAME + "=? AND " + Calendars.ACCOUNT_TYPE + "=?",
            args,
            null)) {
      if (cursor != null && cursor.moveToFirst()) return cursor.getLong(0);
    }
    if (!create) return -1;
    ContentValues v = new ContentValues();
    v.put(Calendars.ACCOUNT_NAME, account);
    v.put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
    v.put(Calendars.NAME, "mimohodom");
    v.put(Calendars.CALENDAR_DISPLAY_NAME, account);
    v.put(Calendars.CALENDAR_COLOR, 0xFF28634B);
    v.put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER);
    v.put(Calendars.OWNER_ACCOUNT, account);
    v.put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().getID());
    v.put(Calendars.VISIBLE, 1);
    v.put(Calendars.SYNC_EVENTS, 1);
    Uri created = resolver.insert(asAdapter(c, Calendars.CONTENT_URI), v);
    return created == null ? -1 : ContentUris.parseId(created);
  }

  private static List<CalendarSyncPlan.Event> events(ContentResolver resolver, long calendar) {
    List<CalendarSyncPlan.Event> out = new ArrayList<>();
    try (Cursor cursor =
        resolver.query(
            Events.CONTENT_URI,
            new String[] {Events._ID, Events._SYNC_ID, Events.SYNC_DATA1},
            Events.CALENDAR_ID + "=? AND " + Events.DELETED + "=0",
            new String[] {String.valueOf(calendar)},
            null)) {
      while (cursor != null && cursor.moveToNext())
        out.add(
            new CalendarSyncPlan.Event(
                cursor.getLong(0),
                CalendarSyncPlan.noteIdOf(cursor.getString(1)),
                cursor.getString(2)));
    }
    return out;
  }

  private static synchronized void remove(Context c) {
    try {
      if (permitted(c))
        c.getContentResolver()
            .delete(
                asAdapter(c, Calendars.CONTENT_URI),
                Calendars.ACCOUNT_NAME + "=? AND " + Calendars.ACCOUNT_TYPE + "=?",
                new String[] {account(c), CalendarContract.ACCOUNT_TYPE_LOCAL});
    } catch (RuntimeException ignored) {
      // Календарь уже удалён или недоступен: забываем состояние в любом случае.
    }
    c.getSharedPreferences(KNOWN, 0).edit().clear().apply();
    status(c, "");
  }
}
