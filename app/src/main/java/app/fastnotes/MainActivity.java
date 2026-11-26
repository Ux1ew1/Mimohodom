package app.fastnotes;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.text.method.*;
import android.text.style.ClickableSpan;
import android.text.style.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.core.view.OneShotPreDrawListener;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.json.*;

public final class MainActivity extends Activity
    implements ChatRunner.Listener, ImageRunner.Listener, VideoRunner.Listener, DayDigestRunner.Listener {
  /** Белый для текста и значков на цветной заливке: одинаковый в обеих темах. */
  private static final int WHITE = 0xFFFFFFFF;
  private static final int NOTE_BODY_LIMIT = 100000;
  /**
   * Палитра текущей темы. Задаётся {@link #applyPalette} до построения экрана; GREEN — цвет
   * текста и значков акцента, FILL — заливка главных кнопок (на ней лежит белый), SURFACE —
   * карточки, листы и поля (в светлой теме белые).
   */
  private static int PAPER = 0xFFF7F8F2,
      INK = 0xFF1E3028,
      MUTED = 0xFF6C786F,
      GREEN = 0xFF28634B,
      FILL = 0xFF28634B,
      LIME = 0xFFE9F1D6,
      LINE = 0xFFDFE4DA,
      SURFACE = 0xFFFFFFFF,
      RED = 0xFFA74335,
      STROKE = 0xFFAEBBA8,
      DOCK = 0xF2FFFFFF,
      STACK_BG = 0xFFF0E8FA,
      STACK_FG = 0xFF75449B,
      STACK_LAYER = 0xFFE1CEEF,
      STACK_LINE = 0xFFAD83CA,
      REMINDER_BG = 0xFFEDF0E2,
      RECAP_BG = 0xFFEEF0EA;
  private boolean dark;
  private boolean importingNotes;

  /** Режим темы из настроек: auto (как в системе, по умолчанию), light, dark. */
  private boolean wantsDark() {
    String mode = getSharedPreferences("settings", 0).getString("theme", "auto");
    if ("dark".equals(mode)) return true;
    if ("light".equals(mode)) return false;
    return (getResources().getConfiguration().uiMode
            & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
  }

  private static void applyPalette(boolean night) {
    if (night) {
      PAPER = 0xFF111815;
      INK = 0xFFE4EBE4;
      MUTED = 0xFF93A298;
      GREEN = 0xFF6FCB9F;
      FILL = 0xFF2F7D5D;
      LIME = 0xFF21302A;
      LINE = 0xFF2A3731;
      SURFACE = 0xFF1A2420;
      RED = 0xFFE5877A;
      STROKE = 0xFF3A4A41;
      DOCK = 0xF21A2420;
      STACK_BG = 0xFF2C2338;
      STACK_FG = 0xFFCDA9E8;
      STACK_LAYER = 0xFF3A2D49;
      STACK_LINE = 0xFF6D4F86;
      REMINDER_BG = 0xFF222B1E;
      RECAP_BG = 0xFF222B26;
    } else {
      PAPER = 0xFFF7F8F2;
      INK = 0xFF1E3028;
      MUTED = 0xFF6C786F;
      GREEN = 0xFF28634B;
      FILL = 0xFF28634B;
      LIME = 0xFFE9F1D6;
      LINE = 0xFFDFE4DA;
      SURFACE = 0xFFFFFFFF;
      RED = 0xFFA74335;
      STROKE = 0xFFAEBBA8;
      DOCK = 0xF2FFFFFF;
      STACK_BG = 0xFFF0E8FA;
      STACK_FG = 0xFF75449B;
      STACK_LAYER = 0xFFE1CEEF;
      STACK_LINE = 0xFFAD83CA;
      REMINDER_BG = 0xFFEDF0E2;
      RECAP_BG = 0xFFEEF0EA;
    }
  }

  /** Фон страницы: у новой заметки и ленты чатов — белый лист на весь экран, у остальных — бумажный. */
  private int pageColor() {
    return "edit".equals(page) || "home".equals(page) || "chats".equals(page) || "today".equals(page) ? SURFACE : PAPER;
  }

  /** Тема окна и системных панелей под выбранную палитру (значки панелей светлые на тёмном). */
  private void applySystemBars() {
    int background = pageColor();
    getWindow().setBackgroundDrawable(new ColorDrawable(background));
    if (Build.VERSION.SDK_INT >= 30) {
      WindowInsetsController controller = getWindow().getInsetsController();
      if (controller != null) {
        int mask =
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        controller.setSystemBarsAppearance(dark ? 0 : mask, mask);
      }
    } else {
      View decor = getWindow().getDecorView();
      int flags = decor.getSystemUiVisibility();
      int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
      if (Build.VERSION.SDK_INT >= 26) light |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
      decor.setSystemUiVisibility(dark ? flags & ~light : flags | light);
      getWindow().setStatusBarColor(background);
      getWindow().setNavigationBarColor(background);
    }
  }
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  /** Долгое копирование GGUF идёт отдельно, чтобы не задерживать «Поделиться», картинки и экспорт. */
  private final ExecutorService heavy = Executors.newSingleThreadExecutor();
  private Store store;
  private SharedPreferences prefs;
  private LinearLayout root, content;
  private FrameLayout frame, dock;
  private int lastDockTab = -2;
  private View dockFade;
  /** Запас под плавающей панелью у нижней полосы новой заметки; без клавиатуры. */
  private View dockSpace;
  private int navInset;
  private boolean keyboardUp, searchOpen, focusEditor, focusSearch;
  private Runnable closeSearch;
  private PopupWindow suggestionsPopup;
  private EditText titleField, bodyField, chatInput;
  // Вкладки слева направо: 0 «Заметки», 1 «Записать», 2 «ИИ-чат». 3 — настройки вне нижней панели.
  private int tab = 1;
  private String page = "home";
  private int todayOrigin;
  private Note scheduleBefore, scheduleAfter;
  private long movedTo;
  private final Handler dayClock = new Handler(Looper.getMainLooper());
  private final Runnable refreshDay = () -> {
    if ("today".equals(page) && !isFinishing() && !isDestroyed()) render();
  };
  private long editing = 0, reminder = 0, chat = 0;
  /** Повтор и свой интервал напоминания в открытом редакторе (-1 — общая настройка). */
  private int repeatMode = 0, leadChoice = -1;
  /** Все даты серии «по списку» в открытом редакторе (JSON, {@link ReminderPolicy#parseTimes}). */
  private String reminderTimes = "";
  /** Какая из картинок открытой заметки выбрана в ленте; {@code selectedImageFor} — её заметка. */
  private int selectedImage = 0;
  /** Положение ленты миниатюр заметки: перерисовка экрана не должна возвращать её в начало. */
  private int stripScroll = 0;
  private long selectedImageFor = -1;
  private boolean editorActive = false, rendering = false;
  /**
   * Открытая заметка, какой её загрузили в редактор. Поля, совпадающие с ней, пользователь не
   * трогал: при выходе их не записываем, чтобы не затереть изменения ИИ за это время.
   */
  private Note editorBase;
  private String returnPage = "notes";
  private TextView reminderButton, folderButton, analysisStatusView;
  private Popup managerPreview, analysisLogDialog;
  private TextView analysisLogText;
  private final SharedPreferences.OnSharedPreferenceChangeListener analysisListener =
      (preferences, key) -> {
        if ("analysisTime".equals(key) || "analysisStatus".equals(key))
          runOnUiThread(
              () -> {
                if (isDestroyed()) return;
                if (analysisLogDialog != null && analysisLogDialog.isShowing())
                  analysisLogText.setText(analysisHistoryText());
                if ("analysisTime".equals(key)
                    && "manager".equals(page)
                    && !rendering
                    && (managerPreview == null || !managerPreview.isShowing())) render();
                else if (analysisStatusView != null)
                  analysisStatusView.setText(analysisStatusText());
              });
      };
  private String search = "", chatSearch = "";
  /** Лента чатов: открыт ли поиск, фильтр, развёрнут ли «Помощник» и ответ из его карточки. */
  private boolean chatSearchOpen, helperOpen, notesSearchOpen;
  private int chatFilter = ChatFeed.ALL;
  private long helperReplyChat;
  private String helperReply = "";
  /** Открытая папка в списке заметок (0 — «Все заметки») и папка заметки в редакторе. */
  private long folder = 0, editFolder = 0;
  private int folderScrollX;
  private final List<Note> visibleNotes = new ArrayList<>();
  private float swipeX, swipeY;
  private boolean swipeAllowed, swipeConsumed;
  private boolean pageAnimating;
  private String noteImage = "", pickerPage = "";
  private long pickerTarget;

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    if (pageAnimating) return true;
    int action = event.getActionMasked();
    if (action == MotionEvent.ACTION_DOWN) {
      swipeX = event.getRawX();
      swipeY = event.getRawY();
      swipeConsumed = false;
      Rect bounds = new Rect();
      swipeAllowed =
          tab != 3
              && pageSurface().getGlobalVisibleRect(bounds)
              && bounds.contains((int) swipeX, (int) swipeY)
              && swipeX > bounds.left + dp(24)
              && swipeX < bounds.right - dp(24)
              && !touchesInput(root, (int) swipeX, (int) swipeY);
    }
    if (action == MotionEvent.ACTION_POINTER_DOWN) {
      swipeAllowed = false;
      if (swipeConsumed) {
        finishSwipe(false);
        return true;
      }
    }
    if (action == MotionEvent.ACTION_MOVE && swipeAllowed) {
      float dx = event.getRawX() - swipeX, dy = event.getRawY() - swipeY;
      int slop = ViewConfiguration.get(this).getScaledTouchSlop();
      if (!swipeConsumed && Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx))
        swipeAllowed = false;
      if (!swipeConsumed
          && swipeAllowed
          && Math.abs(dx) > slop
          && Math.abs(dx) > Math.abs(dy) * 1.5f) {
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
        swipeConsumed = true;
      }
      if (swipeConsumed) {
        int width = pageSurface().getWidth();
        // At the first and last tabs, resist dragging beyond the available pages.
        if ((tab == 0 && dx > 0) || (tab == 2 && dx < 0)) dx *= 0.2f;
        pageSurface().setTranslationX(Math.max(-width, Math.min(width, dx)));
        return true;
      }
    }
    if (swipeConsumed) {
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
        finishSwipe(action == MotionEvent.ACTION_UP);
      return true;
    }
    return super.dispatchTouchEvent(event);
  }

  private void finishSwipe(boolean released) {
    swipeAllowed = false;
    swipeConsumed = false;
    View surface = pageSurface();
    float offset = surface.getTranslationX();
    int next = tab + (offset < 0 ? 1 : -1);
    if (released && next >= 0 && next < 3 && Math.abs(offset) >= surface.getWidth() * 0.25f) {
      buzz(0);
      animateNavigate(next);
    } else {
      pageAnimating = true;
      surface
          .animate()
          .translationX(0)
          .setDuration(android.animation.ValueAnimator.areAnimatorsEnabled() ? 180 : 0)
          .withEndAction(() -> pageAnimating = false)
          .start();
    }
  }

  private boolean touchesInput(View view, int x, int y) {
    if (view == null || view.getVisibility() != View.VISIBLE) return false;
    Rect bounds = new Rect();
    if (!view.getGlobalVisibleRect(bounds) || !bounds.contains(x, y)) return false;
    if (view instanceof EditText
        || view instanceof Spinner
        || view instanceof Switch
        || view instanceof HorizontalScrollView
        || view instanceof SeekBar) return true;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        if (touchesInput(group.getChildAt(i), x, y)) return true;
    return false;
  }

  @Override
  public void onCreate(Bundle state) {
    // Тема и палитра выбираются до построения экрана; при смене системной темы активность
    // пересоздаётся и попадает сюда снова.
    dark = wantsDark();
    applyPalette(dark);
    setTheme(dark ? R.style.AppThemeDark : R.style.AppThemeLight);
    super.onCreate(state);
    store = new Store(this);
    prefs = getSharedPreferences("settings", 0);
    prefs.registerOnSharedPreferenceChangeListener(analysisListener);
    Reminders.channel(this);
    if (state != null) {
      tab = state.getInt("tab", 1);
      page = state.getString("page", "home");
      todayOrigin = state.getInt("todayOrigin", 0);
      long movedId = state.getLong("movedNote");
      if (movedId != 0) {
        Note current = store.note(movedId);
        if (current != null && current.trashed == 0 && current.repeat == state.getInt("movedRepeat")
            && current.reminder == state.getLong("newReminder")
            && current.reminderTimes.equals(state.getString("newTimes"))) {
          scheduleAfter = current;
          scheduleBefore = new Note(movedId, current.title, "", 0, 0, state.getLong("oldReminder"));
          scheduleBefore.reminderTimes = state.getString("oldTimes", "");
          movedTo = state.getLong("movedTo");
        }
      }
      editing = state.getLong("editing");
      chat = state.getLong("chat");
      returnPage = state.getString("return", "notes");
      search = state.getString("search", "");
      notesSearchOpen = state.getBoolean("notesSearchOpen");
      folder = state.getLong("folder");
      pickerPage = state.getString("pickerPage", "");
      pickerTarget = state.getLong("pickerTarget");
    }
    if (state == null) routeIntent(getIntent());
    ChatRunner.listen(this);
    ImageRunner.listen(this);
    VideoRunner.listen(this);
    DayDigestRunner.listen(this);
    BackgroundAnalysis.configure(this);
    render();
    // Файлы изображений, на которые больше никто не ссылается (удалённые заметки, замены).
    io.execute(
        () -> {
          // Вопросы помощника: после обновления или смены режима будильник ставится заново.
          CheckInJob.arm(getApplicationContext());
          try (Store purgeStore = new Store(getApplicationContext())) {
            // Корзина: заметки старше 30 дней удаляются насовсем, их картинки уберёт очистка ниже.
            if (!purgeStore.purgeTrash(System.currentTimeMillis(), false).isEmpty())
              CalendarSync.runAsync(getApplicationContext());
            // Журнал агента хранится 30 дней.
            AgentLog.prune(purgeStore, System.currentTimeMillis());
            // Эксперимент «баллы внимания»: недельные списания и вопросы без ответа.
            purgeStore.attentionSweep(System.currentTimeMillis());
          } catch (Exception ignored) {
            // Не удалось сейчас — попробуем при следующем запуске.
          }
          ImageFiles.cleanup(getApplicationContext(), 10 * 60_000L);
          java.io.File[] shots = new java.io.File(getCacheDir(), "camera").listFiles();
          if (shots != null)
            for (java.io.File shot : shots)
              if (System.currentTimeMillis() - shot.lastModified() > 3_600_000L) shot.delete();
        });
    if (Build.VERSION.SDK_INT >= 33)
      getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::goBack);
  }

  /** Открывает нужный экран по уведомлению или по быстрому действию на значке приложения. */
  private void routeIntent(Intent intent) {
    if (intent == null) return;
    long requested = intent.getLongExtra("note", 0);
    if (requested > 0) {
      editorSearch = null;
      planCollapsed = false;
      editing = requested;
      page = "edit";
      tab = 0;
      return;
    }
    long question = intent.getLongExtra("chat", 0);
    if (question > 0) {
      // Уведомление «Помощник спрашивает»: открываем чат с его вопросом.
      chat = question;
      page = "chat";
      tab = 2;
      return;
    }
    if (intent.getBooleanExtra("manager", false)) {
      page = "manager";
      tab = 0;
      return;
    }
    String action = intent.getAction();
    if (action == null) return;
    switch (action) {
      case Intent.ACTION_SEND -> handleShare(intent);
      case "app.fastnotes.action.NEW_NOTE" -> {
        tab = 1;
        page = "home";
        editing = 0;
        focusEditor = true;
      }
      case "app.fastnotes.action.NEW_CHAT" -> {
        tab = 2;
        page = "chat";
        chat = 0;
      }
      case "app.fastnotes.action.OPEN_NOTES" -> {
        tab = 0;
        page = "notes";
        search = "";
        notesSearchOpen = false;
      }
      case "app.fastnotes.action.SEARCH" -> {
        tab = 0;
        page = "notes";
        search = "";
        focusSearch = true;
      }
      default -> {}
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    stash();
    if (!persistEditedNote()) return;
    hideKeyboard();
    routeIntent(intent);
    render();
  }

  @Override
  protected void onResume() {
    super.onResume();
    ChatRunner.listen(this);
    ImageRunner.listen(this);
    VideoRunner.listen(this);
    DayDigestRunner.listen(this);
    imageFinished();
    videoFinished();
    restoreReminders();
    if (reliabilityBox != null && "settings.reminders".equals(page))
      fillReliability(reliabilityBox);
    if ("chat".equals(page) || "notes".equals(page) || "today".equals(page)) {
      stash();
      render();
    }
    showPendingCreation();
    PeerSync.listen(
        () ->
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  if (pullSync) {
                    // Обмен запущен жестом «потянуть вниз»: сообщаем итог.
                    pullSync = false;
                    toast(PeerSync.status(this));
                  }
                  if (rendering) return;
                  if ("settings.sync".equals(page)
                      || (PeerSync.changedLastRun() && ("notes".equals(page) || "today".equals(page)))) {
                    stash();
                    render();
                  }
                }));
    PeerSync.runAsync(this, false);
  }

  @Override
  protected void onPause() {
    dayClock.removeCallbacks(refreshDay);
    flushCredentials();
    stash();
    persistEditedNote();
    // Ловит всё, что изменилось не через редактор: чат, помощник, удаление.
    CalendarSync.runAsync(this);
    PeerSync.listen(null);
    PeerSync.runAsync(this, false);
    super.onPause();
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(code, permissions, results);
    if (code != CalendarSync.REQUEST_CODE) return;
    boolean granted = results.length > 0;
    for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) granted = false;
    if (granted) {
      CalendarSync.setEnabled(this, true);
      CalendarSync.runAsync(this);
      toast("Календарь «Мимоходом» подключён");
    } else {
      CalendarSync.setEnabled(this, false);
      toast("Без доступа к календарю синхронизация не работает");
    }
    if ("settings.reminders".equals(page)) render();
  }

  @Override
  protected void onDestroy() {
    if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(analysisListener);
    io.shutdown();
    heavy.shutdown();
    store.close();
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    stash();
    out.putString("pickerPage", pickerPage);
    out.putLong("pickerTarget", pickerTarget);
    out.putInt("tab", tab);
    out.putString("page", page);
    out.putInt("todayOrigin", todayOrigin);
    if (scheduleAfter != null) {
      out.putLong("movedNote", scheduleAfter.id);
      out.putInt("movedRepeat", scheduleAfter.repeat);
      out.putLong("newReminder", scheduleAfter.reminder);
      out.putString("newTimes", scheduleAfter.reminderTimes);
      out.putLong("oldReminder", scheduleBefore.reminder);
      out.putString("oldTimes", scheduleBefore.reminderTimes);
      out.putLong("movedTo", movedTo);
    }
    out.putLong("editing", editing);
    out.putLong("chat", chat);
    out.putString("return", returnPage);
    out.putString("search", search);
    out.putBoolean("notesSearchOpen", notesSearchOpen);
    out.putLong("folder", folder);
    super.onSaveInstanceState(out);
  }

  @Override
  public void onBackPressed() {
    goBack();
  }

  private void goBack() {
    if (suggestionsPopup != null && suggestionsPopup.isShowing()) {
      suggestionsPopup.dismiss();
      return;
    }
    if (("notes".equals(page) || "chats".equals(page)) && searchOpen && closeSearch != null) {
      closeSearch.run();
      return;
    }
    if ("edit".equals(page)) {
      stash();
      if (!persistEditedNote()) return;
      page = returnPage;
      tab = page.equals("today") ? todayOrigin : page.equals("chat") ? 2 : page.equals("home") ? 1 : 0;
      render();
    } else if ("today".equals(page)) {
      navigate(todayOrigin);
    } else if (page.startsWith("settings.")) {
      flushCredentials();
      hideKeyboard();
      page = "settings";
      render();
    } else if ("manager".equals(page) || "trash".equals(page)) {
      page = "notes";
      render();
    } else if ("chat".equals(page)) {
      stash();
      page = "chats";
      render();
    } else if (tab != 1) {
      animateNavigate(1);
    } else super.onBackPressed();
  }

  @Override
  public void changed() {
    if (!isFinishing() && !isDestroyed() && "chat".equals(page)) {
      stash();
      render();
    }
    if (!isFinishing() && !isDestroyed()) showPendingCreation();
  }

  @Override
  public void digestChanged() {
    if (!isFinishing() && !isDestroyed() && "today".equals(page)) render();
  }

  private void showPendingCreation() {
    long id = ChatRunner.createdNote.getAndSet(0);
    if (id > 0) celebrateNote(id);
  }

  private void celebrateNote(long id) {
    Note saved = store.note(id);
    if (saved == null) return;
    LinearLayout host = root;
    host.post(
        () -> {
          if (isDestroyed() || root != host) return;
          LinearLayout card = column();
          card.setGravity(Gravity.CENTER);
          pad(card, 22);
          GradientDrawable background = shape(SURFACE, 14);
          background.setStroke(dp(1), LINE);
          card.setBackground(background);
          card.setElevation(dp(12));
          ImageView check = new ImageView(this);
          check.setBackground(shape(LIME, 14));
          check.setPadding(dp(14), dp(14), dp(14), dp(14));
          check.setImageDrawable(new Glyph("check", GREEN, 28));
          card.addView(check, new LinearLayout.LayoutParams(dp(56), dp(56)));
          gap(card, 12);
          TextView title = text("Заметка создана", 20, GREEN);
          title.setGravity(Gravity.CENTER);
          title.setTypeface(null, Typeface.BOLD);
          card.addView(title);
          gap(card, 6);
          TextView name = text(saved.title, 14, MUTED);
          name.setGravity(Gravity.CENTER);
          name.setMaxLines(2);
          name.setEllipsize(TextUtils.TruncateAt.END);
          card.addView(name);
          int width = Math.min(dp(320), host.getWidth() - dp(44));
          if (width <= 0) return;
          card.measure(
              View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
              View.MeasureSpec.makeMeasureSpec(host.getHeight(), View.MeasureSpec.AT_MOST));
          int left = (host.getWidth() - width) / 2,
              top = Math.max(dp(20), (host.getHeight() - card.getMeasuredHeight()) / 3);
          host.getOverlay().add(card);
          card.layout(left, top, left + width, top + card.getMeasuredHeight());
          host.announceForAccessibility("Заметка создана: " + saved.title);
          if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            host.postDelayed(() -> host.getOverlay().remove(card), 1400);
            return;
          }
          card.setAlpha(0);
          card.setScaleX(.9f);
          card.setScaleY(.9f);
          card.setTranslationY(dp(24));
          check.setScaleX(0);
          check.setScaleY(0);
          card.animate()
              .alpha(1)
              .scaleX(1)
              .scaleY(1)
              .translationY(0)
              .setDuration(300)
              .setInterpolator(new android.view.animation.DecelerateInterpolator())
              .start();
          check
              .animate()
              .scaleX(1)
              .scaleY(1)
              .setStartDelay(100)
              .setDuration(350)
              .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f))
              .start();
          host.postDelayed(
              () ->
                  card.animate()
                      .alpha(0)
                      .translationY(-dp(12))
                      .setDuration(200)
                      .withEndAction(() -> host.getOverlay().remove(card))
                      .start(),
              1550);
        });
  }

  /** Короткая вибрация: 0 лёгкий отклик, 1 подтверждение, 2 отказ. Выключается в настройках. */
  private void buzz(int kind) {
    vibrate(kind, prefs == null || prefs.getBoolean("haptics", true));
  }

  private void vibrate(int kind, boolean enabled) {
    if (!enabled) return;
    Vibrator vibrator = getSystemService(Vibrator.class);
    if (vibrator == null || !vibrator.hasVibrator()) return;
    VibrationEffect effect;
    if (Build.VERSION.SDK_INT >= 29)
      effect =
          VibrationEffect.createPredefined(
              kind == 0
                  ? VibrationEffect.EFFECT_TICK
                  : kind == 1 ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_DOUBLE_CLICK);
    else if (kind == 2) effect = VibrationEffect.createWaveform(new long[] {0, 25, 60, 25}, -1);
    else
      effect =
          VibrationEffect.createOneShot(kind == 0 ? 12 : 25, VibrationEffect.DEFAULT_AMPLITUDE);
    vibrator.vibrate(effect);
  }

  private int dp(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private void pad(View v, int n) {
    v.setPadding(dp(n), dp(n), dp(n), dp(n));
  }

  private GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  private android.graphics.drawable.Drawable ripple(int color, int radius) {
    return new RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x2028634B), shape(color, radius), null);
  }

  private TextView text(String value, int size, int color) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    return t;
  }

  private void gap(LinearLayout l, int height) {
    l.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
  }

  private TextView button(String label, boolean primary, Runnable click) {
    TextView b = text(label, 15, primary ? WHITE : GREEN);
    b.setTypeface(null, Typeface.BOLD);
    b.setGravity(Gravity.CENTER);
    b.setMinHeight(dp(50));
    b.setPadding(dp(16), dp(10), dp(16), dp(10));
    b.setBackground(ripple(primary ? FILL : LIME, 12));
    b.setFocusable(true);
    b.setOnClickListener(
        v -> {
          buzz(0);
          click.run();
        });
    return b;
  }

  private TextView iconButton(String label, String icon, Runnable click) {
    TextView b = button(label, false, click);
    b.setCompoundDrawablesWithIntrinsicBounds(new Glyph(icon, GREEN, 20), null, null, null);
    b.setCompoundDrawablePadding(dp(8));
    return b;
  }

  /** Мелкая серая пометка для плотных экранов, например ИИ-чата. */
  private TextView chatNote(LinearLayout l, String value) {
    TextView t = text(value, 11, MUTED);
    t.setLineSpacing(dp(1), 1);
    l.addView(t);
    return t;
  }

  /** Кнопка меньшего размера: ниже, с мелким шрифтом, чтобы на экране помещалось больше. */
  private TextView compactButton(String label, boolean primary, Runnable click) {
    TextView b = button(label, primary, click);
    b.setTextSize(12);
    b.setMinHeight(dp(34));
    b.setPadding(dp(10), dp(6), dp(10), dp(6));
    b.setBackground(ripple(primary ? FILL : LIME, 10));
    return b;
  }

  private TextView secondary(LinearLayout l, String value) {
    TextView t = text(value, 13, MUTED);
    t.setLineSpacing(dp(3), 1);
    l.addView(t);
    return t;
  }

  private void heading(String title, String subtitle) {
    TextView t = text(title, 32, INK);
    t.setTypeface(Typeface.create("serif", Typeface.NORMAL));
    content.addView(t);
    gap(content, 8);
    if (!subtitle.isEmpty()) secondary(content, subtitle);
    gap(content, 24);
  }

  private ScrollView scroll(LinearLayout l) {
    ScrollView s = new ScrollView(this);
    s.setFillViewport(true);
    s.setClipToPadding(false);
    s.addView(l);
    return s;
  }

  private EditText input(String hint, boolean multiline) {
    return configureInput(new EditText(this), hint, multiline);
  }

  private EditText configureInput(EditText e, String hint, boolean multiline) {
    e.setTextColor(INK);
    e.setHintTextColor(MUTED);
    e.setTextSize(prefs.getBoolean("large", false) ? 20 : 17);
    e.setHint(hint);
    e.setContentDescription(hint);
    e.setPadding(dp(16), dp(14), dp(16), dp(14));
    e.setBackground(shape(SURFACE, 10));
    e.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            | (multiline ? android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
    e.setGravity(Gravity.TOP | Gravity.START);
    e.setSingleLine(!multiline);
    return e;
  }

  private void watch(EditText field, Runnable action) {
    field.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

          public void onTextChanged(CharSequence s, int st, int b, int c) {
            if (!rendering) action.run();
          }

          public void afterTextChanged(Editable e) {}
        });
  }

  private void navigate(int next) {
    stash();
    if (!persistEditedNote()) return;
    hideKeyboard();
    tab = next;
    page = new String[] {"notes", "home", "chats", "settings"}[next];
    editing = 0;
    render();
  }

  private View pageSurface() {
    return content.getParent() instanceof ScrollView ? (View) content.getParent() : content;
  }

  private void animateNavigate(int next) {
    if (pageAnimating) return;
    if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
      navigate(next);
      return;
    }
    int previous = tab, direction = next > tab ? 1 : -1;
    View outgoing = pageSurface();
    float dragOffset = outgoing.getTranslationX();
    outgoing.setTranslationX(0);
    int[] origin = new int[2];
    outgoing.getLocationOnScreen(origin);
    int width = outgoing.getWidth(), height = outgoing.getHeight();
    navigate(next);
    if (tab == previous || width == 0 || height == 0) return;
    View incoming = pageSurface();
    LinearLayout destination = root;
    pageAnimating = true;
    incoming.setTranslationX(direction * width + dragOffset);
    destination.post(
        () -> {
          if (isDestroyed() || root != destination) {
            incoming.setTranslationX(0);
            pageAnimating = false;
            return;
          }
          int[] offset = new int[2];
          destination.getLocationOnScreen(offset);
          destination.getOverlay().add(outgoing);
          outgoing.layout(
              origin[0] - offset[0],
              origin[1] - offset[1],
              origin[0] - offset[0] + width,
              origin[1] - offset[1] + height);
          outgoing.setTranslationX(dragOffset);
          android.view.animation.Interpolator easing =
              new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);
          outgoing
              .animate()
              .translationX(-direction * width)
              .setDuration(280)
              .setInterpolator(easing)
              .start();
          incoming
              .animate()
              .translationX(0)
              .setDuration(280)
              .setInterpolator(easing)
              .withEndAction(
                  () -> {
                    destination.getOverlay().remove(outgoing);
                    outgoing.setTranslationX(0);
                    pageAnimating = false;
                  })
              .start();
        });
  }

  private void render() {
    dayClock.removeCallbacks(refreshDay);
    flushCredentials();
    rendering = true;
    analysisStatusView = null;
    editorActive = false;
    titleField = null;
    bodyField = null;
    chatInput = null;
    if (suggestionsPopup != null) suggestionsPopup.dismiss();
    dockSpace = null;
    if (!"notes".equals(page)) notesSearchOpen = false;
    if (!"chats".equals(page)) {
      // Поиск и ответ в карточке помощника живут, пока открыта лента чатов.
      chatSearchOpen = false;
      helperReplyChat = 0;
      helperReply = "";
    }
    root = column();
    root.setBackgroundColor(pageColor());
    frame = new FrameLayout(this);
    frame.setBackgroundColor(pageColor());
    frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
    setContentView(frame);
    applySystemBars();
    if (Build.VERSION.SDK_INT >= 30) {
      getWindow().setDecorFitsSystemWindows(false);
      root.setOnApplyWindowInsetsListener(
          (v, insets) -> {
            android.graphics.Insets bars =
                insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            navInset = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
            keyboardUp = insets.isVisible(WindowInsets.Type.ime());
            placeDock();
            return WindowInsets.CONSUMED;
          });
    } else {
      root.setOnApplyWindowInsetsListener(
          (v, insets) -> {
            v.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
            navInset = insets.getStableInsetBottom();
            keyboardUp = insets.getSystemWindowInsetBottom() > navInset + dp(100);
            placeDock();
            return insets.consumeSystemWindowInsets();
          });
    }
    content = column();
    // Плавающая нижняя панель перекрывает низ страницы, поэтому внизу оставляем под неё место.
    boolean notesList = "notes".equals(page);
    // У новой заметки место под панелью держит её нижняя полоса со значками (dockSpace).
    boolean compactBottom = "edit".equals(page) || "chat".equals(page) || "home".equals(page);
    content.setPadding(dp(22), dp(8), dp(22), notesList ? 0 : dp(compactBottom ? 16 : 100));
    if ("notes".equals(page) || "chat".equals(page)) {
      root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    } else root.addView(scroll(content), new LinearLayout.LayoutParams(-1, 0, 1));
    switch (page) {
      case "home" -> editor(0);
      case "edit" -> editor(editing);
      case "notes" -> notes();
      case "today" -> todayPage();
      case "manager" -> managerPage();
      case "trash" -> trashPage();
      case "chats" -> chats();
      case "chat" -> chatPage();
      case "settings" -> settings();
      case "settings.ai" -> settingsAi();
      case "settings.reminders" -> settingsReminders();
      case "settings.assistant" -> settingsAssistant();
      case "settings.sync" -> settingsSync();
      case "settings.app" -> settingsApp();
    }
    if ("edit".equals(page)) {
      dock = null;
      dockFade = null;
    } else bottomNav();
    rendering = false;
  }

  private String analysisStatusText() {
    long time = prefs.getLong("analysisTime", 0);
    return time == 0
        ? "Проверок пока не было"
        : ReviewPolicy.summary(prefs.getString("analysisStatus", ""))
            + " · "
            + new java.text.SimpleDateFormat("HH:mm", new Locale("ru")).format(new Date(time));
  }

  private void showAnalysisStatus() {
    analysisStatusView = text(analysisStatusText(), 14, MUTED);
    content.addView(analysisStatusView);
  }

  private void manualAnalysis() {
    if (!LocalLlm.cloudBackground(this)) {
      toast(
          "Общий обзор идёт через Polza.AI. С GGUF выберите облачный режим или Gemini Nano.");
      return;
    }
    if (!persistEditedNote()) return;
    try {
      if (Secrets.read(this).isEmpty() || prefs.getString("model", "").isEmpty()) {
        new Ask(this)
            .setTitle("Подключите ИИ")
            .setMessage("Сохраните API-ключ Polza.AI и выберите модель в настройках.")
            .setNegativeButton("Позже", null)
            .setPositiveButton("Настройки", (d, w) -> navigate(3))
            .show();
        return;
      }
      String today = java.time.LocalDate.now().toString();
      if (today.equals(prefs.getString("analysisDay", ""))
          && !ReviewPolicy.allowed(
              prefs.getInt("analysisCount", 0), prefs.getBoolean("unlimitedAi", false))) {
        toast("На сегодня использованы все 4 пакета проверки");
        return;
      }
      if (store.totalNotes() == 0) {
        toast("Нет заметок для проверки");
        return;
      }
      if (!prefs.getBoolean("manualReviewConsent", false)) {
        new Ask(this)
            .setTitle("Проверить заметки через Polza.AI?")
            .setMessage(
                "Один платный запрос проверит автоматические названия, даты для напоминаний и"
                    + " возможность объединения записей. До 8 заметок и 12 000 символов; включает"
                    + " записи после прежних ошибок. Дневной лимит можно изменить на экране"
                    + " помощника. Авторские названия сохраняются. Напоминания и объединения —"
                    + " после подтверждения. Фоновая работа от этого не включается.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton(
                "Проверить",
                (d, w) -> {
                  prefs.edit().putBoolean("manualReviewConsent", true).apply();
                  manualAnalysis();
                })
            .show();
        return;
      }
      boolean queued = BackgroundAnalysis.enqueueManual(this);
      if (queued)
        ReviewLog.record(
            this,
            "Общая проверка поставлена в очередь Android. Нужен интернет; повтор после ошибок"
                + " включён.");
      toast(
          queued
              ? "Запущена проверка названий, напоминаний и объединений"
              : "Проверка уже выполняется или ожидает запуска");
    } catch (Exception e) {
      toast("Не удалось запустить проверку. Проверьте API-ключ в настройках.");
    }
  }

  /** Плавающая панель: три значка без подписей, по центру внизу поверх страницы. */
  private void bottomNav() {
    dock = null;
    dockFade = null;
    // Мягкий переход под панелью. Касания проходят сквозь него к списку.
    dockFade = new View(this);
    dockFade.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {pageColor() & 0x00FFFFFF, pageColor()}));
    frame.addView(dockFade, new FrameLayout.LayoutParams(-1, dp(110), Gravity.BOTTOM));
    dock = new FrameLayout(this);
    GradientDrawable background = shape(DOCK, 20);
    background.setStroke(dp(1), LINE);
    dock.setBackground(background);
    dock.setElevation(dp(8));
    // Подсветка активной вкладки лежит под значками и скользит к новой вкладке.
    int step = dp(60);
    View marker = new View(this);
    marker.setBackground(shape(LIME, 14));
    FrameLayout.LayoutParams markerParams = new FrameLayout.LayoutParams(dp(46), dp(46));
    markerParams.leftMargin = dp(14);
    markerParams.topMargin = dp(9);
    dock.addView(marker, markerParams);
    LinearLayout items = row();
    items.setPadding(dp(14), dp(9), dp(14), dp(9));
    dock.addView(items, new FrameLayout.LayoutParams(-2, -2));
    // Настройки (вкладка 3) вне панели: подсветка гаснет и ничего не выделено (индекс -1).
    final int to = tab < 3 && !"today".equals(page) ? tab : -1;
    final int from = lastDockTab == -2 ? to : lastDockTab;
    boolean animate = from != to && android.animation.ValueAnimator.areAnimatorsEnabled();
    final int endIndex = to >= 0 ? to : (from >= 0 ? from : 1);
    final int startIndex = from >= 0 ? from : endIndex;
    final float startAlpha = animate ? (from >= 0 ? 1f : 0f) : (to >= 0 ? 1f : 0f);
    final float endAlpha = to >= 0 ? 1f : 0f;
    marker.setTranslationX(step * (animate ? startIndex : endIndex));
    marker.setAlpha(startAlpha);
    String[] labels = {"Заметки", "Записать", "ИИ-чат"};
    String[] icons = {"notes", "add", "chat"};
    ImageView[] views = new ImageView[3];
    for (int i = 0; i < 3; i++) {
      final int target = i;
      boolean add = i == 1;
      ImageView item = new ImageView(this);
      item.setScaleType(ImageView.ScaleType.CENTER);
      item.setImageDrawable(new Glyph(icons[i], add ? WHITE : MUTED, 24));
      if (!add) item.setColorFilter(i == (animate ? from : to) ? GREEN : MUTED);
      item.setBackground(ripple(add ? FILL : android.graphics.Color.TRANSPARENT, 14));
      item.setContentDescription(labels[i]);
      item.setFocusable(true);
      item.setClickable(true);
      item.setSelected(i == to);
      item.setOnClickListener(
          v -> {
            buzz(0);
            animateNavigate(target);
          });
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
      if (i > 0) lp.leftMargin = dp(14);
      items.addView(item, lp);
      views[i] = item;
    }
    if (animate) {
      android.animation.ArgbEvaluator argb = new android.animation.ArgbEvaluator();
      android.animation.ValueAnimator slide = android.animation.ValueAnimator.ofFloat(0, 1);
      slide.setDuration(280);
      slide.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f));
      slide.addUpdateListener(
          a -> {
            float f = (float) a.getAnimatedValue();
            marker.setTranslationX(step * (startIndex + (endIndex - startIndex) * f));
            marker.setAlpha(startAlpha + (endAlpha - startAlpha) * f);
            if (from >= 0 && from != 1)
              views[from].setColorFilter((int) argb.evaluate(f, GREEN, MUTED));
            if (to >= 0 && to != 1)
              views[to].setColorFilter((int) argb.evaluate(f, MUTED, GREEN));
          });
      slide.start();
    }
    lastDockTab = to;
    frame.addView(
        dock, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
    placeDock();
  }

  private void placeDock() {
    if (dock == null || dockFade == null) return;
    boolean visible = !keyboardUp && !"chat".equals(page);
    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) dock.getLayoutParams();
    lp.bottomMargin = navInset + dp(16);
    dock.setLayoutParams(lp);
    FrameLayout.LayoutParams fade = (FrameLayout.LayoutParams) dockFade.getLayoutParams();
    fade.height = dp(110) + navInset;
    dockFade.setLayoutParams(fade);
    boolean home = "home".equals(page);
    dock.setVisibility(visible ? View.VISIBLE : View.GONE);
    // У новой заметки под панелью сплошная полоса, градиент там не нужен.
    dockFade.setVisibility(visible && !home ? View.VISIBLE : View.GONE);
    if (dockSpace != null) {
      // Панель: 46 dp значок + 9 + 9 отступ, над краем 16 dp; ещё 8 dp зазора до полосы.
      ViewGroup.LayoutParams space = dockSpace.getLayoutParams();
      space.height = keyboardUp ? 0 : dp(88);
      dockSpace.setLayoutParams(space);
    }
    // Пока открыта клавиатура, панели нет, и запас под неё внизу страницы не нужен.
    if (content != null && !"notes".equals(page) && !"chat".equals(page) && !home)
      content.setPadding(
          content.getPaddingLeft(),
          content.getPaddingTop(),
          content.getPaddingRight(),
          dp(keyboardUp ? 16 : 100));
  }

  private ImageView barIcon(String icon, String description, Runnable click) {
    ImageView view = new ImageView(this);
    view.setScaleType(ImageView.ScaleType.CENTER);
    view.setImageDrawable(new Glyph(icon, INK, 24));
    view.setBackground(ripple(android.graphics.Color.TRANSPARENT, 14));
    view.setContentDescription(description);
    view.setFocusable(true);
    view.setOnClickListener(
        v -> {
          buzz(0);
          click.run();
        });
    return view;
  }

  /**
   * Верхняя строка: назад слева, колокольчик и настройки справа.
   */
  private View topBar(boolean back, boolean actions) {
    searchOpen = false;
    closeSearch = null;
    FrameLayout bar = new FrameLayout(this);
    int size = dp(44);
    if (back) {
      View button = barIcon("back", "Назад", this::goBack);
      bar.addView(
          button, new FrameLayout.LayoutParams(size, size, Gravity.START | Gravity.CENTER_VERTICAL));
    }
    if (actions) {
      View settingsButton = barIcon("settings", "Настройки", () -> animateNavigate(3));
      bar.addView(
          settingsButton,
          new FrameLayout.LayoutParams(size, size, Gravity.END | Gravity.CENTER_VERTICAL));
      int pending = new ManagerStore(store).pending().size();
      FrameLayout bell = new FrameLayout(this);
      bell.addView(
          barIcon(
              "bell",
              pending > 0 ? "Сегодня. Предложений помощника: " + pending : "Сегодня",
              () -> {
                stash();
                hideKeyboard();
                todayOrigin = tab;
                editing = 0;
                page = "today";
                render();
              }),
          new FrameLayout.LayoutParams(size, size));
      if (pending > 0) {
        TextView badge = text(pending > 9 ? "9+" : String.valueOf(pending), 10, WHITE);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setIncludeFontPadding(false);
        badge.setMinWidth(dp(16));
        badge.setPadding(dp(4), 0, dp(4), 0);
        badge.setBackground(shape(FILL, 6));
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams badgeParams =
            new FrameLayout.LayoutParams(-2, dp(16), Gravity.TOP | Gravity.END);
        badgeParams.topMargin = dp(5);
        badgeParams.rightMargin = dp(3);
        bell.addView(badge, badgeParams);
      }
      FrameLayout.LayoutParams bellParams =
          new FrameLayout.LayoutParams(size, size, Gravity.END | Gravity.CENTER_VERTICAL);
      bellParams.rightMargin = dp(48);
      bar.addView(bell, bellParams);
    }
    return bar;
  }

  private EditText notesSearchOverlay(TextView count) {
    FrameLayout bar = new FrameLayout(this);
    View folders = folderChips();
    FrameLayout.LayoutParams folderParams = new FrameLayout.LayoutParams(-1, -2);
    folderParams.topMargin = dp(54);
    bar.addView(folders, folderParams);
    // Папки задают постоянную высоту строки. Поверх неё растёт только панель поиска.
    bar.setFocusableInTouchMode(true);
    bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    EditText field = new EditText(this);
    field.setBackground(null);
    field.setHintTextColor(MUTED);
    field.setTextColor(INK);
    field.setTextSize(15);
    field.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
    field.setSingleLine(true);
    field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
    field.setPadding(dp(8), 0, dp(8), 0);
    field.setContentDescription("Поиск по заметкам");
    field.setOnEditorActionListener(
        (v, action, event) -> {
          hideKeyboard();
          return true;
        });
    field.setText(search);
    field.setSelection(field.length());
    LinearLayout box = row();
    box.setPadding(dp(14), 0, dp(6), 0);
    GradientDrawable boxBackground = shape(SURFACE, 14);
    boxBackground.setStroke(dp(1), LINE);
    ImageView glass = new ImageView(this);
    glass.setImageDrawable(new Glyph("search", MUTED, 20));
    box.addView(glass, new LinearLayout.LayoutParams(dp(20), dp(20)));
    box.addView(field, new LinearLayout.LayoutParams(0, -1, 1));
    ImageView clear = new ImageView(this);
    clear.setScaleType(ImageView.ScaleType.CENTER);
    clear.setImageDrawable(new Glyph("close", GREEN, 18));
    clear.setBackground(shape(LIME, 10));
    clear.setContentDescription("Закрыть поиск");
    box.addView(clear, new LinearLayout.LayoutParams(dp(32), dp(32)));
    LinearLayout panel = column();
    panel.setBackground(boxBackground);
    panel.setClipToOutline(true);
    panel.addView(box, new LinearLayout.LayoutParams(-1, dp(46)));
    count.setGravity(Gravity.CENTER_VERTICAL);
    count.setPadding(dp(16), 0, dp(12), 0);
    panel.addView(count, new LinearLayout.LayoutParams(-1, -1));
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, dp(46), Gravity.TOP);
    bar.addView(panel, params);
    content.addView(bar);
    SearchMotion motion = new SearchMotion(params, panel, clear, folders, count);
    closeSearch =
        () -> {
          field.setText("");
          bar.requestFocus();
          hideKeyboard();
          motion.set(false, true);
        };
    clear.setOnClickListener(v -> closeSearch.run());
    panel.setOnClickListener(
        v -> {
          buzz(0);
          motion.set(true, true);
          field.requestFocus();
          getSystemService(InputMethodManager.class).showSoftInput(field, 0);
        });
    field.setOnFocusChangeListener(
        (v, focused) -> {
          if (focused) motion.set(true, true);
        });
    field.setHint("Поиск по заметкам");
    bar.requestFocus();
    boolean focus = focusSearch;
    motion.set(focus || notesSearchOpen || !search.isEmpty(), false);
    if (focus) field.post(() -> {
      if (!field.isAttachedToWindow() || !"notes".equals(page) || !focusSearch) return;
      focusSearch = false;
      field.requestFocus();
      getSystemService(InputMethodManager.class).showSoftInput(field, 0);
    });
    return field;
  }

  /** Раскрытие поля поиска: 480 мс с быстрым стартом, закрытие короче и спокойнее. */
  private final class SearchMotion {
    final FrameLayout.LayoutParams params;
    final View box, clear, folders, count;
    android.animation.ValueAnimator running;
    float fraction;

    SearchMotion(
        FrameLayout.LayoutParams params,
        View box,
        View clear,
        View folders, View count) {
      this.params = params;
      this.box = box;
      this.clear = clear;
      this.folders = folders;
      this.count = count;
      folders.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
        if (b - t != ob - ot && fraction != 0) apply(fraction);
      });
    }

    void apply(float f) {
      fraction = f;
      params.leftMargin = params.rightMargin = Math.round(dp(8) * (1 - f));
      params.height = dp(46) + Math.round((dp(8) + folders.getHeight()) * f);
      box.setLayoutParams(params);
      box.setElevation(dp(2) * f);
      clear.setAlpha(f);
      count.setAlpha(f);
      folders.setAlpha(1 - f);
      folders.setVisibility(f == 1 ? View.INVISIBLE : View.VISIBLE);
    }

    void set(boolean open, boolean animated) {
      if (animated && open == searchOpen) return;
      searchOpen = notesSearchOpen = open;
      clear.setVisibility(open ? View.VISIBLE : View.INVISIBLE);
      count.setVisibility(open ? View.VISIBLE : View.INVISIBLE);
      folders.setImportantForAccessibility(open ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
          : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
      // Сначала обнуляем ссылку, потом гасим: иначе onAnimationEnd старой анимации
      // примет её за действующую и перезапишет состояние новой.
      android.animation.ValueAnimator old = running;
      running = null;
      if (old != null) old.cancel();
      float target = open ? 1 : 0;
      if (!animated || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
        apply(target);
        return;
      }
      android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(fraction, target);
      anim.setDuration(open ? 480 : 320);
      anim.setInterpolator(
          open
              ? new android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f)
              : new android.view.animation.PathInterpolator(0.65f, 0f, 0.35f, 1f));
      anim.addUpdateListener(a -> apply((float) a.getAnimatedValue()));
      // Как бы анимация ни завершилась, конечное состояние применяем точно:
      // без этого прерванная системой анимация оставляла строку между положениями.
      anim.addListener(
          new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
              if (running == anim) apply(target);
            }
          });
      running = anim;
      anim.start();
    }
  }

  private TextView smallButton(String label, boolean primary, Runnable click) {
    TextView b = button(label, primary, click);
    b.setTextSize(13);
    b.setMinHeight(dp(38));
    b.setPadding(dp(14), dp(6), dp(14), dp(6));
    return b;
  }

  /** Местный календарный день с учётом длины суток в часовом поясе телефона. */
  private void todayPage() {
    ZoneId zone = ZoneId.systemDefault();
    java.time.LocalDate day = java.time.LocalDate.now(zone);
    long start = day.atStartOfDay(zone).toInstant().toEpochMilli();
    long end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    dayClock.postDelayed(refreshDay, Math.max(1, end - System.currentTimeMillis()));
    LinearLayout bar = row();
    String source = todayOrigin == 1 ? "Записать" : todayOrigin == 2 ? "Чаты" : "Заметки";
    bar.addView(iconButton(source, "back", this::goBack));
    bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    bar.addView(quietAction("Предложения ИИ", () -> showSuggestions(bar)));
    content.addView(bar);
    gap(content, 12);
    TextView title = text("Сегодня", 32, INK);
    title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
    content.addView(title);
    String dayName = day.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", new Locale("ru")));
    content.addView(text(Character.toUpperCase(dayName.charAt(0)) + dayName.substring(1), 14, MUTED));
    DayDigest.Input digestInput;
    try {
      digestInput = DayDigest.collect(
          store.dayReminders(start, end), store.dayPlanSources(start, end), start, end, zone);
    } catch (JSONException e) {
      digestInput = new DayDigest.Input(start, end, zone);
    }
    todayDigestView(digestInput);

    TreeMap<Long, List<Note>> events = new TreeMap<>();
    for (Note note : store.dayReminders(start, end)) {
      long[] times = note.repeat == ReminderPolicy.REPEAT_LIST
          ? ReminderPolicy.withTime(ReminderPolicy.parseTimes(note.reminderTimes), note.reminder)
          : new long[]{note.reminder};
      for (long at : times) if (at >= start && at < end)
        events.computeIfAbsent(at, key -> new ArrayList<>()).add(note);
    }
    if (!events.isEmpty()) todayHeading("Напоминания");
    for (Map.Entry<Long, List<Note>> entry : events.entrySet())
      for (Note note : entry.getValue()) todayEvent(note, entry.getKey());

    boolean hasSteps = false;
    try (android.database.Cursor rows = store.getReadableDatabase().rawQuery(
        "SELECT id,title,plan FROM notes WHERE trashed=0 AND plan<>'' ORDER BY updated DESC,id DESC", null)) {
      while (rows.moveToNext()) {
        PlanTree plan = PlanTree.parse(rows.getString(2));
        List<PlanTree.Node> steps = plan.scheduledSteps(start, end);
        if (steps.isEmpty()) continue;
        if (!hasSteps) todayHeading("План на сегодня");
        hasSteps = true;
        long id = rows.getLong(0);
        LinearLayout card = todayCard(PAPER);
        card.addView(quietAction(rows.getString(1), () -> openNote(id)));
        card.addView(text("План · " + plan.done() + " из " + plan.checkable(), 13, MUTED));
        android.widget.ProgressBar progress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(plan.checkable());
        progress.setProgress(plan.done());
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
        card.addView(progress, new LinearLayout.LayoutParams(-1, dp(5)));
        for (PlanTree.Node step : steps) {
          CheckBox box = new CheckBox(this);
          box.setText(step.text);
          box.setTextColor(step.done ? MUTED : INK);
          box.setChecked(step.done);
          if (step.done) box.setPaintFlags(box.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
          box.setOnClickListener(v -> {
            if (!store.markScheduledStep(id, step)) toast("План изменился");
            render();
          });
          card.addView(box);
        }
      }
    }
    ManagerStore manager = new ManagerStore(store);
    boolean questions = false;
    for (JSONObject question : manager.pending()) {
      if (!"clarify".equals(question.optString("type"))) continue;
      Note sourceNote = store.note(ManagerStore.firstSource(question));
      if (sourceNote == null || sourceNote.trashed != 0) continue;
      if (!questions) todayHeading("Помощник");
      questions = true;
      LinearLayout card = todayCard(STACK_BG);
      card.addView(text(sourceNote.title, 13, STACK_FG));
      card.addView(text(question.optString("reason"), 17, INK));
      LinearLayout options = column();
      card.addView(quietAction("Ответить", () ->
          options.setVisibility(options.getVisibility() == View.GONE ? View.VISIBLE : View.GONE)));
      clarifyOptions(options, question, manager, this::render);
      options.setVisibility(View.GONE);
      card.addView(options);
    }
    for (AgentLog.Entry entry : AgentLog.recent(store, Integer.MAX_VALUE,
        Math.max(1, System.currentTimeMillis() - start + 1000))) {
      if (entry.created < start || !AgentJournal.ANSWER.equals(entry.action)
          || !AgentJournal.DONE.equals(entry.state)) continue;
      Note sourceNote = store.note(entry.data.optLong("note"));
      if (sourceNote == null || sourceNote.trashed != 0) continue;
      if (!questions) todayHeading("Помощник");
      questions = true;
      LinearLayout card = todayCard(STACK_BG);
      card.addView(text(sourceNote.title, 13, STACK_FG));
      card.addView(text(entry.data.optString("question"), 17, INK));
      card.addView(text("Ваш ответ · " + entry.data.optString("answer"), 14, MUTED));
      card.addView(quietAction("Изменить ответ", () -> askPlanText("Ваш ответ", entry.data.optString("answer"),
          "Сохранить", answer -> {
            if (!manager.replaceAnswer(entry, answer)) toast("Вопрос уже изменился");
            render();
          })));
    }
    if (events.isEmpty() && !hasSteps && !questions) todayHeading("На сегодня всё");
    if (scheduleAfter != null) {
      Note current = store.note(scheduleAfter.id);
      if (current == null || current.trashed != 0 || current.reminder != scheduleAfter.reminder
          || current.repeat != scheduleAfter.repeat || !current.reminderTimes.equals(scheduleAfter.reminderTimes))
        scheduleBefore = scheduleAfter = null;
    }
    if (scheduleAfter != null) {
      LinearLayout moved = todayCard(PAPER);
      moved.addView(text(scheduleAfter.title + " · " + date(movedTo), 14, MUTED));
      moved.addView(quietAction("Отменить перенос", () -> {
        Note restored = store.changeSchedule(scheduleAfter, scheduleBefore.reminder, scheduleBefore.reminderTimes);
        if (restored != null) Reminders.scheduleEdited(this, scheduleAfter, restored);
        else toast("Расписание уже изменилось");
        scheduleAfter = scheduleBefore = null;
        render();
      }));
    }
  }

  private void todayDigestView(DayDigest.Input snapshot) {
    boolean sameDay = DayDigestRunner.day == snapshot.start;
    TextView launch = quietAction(
        DayDigestRunner.running ? "Готовим сводку…" : "Сводка дня",
        () -> requestDayDigest(snapshot));
    launch.setEnabled(!DayDigestRunner.running);
    launch.setAlpha(DayDigestRunner.running ? .5f : 1);
    content.addView(launch);
    gap(content, 4);
    if (sameDay && DayDigestRunner.result != null) {
      LinearLayout card = todayCard(PAPER);
      TextView heading = text("Сводка дня", 20, INK);
      heading.setTypeface(Typeface.create("serif", Typeface.NORMAL));
      card.addView(heading);
      if (!DayDigestRunner.result.summary.isEmpty()) {
        gap(card, 4);
        card.addView(text(DayDigestRunner.result.summary, 15, INK));
      }
      for (DayDigest.Item item : DayDigestRunner.result.items) {
        gap(card, 8);
        card.addView(text(item.text, 15, INK));
        DayDigest.Entry source = DayDigestRunner.input == null
            ? null : DayDigestRunner.input.source(item.note);
        if (source != null) {
          TextView link = quietAction(source.title.isEmpty() ? "Открыть исходную заметку" : source.title, () -> {
            Note current = store.note(item.note);
            if (current == null || current.trashed != 0) toast("Исходная заметка удалена");
            else openNote(item.note);
          });
          link.setTextColor(GREEN);
          card.addView(link);
        }
      }
      if (!DayDigestRunner.usage.isEmpty()) card.addView(text(DayDigestRunner.usage, 11, MUTED));
      card.addView(quietAction("Обновить сводку", () -> requestDayDigest(snapshot)));
    }
    if (sameDay && !DayDigestRunner.error.isEmpty()) {
      LinearLayout card = todayCard(PAPER);
      card.addView(text(DayDigestRunner.error, 14, RED));
      LinearLayout actions = row();
      actions.addView(compactButton("Повторить", true, () -> requestDayDigest(snapshot)),
          new LinearLayout.LayoutParams(0, -2, 1));
      actions.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
      actions.addView(compactButton("Закрыть", false, () -> {
        DayDigestRunner.clear();
        render();
      }), new LinearLayout.LayoutParams(0, -2, 1));
      card.addView(actions);
    }
  }

  /** Перед каждым платным запуском показывает состав данных; согласие сохраняется общее с чатом. */
  private void requestDayDigest(DayDigest.Input snapshot) {
    if (DayDigestRunner.running) {
      toast("Сводка уже готовится");
      return;
    }
    if (snapshot == null || snapshot.isEmpty()) {
      toast("Для сводки пока нет данных");
      return;
    }
    String key;
    try {
      key = Secrets.read(this);
    } catch (Exception e) {
      toast("Не удалось прочитать ключ. Сохраните его заново в настройках.");
      return;
    }
    String model = prefs.getString("model", "");
    if (key.isEmpty() || model.isEmpty()) {
      new Ask(this)
          .setTitle("Подключите Polza.AI")
          .setMessage("Для сводки дня нужны API-ключ и выбранная модель Polza.AI.")
          .setNegativeButton("Позже", null)
          .setPositiveButton("Настройки", (d, w) -> navigate(3))
          .show();
      return;
    }
    boolean first = !prefs.getBoolean("consent", false);
    new Ask(this)
        .setTitle(first ? "Отправить данные дня в Polza.AI?" : "Составить сводку дня?")
        .setMessage(
            "В Polza.AI уйдут названия и даты сегодняшних событий, шаги планов и отметки их"
                + " выполнения — не больше 12 заметок и 6000 знаков данных. Полные тексты названных"
                + " заметок не отправляются. Это один платный запрос с вашего баланса; автоматического"
                + " повтора не будет.")
        .setNegativeButton("Отмена", null)
        .setPositiveButton(first ? "Согласен и отправить" : "Отправить", (d, w) -> {
          if (first) prefs.edit().putBoolean("consent", true).apply();
          if (DayDigestRunner.start(this, key, model, snapshot)) render();
        })
        .show();
  }

  private void todayHeading(String label) {
    gap(content, 16);
    TextView head = text(label, 22, INK);
    head.setTypeface(Typeface.create("serif", Typeface.NORMAL));
    if (Build.VERSION.SDK_INT >= 28) head.setAccessibilityHeading(true);
    content.addView(head);
    gap(content, 6);
  }

  private LinearLayout todayCard(int color) {
    LinearLayout card = column();
    pad(card, 14);
    card.setBackground(shape(color, 14));
    content.addView(card);
    gap(content, 6);
    return card;
  }

  private void todayEvent(Note note, long at) {
    LinearLayout card = todayCard(REMINDER_BG);
    LinearLayout line = row();
    LinearLayout words = column();
    TextView time = text(new java.text.SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(at)), 14, GREEN);
    time.setCompoundDrawablesWithIntrinsicBounds(new Glyph("clock", GREEN, 16), null, null, null);
    time.setCompoundDrawablePadding(dp(6));
    words.addView(time);
    TextView name = text(note.title, 19, INK);
    name.setTypeface(null, Typeface.BOLD);
    words.addView(name);
    if (!note.body.isEmpty()) {
      TextView preview = text(Retrieval.excerpt(note.body, "", 130), 14, MUTED);
      preview.setMaxLines(2);
      preview.setEllipsize(TextUtils.TruncateAt.END);
      words.addView(preview);
    }
    line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    if (!note.image.isEmpty())
      line.addView(imageTile(ImageList.cover(note.image), 72, 10), new LinearLayout.LayoutParams(dp(64), dp(72)));
    line.setOnClickListener(v -> openNote(note.id));
    line.setFocusable(true);
    card.addView(line);
    boolean repeating = note.repeat != 0 && note.repeat != ReminderPolicy.REPEAT_LIST;
    card.addView(quietAction(repeating ? "Перенести серию" : "Перенести", () ->
        whenPicker(Math.max(System.currentTimeMillis(), at), 20, repeating ? "Перенести серию" : "Перенести напоминание", target -> {
          if (target == at) return;
          long reminder = target;
          String times = note.reminderTimes;
          if (note.repeat == ReminderPolicy.REPEAT_LIST) {
            long[] changed = ReminderPolicy.moveInList(
                ReminderPolicy.withTime(ReminderPolicy.parseTimes(times), note.reminder), at, target);
            if (changed.length == 0) {
              toast("Это время уже есть в заметке");
              return;
            }
            times = ReminderPolicy.encodeTimes(changed);
            reminder = ReminderPolicy.currentInList(changed, System.currentTimeMillis());
          }
          Note changed = store.changeSchedule(note, reminder, times);
          if (changed == null) toast("Расписание уже изменилось");
          else {
            scheduleBefore = note;
            scheduleAfter = changed;
            movedTo = target;
            Reminders.scheduleEdited(this, note, changed);
          }
          render();
        })));
  }

  /** Предложения и журнал остаются доступны из шапки экрана «Сегодня». */
  private void showSuggestions(View anchor) {
    if (suggestionsPopup != null && suggestionsPopup.isShowing()) {
      suggestionsPopup.dismiss();
      return;
    }
    ManagerStore manager = new ManagerStore(store);
    List<JSONObject> proposals = manager.pending();
    int width = Math.min(dp(344), getResources().getDisplayMetrics().widthPixels - dp(44));
    LinearLayout panel = column();
    GradientDrawable background = shape(SURFACE, 14);
    background.setStroke(dp(1), LINE);
    panel.setBackground(background);
    LinearLayout head = row();
    head.setPadding(dp(16), dp(14), dp(16), dp(6));
    ImageView spark = new ImageView(this);
    spark.setImageDrawable(new Glyph("spark", GREEN, 20));
    head.addView(spark, new LinearLayout.LayoutParams(dp(20), dp(20)));
    TextView title = text("Предложения ИИ", 16, INK);
    title.setTypeface(null, Typeface.BOLD);
    title.setPadding(dp(8), 0, 0, 0);
    head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    if (!proposals.isEmpty()) head.addView(text("новых: " + proposals.size(), 12, MUTED));
    panel.addView(head);
    LinearLayout items = column();
    items.setPadding(dp(8), 0, dp(8), dp(4));
    if (proposals.isEmpty()) {
      TextView empty =
          text("Новых предложений нет. Проверка ищет общие темы, дубли и дела в заметках.", 13, MUTED);
      empty.setPadding(dp(8), dp(8), dp(8), dp(12));
      items.addView(empty);
    }
    for (JSONObject p : proposals) {
      String type = p.optString("type");
      boolean stack = deckProposal(type);
      LinearLayout item = column();
      item.setPadding(dp(8), dp(12), dp(8), dp(12));
      LinearLayout top = row();
      top.setGravity(Gravity.TOP);
      ImageView mark = new ImageView(this);
      mark.setScaleType(ImageView.ScaleType.CENTER);
      mark.setBackground(shape(stack ? STACK_BG : LIME, 10));
      mark.setImageDrawable(new Glyph(stack ? "notes" : "spark", stack ? STACK_FG : GREEN, 20));
      top.addView(mark, new LinearLayout.LayoutParams(dp(36), dp(36)));
      LinearLayout words = column();
      words.setPadding(dp(12), 0, 0, 0);
      TextView name = text(proposalLabel(type), 14, INK);
      name.setTypeface(null, Typeface.BOLD);
      words.addView(name);
      TextView reason = text(proposalReason(p), 12, MUTED);
      reason.setMaxLines(type.equals("clarify") ? 5 : 3);
      reason.setEllipsize(TextUtils.TruncateAt.END);
      words.addView(reason);
      top.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
      item.addView(top);
      if (type.equals("plan")) {
        LinearLayout actions = column();
        actions.setPadding(dp(48), dp(8), 0, 0);
        planOfferActions(
            actions,
            p,
            manager,
            () -> {
              suggestionsPopup.dismiss();
              render();
            });
        item.addView(actions);
        items.addView(item);
        View planRule = new View(this);
        planRule.setBackgroundColor(LINE);
        items.addView(planRule, new LinearLayout.LayoutParams(-1, dp(1)));
        continue;
      }
      if (type.equals("clarify")) {
        LinearLayout options = column();
        options.setPadding(dp(48), dp(8), 0, 0);
        clarifyOptions(
            options,
            p,
            manager,
            () -> {
              suggestionsPopup.dismiss();
              render();
            });
        item.addView(options);
        items.addView(item);
        View clarifyRule = new View(this);
        clarifyRule.setBackgroundColor(LINE);
        items.addView(clarifyRule, new LinearLayout.LayoutParams(-1, dp(1)));
        continue;
      }
      boolean ready = false;
      try {
        ready = manager.summaryProgress(p.optLong("id")).optBoolean("ready");
      } catch (Exception ignored) {
      }
      LinearLayout buttons = row();
      buttons.setPadding(dp(48), dp(10), 0, 0);
      buttons.addView(
          smallButton(
              ready
                  ? "Посмотреть выжимку"
                  : stack ? "Подготовить выжимку" : "Посмотреть черновик",
              true,
              () -> {
                suggestionsPopup.dismiss();
                previewProposal(p);
              }));
      TextView skip =
          smallButton(
              "Не нужно",
              false,
              () -> {
                manager.state(p.optLong("id"), "dismissed");
                suggestionsPopup.dismiss();
                render();
              });
      LinearLayout.LayoutParams skipParams = new LinearLayout.LayoutParams(-2, -2);
      skipParams.leftMargin = dp(8);
      buttons.addView(skip, skipParams);
      item.addView(buttons);
      items.addView(item);
      View rule = new View(this);
      rule.setBackgroundColor(LINE);
      items.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
    }
    ScrollView list = new ScrollView(this);
    list.addView(items);
    panel.addView(list, new LinearLayout.LayoutParams(-1, proposals.size() > 2 ? dp(300) : -2));
    // Сделанное помощником за сутки — с «Отменить» (журнал агента).
    List<AgentLog.Entry> recentDone = new ArrayList<>();
    long nowMs = System.currentTimeMillis();
    for (AgentLog.Entry e : AgentLog.recent(store, 20, AgentJournal.UNDO_WINDOW))
      if (e.undoable(nowMs)) recentDone.add(e);
    if (!recentDone.isEmpty()) {
      View doneRule = new View(this);
      doneRule.setBackgroundColor(LINE);
      panel.addView(doneRule, new LinearLayout.LayoutParams(-1, dp(1)));
      LinearLayout doneBox = column();
      doneBox.setPadding(dp(16), dp(10), dp(12), dp(2));
      TextView doneTitle = text("Сделано помощником", 13, STACK_FG);
      doneTitle.setTypeface(null, Typeface.BOLD);
      doneBox.addView(doneTitle);
      for (int i = 0; i < Math.min(3, recentDone.size()); i++)
        doneBox.addView(journalRow(recentDone.get(i), () -> suggestionsPopup.dismiss()));
      if (recentDone.size() > 3)
        doneBox.addView(
            text("И ещё " + (recentDone.size() - 3) + " — в журнале помощника", 12, MUTED));
      panel.addView(doneBox);
    }
    LinearLayout foot = column();
    foot.setPadding(dp(16), dp(10), dp(8), dp(6));
    String today = java.time.LocalDate.now().toString();
    int used =
        today.equals(prefs.getString("analysisDay", "")) ? prefs.getInt("analysisCount", 0) : 0;
    foot.addView(
        text(
            prefs.getBoolean("unlimitedAi", false)
                ? "Без дневного лимита · запросы платные"
                : "Проверок сегодня: " + used + " из 4",
            12,
            MUTED));
    foot.addView(
        quietAction(
            "Помощник и проверка заметок",
            () -> {
              suggestionsPopup.dismiss();
              // Экран помощника относится к вкладке «Заметки» (как и переход из уведомления):
              // иначе «Назад» вело бы на «Заметки» с подсветкой «Записать», а потом сворачивало
              // приложение.
              stash();
              if (!persistEditedNote()) return;
              hideKeyboard();
              editing = 0;
              tab = 0;
              page = "manager";
              render();
            }));
    panel.addView(foot);
    PopupWindow popup = new PopupWindow(panel, width, -2, true);
    popup.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
    popup.setOutsideTouchable(true);
    suggestionsPopup = popup;
    popup.showAsDropDown(anchor, 0, dp(4), Gravity.END);
    if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
      panel.setPivotX(width);
      panel.setPivotY(0);
      panel.setAlpha(0);
      panel.setScaleX(.92f);
      panel.setScaleY(.92f);
      panel.setTranslationY(-dp(6));
      panel
          .animate()
          .alpha(1)
          .scaleX(1)
          .scaleY(1)
          .translationY(0)
          .setDuration(240)
          .setInterpolator(new android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f))
          .start();
    }
  }

  private String draftPrefix() {
    return "draft." + editing + ".";
  }

  private void stash() {
    if (editorActive && titleField != null && bodyField != null) {
      String p = draftPrefix();
      prefs
          .edit()
          .putString(p + "title", titleField.getText().toString())
          .putString(p + "body", bodyField.getText().toString())
          .putLong(p + "reminder", reminder)
          .putLong(p + "folder", editFolder)
          .putInt(p + "repeat", repeatMode)
          .putString(p + "times", reminderTimes)
          .putInt(p + "lead", leadChoice)
          .putString(p + "image", noteImage)
          .apply();
    }
    if (chatInput != null)
      prefs.edit().putString("chatDraft." + chat, chatInput.getText().toString()).apply();
  }

  private void clearDraft(long id) {
    String p = "draft." + id + ".";
    prefs
        .edit()
        .remove(p + "title")
        .remove(p + "body")
        .remove(p + "reminder")
        .remove(p + "folder")
        .remove(p + "repeat")
        .remove(p + "times")
        .remove(p + "lead")
        .remove(p + "image")
        .apply();
  }

  private void editor(long id) {
    editing = id;
    Note note = id == 0 ? null : store.note(id);
    if (id > 0 && note == null) {
      heading("Заметка удалена", "");
      content.addView(button("К заметкам", false, () -> navigate(0)));
      return;
    }
    if (note != null && note.trashed > 0) {
      editorActive = false;
      heading(
          "Заметка в корзине",
          "«" + note.title + "». " + TrashPolicy.label(note.trashed, System.currentTimeMillis()) + ".");
      content.addView(
          button(
              "Восстановить",
              true,
              () -> {
                restoreNote(id);
                render();
              }));
      gap(content, 8);
      content.addView(button("К заметкам", false, () -> navigate(0)));
      return;
    }
    // Снимок для persistEditedNote: поля загружаются из базы, если черновика нет; с черновиком
    // остаётся снимок, сделанный при открытии этой заметки.
    if (id > 0
        && (editorBase == null
            || editorBase.id != id
            || !prefs.contains("draft." + id + ".title"))) editorBase = note;
    if (id > 0) {
      LinearLayout toolbar = row();
      TextView back = iconButton(switch (returnPage) {
        case "chat" -> "Чат"; case "manager" -> "Помощник"; case "notes" -> "Заметки"; case "today" -> "Сегодня"; default -> "Назад";
      }, "back", this::goBack);
      back.setBackground(ripple(SURFACE, 10));
      toolbar.addView(back);
      toolbar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
      TextView menu = button("⋮", false, () -> {});
      menu.setTextSize(24);
      menu.setPadding(0, 0, 0, 0);
      menu.setContentDescription("Меню заметки");
      menu.setBackground(ripple(SURFACE, 10));
      menu.setOnClickListener(
          v -> {
            Pop popup = new Pop(menu);
            popup.getMenu().add(0, 1, 0, "Редактировать название");
            popup.getMenu().add(0, 2, 0, "Переместить в папку");
            if (!noteImage.isEmpty()) popup.getMenu().add(0, 4, 0, "Действия с фотографией");
            popup.getMenu().add(0, 3, 0, "Удалить заметку");
            popup.setOnMenuItemClickListener(
                item -> {
                  if (item.getItemId() == 1) {
                    titleField.requestFocus();
                    getSystemService(InputMethodManager.class).showSoftInput(titleField, 0);
                  } else if (item.getItemId() == 2) chooseEditorFolder();
                  else if (item.getItemId() == 4) showImageActions();
                  else deleteNote(id);
                  return true;
                });
            popup.show();
          });
      toolbar.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
      toolbar.setPadding(dp(8), dp(2), dp(14), dp(2));
      toolbar.setFocusableInTouchMode(true);
      root.addView(toolbar, 0);
      toolbar.requestFocus();
    } else {
      // Новая заметка — лист на весь экран: сверху только сегодняшняя дата и значки.
      FrameLayout bar = (FrameLayout) topBar(false, true);
      String today =
          new java.text.SimpleDateFormat("EEEE, d MMMM", new Locale("ru")).format(new Date());
      if (!today.isEmpty()) today = Character.toUpperCase(today.charAt(0)) + today.substring(1);
      TextView screen = text(today, 13, MUTED);
      screen.setSingleLine(true);
      screen.setEllipsize(TextUtils.TruncateAt.END);
      FrameLayout.LayoutParams screenParams =
          new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL);
      screenParams.rightMargin = dp(96);
      bar.addView(screen, screenParams);
      content.addView(bar, new LinearLayout.LayoutParams(-1, dp(44)));
      gap(content, 12);
    }
    boolean newNote = id == 0;
    String p = draftPrefix();
    noteImage = prefs.getString(p + "image", note == null ? "" : note.image);
    if (selectedImageFor != id) {
      selectedImage = 0;
      stripScroll = 0;
      selectedImageFor = id;
    }
    LinearLayout sheet = column();
    boolean large = prefs.getBoolean("large", false);
    titleField = input("Название", false);
    titleField.setBackground(null);
    titleField.setTypeface(Typeface.create("serif", Typeface.NORMAL));
    titleField.setTextSize(large ? 32 : 30);
    titleField.setPadding(0, 0, 0, dp(4));
    if (!newNote) {
      titleField.setSingleLine(false);
      titleField.setImeOptions(EditorInfo.IME_ACTION_NEXT);
    }
    titleField.setText(prefs.getString(p + "title", note == null ? "" : note.title));
    titleField.setFilters(new InputFilter[] {new InputFilter.LengthFilter(160)});
    sheet.addView(titleField, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout meta = row();
    if (newNote) {
      TextView hint =
          text("Можно без названия — возьмём первую строку. Черновик сохраняется сам.", 12, MUTED);
      sheet.addView(hint);
      gap(sheet, 10);
    } else {
      HorizontalScrollView chipScroll = new HorizontalScrollView(this);
      chipScroll.setHorizontalScrollBarEnabled(false);
      chipScroll.addView(meta);
      sheet.addView(chipScroll);
    }
    bodyField = configureInput(new NoteBodyEditText(this, GREEN), "Что хочется запомнить?", true);
    bodyField.setBackground(null);
    bodyField.setPadding(0, dp(newNote ? 4 : 12), 0, dp(12));
    bodyField.setLineSpacing(dp(3), 1f);
    bodyField.setMinHeight(dp(newNote ? 160 : 44));
    bodyField.setText(prefs.getString(p + "body", note == null ? "" : note.body));
    bodyField.setFilters(new InputFilter[] {new InputFilter.LengthFilter(NOTE_BODY_LIMIT)});
    // Новая заметка: текст занимает всё свободное место, касание в любом месте ставит курсор.
    sheet.addView(bodyField, new LinearLayout.LayoutParams(-1, newNote ? 0 : -2, newNote ? 1 : 0));
    List<String> images = ImageList.parse(noteImage);
    if (!images.isEmpty()) {
      selectedImage = Math.max(0, Math.min(selectedImage, images.size() - 1));
      LinearLayout imageBox = column();
      imageBox.setPadding(0, 0, 0, dp(10));
      if (!newNote) {
        ImageView cover = imageTile(images.get(selectedImage), 480, 12);
        cover.setContentDescription("Открыть фотографию " + (selectedImage + 1) + " из " + images.size());
        cover.setOnClickListener(v -> showImageViewer(images, selectedImage));
        imageBox.addView(cover, new LinearLayout.LayoutParams(-1, dp(150)));
      }
      if (newNote || images.size() > 1) imageBox.addView(imageStrip(images));
      gap(imageBox, 6);
      LinearLayout imageBar = row();
      imageBar.setGravity(Gravity.CENTER_VERTICAL);
      TextView caption =
          text(
              images.size() == 1
                  ? "1 изображение · нажмите, чтобы открыть"
                  : "Изображение "
                      + (selectedImage + 1)
                      + " из "
                      + images.size()
                      + " · нажмите выбранное, чтобы открыть",
              12,
              MUTED);
      imageBar.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
      LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-2, -2);
      actionParams.leftMargin = dp(8);
      TextView actions = compactButton("Действия", false, this::showImageActions);
      actions.setMinHeight(dp(44));
      imageBar.addView(actions, actionParams);
      if (newNote || images.size() > 1) imageBox.addView(imageBar);
      sheet.addView(imageBox);
    }
    View footerRule = new View(this);
    footerRule.setBackgroundColor(LINE);
    reminder = prefs.getLong(p + "reminder", note == null ? 0 : note.reminder);
    repeatMode = prefs.getInt(p + "repeat", note == null ? 0 : note.repeat);
    reminderTimes = prefs.getString(p + "times", note == null ? "" : note.reminderTimes);
    leadChoice = prefs.getInt(p + "lead", note == null ? -1 : note.lead);
    editFolder = prefs.getLong(p + "folder", note == null ? folder : note.folder);
    if (editFolder != FolderPolicy.ALL && !new FolderStore(store).exists(editFolder))
      editFolder = FolderPolicy.ALL;
    LinearLayout footer = row();
    footer.setPadding(dp(12), dp(10), dp(12), dp(10));
    footer.setGravity(Gravity.CENTER_VERTICAL);
    // У новой заметки значки прокручиваются вбок, чтобы длинные подписи не сдвигали «Сохранить».
    LinearLayout tools = row();
    ImageView imageTool = new ImageView(this);
    imageTool.setScaleType(ImageView.ScaleType.CENTER);
    imageTool.setImageDrawable(new Glyph("image", GREEN, 20));
    imageTool.setBackground(ripple(LIME, 10));
    imageTool.setContentDescription(
        noteImage.isEmpty() ? "Прикрепить изображение" : "Добавить ещё изображение");
    imageTool.setFocusable(true);
    imageTool.setOnClickListener(
        v -> {
          buzz(0);
          pickImage(false);
        });
    if (newNote) tools.addView(imageTool, new LinearLayout.LayoutParams(dp(40), dp(40)));
    reminderButton = text("", 13, GREEN);
    reminderButton.setTypeface(null, Typeface.BOLD);
    reminderButton.setGravity(Gravity.CENTER);
    reminderButton.setMinWidth(dp(40));
    reminderButton.setPadding(dp(10), 0, dp(10), 0);
    reminderButton.setSingleLine(true);
    reminderButton.setEllipsize(TextUtils.TruncateAt.END);
    reminderButton.setBackground(ripple(LIME, 10));
    reminderButton.setCompoundDrawablesWithIntrinsicBounds(
        new Glyph("clock", GREEN, 20), null, null, null);
    reminderButton.setFocusable(true);
    reminderButton.setOnClickListener(
        v -> {
          buzz(0);
          pickReminder();
        });
    refreshReminder();
    LinearLayout.LayoutParams reminderParams = new LinearLayout.LayoutParams(-2, dp(40));
    reminderParams.leftMargin = dp(8);
    if (newNote) tools.addView(reminderButton, reminderParams);
    folderButton = text("", 13, GREEN);
    folderButton.setTypeface(null, Typeface.BOLD);
    folderButton.setGravity(Gravity.CENTER);
    folderButton.setMinWidth(dp(40));
    folderButton.setPadding(dp(10), 0, dp(10), 0);
    folderButton.setSingleLine(true);
    folderButton.setEllipsize(TextUtils.TruncateAt.END);
    folderButton.setMaxWidth(dp(130));
    folderButton.setBackground(ripple(LIME, 10));
    folderButton.setCompoundDrawablesWithIntrinsicBounds(
        new Glyph("folder", GREEN, 20), null, null, null);
    folderButton.setFocusable(true);
    folderButton.setOnClickListener(v -> chooseEditorFolder());
    refreshEditorFolder();
    LinearLayout.LayoutParams folderParams = new LinearLayout.LayoutParams(-2, dp(40));
    if (newNote) {
      folderParams.leftMargin = dp(8);
      tools.addView(folderButton, folderParams);
    } else {
      folderButton.setBackground(ripple(PAPER, 18));
      folderParams.rightMargin = dp(6);
      meta.addView(folderButton, folderParams);
      reminderParams.leftMargin = 0;
      reminderButton.setBackground(ripple(LIME, 18));
      meta.addView(reminderButton, reminderParams);
    }
    if (newNote) {
      // Сводка видео — значок в полосе вместо отдельной большой кнопки под листом.
      if (!LocalLlm.enabled(this)) {
        ImageView videoTool = new ImageView(this);
        videoTool.setScaleType(ImageView.ScaleType.CENTER);
        videoTool.setImageDrawable(new Glyph("video", GREEN, 20));
        videoTool.setBackground(ripple(LIME, 10));
        videoTool.setContentDescription("Сводка видео YouTube");
        videoTool.setFocusable(true);
        videoTool.setOnClickListener(
            v -> {
              buzz(0);
              askVideoLink();
            });
        LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        videoParams.leftMargin = dp(8);
        tools.addView(videoTool, videoParams);
      }
      HorizontalScrollView toolScroll = new HorizontalScrollView(this);
      toolScroll.setHorizontalScrollBarEnabled(false);
      toolScroll.addView(tools);
      footer.addView(toolScroll, new LinearLayout.LayoutParams(0, -2, 1));
      // Компактная кнопка сохранения в правом краю нижней полосы листа.
      TextView save = text("Сохранить", 14, WHITE);
      save.setTypeface(null, Typeface.BOLD);
      save.setGravity(Gravity.CENTER);
      save.setPadding(dp(18), 0, dp(18), 0);
      save.setBackground(ripple(FILL, 10));
      save.setFocusable(true);
      save.setContentDescription("Сохранить заметку");
      save.setOnClickListener(
          v -> {
            buzz(0);
            saveNote();
          });
      LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-2, dp(40));
      saveParams.leftMargin = dp(8);
      footer.addView(save, saveParams);
      // Полоса значков прижата к низу экрана над плавающей панелью, а с клавиатурой — над ней.
      LinearLayout strip = column();
      strip.setBackgroundColor(pageColor());
      strip.addView(footerRule, new LinearLayout.LayoutParams(-1, dp(1)));
      footer.setPadding(dp(10), dp(8), dp(16), dp(8));
      strip.addView(footer, new LinearLayout.LayoutParams(-1, -2));
      dockSpace = new View(this);
      strip.addView(dockSpace, new LinearLayout.LayoutParams(-1, keyboardUp ? 0 : dp(88)));
      root.addView(strip, new LinearLayout.LayoutParams(-1, -2));
      content.addView(sheet, new LinearLayout.LayoutParams(-1, 0, 1));
    } else {
      content.addView(sheet);
      openedNoteActions(id, footer, footerRule);
      if (editorSearch != null) {
        revealEditorMatch(editorSearch);
        editorSearch = null;
      }
    }
    if (id > 0) questionSection(id);
    if (id > 0) planSection(id);
    if (id > 0 && note != null && prefs.getBoolean("attentionPoints", false)) {
      // Баллы внимания: открытие заметки — слабый интерес (+1, не чаще раза в 20 часов).
      store.attentionAdjust(id, 1);
      Note fresh = store.note(id);
      if (fresh != null && fresh.attention >= 0) {
        gap(content, 4);
        secondary(
            content,
            fresh.attention == 0
                ? "Помощник больше не работает с этой заметкой. Откройте или измените её, чтобы"
                    + " вернуть его внимание."
                : "Внимание помощника: " + fresh.attention + " из " + AttentionPolicy.MAX);
      }
    }
    if (note != null && note.suggestion > 0) {
      long[] offered = ReminderPolicy.parseTimes(note.suggestionTimes);
      gap(content, 12);
      LinearLayout proposal = column();
      pad(proposal, 14);
      proposal.setBackground(shape(LIME, 12));
      String proposed = "Предложение ИИ: ";
      if (offered.length > 1) {
        StringBuilder days = new StringBuilder();
        java.time.ZoneId zone = ZoneId.systemDefault();
        long nowMs = System.currentTimeMillis();
        for (long at : offered) {
          if (days.length() > 0) days.append('\n');
          days.append(ReminderBatch.label(at, zone));
          if (at <= nowMs) days.append(" · прошло");
        }
        proposed = proposed + ReminderPolicy.dateCount(offered.length) + ":\n" + days;
      } else {
        proposed = proposed + date(note.suggestion);
      }
      secondary(
          proposal,
          proposed
              + suggestedLeadNote(note.suggestionLead)
              + "\n"
              + note.suggestionReason);
      gap(proposal, 8);
      proposal.addView(
          button(
              offered.length > 1 ? "Использовать даты" : "Использовать дату",
              false,
              () -> {
                long now = System.currentTimeMillis();
                long[] chosen = offered.length > 1 ? offered : new long[] {note.suggestion};
                int ahead = 0;
                for (long at : chosen) if (at > now) ahead++;
                if (ahead == 0) {
                  toast("Предложенное время уже прошло");
                  return;
                }
                if (ahead < chosen.length) {
                  // Пока предложение ждало, часть дат могла пройти: берём только будущие.
                  long[] future = new long[ahead];
                  int i = 0;
                  for (long at : chosen) if (at > now) future[i++] = at;
                  chosen = future;
                }
                if (note.suggestionLead >= 0) leadChoice = note.suggestionLead;
                // Несколько дат становятся серией «по списку», одна — обычным напоминанием.
                setEditorTimes(chosen);
                // Баллы внимания: принятое предложение ИИ — сильный интерес (+2 без гейта).
                store.attentionReward(id, 2);
                if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED)
                  requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 42);
                toast(
                    chosen.length > 1
                        ? "Серия сохранится при выходе из заметки."
                        : "Напоминание сохранится при выходе из заметки.");
              }));
      gap(proposal, 6);
      proposal.addView(
          button(
              "Отклонить",
              false,
              () -> {
                store.dismissSuggestion(id);
                // Баллы внимания: отклонённое предложение — минус балл.
                store.attentionAdjust(id, -1);
                proposal.setVisibility(View.GONE);
              }));
      content.addView(proposal);
    }
    editorActive = true;
    watch(titleField, this::stash);
    watch(bodyField, this::stash);
    formatHandle();
    if (focusEditor && id == 0) {
      // Быстрое действие «Записать»: сразу открыть клавиатуру в тексте заметки.
      focusEditor = false;
      EditText target = bodyField;
      target.post(
          () -> {
            target.requestFocus();
            getSystemService(InputMethodManager.class).showSoftInput(target, 0);
          });
    }
  }

  private void chooseEditorFolder() {
    chooseFolder(editFolder, picked -> {
      editFolder = picked;
      stash();
      refreshEditorFolder();
    });
  }

  private void refreshEditorFolder() {
    FolderStore.Folder folder = new FolderStore(store).folder(editFolder);
    folderButton.setText(folder == null ? "" : "  " + folder.name);
    folderButton.setContentDescription(folder == null ? "Папка: без папки" : "Папка: " + folder.name);
    folderButton.setVisibility(editing != 0 && folder == null ? View.GONE : View.VISIBLE);
  }

  private void openedNoteActions(long id, LinearLayout footer, View rule) {
    LinearLayout strip = column();
    strip.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
    TextView add = iconButton("Добавить", "plus", () ->
        new Ask(this).setTitle("Добавить").setItems(
            new String[] {"Фотографию", "Напоминание", "Папку", "Пункт плана"},
            (dialog, which) -> {
              switch (which) {
                case 0 -> pickImage(false);
                case 1 -> pickReminder();
                case 2 -> chooseEditorFolder();
                case 3 -> askPlanText("Новый пункт", "", "Добавить", value -> {
                  PlanTree plan = PlanTree.parse(store.plan(id));
                  if (plan.add(null, PlanTree.STEP, value) != null) {
                    planCollapsed = false;
                    savePlan(id, plan, () -> { stash(); render(); });
                  }
                });
              }
            }).setNegativeButton("Отмена", null).show());
    footer.addView(add);
    footer.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    TextView discuss = iconButton("Обсудить", "chat", () -> openPlanChat(id, false));
    discuss.setBackground(ripple(SURFACE, 12));
    footer.addView(discuss);
    TextView done = button("Готово", true, () -> {
      if (!persistEditedNote()) return;
      hideKeyboard();
      render();
      toast("Сохранено");
    });
    footer.addView(done);
    done.setVisibility(View.GONE);
    View.OnFocusChangeListener mode = (v, focused) -> {
      if (focused) {
        done.setVisibility(View.VISIBLE);
        discuss.setVisibility(View.GONE);
      }
    };
    titleField.setOnFocusChangeListener(mode);
    bodyField.setOnFocusChangeListener(mode);
    strip.addView(footer);
    root.addView(strip);
  }

  private void revealEditorMatch(Pattern pattern) {
    EditText field = bodyField;
    Matcher match = pattern.matcher(field.getText());
    if (!match.find()) {
      field = titleField;
      match = pattern.matcher(field.getText());
      if (!match.find()) return;
    }
    EditText target = field;
    int offset = match.start();
    Editable value = target.getText();
    android.text.style.BackgroundColorSpan highlight = new android.text.style.BackgroundColorSpan(LIME);
    value.setSpan(highlight, offset, match.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    ScrollView viewport = (ScrollView) content.getParent();
    target.post(() -> {
      Layout layout = target.getLayout();
      if (!target.isAttachedToWindow() || layout == null) return;
      int line = layout.getLineForOffset(Math.min(offset, value.length()));
      Rect bounds = new Rect();
      viewport.offsetDescendantRectToMyCoords(target, bounds);
      int y = bounds.top + target.getTotalPaddingTop() + layout.getLineTop(line);
      if (y < viewport.getScrollY() || y + layout.getLineBottom(line) - layout.getLineTop(line)
          > viewport.getScrollY() + viewport.getHeight())
        viewport.scrollTo(0, Math.max(0, y - viewport.getHeight() / 3));
      target.postDelayed(() -> value.removeSpan(highlight), 3500);
    });
  }

  /** Удаляет файл прежней картинки, если ни заметка, ни черновик на неё больше не ссылаются. */
  private void releaseImage(String previous, String current) {
    for (String name : ImageList.removed(previous, current))
      io.execute(() -> ImageFiles.release(getApplicationContext(), name));
  }

  /**
   * Удаление заметки — в корзину на 30 дней. У верхней заметки колоды спрашиваем: колоду целиком
   * или только сводку (исходные вернутся в список).
   */
  private void deleteNote(long id) {
    ManagerStore manager = new ManagerStore(store);
    int inside = manager.top(id) == id ? manager.members(id).size() : 0;
    if (inside == 0) {
      moveToTrash(id, false);
      return;
    }
    new Ask(this)
        .setTitle("Удалить колоду?")
        .setItems(
            new String[] {
              "Колоду целиком (" + (inside + 1) + " " + TrashPolicy.plural(inside + 1, "заметка", "заметки", "заметок") + ")",
              "Только сводку, исходные вернуть в список"
            },
            (d, which) -> moveToTrash(id, which == 0))
        .setNegativeButton("Отмена", null)
        .show();
  }

  /**
   * «Разобрать колоду»: исходные возвращаются в список. Сводку можно оставить обычной заметкой или
   * отправить в корзину (оттуда её можно вернуть вместе с исходными 30 дней).
   */
  private void dissolveDeck(long top) {
    int inside = new ManagerStore(store).members(top).size();
    new Ask(this)
        .setTitle("Разобрать колоду?")
        .setMessage(
            "Исходные заметки ("
                + inside
                + ") вернутся в список. Что сделать со сводкой сверху?")
        .setNegativeButton("Назад", null)
        .setNeutralButton(
            "Оставить",
            (d, w) -> {
              new ManagerStore(store).dissolve(top);
              render();
              toast("Колода разобрана");
            })
        .setPositiveButton("В корзину", (d, w) -> moveToTrash(top, false))
        .show();
  }

  private void moveToTrash(long id, boolean wholeStack) {
    List<Long> gone = store.trash(id, wholeStack);
    for (long g : gone) Reminders.cancel(this, g);
    if (!gone.isEmpty()) CalendarSync.runAsync(this);
    clearDraft(id);
    editorActive = false;
    page = returnPage;
    render();
    toast(
        gone.size() > 1
            ? "В корзине: " + gone.size() + " " + TrashPolicy.plural(gone.size(), "заметка", "заметки", "заметок")
            : "Заметка в корзине. Вернуть её можно 30 дней");
  }

  /** Возвращает заметку (и колоду, удалённую целиком) из корзины и заново ставит напоминания. */
  private void restoreNote(long id) {
    List<Long> back = store.restore(id);
    long now = System.currentTimeMillis();
    for (long r : back) {
      Note n = store.note(r);
      if (n != null && n.reminder > now) Reminders.schedule(this, n);
    }
    if (!back.isEmpty()) CalendarSync.runAsync(this);
    toast(back.size() > 1 ? "Колода возвращена" : "Заметка возвращена");
  }

  /** Удаляет насовсем после подтверждения; картинки освобождаются. */
  private void purgeNotes(String title, String message, java.util.function.Supplier<List<Note>> purge) {
    new Ask(this)
        .setTitle(title)
        .setMessage(message + " Вернуть их будет нельзя.")
        .setNegativeButton("Отмена", null)
        .setPositiveButton(
            "Удалить навсегда",
            (d, w) -> {
              List<Note> gone = purge.get();
              for (Note n : gone) {
                Reminders.cancel(this, n.id);
                clearDraft(n.id);
                releaseImage(n.image, "");
              }
              render();
              toast(gone.isEmpty() ? "Нечего удалять" : "Удалено навсегда: " + gone.size());
            })
        .show();
  }

  private void trashPage() {
    LinearLayout header = row();
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView back =
        quietAction(
            "",
            () -> {
              page = "notes";
              render();
            });
    back.setCompoundDrawablesWithIntrinsicBounds(new Glyph("back", GREEN, 22), null, null, null);
    back.setContentDescription("Назад к заметкам");
    header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
    TextView title = text("Корзина", 26, INK);
    title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
    if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    content.addView(header);
    secondary(content, "Каждая заметка удаляется насовсем через 30 дней после того, как попала сюда.");
    gap(content, 12);
    List<Note> items = store.trashed();
    if (items.isEmpty()) {
      secondary(content, "Корзина пуста.");
      return;
    }
    content.addView(
        compactButton(
            "Очистить корзину",
            false,
            () ->
                purgeNotes(
                    "Очистить корзину?",
                    "Все заметки из корзины будут удалены.",
                    () -> store.purgeTrash(System.currentTimeMillis(), true))));
    gap(content, 12);
    long now = System.currentTimeMillis();
    ManagerStore manager = new ManagerStore(store);
    for (Note n : items) {
      LinearLayout card = column();
      pad(card, 14);
      card.setBackground(shape(SURFACE, 12));
      TextView name = text(n.title.isEmpty() ? "Без названия" : n.title, 16, INK);
      name.setTypeface(null, Typeface.BOLD);
      name.setMaxLines(2);
      name.setEllipsize(TextUtils.TruncateAt.END);
      card.addView(name);
      String excerpt = Retrieval.excerpt(n.body, "", 140);
      if (!excerpt.isEmpty()) {
        gap(card, 4);
        TextView body = text(excerpt, 13, MUTED);
        body.setMaxLines(3);
        body.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(body);
      }
      gap(card, 6);
      int stacked = 0;
      for (long member : manager.members(n.id)) {
        Note m = store.note(member);
        if (m != null && m.trashed == n.trashed) stacked++;
      }
      boolean wholeStack = stacked > 0;
      card.addView(
          text(
              TrashPolicy.label(n.trashed, now)
                  + (stacked > 0 ? " · колода, исходных: " + stacked : ""),
              12,
              stacked > 0 ? STACK_FG : RED));
      gap(card, 8);
      LinearLayout actions = row();
      actions.addView(
          compactButton(
              "Восстановить",
              true,
              () -> {
                restoreNote(n.id);
                render();
              }),
          new LinearLayout.LayoutParams(0, -2, 1));
      actions.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
      actions.addView(
          compactButton(
              "Удалить навсегда",
              false,
              () ->
                  purgeNotes(
                      "Удалить навсегда?",
                      "«" + (n.title.isEmpty() ? "Без названия" : n.title) + "»"
                          + (wholeStack ? " и исходные заметки колоды будут удалены." : " будет удалена."),
                      () -> store.purge(n.id))),
          new LinearLayout.LayoutParams(0, -2, 1));
      card.addView(actions);
      content.addView(card);
      gap(content, 8);
    }
  }

  private boolean persistEditedNote() {
    if (!editorActive || editing == 0 || titleField == null || bodyField == null) return true;
    String title = titleField.getText().toString().trim(),
        body = bodyField.getText().toString().trim();
    try {
      Note old = store.note(editing);
      if (old == null) return true;
      reconcileReminder(old);
      int repeatNow = reminder == 0 ? 0 : repeatMode, leadNow = reminder == 0 ? -1 : leadChoice;
      String timesNow = timesFor(repeatNow);
      // Пока заметка была открыта, её могли изменить фоновая проверка ИИ (название, предложение
      // напоминания) или помощник в чате (итог). Поэтому сравниваем с тем, что загрузили в
      // редактор, и записываем только поля, которые изменил пользователь; остальное берём из базы.
      Note base = editorBase != null && editorBase.id == editing ? editorBase : old;
      boolean titleEdited = !title.equals(base.title) && !title.equals(old.title);
      boolean bodyEdited = !body.equals(base.body) && !body.equals(old.body);
      boolean reminderEdited =
          !(reminder == base.reminder
                  && repeatNow == base.repeat
                  && leadNow == base.lead
                  && timesNow.equals(timesFor(base)))
              && !(reminder == old.reminder
                  && repeatNow == old.repeat
                  && leadNow == old.lead
                  && timesNow.equals(timesFor(old)));
      boolean imageEdited = !noteImage.equals(base.image) && !noteImage.equals(old.image);
      boolean folderEdited = editFolder != base.folder && editFolder != old.folder;
      if (folderEdited) new FolderStore(store).move(editing, editFolder);
      if (titleEdited || bodyEdited || reminderEdited || imageEdited) {
        store.save(
            editing,
            titleEdited ? title : old.title,
            bodyEdited ? body : old.body,
            reminderEdited ? reminder : old.reminder,
            reminderEdited ? repeatMode : old.repeat,
            reminderEdited ? leadChoice : old.lead,
            reminderEdited ? timesNow : old.reminderTimes);
        // Баллы внимания: правка заметки — интерес (+1, не чаще раза в 20 часов).
        if (titleEdited || bodyEdited) store.attentionAdjust(editing, 1);
        if (imageEdited) {
          store.image(editing, noteImage);
          releaseImage(old.image, noteImage);
        }
        Reminders.scheduleEdited(this, old, store.note(editing));
        BackgroundAnalysis.enqueue(this);
      }
      clearDraft(editing);
      // Редактор может остаться открытым (сворачивание приложения): дальше сравниваем с тем, что
      // сейчас в полях, чтобы нетронутые поля и потом не затирали изменения из базы.
      Note shown = new Note(editing, title, body, old.created, old.updated, reminder);
      shown.repeat = repeatNow;
      shown.lead = leadNow;
      shown.reminderTimes = timesNow;
      shown.image = noteImage;
      shown.folder = editFolder;
      editorBase = shown;
      return true;
    } catch (Exception e) {
      android.util.Log.e("MainActivity", "persistEditedNote failed", e);
      stash();
      toast("Не удалось сохранить изменения. Черновик сохранён на устройстве.");
      return false;
    }
  }

  /**
   * Повторяющееся напоминание могло сработать и передвинуться, пока заметка была открыта: прошлое
   * время в редакторе не выбирается вручную, поэтому оно устарело и заменяется текущим из базы.
   */
  private void reconcileReminder(Note old) {
    if (old != null
        && old.repeat != 0
        && reminder != 0
        && reminder < old.reminder
        && reminder <= System.currentTimeMillis()) reminder = old.reminder;
  }

  private String reminderLabel() {
    if (reminder == 0) return "";
    String when = date(reminder);
    if ("edit".equals(page)) {
      java.time.ZonedDateTime at = java.time.Instant.ofEpochMilli(reminder).atZone(ZoneId.systemDefault());
      java.time.LocalDate today = java.time.LocalDate.now(at.getZone());
      String day = at.toLocalDate().equals(today) ? "Сегодня" :
          at.toLocalDate().equals(today.plusDays(1)) ? "Завтра" : "";
      if (!day.isEmpty()) when = day + ", " + at.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }
    if (repeatMode == ReminderPolicy.REPEAT_LIST)
      return when + moreDates(repeatMode, reminderTimes);
    return when + (repeatMode != 0 ? "  ↻" : "");
  }

  /** « +2» после даты: сколько ещё дат серии впереди; пусто, если серии нет. */
  private String moreDates(int repeat, String times) {
    if (repeat != ReminderPolicy.REPEAT_LIST) return "";
    int left = ReminderPolicy.remaining(ReminderPolicy.parseTimes(times), System.currentTimeMillis());
    return left > 1 ? "  +" + (left - 1) : "";
  }

  /** Список дат для сохранения: только у серии по списку, иначе пусто. */
  private String timesFor(int repeat) {
    return repeat == ReminderPolicy.REPEAT_LIST ? ReminderPolicy.encodeTimes(editorTimes()) : "";
  }

  private static String timesFor(Note n) {
    return n.repeat == ReminderPolicy.REPEAT_LIST
        ? ReminderPolicy.encodeTimes(ReminderPolicy.parseTimes(n.reminderTimes))
        : "";
  }

  /** Даты открытой заметки: список серии или единственное напоминание. */
  private long[] editorTimes() {
    if (repeatMode == ReminderPolicy.REPEAT_LIST) {
      long[] times = ReminderPolicy.parseTimes(reminderTimes);
      // Список мог прийти пустым (старая запись): текущее напоминание в нём всегда есть.
      return reminder > 0 ? ReminderPolicy.withTime(times, reminder) : times;
    }
    return reminder > 0 ? new long[] {reminder} : new long[0];
  }

  /** Ставит даты в редактор: одна — обычное напоминание, несколько — серия по списку дат. */
  private void setEditorTimes(long[] times) {
    if (times.length == 0) {
      reminder = 0;
      repeatMode = 0;
      leadChoice = -1;
      reminderTimes = "";
    } else if (times.length == 1) {
      reminder = times[0];
      if (repeatMode == ReminderPolicy.REPEAT_LIST) repeatMode = 0;
      reminderTimes = "";
    } else {
      reminder = ReminderPolicy.currentInList(times, System.currentTimeMillis());
      repeatMode = ReminderPolicy.REPEAT_LIST;
      reminderTimes = ReminderPolicy.encodeTimes(times);
    }
    refreshReminder();
    stash();
  }

  private void addReminderDate(long at) {
    long[] times = ReminderPolicy.withTime(editorTimes(), at);
    setEditorTimes(times);
    if (times.length > 1) toast("Напоминания: " + ReminderPolicy.dateCount(times.length));
  }

  /** Все даты серии с галочками: снятая галочка убирает дату, «Добавить» — ещё одну. */
  private void editReminderDates() {
    long[] times = editorTimes();
    long now = System.currentTimeMillis();
    java.time.ZoneId zone = java.time.ZoneId.systemDefault();
    String[] labels = new String[times.length];
    boolean[] keep = new boolean[times.length];
    for (int i = 0; i < times.length; i++) {
      labels[i] = ReminderBatch.label(times[i], zone) + (times[i] <= now ? " · прошло" : "");
      keep[i] = true;
    }
    new Ask(this)
        .setTitle("Даты напоминания")
        .setMultiChoiceItems(labels, keep, (d, w, checked) -> keep[w] = checked)
        .setNegativeButton("Отмена", null)
        .setNeutralButton("Добавить", (d, w) -> datePicker(this::addReminderDate))
        .setPositiveButton(
            "Сохранить",
            (d, w) -> {
              long[] kept = new long[0];
              for (int i = 0; i < times.length; i++)
                if (keep[i]) kept = ReminderPolicy.withTime(kept, times[i]);
              if (kept.length > 0 && ReminderPolicy.remaining(kept, System.currentTimeMillis()) == 0) {
                toast("Оставьте хотя бы одну будущую дату или уберите напоминание");
                return;
              }
              setEditorTimes(kept);
            })
        .show();
  }

  /** « · напомнить за 2 дня» для предложения ИИ; пусто, если интервал не предложен. */
  private String suggestedLeadNote(int lead) {
    if (lead < 0) return "";
    for (int i = 0; i < Reminders.LEAD_MINUTES.length; i++)
      if (Reminders.LEAD_MINUTES[i] == lead)
        return " · напомнить " + LEAD_LABELS[i].toLowerCase(new Locale("ru"));
    return "";
  }

  private String leadChoiceLabel() {
    for (int i = 0; i < Reminders.LEAD_MINUTES.length; i++)
      if (Reminders.LEAD_MINUTES[i] == leadChoice) return LEAD_LABELS[i].toLowerCase(new Locale("ru"));
    return "как в настройках";
  }

  /** Кнопка напоминания без даты показывает только значок часов. */
  private void refreshReminder() {
    if (reminderButton == null) return;
    String label = reminderLabel();
    if ("edit".equals(page)) reminderButton.setVisibility(label.isEmpty() ? View.GONE : View.VISIBLE);
    reminderButton.setText(label);
    reminderButton.setCompoundDrawablePadding(label.isEmpty() ? 0 : dp(6));
    reminderButton.setContentDescription(
        label.isEmpty()
            ? "Добавить напоминание"
            : "Напоминание: "
                + date(reminder)
                + (repeatMode == ReminderPolicy.REPEAT_LIST
                    ? ". Всего " + ReminderPolicy.dateCount(editorTimes().length)
                    : repeatMode != 0 ? ". " + ReminderPolicy.repeatLabel(repeatMode) : ""));
  }

  /**
   * Заметка с несколькими датами (расписание, план): готовит в чате запрос «создай напоминания по
   * датам», на который ИИ ответит пачкой напоминаний. Запрос виден и правится перед отправкой.
   */
  private void remindersFromText() {
    String text = bodyField == null ? "" : bodyField.getText().toString().trim();
    String name = titleField == null ? "" : titleField.getText().toString().trim();
    if (text.isEmpty()) return;
    String query = ReminderBatch.requestFor(name, text);
    stash();
    if (!persistEditedNote()) return;
    hideKeyboard();
    String old = prefs.getString("chatDraft.0", "");
    // Ответ на этот запрос предложит добавить даты в эту же заметку (ChatRunner).
    prefs
        .edit()
        .putString("chatDraft.0", old.trim().isEmpty() ? query : old.trim() + "\n\n" + query)
        .putLong("reminderSource", editing)
        .apply();
    chat = 0;
    tab = 2;
    page = "chat";
    editing = 0;
    render();
    toast("Проверьте текст и отправьте. ИИ предложит напоминания по датам");
  }

  private void pickReminder() {
    boolean many = bodyField != null && ReminderBatch.manyDates(bodyField.getText().toString());
    if (reminder == 0 && !many) {
      datePicker();
      return;
    }
    List<String> labels = new ArrayList<>();
    List<Runnable> actions = new ArrayList<>();
    Runnable clear = () -> setEditorTimes(new long[0]);
    Locale ru = new Locale("ru");
    if (reminder == 0) {
      labels.add("Выбрать дату и время");
      actions.add(this::datePicker);
    } else if (repeatMode == ReminderPolicy.REPEAT_LIST) {
      labels.add("Даты: " + ReminderPolicy.dateCount(editorTimes().length));
      actions.add(this::editReminderDates);
      labels.add("Добавить дату");
      actions.add(() -> datePicker(this::addReminderDate));
      labels.add("Напомнить за: " + leadChoiceLabel());
      actions.add(this::pickLead);
      labels.add("Добавить в календарь");
      actions.add(this::addToCalendar);
      labels.add("Убрать все напоминания");
      actions.add(clear);
    } else {
      labels.add("Изменить дату и время");
      actions.add(this::datePicker);
      if (!ReminderPolicy.periodic(repeatMode)) {
        // Вторая дата превращает напоминание в серию по списку дат (одна заметка — несколько дат).
        labels.add("Добавить ещё дату");
        actions.add(() -> datePicker(this::addReminderDate));
      }
      labels.add("Повтор: " + ReminderPolicy.repeatLabel(repeatMode).toLowerCase(ru));
      actions.add(this::pickRepeat);
      labels.add("Напомнить за: " + leadChoiceLabel());
      actions.add(this::pickLead);
      labels.add("Добавить в календарь");
      actions.add(this::addToCalendar);
      labels.add("Убрать напоминание");
      actions.add(clear);
    }
    if (many) {
      labels.add("Напоминания по всем датам в тексте");
      actions.add(this::remindersFromText);
    }
    new Ask(this)
        .setTitle("Напоминание")
        .setItems(labels.toArray(new String[0]), (d, w) -> actions.get(w).run())
        .show();
  }

  /**
   * Прототип: открывает системное окно «Новое событие» (Календарь Samsung или любой другой) с
   * данными заметки. Разрешения не нужны, пользователь сам нажимает «Сохранить» в календаре.
   */
  private void addToCalendar() {
    CalendarLink link =
        CalendarLink.of(
            titleField == null ? "" : titleField.getText().toString(),
            bodyField == null ? "" : bodyField.getText().toString(),
            reminder,
            repeatMode);
    if (link == null) {
      toast("Сначала выберите дату напоминания");
      return;
    }
    Intent intent =
        new Intent(Intent.ACTION_INSERT)
            .setData(android.provider.CalendarContract.Events.CONTENT_URI)
            .putExtra(android.provider.CalendarContract.Events.TITLE, link.title)
            .putExtra(android.provider.CalendarContract.Events.DESCRIPTION, link.description)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, link.begin)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, link.end);
    if (link.rrule != null)
      intent.putExtra(android.provider.CalendarContract.Events.RRULE, link.rrule);
    try {
      startActivity(intent);
    } catch (ActivityNotFoundException e) {
      toast("На устройстве нет приложения «Календарь»");
    }
  }

  private void pickRepeat() {
    String[] labels = new String[5];
    for (int i = 0; i < labels.length; i++) labels[i] = ReminderPolicy.repeatLabel(i);
    new Ask(this)
        .setTitle("Повтор")
        .setSingleChoiceItems(
            labels,
            repeatMode,
            (d, w) -> {
              repeatMode = w;
              refreshReminder();
              stash();
              d.dismiss();
            })
        .show();
  }

  private void pickLead() {
    String[] labels = new String[LEAD_LABELS.length + 1];
    labels[0] = "Как в настройках";
    System.arraycopy(LEAD_LABELS, 0, labels, 1, LEAD_LABELS.length);
    int checked = 0;
    for (int i = 0; i < Reminders.LEAD_MINUTES.length; i++)
      if (Reminders.LEAD_MINUTES[i] == leadChoice) checked = i + 1;
    new Ask(this)
        .setTitle("Напомнить за")
        .setSingleChoiceItems(
            labels,
            checked,
            (d, w) -> {
              leadChoice = w == 0 ? -1 : Reminders.LEAD_MINUTES[w - 1];
              refreshReminder();
              stash();
              d.dismiss();
            })
        .show();
  }

  private void datePicker() {
    datePicker(
        at -> {
          reminder = at;
          refreshReminder();
          stash();
        });
  }

  /** Выбор даты и времени в будущем; выбранный момент получает {@code picked}. */
  private void datePicker(java.util.function.LongConsumer picked) {
    long now = System.currentTimeMillis();
    whenPicker(
        reminder > now ? reminder : now + 3600000,
        at -> {
          picked.accept(at);
          if (Build.VERSION.SDK_INT >= 33
              && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                  != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 42);
        });
  }

  private void saveNote() {
    saveNote(true);
  }

  private void saveNote(boolean offerVideo) {
    String title = titleField.getText().toString(), body = bodyField.getText().toString();
    if (title.trim().isEmpty() && body.trim().isEmpty() && noteImage.isEmpty()) {
      buzz(2);
      bodyField.setError("Напишите текст или название");
      bodyField.requestFocus();
      return;
    }
    // В новую заметку вставили только ссылки на видео: предложим сводку вместо заметки-ссылки.
    if (offerVideo
        && editing == 0
        && noteImage.isEmpty()
        && title.trim().isEmpty()
        && !LocalLlm.enabled(this)
        && YoutubeLink.onlyLinks(body)) {
      offerVideoSummary(
          YoutubeLink.all(body),
          () -> saveNote(false),
          () -> {
            bodyField.setText("");
            clearDraft(0);
          });
      return;
    }
    Note old = editing == 0 ? null : store.note(editing);
    reconcileReminder(old);
    if (reminder != 0
        && reminder <= System.currentTimeMillis()
        && (old == null || old.reminder != reminder)) {
      toast("Время напоминания уже прошло. Выберите новое.");
      return;
    }
    try {
      long id =
          store.save(editing, title, body, reminder, repeatMode, leadChoice, timesFor(repeatMode));
      Note placed = store.note(id);
      if (placed != null && placed.folder != editFolder) new FolderStore(store).move(id, editFolder);
      store.image(id, noteImage);
      if (old != null) releaseImage(old.image, noteImage);
      BackgroundAnalysis.enqueue(this);
      Reminders.scheduleEdited(this, old, store.note(id));
      boolean denied =
          reminder > 0 && !getSystemService(NotificationManager.class).areNotificationsEnabled();
      clearDraft(editing);
      editorActive = false;
      hideKeyboard();
      boolean wasNew = editing == 0;
      editing = 0;
      page = wasNew ? "home" : returnPage;
      render();
      celebrateNote(id);
      buzz(1);
      if (denied) toast("Сохранено. Разрешите уведомления в настройках Android.");
    } catch (Exception e) {
      android.util.Log.e("MainActivity", "saveNote failed", e);
      toast("Не удалось сохранить заметку. Черновик сохранён.");
    }
  }

  private Pattern editorSearch;
  private boolean planCollapsed;

  private void openNote(long id) {
    openNote(id, null);
  }

  private void openNote(long id, Pattern searchPattern) {
    editorSearch = searchPattern;
    planCollapsed = false;
    stash();
    if ("edit".equals(page)) {
      // Из открытой заметки в другую («Поделиться», готовая сводка видео): сохраняем текущую,
      // а «Назад» ведёт туда, откуда открыли первую, — иначе редактор открывался бы по кругу.
      if (!persistEditedNote()) return;
      if ("edit".equals(returnPage)) returnPage = "notes";
    } else returnPage = page;
    hideKeyboard();
    editing = id;
    page = "edit";
    render();
  }

  /**
   * Страница «Заметки» (0.10.24, вариант А с холста): заголовок, поиск строкой под ним, ряд
   * папок и «кладка» — два столбца карточек, высота каждой по её содержимому.
   */
  private void notes() {
    boolean grid = prefs.getBoolean("grid", true);
    FrameLayout bar = (FrameLayout) topBar(false, true);
    TextView heading = text("Заметки", 30, INK);
    heading.setTypeface(Typeface.create("serif", Typeface.NORMAL));
    if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
    bar.addView(
        heading, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
    View layout =
        barIcon(
            grid ? "list" : "grid",
            grid ? "Показать списком" : "Показать плиткой",
            () -> {
              prefs.edit().putBoolean("grid", !grid).apply();
              render();
            });
    FrameLayout.LayoutParams layoutParams =
        new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END | Gravity.CENTER_VERTICAL);
    layoutParams.rightMargin = dp(96);
    bar.addView(layout, layoutParams);
    content.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));
    gap(content, 2);
    TextView count = text("", 13, MUTED);
    EditText field = notesSearchOverlay(count);
    gap(content, 14);
    LinearLayout listing = column();
    // Список прокручивается под плавающей панелью, поэтому внизу оставлен запас.
    listing.setPadding(0, 0, 0, dp(100));
    ScrollView viewport = scroll(listing);
    content.addView(pullToSync(viewport), new LinearLayout.LayoutParams(-1, 0, 1));
    Runnable refresh =
        () -> {
          listing.removeAllViews();
          ManagerStore manager = new ManagerStore(store);
          // Стопки читаем один раз на обновление, а не по запросу на каждую заметку и карточку.
          Map<Long, Long> tops = manager.topMap();
          Map<Long, List<Long>> groups = manager.membersMap();
          membersCache = groups;
          deckViews.clear();
          planCache = store.planBadges();
          questionCache = manager.questionNotes();
          notesHighlight = SearchQuery.highlight(search);
          List<Note> found = search.trim().isEmpty() ? store.notes() : store.searchNotes(search, folder);
          visibleNotes.clear();
          for (Note n : found) {
            if (folder != FolderPolicy.ALL && n.folder != folder) continue;
            long top = tops.getOrDefault(n.id, 0L);
            if (!search.trim().isEmpty() || top == 0 || top == n.id) visibleNotes.add(n);
          }
          count.setText(getString(R.string.notes_count, visibleNotes.size()));
          listing.setContentDescription(count.getText());
          if (visibleNotes.isEmpty()) {
            listing.addView(
                text(
                    !search.isEmpty()
                        ? "Ничего не найдено."
                        : folder == FolderPolicy.ALL
                            ? "Пока здесь чистый лист."
                            : "В этой папке пока пусто.",
                    17,
                    MUTED));
            if (!search.trim().isEmpty() && folder != FolderPolicy.ALL)
              listing.addView(button("Искать во всех папках", false, () -> {
                folder = FolderPolicy.ALL;
                render();
              }));
          }
          int columns = grid ? (getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2) : 1;
          // Кладка: карточки идут в столбцы, каждая — в тот, где сейчас меньше «высоты» (оценка
          // по содержимому, без замеров). Раскрытая колода прерывает кладку своим блоком.
          LinearLayout[] cols = null;
          float[] filled = new float[columns];
          for (Note n : visibleNotes) {
            List<Long> members = groups.getOrDefault(n.id, Collections.emptyList());
            boolean expanded =
                !members.isEmpty() && manager.expanded(n.id) && search.trim().isEmpty();
            if (expanded) {
              cols = null;
              LinearLayout group = column();
              group.setBackground(shape(STACK_BG, 15));
              group.setPadding(dp(10), 0, dp(10), dp(2));
              group.addView(deckToggle(n.id, members.size() + 1, true));
              List<Note> all = new ArrayList<>();
              all.add(n);
              for (long member : members) {
                Note source = store.note(member);
                if (source != null) all.add(source);
              }
              LinearLayout[] deckCols = masonryRow(group, columns);
              float[] deckFilled = new float[columns];
              List<View> fan = new ArrayList<>();
              for (int i = 0; i < all.size(); i++) {
                View card = noteCard(all.get(i), grid, true);
                placeCard(deckCols, deckFilled, card, cardWeight(all.get(i)));
                fan.add(card);
              }
              listing.addView(group);
              deckViews.put(n.id, fan);
              if (fanOpen == n.id) {
                fanOpen = 0;
                fanOut(fan);
              }
              gap(listing, 6);
            } else {
              if (cols == null) {
                cols = masonryRow(listing, columns);
                Arrays.fill(filled, 0);
              }
              placeCard(cols, filled, groupedCard(n, grid), cardWeight(n) + (members.isEmpty() ? 0 : 0.6f));
            }
          }
          membersCache = null;
          planCache = null;
          questionCache = null;
        };
    refresh.run();
    if (notesAnchor != 0) {
      View anchor = listing.findViewWithTag(notesAnchor);
      int offset = notesAnchorOffset;
      notesAnchor = 0;
      if (anchor != null) viewport.post(() -> {
        if (!anchor.isAttachedToWindow()) return;
        Rect bounds = new Rect();
        viewport.offsetDescendantRectToMyCoords(anchor, bounds);
        viewport.scrollTo(0, bounds.top - offset);
      });
    }
    watch(field, () -> {
      search = field.getText().toString();
      field.removeCallbacks(refresh);
      field.postDelayed(refresh, SEARCH_DELAY_MS);
    });
    field.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
      public void onViewAttachedToWindow(View v) {}
      public void onViewDetachedFromWindow(View v) { field.removeCallbacks(refresh); }
    });
  }

  /** Ряд столбцов кладки: {@code columns} вертикальных столбцов с зазором 10 dp. */
  private LinearLayout[] masonryRow(LinearLayout parent, int columns) {
    LinearLayout line = new LinearLayout(this);
    line.setOrientation(LinearLayout.HORIZONTAL);
    line.setGravity(Gravity.TOP);
    LinearLayout[] cols = new LinearLayout[columns];
    for (int i = 0; i < columns; i++) {
      cols[i] = column();
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
      if (i > 0) p.leftMargin = dp(10);
      line.addView(cols[i], p);
    }
    parent.addView(line, new LinearLayout.LayoutParams(-1, -2));
    return cols;
  }

  /** Кладёт карточку в столбец, где сейчас меньше всего (по оценке {@link #cardWeight}). */
  private void placeCard(LinearLayout[] cols, float[] filled, View card, float weight) {
    int target = 0;
    for (int i = 1; i < cols.length; i++) if (filled[i] < filled[target] - 0.01f) target = i;
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(10);
    cols[target].addView(card, p);
    filled[target] += weight;
  }

  /** Примерная высота карточки в строках: заголовок, текст, фото, метки. */
  private float cardWeight(Note n) {
    float w = 2.2f + Math.min(2, n.title.length() / 18);
    if (!n.body.isEmpty()) w += Math.min(6, 1 + n.body.length() / 26f);
    if (!n.image.isEmpty()) w += 4.5f;
    if (n.reminder > 0) w += 1;
    if (!planBadge(n.id).isEmpty()) w += 1.4f;
    if (n.suggestion > 0) w += 1;
    if (questionCache != null && questionCache.contains(n.id)) w += 1;
    return w;
  }

  private static final long SEARCH_DELAY_MS = 150;

  /** Обмен запущен жестом «потянуть вниз» и ждёт итога (его покажет слушатель PeerSync). */
  private boolean pullSync;

  /**
   * Жест «потянуть вниз» на списке заметок: у верхнего края список оттягивается, на отпускании
   * после достаточной длины жеста запускается обмен с компьютером (как «Обменяться сейчас» в
   * настройках). Обычная прокрутка и нажатия на карточки не затрагиваются: перехват начинается,
   * только когда список уже у верха и движение идёт вниз и в основном вертикально.
   */
  private final class PullLayout extends FrameLayout {
    private final View target;
    private final TextView hint;
    private final int slop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
    private final float threshold = dp(64), limit = dp(96);
    private float startX, startY, pulled;
    private boolean pulling, armed;

    PullLayout(View target) {
      super(MainActivity.this);
      this.target = target;
      hint = text("", 13, MUTED);
      hint.setAlpha(0);
      hint.setGravity(Gravity.CENTER);
      addView(hint, new LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
      hint.setPadding(0, dp(10), 0, 0);
      addView(target, new LayoutParams(-1, -1));
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN -> {
          startX = e.getX();
          startY = e.getY();
          pulling = false;
        }
        case MotionEvent.ACTION_MOVE -> {
          float dy = e.getY() - startY, dx = e.getX() - startX;
          if (!pulling && dy > slop && dy > Math.abs(dx) * 1.5f && !target.canScrollVertically(-1)) {
            pulling = true;
            armed = false;
            startY = e.getY();
            return true;
          }
        }
        default -> {}
      }
      return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
      if (!pulling) return false;
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_MOVE -> {
          pulled = Math.min(limit, Math.max(0, (e.getY() - startY) * 0.5f));
          target.setTranslationY(pulled);
          hint.setAlpha(Math.min(1f, pulled / threshold));
          boolean now = pulled >= threshold;
          if (now != armed) {
            armed = now;
            if (now) buzz(0);
          }
          hint.setText(armed ? "Отпустите, чтобы обменяться" : "Потяните, чтобы обменяться");
        }
        case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          boolean go = armed && e.getActionMasked() == MotionEvent.ACTION_UP;
          pulling = false;
          armed = false;
          target.animate().translationY(0).setDuration(160).start();
          hint.animate().alpha(0).setDuration(160).start();
          if (go) startPullSync();
        }
        default -> {}
      }
      return true;
    }
  }

  private View pullToSync(View list) {
    return new PullLayout(list);
  }

  private void startPullSync() {
    if (PeerSync.running()) {
      toast("Обмен уже идёт");
      return;
    }
    List<SyncStore.Peer> peers;
    try {
      peers = PeerSync.peers(this);
    } catch (RuntimeException e) {
      peers = Collections.emptyList();
    }
    if (peers.isEmpty()) {
      toast("Компьютер не подключён: Настройки → Синхронизация");
      return;
    }
    pullSync = true;
    toast("Обмен…");
    PeerSync.runAsync(this, true);
  }

  /** Состав стопок на время построения списка; вне его {@code null} и карточки спрашивают базу. */
  private Map<Long, List<Long>> membersCache;

  /** Отметки «План 3/8» на время построения списка; вне его {@code null} и карточка читает базу. */
  private Map<Long, String> planCache;
  /** Заметки с открытым вопросом агента на время построения списка; вне его {@code null}. */
  private Set<Long> questionCache;

  private String planBadge(long id) {
    Map<Long, String> cache = planCache;
    if (cache == null) return PlanTree.parse(store.plan(id)).badge();
    String found = cache.get(id);
    return found == null ? "" : found;
  }

  private List<Long> membersOf(long id) {
    Map<Long, List<Long>> cache = membersCache;
    if (cache == null) return new ManagerStore(store).members(id);
    List<Long> found = cache.get(id);
    return found == null ? Collections.emptyList() : found;
  }

  /** Строка папок над списком: «Все заметки», свои папки и «＋ Папка». Долгое нажатие — меню папки. */
  private View folderChips() {
    FolderStore folders = new FolderStore(store);
    if (folder != FolderPolicy.ALL && !folders.exists(folder)) {
      folder = FolderPolicy.ALL;
    }
    HorizontalScrollView scroller = new HorizontalScrollView(this);
    scroller.setHorizontalScrollBarEnabled(false);
    LinearLayout chips = row();
    chips.addView(
        folderChip("Все · " + store.totalNotes(), folder == FolderPolicy.ALL, FolderPolicy.ALL, null));
    for (FolderStore.Folder f : folders.list())
      chips.addView(folderChip(f.name + " · " + f.notes, folder == f.id, f.id, f));
    // «Новая папка» — квадратная кнопка с плюсом и пунктирной рамкой (0.10.24).
    ImageView add = new ImageView(this);
    add.setScaleType(ImageView.ScaleType.CENTER);
    add.setImageDrawable(new Glyph("add", GREEN, 16));
    GradientDrawable dashed = shape(android.graphics.Color.TRANSPARENT, 10);
    dashed.setStroke(dp(1), STROKE, dp(4), dp(3));
    add.setBackground(
        new RippleDrawable(android.content.res.ColorStateList.valueOf(0x2028634B), dashed, null));
    add.setFocusable(true);
    add.setContentDescription("Создать папку");
    add.setOnClickListener(
        v -> {
          buzz(0);
          askFolderName(
              "Новая папка",
              "",
              "Создать",
              name -> {
                long id = folders.create(name, false);
                if (id == 0) {
                  toast("Название не подходит. «Все заметки» занято системой.");
                  return;
                }
                folder = id;
                render();
              });
        });
    LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(dp(36), dp(36));
    addParams.leftMargin = dp(6);
    chips.addView(add, addParams);
    TextView suggest = text("Подобрать папки", 13, GREEN);
    styleChip(suggest, LIME);
    suggest.setCompoundDrawablesWithIntrinsicBounds(new Glyph("spark", GREEN, 16), null, null, null);
    suggest.setFocusable(true);
    suggest.setContentDescription("Подобрать папки по темам заметок");
    suggest.setOnClickListener(
        v -> {
          buzz(0);
          showFolderSuggestions();
        });
    LinearLayout.LayoutParams suggestParams = new LinearLayout.LayoutParams(-2, dp(36));
    suggestParams.leftMargin = dp(6);
    chips.addView(suggest, suggestParams);
    TextView videoChip = text("Сводка видео", 13, GREEN);
    styleChip(videoChip, LIME);
    videoChip.setCompoundDrawablesWithIntrinsicBounds(new Glyph("video", GREEN, 16), null, null, null);
    videoChip.setFocusable(true);
    videoChip.setContentDescription("Сделать сводку видео YouTube по ссылке");
    videoChip.setOnClickListener(
        v -> {
          buzz(0);
          askVideoLink();
        });
    LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(-2, dp(36));
    videoParams.leftMargin = dp(6);
    chips.addView(videoChip, videoParams);
    int inTrash = store.trashCount();
    if (inTrash > 0) {
      TextView trashChip = text("Корзина · " + inTrash, 13, MUTED);
      styleChip(trashChip, LINE);
      trashChip.setCompoundDrawablesWithIntrinsicBounds(new Glyph("trash", MUTED, 16), null, null, null);
      trashChip.setFocusable(true);
      trashChip.setContentDescription("Корзина, заметок: " + inTrash);
      trashChip.setOnClickListener(
          v -> {
            buzz(0);
            hideKeyboard();
            page = "trash";
            render();
          });
      LinearLayout.LayoutParams trashParams = new LinearLayout.LayoutParams(-2, dp(36));
      trashParams.leftMargin = dp(6);
      chips.addView(trashChip, trashParams);
    }
    scroller.addView(chips);
    OneShotPreDrawListener.add(scroller, () -> {
      scroller.scrollTo(folderScrollX, 0);
      folderScrollX = scroller.getScrollX();
      scroller.setOnScrollChangeListener((v, x, y, oldX, oldY) -> folderScrollX = x);
    });
    return scroller;
  }

  private Map<String, Long> folderKeys() {
    Map<String, Long> keys = new HashMap<>();
    for (FolderStore.Folder f : new FolderStore(store).list()) keys.put(FolderPolicy.key(f.name), f.id);
    return keys;
  }

  private Set<String> dismissedTopics() {
    return new HashSet<>(prefs.getStringSet("folderDismissed", new HashSet<>()));
  }

  private static String plural(int n, String one, String few, String many) {
    int hundreds = n % 100, ones = n % 10;
    if (hundreds >= 11 && hundreds <= 14) return many;
    if (ones == 1) return one;
    return ones >= 2 && ones <= 4 ? few : many;
  }

  /** Создаёт папку (или берёт существующую) и переносит в неё заметки без папки. Возвращает число перенесённых. */
  private int applyFolderSuggestion(FolderSuggestion.Suggestion s) {
    FolderStore folders = new FolderStore(store);
    long id = s.existing > 0 && folders.exists(s.existing) ? s.existing : folders.create(s.name, true);
    if (id == 0) return 0;
    int moved = 0;
    for (long noteId : s.notes) {
      Note current = store.note(noteId);
      if (current != null && current.folder == FolderPolicy.ALL) {
        folders.move(noteId, id);
        moved++;
      }
    }
    return moved;
  }

  /** Предлагает папки по общим темам заметок; создаёт и переносит только выбранное в окне. */
  private void showFolderSuggestions() {
    ManagerStore manager = new ManagerStore(store);
    List<Note> pool = new ArrayList<>();
    for (Note n : store.notes())
      if (manager.top(n.id) == 0 || manager.top(n.id) == n.id) pool.add(n);
    List<FolderSuggestion.Suggestion> list =
        FolderSuggestion.suggest(pool, folderKeys(), dismissedTopics());
    if (list.isEmpty()) {
      toast("Пока нечего предложить. Нужны заметки с общей темой: темы ИИ появляются после проверки заметок.");
      return;
    }
    String[] labels = new String[list.size()];
    boolean[] chosen = new boolean[list.size()];
    for (int i = 0; i < labels.length; i++) {
      FolderSuggestion.Suggestion s = list.get(i);
      int n = s.notes.size();
      labels[i] =
          s.name
              + (s.existing > 0 ? " (папка уже есть)" : "")
              + " · "
              + n
              + " "
              + plural(n, "заметка", "заметки", "заметок");
      chosen[i] = true;
    }
    new Ask(this)
        .setTitle("Папки по темам")
        .setMultiChoiceItems(labels, chosen, (d, which, checked) -> chosen[which] = checked)
        .setNegativeButton("Отмена", null)
        .setNeutralButton(
            "Скрыть эти",
            (d, w) -> {
              Set<String> hidden = dismissedTopics();
              for (FolderSuggestion.Suggestion s : list) hidden.add(FolderPolicy.key(s.name));
              prefs.edit().putStringSet("folderDismissed", hidden).apply();
              toast("Эти темы больше не предлагаются");
            })
        .setPositiveButton(
            "Создать и перенести",
            (d, w) -> {
              int folders = 0, moved = 0;
              for (int i = 0; i < list.size(); i++)
                if (chosen[i]) {
                  int count = applyFolderSuggestion(list.get(i));
                  moved += count;
                  if (count > 0 && list.get(i).existing == 0) folders++;
                }
              toast(
                  moved == 0
                      ? "Ничего не перенесено"
                      : "Создано папок: " + folders + ", перенесено заметок: " + moved);
              render();
            })
        .show();
  }

  private final ArrayDeque<Long> folderQueue = new ArrayDeque<>();

  /**
   * После сводки видео предлагает папку по первой теме новой заметки. true — окно показано; очередь
   * продолжится после ответа. Не мешает окну объединения и работающему запросу.
   */
  private boolean askNextFolder() {
    if (mergeAsking || VideoRunner.running || isDestroyed() || isFinishing()) return mergeAsking;
    ManagerStore manager = new ManagerStore(store);
    while (!folderQueue.isEmpty()) {
      Note n = store.note(folderQueue.poll());
      if (n == null || manager.top(n.id) != 0) continue;
      FolderSuggestion.Suggestion s = FolderSuggestion.forNote(n, folderKeys(), dismissedTopics());
      if (s == null) continue;
      mergeAsking = true;
      Runnable next =
          () -> {
            mergeAsking = false;
            askNext();
          };
      new Ask(this)
          .setTitle(
              s.existing > 0
                  ? "Положить в папку «" + s.name + "»?"
                  : "Создать папку «" + s.name + "»?")
          .setMessage(
              "Заметка «"
                  + n.title
                  + "» — про «"
                  + s.name
                  + "». "
                  + (s.existing > 0
                      ? "Такая папка уже есть: переместить заметку туда?"
                      : "Создать такую папку и переместить заметку в неё?"))
          .setNegativeButton("Нет", (d, w) -> next.run())
          .setNeutralButton(
              "Не предлагать",
              (d, w) -> {
                Set<String> hidden = dismissedTopics();
                hidden.add(FolderPolicy.key(s.name));
                prefs.edit().putStringSet("folderDismissed", hidden).apply();
                next.run();
              })
          .setPositiveButton(
              s.existing > 0 ? "Положить" : "Создать",
              (d, w) -> {
                applyFolderSuggestion(s);
                toast("Заметка в папке «" + s.name + "»");
                next.run();
                if ("notes".equals(page)) render();
              })
          .setOnCancelListener(d -> next.run())
          .show();
      return true;
    }
    return false;
  }

  private boolean askNext() {
    return askNextMerge() || askNextFolder();
  }

  /** Кнопка-фильтр в ряду папок: 36 dp, скругление 10, 13 sp жирным (0.10.24). */
  private void styleChip(TextView chip, int fill) {
    chip.setTypeface(null, Typeface.BOLD);
    chip.setGravity(Gravity.CENTER);
    chip.setSingleLine(true);
    chip.setMinHeight(dp(36));
    chip.setPadding(dp(12), 0, dp(12), 0);
    chip.setCompoundDrawablePadding(dp(6));
    chip.setBackground(ripple(fill, 10));
  }

  private View folderChip(String label, boolean selected, long id, FolderStore.Folder f) {
    TextView chip = text(label, 13, selected ? WHITE : GREEN);
    styleChip(chip, selected ? FILL : LIME);
    LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, dp(36));
    if (id != FolderPolicy.ALL) chipParams.leftMargin = dp(6);
    chip.setLayoutParams(chipParams);
    chip.setFocusable(true);
    chip.setSelected(selected);
    chip.setContentDescription(f == null ? "Все заметки" : "Папка " + f.name);
    chip.setOnClickListener(
        v -> {
          buzz(0);
          if (folder == id) return;
          folder = id;
          render();
        });
    if (f != null)
      chip.setOnLongClickListener(
          v -> {
            Pop popup = new Pop(chip);
            popup
                .getMenu()
                .add("Переименовать")
                .setOnMenuItemClickListener(
                    item -> {
                      askFolderName(
                          "Переименовать папку",
                          f.name,
                          "Сохранить",
                          name -> {
                            if (new FolderStore(store).rename(f.id, name)) render();
                            else toast("Такая папка уже есть или название не подходит.");
                          });
                      return true;
                    });
            popup
                .getMenu()
                .add("Удалить папку")
                .setOnMenuItemClickListener(
                    item -> {
                      new Ask(this)
                          .setTitle("Удалить папку «" + f.name + "»?")
                          .setMessage(
                              "Заметки из неё не удаляются: они останутся в «Все заметки».")
                          .setNegativeButton("Отмена", null)
                          .setPositiveButton(
                              "Удалить",
                              (d, w) -> {
                                new FolderStore(store).delete(f.id);
                                if (folder == f.id) {
                                  folder = FolderPolicy.ALL;
                                }
                                render();
                              })
                          .show();
                      return true;
                    });
            popup.show();
            return true;
          });
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    if (id != FolderPolicy.ALL) lp.leftMargin = dp(8);
    chip.setLayoutParams(lp);
    return chip;
  }

  private void askFolderName(
      String title, String initial, String action, java.util.function.Consumer<String> done) {
    EditText field = input("Название папки", false);
    field.setText(initial);
    field.setSelection(field.getText().length());
    FrameLayout holder = new FrameLayout(this);
    holder.setPadding(dp(20), dp(8), dp(20), 0);
    holder.addView(field, new FrameLayout.LayoutParams(-1, -2));
    new Ask(this)
        .setTitle(title)
        .setView(holder)
        .setNegativeButton("Отмена", null)
        .setPositiveButton(action, (d, w) -> done.accept(field.getText().toString()))
        .show();
  }

  /**
   * Выбор папки: «Без папки», существующие и «Новая папка…». Номер папки возвращается в {@code
   * picked} (0 — без папки); решает вызывающий, применять ли его сразу.
   */
  private void chooseFolder(long current, java.util.function.LongConsumer picked) {
    FolderStore folders = new FolderStore(store);
    List<FolderStore.Folder> list = folders.list();
    String[] items = new String[list.size() + 2];
    items[0] = (current == FolderPolicy.ALL ? "✓ " : "") + "Без папки (Все заметки)";
    for (int i = 0; i < list.size(); i++)
      items[i + 1] = (list.get(i).id == current ? "✓ " : "") + list.get(i).name;
    items[items.length - 1] = "＋ Новая папка…";
    new Ask(this)
        .setTitle("Папка")
        .setItems(
            items,
            (d, which) -> {
              if (which == 0) picked.accept(FolderPolicy.ALL);
              else if (which == items.length - 1)
                askFolderName(
                    "Новая папка",
                    "",
                    "Создать",
                    name -> {
                      long id = folders.create(name, false);
                      if (id == 0) toast("Название не подходит. «Все заметки» занято системой.");
                      else picked.accept(id);
                    });
              else picked.accept(list.get(which - 1).id);
            })
        .setNegativeButton("Отмена", null)
        .show();
  }

  /**
   * Карточка заметки для кладки (0.10.24): высота по содержимому, обложка-фото сверху, время
   * напоминания, название, текст, полоска плана, метки помощника, дата. Меню — долгим нажатием.
   */
  private Pattern notesHighlight;
  private long notesAnchor;
  private int notesAnchorOffset;

  private void rememberNotePosition(View view, long id) {
    ViewParent parent = view.getParent();
    while (parent instanceof View && !(parent instanceof ScrollView)) parent = parent.getParent();
    if (parent instanceof ScrollView viewport) {
      Rect bounds = new Rect();
      viewport.offsetDescendantRectToMyCoords(view, bounds);
      notesAnchorOffset = bounds.top - viewport.getScrollY();
      notesAnchor = id;
    }
  }

  private CharSequence highlightNoteText(String value) {
    if (notesHighlight == null) return value;
    SpannableString styled = new SpannableString(value);
    Matcher matches = notesHighlight.matcher(value);
    while (matches.find()) {
      styled.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), matches.start(), matches.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      styled.setSpan(new android.text.style.BackgroundColorSpan(LIME), matches.start(), matches.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    return styled;
  }

  private TextView deckToggle(long top, int count, boolean expanded) {
    String number = count + " " + TrashPolicy.plural(count, "заметка", "заметки", "заметок");
    TextView toggle = text("Колода · " + number, 13, STACK_FG);
    toggle.setTypeface(null, Typeface.BOLD);
    toggle.setMinHeight(dp(48));
    toggle.setGravity(Gravity.CENTER_VERTICAL);
    toggle.setCompoundDrawablesWithIntrinsicBounds(null, null, new Glyph(expanded ? "up" : "down", STACK_FG, 16), null);
    toggle.setContentDescription((expanded ? "Свернуть колоду, " : "Раскрыть колоду, ") + number);
    toggle.setTag(-top);
    toggle.setFocusable(true);
    toggle.setOnClickListener(v -> {
      rememberNotePosition(v, -top);
      toggleDeck(new ManagerStore(store), top);
    });
    return toggle;
  }

  private View noteCard(Note n, boolean grid, boolean expanded) {
    boolean inDeck = new ManagerStore(store).top(n.id) != 0;
    List<Long> sources = membersOf(n.id);
    LinearLayout card = column();
    card.setTag(n.id);
    int cardColor = inDeck && !expanded ? STACK_BG : n.reminder > 0 ? REMINDER_BG : SURFACE;
    GradientDrawable panel = shape(cardColor, 14);
    panel.setStroke(dp(1), inDeck ? STACK_LINE : LINE);
    card.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(0x2028634B), panel, null));
    card.setClipToOutline(true);
    card.setOutlineProvider(rounded(14));
    card.setOnClickListener(
        v -> {
          buzz(0);
          rememberNotePosition(card, n.id);
          openNote(n.id, notesHighlight);
        });
    card.setOnLongClickListener(
        v -> {
          buzz(0);
          showNoteMenu(card, n);
          return true;
        });
    if (!n.image.isEmpty()) {
      // Фото — обложкой во всю ширину карточки; при нескольких — счётчик в углу.
      int imageCount = ImageList.count(n.image);
      FrameLayout cover = new FrameLayout(this);
      ImageView thumb = new ImageView(this);
      thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
      thumb.setBackgroundColor(LIME);
      thumb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      ImageThumbs.load(this, thumb, ImageList.cover(n.image), 360);
      cover.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
      if (imageCount > 1) {
        TextView badge = text("+" + (imageCount - 1), 11, WHITE);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setBackground(shape(0xAA000000, 6));
        badge.setPadding(dp(6), dp(2), dp(6), dp(2));
        FrameLayout.LayoutParams bp =
            new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        bp.setMargins(0, 0, dp(8), dp(8));
        cover.addView(badge, bp);
      }
      card.addView(cover, new LinearLayout.LayoutParams(-1, dp(grid ? 96 : 120)));
    }
    LinearLayout inner = column();
    inner.setPadding(dp(14), dp(14), dp(14), dp(14));
    card.addView(inner, new LinearLayout.LayoutParams(-1, -2));
    if (n.reminder > 0) {
      TextView when =
          text(date(n.reminder) + moreDates(n.repeat, n.reminderTimes), 12, GREEN);
      when.setTypeface(null, Typeface.BOLD);
      when.setCompoundDrawablesWithIntrinsicBounds(new Glyph("clock", GREEN, 14), null, null, null);
      when.setCompoundDrawablePadding(dp(6));
      when.setMaxLines(2);
      inner.addView(when);
      gap(inner, 8);
    }
    if (!sources.isEmpty() && !expanded && search.trim().isEmpty())
      inner.addView(deckToggle(n.id, sources.size() + 1, false));
    TextView title = text(n.title, 16, inDeck ? (dark ? INK : 0xFF3E2352) : INK);
    title.setText(highlightNoteText(n.title));
    title.setTypeface(null, Typeface.BOLD);
    title.setMaxLines(grid ? 3 : 1);
    title.setEllipsize(TextUtils.TruncateAt.END);
    inner.addView(title);
    String preview = SearchQuery.preview(n.body, notesHighlight);
    if (!preview.isEmpty()) {
      gap(inner, 6);
      TextView body = text(preview, 14, inDeck ? STACK_FG : MUTED);
      body.setText(highlightNoteText(preview));
      body.setLineSpacing(dp(2), 1);
      body.setMaxLines(grid ? 6 : 2);
      body.setEllipsize(TextUtils.TruncateAt.END);
      inner.addView(body);
    }
    String planned = planBadge(n.id);
    if (!planned.isEmpty()) {
      gap(inner, 10);
      java.util.regex.Matcher m = Pattern.compile("(\\d+)/(\\d+)").matcher(planned);
      if (m.find()) {
        int done = Integer.parseInt(m.group(1)), total = Math.max(1, Integer.parseInt(m.group(2)));
        FrameLayout track = new FrameLayout(this);
        track.setBackground(shape(LINE, 2));
        View fill = new View(this);
        fill.setBackground(shape(FILL, 2));
        track.addView(fill, new FrameLayout.LayoutParams(0, -1));
        track.addOnLayoutChangeListener(
            (v, l, t, r, b, ol, ot, or, ob) -> {
              int width = Math.round((r - l) * Math.min(1f, done / (float) total));
              ViewGroup.LayoutParams fp = fill.getLayoutParams();
              if (fp.width != width) {
                fp.width = width;
                fill.post(() -> fill.setLayoutParams(fp));
              }
            });
        track.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        inner.addView(track, new LinearLayout.LayoutParams(-1, dp(4)));
        gap(inner, 6);
        planned = "План: " + done + " из " + m.group(2);
      }
      TextView plan = text(planned, 12, GREEN);
      plan.setTypeface(null, Typeface.BOLD);
      inner.addView(plan);
    }
    if (n.suggestion > 0) {
      gap(inner, 6);
      inner.addView(cardMark("Есть предложение ИИ", GREEN));
    }
    if (questionCache != null && questionCache.contains(n.id)) {
      gap(inner, 6);
      inner.addView(cardMark("Вопрос помощника", STACK_FG));
    }
    if (n.reminder == 0) {
      gap(inner, 8);
      int imageCount = n.image.isEmpty() ? 0 : ImageList.count(n.image);
      inner.addView(
          text(
              noteCardDate(n) + (imageCount > 0 ? " · " + imageCount + " фото" : ""),
              12,
              MUTED));
    }
    card.setContentDescription(
        n.title
            + (n.reminder > 0 ? ", напомнить " + date(n.reminder) : "")
            + ", "
            + preview
            + ", создана "
            + date(n.created)
            + ". Долгое нажатие — меню");
    card.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
    return card;
  }

  /** Метка на карточке: точка цвета и короткая подпись. */
  private TextView cardMark(String label, int color) {
    TextView mark = text(label, 12, color);
    mark.setTypeface(null, Typeface.BOLD);
    GradientDrawable dot = shape(color, 4);
    dot.setSize(dp(8), dp(8));
    mark.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null);
    mark.setCompoundDrawablePadding(dp(6));
    return mark;
  }

  /** Меню заметки (долгое нажатие на карточку): удалить, папка, колода. */
  private void showNoteMenu(View anchor, Note n) {
          ManagerStore manager = new ManagerStore(store);
          Pop popup = new Pop(anchor);
          popup
              .getMenu()
              .add("Удалить")
              .setOnMenuItemClickListener(
                  item -> {
                    returnPage = "notes";
                    deleteNote(n.id);
                    return true;
                  });
          popup
              .getMenu()
              .add("Переместить в папку")
              .setOnMenuItemClickListener(
                  item -> {
                    chooseFolder(
                        n.folder,
                        target -> {
                          new FolderStore(store).move(n.id, target);
                          toast(
                              target == FolderPolicy.ALL
                                  ? "Заметка без папки"
                                  : "Заметка перенесена");
                          render();
                        });
                    return true;
                  });
          long top = manager.top(n.id);
          if (top != 0)
            popup
                .getMenu()
                .add(manager.expanded(top) ? "Свернуть колоду" : "Раскрыть колоду")
                .setOnMenuItemClickListener(
                    item -> {
                      toggleDeck(manager, top);
                      return true;
                    });
          if (top != 0 && top == n.id)
            popup
                .getMenu()
                .add("Разобрать колоду")
                .setOnMenuItemClickListener(
                    item -> {
                      dissolveDeck(n.id);
                      return true;
                    });
          if (top != 0 && top != n.id)
            popup
                .getMenu()
                .add("Убрать из колоды")
                .setOnMenuItemClickListener(
                    item -> {
                      manager.removeMember(n.id);
                      render();
                      toast("Заметка вернулась в список");
                      return true;
                    });
          popup.show();
  }

  /**
   * Свёрнутая колода (этап 8): под верхней карточкой 1–3 слоя, повёрнутые на ±2–4° и сдвинутые на
   * ~5 dp ({@link DeckGeometry}); места снизу ровно столько, чтобы углы слоёв не обрезались.
   */
  private View groupedCard(Note n, boolean grid) {
    View card = noteCard(n, grid, false);
    int layers = DeckGeometry.layers(membersOf(n.id).size());
    if (layers == 0 || !search.trim().isEmpty()) return card;
    // Высота карточки в кладке своя у каждой, поэтому слои растягиваются по высоте самой карточки
    // (MATCH_PARENT со сдвигом вниз), а запас снизу считается по средней высоте карточки.
    FrameLayout stack = new FrameLayout(this);
    int screen = getResources().getConfiguration().screenWidthDp;
    int columns = grid ? (screen >= 600 ? 3 : 2) : 1;
    int widthDp = (screen - 44 - (columns - 1) * 10) / columns;
    int extra = dp(DeckGeometry.extra(layers, widthDp, 160));
    for (int i = layers; i > 0; i--) {
      View layer = new View(this);
      GradientDrawable shape = shape(STACK_LAYER, 12);
      shape.setStroke(dp(1), STACK_LINE);
      layer.setBackground(shape);
      FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
      lp.topMargin = dp(DeckGeometry.dy(i));
      lp.bottomMargin = extra - dp(DeckGeometry.dy(i));
      lp.leftMargin = dp(DeckGeometry.INSET);
      lp.rightMargin = dp(DeckGeometry.INSET);
      layer.setTranslationX(dp(DeckGeometry.dx(i)));
      layer.setRotation(DeckGeometry.angle(i));
      stack.addView(layer, lp);
    }
    FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(-1, -2);
    cardParams.bottomMargin = extra;
    stack.addView(card, cardParams);
    stack.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
    return stack;
  }

  /** Колода, которую только что раскрыли: при следующей отрисовке её карточки вылетают веером. */
  private long fanOpen = 0;
  /** Карточки раскрытых колод на экране (верхняя первой) — для анимации сворачивания. */
  private final Map<Long, List<View>> deckViews = new HashMap<>();

  private static boolean motion() {
    return android.animation.ValueAnimator.areAnimatorsEnabled();
  }

  /**
   * Раскрытие веером: исходные карточки стартуют из-под верхней (со сдвигом и наклоном слоя) и
   * по очереди встают на свои места. Без анимаций в системе — сразу на местах.
   */
  private void fanOut(List<View> views) {
    if (views.size() < 2 || !motion()) return;
    View top = views.get(0);
    for (int i = 1; i < views.size(); i++) views.get(i).setAlpha(0f);
    top.post(
        () -> {
          int[] from = new int[2], at = new int[2];
          top.getLocationOnScreen(from);
          for (int i = 1; i < views.size(); i++) {
            View v = views.get(i);
            v.getLocationOnScreen(at);
            v.setTranslationX(from[0] - at[0]);
            v.setTranslationY(from[1] - at[1] + dp(DeckGeometry.STEP));
            v.setRotation(DeckGeometry.fanAngle(i));
            v.animate()
                .translationX(0)
                .translationY(0)
                .rotation(0)
                .alpha(1f)
                .setStartDelay(DeckGeometry.delay(i))
                .setDuration(DeckGeometry.FAN_MS)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .start();
          }
        });
  }

  /** «Раскрыть/Свернуть колоду»: сворачивание сначала собирает карточки под верхнюю, потом перерисовка. */
  private void toggleDeck(ManagerStore manager, long top) {
    boolean wasExpanded = manager.expanded(top);
    List<View> views = deckViews.get(top);
    Runnable apply =
        () -> {
          manager.toggle(top);
          if (!wasExpanded) fanOpen = top;
          search = "";
          render();
        };
    if (!wasExpanded || views == null || views.size() < 2 || !motion()) {
      apply.run();
      return;
    }
    View first = views.get(0);
    int[] to = new int[2], at = new int[2];
    first.getLocationOnScreen(to);
    int last = views.size() - 1;
    for (int i = 1; i <= last; i++) {
      View v = views.get(i);
      v.getLocationOnScreen(at);
      android.view.ViewPropertyAnimator gather =
          v.animate()
              .translationX(to[0] - at[0])
              .translationY(to[1] - at[1] + dp(DeckGeometry.STEP))
              .rotation(DeckGeometry.fanAngle(i))
              .alpha(0f)
              .setStartDelay(DeckGeometry.delay(last - i + 1))
              .setDuration(DeckGeometry.FAN_MS - 80)
              .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f));
      // Первая исходная уходит последней: по её окончании колода сворачивается в базе.
      if (i == 1) gather.withEndAction(apply);
      gather.start();
    }
  }

  /** Текст предложения; для уточнения впереди название заметки, о которой вопрос. */
  private String proposalReason(JSONObject p) {
    String reason = p.optString("reason");
    if (!p.optString("type").equals("clarify") && !p.optString("type").equals("plan"))
      return reason;
    JSONArray sources = p.optJSONArray("sources");
    Note n = sources == null || sources.length() == 0 ? null : store.note(sources.optJSONObject(0).optLong("id"));
    return n == null ? reason : "«" + n.title + "»: " + reason;
  }

  /** Варианты ответа на уточняющий вопрос ИИ: выбранный запоминается и учитывается при проверке. */
  private void clarifyOptions(
      LinearLayout parent, JSONObject p, ManagerStore manager, Runnable after) {
    JSONArray options = p.optJSONArray("options");
    for (int i = 0; options != null && i < options.length(); i++) {
      String option = options.optString(i);
      parent.addView(
          button(
              option,
              false,
              () -> {
                boolean answered = manager.answerClarify(p.optLong("id"), option);
                after.run();
                if (answered) undoBar("Запомнил: " + option);
                else toast("Запись уже изменилась");
              }));
      gap(parent, 6);
    }
    parent.addView(
        button(
            "Свой ответ",
            options == null || options.length() == 0,
            () -> {
              EditText field = input("Ваш ответ", false);
              field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(300)});
              LinearLayout box = column();
              pad(box, 20);
              box.addView(field);
              Popup dialog =
                  new Ask(this)
                      .setTitle(p.optString("reason"))
                      .setView(box)
                      .setNegativeButton("Отмена", null)
                      .setPositiveButton("Ответить", null)
                      .create();
              dialog.setOnShowListener(
                  d ->
                      dialog
                          .getButton(DialogInterface.BUTTON_POSITIVE)
                          .setOnClickListener(
                              v -> {
                                String answer = field.getText().toString().trim();
                                if (answer.isEmpty()) {
                                  field.setError("Напишите ответ");
                                  return;
                                }
                                dialog.dismiss();
                                boolean answered = manager.answerClarify(p.optLong("id"), answer);
                                after.run();
                                if (answered) undoBar("Запомнил: " + answer);
                                else toast("Запись уже изменилась");
                              }));
              dialog.show();
            }));
    gap(parent, 6);
    JSONArray sources = p.optJSONArray("sources");
    long noteId = sources == null || sources.length() == 0 ? 0 : sources.optJSONObject(0).optLong("id");
    if (noteId > 0 && !"edit".equals(page))
      parent.addView(
          quietAction(
              "Открыть заметку",
              () -> {
                if (suggestionsPopup != null) suggestionsPopup.dismiss();
                openNote(noteId);
              }));
    parent.addView(
        quietAction(
            "Не нужно",
            () -> {
              manager.state(p.optLong("id"), "dismissed");
              after.run();
            }));
  }

  /** «Разобрать план» для большой задачи: открыть чат по заметке или отказаться (больше не предлагать). */
  private void planOfferActions(
      LinearLayout parent, JSONObject p, ManagerStore manager, Runnable after) {
    long noteId = ManagerStore.firstSource(p);
    parent.addView(
        button(
            "Разобрать",
            true,
            () -> {
              manager.state(p.optLong("id"), "accepted");
              if (suggestionsPopup != null) suggestionsPopup.dismiss();
              openPlanChat(noteId, true);
            }));
    gap(parent, 6);
    parent.addView(
        quietAction(
            "Не нужно",
            () -> {
              manager.state(p.optLong("id"), "dismissed");
              after.run();
            }));
  }

  /** Открытый вопрос агента в открытой заметке: варианты кнопками, свой ответ, «Не нужно». */
  private void questionSection(long noteId) {
    ManagerStore manager = new ManagerStore(store);
    JSONObject p = manager.pendingQuestion(noteId);
    if (p == null) return;
    gap(content, 12);
    LinearLayout box = column();
    pad(box, 14);
    box.setBackground(shape(LIME, 12));
    TextView head = text("Вопрос помощника", 13, GREEN);
    head.setTypeface(null, Typeface.BOLD);
    if (Build.VERSION.SDK_INT >= 28) head.setAccessibilityHeading(true);
    box.addView(head);
    gap(box, 4);
    box.addView(text(p.optString("reason"), 15, INK));
    gap(box, 8);
    clarifyOptions(
        box,
        p,
        manager,
        () -> {
          stash();
          render();
        });
    content.addView(box);
  }

  /** Строка журнала агента: что сделано, когда и «Отменить», пока прошло меньше суток. */
  private View journalRow(AgentLog.Entry e, Runnable beforeUndo) {
    long now = System.currentTimeMillis();
    LinearLayout line = row();
    line.setGravity(Gravity.CENTER_VERTICAL);
    line.setPadding(0, dp(6), 0, dp(6));
    LinearLayout words = column();
    TextView what = text(e.text(), 13, AgentJournal.UNDONE.equals(e.state) ? MUTED : INK);
    what.setMaxLines(3);
    what.setEllipsize(TextUtils.TruncateAt.END);
    words.addView(what);
    String left = AgentJournal.left(e.state, e.created, now);
    words.addView(
        text(
            shortDate(e.created)
                + (AgentJournal.UNDONE.equals(e.state)
                    ? " · отменено"
                    : left.isEmpty() ? "" : " · отменить можно " + left),
            11,
            MUTED));
    line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    if (e.undoable(now)) {
      TextView undo =
          compactButton(
              "Отменить",
              false,
              () -> {
                beforeUndo.run();
                confirmUndo(e);
              });
      undo.setContentDescription("Отменить: " + e.text());
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.leftMargin = dp(8);
      line.addView(undo, lp);
    }
    return line;
  }

  private void confirmUndo(AgentLog.Entry e) {
    new Ask(this)
        .setTitle("Отменить действие помощника?")
        .setMessage(e.text() + "\n\n" + AgentJournal.undoHint(e.action))
        .setNegativeButton("Назад", null)
        .setPositiveButton("Отменить", (d, w) -> undoAgent(e.id))
        .show();
  }

  private void undoAgent(long id) {
    String done;
    try {
      AgentLog.Outcome outcome = AgentLog.undo(store, id);
      done = outcome.message;
      // Ушедшие в корзину — без будильника, вернувшиеся из неё — с будильником, как при «Восстановить».
      for (long gone : outcome.trashed) Reminders.cancel(this, gone);
      long now = System.currentTimeMillis();
      for (long back : outcome.restored) {
        Note n = store.note(back);
        if (n != null && n.reminder > now) Reminders.schedule(this, n);
      }
      for (int i = 0; i < outcome.reminderAfter.size(); i++)
        Reminders.scheduleEdited(this, outcome.reminderBefore.get(i), outcome.reminderAfter.get(i));
      if (!outcome.trashed.isEmpty() || !outcome.restored.isEmpty() || !outcome.reminderAfter.isEmpty())
        CalendarSync.runAsync(this);
    } catch (IllegalStateException e) {
      done = e.getMessage();
    } catch (Exception e) {
      done = "Не удалось отменить";
    }
    render();
    toast(done);
  }

  /**
   * Плашка внизу экрана сразу после действия помощника: что сделано и «Отменить» без
   * подтверждения. Через 8 секунд исчезает; отменить можно и позже — из колокольчика, сутки.
   */
  private void undoBar(String message) {
    List<AgentLog.Entry> last = AgentLog.recent(store, 1, AgentJournal.UNDO_WINDOW);
    if (frame == null || last.isEmpty() || !last.get(0).undoable(System.currentTimeMillis())) {
      toast(message);
      return;
    }
    long id = last.get(0).id;
    LinearLayout bar = row();
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setBackground(shape(INK, 12));
    bar.setPadding(dp(16), dp(4), dp(4), dp(4));
    bar.setElevation(dp(8));
    bar.addView(text(message, 14, PAPER), new LinearLayout.LayoutParams(0, -2, 1));
    TextView undo = text("Отменить", 14, LIME);
    undo.setTypeface(null, Typeface.BOLD);
    undo.setGravity(Gravity.CENTER);
    undo.setMinHeight(dp(44));
    undo.setPadding(dp(14), 0, dp(14), 0);
    undo.setBackground(ripple(android.graphics.Color.TRANSPARENT, 10));
    undo.setContentDescription("Отменить: " + message);
    FrameLayout host = frame;
    undo.setOnClickListener(
        v -> {
          buzz(0);
          host.removeView(bar);
          undoAgent(id);
        });
    bar.addView(undo);
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    lp.leftMargin = dp(16);
    lp.rightMargin = dp(16);
    lp.bottomMargin = navInset + dp(96);
    host.addView(bar, lp);
    bar.postDelayed(() -> host.removeView(bar), 8000);
  }

  /** Карточка про колоду: собрать новую или добавить в существующую. */
  private static boolean deckProposal(String type) {
    return TopicSelection.deck(type) || type.equals("extend");
  }

  private String proposalLabel(String type) {
    return switch (type) {
      case "group", "merge", "link" -> "Собрать в колоду";
      case "extend" -> "Добавить в колоду";
      case "task" -> "Идея для дела";
      case "clarify" -> "Вопрос помощника";
      case "plan" -> "Разобрать план";
      default -> "Стоит уточнить";
    };
  }

  private TextView quietAction(String label, Runnable action) {
    TextView view = button(label, false, action);
    view.setBackground(ripple(android.graphics.Color.TRANSPARENT, 10));
    view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    view.setPadding(dp(8), dp(8), dp(8), dp(8));
    view.setMinHeight(dp(48));
    return view;
  }

  private TextView limitButton() {
    TextView control =
        quietAction(
            prefs.getBoolean("unlimitedAi", false) ? "Вернуть лимит" : "Снять лимит 4/день",
            () -> {});
    control.setOnClickListener(
        view -> {
          boolean unlimited = !prefs.getBoolean("unlimitedAi", false);
          prefs.edit().putBoolean("unlimitedAi", unlimited).apply();
          if ("manager".equals(page)) render();
          else control.setText(unlimited ? "Вернуть лимит" : "Снять лимит 4/день");
          toast(
              unlimited
                  ? "Дневной лимит снят. Ручные и фоновые запросы остаются платными."
                  : "Лимит 4 проверки в день возвращён. Выполненные запросы учитываются.");
        });
    return control;
  }

  private String analysisHistoryText() {
    StringBuilder result = new StringBuilder();
    try {
      JSONArray entries = new JSONArray(prefs.getString("analysisHistory", "[]"));
      for (int i = 0; i < entries.length(); i++) {
        JSONObject entry = entries.getJSONObject(i);
        if (i > 0) result.append("\n\n");
        result.append(date(entry.getLong("time"))).append("\n").append(entry.getString("text"));
      }
    } catch (Exception ignored) {
    }
    if (result.length() == 0) {
      long time = prefs.getLong("analysisTime", 0);
      if (time > 0)
        result.append(date(time)).append("\n").append(prefs.getString("analysisStatus", ""));
      else result.append("Проверок пока не было.");
    }
    return result.toString();
  }

  private void showAnalysisLog() {
    LinearLayout body = column();
    pad(body, 16);
    analysisLogText = text(analysisHistoryText(), 14, INK);
    analysisLogText.setTextIsSelectable(true);
    body.addView(analysisLogText);
    analysisLogDialog =
        new Ask(this)
            .setTitle("История проверок")
            .setView(scroll(body))
            .setPositiveButton("Закрыть", null)
            .create();
    analysisLogDialog.show();
  }

  private void managerPage() {
    LinearLayout header = row();
    TextView back =
        quietAction(
            "",
            () -> {
              page = "notes";
              render();
            });
    back.setCompoundDrawablesWithIntrinsicBounds(new Glyph("back", GREEN, 22), null, null, null);
    back.setContentDescription("Назад к заметкам");
    header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
    TextView title = text("Помощник", 26, INK);
    title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    content.addView(header);
    secondary(content, "Находит связи и помогает собрать заметки в колоды.");
    gap(content, 14);
    content.addView(button("Проверить заметки", true, this::manualAnalysis));
    gap(content, 8);
    showAnalysisStatus();
    LinearLayout tools = row();
    tools.addView(
        quietAction("История проверок", this::showAnalysisLog),
        new LinearLayout.LayoutParams(0, -2, 1));
    tools.addView(limitButton(), new LinearLayout.LayoutParams(0, -2, 1));
    content.addView(tools);
    if (prefs.getBoolean("unlimitedAi", false))
      secondary(content, "Без дневного лимита · запросы платные");
    gap(content, 12);
    ManagerStore manager = new ManagerStore(store);
    List<JSONObject> proposals = manager.pending();
    TextView count = text("Предложения · " + proposals.size(), 18, INK);
    count.setTypeface(null, Typeface.BOLD);
    content.addView(count);
    if (proposals.isEmpty()) {
      gap(content, 8);
      secondary(
          content,
          "Пока нет предложений. Проверка ищет общие темы, дубли и дела в ваших заметках.");
    }
    for (JSONObject p : proposals) {
      gap(content, 12);
      LinearLayout card = column();
      pad(card, 16);
      card.setBackground(shape(SURFACE, 12));
      String type = p.optString("type");
      TextView label = text(proposalLabel(type), 18, INK);
      label.setTypeface(null, Typeface.BOLD);
      card.addView(label);
      gap(card, 6);
      card.addView(text(proposalReason(p), 14, MUTED));
      if (type.equals("clarify")) {
        gap(card, 8);
        clarifyOptions(card, p, manager, this::render);
        content.addView(card);
        continue;
      }
      if (type.equals("plan")) {
        gap(card, 8);
        planOfferActions(card, p, manager, this::render);
        content.addView(card);
        continue;
      }
      if (TopicSelection.deck(type))
        secondary(
            card,
            "ИИ подготовит общую сводку отдельным платным запросом; исходные спрячутся в колоду под"
                + " ней и сохранятся.");
      if (type.equals("extend"))
        secondary(
            card,
            "ИИ дополнит сводку колоды новыми записями отдельным платным запросом; записи лягут в"
                + " колоду.");
      gap(card, 8);
      JSONArray sources = p.optJSONArray("sources");
      if (sources != null)
        for (int i = 0; i < sources.length(); i++) {
          long id = sources.optJSONObject(i).optLong("id");
          Note n = store.note(id);
          if (n != null) card.addView(quietAction(n.title + "  ↗", () -> openNote(id)));
        }
      gap(card, 8);
      boolean summaryReady = false;
      try {
        summaryReady = manager.summaryProgress(p.optLong("id")).optBoolean("ready");
      } catch (Exception ignored) {
      }
      card.addView(
          button(
              summaryReady
                  ? "Посмотреть выжимку"
                  : deckProposal(type) ? "Подготовить выжимку" : "Посмотреть черновик",
              true,
              () -> previewProposal(p)));
      card.addView(
          quietAction(
              "Не нужно",
              () -> {
                manager.state(p.optLong("id"), "dismissed");
                render();
              }));
      content.addView(card);
    }
    gap(content, 24);
    TextView journalTitle = text("Журнал помощника", 18, INK);
    journalTitle.setTypeface(null, Typeface.BOLD);
    if (Build.VERSION.SDK_INT >= 28) journalTitle.setAccessibilityHeading(true);
    content.addView(journalTitle);
    secondary(
        content,
        "Что помощник сделал за 30 дней: названия, созданные заметки, колоды, ответы на вопросы."
            + " Отменить можно в течение суток.");
    List<AgentLog.Entry> journal = AgentLog.recent(store, 30, 0);
    if (journal.isEmpty()) {
      gap(content, 6);
      secondary(content, "Пока пусто.");
      return;
    }
    gap(content, 8);
    LinearLayout journalCard = column();
    journalCard.setPadding(dp(16), dp(8), dp(12), dp(8));
    journalCard.setBackground(shape(SURFACE, 12));
    for (AgentLog.Entry e : journal) journalCard.addView(journalRow(e, () -> {}));
    content.addView(journalCard);
  }

  private void prepareSummary(JSONObject p) {
    try {
      JSONObject cached = new ManagerStore(store).summaryProgress(p.optLong("id"));
      if (cached.optBoolean("ready")) {
        p.put("title", cached.getString("title"))
            .put("body", cached.getString("body"))
            .put("summaryReady", true);
        previewProposal(p);
        return;
      }
    } catch (Exception ignored) {
    }
    if (!LocalLlm.cloudBackground(this)) {
      toast("Для выжимки выберите Polza.AI или Gemini Nano в настройках.");
      return;
    }
    try {
      String key = Secrets.read(this), model = prefs.getString("model", "");
      if (key.isEmpty() || model.isEmpty()) {
        toast("Укажите ключ и модель в настройках.");
        return;
      }
      boolean started =
          SummaryRunner.start(
              this,
              p,
              key,
              model,
              (result, error) -> {
                if (isDestroyed() || isFinishing()) return;
                if (error != null) {
                  new Ask(this)
                      .setTitle("Выжимка не готова")
                      .setMessage(error)
                      .setPositiveButton("Понятно", null)
                      .show();
                } else {
                  try {
                    p.put("title", result.getString("title"))
                        .put("body", result.getString("body"))
                        .put("summaryReady", true);
                    previewProposal(p);
                  } catch (Exception e) {
                    toast("Не удалось открыть выжимку");
                  }
                }
              });
      toast(
          started
              ? "ИИ готовит выжимку. Готовый текст можно будет проверить перед сохранением."
              : "Выжимка уже готовится. Откройте предложение после завершения.");
    } catch (Exception e) {
      toast("Не удалось начать выжимку");
    }
  }

  private void previewProposal(JSONObject p) {
    ManagerStore manager = new ManagerStore(store);
    try {
      if (!manager.current(p)) {
        toast("Исходники изменились. Нужна новая проверка.");
        render();
        return;
      }
    } catch (Exception e) {
      toast("Не удалось открыть предложение");
      return;
    }
    String type = p.optString("type");
    boolean deck = TopicSelection.deck(type), extend = type.equals("extend");
    if ((deck || extend) && !p.optBoolean("summaryReady")) {
      prepareSummary(p);
      return;
    }
    JSONArray proposalSources = p.optJSONArray("sources");
    boolean stacked = deck || (!extend && proposalSources != null && proposalSources.length() >= 2);
    LinearLayout form = column();
    pad(form, 20);
    if (stacked) {
      secondary(
          form,
          "Сверху колоды будет эта заметка. Исходные спрячутся под ней; их тексты и напоминания"
              + " сохранятся, «Разобрать колоду» вернёт их в список.");
      gap(form, 12);
    }
    if (extend) {
      Note head = store.note(p.optLong("stack"));
      secondary(
          form,
          "Это новая сводка колоды «"
              + (head == null ? "" : head.title)
              + "». Новые записи лягут в колоду; прежнюю сводку вернёт «Отменить».");
      gap(form, 12);
    }
    EditText title = input("Заголовок", false), body = input("Текст", true);
    String key =
        (p.optBoolean("summaryReady") ? "managerSummaryDraft." : "managerDraft.") + p.optLong("id");
    title.setText(prefs.getString(key + ".title", p.optString("title")));
    body.setText(prefs.getString(key + ".body", p.optString("body")));
    form.addView(title);
    form.addView(body);
    // Галочка «Пустые и дубли — в корзину» (решение 2): только у колоды, по умолчанию выключена.
    Switch trashExtras = null;
    if (deck) {
      try {
        Map<Long, String> extras = manager.trashCandidates(p);
        if (!extras.isEmpty()) {
          Map<Long, String> titles = new HashMap<>();
          for (long id : extras.keySet()) {
            Note n = store.note(id);
            if (n != null) titles.put(id, n.title);
          }
          gap(form, 12);
          trashExtras = toggle("Пустые и дубли — в корзину", false);
          form.addView(trashExtras);
          secondary(form, DeckPolicy.describe(extras, titles) + ". Вернуть можно 30 дней.");
        }
      } catch (Exception ignored) {
        // Без галочки: исходные просто лягут в колоду.
      }
    }
    Switch extrasSwitch = trashExtras;
    watch(title, () -> prefs.edit().putString(key + ".title", title.getText().toString()).apply());
    watch(body, () -> prefs.edit().putString(key + ".body", body.getText().toString()).apply());
    Popup dialog =
        new Ask(this)
            .setTitle(extend ? "Добавить в колоду" : stacked ? "Новая колода" : "Сохранить заметку")
            .setView(scroll(form))
            .setNegativeButton("Назад", null)
            .setPositiveButton(
                extend ? "Добавить в колоду" : stacked ? "Собрать колоду" : "Сохранить", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      try {
                        ManagerStore.Accepted done =
                            manager.accept(
                                p.optLong("id"),
                                title.getText().toString(),
                                body.getText().toString(),
                                extrasSwitch != null && extrasSwitch.isChecked());
                        for (long gone : done.trashed) Reminders.cancel(this, gone);
                        if (!done.trashed.isEmpty()) CalendarSync.runAsync(this);
                        prefs.edit().remove(key + ".title").remove(key + ".body").apply();
                        dialog.dismiss();
                        page = "notes";
                        search = "";
                        render();
                        undoBar(
                            extend
                                ? "✓ Добавлено в колоду"
                                : stacked
                                    ? done.trashed.isEmpty()
                                        ? "✓ Колода собрана"
                                        : "✓ Колода собрана, в корзину: " + done.trashed.size()
                                    : "✓ Заметка сохранена");
                      } catch (Exception e) {
                        toast(
                            e instanceof IllegalStateException
                                ? e.getMessage()
                                : "Не удалось сохранить предложение");
                      }
                    }));
    managerPreview = dialog;
    dialog.setOnDismissListener(d -> managerPreview = null);
    dialog.show();
  }

  private List<String> chatBodies(long id) {
    List<String> out = new ArrayList<>();
    JSONArray all = store.messages(id);
    for (int i = 0; i < all.length(); i++) out.add(all.optJSONObject(i).optString("content"));
    return out;
  }

  /** Страница «Чаты» (0.10.22): заголовок, «Спросить у заметок…», фильтры, лента по дням. */
  private void chats() {
    FrameLayout bar = (FrameLayout) topBar(false, true);
    TextView heading = text("Чаты", 30, INK);
    heading.setTypeface(Typeface.create("serif", Typeface.NORMAL));
    if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
    bar.addView(
        heading, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
    View findButton =
        barIcon(
            "search",
            "Поиск по чатам",
            () -> {
              chatSearchOpen = true;
              render();
            });
    FrameLayout.LayoutParams findParams =
        new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END | Gravity.CENTER_VERTICAL);
    findParams.rightMargin = dp(96);
    bar.addView(findButton, findParams);
    content.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));
    gap(content, 4);

    EditText find = null;
    if (chatSearchOpen || !chatSearch.isEmpty())
      find =
          searchRow(
              "Поиск по чатам",
              chatSearch,
              chatSearchOpen && chatSearch.isEmpty(),
              () -> {
                chatSearch = "";
                chatSearchOpen = false;
              });

    // «Спросить у заметок…» начинает новый чат. Черновик нового чата (текст и картинки)
    // сохраняется, пока его не отправят или не уберут вручную: файлы вложений нельзя осиротить.
    LinearLayout ask = row();
    ask.setGravity(Gravity.CENTER_VERTICAL);
    ask.setPadding(dp(16), 0, dp(7), 0);
    GradientDrawable askBackground = shape(PAPER, 12);
    askBackground.setStroke(dp(1), LINE);
    ask.setBackground(askBackground);
    ask.setForeground(ripple(android.graphics.Color.TRANSPARENT, 12));
    TextView askText = text("Спросить у заметок…", 15, MUTED);
    askText.setSingleLine(true);
    ask.addView(askText, new LinearLayout.LayoutParams(0, -2, 1));
    ImageView askGo = new ImageView(this);
    askGo.setScaleType(ImageView.ScaleType.CENTER);
    askGo.setImageDrawable(new Glyph("send", WHITE, 18));
    askGo.setBackground(shape(FILL, 10));
    askGo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    ask.addView(askGo, new LinearLayout.LayoutParams(dp(36), dp(36)));
    ask.setContentDescription("Новый чат: спросить у заметок");
    ask.setFocusable(true);
    ask.setOnClickListener(
        v -> {
          buzz(0);
          chat = 0;
          page = "chat";
          render();
        });
    content.addView(ask, new LinearLayout.LayoutParams(-1, dp(50)));
    gap(content, 14);

    List<ChatFeed.Row> rows = store.chatFeed();
    LinearLayout listing = column();
    long[] hidden = {0};
    String[] term = {chatSearch.trim().toLowerCase(Locale.ROOT)};
    Runnable refresh =
        () -> {
          listing.removeAllViews();
          long now = System.currentTimeMillis();
          ZoneId zone = ZoneId.systemDefault();
          String group = null;
          boolean any = false;
          for (ChatFeed.Row r : rows) {
            if (!term[0].isEmpty() && !r.title.toLowerCase(Locale.ROOT).contains(term[0])) continue;
            if (!ChatFeed.matches(chatFilter, r) || (helperOpen && r.id == hidden[0])) continue;
            String g = ChatFeed.group(r.time, now, zone);
            if (!g.equals(group)) {
              TextView label = text(g, 12, MUTED);
              label.setTypeface(null, Typeface.BOLD);
              label.setPadding(0, dp(any ? 18 : 6), 0, dp(4));
              if (Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
              listing.addView(label);
              group = g;
            } else {
              View rule = new View(this);
              rule.setBackgroundColor(LINE);
              LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(-1, dp(1));
              ruleParams.leftMargin = dp(52);
              listing.addView(rule, ruleParams);
            }
            listing.addView(chatFeedRow(r, now, zone));
            any = true;
          }
          if (!any) {
            gap(listing, 12);
            secondary(
                listing,
                !term[0].isEmpty()
                    ? "Чатов с такими словами нет."
                    : rows.isEmpty()
                        ? "Первый разговор ещё впереди.\nОтветы будут ссылаться на ваши заметки."
                        : "Здесь пока пусто.");
          }
        };
    content.addView(chatFilters(rows, hidden, refresh));
    gap(content, 4);
    content.addView(listing);
    refresh.run();
    if (find != null) {
      EditText field = find;
      watch(
          field,
          () -> {
            chatSearch = field.getText().toString();
            term[0] = chatSearch.trim().toLowerCase(Locale.ROOT);
            refresh.run();
          });
    }
  }

  /**
   * Строка поиска под заголовком страницы (ленты чатов и заметок): поле с рамкой и «Закрыть».
   * {@code reset} сбрасывает запрос и признак «поиск открыт»; «Назад» тоже закрывает поиск.
   */
  private EditText searchRow(String hint, String value, boolean focus, Runnable reset) {
    LinearLayout box = row();
    box.setPadding(dp(14), 0, dp(6), 0);
    GradientDrawable boxBackground = shape(SURFACE, 12);
    boxBackground.setStroke(dp(1), LINE);
    box.setBackground(boxBackground);
    ImageView glass = new ImageView(this);
    glass.setImageDrawable(new Glyph("search", MUTED, 20));
    box.addView(glass, new LinearLayout.LayoutParams(dp(20), dp(20)));
    EditText field = new EditText(this);
    field.setBackground(null);
    field.setHint(hint);
    field.setContentDescription(hint);
    field.setHintTextColor(MUTED);
    field.setTextColor(INK);
    field.setTextSize(15);
    field.setSingleLine(true);
    field.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
    field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
    field.setPadding(dp(8), 0, dp(8), 0);
    field.setText(value);
    field.setOnEditorActionListener(
        (v, action, event) -> {
          hideKeyboard();
          return true;
        });
    box.addView(field, new LinearLayout.LayoutParams(0, -1, 1));
    ImageView clear = new ImageView(this);
    clear.setScaleType(ImageView.ScaleType.CENTER);
    clear.setImageDrawable(new Glyph("close", GREEN, 18));
    clear.setBackground(ripple(LIME, 10));
    clear.setContentDescription("Закрыть поиск");
    Runnable close =
        () -> {
          reset.run();
          hideKeyboard();
          render();
        };
    clear.setOnClickListener(
        v -> {
          buzz(0);
          close.run();
        });
    box.addView(clear, new LinearLayout.LayoutParams(dp(32), dp(32)));
    content.addView(box, new LinearLayout.LayoutParams(-1, dp(44)));
    gap(content, 10);
    searchOpen = true;
    closeSearch = close;
    if (focus)
      field.post(
          () -> {
            field.requestFocus();
            getSystemService(InputMethodManager.class).showSoftInput(field, 0);
          });
    return field;
  }

  /** Строка ленты: значок вида чата, название, строка последнего сообщения и время. */
  private View chatFeedRow(ChatFeed.Row r, long now, ZoneId zone) {
    boolean agent = ChatFeed.byAgent(r), note = ChatFeed.byNote(r), waiting = ChatFeed.awaits(r);
    LinearLayout item = row();
    item.setGravity(Gravity.TOP);
    item.setPadding(0, dp(12), 0, dp(12));
    item.setBackground(ripple(android.graphics.Color.TRANSPARENT, 10));
    ImageView icon = new ImageView(this);
    icon.setScaleType(ImageView.ScaleType.CENTER);
    GradientDrawable tile = shape(agent ? STACK_BG : note ? PAPER : LIME, 12);
    if (note) tile.setStroke(dp(1), LINE);
    icon.setBackground(tile);
    icon.setImageDrawable(
        new Glyph(agent ? "spark" : note ? "notes" : "chat", agent ? STACK_FG : GREEN, 20));
    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    item.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
    LinearLayout words = column();
    LinearLayout top = row();
    TextView title = text(r.title, 15, INK);
    title.setTypeface(null, Typeface.BOLD);
    title.setSingleLine(true);
    title.setEllipsize(TextUtils.TruncateAt.END);
    top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    TextView when = text(ChatFeed.time(r.time, now, zone), 12, waiting ? STACK_FG : MUTED);
    if (waiting) when.setTypeface(null, Typeface.BOLD);
    LinearLayout.LayoutParams whenParams = new LinearLayout.LayoutParams(-2, -2);
    whenParams.leftMargin = dp(8);
    top.addView(when, whenParams);
    words.addView(top);
    String said = ChatFeed.preview(r.body);
    String line =
        waiting
            ? "Помощник ждёт ответа"
            : said.isEmpty() ? "" : ("user".equals(r.role) ? "Вы: " : "") + said;
    if (!line.isEmpty()) {
      gap(words, 3);
      TextView preview = text(line, 13, waiting ? STACK_FG : MUTED);
      preview.setSingleLine(true);
      preview.setEllipsize(TextUtils.TruncateAt.END);
      words.addView(preview);
    }
    LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, -2, 1);
    wordsParams.leftMargin = dp(12);
    item.addView(words, wordsParams);
    item.setContentDescription(
        (agent ? "Чат помощника: " : note ? "Чат по заметке: " : "Чат: ")
            + r.title
            + (line.isEmpty() ? "" : ". " + line)
            + ". "
            + ChatFeed.time(r.time, now, zone));
    item.setFocusable(true);
    item.setOnClickListener(
        v -> {
          buzz(0);
          chat = r.id;
          page = "chat";
          render();
        });
    item.setOnLongClickListener(
        v -> {
          buzz(0);
          showChatMenu(item, r.id);
          return true;
        });
    return item;
  }

  /**
   * Фильтры ленты и «Помощник». Нажатие на «Помощник» разворачивает его в карточку от края до
   * края поверх остальных фильтров (вопрос помощника с ответами кнопками), повторное — сворачивает.
   */
  private View chatFilters(List<ChatFeed.Row> rows, long[] hidden, Runnable refresh) {
    List<ChatFeed.Row> waiting = new ArrayList<>(), agent = new ArrayList<>();
    for (ChatFeed.Row r : rows) {
      if (ChatFeed.byAgent(r)) agent.add(r);
      if (ChatFeed.awaits(r)) waiting.add(r);
    }
    FrameLayout box = new FrameLayout(this);
    LinearLayout chips = row();
    String[] names = {"Все", "Мои", "По заметкам"};
    int[] filters = {ChatFeed.ALL, ChatFeed.MINE, ChatFeed.NOTES};
    String closedText = waiting.isEmpty() ? "Помощник" : "Помощник · " + waiting.size();
    TextView placeholder = null;
    for (int i = 0; i < names.length; i++) {
      if (i == 2 && !agent.isEmpty()) {
        // Место под «Помощником»: невидимая копия подписи той же ширины, что и свёрнутая карточка.
        placeholder = text(closedText, 13, STACK_FG);
        placeholder.setTypeface(null, Typeface.BOLD);
        placeholder.setSingleLine(true);
        placeholder.setGravity(Gravity.CENTER_VERTICAL);
        placeholder.setPadding(dp(12), 0, dp(32), 0);
        placeholder.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(36));
        p.leftMargin = dp(6);
        chips.addView(placeholder, p);
      }
      int filter = filters[i];
      boolean active = chatFilter == filter;
      TextView chip = text(names[i], 13, active ? WHITE : GREEN);
      chip.setTypeface(null, Typeface.BOLD);
      chip.setSingleLine(true);
      chip.setGravity(Gravity.CENTER);
      chip.setPadding(dp(12), 0, dp(12), 0);
      chip.setBackground(ripple(active ? FILL : LIME, 10));
      chip.setSelected(active);
      chip.setFocusable(true);
      chip.setContentDescription("Показать: " + names[i] + (active ? ", выбрано" : ""));
      chip.setOnClickListener(
          v -> {
            buzz(0);
            chatFilter = filter;
            render();
          });
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(36));
      if (i > 0) p.leftMargin = dp(6);
      chips.addView(chip, p);
    }
    box.addView(chips, new FrameLayout.LayoutParams(-2, dp(36)));
    LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(-1, dp(36));
    if (placeholder == null) {
      box.setLayoutParams(boxParams);
      return box;
    }

    // Карточка помощника: строка-заголовок (она же свёрнутая кнопка) и содержимое.
    ChatFeed.Row shown = helperReplyChat == 0 && !waiting.isEmpty() ? waiting.get(0) : null;
    hidden[0] = helperReplyChat != 0 ? helperReplyChat : shown == null ? 0 : shown.id;
    LinearLayout card = column();
    GradientDrawable cardBackground = shape(STACK_BG, 10);
    card.setBackground(cardBackground);
    LinearLayout header = row();
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(12), 0, dp(10), 0);
    header.setBackground(ripple(android.graphics.Color.TRANSPARENT, 10));
    TextView label = text(closedText, 13, STACK_FG);
    label.setTypeface(null, Typeface.BOLD);
    label.setSingleLine(true);
    label.setEllipsize(TextUtils.TruncateAt.END);
    header.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    long now = System.currentTimeMillis();
    TextView meta =
        text(shown == null ? "" : ChatFeed.time(shown.time, now, ZoneId.systemDefault()), 12, STACK_FG);
    LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-2, -2);
    metaParams.leftMargin = dp(6);
    header.addView(meta, metaParams);
    ImageView chevron = new ImageView(this);
    chevron.setScaleType(ImageView.ScaleType.CENTER);
    chevron.setImageDrawable(new Glyph("down", STACK_FG, 16));
    LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(dp(16), dp(16));
    chevronParams.leftMargin = dp(6);
    header.addView(chevron, chevronParams);
    card.addView(header, new LinearLayout.LayoutParams(-1, dp(36)));

    LinearLayout body = column();
    body.setPadding(dp(14), 0, dp(14), dp(14));
    int deep = dark ? INK : 0xFF3E2352;
    if (helperReplyChat != 0) {
      TextView reply = text(helperReply, 14, deep);
      reply.setLineSpacing(dp(2), 1);
      body.addView(reply);
      // Действия по задаче («Выполнено», «В корзину», перенос напоминания) — те же, что в чате,
      // чтобы дело можно было завершить прямо с ленты.
      try {
        JSONArray history = store.messages(helperReplyChat);
        if (history.length() > 0) {
          JSONObject last = history.getJSONObject(history.length() - 1);
          String draftJson = last.optString("draft", "");
          if ("assistant".equals(last.optString("role")) && !draftJson.isEmpty()) {
            JSONObject draft = new JSONObject(draftJson);
            String kind = draft.optString("kind");
            if (kind.equals("progress_trash") || kind.equals("progress_later"))
              progressDraftView(body, last.getLong("id"), draft);
          }
        }
      } catch (Exception ignored) {
      }
      gap(body, 10);
      LinearLayout actions = row();
      actions.addView(helperPill("Открыть чат", false, () -> openChatFromHelper(helperReplyChat)));
      body.addView(actions);
    } else if (shown != null) {
      PlanChat.Split asked = PlanChat.split(ChatMemo.split(ChatImages.split(shown.body).text).text);
      TextView question = text(asked.text.isEmpty() ? shown.title : asked.text, 15, deep);
      question.setTypeface(null, Typeface.BOLD);
      question.setLineSpacing(dp(2), 1);
      body.addView(question);
      gap(body, 10);
      LinearLayout actions = row();
      if (ChatFeed.progress(shown)) {
        String[] answers = {ProgressCheck.DONE, ProgressCheck.ACTIVE, ProgressCheck.LATER};
        for (int i = 0; i < answers.length; i++) {
          String answer = answers[i];
          TextView pill = helperPill(answer, i == 0, () -> answerFromHelper(shown.id, answer));
          LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(36));
          if (i > 0) p.leftMargin = dp(8);
          actions.addView(pill, p);
        }
        body.addView(actions);
        gap(body, 6);
        LinearLayout more = row();
        more.addView(
            helperPill(
                ProgressCheck.DROP, null, () -> answerFromHelper(shown.id, ProgressCheck.DROP)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(36));
        p.leftMargin = dp(8);
        more.addView(helperPill("Открыть чат", null, () -> openChatFromHelper(shown.id)), p);
        body.addView(more);
      } else {
        actions.addView(helperPill("Ответить", true, () -> openChatFromHelper(shown.id)));
        body.addView(actions);
      }
    } else {
      body.addView(text("Новых вопросов нет.", 13, deep));
    }
    // Следующий вопрос или последний чат помощника — строкой внизу карточки.
    ChatFeed.Row next = null;
    for (ChatFeed.Row r : waiting) if (r.id != hidden[0]) { next = r; break; }
    if (next == null) for (ChatFeed.Row r : agent) if (r.id != hidden[0]) { next = r; break; }
    if (next != null) {
      View rule = new View(this);
      rule.setBackgroundColor(STACK_LINE);
      LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(-1, 1);
      ruleParams.topMargin = dp(12);
      body.addView(rule, ruleParams);
      int more = 0;
      for (ChatFeed.Row r : waiting) if (r.id != hidden[0]) more++;
      ChatFeed.Row target = next;
      TextView link =
          text(
              (more > 1 ? "Ещё вопросов: " + more + " · «" : "Ещё от помощника: «")
                  + target.title
                  + "» · "
                  + ChatFeed.time(target.time, now, ZoneId.systemDefault()),
              12,
              STACK_FG);
      link.setSingleLine(true);
      link.setEllipsize(TextUtils.TruncateAt.END);
      link.setMinHeight(dp(36));
      link.setGravity(Gravity.CENTER_VERTICAL);
      link.setBackground(ripple(android.graphics.Color.TRANSPARENT, 8));
      link.setFocusable(true);
      link.setOnClickListener(
          v -> {
            buzz(0);
            openChatFromHelper(target.id);
          });
      body.addView(link, new LinearLayout.LayoutParams(-1, -2));
    }
    card.addView(body, new LinearLayout.LayoutParams(-1, -2));
    FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(0, dp(36));
    card.setVisibility(View.INVISIBLE);
    box.addView(card, cardParams);
    box.setLayoutParams(boxParams);

    String openText = shown == null && helperReplyChat == 0 ? "Помощник" : "Помощник спрашивает";
    HelperMotion motion =
        new HelperMotion(box, chips, placeholder, card, body, header, label, meta, chevron,
            cardBackground, closedText, openText);
    header.setFocusable(true);
    header.setOnClickListener(
        v -> {
          buzz(0);
          helperOpen = !helperOpen;
          if (!helperOpen && helperReplyChat != 0) {
            helperReplyChat = 0;
            helperReply = "";
          }
          motion.set(helperOpen, true);
          refresh.run();
        });
    // Мерить можно только после первой разметки: до неё ширина нулевая, и карточка осталась бы
    // невидимой. Меряем один раз, когда у строки фильтров появилась ширина.
    box.addOnLayoutChangeListener(
        new View.OnLayoutChangeListener() {
          @Override
          public void onLayoutChange(
              View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            if (motion.full != 0 || v.getWidth() == 0) return;
            v.removeOnLayoutChangeListener(this);
            v.post(motion::place);
          }
        });
    return box;
  }

  private TextView helperPill(String label, Boolean primary, Runnable click) {
    TextView pill = text(label, 13, Boolean.TRUE.equals(primary) ? SURFACE : STACK_FG);
    pill.setTypeface(null, Typeface.BOLD);
    pill.setSingleLine(true);
    pill.setGravity(Gravity.CENTER);
    pill.setMinHeight(dp(36));
    pill.setPadding(dp(primary == null ? 4 : 14), 0, dp(primary == null ? 4 : 14), 0);
    int fill =
        primary == null
            ? android.graphics.Color.TRANSPARENT
            : primary ? STACK_FG : SURFACE;
    pill.setBackground(ripple(fill, 10));
    pill.setFocusable(true);
    pill.setOnClickListener(
        v -> {
          buzz(0);
          click.run();
        });
    return pill;
  }

  private void openChatFromHelper(long id) {
    helperReplyChat = 0;
    helperReply = "";
    chat = id;
    page = "chat";
    render();
  }

  /** Ответ кнопкой из блока помощника: тот же путь, что и в чате, ответ помощника — в карточке. */
  private void answerFromHelper(long id, String answer) {
    String reply = answerProgressIn(id, answer);
    if (reply == null) {
      toast("На этот вопрос уже ответили");
      render();
      return;
    }
    helperReplyChat = id;
    helperReply = reply;
    render();
  }

  /**
   * Разворот «Помощника»: от места кнопки среди фильтров до всей ширины и высоты карточки за
   * 260 мс, быстро в начале и мягко к концу; фильтры гаснут, содержимое проявляется к концу.
   */
  private final class HelperMotion {
    final FrameLayout box;
    final View chips, placeholder, card, body, header, meta, chevron;
    final TextView label;
    final GradientDrawable background;
    final String closedText, openText;
    int full, left, width, openHeight;
    float fraction = -1;
    android.animation.ValueAnimator running;

    HelperMotion(
        FrameLayout box,
        View chips,
        View placeholder,
        View card,
        View body,
        View header,
        TextView label,
        View meta,
        View chevron,
        GradientDrawable background,
        String closedText,
        String openText) {
      this.box = box;
      this.chips = chips;
      this.placeholder = placeholder;
      this.card = card;
      this.body = body;
      this.header = header;
      this.label = label;
      this.meta = meta;
      this.chevron = chevron;
      this.background = background;
      this.closedText = closedText;
      this.openText = openText;
    }

    /** Меряет место после разметки: ширину страницы, кнопку и высоту раскрытой карточки. */
    void place() {
      full = box.getWidth();
      if (full == 0) return;
      left = placeholder.getLeft();
      width = placeholder.getWidth();
      ViewGroup.LayoutParams bodyParams = body.getLayoutParams();
      bodyParams.width = full;
      body.setLayoutParams(bodyParams);
      body.measure(
          View.MeasureSpec.makeMeasureSpec(full, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
      openHeight = dp(36) + body.getMeasuredHeight();
      apply(helperOpen ? 1 : 0);
      card.setVisibility(View.VISIBLE);
    }

    void apply(float f) {
      fraction = f;
      int closed = dp(36);
      FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
      lp.leftMargin = Math.round(left * (1 - f));
      lp.width = Math.round(width + (full - width) * f);
      lp.height = Math.round(closed + (openHeight - closed) * f);
      card.setLayoutParams(lp);
      ViewGroup.LayoutParams outer = box.getLayoutParams();
      outer.height = lp.height;
      box.setLayoutParams(outer);
      chips.setAlpha(Math.max(0f, 1f - f * 1.6f));
      chips.setVisibility(f >= 1f ? View.INVISIBLE : View.VISIBLE);
      float shown = Math.max(0f, Math.min(1f, (f - 0.4f) / 0.6f));
      body.setAlpha(shown);
      meta.setAlpha(shown);
      meta.setVisibility(f > 0.5f ? View.VISIBLE : View.GONE);
      label.setText(f > 0.5f ? openText : closedText);
      chevron.setRotation(180f * f);
      background.setCornerRadius(dp(10) + dp(2) * f);
      header.setContentDescription(
          (f > 0.5f ? openText + ", свернуть" : closedText + ", развернуть"));
    }

    void set(boolean open, boolean animated) {
      if (full == 0) return;
      if (running != null) running.cancel();
      float target = open ? 1 : 0;
      if (!animated || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
        apply(target);
        return;
      }
      running = android.animation.ValueAnimator.ofFloat(Math.max(0, fraction), target);
      running.setDuration(260);
      running.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f));
      running.addUpdateListener(a -> apply((float) a.getAnimatedValue()));
      running.start();
    }
  }

  private TextView chatMenu(long id) {
    TextView menu = button("⋮", false, () -> {});
    menu.setTextSize(20);
    menu.setPadding(0, 0, 0, 0);
    menu.setMinHeight(dp(36));
    menu.setMinWidth(dp(40));
    menu.setContentDescription("Меню чата");
    menu.setOnClickListener(v -> showChatMenu(menu, id));
    return menu;
  }

  /** Меню чата: заметка из диалога или удаление. В ленте чатов открывается долгим нажатием. */
  private void showChatMenu(View anchor, long id) {
          Pop popup = new Pop(anchor);
          popup.getMenu().add(0, 1, 0, "Создать заметку из диалога");
          popup.getMenu().add(0, 2, 1, "Удалить чат");
          popup.setOnMenuItemClickListener(
              item -> {
                if (ChatRunner.running == id) {
                  toast("Дождитесь завершения ответа");
                  return true;
                }
                if (item.getItemId() == 1) {
                  if (ChatRunner.running != 0) {
                    toast("Дождитесь завершения текущего ответа");
                    return true;
                  }
                  stash();
                  chat = id;
                  page = "chat";
                  tab = 2;
                  render();
                  chatInput.setText(R.string.create_dialog_note);
                  send();
                  return true;
                }
                new Ask(this)
                    .setTitle("Удалить этот чат?")
                    .setMessage("Заметки останутся на месте.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton(
                        "Удалить",
                        (d, w) -> {
                          Set<String> chatPictures = new HashSet<>();
                          for (String body : chatBodies(id))
                            chatPictures.addAll(ChatImages.split(body).images);
                          // Неотправленные вложения этого чата тоже освобождаем.
                          chatPictures.addAll(
                              ImageList.parse(prefs.getString("chatImage." + id, "")));
                          store.deleteChat(id);
                          prefs.edit().remove("chatDraft." + id).remove("chatImage." + id).apply();
                          for (String name : chatPictures)
                            io.execute(() -> ImageFiles.release(getApplicationContext(), name));
                          if (chat == id) {
                            chatInput = null;
                            chat = 0;
                          }
                          page = "chats";
                          render();
                        })
                    .show();
                return true;
              });
          popup.show();
  }

  private void chatPage() {
    LinearLayout actions = row();
    actions.addView(
        barIcon(
            "back",
            "К истории чатов",
            () -> {
              stash();
              page = "chats";
              render();
            }),
        new LinearLayout.LayoutParams(dp(36), dp(36)));
    String chatTitle = "Новый чат";
    if (chat > 0)
      for (String[] c : store.chats()) if (Long.parseLong(c[0]) == chat) chatTitle = c[1];
    TextView titleView = text(chatTitle, 14, INK);
    titleView.setTypeface(null, Typeface.BOLD);
    titleView.setSingleLine(true);
    titleView.setEllipsize(TextUtils.TruncateAt.END);
    titleView.setPadding(dp(4), 0, dp(4), 0);
    actions.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
    if (chat > 0) {
      actions.addView(chatMenu(chat));
    }
    content.addView(actions);
    long planNote = chat > 0 ? store.chatNote(chat) : 0;
    Note planOf = planNote > 0 ? store.note(planNote) : null;
    if (planOf != null) {
      // Чат по заметке: ссылка на неё под заголовком.
      TextView link = text("По заметке: " + planOf.title + "  ›", 12, GREEN);
      link.setSingleLine(true);
      link.setEllipsize(TextUtils.TruncateAt.END);
      link.setPadding(dp(8), dp(4), dp(8), dp(4));
      link.setBackground(ripple(LIME, 8));
      link.setFocusable(true);
      link.setOnClickListener(
          v -> {
            buzz(0);
            openNote(planNote);
          });
      content.addView(link, new LinearLayout.LayoutParams(-2, -2));
    }
    gap(content, 6);
    LinearLayout messages = column();
    ScrollView scroller = scroll(messages);
    content.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
    JSONArray history = chat == 0 ? new JSONArray() : store.messages(chat);
    if (history.length() == 0 && planOf != null) {
      gap(messages, 12);
      chatNote(
          messages,
          "Агент задаст несколько вопросов по задаче и соберёт план. Можно попросить переименовать"
              + " заметку, дописать в неё или напомнить о дате — изменения применяются кнопкой.");
      gap(messages, 6);
      messages.addView(
          compactButton(
              "Разобрать план",
              true,
              () -> {
                chatInput.setText(PlanChat.START);
                send();
              }));
    } else if (history.length() == 0) {
      gap(messages, 12);
      TextView title = text("Память, с которой\nможно поговорить.", 20, INK);
      title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
      messages.addView(title);
      gap(messages, 6);
      chatNote(messages, "Помощник найдёт подходящие заметки и даст ссылки на источники.");
      gap(messages, 12);
      for (String suggestion :
          new String[] {
            "Какие есть рецепты?", "Какой ближайший праздник?", "Что мне нужно сделать?"
          }) {
        messages.addView(
            compactButton(
                suggestion,
                false,
                () -> {
                  chatInput.setText(suggestion);
                  chatInput.requestFocus();
                }));
        gap(messages, 5);
      }
      if (!LocalLlm.enabled(this)) {
        messages.addView(compactButton("Сводка видео YouTube", false, this::askVideoLink));
        gap(messages, 5);
        chatNote(messages, "Или просто пришлите ссылку на видео в чат.");
      }
    }
    for (int i = 0; i < history.length(); i++)
      try {
        JSONObject m = history.getJSONObject(i);
        boolean user = m.getString("role").equals("user");
        // Факты «запомнил» и итог хранятся в тексте сообщения; текст показываем без служебных меток.
        ChatImages.Split pictures = ChatImages.split(m.getString("content"));
        ChatMemo.Split part = ChatMemo.split(pictures.text);
        // Варианты ответа агента в чате по заметке хранятся служебной строкой в конце текста.
        PlanChat.Split asked = user ? null : PlanChat.split(part.text);
        LinearLayout bubble = column();
        bubble.setPadding(dp(11), dp(7), dp(11), dp(8));
        bubble.setBackground(shape(user ? LIME : part.recap ? RECAP_BG : SURFACE, 10));
        TextView who =
            text(user ? "Вы" : part.recap ? "Итог" : "Помощник", 10, part.recap ? GREEN : MUTED);
        who.setTypeface(null, part.recap ? Typeface.BOLD : Typeface.NORMAL);
        bubble.addView(who);
        gap(bubble, 2);
        TextView body = text("", 14, INK);
        body.setLineSpacing(dp(2), 1);
        body.setTextIsSelectable(true);
        String said = asked == null ? part.text : asked.text;
        String shown = part.recap ? said.replaceFirst("^Итог\\.?\\s*", "") : said;
        // Ответы помощника выводим с разметкой, свои сообщения — как есть.
        body.setText(
            user
                ? linkedAnswer(shown, m.getJSONArray("sources"))
                : styledAnswer(shown, m.getJSONArray("sources")));
        body.setMovementMethod(LinkMovementMethod.getInstance());
        if (!pictures.images.isEmpty()) {
          bubble.addView(messageImages(pictures.images));
          if (!shown.isEmpty()) gap(bubble, 4);
        }
        if (!shown.isEmpty() || pictures.images.isEmpty()) bubble.addView(body);
        if (!part.facts.isEmpty()) {
          // То, что помощник запомнил из этого сообщения, — серым.
          gap(bubble, 4);
          for (String fact : part.facts) {
            TextView memory = text("✓ Запомнил: " + fact, 12, MUTED);
            memory.setLineSpacing(dp(1), 1);
            bubble.addView(memory);
          }
        }
        if (asked != null
            && !asked.options.isEmpty()
            && i == history.length() - 1
            && ChatRunner.running == 0) {
          gap(bubble, 6);
          for (String option : asked.options) {
            bubble.addView(
                compactButton(
                    option,
                    false,
                    () -> {
                      chatInput.setText(option);
                      send();
                    }));
            gap(bubble, 4);
          }
        }
        String draftJson = m.optString("draft", "");
        if (!user && !draftJson.isEmpty() && CommandDraft.isDraft(new JSONObject(draftJson))) {
          commandDraftView(bubble, m.getLong("id"), new JSONObject(draftJson));
        } else if (!user
            && !draftJson.isEmpty()
            && (new JSONObject(draftJson).has("plan") || new JSONObject(draftJson).has("edit"))) {
          noteDraftView(bubble, m.getLong("id"), new JSONObject(draftJson));
        } else if (!user && !draftJson.isEmpty() && new JSONObject(draftJson).has("kind")
            && new JSONObject(draftJson).optString("kind").startsWith("progress")) {
          // «Как продвигается?»: варианты — кнопками выше, карточки «В корзину» и «Перенести».
          progressDraftView(bubble, m.getLong("id"), new JSONObject(draftJson));
        } else if (!user && !draftJson.isEmpty() && ReminderBatch.isBatch(new JSONObject(draftJson))) {
          reminderBatchView(bubble, m.getLong("id"), new JSONObject(draftJson));
        } else if (!user && !draftJson.isEmpty()) {
          JSONObject draft = new JSONObject(draftJson);
          long message = m.getLong("id");
          gap(bubble, 6);
          chatNote(bubble, "Можно сохранить в заметку");
          TextView draftTitle = text(draft.getString("title"), 13, GREEN);
          draftTitle.setTypeface(null, Typeface.BOLD);
          bubble.addView(draftTitle);
          TextView excerpt = chatNote(bubble, Retrieval.excerpt(draft.getString("body"), "", 200));
          excerpt.setPadding(0, dp(2), 0, 0);
          gap(bubble, 6);
          LinearLayout draftActions = row();
          draftActions.addView(
              compactButton("Проверить и сохранить", false, () -> reviewChatDraft(message, draft)),
              new LinearLayout.LayoutParams(0, -2, 1));
          View spacer = new View(this);
          draftActions.addView(spacer, new LinearLayout.LayoutParams(dp(6), 1));
          draftActions.addView(
              compactButton(
                  "Не сохранять",
                  false,
                  () -> {
                    store.dismissDraft(message);
                    stash();
                    render();
                  }),
              new LinearLayout.LayoutParams(0, -2, 1));
          bubble.addView(draftActions);
        }
        JSONArray sources = m.getJSONArray("sources");
        if (sources.length() > 0) {
          gap(bubble, 6);
          for (int j = 0; j < sources.length(); j++) {
            JSONObject s = sources.getJSONObject(j);
            long id = s.getLong("id");
            LinearLayout source = row();
            source.setMinimumHeight(dp(34));
            source.setPadding(dp(8), dp(4), dp(8), dp(4));
            source.setBackground(ripple(LIME, 8));
            source.setGravity(Gravity.CENTER_VERTICAL);
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(new Glyph("notes", GREEN, 16));
            source.addView(icon, new LinearLayout.LayoutParams(dp(16), dp(16)));
            TextView title = text(s.getString("title"), 12, GREEN);
            title.setTypeface(null, Typeface.BOLD);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(dp(8), 0, dp(6), 0);
            source.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            source.addView(text("›", 18, GREEN));
            source.setFocusable(true);
            source.setContentDescription("Открыть заметку: " + s.getString("title"));
            source.setOnClickListener(v -> openNote(id));
            LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(-1, -2);
            sourceParams.topMargin = dp(3);
            bubble.addView(source, sourceParams);
          }
        }
        messages.addView(bubble);
        gap(messages, 5);
      } catch (JSONException ignored) {
      }
    if (ChatRunner.running == chat && chat != 0) {
      LinearLayout waiting = row();
      ProgressBar progress = new ProgressBar(this);
      waiting.addView(progress, new LinearLayout.LayoutParams(dp(18), dp(18)));
      String stage = LocalLlm.stage();
      TextView waitingText = chatNote(waiting, stage.isEmpty() ? "Подготавливаю ответ…" : stage);
      waitingText.setPadding(dp(8), 0, 0, 0);
      messages.addView(waiting);
      gap(messages, 6);
    }
    if (ChatRunner.errorChat == chat && !ChatRunner.error.isEmpty()) {
      TextView error = text(ChatRunner.error, 12, RED);
      messages.addView(error);
      gap(messages, 5);
      if (history.length() > 0) {
        String last = history.optJSONObject(history.length() - 1).optString("content");
        messages.addView(
            compactButton(
                "Повторить запрос",
                false,
                () -> {
                  chatInput.setText(ChatMemo.split(ChatImages.split(last).text).text);
                  send();
                }));
        gap(messages, 5);
      }
    }
    if (ChatRunner.usageChat == chat && !ChatRunner.usage.isEmpty() && history.length() > 0) {
      chatNote(messages, ChatRunner.usage);
      gap(messages, 5);
    }
    gap(content, 4);
    chatNote(
        content,
        LocalLlm.enabled(this)
            ? (LocalLlm.aicore(this)
                ? "Локально · Gemini Nano (Google AICore) отвечает на телефоне"
                : "Локально · GGUF отвечает на телефоне")
            : "ИИ получает выбранные фрагменты через Polza.AI. Поиск может найти не всё.");
    gap(content, 4);
    List<String> attachedList = ImageList.parse(prefs.getString("chatImage." + chat, ""));
    List<String> historyBodies = new ArrayList<>();
    for (int i = 0; i < history.length(); i++)
      historyBodies.add(history.optJSONObject(i).optString("content"));
    int remembered = ChatImages.all(historyBodies).size();
    if (!attachedList.isEmpty()) {
      content.addView(chatAttachStrip(attachedList, chat));
      gap(content, 4);
      chatNote(
          content,
          "Напишите вопрос — ИИ ответит по картинкам. Без текста или с просьбой «создай заметку»"
              + " ИИ сделает из них заметку. Картинки останутся в чате, присылать их заново не"
              + " нужно.");
    } else if (remembered > 0) {
      chatNote(
          content,
          LocalLlm.enabled(this)
              ? "В чате " + remembered + " картинок, но локальная модель их не видит."
              : "ИИ помнит картинки этого чата ("
                  + remembered
                  + (remembered > ChatImages.MAX_IN_REQUEST
                      ? ", видит последние " + ChatImages.MAX_IN_REQUEST
                      : "")
                  + "). Присылать их заново не нужно.");
    }
    gap(content, 4);
    // Одна строка ввода: изображение, поле и отправка.
    chatInput = input("Сообщение или просьба создать заметку…", true);
    chatInput.setTextSize(15);
    chatInput.setPadding(dp(12), dp(9), dp(12), dp(9));
    chatInput.setMinLines(1);
    chatInput.setMaxLines(4);
    chatInput.setFilters(new InputFilter[] {new InputFilter.LengthFilter(2000)});
    chatInput.setText(prefs.getString("chatDraft." + chat, ""));
    LinearLayout composer = row();
    composer.setGravity(Gravity.BOTTOM);
    ImageView attach = new ImageView(this);
    attach.setScaleType(ImageView.ScaleType.CENTER);
    attach.setImageDrawable(new Glyph("image", GREEN, 18));
    attach.setBackground(ripple(SURFACE, 10));
    attach.setContentDescription("Прикрепить изображение");
    attach.setFocusable(true);
    attach.setOnClickListener(
        v -> {
          buzz(0);
          pickImage(true);
        });
    composer.addView(attach, new LinearLayout.LayoutParams(dp(40), dp(40)));
    LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, -2, 1);
    inputParams.leftMargin = dp(6);
    inputParams.rightMargin = dp(6);
    composer.addView(chatInput, inputParams);
    ImageView sendButton = new ImageView(this);
    sendButton.setScaleType(ImageView.ScaleType.CENTER);
    sendButton.setImageDrawable(new Glyph("send", WHITE, 18));
    sendButton.setBackground(ripple(FILL, 10));
    sendButton.setContentDescription(ChatRunner.running == 0 ? "Отправить" : "Ожидаем ответ");
    sendButton.setFocusable(true);
    sendButton.setEnabled(ChatRunner.running == 0);
    sendButton.setAlpha(ChatRunner.running == 0 ? 1 : .4f);
    sendButton.setOnClickListener(
        v -> {
          buzz(0);
          send();
        });
    composer.addView(sendButton, new LinearLayout.LayoutParams(dp(40), dp(40)));
    content.addView(composer);
    if (LocalLlm.enabled(this) && ChatRunner.running != 0) {
      gap(content, 4);
      content.addView(
          compactButton(
              "Остановить генерацию",
              false,
              () -> {
                LocalLlm.cancel();
                toast("Останавливаю локальную модель…");
              }));
    }
    watch(chatInput, this::stash);
    scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
  }

  /**
   * Предложение ИИ: по напоминанию на каждую дату из ответа. Строку можно исключить нажатием (так
   * убирают опечатку в дате); создаётся отдельная заметка с напоминанием на каждую оставшуюся.
   */
  private void reminderBatchView(LinearLayout bubble, long message, JSONObject draft) {
    List<ReminderBatch.Item> items = ReminderBatch.items(draft);
    java.time.ZoneId zone = java.time.ZoneId.systemDefault();
    Set<Integer> skip = new HashSet<>();
    // Куда лягут даты: в заметку-источник запроса, одной новой заметкой или отдельными заметками.
    Note target = ReminderBatch.source(draft) > 0 ? store.note(ReminderBatch.source(draft)) : null;
    boolean into = target != null && target.trashed == 0 && ReminderBatch.canMerge(target.repeat);
    boolean separate = ReminderBatch.separate(draft);
    int primaryMode = into ? Store.BATCH_INTO : separate ? Store.BATCH_SEPARATE : Store.BATCH_ONE;
    int otherMode = primaryMode == Store.BATCH_SEPARATE ? Store.BATCH_ONE : Store.BATCH_SEPARATE;
    gap(bubble, 6);
    chatNote(
        bubble,
        (into
                ? "Даты добавятся в заметку «" + target.title + "»"
                : separate
                    ? "Похоже, это разные дела: по заметке на дату"
                    : "Все даты — в одной заметке, напоминание придёт к каждой")
            + ". Нажмите на строку, чтобы исключить её");
    gap(bubble, 4);
    int[] chosenMode = {primaryMode};
    TextView create = compactButton("", true, () -> {});
    TextView other = compactButton("", false, () -> {});
    Runnable refresh =
        () -> {
          int n = items.size() - skip.size();
          create.setText(
              n == 0
                  ? "Ничего не выбрано"
                  : primaryMode == Store.BATCH_INTO
                      ? "Добавить в заметку: " + ReminderPolicy.dateCount(n)
                      : primaryMode == Store.BATCH_ONE && n > 1
                          ? "Одна заметка: " + ReminderPolicy.dateCount(n)
                          : "Создать: " + n);
          create.setAlpha(n == 0 ? 0.5f : 1f);
          other.setText(otherMode == Store.BATCH_ONE ? "Одной заметкой" : "Отдельными заметками");
          other.setVisibility(n > 1 ? View.VISIBLE : View.GONE);
        };
    for (int i = 0; i < items.size(); i++) {
      ReminderBatch.Item item = items.get(i);
      final int index = i;
      TextView line =
          text(ReminderBatch.label(item.at, zone) + " · " + item.title, 12, INK);
      line.setPadding(dp(8), dp(6), dp(8), dp(6));
      line.setMinHeight(dp(34));
      line.setGravity(Gravity.CENTER_VERTICAL);
      line.setBackground(ripple(LIME, 8));
      line.setContentDescription(ReminderBatch.label(item.at, zone) + ", " + item.title);
      line.setOnClickListener(
          v -> {
            buzz(0);
            boolean off = skip.add(index);
            if (!off) skip.remove(index);
            line.setTextColor(off ? MUTED : INK);
            line.setPaintFlags(
                off
                    ? line.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                    : line.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            refresh.run();
          });
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.bottomMargin = dp(2);
      bubble.addView(line, lp);
    }
    if (draft.optInt("skipped") > 0)
      chatNote(
          bubble,
          "Пропущено строк: "
              + draft.optInt("skipped")
              + " (время уже прошло, повтор или неразборчиво)");
    gap(bubble, 6);
    Runnable accept =
        () -> {
          if (skip.size() >= items.size()) return;
          buzz(1);
          int mode = chosenMode[0];
          try {
            Note before = mode == Store.BATCH_INTO ? store.note(target.id) : null;
            List<Note> made = store.acceptReminders(message, skip, mode);
            for (Note n : made)
              if (before != null && n.id == before.id)
                Reminders.scheduleEdited(getApplicationContext(), before, n);
              else Reminders.schedule(getApplicationContext(), n);
            CalendarSync.runAsync(getApplicationContext());
            BackgroundAnalysis.enqueue(this);
            stash();
            render();
            toast(
                mode == Store.BATCH_INTO
                    ? "Даты добавлены в заметку"
                    : made.size() == 1
                        ? "Создана заметка с напоминаниями"
                        : "Создано напоминаний: " + made.size());
            if (!Reminders.notificationsAllowed(this))
              toast("Уведомления выключены — напоминания не придут");
          } catch (Exception e) {
            toast("Не удалось создать напоминания или предложение уже обработано");
          }
        };
    create.setOnClickListener(
        v -> {
          chosenMode[0] = primaryMode;
          accept.run();
        });
    other.setOnClickListener(
        v -> {
          chosenMode[0] = otherMode;
          accept.run();
        });
    refresh.run();
    bubble.addView(create, new LinearLayout.LayoutParams(-1, -2));
    gap(bubble, 6);
    LinearLayout actions = row();
    actions.addView(other, new LinearLayout.LayoutParams(0, -2, 1));
    actions.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
    actions.addView(
        compactButton(
            "Не создавать",
            false,
            () -> {
              store.dismissDraft(message);
              stash();
              render();
            }),
        new LinearLayout.LayoutParams(0, -2, 1));
    bubble.addView(actions);
  }

  /** Карточка команды и выбор заметок восстанавливаются из messages.draft после render(). */
  private void commandDraftView(LinearLayout bubble, long message, JSONObject json) {
    CommandDraft draft;
    try {
      draft = CommandDraft.fromJson(json);
    } catch (JSONException e) {
      chatNote(bubble, "Черновик команды повреждён. Отправьте команду ещё раз.");
      return;
    }
    Set<Long> selectable = draft.selectable();
    gap(bubble, 8);
    TextView heading = text("Предпросмотр команды", 14, GREEN);
    heading.setTypeface(null, Typeface.BOLD);
    bubble.addView(heading);
    gap(bubble, 4);
    LinearLayout previewList = column();
    bubble.addView(previewList);
    TextView selectionSummary = null;
    LinearLayout choices = null;
    if (!selectable.isEmpty()) {
      gap(bubble, 6);
      chatNote(bubble, "Снимите галочки с заметок, которые нужно оставить без изменений.");
      selectionSummary = text("", 13, GREEN);
      selectionSummary.setTypeface(null, Typeface.BOLD);
      selectionSummary.setPadding(0, dp(4), 0, dp(4));
      bubble.addView(selectionSummary);
      choices = column();
      bubble.addView(choices);
    }
    if (selectable.size() < draft.notes.size()) {
      gap(bubble, 5);
      chatNote(bubble, "Источники новой заметки:");
      for (CommandDraft.NoteState note : draft.notes.values()) {
        if (selectable.contains(note.id)) continue;
        TextView name = text(note.title, 13, GREEN);
        name.setTypeface(null, Typeface.BOLD);
        name.setPadding(dp(8), dp(5), dp(8), dp(5));
        name.setMinHeight(dp(40));
        name.setGravity(Gravity.CENTER_VERTICAL);
        name.setBackground(ripple(LIME, 8));
        name.setContentDescription("Открыть заметку: " + note.title);
        name.setOnClickListener(v -> openNote(note.id));
        bubble.addView(name);
      }
    }
    if (draft.rejected > 0)
      chatNote(bubble, "Отклонено небезопасных или неподдерживаемых действий: "
          + draft.rejected + ". Применятся только перечисленные выше.");
    chatNote(bubble, "Применятся только перечисленные выше изменения. После этого их можно"
        + " отменить одной кнопкой в течение суток — здесь или в журнале колокольчика.");
    gap(bubble, 8);
    LinearLayout actions = row();
    TextView apply = compactButton("Применить", true, () -> {
      buzz(1);
      try {
        CommandExecutor.Outcome outcome = CommandExecutor.apply(store, message);
        // Системные будильники и календарь меняются только после успешного commit.
        for (CommandExecutor.Change change : outcome.reminders)
          Reminders.scheduleEdited(getApplicationContext(), change.before, change.after);
        CalendarSync.runAsync(getApplicationContext());
        stash();
        render();
        undoBar("Команда применена");
      } catch (IllegalStateException e) {
        // Конфликт не закрывает черновик: пользователь видит, что именно не применилось.
        toast(e.getMessage() == null ? "Команда не применена" : e.getMessage());
      } catch (Exception e) {
        toast("Не удалось применить команду. Никакие изменения не сохранены.");
      }
    });
    actions.addView(apply, new LinearLayout.LayoutParams(0, -2, 1));
    actions.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
    actions.addView(compactButton("Отмена", false, () -> {
      store.dismissDraft(message);
      stash();
      render();
    }), new LinearLayout.LayoutParams(0, -2, 1));
    bubble.addView(actions);

    TextView summary = selectionSummary;
    Runnable refresh = () -> {
      previewList.removeAllViews();
      List<String> lines = draft.preview();
      if (lines.isEmpty()) chatNote(previewList, "Нет выбранных изменений.");
      for (String line : lines) {
        TextView item = text("• " + line, 13, INK);
        item.setLineSpacing(dp(2), 1);
        item.setPadding(0, dp(3), 0, dp(3));
        previewList.addView(item);
      }
      if (summary != null)
        summary.setText("Выбрано: " + draft.selected.size() + " из " + selectable.size());
      apply.setEnabled(draft.hasSelectedChanges());
      apply.setAlpha(draft.hasSelectedChanges() ? 1f : .45f);
    };
    if (choices != null) {
      for (CommandDraft.NoteState note : draft.notes.values()) {
        if (!selectable.contains(note.id)) continue;
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox box = new CheckBox(this);
        box.setText(note.title);
        box.setTextSize(13);
        box.setTextColor(INK);
        box.setMinHeight(dp(48));
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setButtonTintList(android.content.res.ColorStateList.valueOf(GREEN));
        box.setChecked(draft.isSelected(note.id));
        line.addView(box, new LinearLayout.LayoutParams(0, -2, 1));
        TextView open = text("Открыть", 12, GREEN);
        open.setTypeface(null, Typeface.BOLD);
        open.setGravity(Gravity.CENTER);
        open.setMinWidth(dp(64));
        open.setMinHeight(dp(44));
        open.setContentDescription("Открыть заметку: " + note.title);
        open.setOnClickListener(v -> openNote(note.id));
        line.addView(open);
        choices.addView(line);
        box.setOnCheckedChangeListener((v, checked) -> {
          draft.setSelected(note.id, checked);
          try {
            store.updateDraft(message, draft.toJson());
            refresh.run();
          } catch (Exception e) {
            toast(e.getMessage() == null ? "Не удалось сохранить выбор" : e.getMessage());
            render();
          }
        });
      }
    }
    refresh.run();
  }

  private void reviewChatDraft(long message, JSONObject draft) {
    LinearLayout form = column();
    pad(form, 20);
    EditText title = input("Название", false), body = input("Текст заметки", true);
    title.setText(draft.optString("title"));
    body.setText(draft.optString("body"));
    body.setMinLines(5);
    body.setMaxLines(12);
    title.setFilters(new InputFilter[] {new InputFilter.LengthFilter(160)});
    body.setFilters(new InputFilter[] {new InputFilter.LengthFilter(100000)});
    form.addView(title);
    gap(form, 10);
    form.addView(body);
    Popup dialog =
        new Ask(this)
            .setTitle("Заметка из диалога")
            .setView(scroll(form))
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      if (body.getText().toString().trim().isEmpty()) {
                        body.setError("Добавьте текст");
                        return;
                      }
                      try {
                        long id =
                            store.acceptDraft(
                                message, title.getText().toString(), body.getText().toString());
                        BackgroundAnalysis.enqueue(this);
                        dialog.dismiss();
                        stash();
                        openNote(id);
                        celebrateNote(id);
                      } catch (Exception e) {
                        toast("Не удалось сохранить заметку или предложение уже обработано");
                      }
                    }));
    dialog.show();
  }

  private CharSequence linkedAnswer(String raw, JSONArray sources) {
    SpannableStringBuilder out = new SpannableStringBuilder(raw);
    applyNoteLinks(out, sources);
    return out;
  }

  /**
   * Ответ помощника: сначала лёгкая разметка ({@link Markup}), поверх — ссылки на заметки
   * [[note:N]] из списка источников.
   */
  private CharSequence styledAnswer(String raw, JSONArray sources) {
    Markup.Result parsed = Markup.parse(raw);
    SpannableStringBuilder out = new SpannableStringBuilder(parsed.text);
    for (Markup.Span s : parsed.spans) {
      int f = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE;
      if ((s.flags & Markup.BOLD) != 0)
        out.setSpan(new StyleSpan(Typeface.BOLD), s.start, s.end, f);
      if ((s.flags & Markup.ITALIC) != 0)
        out.setSpan(new StyleSpan(Typeface.ITALIC), s.start, s.end, f);
      if ((s.flags & Markup.STRIKE) != 0)
        out.setSpan(new StrikethroughSpan(), s.start, s.end, f);
      if ((s.flags & Markup.HEADING) != 0)
        out.setSpan(new RelativeSizeSpan(1.15f), s.start, s.end, f);
      if ((s.flags & Markup.CODE) != 0) {
        out.setSpan(new TypefaceSpan("monospace"), s.start, s.end, f);
        out.setSpan(new BackgroundColorSpan(LINE), s.start, s.end, f);
      }
      if ((s.flags & Markup.QUOTE) != 0) out.setSpan(new QuoteSpan(GREEN), s.start, s.end, f);
      if ((s.flags & Markup.LINK) != 0)
        out.setSpan(
            new URLSpan(s.url) {
              @Override
              public void updateDrawState(TextPaint paint) {
                paint.setColor(GREEN);
                paint.setUnderlineText(true);
              }
            },
            s.start,
            s.end,
            f);
    }
    applyNoteLinks(out, sources);
    return out;
  }

  /** Заменяет в собранном тексте токены [[note:N]] на кликабельные «Названия» заметок. */
  private void applyNoteLinks(SpannableStringBuilder out, JSONArray sources) {
    Set<Long> allowed = new HashSet<>();
    for (int i = 0; i < sources.length(); i++) allowed.add(sources.optJSONObject(i).optLong("id"));
    Matcher m = Pattern.compile("\\[\\[note:(\\d+)]]").matcher(out);
    int shift = 0;
    while (m.find()) {
      int from = m.start() + shift, to = m.end() + shift;
      long id;
      try {
        id = Long.parseLong(m.group(1));
      } catch (NumberFormatException e) {
        out.replace(from, to, ""); // повреждённый токен убираем, как раньше
        shift -= to - from;
        continue;
      }
      if (!allowed.contains(id)) {
        out.replace(from, to, ""); // ссылки на чужие источники не показываем
        shift -= to - from;
        continue;
      }
      String title = "Заметка";
      for (int i = 0; i < sources.length(); i++) {
        JSONObject source = sources.optJSONObject(i);
        if (source.optLong("id") == id) {
          title = source.optString("title", "Заметка");
          break;
        }
      }
      if (title.length() > 44) title = title.substring(0, 44) + "…";
      String label = "«" + title + "»";
      out.replace(from, to, label);
      shift += label.length() - (to - from);
      out.setSpan(
          new ClickableSpan() {
            @Override
            public void updateDrawState(TextPaint paint) {
              paint.setColor(GREEN);
              paint.setUnderlineText(false);
              paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            }

            public void onClick(View v) {
              openNote(id);
            }
          },
          from,
          from + label.length(),
          Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
  }

  private boolean sendPlain;

  /**
   * Ответ кнопкой на «Как продвигается?» обрабатывается без модели: ответ записывается в чат,
   * помощник отвечает сам и при «Сделано» / «Не актуально» предлагает корзину, при «Отложить» —
   * перенести напоминание. Свой ответ словами уходит модели как обычно. Возвращает true, если
   * ответ обработан здесь.
   */
  private boolean answerProgress(String query) {
    if (chat == 0) return false;
    String reply = answerProgressIn(chat, query);
    if (reply == null) return false;
    chatInput.setText("");
    stash();
    render();
    return true;
  }

  /**
   * Записывает ответ кнопкой на «Как продвигается?» в чат {@code chatId} и ответ помощника.
   * Возвращает текст ответа помощника или null, если последнее сообщение — не такой вопрос.
   * Вызывается и из чата, и из блока помощника на странице «Чаты».
   */
  private String answerProgressIn(long chatId, String query) {
    String answer = ProgressCheck.option(query);
    if (chatId == 0 || answer.isEmpty()) return null;
    try {
      JSONArray history = store.messages(chatId);
      if (history.length() == 0) return null;
      JSONObject last = history.getJSONObject(history.length() - 1);
      String draftJson = last.optString("draft", "");
      if (!"assistant".equals(last.optString("role")) || draftJson.isEmpty()) return null;
      JSONObject draft = new JSONObject(draftJson);
      if (!"progress".equals(draft.optString("kind"))) return null;
      long noteId = draft.optLong("note");
      Note n = store.note(noteId);
      store.dismissDraft(last.getLong("id"));
      store.message(chatId, "user", answer, new JSONArray());
      long now = System.currentTimeMillis();
      prefs.edit().putLong(CheckInJob.progressKey(noteId), ProgressCheck.nextAsk(answer, now)).apply();
      // Баллы внимания: «Не актуально» — сразу минус два, «Отложить» дважды подряд — минус балл,
      // остальные ответы сбрасывают счётчик откладываний.
      if (answer.equals(ProgressCheck.DROP)) {
        store.attentionAdjust(noteId, -2);
        prefs.edit().remove("attentionLater." + noteId).apply();
      } else if (answer.equals(ProgressCheck.LATER)) {
        String laterKey = "attentionLater." + noteId;
        int streak = prefs.getInt(laterKey, 0) + 1;
        if (streak >= 2) {
          store.attentionAdjust(noteId, -1);
          streak = 0;
        }
        prefs.edit().putInt(laterKey, streak).apply();
      } else prefs.edit().remove("attentionLater." + noteId).apply();
      boolean alive = n != null && n.trashed == 0;
      boolean postpone = alive && ProgressCheck.canPostpone(n.reminder, n.repeat);
      JSONObject next = null;
      String reply;
      if (!alive) reply = "Этой заметки уже нет — спрашивать о ней больше не буду.";
      else {
        reply = ProgressCheck.reply(answer, postpone);
        if (answer.equals(ProgressCheck.DONE) || answer.equals(ProgressCheck.DROP))
          next =
              new JSONObject().put("kind", "progress_trash").put("note", noteId).put("answer", answer);
        else if (answer.equals(ProgressCheck.LATER) && postpone)
          next = new JSONObject().put("kind", "progress_later").put("note", noteId);
      }
      JSONArray sources =
          alive ? new JSONArray().put(new JSONObject().put("id", noteId).put("title", n.title)) : new JSONArray();
      store.message(chatId, "assistant", reply, sources, next);
      return reply;
    } catch (Exception e) {
      return null;
    }
  }

  /** Карточки после ответа на «Как продвигается?»: убрать в корзину или перенести напоминание. */
  private void progressDraftView(LinearLayout bubble, long message, JSONObject draft) {
    String kind = draft.optString("kind");
    long noteId = draft.optLong("note");
    Note n = store.note(noteId);
    if (kind.equals("progress") || n == null || n.trashed > 0) return;
    gap(bubble, 6);
    LinearLayout row = row();
    if (kind.equals("progress_trash") && ProgressCheck.DONE.equals(draft.optString("answer"))) {
      // «Сделано»: «Выполнено» оставляет заметку и отмечает все пункты её плана.
      PlanTree plan = PlanTree.parse(store.plan(noteId));
      boolean planOpen = plan.checkable() > plan.done();
      row.addView(
          compactButton(
              "Выполнено",
              true,
              () -> {
                store.dismissDraft(message);
                PlanTree fresh = PlanTree.parse(store.plan(noteId));
                int marked = fresh.completeAll();
                if (marked > 0) {
                  try {
                    store.savePlan(noteId, fresh.toJson());
                  } catch (Exception e) {
                    toast("Не удалось отметить план");
                  }
                }
                stash();
                render();
                toast(marked > 0 ? "План выполнен: " + fresh.badge() : "Отмечено: выполнено");
              }),
          new LinearLayout.LayoutParams(0, -2, 1));
      row.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
      row.addView(
          compactButton(
              "В корзину",
              false,
              () -> {
                store.dismissDraft(message);
                List<Long> gone = store.trash(noteId, false);
                for (long g : gone) Reminders.cancel(this, g);
                if (!gone.isEmpty()) CalendarSync.runAsync(this);
                stash();
                render();
                toast(gone.isEmpty() ? "Заметка уже в корзине" : "Заметка в корзине. Вернуть её можно 30 дней");
              }),
          new LinearLayout.LayoutParams(0, -2, 1));
      bubble.addView(row);
      if (planOpen) {
        gap(bubble, 4);
        chatNote(bubble, "«Выполнено» отметит все пункты плана (" + plan.badge() + ")");
      }
      return;
    }
    if (kind.equals("progress_trash")) {
      row.addView(
          compactButton(
              "В корзину",
              true,
              () -> {
                store.dismissDraft(message);
                List<Long> gone = store.trash(noteId, false);
                for (long g : gone) Reminders.cancel(this, g);
                if (!gone.isEmpty()) CalendarSync.runAsync(this);
                stash();
                render();
                toast(gone.isEmpty() ? "Заметка уже в корзине" : "Заметка в корзине. Вернуть её можно 30 дней");
              }),
          new LinearLayout.LayoutParams(0, -2, 1));
      row.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
      row.addView(
          compactButton(
              "Оставить",
              false,
              () -> {
                store.dismissDraft(message);
                stash();
                render();
              }),
          new LinearLayout.LayoutParams(0, -2, 1));
    } else if (kind.equals("progress_later")) {
      for (int days : new int[] {1, 7}) {
        if (row.getChildCount() > 0) row.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
        row.addView(
            compactButton(
                days == 1 ? "На день" : "На неделю",
                days == 1,
                () -> {
                  Note before = store.note(noteId);
                  if (before == null || !ProgressCheck.canPostpone(before.reminder, before.repeat)) {
                    toast("Напоминание уже изменилось");
                    store.dismissDraft(message);
                    render();
                    return;
                  }
                  long moved = ProgressCheck.postponed(before.reminder, days, System.currentTimeMillis());
                  store.save(
                      noteId, before.title, before.body, moved, before.repeat, before.lead);
                  store.dismissDraft(message);
                  Reminders.scheduleEdited(getApplicationContext(), before, store.note(noteId));
                  stash();
                  render();
                  toast("Напоминание: " + date(moved));
                }),
            new LinearLayout.LayoutParams(0, -2, 1));
      }
      row.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
      row.addView(
          compactButton(
              "Не переносить",
              false,
              () -> {
                store.dismissDraft(message);
                stash();
                render();
              }),
          new LinearLayout.LayoutParams(0, -2, 1));
    }
    bubble.addView(row);
  }

  /** Открывает чат по заметке; новый чат сразу просит агента начать разбор. */
  private void openPlanChat(long noteId, boolean startPlan) {
    stash();
    if ("edit".equals(page) && !persistEditedNote()) return;
    Note n = store.note(noteId);
    if (n == null) return;
    long id = store.noteChat(noteId);
    boolean fresh = id == 0;
    if (fresh) id = store.newNoteChat(noteId, "План: " + n.title);
    hideKeyboard();
    chat = id;
    page = "chat";
    tab = 2;
    render();
    if (fresh && startPlan && chatInput != null) {
      chatInput.setText(PlanChat.START);
      send();
    }
  }

  /** Блок «План» под листом редактора: пункты с галочками, правка долгим нажатием. */
  private void planSection(long noteId) {
    LinearLayout box = column();
    content.addView(box);
    Runnable[] fill = new Runnable[1];
    fill[0] = () -> {
      box.removeAllViews();
      PlanTree plan = PlanTree.parse(store.plan(noteId));
      if (plan.isEmpty()) return;
      gap(box, 12);
      View rule = new View(this);
      rule.setBackgroundColor(LINE);
      box.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
      String count = plan.checkable() == 0 ? "" : " · " + plan.done() + " из " + plan.checkable();
      TextView heading = text("План" + count, 16, INK);
      heading.setTypeface(null, Typeface.BOLD);
      heading.setGravity(Gravity.CENTER_VERTICAL);
      heading.setMinHeight(dp(48));
      heading.setFocusable(true);
      heading.setCompoundDrawablesWithIntrinsicBounds(null, null, new Glyph(planCollapsed ? "down" : "up", GREEN, 18), null);
      heading.setContentDescription((planCollapsed ? "Раскрыть план" : "Свернуть план") + count);
      heading.setOnClickListener(v -> { planCollapsed = !planCollapsed; fill[0].run(); });
      box.addView(heading);
      if (!planCollapsed) planRows(box, plan, plan.nodes, 0, noteId, ZoneId.systemDefault(), fill[0]);
    };
    fill[0].run();
  }

  private void planRows(
      LinearLayout into,
      PlanTree plan,
      List<PlanTree.Node> list,
      int level,
      long noteId,
      java.time.ZoneId zone,
      Runnable refresh) {
    for (PlanTree.Node n : list) {
      LinearLayout line = row();
      line.setPadding(dp(6 + 18 * level), dp(7), dp(6), dp(7));
      TextView mark = text(n.checkable() ? (n.done ? "☑" : "☐") : "•", 16, n.done ? MUTED : GREEN);
      mark.setMinWidth(dp(24));
      line.addView(mark);
      String shown =
          (PlanTree.STEP.equals(n.kind) ? "" : PlanTree.label(n.kind) + ": ")
              + PlanTree.line(n, zone);
      TextView words = text(shown, 14, n.done ? MUTED : INK);
      if (n.done) words.setPaintFlags(words.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
      line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
      line.setBackground(ripple(SURFACE, 8));
      line.setFocusable(true);
      line.setContentDescription(
          (n.checkable() ? (n.done ? "Сделано: " : "Не сделано: ") : "") + shown);
      line.setOnClickListener(
          v -> {
            buzz(0);
            if (n.checkable()) {
              plan.toggle(n.id);
              savePlan(noteId, plan, refresh);
            } else if (PlanTree.TIME.equals(n.kind) && n.at > 0) remindFromPlan(n);
            else planNodeMenu(plan, n, noteId, refresh);
          });
      line.setOnLongClickListener(
          v -> {
            buzz(0);
            planNodeMenu(plan, n, noteId, refresh);
            return true;
          });
      into.addView(line);
      planRows(into, plan, n.children, level + 1, noteId, zone, refresh);
    }
  }

  private void planNodeMenu(PlanTree plan, PlanTree.Node n, long noteId, Runnable refresh) {
    List<String> labels = new ArrayList<>();
    List<Runnable> actions = new ArrayList<>();
    if (n.checkable()) {
      labels.add("Назначить срок");
      actions.add(() -> whenPicker(System.currentTimeMillis(), 18, "Срок пункта плана", at -> {
        PlanTree.Node due = null;
        for (PlanTree.Node child : n.children)
          if (PlanTree.TIME.equals(child.kind) && "Срок".equals(child.text)) { due = child; break; }
        if (due == null) due = plan.add(n.id, PlanTree.TIME, "Срок");
        if (due == null) { toast("Достигнут предел размера или глубины плана"); return; }
        due.at = at;
        savePlan(noteId, plan, refresh);
      }));
    }
    if (PlanTree.TIME.equals(n.kind) && n.at > 0) {
      labels.add("Напомнить в это время");
      actions.add(() -> remindFromPlan(n));
    }
    labels.add("Изменить");
    actions.add(
        () ->
            askPlanText(
                "Пункт плана",
                n.text,
                "Сохранить",
                text -> {
                  if (plan.edit(n.id, text)) savePlan(noteId, plan, refresh);
                }));
    labels.add("Добавить пункт внутрь");
    actions.add(
        () ->
            askPlanText(
                "Новый пункт",
                "",
                "Добавить",
                text -> {
                  if (plan.add(n.id, PlanTree.STEP, text) != null) savePlan(noteId, plan, refresh);
                  else toast("Глубже трёх уровней план не вкладывается");
                }));
    labels.add("Удалить");
    actions.add(
        () -> {
          plan.remove(n.id);
          savePlan(noteId, plan, refresh);
        });
    new Ask(this)
        .setTitle(n.text)
        .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
        .show();
  }

  /** Время из плана становится напоминанием заметки только по нажатию пользователя. */
  private void remindFromPlan(PlanTree.Node n) {
    if (n.at <= System.currentTimeMillis()) {
      toast("Это время уже прошло");
      return;
    }
    // К разовому напоминанию или списку дат время из плана можно добавить ещё одной датой.
    boolean canAdd = reminder > 0 && !ReminderPolicy.periodic(repeatMode);
    Ask dialog =
        new Ask(this)
            .setTitle("Напомнить " + date(n.at) + "?")
            .setMessage(
                n.text
                    + (canAdd
                        ? "\n\nМожно добавить к датам заметки или заменить их."
                        : reminder > 0 ? "\n\nТекущее напоминание заметки заменится." : ""))
            .setNegativeButton("Отмена", null)
            .setPositiveButton(
                canAdd ? "Заменить" : "Напомнить",
                (d, w) -> {
                  // Время из плана — разовое событие, прежний повтор и список к нему не относятся.
                  setEditorTimes(new long[] {n.at});
                  repeatMode = 0;
                  refreshReminder();
                  stash();
                  toast("Напоминание сохранится вместе с заметкой");
                });
    if (canAdd)
      dialog.setNeutralButton(
          "Добавить к датам",
          (d, w) -> {
            addReminderDate(n.at);
            toast("Даты сохранятся вместе с заметкой");
          });
    dialog.show();
  }

  private void savePlan(long noteId, PlanTree plan, Runnable refresh) {
    try {
      store.savePlan(noteId, plan.toJson());
    } catch (Exception e) {
      toast(e.getMessage() == null ? "Не удалось сохранить план" : e.getMessage());
    }
    refresh.run();
  }

  private void askPlanText(
      String title, String initial, String action, java.util.function.Consumer<String> done) {
    EditText field = input("Текст пункта", false);
    field.setText(initial);
    field.setSelection(field.getText().length());
    FrameLayout holder = new FrameLayout(this);
    holder.setPadding(dp(20), dp(8), dp(20), 0);
    holder.addView(field, new FrameLayout.LayoutParams(-1, -2));
    new Ask(this)
        .setTitle(title)
        .setView(holder)
        .setNegativeButton("Отмена", null)
        .setPositiveButton(
            action,
            (d, w) -> {
              String text = field.getText().toString().trim();
              if (!text.isEmpty()) done.accept(text);
            })
        .show();
  }

  /**
   * Черновик в ответе агента в чате по заметке: план и/или правка заметки (название, дописать,
   * даты). Ничего не меняется без нажатия «Сохранить план» / «Применить».
   */
  private void noteDraftView(LinearLayout bubble, long message, JSONObject draft) {
    long noteId = draft.optLong("note");
    PlanTree plan = draft.has("plan") ? PlanTree.parse(draft.optString("plan")) : null;
    if (plan != null && plan.isEmpty()) plan = null;
    NoteEdit edit = NoteEdit.fromJson(draft.optJSONObject("edit"));
    java.time.ZoneId zone = java.time.ZoneId.systemDefault();
    if (edit != null) {
      gap(bubble, 6);
      chatNote(bubble, "Изменения заметки");
      StringBuilder shown = new StringBuilder();
      for (String line : edit.describe(zone)) shown.append(shown.length() > 0 ? "\n" : "").append("• ").append(line);
      TextView lines = text(shown.toString(), 13, INK);
      lines.setLineSpacing(dp(2), 1);
      lines.setTextIsSelectable(true);
      bubble.addView(lines);
    }
    if (plan != null) {
      gap(bubble, 6);
      chatNote(bubble, "Черновик плана");
      List<String> outlineLines = plan.outline(zone);
      StringBuilder shown = new StringBuilder();
      for (int k = 0; k < outlineLines.size() && k < 40; k++)
        shown.append(k > 0 ? "\n" : "").append(outlineLines.get(k));
      if (outlineLines.size() > 40) shown.append("\n… ещё ").append(outlineLines.size() - 40);
      TextView outline = text(shown.toString(), 13, INK);
      outline.setLineSpacing(dp(2), 1);
      outline.setTextIsSelectable(true);
      bubble.addView(outline);
    }
    if (edit == null && plan == null) return;
    gap(bubble, 6);
    PlanTree finalPlan = plan;
    LinearLayout row = row();
    row.addView(
        compactButton(
            edit == null ? "Сохранить план" : "Применить",
            true,
            () -> {
              try {
                Note before = store.applyNoteDraft(message, noteId, edit, finalPlan);
                Note after = store.note(noteId);
                if (edit != null) {
                  Reminders.scheduleEdited(getApplicationContext(), before, after);
                  BackgroundAnalysis.enqueue(this);
                  NoteEdit.Result check =
                      edit.apply("", "", 0, 0, "", System.currentTimeMillis());
                  toast(
                      check.datesPassed
                          ? "Изменения применены, но даты напоминания уже прошли"
                          : "Изменения применены к заметке");
                } else toast("План сохранён в заметке");
              } catch (Exception e) {
                toast(e.getMessage() == null ? "Не удалось применить" : e.getMessage());
              }
              stash();
              render();
            }),
        new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
    row.addView(
        compactButton(
            edit == null ? "Не сохранять" : "Не применять",
            false,
            () -> {
              store.dismissDraft(message);
              stash();
              render();
            }),
        new LinearLayout.LayoutParams(0, -2, 1));
    bubble.addView(row);
  }

  private void send() {
    String query = chatInput.getText().toString().trim();
    String image = prefs.getString("chatImage." + chat, "");
    if (ChatRunner.running != 0 || (query.isEmpty() && image.isEmpty())) return;
    if (image.isEmpty() && answerProgress(query)) return;
    if (query.isEmpty()) query = ChatRunner.NOTE_QUERY;
    boolean local = LocalLlm.enabled(this);
    if (local && !image.isEmpty()) {
      toast(
          "Локальная модель пока работает только с текстом. Уберите картинку или выберите"
              + " Polza.AI в настройках.");
      return;
    }
    if (local && !LocalLlm.ready(this)) {
      toast("Сначала импортируйте GGUF-модель в настройках");
      return;
    }
    String key;
    try {
      key = local ? "" : Secrets.read(this);
    } catch (Exception e) {
      toast("Не удалось прочитать ключ. Сохраните его заново в настройках.");
      return;
    }
    if (!local && (key.isEmpty() || prefs.getString("model", "").isEmpty())) {
      new Ask(this)
          .setTitle("Подключите Polza.AI")
          .setMessage(
              "Укажите свой API-ключ и выберите модель в настройках или включите локальную модель"
                  + " Gemini Nano (Google AICore) без ключа.")
          .setNegativeButton("Позже", null)
          .setPositiveButton("Настройки", (d, w) -> navigate(3))
          .show();
      return;
    }
    if (!local && !prefs.getBoolean("consent", false)) {
      new Ask(this)
          .setTitle("Отправить заметки в Polza.AI?")
          .setMessage(
              "Ваш вопрос, последние сообщения чата и до 8 найденных фрагментов заметок будут"
                  + " отправлены в Polza.AI и выбранной модели. Запросы оплачиваются с вашего"
                  + " баланса Polza.AI. Картинки, которые вы присылаете в чат, тоже уходят"
                  + " модели и остаются в чате: ИИ видит последние из них при каждом ответе.")
          .setNegativeButton("Отмена", null)
          .setPositiveButton(
              "Подключить",
              (d, w) -> {
                prefs.edit().putBoolean("consent", true).apply();
                send();
              })
          .show();
      return;
    }
    List<String> videoLinks = YoutubeLink.all(query);
    if (!local
        && !sendPlain
        && image.isEmpty()
        && !videoLinks.isEmpty()
        && (chat == 0 || store.chatNote(chat) == 0)) {
      final String asked = query;
      offerVideoSummary(
          videoLinks,
          () -> {
            sendPlain = true;
            send();
          },
          () -> {
            chatInput.setText("");
            prefs.edit().remove("chatDraft." + chat).apply();
            hideKeyboard();
            render();
          },
          "Отправить как вопрос",
          () -> {
            if (chat == 0) chat = store.newChat(Note.autoTitle("", asked));
            store.message(chat, "user", asked, new JSONArray());
            return chat;
          });
      return;
    }
    sendPlain = false;
    if (chat == 0) {
      chat = store.newChat(Note.autoTitle("", query));
      if (!image.isEmpty())
        prefs.edit().putString("chatImage." + chat, image).remove("chatImage.0").apply();
      prefs.edit().remove("chatDraft.0").apply();
    }
    chatInput.setText("");
    prefs.edit().remove("chatDraft." + chat).apply();
    hideKeyboard();
    ChatRunner.send(
        this,
        chat,
        key,
        local ? LocalLlm.modelId(this) : prefs.getString("model", ""),
        query,
        ManagerAnalysis.answerTokens(prefs.getInt("maxTokens", 0)),
        image,
        imageModel());
    render();
  }

  /** Действия с выбранной картинкой заметки одним списком, чтобы не занимать место кнопками. */
  private void showImageActions() {
    List<String> labels = new ArrayList<>(), keys = new ArrayList<>();
    labels.add("Описать и добавить в заметку");
    keys.add("describe");
    labels.add("Создать заметку по изображению");
    keys.add("analyze");
    labels.add("Повернуть и обрезать");
    keys.add("edit");
    if (ImageList.count(noteImage) > 1) {
      labels.add("Сдвинуть раньше");
      keys.add("earlier");
      labels.add("Сдвинуть позже");
      keys.add("later");
    }
    labels.add("Убрать");
    keys.add("remove");
    new Ask(this)
        .setTitle("Изображение " + (selectedImage + 1))
        .setItems(
            labels.toArray(new String[0]),
            (d, which) -> {
              switch (keys.get(which)) {
                case "describe" -> describeNoteImage();
                case "analyze" -> analyzeNoteImage();
                case "edit" -> editSelectedImage();
                case "earlier" -> moveSelectedImage(-1);
                case "later" -> moveSelectedImage(1);
                default -> removeSelectedImage();
              }
            })
        .show();
  }

  private ImageView imageTile(String name, int size, int radius) {
    ImageView thumb = new ImageView(this);
    thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
    thumb.setBackground(shape(LIME, radius));
    thumb.setClipToOutline(true);
    thumb.setOutlineProvider(rounded(radius));
    ImageThumbs.load(this, thumb, name, size * 2);
    return thumb;
  }

  /** Картинки, приложенные к новому сообщению чата: лента с «×» и плиткой «добавить ещё». */
  private View chatAttachStrip(List<String> names, long chatId) {
    HorizontalScrollView scroll = new HorizontalScrollView(this);
    scroll.setHorizontalScrollBarEnabled(false);
    LinearLayout strip = row();
    for (int position = 0; position < names.size(); position++) {
      String name = names.get(position);
      String label = "изображение " + (position + 1) + " из " + names.size();
      FrameLayout cell = new FrameLayout(this);
      ImageView thumb = imageTile(name, 64, 10);
      thumb.setContentDescription("Приложено " + label + ". Нажмите: открыть, обрезать или убрать");
      thumb.setOnClickListener(
          v -> {
            buzz(0);
            chatImageMenu(name, names, chatId);
          });
      cell.addView(thumb, new FrameLayout.LayoutParams(dp(64), dp(64)));
      ImageView remove = new ImageView(this);
      remove.setScaleType(ImageView.ScaleType.CENTER);
      remove.setImageDrawable(new Glyph("close", WHITE, 12));
      // Видимый кружок 22 dp, зона касания 36 dp.
      remove.setBackground(new InsetDrawable(shape(0xCC1E3028, 11), dp(7)));
      remove.setContentDescription("Убрать " + label);
      remove.setOnClickListener(
          v -> {
            buzz(0);
            removeChatImage(name, chatId);
          });
      FrameLayout.LayoutParams removeParams =
          new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP | Gravity.END);
      cell.addView(remove, removeParams);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
      params.rightMargin = dp(6);
      strip.addView(cell, params);
    }
    if (names.size() < ChatImages.MAX_ATTACH) {
      ImageView more = new ImageView(this);
      more.setScaleType(ImageView.ScaleType.CENTER);
      more.setImageDrawable(new Glyph("add", GREEN, 22));
      more.setBackground(ripple(LIME, 10));
      more.setContentDescription("Добавить ещё изображения");
      more.setFocusable(true);
      more.setOnClickListener(
          v -> {
            buzz(0);
            pickImage(true);
          });
      strip.addView(more, new LinearLayout.LayoutParams(dp(64), dp(64)));
    }
    scroll.addView(strip);
    return scroll;
  }

  private void chatImageMenu(String name, List<String> all, long chatId) {
    new Ask(this)
        .setItems(
            new String[] {"Открыть", "Повернуть и обрезать", "Убрать"},
            (d, which) -> {
              if (which == 0) showImageViewer(all, Math.max(0, all.indexOf(name)));
              else if (which == 1) editChatImage(name, chatId);
              else removeChatImage(name, chatId);
            })
        .show();
  }

  private void removeChatImage(String name, long chatId) {
    if (ChatRunner.running != 0 && ChatRunner.running == chatId) {
      toast("Дождитесь завершения ответа");
      return;
    }
    String key = "chatImage." + chatId;
    String before = prefs.getString(key, "");
    String after = ImageList.remove(before, name);
    stash();
    if (after.isEmpty()) prefs.edit().remove(key).apply();
    else prefs.edit().putString(key, after).apply();
    releaseImage(before, after);
    render();
  }

  private void editChatImage(String name, long chatId) {
    if (ChatRunner.running != 0) {
      toast("Дождитесь завершения ответа");
      return;
    }
    showImageEditor(
        name,
        edited -> {
          String key = "chatImage." + chatId;
          String before = prefs.getString(key, "");
          if (!ImageList.parse(before).contains(name)) {
            releaseImage(edited, "");
            return;
          }
          String after = ImageList.replace(before, name, edited);
          prefs.edit().putString(key, after).apply();
          releaseImage(before, after);
          if (page.equals("chat") && chat == chatId) {
            stash();
            render();
          }
        });
  }

  /** Миниатюры картинок внутри пузыря сообщения; нажатие открывает просмотр на весь экран. */
  private View messageImages(List<String> names) {
    HorizontalScrollView scroll = new HorizontalScrollView(this);
    scroll.setHorizontalScrollBarEnabled(false);
    LinearLayout strip = row();
    for (int i = 0; i < names.size(); i++) {
      int index = i;
      ImageView thumb = imageTile(names.get(i), 72, 8);
      thumb.setContentDescription(
          "Изображение " + (i + 1) + " из " + names.size() + ". Нажмите, чтобы открыть");
      thumb.setOnClickListener(
          v -> {
            buzz(0);
            showImageViewer(names, index);
          });
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(72), dp(72));
      params.rightMargin = dp(5);
      strip.addView(thumb, params);
    }
    scroll.addView(strip);
    return scroll;
  }

  private ViewOutlineProvider rounded(int radius) {
    return new ViewOutlineProvider() {
      @Override
      public void getOutline(View view, Outline outline) {
        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radius));
      }
    };
  }

  private LinearLayout pair(View first, View second) {
    LinearLayout row = row();
    LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
    left.rightMargin = dp(8);
    row.addView(first, left);
    row.addView(second, new LinearLayout.LayoutParams(0, -2, 1));
    return row;
  }

  /** Лента миниатюр заметки: нажатие выбирает картинку, к которой относятся кнопки ниже. */
  private View imageStrip(List<String> images) {
    HorizontalScrollView scroll = new HorizontalScrollView(this);
    scroll.setHorizontalScrollBarEnabled(false);
    LinearLayout strip = row();
    for (int i = 0; i < images.size(); i++) {
      int index = i;
      FrameLayout cell = new FrameLayout(this);
      cell.setPadding(dp(2), dp(2), dp(2), dp(2));
      cell.setBackground(shape(i == selectedImage ? GREEN : 0x00000000, 10));
      ImageView thumb = new ImageView(this);
      thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
      thumb.setBackground(shape(LIME, 8));
      thumb.setClipToOutline(true);
      thumb.setOutlineProvider(rounded(8));
      ImageThumbs.load(this, thumb, images.get(i), 160);
      cell.addView(thumb, new FrameLayout.LayoutParams(dp(72), dp(72)));
      cell.setContentDescription(
          "Изображение " + (i + 1) + " из " + images.size() + (i == selectedImage ? ", выбрано" : ""));
      cell.setOnClickListener(
          v -> {
            buzz(0);
            if (index == selectedImage) {
              showImageViewer(images, index);
              return;
            }
            stash();
            selectedImage = index;
            render();
          });
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
      params.rightMargin = dp(6);
      strip.addView(cell, params);
    }
    scroll.addView(strip);
    scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> stripScroll = x);
    // После перерисовки возвращаем прежнее положение и следим, чтобы выбранная плитка была видна
    // (выбор, добавление и сдвиг перерисовывают экран).
    scroll.post(
        () -> {
          int target = stripScroll;
          View selected = strip.getChildAt(selectedImage);
          if (selected != null) {
            if (selected.getLeft() < target) target = selected.getLeft();
            else if (selected.getRight() > target + scroll.getWidth())
              target = selected.getRight() - scroll.getWidth();
          }
          scroll.scrollTo(Math.max(0, target), 0);
        });
    return scroll;
  }

  private String selectedImageName() {
    List<String> images = ImageList.parse(noteImage);
    return images.isEmpty() ? "" : images.get(Math.max(0, Math.min(selectedImage, images.size() - 1)));
  }

  private void removeSelectedImage() {
    String before = noteImage;
    noteImage = ImageList.remove(noteImage, selectedImageName());
    selectedImage = Math.min(selectedImage, Math.max(0, ImageList.count(noteImage) - 1));
    stash();
    releaseImage(before, noteImage);
    render();
  }

  private void moveSelectedImage(int delta) {
    String name = selectedImageName();
    String moved = ImageList.move(noteImage, name, delta);
    if (moved.equals(noteImage)) return;
    noteImage = moved;
    selectedImage = ImageList.parse(moved).indexOf(name);
    stash();
    render();
  }

  private void editSelectedImage() {
    if (ImageRunner.running) {
      toast("Дождитесь завершения анализа");
      return;
    }
    stash();
    String current = selectedImageName();
    long noteId = editing;
    showImageEditor(
        current,
        edited -> {
          // Пока шло сохранение, заметку могли закрыть или картинку убрать.
          if (!editorActive || editing != noteId || !ImageList.parse(noteImage).contains(current)) {
            releaseImage(edited, "");
            return;
          }
          String before = noteImage;
          noteImage = ImageList.replace(noteImage, current, edited);
          stash();
          releaseImage(before, noteImage);
          render();
        });
  }

  /** Полноэкранный просмотр: листание кнопками, щипок и двойное касание увеличивают. */
  private void showImageViewer(List<String> names, int start) {
    Dialog viewer = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
    FrameLayout frame = new FrameLayout(this);
    frame.setBackgroundColor(Color.BLACK);
    ZoomImageView zoom = new ZoomImageView(this);
    zoom.setContentDescription("Изображение. Щипком можно увеличить, двойное касание — приблизить");
    frame.addView(zoom, new FrameLayout.LayoutParams(-1, -1));
    TextView close = text("✕", 22, WHITE);
    close.setGravity(Gravity.CENTER);
    close.setBackground(shape(0x66000000, 24));
    close.setContentDescription("Закрыть");
    close.setOnClickListener(v -> viewer.dismiss());
    FrameLayout.LayoutParams closeParams =
        new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END);
    closeParams.topMargin = dp(36);
    closeParams.rightMargin = dp(16);
    frame.addView(close, closeParams);
    int[] at = {Math.max(0, Math.min(start, names.size() - 1))};
    TextView counter = text("", 15, WHITE);
    counter.setGravity(Gravity.CENTER);
    counter.setBackground(shape(0x66000000, 16));
    counter.setPadding(dp(12), dp(6), dp(12), dp(6));
    FrameLayout.LayoutParams counterParams =
        new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
    counterParams.topMargin = dp(44);
    counterParams.leftMargin = dp(16);
    counter.setVisibility(names.size() > 1 ? View.VISIBLE : View.GONE);
    frame.addView(counter, counterParams);
    Runnable show =
        () -> {
          String name = names.get(at[0]);
          counter.setText((at[0] + 1) + " / " + names.size());
          ImageThumbs.request(
              this,
              name,
              2000,
              bitmap -> {
                if (viewer.isShowing() && names.get(at[0]).equals(name)) zoom.setImageBitmap(bitmap);
              });
        };
    if (names.size() > 1) {
      for (int direction : new int[] {-1, 1}) {
        TextView arrow = text(direction < 0 ? "‹" : "›", 30, WHITE);
        arrow.setGravity(Gravity.CENTER);
        arrow.setBackground(shape(0x66000000, 24));
        arrow.setContentDescription(direction < 0 ? "Предыдущее изображение" : "Следующее изображение");
        arrow.setOnClickListener(
            v -> {
              buzz(0);
              at[0] = (at[0] + direction + names.size()) % names.size();
              show.run();
            });
        frame.addView(
            arrow,
            new FrameLayout.LayoutParams(
                dp(48),
                dp(48),
                Gravity.CENTER_VERTICAL | (direction < 0 ? Gravity.START : Gravity.END)));
      }
    }
    viewer.setContentView(frame);
    show.run();
    viewer.show();
  }

  /** Сколько картинок ещё можно добавить к новому сообщению чата или к заметке. */
  private int imageRoom(boolean forChat) {
    if (forChat) return ChatImages.MAX_ATTACH - ImageList.count(prefs.getString("chatImage." + chat, ""));
    return ImageList.MAX - ImageList.count(noteImage);
  }

  private void pickImage(boolean forChat) {
    if ((forChat && ChatRunner.running != 0) || ImageRunner.running) {
      toast("Дождитесь завершения анализа");
      return;
    }
    int room = imageRoom(forChat);
    if (room <= 0) {
      toast(
          forChat
              ? "К сообщению не больше " + ChatImages.MAX_ATTACH + " картинок. Уберите лишнее."
              : "В заметке не больше " + ImageList.MAX + " изображений. Уберите лишнее.");
      return;
    }
    stash();
    pickerPage = forChat ? "chat" : "note";
    pickerTarget = forChat ? chat : editing;
    boolean system = Build.VERSION.SDK_INT >= 33;
    String limit = room > 1 ? " (до " + room + ")" : "";
    new Ask(this)
        .setTitle("Добавить изображения")
        .setItems(
            system
                ? new String[] {"Галерея: несколько фото" + limit, "Файлы", "Сделать снимок"}
                : new String[] {"Выбрать несколько изображений" + limit, "Сделать снимок"},
            (d, which) -> {
              int choice = system ? which : which == 0 ? 1 : 2;
              if (choice == 0) pickFromGallery(room);
              else if (choice == 1) pickFromFiles();
              else takePhoto();
            })
        .show();
  }

  /** Системный выбор фото Android 13+: плитка снимков, можно отметить сразу несколько. */
  private void pickFromGallery(int room) {
    try {
      Intent picker = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
      // Лимит системного выбора — сотни снимков, нам нужно не больше 8; при отказе откроются файлы.
      if (room > 1) picker.putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, room);
      startActivityForResult(picker, 91);
    } catch (Exception e) {
      pickFromFiles();
    }
  }

  private void pickFromFiles() {
    startActivityForResult(
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .setType("image/*"),
        88);
  }

  /** Снимок во временный файл приложения; разрешение на камеру не нужно, снимает системное окно. */
  private void takePhoto() {
    try {
      java.io.File dir = new java.io.File(getCacheDir(), "camera");
      if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException();
      java.io.File shot = new java.io.File(dir, "shot-" + System.currentTimeMillis() + ".jpg");
      prefs.edit().putString("cameraShot", shot.getName()).apply();
      Uri output =
          androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", shot);
      Intent camera =
          new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
              .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, output)
              .addFlags(
                  Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
      camera.setClipData(ClipData.newRawUri("", output));
      startActivityForResult(camera, 90);
    } catch (ActivityNotFoundException e) {
      toast("На телефоне нет приложения камеры");
    } catch (Exception e) {
      toast("Не удалось открыть камеру");
    }
  }

  /** Файл последнего снимка; имя проверяется, чтобы нельзя было выйти за папку снимков. */
  private java.io.File takeShot() {
    String name = prefs.getString("cameraShot", "");
    prefs.edit().remove("cameraShot").apply();
    if (!name.matches("shot-\\d+\\.jpg")) return null;
    java.io.File file = new java.io.File(new java.io.File(getCacheDir(), "camera"), name);
    return file.exists() ? file : null;
  }

  private TextView darkButton(String label, boolean primary, String description, Runnable click) {
    TextView b = text(label, 16, WHITE);
    b.setTypeface(null, Typeface.BOLD);
    b.setGravity(Gravity.CENTER);
    b.setMinHeight(dp(48));
    b.setPadding(dp(12), dp(8), dp(12), dp(8));
    b.setBackground(ripple(primary ? FILL : 0x33FFFFFF, 10));
    b.setFocusable(true);
    if (description != null) b.setContentDescription(description);
    b.setOnClickListener(
        v -> {
          buzz(0);
          click.run();
        });
    return b;
  }

  /**
   * Экран поворота и обрезки. Результат — имя нового файла; исходный файл не меняется. Если
   * ничего не менялось, {@code done} не вызывается.
   */
  private void showImageEditor(String name, java.util.function.Consumer<String> done) {
    Dialog editor = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
    LinearLayout box = column();
    box.setBackgroundColor(Color.BLACK);
    box.setPadding(0, dp(28), 0, dp(12));
    CropView crop = new CropView(this);
    crop.setContentDescription("Область обрезки. Перетащите углы или саму рамку");
    box.addView(crop, new LinearLayout.LayoutParams(-1, 0, 1));
    Bitmap[] base = {null};
    int[] turns = {0};
    Runnable show =
        () -> {
          Bitmap source = base[0];
          if (source == null) return;
          if (turns[0] == 0) {
            crop.setBitmap(source);
            return;
          }
          Matrix matrix = new Matrix();
          matrix.postRotate(90f * turns[0]);
          crop.setBitmap(
              Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true));
        };
    LinearLayout tools = row();
    tools.setPadding(dp(12), dp(10), dp(12), 0);
    LinearLayout.LayoutParams weight = new LinearLayout.LayoutParams(0, -2, 1);
    weight.rightMargin = dp(8);
    tools.addView(
        darkButton(
            "⟲",
            false,
            "Повернуть влево",
            () -> {
              turns[0] = CropMath.turns(turns[0] - 1);
              show.run();
            }),
        weight);
    tools.addView(
        darkButton(
            "⟳",
            false,
            "Повернуть вправо",
            () -> {
              turns[0] = CropMath.turns(turns[0] + 1);
              show.run();
            }),
        weight);
    tools.addView(
        darkButton(
            "Сбросить",
            false,
            null,
            () -> {
              turns[0] = 0;
              show.run();
            }),
        new LinearLayout.LayoutParams(0, -2, 1));
    box.addView(tools);
    LinearLayout actions = row();
    actions.setPadding(dp(12), dp(10), dp(12), 0);
    actions.addView(darkButton("Отмена", false, null, editor::dismiss), weight);
    actions.addView(
        darkButton(
            "Готово",
            true,
            null,
            () -> {
              if (base[0] == null) return;
              float[] fractions = crop.fractions();
              int quarterTurns = CropMath.turns(turns[0]);
              editor.dismiss();
              if (quarterTurns == 0 && CropMath.isFull(fractions)) return;
              toast("Сохраняю…");
              io.execute(
                  () -> {
                    try {
                      String edited =
                          ImageFiles.edit(getApplicationContext(), name, quarterTurns, fractions);
                      runOnUiThread(
                          () -> {
                            if (!isDestroyed()) done.accept(edited);
                          });
                    } catch (Exception | OutOfMemoryError e) {
                      runOnUiThread(
                          () -> {
                            if (!isDestroyed()) toast("Не удалось изменить изображение");
                          });
                    }
                  });
            }),
        new LinearLayout.LayoutParams(0, -2, 1));
    box.addView(actions);
    editor.setContentView(box);
    ImageThumbs.request(
        this,
        name,
        1600,
        bitmap -> {
          if (!editor.isShowing()) return;
          base[0] = bitmap;
          show.run();
        });
    editor.show();
  }

  /** «Поделиться» из другого приложения: сохраняем заметку сразу и открываем её для правки. */
  private void handleShare(Intent intent) {
    CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
    Uri stream =
        Build.VERSION.SDK_INT >= 33
            ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
            : intent.getParcelableExtra(Intent.EXTRA_STREAM);
    boolean image = ShareParser.isImage(intent.getType()) && stream != null;
    String[] parts =
        ShareParser.note(
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
            text == null ? null : text.toString(),
            image);
    // Повторный разбор того же intent при пересоздании экрана не нужен.
    setIntent(new Intent(this, MainActivity.class));
    if (parts == null) {
      toast("В этом содержимом нет текста или картинки для заметки");
      return;
    }
    if (!image && !LocalLlm.enabled(this)) {
      List<String> links = YoutubeLink.all(text == null ? "" : text.toString());
      if (!links.isEmpty()) {
        offerVideoSummary(links, () -> saveSharedNote(parts, false, null), null);
        return;
      }
    }
    saveSharedNote(parts, image, stream);
  }

  private void saveSharedNote(String[] parts, boolean image, Uri stream) {
    toast("Сохраняю заметку…");
    io.execute(
        () -> {
          try {
            String file = image ? ImageFiles.importImage(getApplicationContext(), stream) : "";
            long id;
            try (Store saver = new Store(getApplicationContext())) {
              id = saver.save(0, parts[0], parts[1], 0);
              if (!file.isEmpty()) saver.image(id, file);
            }
            BackgroundAnalysis.enqueue(getApplicationContext());
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  tab = 0;
                  openNote(id);
                  toast("Заметка сохранена");
                });
          } catch (Exception | OutOfMemoryError e) {
            runOnUiThread(
                () -> {
                  if (!isDestroyed()) toast("Не удалось сохранить. Картинка должна быть до 25 МБ.");
                });
          }
        });
  }

  /** Модель для картинок: из настройки; если пусто, Gemini 2.5 Flash (хорошо читает текст на фото). */
  private String imageModel() {
    String model = prefs.getString("imageModel", "").trim();
    return model.isEmpty() ? Vision.DEFAULT_MODEL : model;
  }

  private String videoModel() {
    String model = prefs.getString("videoModel", "").trim();
    return model.isEmpty() ? VideoAnalysis.DEFAULT_MODEL : model;
  }

  /**
   * Предлагает сделать сводку по ссылкам на видео. {@code keep} сохраняет их как обычную заметку,
   * {@code started} вызывается после запуска (например, очистить поле ввода).
   */
  private void offerVideoSummary(List<String> links, Runnable keep, Runnable started) {
    offerVideoSummary(links, keep, started, "Сохранить как есть", null);
  }

  /** Окно для вставки ссылки на видео: кнопка «Сводка видео» в заметках и при записи. */
  private void askVideoLink() {
    if (LocalLlm.enabled(this)) {
      toast("Сводку видео делает Polza.AI. Выберите облачный режим в настройках.");
      return;
    }
    EditText field = new EditText(this);
    field.setHint("https://www.youtube.com/watch?v=…");
    field.setSingleLine(true);
    field.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
    try {
      android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
      android.content.ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
      CharSequence pasted =
          clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).coerceToText(this);
      if (pasted != null && !YoutubeLink.all(pasted.toString()).isEmpty())
        field.setText(pasted.toString().trim());
    } catch (RuntimeException ignored) {
    }
    FrameLayout box = new FrameLayout(this);
    box.setPadding(dp(22), dp(8), dp(22), 0);
    box.addView(field);
    new Ask(this)
        .setTitle("Сводка видео YouTube")
        .setMessage("Вставьте ссылку на видео, и ИИ сделает по нему заметку.")
        .setView(box)
        .setNegativeButton("Отмена", null)
        .setPositiveButton(
            "Дальше",
            (d, w) -> {
              List<String> links = YoutubeLink.all(field.getText().toString());
              if (links.isEmpty()) {
                toast("Не нашёл ссылку на видео YouTube");
                return;
              }
              offerVideoSummary(
                  links,
                  null,
                  null,
                  "",
                  "chat".equals(page)
                      ? () -> {
                        if (chat == 0) chat = store.newChat("Сводка видео");
                        store.message(chat, "user", String.join("\n", links), new JSONArray());
                        return chat;
                      }
                      : null);
            })
        .show();
  }

  /**
   * {@code keepLabel} — подпись кнопки, которая вызывает {@code keep}; пусто или {@code keep == null}
   * — кнопки нет. {@code chatFor} вызывается перед запуском и возвращает номер чата, куда
   * положить итог (null — не в чат).
   */
  private void offerVideoSummary(
      List<String> links,
      Runnable keep,
      Runnable started,
      String keepLabel,
      java.util.function.LongSupplier chatFor) {
    if (VideoRunner.running) {
      toast("Сводка видео уже готовится. Подождите её завершения.");
      return;
    }
    String key;
    try {
      key = Secrets.read(this);
    } catch (Exception e) {
      key = "";
    }
    if (key.isEmpty()) {
      new Ask(this)
          .setTitle("Подключите Polza.AI")
          .setMessage(
              "Сводку видео делает модель Polza.AI. Укажите API-ключ в настройках или сохраните"
                  + " ссылку как обычную заметку.")
          .setNeutralButton("Отмена", null)
          .setNegativeButton(keep == null ? null : keepLabel, keep == null ? null : (d, w) -> keep.run())
          .setPositiveButton("Настройки", (d, w) -> navigate(3))
          .show();
      return;
    }
    final String apiKey = key, model = videoModel();
    new Ask(this)
        .setTitle(links.size() == 1 ? "Сделать сводку видео?" : "Сделать сводки видео?")
        .setMessage(
            (links.size() == 1
                    ? "Ссылка на видео будет отправлена в Polza.AI, и модель "
                    : "Ссылок на видео: " + links.size() + ", каждая — отдельный запрос. Модель ")
                + model
                + " посмотрит ролик. Запрос оплачивается с вашего баланса Polza.AI, цена растёт с"
                + " длиной видео. Ориентир: ролик на 10 минут обошёлся примерно в 2 ₽ на "
                + VideoAnalysis.DEFAULT_MODEL
                + ". Результат появится отдельной заметкой; повтор после ошибки — только вручную.")
        .setNeutralButton("Отмена", null)
        .setNegativeButton(keep == null ? null : keepLabel, keep == null ? null : (d, w) -> keep.run())
        .setPositiveButton(
            "Сделать сводку",
            (d, w) -> {
              long into = chatFor == null ? 0 : chatFor.getAsLong();
              if (VideoRunner.start(this, links, apiKey, model, into)) {
                if (started != null) started.run();
                toast("Видео отправлено на анализ. Это может занять до минуты.");
              }
            })
        .show();
  }

  @Override
  public void videoFinished() {
    if (isDestroyed() || isFinishing() || VideoRunner.running) return;
    VideoRunner.Outcome outcome = VideoRunner.take();
    if (outcome.empty()) return;
    boolean idle = ("home".equals(page) || "notes".equals(page)) && !editorActive;
    if (outcome.merged > 0 || !outcome.mergeError.isEmpty()) {
      // Закончилось объединение с общей заметкой.
      if (!outcome.mergeError.isEmpty()) {
        new Ask(this)
            .setTitle("Объединить не удалось")
            .setMessage(outcome.mergeError)
            .setPositiveButton("Понятно", null)
            .show();
      } else {
        toast("Видео добавлено в общую заметку");
      }
      if (outcome.merged > 0) folderQueue.add(outcome.merged);
      boolean asking = askNext();
      if (outcome.merged > 0 && !asking && idle) {
        tab = 0;
        openNote(outcome.merged);
      } else if ("notes".equals(page)) {
        render();
      }
      return;
    }
    int made = outcome.created.size();
    if (!outcome.error.isEmpty()) {
      new Ask(this)
          .setTitle("Сводка видео не готова")
          .setMessage(
              (made > 0 ? "Готово заметок: " + made + ".\n\n" : "") + outcome.error)
          .setPositiveButton("Понятно", null)
          .show();
    } else {
      mergeQueue.addAll(outcome.created);
      folderQueue.addAll(outcome.created);
    }
    long open = made == 1 ? outcome.created.get(0) : 0;
    if (made == 0 && outcome.existing.size() == 1 && outcome.error.isEmpty())
      open = outcome.existing.get(0);
    if (outcome.error.isEmpty()) {
      if (made > 1) toast("Готово сводок: " + made);
      else if (made == 1) toast("Сводка видео готова");
      else toast("Это видео уже есть в заметках");
    }
    boolean asking = outcome.error.isEmpty() && askNext();
    if (open > 0 && idle && !asking && outcome.error.isEmpty()) {
      tab = 0;
      openNote(open);
    } else if ("notes".equals(page)) {
      render();
    }
  }

  private final ArrayDeque<Long> mergeQueue = new ArrayDeque<>();
  private boolean mergeAsking;

  /**
   * Предлагает добавить свежую сводку в заметку по той же теме. Идёт по очереди только что
   * созданных заметок; true — окно показано и очередь продолжится после ответа.
   */
  private boolean askNextMerge() {
    if (mergeAsking || VideoRunner.running || isDestroyed() || isFinishing()) return mergeAsking;
    while (!mergeQueue.isEmpty()) {
      Note fresh = store.note(mergeQueue.poll());
      if (fresh == null) continue;
      Note target = VideoMerge.best(fresh, store.videoNotes());
      if (target == null) continue;
      mergeAsking = true;
      Runnable decline =
          () -> {
            mergeAsking = false;
            askNext();
          };
      new Ask(this)
          .setTitle("Добавить в общую заметку?")
          .setMessage(
              "Видео «"
                  + fresh.title
                  + "» похоже на тему заметки «"
                  + target.title
                  + "». Добавить его отдельным разделом туда и обновить общую сводку? Нужен ещё"
                  + " один небольшой платный запрос (только текст, без видео). Отдельная заметка"
                  + " о видео после этого удаляется.")
          .setNegativeButton("Оставить отдельно", (d, w) -> decline.run())
          .setPositiveButton(
              "Объединить",
              (d, w) -> {
                mergeAsking = false;
                startMerge(fresh, target, decline);
              })
          .setOnCancelListener(d -> decline.run())
          .show();
      return true;
    }
    return false;
  }

  private void startMerge(Note fresh, Note target, Runnable fallback) {
    if (editorActive && (editing == fresh.id || editing == target.id)) {
      toast("Закройте открытую заметку и повторите объединение вручную.");
      fallback.run();
      return;
    }
    String key;
    try {
      key = Secrets.read(this);
    } catch (Exception e) {
      key = "";
    }
    if (key.isEmpty()) {
      toast("Не удалось прочитать API-ключ");
      fallback.run();
      return;
    }
    if (VideoRunner.merge(this, fresh.id, target.id, key, videoModel()))
      toast("Объединяю видео в одну заметку…");
    else fallback.run();
  }

  private void analyzeNoteImage() {
    if (LocalLlm.enabled(this)) {
      toast("Картинки пока доступны только через Polza.AI. Переключите режим в настройках.");
      return;
    }
    if (ImageRunner.running) {
      toast("Изображение уже анализируется");
      return;
    }
    if (!persistEditedNote()) return;
    stash();
    String image = selectedImageName();
    try {
      String key = Secrets.read(this), model = imageModel();
      if (key.isEmpty()) {
        toast("Сначала сохраните API-ключ в настройках");
        return;
      }
      new Ask(this)
          .setTitle("Создать заметку по изображению?")
          .setMessage(
              "Картинка будет отправлена в Polza.AI и выбранную модель. С вашего баланса спишется"
                  + " стоимость анализа. Исходная заметка сохранится; результат появится отдельной"
                  + " заметкой.")
          .setNegativeButton("Отмена", null)
          .setPositiveButton(
              "Проанализировать",
              (d, w) -> {
                if (ImageRunner.start(this, editing, key, model, image))
                  toast("Изображение отправлено на анализ");
              })
          .show();
    } catch (Exception e) {
      toast("Не удалось прочитать API-ключ");
    }
  }

  /** Описывает выбранную картинку и дописывает текст в конец этой заметки, ничего не сохраняя. */
  private void describeNoteImage() {
    if (LocalLlm.enabled(this)) {
      toast("Картинки пока доступны только через Polza.AI. Переключите режим в настройках.");
      return;
    }
    if (ImageRunner.running) {
      toast("Изображение уже анализируется");
      return;
    }
    stash();
    String image = selectedImageName();
    long noteId = editing;
    try {
      String key = Secrets.read(this), model = imageModel();
      if (key.isEmpty()) {
        toast("Сначала сохраните API-ключ в настройках");
        return;
      }
      new Ask(this)
          .setTitle("Описать изображение?")
          .setMessage(
              "Картинка будет отправлена в Polza.AI и выбранную модель. С вашего баланса спишется"
                  + " стоимость анализа. Описание добавится в конец этой заметки; проверьте его"
                  + " и сохраните заметку.")
          .setNegativeButton("Отмена", null)
          .setPositiveButton(
              "Описать",
              (d, w) -> {
                if (ImageRunner.describe(this, noteId, key, model, image))
                  toast("Изображение отправлено на описание");
              })
          .show();
    } catch (Exception e) {
      toast("Не удалось прочитать API-ключ");
    }
  }

  /**
   * Дописывает описание в конец заметки. Модель получила только картинку, а не текст заметки,
   * поэтому описание не зависит от версии текста. Если заметку уже закрыли, текст ждёт в черновике.
   */
  private void applyDescription(long origin, String title, String description) {
    if (editorActive && editing == origin && titleField != null && bodyField != null) {
      String body = bodyField.getText().toString().trim();
      boolean blank = body.isEmpty() && titleField.getText().toString().trim().isEmpty();
      bodyField.setText(body.isEmpty() ? description : body + "\n\n" + description);
      if (blank) titleField.setText(title);
      stash();
      toast("Описание добавлено в заметку. Проверьте и сохраните её.");
      return;
    }
    String draft = "draft." + origin + ".";
    Note saved = origin > 0 ? store.note(origin) : null;
    String body = prefs.getString(draft + "body", saved == null ? "" : saved.body).trim();
    String heading = prefs.getString(draft + "title", saved == null ? "" : saved.title).trim();
    prefs
        .edit()
        .putString(draft + "body", body.isEmpty() ? description : body + "\n\n" + description)
        .putString(draft + "title", body.isEmpty() && heading.isEmpty() ? title : heading)
        .apply();
    toast("Описание готово. Откройте заметку, чтобы проверить и сохранить.");
  }

  @Override
  public void imageFinished() {
    if (isDestroyed() || isFinishing()) return;
    String described = ImageRunner.describeText;
    if (!described.isEmpty()) {
      String heading = ImageRunner.describeTitle;
      long origin = ImageRunner.source;
      ImageRunner.describeText = "";
      ImageRunner.describeTitle = "";
      ImageRunner.describeImage = "";
      applyDescription(origin, heading, described);
      return;
    }
    long result = ImageRunner.note;
    String error = ImageRunner.error;
    if (result > 0) {
      ImageRunner.note = 0;
      boolean atSource =
          ("edit".equals(page) && editing == ImageRunner.source)
              || ("home".equals(page) && ImageRunner.source == 0);
      if (atSource) {
        stash();
        if (!persistEditedNote()) return;
        page = "notes";
        tab = 0;
        openNote(result);
      }
      celebrateNote(result);
    } else if (!error.isEmpty()) {
      ImageRunner.error = "";
      toast(error);
    }
  }

  private LinearLayout reliabilityBox;

  /** Ставит будильники будущих напоминаний и показывает пропущенные за время простоя. */
  private void restoreReminders() {
    io.execute(
        () -> {
          try (Store reminders = new Store(getApplicationContext())) {
            List<Note> notes = reminders.notes();
            for (Note n : notes)
              if (n.reminder > System.currentTimeMillis())
                Reminders.schedule(getApplicationContext(), n);
            Reminders.restoreSnoozed(getApplicationContext());
            Reminders.scheduleDigest(getApplicationContext());
            Reminders.deliverMissed(getApplicationContext(), notes);
          }
          CalendarSync.runAsync(getApplicationContext());
        });
  }

  private void statusRow(LinearLayout box, boolean ok, String message, String action, Runnable fix) {
    TextView row = text((ok ? "✓  " : "!  ") + message, 14, ok ? GREEN : RED);
    row.setLineSpacing(dp(3), 1);
    box.addView(row);
    if (!ok && fix != null) {
      gap(box, 6);
      box.addView(button(action, false, fix));
    }
    gap(box, 12);
  }

  /** Блок «всё ли настроено для напоминаний»; пересобирается при возврате из системных настроек. */
  private void fillReliability(LinearLayout box) {
    box.removeAllViews();
    boolean notifications = Reminders.notificationsAllowed(this);
    statusRow(
        box,
        notifications,
        notifications
            ? "Уведомления разрешены"
            : "Уведомления выключены — напоминания не придут",
        "Включить уведомления",
        () -> {
          boolean asked = prefs.getBoolean("notificationsAsked", false);
          if (Build.VERSION.SDK_INT >= 33
              && !asked
              && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                  != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("notificationsAsked", true).apply();
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 42);
            return;
          }
          startActivity(
              new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                  .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
        });
    if (Build.VERSION.SDK_INT >= 31) {
      boolean exact = Reminders.exactAllowed(this);
      statusRow(
          box,
          exact,
          exact
              ? "Точное время напоминаний включено"
              : "Точные будильники выключены — напоминания могут опаздывать на несколько минут",
          "Разрешить точные будильники",
          () ->
              startActivity(
                  new Intent(
                          android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                          Uri.parse("package:" + getPackageName()))));
    }
    boolean battery = Reminders.batteryUnrestricted(this);
    statusRow(
        box,
        battery,
        battery
            ? "Экономия батареи не ограничивает приложение"
            : "Экономия батареи может задерживать напоминания. Если они опаздывают, снимите"
                + " ограничение",
        "Настроить батарею",
        () ->
            startActivity(
                new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
  }

  // ===== Настройки: хаб и четыре подэкрана. Изменения применяются сразу, кнопки «Сохранить» нет. =====

  private static final String[] LEAD_LABELS = {
    "В момент события",
    "За 5 минут",
    "За 10 минут",
    "За 15 минут",
    "За 30 минут",
    "За 1 час",
    "За 2 часа",
    "За 1 день",
    "За 2 дня",
    "За 3 дня",
    "За неделю"
  };
  private static final int MODE_POLZA = 0, MODE_NANO = 1, MODE_GGUF = 2;
  private EditText keyField, modelField;
  private boolean credentialsDirty, advancedOpen;
  private final Handler saveHandler = new Handler(Looper.getMainLooper());
  private final Runnable saveCredentials = this::flushCredentials;

  private boolean isSettings() {
    return page.startsWith("settings");
  }

  private void openSettings(String target) {
    flushCredentials();
    hideKeyboard();
    page = target;
    render();
  }

  /** Ключ и модель сохраняются через паузу после ввода, при уходе с экрана и при сворачивании. */
  private void scheduleCredentials() {
    credentialsDirty = true;
    saveHandler.removeCallbacks(saveCredentials);
    saveHandler.postDelayed(saveCredentials, 700);
  }

  private void flushCredentials() {
    saveHandler.removeCallbacks(saveCredentials);
    if (!credentialsDirty || keyField == null || modelField == null) return;
    credentialsDirty = false;
    try {
      Secrets.save(this, keyField.getText().toString().trim());
      prefs.edit().putString("model", modelField.getText().toString().trim()).apply();
    } catch (Exception e) {
      toast("Не удалось безопасно сохранить ключ");
    }
  }

  private boolean credentialsReady() {
    try {
      return !Secrets.read(this).isEmpty() && !prefs.getString("model", "").trim().isEmpty();
    } catch (Exception e) {
      return false;
    }
  }

  private int aiMode() {
    return !LocalLlm.enabled(this) ? MODE_POLZA : LocalLlm.aicore(this) ? MODE_NANO : MODE_GGUF;
  }

  private void applyBackground() {
    BackgroundAnalysis.configure(this);
    if (prefs.getBoolean("backgroundAi", false) || prefs.getBoolean("managerAi", false))
      BackgroundAnalysis.enqueue(this);
  }

  private void askNotificationPermission() {
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 42);
  }

  private void setAiMode(int mode) {
    if (mode == aiMode()) return;
    flushCredentials();
    if (mode == MODE_GGUF && !LocalLlm.hasModel(this)) {
      toast("Выберите файл GGUF-модели");
      importGguf();
      return;
    }
    SharedPreferences.Editor edit = prefs.edit().putBoolean("localLlm", mode != MODE_POLZA);
    if (mode != MODE_POLZA) edit.putString("localEngine", mode == MODE_NANO ? "aicore" : "gguf");
    edit.apply();
    applyBackground();
    render();
  }

  private void importGguf() {
    if (LocalLlm.busy()) {
      toast("Дождитесь завершения локального ответа");
      return;
    }
    startActivityForResult(
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*"),
        89);
  }

  private void deleteGguf() {
    if (LocalLlm.busy()) {
      toast("Сначала остановите локальный ответ");
      return;
    }
    new Ask(this)
        .setTitle("Удалить GGUF с устройства?")
        .setMessage(
            "Будет удалена только копия модели в приложении. Исходный файл и заметки сохранятся.")
        .setNegativeButton("Отмена", null)
        .setPositiveButton(
            "Удалить",
            (d, w) -> {
              if (LocalLlm.busy()) {
                toast("Локальный движок занят");
                return;
              }
              if (LocalLlm.model(this).delete()) {
                prefs.edit().remove("localModelName").putBoolean("localLlm", false).apply();
                applyBackground();
                toast("Модель удалена. Включён режим Polza.AI.");
                render();
              } else toast("Нет модели для удаления");
            })
        .show();
  }

  /**
   * Строка выбора (вместо Spinner, 0.10.23): поле с выбранным вариантом и стрелкой, по нажатию —
   * лист снизу с выбором одного. {@code pick} получает новый номер, только если он изменился.
   */
  private TextView choiceRow(
      String title, String[] labels, int selected, java.util.function.IntConsumer pick) {
    int[] shown = {Math.max(0, Math.min(selected, labels.length - 1))};
    TextView field = text(labels[shown[0]], 16, INK);
    field.setGravity(Gravity.CENTER_VERTICAL);
    field.setMinHeight(dp(50));
    field.setPadding(dp(16), dp(10), dp(14), dp(10));
    GradientDrawable box = shape(SURFACE, 12);
    box.setStroke(dp(1), LINE);
    field.setBackground(
        new RippleDrawable(android.content.res.ColorStateList.valueOf(0x2028634B), box, null));
    field.setCompoundDrawablesWithIntrinsicBounds(null, null, new Glyph("down", GREEN, 18), null);
    field.setCompoundDrawablePadding(dp(8));
    field.setContentDescription(title + ": " + labels[shown[0]]);
    field.setFocusable(true);
    field.setOnClickListener(
        v -> {
          buzz(0);
          new Ask(this)
              .setTitle(title)
              .setSingleChoiceItems(
                  labels,
                  shown[0],
                  (d, which) -> {
                    d.dismiss();
                    if (which == shown[0]) return;
                    shown[0] = which;
                    field.setText(labels[which]);
                    field.setContentDescription(title + ": " + labels[which]);
                    pick.accept(which);
                  })
              .show();
        });
    return field;
  }

  private Switch toggle(String label, boolean value) {
    Switch s = new Switch(this);
    s.setText(label);
    s.setTextColor(INK);
    s.setMinHeight(dp(52));
    s.setChecked(value);
    return s;
  }

  /** Длинное пояснение прячется за ссылкой «Подробнее», чтобы экран оставался коротким. */
  private TextView infoLink(String title, String message) {
    TextView link = text("Подробнее", 13, GREEN);
    link.setTypeface(null, Typeface.BOLD);
    link.setPadding(0, dp(10), dp(16), dp(10));
    link.setOnClickListener(
        v ->
            new Ask(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Понятно", null)
                .show());
    return link;
  }

  private void subHeader(String title) {
    content.addView(topBar(true, false), new LinearLayout.LayoutParams(-1, dp(44)));
    gap(content, 10);
    heading(title, "");
  }

  private void settingsRow(String title, String summary, String target) {
    LinearLayout row = row();
    row.setGravity(Gravity.CENTER_VERTICAL);
    pad(row, 16);
    row.setBackground(ripple(LIME, 12));
    row.setClickable(true);
    row.setFocusable(true);
    LinearLayout labels = column();
    TextView head = text(title, 17, INK);
    head.setTypeface(null, Typeface.BOLD);
    labels.addView(head);
    TextView detail = text(summary, 13, MUTED);
    detail.setLineSpacing(dp(2), 1);
    labels.addView(detail);
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(text("›", 26, MUTED));
    row.setContentDescription(title + ". " + summary);
    row.setOnClickListener(
        v -> {
          buzz(0);
          openSettings(target);
        });
    content.addView(row, new LinearLayout.LayoutParams(-1, -2));
    gap(content, 10);
  }

  private String aiSummary() {
    int mode = aiMode();
    if (mode == MODE_NANO) return "Gemini Nano на телефоне";
    if (mode == MODE_GGUF)
      return "GGUF · " + prefs.getString("localModelName", "model.gguf");
    String model = prefs.getString("model", "").trim();
    return "Polza.AI · "
        + (model.isEmpty() ? "модель не выбрана" : model)
        + (credentialsReady() || model.isEmpty() ? "" : " · нет ключа");
  }

  private String reminderSummary() {
    if (!Reminders.notificationsAllowed(this)) return "Уведомления выключены — напоминания не придут";
    if (Build.VERSION.SDK_INT >= 31 && !Reminders.exactAllowed(this))
      return "Время может быть неточным";
    int lead = Reminders.leadMinutes(this);
    String digest =
        Reminders.digestEnabled(this)
            ? String.format(
                Locale.US,
                " · сводка в %02d:%02d",
                Reminders.digestMinutes(this) / 60,
                Reminders.digestMinutes(this) % 60)
            : "";
    for (int i = 0; i < Reminders.LEAD_MINUTES.length; i++)
      if (Reminders.LEAD_MINUTES[i] == lead)
        return "Всё работает · " + LEAD_LABELS[i].toLowerCase(new Locale("ru")) + digest;
    return "Всё работает" + digest;
  }

  private String assistantSummary() {
    boolean background = prefs.getBoolean("backgroundAi", false),
        manager = prefs.getBoolean("managerAi", false);
    boolean asks = CheckInJob.enabled(this);
    if (!background && !manager && !asks) return "Выключен";
    List<String> names = new ArrayList<>();
    if (background) names.add("Фоновый помощник");
    if (manager) names.add("менеджер");
    if (asks) names.add("вопросы о планах");
    String on = String.join(", ", names);
    on = on.substring(0, 1).toUpperCase(new Locale("ru")) + on.substring(1);
    return LocalLlm.cloudBackground(this)
        ? on + " включены"
        : on + " · на паузе, пока выбран GGUF";
  }

  // ---- Хаб ----

  private void settings() {
    content.addView(topBar(true, false), new LinearLayout.LayoutParams(-1, dp(44)));
    gap(content, 10);
    heading("Настройки", "");
    gap(content, 6);
    settingsRow("ИИ", aiSummary(), "settings.ai");
    settingsRow("Напоминания и уведомления", reminderSummary(), "settings.reminders");
    settingsRow("Помощник заметок", assistantSummary(), "settings.assistant");
    settingsRow("Синхронизация", PeerSync.summary(this), "settings.sync");
    settingsRow(
        "Приложение",
        "Внешний вид, данные · версия " + appVersion(),
        "settings.app");
  }

  // ---- ИИ ----

  private void modeChoice(int mode, String title, String hint, int selected) {
    boolean on = mode == selected;
    LinearLayout row = row();
    row.setGravity(Gravity.CENTER_VERTICAL);
    pad(row, 14);
    row.setBackground(ripple(on ? LIME : SURFACE, 12));
    row.setClickable(true);
    row.setFocusable(true);
    TextView mark = text(on ? "●" : "○", 20, on ? GREEN : MUTED);
    mark.setPadding(0, 0, dp(12), 0);
    row.addView(mark);
    LinearLayout labels = column();
    TextView head = text(title, 16, INK);
    head.setTypeface(null, Typeface.BOLD);
    labels.addView(head);
    labels.addView(text(hint, 13, MUTED));
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    row.setContentDescription(title + ". " + hint + (on ? ". Выбрано" : ""));
    row.setOnClickListener(
        v -> {
          buzz(0);
          setAiMode(mode);
        });
    content.addView(row, new LinearLayout.LayoutParams(-1, -2));
    gap(content, 8);
  }

  private void settingsAi() {
    subHeader("ИИ");
    int mode = aiMode();
    secondary(content, "Кто отвечает в чате и ищет по заметкам.");
    gap(content, 10);
    modeChoice(MODE_POLZA, "Polza.AI", "В облаке. Нужны ключ и модель.", mode);
    modeChoice(MODE_NANO, "Gemini Nano", "На телефоне через Google AICore. Без ключа.", mode);
    modeChoice(MODE_GGUF, "Своя модель GGUF", "На телефоне, из файла. Только текст.", mode);
    gap(content, 8);
    if (mode == MODE_NANO) {
      secondary(
          content,
          "Нужен поддерживаемый телефон, например Samsung Galaxy S25. Отвечает, пока приложение"
              + " открыто.");
      content.addView(
          infoLink(
              "Gemini Nano",
              "Gemini Nano работает на самом телефоне через системный Google AICore: файл модели"
                  + " импортировать не нужно, ключ и сеть для чата не требуются. Нужны"
                  + " актуальные Google AICore и система, разблокированный экран. Модель"
                  + " отвечает, только пока приложение открыто. Ответ до 256 токенов, запрос"
                  + " ограничен лимитом AICore. Фоновые проверки, менеджер и выжимки Gemini Nano"
                  + " не обслуживает: они идут через Polza.AI, ключ и модель задаются ниже."));
      TextView status = secondary(content, "Состояние Gemini Nano не проверено");
      gap(content, 10);
      content.addView(
          button("Проверить и скачать Gemini Nano", false, () -> checkAiCore(status, true)));
      // Статус читается из системного AICore без скачивания: скачанная модель подхватывается сама.
      checkAiCore(status, false);
    }
    if (mode == MODE_GGUF) {
      secondary(
          content,
          LocalLlm.hasModel(this)
              ? "Модель: "
                  + prefs.getString("localModelName", "model.gguf")
                  + " ("
                  + (LocalLlm.model(this).length() / 1024 / 1024)
                  + " МБ)"
              : "GGUF-модель не выбрана");
      content.addView(
          infoLink(
              "Своя модель GGUF",
              "Текстовый ИИ на телефоне, без Polza.AI. ARM64, CPU, контекст 4096 токенов, ответ"
                  + " до 256 токенов. Начните с небольшой instruct-модели 0.5–1.5B в Q4 до 2 ГБ."
                  + " Понадобятся свободная память и место для копии файла. Картинки и фоновые"
                  + " проверки в этом режиме недоступны: для них выберите Polza.AI или Gemini"
                  + " Nano."));
      content.addView(button("Заменить модель", false, this::importGguf));
      gap(content, 8);
      content.addView(button("Удалить модель", false, this::deleteGguf));
      gap(content, 8);
      secondary(
          content,
          "Фоновые проверки и менеджер при GGUF на паузе. Чтобы они работали, выберите Polza.AI"
              + " или Gemini Nano.");
    }
    if (mode != MODE_GGUF) polzaBlock(mode);
  }

  private void polzaBlock(int mode) {
    section(mode == MODE_POLZA ? "Polza.AI" : "Polza.AI для фоновых проверок", true);
    EditText key = input("API-ключ Polza.AI", false);
    keyField = key;
    key.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    key.setTransformationMethod(PasswordTransformationMethod.getInstance());
    key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    try {
      key.setText(Secrets.read(this));
    } catch (Exception e) {
      toast("Введите API-ключ заново");
    }
    content.addView(key);
    gap(content, 10);
    LinearLayout account = column();
    pad(account, 16);
    account.setBackground(shape(LIME, 12));
    TextView balance = text("Баланс Polza.AI", 20, GREEN);
    account.addView(balance);
    TextView balanceStatus = text("Введите API-ключ, чтобы узнать баланс", 13, MUTED);
    account.addView(balanceStatus);
    gap(account, 10);
    TextView refresh = button("Обновить баланс", false, () -> {});
    account.addView(refresh);
    content.addView(account);
    gap(content, 16);
    boolean[] balanceLoading = {false};
    Runnable updateBalance =
        () -> {
          if (balanceLoading[0]) return;
          String apiKey = key.getText().toString().trim();
          if (apiKey.isEmpty()) {
            balance.setText(R.string.polza_balance);
            balanceStatus.setText(R.string.balance_key_required);
            return;
          }
          balanceLoading[0] = true;
          refresh.setEnabled(false);
          refresh.setAlpha(.5f);
          balance.setText(R.string.polza_balance);
          balanceStatus.setText("Загружаю баланс…");
          io.execute(
              () -> {
                try {
                  java.math.BigDecimal[] values = Polza.balance(apiKey);
                  java.text.NumberFormat money =
                      java.text.NumberFormat.getNumberInstance(new Locale("ru"));
                  money.setMinimumFractionDigits(2);
                  money.setMaximumFractionDigits(8);
                  String amount = money.format(values[0]) + " ₽",
                      available = money.format(values[1]) + " ₽";
                  runOnUiThread(
                      () -> {
                        if (!isDestroyed()
                            && balance.isAttachedToWindow()
                            && apiKey.equals(key.getText().toString().trim())) {
                          balance.setText(getString(R.string.balance_amount, amount));
                          balanceStatus.setText(
                              getString(
                                  R.string.balance_available,
                                  available,
                                  date(System.currentTimeMillis())));
                        }
                      });
                } catch (Exception e) {
                  runOnUiThread(
                      () -> {
                        if (!isDestroyed()
                            && balance.isAttachedToWindow()
                            && apiKey.equals(key.getText().toString().trim())) {
                          balance.setText("Баланс недоступен");
                          balanceStatus.setText(
                              "Проверьте ключ и интернет, затем обновите баланс.");
                        }
                      });
                } finally {
                  runOnUiThread(
                      () -> {
                        balanceLoading[0] = false;
                        refresh.setEnabled(true);
                        refresh.setAlpha(1);
                      });
                }
              });
        };
    refresh.setOnClickListener(v -> updateBalance.run());
    watch(
        key,
        () -> {
          balance.setText(R.string.polza_balance);
          balanceStatus.setText("Обновите баланс для введённого ключа");
          scheduleCredentials();
        });
    if (LocalLlm.cloudBackground(this) && !key.getText().toString().trim().isEmpty())
      refresh.post(updateBalance);
    EditText model = input("ID модели, например openai/gpt-4o-mini", false);
    model.setText(prefs.getString("model", ""));
    modelField = model;
    watch(model, this::scheduleCredentials);
    content.addView(model);
    gap(content, 10);
    TextView load =
        button(
            "Выбрать из каталога моделей",
            false,
            () -> loadModels(key.getText().toString().trim(), model));
    content.addView(load);
    gap(content, 16);
    if (mode == MODE_POLZA) {
      secondary(content, "Модель для картинок");
      EditText imageField = input(Vision.DEFAULT_MODEL, false);
      imageField.setText(prefs.getString("imageModel", ""));
      watch(
          imageField,
          () ->
              prefs
                  .edit()
                  .putString("imageModel", imageField.getText().toString().trim())
                  .apply());
      content.addView(imageField);
      gap(content, 6);
      secondary(
          content,
          "Модель, которая смотрит картинки в чате и в заметках. Нужна с поддержкой изображений и"
              + " хорошим чтением текста. Если пусто, используется "
              + Vision.DEFAULT_MODEL
              + ". Обычная модель выше отвечает на текстовые вопросы.");
      gap(content, 16);
      secondary(content, "Модель для видео YouTube");
      EditText videoField = input(VideoAnalysis.DEFAULT_MODEL, false);
      videoField.setText(prefs.getString("videoModel", ""));
      watch(
          videoField,
          () ->
              prefs
                  .edit()
                  .putString("videoModel", videoField.getText().toString().trim())
                  .apply());
      content.addView(videoField);
      gap(content, 6);
      secondary(
          content,
          "Модель, которая смотрит видео по ссылке, например Gemini. Если пусто, используется "
              + VideoAnalysis.DEFAULT_MODEL
              + ". Самые новые модели Polza.AI может потребовать с большим запасом на балансе.");
      gap(content, 16);
      TextView more = text((advancedOpen ? "▾  " : "▸  ") + "Дополнительно", 15, GREEN);
      more.setTypeface(null, Typeface.BOLD);
      more.setPadding(0, dp(10), 0, dp(10));
      more.setOnClickListener(
          v -> {
            advancedOpen = !advancedOpen;
            flushCredentials();
            render();
          });
      content.addView(more);
      if (advancedOpen) {
        secondary(content, "Максимальная длина ответа");
        String[] choices = {
          "Коротко — до 300 токенов",
          "Обычно — до 2000 токенов",
          "Подробно — до 3000 токенов",
          "Без ограничения — сколько нужно модели"
        };
        content.addView(
            choiceRow(
                "Длина ответа",
                choices,
                ManagerAnalysis.answerMode(prefs.getInt("maxTokens", 0)),
                position ->
                    prefs
                        .edit()
                        .putInt("maxTokens", ManagerAnalysis.ANSWER_TOKENS[position])
                        .apply()));
        chatNote(
            content,
            "Тот же предел берёт фоновая проверка заметок (не меньше 1600). «Без ограничения» нужен"
                + " моделям, которые сначала «размышляют» (например, Qwen3.8 Max): такой ответ"
                + " может стоить в разы дороже, а Polza.AI может заранее придержать на балансе"
                + " крупную сумму.");
        gap(content, 12);
        secondary(
            content,
            "Экономия без подписки приложения\n\n"
                + "Локальный поиск по всем заметкам. До 8 фрагментов и 6 последних сообщений на"
                + " запрос. Повторные одинаковые запросы — из кеша в течение суток. Точная цена"
                + " зависит от выбранной модели.");
      }
    }
  }

  // ---- Напоминания и уведомления ----

  private void settingsReminders() {
    subHeader("Напоминания и уведомления");
    reliabilityBox = column();
    content.addView(reliabilityBox);
    fillReliability(reliabilityBox);
    secondary(content, "Напоминать до события");
    int leadSelection = 0;
    for (int i = 0; i < Reminders.LEAD_MINUTES.length; i++)
      if (Reminders.LEAD_MINUTES[i] == Reminders.leadMinutes(this)) leadSelection = i;
    content.addView(
        choiceRow(
            "Напоминать до события",
            LEAD_LABELS,
            leadSelection,
            position -> {
              prefs.edit().putInt("reminderLead", Reminders.LEAD_MINUTES[position]).apply();
              // Новый интервал сразу применяется ко всем будущим событиям.
              restoreReminders();
            }));
    content.addView(
        infoLink(
            "Как работают напоминания",
            "Интервал применяется ко всем будущим событиям. Если выбранное время уже прошло,"
                + " напоминание придёт в ближайшее время. Уже доставленные напоминания не"
                + " повторяются. В уведомлении можно отложить напоминание: на 10 минут, на час"
                + " или на завтра, 9:00. Если телефон был выключен в нужное время, напоминание"
                + " придёт как пропущенное при включении или открытии приложения (за последние 7"
                + " дней). После принудительной остановки приложения откройте его снова."));
    gap(content, 12);
    Switch calendar = toggle("Синхронизация с календарём", CalendarSync.enabled(this));
    TextView calendarStatus = secondary(content, "");
    Runnable showCalendarStatus =
        () -> {
          String status = CalendarSync.enabled(this) ? CalendarSync.status(this) : "";
          calendarStatus.setText(status);
          calendarStatus.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
        };
    calendar.setOnCheckedChangeListener(
        (v, checked) -> {
          buzz(0);
          if (checked == CalendarSync.enabled(this)) return;
          if (!checked) {
            CalendarSync.setEnabled(this, false);
            CalendarSync.removeAsync(this);
            toast("События убраны из календаря");
          } else if (CalendarSync.permitted(this)) {
            CalendarSync.setEnabled(this, true);
            CalendarSync.runAsync(this);
            toast("Календарь «Мимоходом» подключён");
          } else {
            requestPermissions(
                new String[] {
                  Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR
                },
                CalendarSync.REQUEST_CODE);
          }
          showCalendarStatus.run();
        });
    content.addView(calendar);
    showCalendarStatus.run();
    secondary(
        content,
        "Заметки с напоминанием появляются в отдельном календаре «Мимоходом» (его видно в Календаре"
            + " Samsung) и обновляются при правке и удалении. Оповещает по-прежнему приложение,"
            + " событие в календаре тихое. Если выключить, события из календаря удаляются; заметки"
            + " остаются. Если удалить событие в календаре самому, оно вернётся только после"
            + " правки заметки.");
    gap(content, 12);
    Switch digest = toggle("Утренняя сводка", Reminders.digestEnabled(this));
    TextView digestTime = button("", false, () -> {});
    Runnable showDigestTime =
        () -> {
          int minutes = Reminders.digestMinutes(this);
          digestTime.setText(
              String.format(Locale.US, "Время сводки: %02d:%02d", minutes / 60, minutes % 60));
          digestTime.setVisibility(digest.isChecked() ? View.VISIBLE : View.GONE);
        };
    digest.setOnCheckedChangeListener(
        (v, checked) -> {
          buzz(0);
          prefs.edit().putBoolean("digestOn", checked).apply();
          Reminders.scheduleDigest(this);
          if (checked) askNotificationPermission();
          showDigestTime.run();
        });
    digestTime.setOnClickListener(
        v -> {
          buzz(0);
          timePicker(
              "Время сводки",
              Reminders.digestMinutes(this),
              picked -> {
                prefs.edit().putInt("digestMinutes", picked).apply();
                Reminders.scheduleDigest(this);
                showDigestTime.run();
              });
        });
    content.addView(digest);
    content.addView(digestTime);
    showDigestTime.run();
    secondary(
        content,
        "Одно тихое уведомление утром со списком событий на сегодня. Если событий нет, сводки не"
            + " будет.");
    gap(content, 12);
    content.addView(
        button(
            "Проверочное уведомление",
            false,
            () -> {
              if (Reminders.test(this)) toast("Проверочное уведомление отправлено");
              else toast("Уведомления выключены. Включите их выше.");
            }));
    gap(content, 8);
    content.addView(
        iconButton(
            "Каналы и звук в Android",
            "clock",
            () ->
                startActivity(
                    new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(
                            android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()))));
  }

  // ---- Помощник заметок ----

  private void settingsAssistant() {
    subHeader("Помощник заметок");
    Switch background = toggle("Фоновый помощник ИИ", prefs.getBoolean("backgroundAi", false));
    background.setOnCheckedChangeListener(
        (v, checked) -> {
          buzz(0);
          if (checked && LocalLlm.cloudBackground(this) && !credentialsReady()) {
            toast("Сначала укажите ключ и модель Polza.AI в разделе «ИИ».");
            background.setChecked(false);
            return;
          }
          prefs.edit().putBoolean("backgroundAi", checked).apply();
          applyBackground();
          if (checked) askNotificationPermission();
        });
    content.addView(background);
    Switch manager = toggle(getString(R.string.note_manager), prefs.getBoolean("managerAi", false));
    manager.setOnCheckedChangeListener(
        (v, checked) -> {
          buzz(0);
          if (!checked) {
            prefs.edit().putBoolean("managerAi", false).apply();
            applyBackground();
            return;
          }
          if (prefs.getBoolean("managerAi", false)) return;
          if (LocalLlm.cloudBackground(this) && !credentialsReady()) {
            toast("Сначала укажите ключ и модель Polza.AI в разделе «ИИ».");
            manager.setChecked(false);
            return;
          }
          new Ask(this)
              .setTitle("Включить менеджера заметок?")
              .setMessage(
                  "Выбранные тексты из всей коллекции будут передаваться в Polza.AI примерно"
                      + " каждые 6 часов, даже при закрытом приложении. По умолчанию — 4 платных"
                      + " запроса в сутки; дневной лимит можно снять. До 8 заметок и 12 000"
                      + " символов на запрос. Изменения — только после подтверждения.")
              .setPositiveButton(
                  "Согласен",
                  (d, w) -> {
                    prefs.edit().putBoolean("managerAi", true).apply();
                    applyBackground();
                    askNotificationPermission();
                  })
              .setNegativeButton("Отмена", (d, w) -> manager.setChecked(false))
              .setOnCancelListener(d -> manager.setChecked(false))
              .show();
        });
    content.addView(manager);
    secondary(
        content,
        "Предлагает названия, напоминания, связи и объединения. Запросы идут через Polza.AI, по"
            + " умолчанию до 4 платных в сутки.");
    content.addView(
        infoLink(
            "Как работает помощник",
            "Менеджер предлагает объединения, связи, дела и вопросы. Сводка доступна в заметках."
                + " Уведомления — не чаще раза в сутки.\n\n"
                + "При включении новые и изменённые заметки передаются в Polza.AI даже при"
                + " закрытом приложении. По умолчанию до 4 платных пакетов в сутки; дневной лимит"
                + " можно снять. Проверка примерно раз в 6 часов; Android может отложить её. ИИ"
                + " улучшает автоматические заголовки и предлагает напоминания. События"
                + " назначаются только после подтверждения.\n\n"
                + "Ручная проверка запускает названия, напоминания и объединения независимо от"
                + " переключателей и повторяет записи после ошибок."));
    gap(content, 6);
    showAnalysisStatus();
    content.addView(quietAction("История проверок", this::showAnalysisLog));
    content.addView(limitButton());
    gap(content, 10);
    content.addView(button("Проверить сейчас", false, this::manualAnalysis));
    checkInSettings();
    section("Эксперименты", true);
    Switch attention = toggle("Баллы внимания помощника", prefs.getBoolean("attentionPoints", false));
    attention.setOnCheckedChangeListener(
        (v, checked) -> {
          buzz(0);
          prefs.edit().putBoolean("attentionPoints", checked).apply();
        });
    content.addView(attention);
    secondary(
        content,
        "Помощник реже возвращается к заметкам, которым вы не отвечаете. У каждой новой заметки"
            + " 5–8 баллов внимания: отклонённые предложения и молчание их тратят, открытия, правки"
            + " и ответы — пополняют. На нуле помощник оставляет заметку в покое, пока вы сами к"
            + " ней не вернётесь. Действует только на заметки, созданные после включения.");
  }

  /** Помощник сам пишет первым и по ситуации расспрашивает о делах и планах. */
  private void checkInSettings() {
    section("Вопросы о планах", true);
    Switch ask = toggle("Помощник спрашивает о планах", CheckInJob.enabled(this));
    TextView time = button("", false, () -> {});
    TextView every = button("", false, () -> {});
    TextView now = button("Спросить сейчас", false, () -> {});
    TextView status = text("", 13, MUTED);
    TextView howItWorks =
        text(
            "Каждый день в это время помощник смотрит дела со сроком в ближайшие два дня или давно"
                + " без движения и спрашивает «Как продвигается?» — без запросов к ИИ. Вопросы о"
                + " планах от ИИ — с выбранной частотой. Всего не больше 4 чатов в день.",
            13,
            MUTED);
    Runnable refresh =
        () -> {
          boolean on = ask.isChecked();
          int minutes = CheckInJob.minutes(this);
          time.setText(
              String.format(Locale.US, "Время вопроса: около %02d:%02d", minutes / 60, minutes % 60));
          every.setText(
              "Вопросы от ИИ: " + CheckIn.everyLabel(CheckInJob.every(this)).toLowerCase(new Locale("ru")));
          String report =
              !on || LocalLlm.cloudBackground(this)
                  ? CheckInJob.status(this)
                  : "Вопросы от ИИ на паузе, пока выбран GGUF. «Как продвигается?» работает.";
          status.setText(report);
          status.setVisibility(on && !report.isEmpty() ? View.VISIBLE : View.GONE);
          int visible = on ? View.VISIBLE : View.GONE;
          time.setVisibility(visible);
          every.setVisibility(visible);
          now.setVisibility(visible);
          howItWorks.setVisibility(visible);
        };
    ask.setOnCheckedChangeListener(
        (v, checked) -> {
          buzz(0);
          if (!checked) {
            prefs.edit().putBoolean(CheckInJob.ON, false).apply();
            io.execute(() -> CheckInJob.arm(getApplicationContext()));
            refresh.run();
            return;
          }
          if (CheckInJob.enabled(this)) return;
          if (LocalLlm.cloudBackground(this) && !credentialsReady()) {
            toast("Сначала укажите ключ и модель Polza.AI в разделе «ИИ».");
            ask.setChecked(false);
            return;
          }
          new Ask(this)
              .setTitle("Включить вопросы о планах?")
              .setMessage(
                  "Время от времени помощник будет писать сам: спрашивать о делах и планах, когда и"
                      + " во сколько что-то происходит, с кем и что взять с собой. Вопросы"
                      + " составляются по вашим заметкам, а не из готового списка. Для этого"
                      + " ближайшие события, названия и начало свежих заметок отправляются в"
                      + " Polza.AI — один платный запрос на вопрос, не чаще выбранного"
                      + " интервала. Что помощник запомнил из разговора, он показывает серым в"
                      + " чате, а в конце сам записывает заметку и присылает итог. Если не"
                      + " отвечать, помощник замолчит.")
              .setPositiveButton(
                  "Согласен",
                  (d, w) -> {
                    prefs.edit().putBoolean(CheckInJob.ON, true).apply();
                    CheckInJob.reset(this);
                    io.execute(() -> CheckInJob.arm(getApplicationContext()));
                    askNotificationPermission();
                    refresh.run();
                  })
              .setNegativeButton("Отмена", (d, w) -> ask.setChecked(false))
              .setOnCancelListener(d -> ask.setChecked(false))
              .show();
        });
    time.setOnClickListener(
        v -> {
          buzz(0);
          timePicker(
              "Время вопросов",
              CheckInJob.minutes(this),
              picked -> {
                prefs.edit().putInt(CheckInJob.MINUTES, picked).apply();
                io.execute(() -> CheckInJob.arm(getApplicationContext()));
                refresh.run();
              });
        });
    every.setOnClickListener(
        v -> {
          buzz(0);
          String[] labels = new String[CheckIn.EVERY_DAYS.length];
          int selected = 0;
          for (int i = 0; i < labels.length; i++) {
            labels[i] = CheckIn.everyLabel(CheckIn.EVERY_DAYS[i]);
            if (CheckIn.EVERY_DAYS[i] == CheckInJob.every(this)) selected = i;
          }
          new Ask(this)
              .setTitle("Как часто спрашивать")
              .setSingleChoiceItems(
                  labels,
                  selected,
                  (d, which) -> {
                    prefs.edit().putInt(CheckInJob.EVERY, CheckIn.EVERY_DAYS[which]).apply();
                    io.execute(() -> CheckInJob.arm(getApplicationContext()));
                    d.dismiss();
                    refresh.run();
                  })
              .setNegativeButton("Отмена", null)
              .show();
        });
    now.setOnClickListener(
        v -> {
          // «Как продвигается?» не нужен ключ; вопрос от ИИ без ключа объяснит статус.
          now.setEnabled(false);
          now.setText("Составляю вопрос…");
          Context app = getApplicationContext();
          io.execute(
              () -> {
                long id = 0;
                String error = "";
                try (Store s = new Store(app)) {
                  id = CheckInJob.compose(app, s);
                } catch (Exception e) {
                  error = CheckInJob.errorText(e);
                }
                CheckInJob.arm(app);
                long opened = id;
                String failure = error;
                runOnUiThread(
                    () -> {
                      if (isFinishing() || isDestroyed()) return;
                      if (opened > 0) {
                        chat = opened;
                        tab = 2;
                        page = "chat";
                        render();
                      } else {
                        now.setEnabled(true);
                        now.setText("Спросить сейчас");
                        toast(failure.isEmpty() ? CheckInJob.status(this) : failure);
                        refresh.run();
                      }
                    });
              });
        });
    content.addView(ask);
    secondary(
        content,
        "Помощник сам пишет в ИИ-чат и присылает уведомление: спрашивает о ближайших делах,"
            + " времени, людях и подготовке. Ответьте в чате, и он уточнит детали и предложит"
            + " сохранить заметку.");
    content.addView(
        infoLink(
            "Как работают вопросы",
            "Вопрос составляет модель по ситуации: смотрит на ближайшие события, свежие заметки,"
                + " день недели и ваши прошлые ответы. Готового набора вопросов нет.\n\n"
                + "Пока вы отвечаете, помощник серым отмечает, что запомнил. Когда сведений"
                + " хватает, он сам записывает заметку (или обновляет её, если вы её не правили) и"
                + " присылает итог со ссылкой. Напоминание он не ставит: его можно добавить в"
                + " заметке.\n\n"
                + "Уведомление приходит около выбранного времени и не чаще выбранного интервала."
                + " Если вы как раз пишете в чате, вопрос пропускается. Если не отвечать на вопросы,"
                + " интервал растёт, а после трёх подряд без ответа помощник замолкает, пока вы не"
                + " ответите в чате.\n\n"
                + "Каждый вопрос — один платный запрос к Polza.AI с коротким ответом. Повторов после"
                + " ошибки нет: следующая попытка в следующий срок или кнопкой «Спросить сейчас»."
                + " Работает при Polza.AI и Gemini Nano; при GGUF на паузе.\n\n"
                + "«Как продвигается?» — отдельно: каждый день в выбранное время помощник смотрит дела"
                + " со сроком в ближайшие два дня (или только что прошедшим) и дела, которые неделю"
                + " не трогали, и спрашивает о них с кнопками «Сделано / В процессе / Отложить / Не"
                + " актуально». Запроса к ИИ нет, ключ не нужен. О каждом деле — не чаще раза в три"
                + " дня."));
    content.addView(time);
    content.addView(every);
    gap(content, 6);
    content.addView(howItWorks);
    gap(content, 6);
    content.addView(status);
    gap(content, 6);
    content.addView(now);
    refresh.run();
  }

  // ---- Синхронизация ----

  private void settingsSync() {
    subHeader("Синхронизация");
    secondary(
        content,
        "Заметки, папки и картинки обмениваются с компьютером (Windows, macOS) напрямую по Wi-Fi,"
            + " без сервера. Телефон и компьютер должны быть в одной сети, а на компьютере открыто"
            + " «Мимоходом» с включённой синхронизацией.");
    gap(content, 12);
    List<SyncStore.Peer> peers;
    try {
      peers = PeerSync.peers(this);
    } catch (RuntimeException e) {
      peers = new ArrayList<>();
    }
    for (SyncStore.Peer peer : peers) syncPeerCard(peer);
    content.addView(button("Подключить компьютер", peers.isEmpty(), this::connectComputer));
    gap(content, 10);
    if (!peers.isEmpty()) {
      content.addView(
          button(
              PeerSync.running() ? "Идёт обмен…" : "Обменяться сейчас",
              true,
              () -> {
                if (PeerSync.running()) return;
                PeerSync.runAsync(this, true);
                toast("Обмен запущен");
                render();
              }));
      gap(content, 10);
    }
    String status = PeerSync.status(this);
    if (!status.isEmpty()) secondary(content, status);
    gap(content, 10);
    content.addView(
        infoLink(
            "Как это работает",
            "Обмен идёт напрямую между устройствами и шифруется общим ключом из кода"
                + " сопряжения (AES-256-GCM). Облака и аккаунта нет. Обмен происходит, когда вы"
                + " открываете приложение и когда выходите из него; кнопка «Обменяться сейчас»"
                + " запускает его сразу.\n\n"
                + "Если одну заметку изменили на двух устройствах до обмена, остаётся более поздняя"
                + " версия, а другая сохраняется отдельной заметкой «… (копия при конфликте)».\n\n"
                + "Код сопряжения действует 10 минут и срабатывает один раз. Кто его увидел, получает"
                + " доступ к заметкам, пока вы не нажмёте «Забыть» у устройства. ИИ-чат пока не"
                + " синхронизируется."));
  }

  private void syncPeerCard(SyncStore.Peer peer) {
    LinearLayout card = column();
    pad(card, 14);
    card.setBackground(shape(SURFACE, 12));
    TextView name = text(peer.name, 16, INK);
    name.setTypeface(null, Typeface.BOLD);
    card.addView(name);
    card.addView(
        text(
            peer.lastSync == 0 ? "Обмена ещё не было" : "Последний обмен: " + PeerSync.time(peer.lastSync),
            13,
            MUTED));
    gap(card, 8);
    LinearLayout actions = row();
    actions.addView(
        compactButton(
            "Обменяться",
            false,
            () -> {
              if (PeerSync.running()) return;
              PeerSync.runAsync(this, true);
              toast("Обмен запущен");
              render();
            }));
    TextView forget =
        compactButton(
            "Забыть",
            false,
            () ->
                new Ask(this)
                    .setTitle("Забыть «" + peer.name + "»?")
                    .setMessage(
                        "Заметки на обоих устройствах останутся. Чтобы подключить компьютер снова,"
                            + " понадобится новый код сопряжения.")
                    .setPositiveButton(
                        "Забыть",
                        (d, w) -> {
                          PeerSync.forget(this, peer.device);
                          page = "settings.sync";
                          render();
                        })
                    .setNegativeButton("Отмена", null)
                    .show());
    LinearLayout.LayoutParams gapParams = new LinearLayout.LayoutParams(-2, -2);
    gapParams.setMarginStart(dp(8));
    actions.addView(forget, gapParams);
    card.addView(actions);
    content.addView(card, new LinearLayout.LayoutParams(-1, -2));
    gap(content, 10);
  }

  private void connectComputer() {
    new Ask(this)
        .setTitle("Подключить компьютер")
        .setMessage(
            "На компьютере откройте «Мимоходом»: Настройки → Синхронизация → «Показать код"
                + " сопряжения».")
        .setPositiveButton("Сканировать QR", (d, w) -> scanPairCode())
        .setNegativeButton("Ввести код", (d, w) -> typePairCode())
        .setNeutralButton("Отмена", null)
        .show();
  }

  private void scanPairCode() {
    try {
      com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this)
          .startScan()
          .addOnSuccessListener(
              barcode -> {
                String value = barcode.getRawValue();
                if (value != null) pairWith(value);
              })
          .addOnFailureListener(
              e -> {
                // Закрытие сканера пользователем ошибкой не считаем.
                if (e instanceof com.google.mlkit.common.MlKitException
                    && ((com.google.mlkit.common.MlKitException) e).getErrorCode() == 201) return;
                toast("Сканер недоступен. Введите код вручную.");
                typePairCode();
              });
    } catch (Throwable e) {
      toast("Сканер недоступен. Введите код вручную.");
      typePairCode();
    }
  }

  private void typePairCode() {
    EditText field = new EditText(this);
    field.setHint("mimohodom://pair?…");
    field.setSingleLine(false);
    field.setMinLines(3);
    field.setTextColor(INK);
    try {
      android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
      if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
        CharSequence clip = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (clip != null && clip.toString().contains("mimohodom://pair")) field.setText(clip);
      }
    } catch (RuntimeException ignored) {
    }
    new Ask(this)
        .setTitle("Код сопряжения")
        .setMessage("Вставьте строку, которую показывает компьютер.")
        .setView(field)
        .setPositiveButton("Подключить", (d, w) -> pairWith(field.getText().toString()))
        .setNegativeButton("Отмена", null)
        .show();
  }

  private void pairWith(String code) {
    toast("Подключаюсь к компьютеру…");
    PeerSync.pair(
        this,
        code,
        message ->
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  toast(message.length() > 120 ? message.substring(0, 120) + "…" : message);
                  if ("settings.sync".equals(page)) {
                    stash();
                    render();
                  }
                }));
  }

  // ---- Приложение ----

  private void settingsApp() {
    subHeader("Приложение");
    section("Внешний вид", false);
    secondary(content, "Тема");
    gap(content, 6);
    String themeMode = prefs.getString("theme", "auto");
    LinearLayout themes = row();
    String[][] themeChoices = {{"auto", "Как в системе"}, {"light", "Светлая"}, {"dark", "Тёмная"}};
    for (int i = 0; i < themeChoices.length; i++) {
      String value = themeChoices[i][0];
      TextView choice =
          compactButton(
              themeChoices[i][1],
              value.equals(themeMode),
              () -> {
                if (value.equals(prefs.getString("theme", "auto"))) return;
                prefs.edit().putString("theme", value).apply();
                // Палитра выбирается при создании экрана, поэтому окно пересоздаётся.
                recreate();
              });
      choice.setContentDescription("Тема: " + themeChoices[i][1]);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
      if (i > 0) params.setMarginStart(dp(6));
      themes.addView(choice, params);
    }
    content.addView(themes);
    gap(content, 4);
    secondary(
        content,
        "«Как в системе» следует за светлой или тёмной темой телефона и переключается сама.");
    gap(content, 8);
    Switch large = toggle("Крупный текст заметок", prefs.getBoolean("large", false));
    large.setOnCheckedChangeListener(
        (v, checked) -> {
          prefs.edit().putBoolean("large", checked).apply();
          buzz(0);
        });
    content.addView(large);
    Switch haptics = toggle("Вибрация в приложении", prefs.getBoolean("haptics", true));
    // Включение сразу показывает отклик.
    haptics.setOnCheckedChangeListener(
        (v, checked) -> {
          prefs.edit().putBoolean("haptics", checked).apply();
          vibrate(0, checked);
        });
    content.addView(haptics);
    secondary(
        content,
        "Короткий отклик при нажатиях, переходах между вкладками и сохранении заметки.");
    section("Данные", true);
    content.addView(
        button(
            "Экспортировать заметки",
            false,
            () ->
                startActivityForResult(
                    new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "mimohodom-notes.json"),
                    77)));
    gap(content, 8);
    content.addView(button("Импортировать заметки", false, this::pickNotesImport));
    secondary(
        content,
        "JSON-копия добавит заметки, папки, напоминания, планы и колоды. Существующие записи"
            + " не удаляются, совпадения пропускаются. Фотографии и чаты в файл не входят.");
    gap(content, 12);
    TextView imagesUsage = secondary(content, "");
    Runnable showUsage =
        () -> {
          long[] usage = ImageFiles.usage(this);
          imagesUsage.setText(
              usage[0] == 0
                  ? "Изображения: нет сохранённых файлов"
                  : "Изображения: "
                      + ImageCleanup.files((int) usage[0])
                      + " · "
                      + ImageCleanup.size(usage[1]));
        };
    showUsage.run();
    gap(content, 8);
    content.addView(
        button(
            "Очистить неиспользуемые изображения",
            false,
            () ->
                io.execute(
                    () -> {
                      int removed = ImageFiles.cleanup(getApplicationContext(), 0);
                      runOnUiThread(
                          () -> {
                            if (isDestroyed()) return;
                            showUsage.run();
                            toast(
                                removed == 0
                                    ? "Лишних файлов нет"
                                    : "Удалено: " + ImageCleanup.files(removed));
                          });
                    })));
    section("О приложении", true);
    secondary(
        content,
        "Мимоходом "
            + appVersion()
            + "\n"
            + "Заметки и чаты хранятся на устройстве. API-ключ зашифрован Android Keystore."
            + " Удаление приложения удалит локальные данные; предварительно экспортируйте"
            + " заметки.");
  }

  /** Заголовок раздела настроек; у всех, кроме первого, сверху тонкая линия. */
  private void section(String title, boolean rule) {
    if (rule) {
      gap(content, 22);
      View line = new View(this);
      line.setBackgroundColor(LINE);
      content.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
      gap(content, 16);
    }
    TextView header = text(title, 17, GREEN);
    header.setTypeface(null, Typeface.BOLD);
    if (Build.VERSION.SDK_INT >= 28) header.setAccessibilityHeading(true);
    content.addView(header);
    gap(content, 10);
  }

  private String appVersion() {
    try {
      return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (Exception e) {
      return "";
    }
  }

  private void loadModels(String key, EditText field) {
    if (key.isEmpty()) {
      toast("Сначала введите API-ключ");
      return;
    }
    toast("Загружаю каталог…");
    io.execute(
        () -> {
          try {
            List<String> ids = Polza.models(key);
            runOnUiThread(
                () -> {
                  if (isDestroyed() || !isSettings()) return;
                  LinearLayout dialog = column();
                  EditText filter = input("Поиск модели", false);
                  dialog.addView(filter);
                  gap(dialog, 8);
                  ListView list = new ListView(this);
                  list.setDivider(new ColorDrawable(LINE));
                  list.setDividerHeight(1);
                  list.setSelector(new ColorDrawable(android.graphics.Color.TRANSPARENT));
                  // Строки списка в стиле приложения вместо системного simple_list_item_1.
                  ArrayAdapter<String> adapter =
                      new ArrayAdapter<String>(this, 0, new ArrayList<>(ids)) {
                        @Override
                        public View getView(int position, View convert, ViewGroup parent) {
                          TextView row =
                              convert instanceof TextView ? (TextView) convert : text("", 15, INK);
                          row.setText(getItem(position));
                          row.setMinHeight(dp(48));
                          row.setGravity(Gravity.CENTER_VERTICAL);
                          row.setPadding(dp(10), dp(6), dp(10), dp(6));
                          row.setBackground(ripple(android.graphics.Color.TRANSPARENT, 10));
                          return row;
                        }
                      };
                  list.setAdapter(adapter);
                  dialog.addView(list, new LinearLayout.LayoutParams(-1, dp(350)));
                  Popup picker =
                      new Ask(this)
                          .asSheet()
                          .setTitle("Модели Polza.AI")
                          .setView(dialog)
                          .setNegativeButton("Отмена", null)
                          .create();
                  watch(filter, () -> adapter.getFilter().filter(filter.getText().toString()));
                  list.setOnItemClickListener(
                      (a, v, p, id) -> {
                        field.setText(adapter.getItem(p));
                        picker.dismiss();
                      });
                  picker.show();
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (!isDestroyed())
                    toast("Не удалось загрузить модели. Проверьте ключ и интернет.");
                });
          }
        });
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 89 && result == RESULT_OK && data != null && data.getData() != null) {
      android.net.Uri uri = data.getData();
      toast("Копирую GGUF в приложение. Большая модель может занять несколько минут.");
      heavy.execute(
          () -> {
            try {
              String name = "model.gguf";
              try (android.database.Cursor cursor =
                  getContentResolver()
                      .query(
                          uri,
                          new String[] {android.provider.OpenableColumns.DISPLAY_NAME},
                          null,
                          null,
                          null)) {
                if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
              }
              LocalLlm.importModel(getApplicationContext(), uri);
              prefs
                  .edit()
                  .putString("localModelName", name)
                  .putBoolean("localLlm", true)
                  .putString("localEngine", "gguf")
                  .apply();
              BackgroundAnalysis.configure(getApplicationContext());
              runOnUiThread(
                  () -> {
                    if (!isDestroyed()) {
                      toast("GGUF скопирован. Локальный режим включён.");
                      if (isSettings()) render();
                    }
                  });
            } catch (Exception e) {
              runOnUiThread(
                  () -> {
                    if (!isDestroyed())
                      toast(
                          e.getMessage() == null
                              ? "Не удалось импортировать GGUF"
                              : e.getMessage());
                  });
            }
          });
      return;
    }
    if ((request == 88 || request == 91) && result == RESULT_OK && data != null) {
      List<Uri> picked = new ArrayList<>();
      ClipData clip = data.getClipData();
      if (clip != null)
        for (int i = 0; i < clip.getItemCount(); i++) picked.add(clip.getItemAt(i).getUri());
      else if (data.getData() != null) picked.add(data.getData());
      if (!picked.isEmpty()) attachImages(picked, pickerPage, pickerTarget, null);
      return;
    }
    if (request == 90) {
      java.io.File shot = takeShot();
      if (result == RESULT_OK && shot != null && shot.length() > 0)
        attachImages(List.of(Uri.fromFile(shot)), pickerPage, pickerTarget, shot);
      else if (shot != null) shot.delete();
      return;
    }
    if (request == 77 && result == RESULT_OK && data != null && data.getData() != null) {
      exportNotes(data.getData());
      return;
    }
    if (request == 78 && result == RESULT_OK && data != null && data.getData() != null) {
      readNotesImport(data.getData());
    }
  }

  /**
   * Сжимает выбранные или снятые картинки и прикрепляет их к заметке или новому сообщению чата.
   * Лишние сверх лимита пропускаются, об этом сообщается одним уведомлением.
   */
  private void attachImages(
      List<Uri> uris, String targetPage, long target, java.io.File temporary) {
    toast(uris.size() > 1 ? "Подготавливаю изображения…" : "Подготавливаю изображение…");
    io.execute(
        () -> {
          int added = 0, failed = 0, skipped = 0;
          boolean chatTarget = targetPage.equals("chat");
          String list = "";
          try {
            // Источник правды для заметки — черновик, его редактор ведёт при каждом изменении;
            // без него берём сохранённую заметку. Для чата — список вложений нового сообщения.
            if (chatTarget) list = prefs.getString("chatImage." + target, "");
            else {
              String base = prefs.getString("draft." + target + ".image", null);
              if (base == null && target > 0)
                try (Store images = new Store(getApplicationContext())) {
                  Note existing = images.note(target);
                  base = existing == null ? "" : existing.image;
                }
              list = base == null ? "" : base;
            }
            int max = chatTarget ? ChatImages.MAX_ATTACH : ImageList.MAX;
            for (Uri uri : uris) {
              if (ImageList.count(list) >= max) {
                skipped++;
                continue;
              }
              try {
                String imported = ImageFiles.importImage(getApplicationContext(), uri);
                list = ImageList.add(list, imported);
                added++;
              } catch (Exception | OutOfMemoryError e) {
                failed++;
              }
            }
            if (added > 0) {
              if (chatTarget) prefs.edit().putString("chatImage." + target, list).apply();
              else prefs.edit().putString("draft." + target + ".image", list).apply();
            }
          } catch (Exception | OutOfMemoryError e) {
            failed = uris.size() - added;
          } finally {
            if (temporary != null) temporary.delete();
          }
          String result = list;
          int addedNow = added, failedNow = failed, skippedNow = skipped;
          runOnUiThread(
              () -> {
                if (isDestroyed()) return;
                if (failedNow > 0)
                  toast(
                      addedNow == 0
                          ? "Не удалось прикрепить изображение. Выберите картинку до 25 МБ."
                          : "Добавлено " + addedNow + ", не удалось прикрепить " + failedNow + ".");
                else if (skippedNow > 0)
                  toast(
                      "Добавлено "
                          + addedNow
                          + ", остальные не поместились: "
                          + (chatTarget ? ChatImages.MAX_ATTACH : ImageList.MAX)
                          + " максимум.");
                if (addedNow == 0) return;
                if (chatTarget && page.equals("chat") && chat == target) {
                  stash();
                  render();
                } else if (!chatTarget && editorActive && editing == target) {
                  noteImage = result;
                  selectedImage = ImageList.count(result) - 1;
                  stash();
                  render();
                }
              });
        });
  }

  private void pickNotesImport() {
    if (importingNotes) {
      toast("Дождитесь завершения импорта");
      return;
    }
    startActivityForResult(
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json"),
        78);
  }

  private void readNotesImport(Uri uri) {
    importingNotes = true;
    toast("Читаю резервную копию…");
    io.execute(
        () -> {
          try {
            NotesImport.Backup backup = NotesImport.parse(readNotesImportText(uri));
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  String message =
                      "Найдено заметок: "
                          + backup.notes.size()
                          + "\nПапок: "
                          + backup.folders.size()
                          + " · колод: "
                          + backup.groups.size()
                          + "\nС напоминаниями: "
                          + backup.reminders()
                          + " · с планами: "
                          + backup.plans()
                          + "\n\nСуществующие заметки не удаляются. Неизменённые совпадения"
                          + " пропускаются. Фотографии, чаты и предложения ИИ не переносятся.";
                  new Ask(this)
                      .setTitle("Импортировать резервную копию?")
                      .setMessage(message)
                      .setNegativeButton("Отмена", (d, w) -> importingNotes = false)
                      .setOnCancelListener(d -> importingNotes = false)
                      .setPositiveButton("Импортировать", (d, w) -> applyNotesImport(backup))
                      .show();
                });
          } catch (Exception | OutOfMemoryError e) {
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  importingNotes = false;
                  String detail = e instanceof OutOfMemoryError ? "Файл слишком велик" : e.getMessage();
                  toast(detail == null || detail.isEmpty() ? "Не удалось импортировать заметки" : detail);
                });
          }
        });
  }

  private String readNotesImportText(Uri uri) throws java.io.IOException {
    try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
      if (input == null) throw new java.io.IOException("Не удалось открыть файл");
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[16 * 1024];
      int total = 0, read;
      while ((read = input.read(buffer)) >= 0) {
        total += read;
        if (total > NotesImport.MAX_FILE_BYTES)
          throw new java.io.IOException("Файл больше 32 МБ");
        out.write(buffer, 0, read);
      }
      return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  private void applyNotesImport(NotesImport.Backup backup) {
    io.execute(
        () -> {
          try {
            android.content.Context app = getApplicationContext();
            NotesImport.Result result;
            List<Note> imported = new ArrayList<>();
            try (Store store = new Store(app)) {
              result = store.importNotes(backup);
              for (long id : result.noteIds) {
                Note note = store.note(id);
                if (note != null) imported.add(note);
              }
            }
            // Транзакция уже завершена: системные побочные эффекты не попадут в откат базы.
            for (Note note : imported) Reminders.schedule(app, note);
            if (!imported.isEmpty()) CalendarSync.runAsync(app);
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  importingNotes = false;
                  toast(result.message());
                  if (isSettings()) render();
                });
          } catch (Exception | OutOfMemoryError e) {
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  importingNotes = false;
                  toast("Импорт отменён, существующие заметки не изменены");
                });
          }
        });
  }

  private void exportNotes(Uri uri) {
    {
      io.execute(
          () -> {
            try (Store exportStore = new Store(getApplicationContext())) {
              JSONArray notes = new JSONArray();
              for (Note n : exportStore.notes())
                notes.put(
                    new JSONObject()
                        .put("id", n.id)
                        .put("title", n.title)
                        .put("body", n.body)
                        .put("created", n.created)
                        .put("updated", n.updated)
                        .put("reminder", n.reminder)
                        .put("repeat", n.repeat)
                        .put("reminderTimes", n.reminderTimes)
                        .put("lead", n.lead)
                        .put("folder", n.folder)
                        .put("plan", exportStore.plan(n.id))
                        .put("metadata", Metadata.read(n.metadata)));
              JSONArray folderList = new JSONArray();
              for (FolderStore.Folder f : new FolderStore(exportStore).list())
                folderList.put(
                    new JSONObject().put("id", f.id).put("name", f.name).put("auto", f.auto));
              JSONObject export =
                  new JSONObject()
                      .put("version", 4)
                      .put("folders", folderList)
                      .put("notes", notes)
                      .put("groups", new ManagerStore(exportStore).export("note_groups"))
                      .put("members", new ManagerStore(exportStore).export("note_members"))
                      .put("proposals", new ManagerStore(exportStore).export("manager_proposals"));
              try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException();
                out.write(export.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
              }
              runOnUiThread(() -> toast("Заметки экспортированы"));
            } catch (Exception e) {
              runOnUiThread(() -> toast("Не удалось экспортировать заметки"));
            }
          });
    }
  }

  // Форматтеры создаются один раз: на каждую карточку списка их раньше строили заново.
  private final java.text.SimpleDateFormat dateFormat =
      new java.text.SimpleDateFormat("d MMM yyyy, HH:mm", new Locale("ru"));
  private final java.text.SimpleDateFormat shortDateFormat =
      new java.text.SimpleDateFormat("d MMM yyyy", new Locale("ru"));

  private static String formatted(java.text.SimpleDateFormat format, long ms) {
    synchronized (format) {
      format.setTimeZone(TimeZone.getDefault());
      return format.format(new Date(ms));
    }
  }

  private String date(long ms) {
    return formatted(dateFormat, ms);
  }

  private String shortDate(long ms) {
    return formatted(shortDateFormat, ms);
  }

  private String noteCardDate(Note n) {
    String created = shortDate(n.created), updated = shortDate(n.updated);
    if (n.updated <= n.created || created.equals(updated)) return created;
    java.time.LocalDate day = java.time.Instant.ofEpochMilli(n.updated).atZone(ZoneId.systemDefault()).toLocalDate();
    java.time.LocalDate today = java.time.LocalDate.now();
    if (day.equals(today)) return "Изменено сегодня";
    if (day.equals(today.minusDays(1))) return "Изменено вчера";
    return "Изменено " + updated;
  }

  /** Проверяет AICore и при необходимости скачивает Gemini Nano. Работает вне UI-потока. */
  private void checkAiCore(TextView status, boolean allowDownload) {
    if (LocalLlm.busy()) {
      if (allowDownload) toast("Дождитесь завершения локального ответа");
      return;
    }
    status.setText("Проверяю Gemini Nano…");
    // Отдельный поток: скачивание модели может идти долго и не должно блокировать общую очередь.
    new Thread(
            () -> {
              String result;
              try {
                int state = AiCore.status();
                if (allowDownload
                    && state == com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE) {
                  runOnUiThread(() -> status.setText("Скачиваю Gemini Nano через AICore…"));
                  AiCore.download(text -> runOnUiThread(() -> status.setText(text)));
                  state = AiCore.status();
                }
                result = AiCore.describe(state);
              } catch (Exception e) {
                result =
                    e.getMessage() == null ? "Не удалось проверить Gemini Nano" : e.getMessage();
              }
              String shown = result;
              runOnUiThread(
                  () -> {
                    if (!isDestroyed() && status.isAttachedToWindow()) status.setText(shown);
                  });
            },
            "aicore-check")
        .start();
  }

  private View snack;

  /**
   * Подсказка внизу (вместо Toast, 0.10.23): тёмная плашка над нижней панелью, сама гаснет через
   * несколько секунд, исчезает по касанию. Лежит прямо в окне, поэтому переживает {@code render()}.
   */
  private void toast(String message) {
    if (isFinishing() || getWindow() == null) return;
    ViewGroup decor = (ViewGroup) getWindow().getDecorView();
    if (snack != null) decor.removeView(snack);
    LinearLayout bar = row();
    bar.setPadding(dp(16), dp(12), dp(16), dp(12));
    bar.setBackground(shape(INK, 14));
    bar.setElevation(dp(8));
    TextView words = text(message, 14, PAPER);
    words.setLineSpacing(dp(2), 1);
    bar.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    bar.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    lp.leftMargin = dp(22);
    lp.rightMargin = dp(22);
    boolean dockShown = dock != null && dock.getVisibility() == View.VISIBLE;
    lp.bottomMargin = (root != null ? root.getPaddingBottom() : navInset) + dp(dockShown ? 96 : 16);
    View shown = bar;
    snack = bar;
    Runnable hide =
        () -> {
          if (snack != shown) return;
          snack = null;
          if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            decor.removeView(shown);
            return;
          }
          shown
              .animate()
              .alpha(0)
              .translationY(dp(8))
              .setDuration(160)
              .withEndAction(() -> decor.removeView(shown))
              .start();
        };
    bar.setOnClickListener(v -> hide.run());
    decor.addView(bar, lp);
    if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
      bar.setAlpha(0);
      bar.setTranslationY(dp(12));
      bar.animate()
          .alpha(1)
          .translationY(0)
          .setDuration(200)
          .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
          .start();
    }
    bar.postDelayed(hide, Math.min(6000, 2800 + message.length() * 30L));
  }

  private void hideKeyboard() {
    getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(), 0);
  }

  // ── Свои всплывающие окна (0.10.23) вместо AlertDialog, PopupMenu и выбора даты Android ──

  /** Кнопка с действием «удалить»: красная, а у окна по центру — значок корзины сверху. */
  private static boolean destructive(CharSequence label) {
    if (label == null) return false;
    String l = label.toString();
    return l.startsWith("Удал") || l.startsWith("Очист") || l.startsWith("Забыть") || l.startsWith("Стер");
  }

  /**
   * Замена {@code AlertDialog.Builder} с теми же методами. Список, выбор одного и нескольких —
   * лист снизу, остальное — карточка по центру; цвета, шрифты и скругления приложения.
   */
  private final class Ask {
    CharSequence title, message, pos, neg, neu;
    DialogInterface.OnClickListener posL, negL, neuL, itemsL;
    DialogInterface.OnMultiChoiceClickListener multiL;
    DialogInterface.OnCancelListener cancelL;
    CharSequence[] items;
    boolean[] multi;
    int checked = -2;
    View view;
    boolean sheet;

    Ask(Context context) {}

    Ask setTitle(CharSequence value) {
      title = value;
      return this;
    }

    Ask setMessage(CharSequence value) {
      message = value;
      return this;
    }

    Ask setView(View value) {
      view = value;
      return this;
    }

    /** Показать содержимое листом снизу (выбор даты, длинные формы). */
    Ask asSheet() {
      sheet = true;
      return this;
    }

    Ask setItems(CharSequence[] list, DialogInterface.OnClickListener listener) {
      items = list;
      itemsL = listener;
      sheet = true;
      return this;
    }

    Ask setSingleChoiceItems(CharSequence[] list, int selected, DialogInterface.OnClickListener listener) {
      items = list;
      checked = selected;
      itemsL = listener;
      sheet = true;
      return this;
    }

    Ask setMultiChoiceItems(
        CharSequence[] list, boolean[] state, DialogInterface.OnMultiChoiceClickListener listener) {
      items = list;
      multi = state == null ? new boolean[list.length] : state;
      multiL = listener;
      sheet = true;
      return this;
    }

    Ask setPositiveButton(CharSequence label, DialogInterface.OnClickListener listener) {
      pos = label;
      posL = listener;
      return this;
    }

    Ask setNegativeButton(CharSequence label, DialogInterface.OnClickListener listener) {
      neg = label;
      negL = listener;
      return this;
    }

    Ask setNeutralButton(CharSequence label, DialogInterface.OnClickListener listener) {
      neu = label;
      neuL = listener;
      return this;
    }

    Ask setOnCancelListener(DialogInterface.OnCancelListener listener) {
      cancelL = listener;
      return this;
    }

    Popup create() {
      return new Popup(this);
    }

    Popup show() {
      Popup p = create();
      p.show();
      return p;
    }
  }

  /** Окно, которое строит {@link Ask}: своё затемнение, карточка или лист, плавное появление. */
  private final class Popup extends Dialog {
    final Ask a;
    final Map<Integer, TextView> buttons = new HashMap<>();
    final FrameLayout frameView, holder;
    final View scrim;
    final LinearLayout panel;
    final List<Runnable> marks = new ArrayList<>();
    final int panelBottom;
    boolean closing;

    Popup(Ask a) {
      super(MainActivity.this, android.R.style.Theme_Translucent_NoTitleBar);
      this.a = a;
      frameView = new FrameLayout(MainActivity.this);
      scrim = new View(MainActivity.this);
      scrim.setBackgroundColor(dark ? 0x99000000 : 0x6B1E3028);
      scrim.setOnClickListener(v -> cancel());
      scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      frameView.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
      holder = new FrameLayout(MainActivity.this);
      frameView.addView(holder, new FrameLayout.LayoutParams(-1, -1));
      float share = a.sheet ? 0.88f : 1f;
      panel =
          new LinearLayout(MainActivity.this) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
              int size = View.MeasureSpec.getSize(heightSpec);
              if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED)
                heightSpec =
                    View.MeasureSpec.makeMeasureSpec(Math.round(size * share), View.MeasureSpec.AT_MOST);
              super.onMeasure(widthSpec, heightSpec);
            }
          };
      panel.setOrientation(LinearLayout.VERTICAL);
      panel.setClickable(true);
      FrameLayout.LayoutParams panelParams;
      if (a.sheet) {
        GradientDrawable sheetShape = new GradientDrawable();
        sheetShape.setColor(SURFACE);
        float r = dp(20);
        sheetShape.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(sheetShape);
        panelBottom = dp(20);
        panel.setPadding(dp(12), dp(8), dp(12), panelBottom);
        panelParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
      } else {
        panel.setBackground(shape(SURFACE, 20));
        panelBottom = dp(18);
        panel.setPadding(dp(20), dp(22), dp(20), panelBottom);
        panelParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        panelParams.leftMargin = dp(22);
        panelParams.rightMargin = dp(22);
        panelParams.topMargin = dp(16);
        panelParams.bottomMargin = dp(16);
      }
      panel.setElevation(dp(12));
      holder.addView(panel, panelParams);
      build();
      setContentView(frameView, new ViewGroup.LayoutParams(-1, -1));
      Window w = getWindow();
      if (w != null) {
        w.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setWindowAnimations(0);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false);
      }
      frameView.setOnApplyWindowInsetsListener(
          (v, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
              android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
              android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
              left = bars.left;
              top = bars.top;
              right = bars.right;
              bottom = Math.max(bars.bottom, ime.bottom);
            } else {
              left = insets.getSystemWindowInsetLeft();
              top = insets.getSystemWindowInsetTop();
              right = insets.getSystemWindowInsetRight();
              bottom = insets.getSystemWindowInsetBottom();
            }
            holder.setPadding(left, top, right, a.sheet ? 0 : bottom);
            if (a.sheet)
              panel.setPadding(
                  panel.getPaddingLeft(), panel.getPaddingTop(), panel.getPaddingRight(), panelBottom + bottom);
            return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
          });
      if (a.cancelL != null) setOnCancelListener(a.cancelL);
    }

    private void build() {
      int side = a.sheet ? dp(10) : 0;
      if (a.sheet) {
        View handle = new View(MainActivity.this);
        handle.setBackground(shape(LINE, 2));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(36), dp(4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(14);
        panel.addView(handle, hp);
      } else if (destructive(a.pos)) {
        ImageView icon = new ImageView(MainActivity.this);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageDrawable(new Glyph("trash", RED, 22));
        icon.setBackground(shape((RED & 0x00FFFFFF) | 0x1F000000, 12));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(44), dp(44));
        ip.bottomMargin = dp(14);
        panel.addView(icon, ip);
      }
      if (a.title != null && a.title.length() > 0) {
        TextView title = text(a.title.toString(), 22, INK);
        title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        title.setLineSpacing(dp(2), 1);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.leftMargin = side;
        tp.rightMargin = side;
        tp.bottomMargin = dp(a.sheet ? 6 : 8);
        panel.addView(title, tp);
      }
      // Тело: пояснение, своё содержимое и список; длинное прокручивается, кнопки всегда видны.
      LinearLayout body = column();
      if (a.message != null && a.message.length() > 0) {
        TextView message = text("", a.sheet ? 13 : 15, MUTED);
        message.setText(a.message);
        message.setLineSpacing(dp(3), 1);
        message.setPadding(side, 0, side, dp(a.sheet ? 8 : 4));
        body.addView(message);
      }
      // Своё содержимое с прокруткой (список, ScrollView) не вкладываем во вторую прокрутку.
      boolean scrolls = a.view != null && scrolls(a.view);
      if (a.view != null) {
        styleFields(a.view);
        if (!scrolls) {
          LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, -2);
          vp.topMargin = dp(4);
          body.addView(a.view, vp);
        }
      }
      if (a.items != null) for (int i = 0; i < a.items.length; i++) body.addView(itemRow(i));
      if (body.getChildCount() > 0) {
        ScrollView bodyScroll = new ScrollView(MainActivity.this);
        bodyScroll.setFillViewport(false);
        bodyScroll.setVerticalScrollBarEnabled(false);
        bodyScroll.addView(body);
        panel.addView(bodyScroll, new LinearLayout.LayoutParams(-1, -2, 1));
      }
      if (a.view != null && scrolls) panel.addView(a.view, new LinearLayout.LayoutParams(-1, -2, 1));
      addButtons(side);
    }

    private boolean scrolls(View v) {
      if (v instanceof ScrollView || v instanceof AbsListView) return true;
      if (v instanceof ViewGroup)
        for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++)
          if (scrolls(((ViewGroup) v).getChildAt(i))) return true;
      return false;
    }

    /** Поля ввода на белой карточке получают тонкую рамку, иначе их не видно. */
    private void styleFields(View v) {
      if (v instanceof EditText && v.getBackground() instanceof GradientDrawable)
        ((GradientDrawable) v.getBackground()).setStroke(dp(1), LINE);
      if (v instanceof ViewGroup)
        for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) styleFields(((ViewGroup) v).getChildAt(i));
    }

    private View itemRow(int i) {
      boolean single = a.checked != -2, many = a.multi != null;
      LinearLayout item = row();
      item.setMinimumHeight(dp(52));
      item.setPadding(dp(10), dp(6), dp(10), dp(6));
      TextView label = text(a.items[i].toString(), 16, INK);
      label.setLineSpacing(dp(2), 1);
      item.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
      ImageView mark = null;
      if (single || many) {
        mark = new ImageView(MainActivity.this);
        mark.setScaleType(ImageView.ScaleType.CENTER);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(22), dp(22));
        mp.leftMargin = dp(12);
        item.addView(mark, mp);
      }
      ImageView box = mark;
      Runnable paint =
          () -> {
            boolean on = single ? a.checked == i : many && a.multi[i];
            if (single) {
              item.setBackground(ripple(on ? LIME : android.graphics.Color.TRANSPARENT, 12));
              label.setTextColor(on ? GREEN : INK);
              label.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            } else item.setBackground(ripple(android.graphics.Color.TRANSPARENT, 12));
            if (box != null) {
              GradientDrawable g = shape(on ? FILL : android.graphics.Color.TRANSPARENT, single ? 11 : 6);
              if (!on) g.setStroke(dp(2), STROKE);
              box.setBackground(g);
              box.setImageDrawable(on ? new Glyph("check", WHITE, 14) : null);
            }
            item.setSelected(on);
            if (single || many)
              item.setContentDescription(a.items[i] + (on ? ", выбрано" : ", не выбрано"));
          };
      paint.run();
      marks.add(paint);
      item.setFocusable(true);
      item.setOnClickListener(
          v -> {
            buzz(0);
            if (many) {
              a.multi[i] = !a.multi[i];
              paint.run();
              if (a.multiL != null) a.multiL.onClick(this, i, a.multi[i]);
            } else if (single) {
              a.checked = i;
              for (Runnable r : marks) r.run();
              if (a.itemsL != null) a.itemsL.onClick(this, i);
            } else {
              if (a.itemsL != null) a.itemsL.onClick(this, i);
              dismiss();
            }
          });
      return item;
    }

    private TextView dialogButton(CharSequence label, int which, int fill, int color) {
      TextView b = text(label.toString(), 15, color);
      b.setTypeface(null, Typeface.BOLD);
      b.setGravity(Gravity.CENTER);
      b.setMinHeight(dp(48));
      b.setPadding(dp(12), dp(6), dp(12), dp(6));
      b.setBackground(ripple(fill, 12));
      b.setFocusable(true);
      DialogInterface.OnClickListener l =
          which == DialogInterface.BUTTON_POSITIVE
              ? a.posL
              : which == DialogInterface.BUTTON_NEGATIVE ? a.negL : a.neuL;
      b.setOnClickListener(
          v -> {
            buzz(which == DialogInterface.BUTTON_POSITIVE ? 1 : 0);
            if (l != null) l.onClick(this, which);
            dismiss();
          });
      buttons.put(which, b);
      return b;
    }

    private void addButtons(int side) {
      if (a.pos == null && a.neg == null && a.neu == null) return;
      LinearLayout area = column();
      area.setPadding(side, dp(14), side, 0);
      if (a.neu != null) {
        TextView neutral = dialogButton(a.neu, DialogInterface.BUTTON_NEUTRAL, android.graphics.Color.TRANSPARENT, GREEN);
        area.addView(neutral, new LinearLayout.LayoutParams(-1, -2));
        if (a.pos != null || a.neg != null) gap(area, 6);
      }
      boolean stack =
          (a.pos != null && a.pos.length() > 16) || (a.neg != null && a.neg.length() > 16);
      LinearLayout line = stack ? column() : row();
      TextView negative =
          a.neg == null ? null : dialogButton(a.neg, DialogInterface.BUTTON_NEGATIVE, LIME, GREEN);
      TextView positive =
          a.pos == null
              ? null
              : dialogButton(a.pos, DialogInterface.BUTTON_POSITIVE, destructive(a.pos) ? RED : FILL, WHITE);
      // Рядом: «Отмена» слева, действие справа; столбиком — действие сверху.
      List<TextView> order = new ArrayList<>();
      if (stack) {
        if (positive != null) order.add(positive);
        if (negative != null) order.add(negative);
      } else {
        if (negative != null) order.add(negative);
        if (positive != null) order.add(positive);
      }
      for (int i = 0; i < order.size(); i++) {
        LinearLayout.LayoutParams p =
            stack ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1);
        if (i > 0) {
          if (stack) p.topMargin = dp(8);
          else p.leftMargin = dp(8);
        }
        line.addView(order.get(i), p);
      }
      if (!order.isEmpty()) area.addView(line, new LinearLayout.LayoutParams(-1, -2));
      panel.addView(area, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Кнопка окна, как у AlertDialog: свой обработчик нажатия заменяет закрытие окна. */
    TextView getButton(int which) {
      return buttons.get(which);
    }

    @Override
    protected void onStart() {
      super.onStart();
      Window w = getWindow();
      if (w != null) w.setLayout(-1, -1);
    }

    @Override
    public void show() {
      super.show();
      if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
      android.view.animation.PathInterpolator ease =
          new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);
      scrim.setAlpha(0);
      scrim.animate().alpha(1).setDuration(180).start();
      if (a.sheet) {
        panel.setAlpha(0);
        panel.post(
            () -> {
              panel.setTranslationY(panel.getHeight());
              panel.setAlpha(1);
              panel.animate().translationY(0).setDuration(240).setInterpolator(ease).start();
            });
      } else {
        panel.setAlpha(0);
        panel.setScaleX(0.96f);
        panel.setScaleY(0.96f);
        panel.animate().alpha(1).scaleX(1).scaleY(1).setDuration(200).setInterpolator(ease).start();
      }
    }

    @Override
    public void dismiss() {
      if (closing) return;
      if (!isShowing() || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
        finish();
        return;
      }
      closing = true;
      android.view.animation.PathInterpolator ease =
          new android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f);
      scrim.animate().alpha(0).setDuration(150).start();
      ViewPropertyAnimator out =
          a.sheet
              ? panel.animate().translationY(panel.getHeight())
              : panel.animate().alpha(0).scaleX(0.97f).scaleY(0.97f);
      out.setDuration(160).setInterpolator(ease).withEndAction(this::finish).start();
    }

    private void finish() {
      try {
        Popup.super.dismiss();
      } catch (RuntimeException ignored) {
        // Окно уже снято вместе с экраном.
      }
    }
  }

  /** Пункт своего меню (вместо MenuItem). */
  private interface PopPick {
    boolean onMenuItemClick(PopItem item);
  }

  private static final class PopItem {
    final int id;
    final CharSequence title;
    PopPick own;

    PopItem(int id, CharSequence title) {
      this.id = id;
      this.title = title;
    }

    PopItem setOnMenuItemClickListener(PopPick listener) {
      own = listener;
      return this;
    }

    int getItemId() {
      return id;
    }
  }

  /** Замена PopupMenu с теми же вызовами: белая карточка рядом с кнопкой, «Удалить» — красным. */
  private final class Pop {
    final View anchor;
    final List<PopItem> items = new ArrayList<>();
    final PopList menu = new PopList();
    PopPick all;

    Pop(View anchor) {
      this.anchor = anchor;
    }

    PopList getMenu() {
      return menu;
    }

    void setOnMenuItemClickListener(PopPick listener) {
      all = listener;
    }

    final class PopList {
      PopItem add(CharSequence title) {
        PopItem item = new PopItem(0, title);
        items.add(item);
        return item;
      }

      PopItem add(int group, int id, int order, CharSequence title) {
        PopItem item = new PopItem(id, title);
        items.add(item);
        return item;
      }
    }

    void show() {
      if (items.isEmpty()) return;
      FrameLayout outer = new FrameLayout(MainActivity.this);
      int shadow = dp(12);
      outer.setPadding(shadow, shadow, shadow, shadow);
      LinearLayout card = column();
      card.setPadding(dp(6), dp(6), dp(6), dp(6));
      GradientDrawable background = shape(SURFACE, 14);
      background.setStroke(dp(1), LINE);
      card.setBackground(background);
      card.setElevation(dp(8));
      card.setMinimumWidth(dp(220));
      outer.addView(card, new FrameLayout.LayoutParams(-2, -2));
      PopupWindow window = new PopupWindow(outer, -2, -2, true);
      window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
      window.setOutsideTouchable(true);
      window.setAnimationStyle(0);
      for (PopItem item : items) {
        boolean danger = destructive(item.title);
        TextView row = text(item.title.toString(), 15, danger ? RED : INK);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinHeight(dp(48));
        row.setPadding(dp(14), 0, dp(14), 0);
        if (danger) {
          row.setCompoundDrawablesWithIntrinsicBounds(new Glyph("trash", RED, 20), null, null, null);
          row.setCompoundDrawablePadding(dp(12));
        }
        row.setBackground(ripple(android.graphics.Color.TRANSPARENT, 10));
        row.setFocusable(true);
        row.setOnClickListener(
            v -> {
              buzz(0);
              window.dismiss();
              boolean handled = item.own != null && item.own.onMenuItemClick(item);
              if (!handled && all != null) all.onMenuItemClick(item);
            });
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
      }
      window.showAsDropDown(anchor, shadow, -anchor.getHeight() / 2 - shadow, Gravity.END);
      if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
        card.setAlpha(0);
        card.setScaleX(0.95f);
        card.setScaleY(0.95f);
        card.setPivotX(card.getMeasuredWidth());
        card.setPivotY(0);
        card.animate()
            .alpha(1)
            .scaleX(1)
            .scaleY(1)
            .setDuration(160)
            .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
            .start();
      }
    }
  }

  private static final String[] MONTH_NAMES = {
    "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
    "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"
  };

  /** Часы и минуты кнопками «−» и «+» (минуты шагом 5); {@code hm} меняется на месте. */
  private View timeControl(int[] hm, Runnable changed) {
    LinearLayout line = row();
    line.setGravity(Gravity.CENTER);
    for (int part = 0; part < 2; part++) {
      int index = part;
      if (part == 1) {
        TextView colon = text(":", 22, MUTED);
        colon.setPadding(dp(6), 0, dp(6), 0);
        line.addView(colon);
      }
      TextView value = text("", 22, INK);
      value.setTypeface(Typeface.create("serif", Typeface.NORMAL));
      value.setGravity(Gravity.CENTER);
      GradientDrawable box = shape(PAPER, 12);
      box.setStroke(dp(1), LINE);
      value.setBackground(box);
      Runnable show =
          () -> {
            value.setText(String.format(Locale.ROOT, "%02d", hm[index]));
            value.setContentDescription((index == 0 ? "Часы: " : "Минуты: ") + hm[index]);
          };
      show.run();
      for (int step : new int[] {-1, 1}) {
        TextView b = text(step < 0 ? "−" : "+", 22, GREEN);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(android.graphics.Color.TRANSPARENT, 10));
        b.setContentDescription(
            (step < 0 ? "Меньше " : "Больше ") + (index == 0 ? "часов" : "минут"));
        b.setFocusable(true);
        b.setOnClickListener(
            v -> {
              buzz(0);
              if (index == 0) hm[0] = (hm[0] + step + 24) % 24;
              else hm[1] = ((hm[1] / 5) * 5 + step * 5 + 60) % 60;
              show.run();
              changed.run();
            });
        if (step < 0) {
          line.addView(b, new LinearLayout.LayoutParams(dp(40), dp(48)));
          line.addView(value, new LinearLayout.LayoutParams(dp(60), dp(48)));
        } else line.addView(b, new LinearLayout.LayoutParams(dp(40), dp(48)));
      }
    }
    return line;
  }

  private TextView sheetChip(String label, boolean on, Runnable click) {
    TextView chip = text(label, 13, on ? WHITE : GREEN);
    chip.setTypeface(null, Typeface.BOLD);
    chip.setSingleLine(true);
    chip.setGravity(Gravity.CENTER);
    chip.setMinHeight(dp(36));
    chip.setPadding(dp(12), 0, dp(12), 0);
    chip.setBackground(ripple(on ? FILL : LIME, 10));
    chip.setSelected(on);
    chip.setFocusable(true);
    chip.setOnClickListener(
        v -> {
          buzz(0);
          click.run();
        });
    return chip;
  }

  /** Выбор времени листом снизу (вместо TimePickerDialog): частые варианты и «−/+». */
  private void timePicker(String title, int minutes, java.util.function.IntConsumer done) {
    int[] hm = {minutes / 60, minutes % 60};
    LinearLayout body = column();
    Popup[] popup = {null};
    Runnable[] refresh = {null};
    refresh[0] =
        () -> {
          body.removeAllViews();
          HorizontalScrollView chipsScroll = new HorizontalScrollView(MainActivity.this);
          chipsScroll.setHorizontalScrollBarEnabled(false);
          LinearLayout chips = row();
          int[][] quick = {{8, 0}, {9, 0}, {12, 0}, {18, 0}, {21, 0}};
          for (int i = 0; i < quick.length; i++) {
            int[] q = quick[i];
            TextView chip =
                sheetChip(
                    q[0] + ":00",
                    hm[0] == q[0] && hm[1] == q[1],
                    () -> {
                      hm[0] = q[0];
                      hm[1] = q[1];
                      refresh[0].run();
                    });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) p.leftMargin = dp(8);
            chips.addView(chip, p);
          }
          chipsScroll.addView(chips);
          body.addView(chipsScroll);
          gap(body, 16);
          body.addView(timeControl(hm, () -> refresh[0].run()));
          if (popup[0] != null && popup[0].getButton(DialogInterface.BUTTON_POSITIVE) != null)
            popup[0]
                .getButton(DialogInterface.BUTTON_POSITIVE)
                .setText(String.format(Locale.ROOT, "Готово · %d:%02d", hm[0], hm[1]));
        };
    body.setPadding(dp(10), dp(4), dp(10), 0);
    popup[0] =
        new Ask(this)
            .asSheet()
            .setTitle(title)
            .setView(body)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Готово", (d, w) -> done.accept(hm[0] * 60 + hm[1]))
            .create();
    refresh[0].run();
    popup[0].show();
  }

  /**
   * Дата и время листом снизу (вместо DatePickerDialog + TimePickerDialog): быстрые варианты,
   * свой календарь месяца (неделя с понедельника, прошедшие дни недоступны) и время «−/+».
   */
  private void whenPicker(long initial, java.util.function.LongConsumer picked) {
    whenPicker(initial, 18, "Когда напомнить", picked);
  }

  private void whenPicker(long initial, int eveningHour, String title, java.util.function.LongConsumer picked) {
    Calendar cal = Calendar.getInstance();
    cal.setTimeInMillis(initial);
    int[] ymd = {cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)};
    int[] hm = {cal.get(Calendar.HOUR_OF_DAY), (cal.get(Calendar.MINUTE) / 5) * 5};
    int[] shown = {ymd[0], ymd[1]};
    LinearLayout body = column();
    body.setPadding(dp(10), dp(4), dp(10), 0);
    Popup[] popup = {null};
    Runnable[] refresh = {null};
    java.util.function.Supplier<Long> moment =
        () -> {
          Calendar c = Calendar.getInstance();
          c.clear();
          c.set(ymd[0], ymd[1], ymd[2], hm[0], hm[1], 0);
          return c.getTimeInMillis();
        };
    refresh[0] =
        () -> {
          body.removeAllViews();
          long now = System.currentTimeMillis();
          Calendar today = Calendar.getInstance();
          // Быстрые варианты: сегодня вечером, завтра утром, в понедельник утром.
          List<long[]> quick = new ArrayList<>();
          List<String> quickNames = new ArrayList<>();
          Calendar c = Calendar.getInstance();
          c.set(Calendar.HOUR_OF_DAY, eveningHour);
          c.set(Calendar.MINUTE, 0);
          c.set(Calendar.SECOND, 0);
          c.set(Calendar.MILLISECOND, 0);
          if (c.getTimeInMillis() > now + 300_000) {
            quick.add(new long[] {c.getTimeInMillis()});
            quickNames.add("Сегодня " + eveningHour + ":00");
          }
          c.add(Calendar.DAY_OF_MONTH, 1);
          c.set(Calendar.HOUR_OF_DAY, 9);
          quick.add(new long[] {c.getTimeInMillis()});
          quickNames.add("Завтра 9:00");
          Calendar monday = (Calendar) c.clone();
          while (monday.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) monday.add(Calendar.DAY_OF_MONTH, 1);
          if (monday.getTimeInMillis() != c.getTimeInMillis()) {
            quick.add(new long[] {monday.getTimeInMillis()});
            quickNames.add("В понедельник 9:00");
          }
          HorizontalScrollView chipsScroll = new HorizontalScrollView(MainActivity.this);
          chipsScroll.setHorizontalScrollBarEnabled(false);
          LinearLayout chips = row();
          for (int i = 0; i < quick.size(); i++) {
            long at = quick.get(i)[0];
            TextView chip =
                sheetChip(
                    quickNames.get(i),
                    moment.get() == at,
                    () -> {
                      Calendar q = Calendar.getInstance();
                      q.setTimeInMillis(at);
                      ymd[0] = q.get(Calendar.YEAR);
                      ymd[1] = q.get(Calendar.MONTH);
                      ymd[2] = q.get(Calendar.DAY_OF_MONTH);
                      hm[0] = q.get(Calendar.HOUR_OF_DAY);
                      hm[1] = q.get(Calendar.MINUTE);
                      shown[0] = ymd[0];
                      shown[1] = ymd[1];
                      refresh[0].run();
                    });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) p.leftMargin = dp(8);
            chips.addView(chip, p);
          }
          chipsScroll.addView(chips);
          body.addView(chipsScroll);
          gap(body, 12);
          // Заголовок месяца со стрелками.
          LinearLayout head = row();
          TextView month = text(MONTH_NAMES[shown[1]] + " " + shown[0], 15, INK);
          month.setTypeface(null, Typeface.BOLD);
          head.addView(month, new LinearLayout.LayoutParams(0, -2, 1));
          boolean canBack =
              shown[0] > today.get(Calendar.YEAR)
                  || (shown[0] == today.get(Calendar.YEAR) && shown[1] > today.get(Calendar.MONTH));
          for (int step : new int[] {-1, 1}) {
            ImageView arrow = new ImageView(MainActivity.this);
            arrow.setScaleType(ImageView.ScaleType.CENTER);
            boolean enabled = step > 0 || canBack;
            arrow.setImageDrawable(new Glyph("back", enabled ? GREEN : LINE, 18));
            if (step > 0) arrow.setRotation(180);
            arrow.setBackground(ripple(android.graphics.Color.TRANSPARENT, 10));
            arrow.setContentDescription(step < 0 ? "Предыдущий месяц" : "Следующий месяц");
            arrow.setEnabled(enabled);
            arrow.setOnClickListener(
                v -> {
                  buzz(0);
                  shown[1] += step;
                  if (shown[1] < 0) {
                    shown[1] = 11;
                    shown[0]--;
                  } else if (shown[1] > 11) {
                    shown[1] = 0;
                    shown[0]++;
                  }
                  refresh[0].run();
                });
            head.addView(arrow, new LinearLayout.LayoutParams(dp(44), dp(44)));
          }
          body.addView(head);
          // Дни недели и числа месяца.
          LinearLayout week = row();
          for (String d : new String[] {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"}) {
            TextView w = text(d, 12, MUTED);
            w.setGravity(Gravity.CENTER);
            w.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            week.addView(w, new LinearLayout.LayoutParams(0, dp(24), 1));
          }
          body.addView(week);
          Calendar first = Calendar.getInstance();
          first.clear();
          first.set(shown[0], shown[1], 1);
          int lead = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7;
          int days = first.getActualMaximum(Calendar.DAY_OF_MONTH);
          Calendar startOfToday = Calendar.getInstance();
          startOfToday.set(Calendar.HOUR_OF_DAY, 0);
          startOfToday.set(Calendar.MINUTE, 0);
          startOfToday.set(Calendar.SECOND, 0);
          startOfToday.set(Calendar.MILLISECOND, 0);
          LinearLayout line = null;
          for (int cell = 0; cell < lead + days; cell++) {
            if (cell % 7 == 0) {
              line = row();
              body.addView(line);
            }
            int day = cell - lead + 1;
            if (day < 1) {
              line.addView(new View(MainActivity.this), new LinearLayout.LayoutParams(0, dp(40), 1));
              continue;
            }
            Calendar d = Calendar.getInstance();
            d.clear();
            d.set(shown[0], shown[1], day);
            boolean past = d.getTimeInMillis() < startOfToday.getTimeInMillis();
            boolean selected = ymd[0] == shown[0] && ymd[1] == shown[1] && ymd[2] == day;
            boolean isToday = d.getTimeInMillis() == startOfToday.getTimeInMillis();
            TextView number = text(String.valueOf(day), 14, past ? MUTED : selected ? WHITE : isToday ? GREEN : INK);
            number.setGravity(Gravity.CENTER);
            if (selected || isToday) number.setTypeface(null, Typeface.BOLD);
            GradientDrawable g = shape(selected ? FILL : android.graphics.Color.TRANSPARENT, 10);
            if (isToday && !selected) g.setStroke(dp(1), GREEN);
            number.setBackground(
                past ? g : new RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x2028634B), g, null));
            number.setContentDescription(
                day + " " + MONTH_NAMES[shown[1]].toLowerCase(Locale.ROOT) + (selected ? ", выбрано" : ""));
            if (!past) {
              number.setFocusable(true);
              number.setOnClickListener(
                  v -> {
                    buzz(0);
                    ymd[0] = shown[0];
                    ymd[1] = shown[1];
                    ymd[2] = day;
                    refresh[0].run();
                  });
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(40), 1);
            p.setMargins(dp(1), dp(1), dp(1), dp(1));
            line.addView(number, p);
          }
          if (line != null)
            while (line.getChildCount() < 7)
              line.addView(new View(MainActivity.this), new LinearLayout.LayoutParams(0, dp(40), 1));
          View rule = new View(MainActivity.this);
          rule.setBackgroundColor(LINE);
          LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(1));
          rp.topMargin = dp(8);
          rp.bottomMargin = dp(10);
          body.addView(rule, rp);
          body.addView(timeControl(hm, () -> refresh[0].run()));
          // На кнопке — выбранный момент; прошедшее время подписано прямо на ней (подсказка внизу
          // экрана была бы под затемнением листа).
          if (popup[0] != null && popup[0].getButton(DialogInterface.BUTTON_POSITIVE) != null)
            popup[0]
                .getButton(DialogInterface.BUTTON_POSITIVE)
                .setText(moment.get() <= now ? "Время уже прошло" : date(moment.get()));
        };
    popup[0] =
        new Ask(this)
            .asSheet()
            .setTitle(title)
            .setView(body)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Готово", null)
            .create();
    refresh[0].run();
    popup[0]
        .getButton(DialogInterface.BUTTON_POSITIVE)
        .setOnClickListener(
            v -> {
              long at = moment.get();
              if (at <= System.currentTimeMillis()) {
                buzz(2);
                refresh[0].run();
                return;
              }
              buzz(1);
              popup[0].dismiss();
              picked.accept(at);
            });
    popup[0].show();
  }

  /**
   * Радиальная панель форматирования редактора: круглая ручка у правого края листа. Свайп по
   * ней, не отрывая палец, выдвигает веер функций (списки, чекбоксы, жирный, курсив,
   * зачёркнутый); отпустил над функцией — она применяется к тексту, отпустил раньше — панель
   * закрывается и уходит за край. Долгое нажатие открывает тот же список окном (TalkBack).
   * Синтаксис общий с разметкой чата: {@link TextFormat}.
   */
  private void formatHandle() {
    if (bodyField == null) return;
    RadialFan fan = new RadialFan();
    FrameLayout overlay = new FrameLayout(this);
    overlay.addView(fan, new FrameLayout.LayoutParams(-1, -1));
    overlay.setVisibility(View.GONE);
    overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    frame.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

    ImageView handle = new ImageView(this);
    handle.setImageDrawable(new Glyph("fmt", WHITE, 15));
    handle.setBackground(shape(FILL, 18));
    handle.setAlpha(0.94f);
    handle.setElevation(dp(3));
    handle.setContentDescription("Форматирование текста");
    handle.setFocusable(true);
    FrameLayout.LayoutParams handleParams =
        new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.END | Gravity.CENTER_VERTICAL);
    handle.setTranslationX(dp(9)); // кругляж наполовину за краем, тексту не мешает
    // Ручка у системного края должна получать свайп при жестовой навигации.
    if (Build.VERSION.SDK_INT >= 29)
      handle.addOnLayoutChangeListener(
          (v, l, t, r, b, ol, ot, or, ob) ->
              handle.setSystemGestureExclusionRects(
                  Collections.singletonList(new Rect(0, 0, r - l, b - t))));
    frame.addView(handle, handleParams);

    handle.setOnLongClickListener(
        v -> {
          fan.close(false, () -> overlay.setVisibility(View.GONE));
          String[] labels = {
            "Маркированный список", "Нумерованный список", "Чекбоксы",
            "Жирный", "Курсив", "Зачёркнутый"
          };
          new Ask(this)
              .setTitle("Форматирование")
              .setItems(labels, (d, which) -> applyFormat(fan.actions[which]))
              .show();
          return true;
        });
    GestureDetector hold = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
      @Override public boolean onDown(MotionEvent event) {
        return true;
      }

      @Override public void onLongPress(MotionEvent event) {
        if (fan.tracking) {
          fan.tracking = false;
          handle.performLongClick();
        }
      }
    });
    hold.setOnDoubleTapListener(null);
    int[] frameXY = new int[2];
    handle.setOnTouchListener(
        (v, event) -> {
          hold.onTouchEvent(event);
          frame.getLocationOnScreen(frameXY);
          float x = event.getRawX() - frameXY[0], y = event.getRawY() - frameXY[1];
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              swipeAllowed = false;
              fan.tracking = true;
              overlay.setVisibility(View.VISIBLE);
              fan.open(y, frame.getHeight());
              return true;
            case MotionEvent.ACTION_MOVE:
              if (fan.tracking) fan.track(x, y);
              return true;
            case MotionEvent.ACTION_UP:
              if (!fan.tracking) return true;
              fan.tracking = false;
              fan.track(x, y);
              String action = fan.selectedAction();
              fan.close(action != null, () -> overlay.setVisibility(View.GONE));
              if (action != null) applyFormat(action);
              v.performClick();
              return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
              if (fan.tracking) {
                fan.tracking = false;
                fan.close(false, () -> overlay.setVisibility(View.GONE));
              }
              return true;
            default:
              return true;
          }
        });
  }

  /** Применяет форматирование с сохранением позиции курсора и текущего поля ввода. */
  private void applyFormat(String action) {
    EditText f = bodyField;
    if (f == null) return;
    Editable value = f.getText();
    String text = value.toString();
    int start = f.getSelectionStart(), end = f.getSelectionEnd();
    if (start < 0 || end < 0) start = end = text.length();
    TextFormat.Result r =
        switch (action) {
          case "bold" -> TextFormat.wrap(text, start, end, TextFormat.BOLD);
          case "italic" -> TextFormat.wrap(text, start, end, TextFormat.ITALIC);
          case "strike" -> TextFormat.wrap(text, start, end, TextFormat.STRIKE);
          default -> TextFormat.prefix(text, start, end, action);
        };
    String formatted = r.text();
    if (formatted.equals(text)) return;
    if (formatted.length() > NOTE_BODY_LIMIT) {
      toast("Достигнут предел длины заметки");
      return;
    }
    int from = 0, oldEnd = text.length(), newEnd = formatted.length();
    while (from < oldEnd && from < newEnd && text.charAt(from) == formatted.charAt(from)) from++;
    while (from < oldEnd && from < newEnd
        && text.charAt(oldEnd - 1) == formatted.charAt(newEnd - 1)) {
      oldEnd--;
      newEnd--;
    }
    f.beginBatchEdit();
    try {
      value.replace(from, oldEnd, formatted, from, newEnd);
      f.setSelection(Math.min(r.start(), value.length()), Math.min(r.end(), value.length()));
    } finally {
      f.endBatchEdit();
    }
  }

  /**
   * Веер функций: круглая панель из-за правого края, функции дугой слева от ручки. Рисуется
   * сама, состояние — центр, доля раскрытия, сдвиг при закрытии и выбранный сектор.
   */
  private final class RadialFan extends View {
    final String[] actions = {"bullet", "number", "check", "bold", "italic", "strike"};
    final String[] glyphs = {"fmt_list", "fmt_num", "fmt_checklist", "fmt_bold", "fmt_italic", "fmt_strike"};
    final float[] angles = {165f, 139f, 113f, 247f, 221f, 195f};
    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    float anchorY, fraction, slide;
    int selected = -1;
    boolean tracking;
    android.animation.ValueAnimator running;

    RadialFan() {
      super(MainActivity.this);
      fill.setStyle(Paint.Style.FILL);
      stroke.setStyle(Paint.Style.STROKE);
      stroke.setStrokeWidth(dp(1));
    }

    float radius() {
      return dp(96);
    }

    float cx() {
      return getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
    }

    float cy() {
      return anchorY;
    }

    void open(float y, float height) {
      float margin = Math.min(height / 2, radius() + dp(36));
      anchorY = Math.max(margin, Math.min(height - margin, y));
      selected = -1;
      slide = 0;
      animateTo(1, 170, null);
    }

    void track(float x, float y) {
      float dx = x - cx(), dy = y - cy();
      double dist = Math.hypot(dx, dy);
      int next = -1;
      if (dist > dp(30) && dist < radius() + dp(34)) {
        double angle = Math.toDegrees(Math.atan2(dy, dx));
        if (angle < 0) angle += 360;
        double best = 20;
        for (int i = 0; i < angles.length; i++) {
          double d = Math.abs(angle - angles[i]);
          if (d > 180) d = 360 - d;
          if (d < best) {
            best = d;
            next = i;
          }
        }
      }
      if (next != selected) {
        selected = next;
        if (next >= 0) buzz(0);
        invalidate();
      }
    }

    String selectedAction() {
      return selected < 0 ? null : actions[selected];
    }

    /** {@code apply} — палец отпущен над функцией: быстрое сворачивание, иначе уход за край. */
    void close(boolean apply, Runnable done) {
      animateTo(
          0,
          apply ? 130 : 200,
          () -> {
            slide = 0;
            selected = -1;
            done.run();
          });
    }

    void animateTo(float target, long ms, Runnable done) {
      android.animation.ValueAnimator previous = running;
      running = null;
      if (previous != null) previous.cancel();
      if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
        fraction = target;
        invalidate();
        if (done != null) done.run();
        return;
      }
      float from = fraction;
      running = android.animation.ValueAnimator.ofFloat(0, 1);
      running.setDuration(ms);
      running.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0, 0, 1));
      running.addUpdateListener(
          a -> {
            float f = (float) a.getAnimatedValue();
            fraction = from + (target - from) * f;
            slide = target == 0 && done != null ? dp(140) * f : 0;
            invalidate();
          });
      running.addListener(
          new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
              if (running != a) return;
              running = null;
              fraction = target;
              invalidate();
              if (done != null) done.run();
            }
          });
      running.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
      if (fraction <= 0) return;
      canvas.save();
      canvas.translate(slide, 0);
      float r = radius();
      fill.setColor(SURFACE);
      fill.setAlpha((int) (0.97f * 255 * fraction));
      stroke.setColor(LINE);
      stroke.setAlpha((int) (255 * fraction));
      canvas.drawCircle(cx(), cy(), (r + dp(30)) * fraction, fill);
      canvas.drawCircle(cx(), cy(), (r + dp(30)) * fraction, stroke);
      int item = dp(21);
      for (int i = 0; i < actions.length; i++) {
        double a = Math.toRadians(angles[i]);
        float x = cx() + (float) (r * Math.cos(a)) * fraction;
        float y = cy() + (float) (r * Math.sin(a)) * fraction;
        boolean on = i == selected;
        fill.setColor(on ? LIME : SURFACE);
        fill.setAlpha((int) (255 * fraction));
        float half = (on ? item * 1.18f : item) * fraction;
        canvas.drawCircle(x, y, half, fill);
        canvas.drawCircle(x, y, half, stroke);
        Glyph icon = new Glyph(glyphs[i], on ? FILL : MUTED, on ? 17 : 16);
        int s = (int) (icon.getIntrinsicWidth() * fraction);
        if (s > 0) {
          icon.setBounds((int) x - s / 2, (int) y - s / 2, (int) x + s / 2, (int) y + s / 2);
          icon.setAlpha((int) (255 * fraction));
          icon.draw(canvas);
        }
      }
      canvas.restore();
    }
  }

  private final class Glyph extends android.graphics.drawable.Drawable {
    private final String name;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint textPaint;
    private final int size;

    private Paint textPaint(boolean bold, boolean italic) {
      if (textPaint == null) {
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);
      }
      textPaint.setColor(paint.getColor());
      textPaint.setAlpha(paint.getAlpha());
      textPaint.setTextSize(13);
      textPaint.setTypeface(
          Typeface.create(
              Typeface.DEFAULT,
              bold && italic
                  ? Typeface.BOLD_ITALIC
                  : bold ? Typeface.BOLD : italic ? Typeface.ITALIC : Typeface.NORMAL));
      return textPaint;
    }

    Glyph(String name, int color, int size) {
      this.name = name;
      this.size = dp(size);
      paint.setColor(color);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(1.7f);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
      setBounds(0, 0, this.size, this.size);
    }

    public int getIntrinsicWidth() {
      return size;
    }

    public int getIntrinsicHeight() {
      return size;
    }

    @Override
    public void draw(Canvas c) {
      c.save();
      c.translate(getBounds().left, getBounds().top);
      c.scale(getBounds().width() / 24f, getBounds().height() / 24f);
      switch (name) {
        case "up", "down" -> {
          if (name.equals("up")) c.rotate(180, 12, 12);
          Path down = new Path();
          down.moveTo(6, 9);
          down.lineTo(12, 15);
          down.lineTo(18, 9);
          c.drawPath(down, paint);
        }
        case "check" -> {
          Path check = new Path();
          check.moveTo(4, 12);
          check.lineTo(10, 18);
          check.lineTo(21, 6);
          c.drawPath(check, paint);
        }
        case "spark" -> {
          Path star = new Path();
          star.moveTo(10, 3);
          star.lineTo(12.5f, 9.5f);
          star.lineTo(19, 12);
          star.lineTo(12.5f, 14.5f);
          star.lineTo(10, 21);
          star.lineTo(7.5f, 14.5f);
          star.lineTo(1, 12);
          star.lineTo(7.5f, 9.5f);
          star.close();
          c.drawPath(star, paint);
          c.drawLine(19, 2, 19, 6, paint);
          c.drawLine(17, 4, 21, 4, paint);
        }
        case "video" -> {
          c.drawRoundRect(2, 5, 22, 19, 4, 4, paint);
          Path play = new Path();
          play.moveTo(10, 9);
          play.lineTo(15.5f, 12);
          play.lineTo(10, 15);
          play.close();
          c.drawPath(play, paint);
        }
        case "plus" -> {
          c.drawRoundRect(3, 3, 21, 21, 5, 5, paint);
          c.drawLine(12, 7, 12, 17, paint);
          c.drawLine(7, 12, 17, 12, paint);
        }
        case "notes" -> {
          c.drawRoundRect(5, 3, 19, 21, 2, 2, paint);
          c.drawLine(8, 8, 16, 8, paint);
          c.drawLine(8, 12, 16, 12, paint);
          c.drawLine(8, 16, 13, 16, paint);
        }
        case "chat" -> {
          Path p = new Path();
          p.moveTo(5, 4);
          p.lineTo(19, 4);
          p.quadTo(21, 4, 21, 6);
          p.lineTo(21, 16);
          p.quadTo(21, 18, 19, 18);
          p.lineTo(9, 18);
          p.lineTo(4, 21);
          p.lineTo(4, 6);
          p.quadTo(4, 4, 5, 4);
          c.drawPath(p, paint);
          c.drawLine(8, 9, 17, 9, paint);
          c.drawLine(8, 13, 14, 13, paint);
        }
        case "clock" -> {
          c.drawCircle(12, 12, 9, paint);
          c.drawLine(12, 6, 12, 12, paint);
          c.drawLine(12, 12, 16, 14, paint);
        }
        case "back" -> {
          c.drawLine(19, 12, 5, 12, paint);
          c.drawLine(5, 12, 11, 6, paint);
          c.drawLine(5, 12, 11, 18, paint);
        }
        case "add" -> {
          c.drawLine(12, 5, 12, 19, paint);
          c.drawLine(5, 12, 19, 12, paint);
        }
        case "send" -> {
          c.drawLine(12, 19, 12, 5, paint);
          c.drawLine(12, 5, 6, 11, paint);
          c.drawLine(12, 5, 18, 11, paint);
        }
        case "image" -> {
          c.drawRoundRect(4, 5, 20, 19, 3, 3, paint);
          c.drawCircle(9, 10, 1.5f, paint);
          Path hills = new Path();
          hills.moveTo(4, 17);
          hills.lineTo(9, 13);
          hills.lineTo(13, 16);
          hills.lineTo(16, 13.5f);
          hills.lineTo(20, 17);
          c.drawPath(hills, paint);
        }
        case "close" -> {
          c.drawLine(6, 6, 18, 18, paint);
          c.drawLine(18, 6, 6, 18, paint);
        }
        case "search" -> {
          c.drawCircle(11, 11, 6.5f, paint);
          c.drawLine(16, 16, 20.5f, 20.5f, paint);
        }
        case "bell" -> {
          Path bell = new Path();
          bell.moveTo(6, 17);
          bell.lineTo(6, 11);
          bell.arcTo(new RectF(6, 5, 18, 17), 180, 180);
          bell.lineTo(18, 17);
          bell.lineTo(19.5f, 19);
          bell.lineTo(4.5f, 19);
          bell.close();
          c.drawPath(bell, paint);
          c.drawArc(new RectF(10, 17, 14, 22), 0, 180, false, paint);
        }
        case "grid" -> {
          for (int x = 3; x < 20; x += 11)
            for (int y = 3; y < 20; y += 11) c.drawRoundRect(x, y, x + 7, y + 7, 1, 1, paint);
        }
        case "list" -> {
          for (int y = 5; y < 22; y += 7) {
            c.drawCircle(4, y, .5f, paint);
            c.drawLine(9, y, 21, y, paint);
          }
        }
        case "folder" -> {
          Path outline = new Path();
          outline.moveTo(3, 6.5f);
          outline.quadTo(3, 5, 4.5f, 5);
          outline.lineTo(9, 5);
          outline.lineTo(11, 7.5f);
          outline.lineTo(19.5f, 7.5f);
          outline.quadTo(21, 7.5f, 21, 9);
          outline.lineTo(21, 18);
          outline.quadTo(21, 19.5f, 19.5f, 19.5f);
          outline.lineTo(4.5f, 19.5f);
          outline.quadTo(3, 19.5f, 3, 18);
          outline.close();
          c.drawPath(outline, paint);
        }
        case "trash" -> {
          c.drawLine(4, 6, 20, 6, paint);
          c.drawRect(7, 6, 17, 21, paint);
          c.drawLine(9, 3, 15, 3, paint);
          c.drawLine(10, 10, 10, 17, paint);
          c.drawLine(14, 10, 14, 17, paint);
        }
        case "fmt" -> {
          // Ручка панели: буква и штрихи строк.
          c.drawText("А", 8, 16.5f, textPaint(true, false));
          c.drawLine(14, 8, 21, 8, paint);
          c.drawLine(14, 12, 21, 12, paint);
          c.drawLine(14, 16, 18, 16, paint);
        }
        case "fmt_bold" -> c.drawText("Б", 12, 16.5f, textPaint(true, false));
        case "fmt_italic" -> c.drawText("К", 12, 16.5f, textPaint(false, true));
        case "fmt_strike" -> {
          c.drawText("S", 12, 16.5f, textPaint(false, false));
          c.drawLine(5, 12, 19, 12, paint);
        }
        case "fmt_list" -> {
          for (int y = 7; y < 22; y += 6) {
            c.drawCircle(5.5f, y, 1.1f, textPaint(false, false));
            c.drawLine(10, y, 20, y, paint);
          }
        }
        case "fmt_num" -> {
          Paint digits = textPaint(false, false);
          digits.setTextSize(7);
          c.drawText("1", 5.5f, 8.5f, digits);
          c.drawText("2", 5.5f, 16.5f, digits);
          c.drawLine(10, 6, 20, 6, paint);
          c.drawLine(10, 14, 20, 14, paint);
          c.drawLine(10, 20, 17, 20, paint);
        }
        case "fmt_checklist" -> {
          c.drawRoundRect(3, 4, 9, 10, 1.5f, 1.5f, paint);
          Path tick = new Path();
          tick.moveTo(4.2f, 6.8f);
          tick.lineTo(5.6f, 8.4f);
          tick.lineTo(8, 5.4f);
          c.drawPath(tick, textPaint(false, false));
          c.drawLine(12, 7, 21, 7, paint);
          c.drawRoundRect(3, 13, 9, 19, 1.5f, 1.5f, paint);
          c.drawLine(12, 16, 21, 16, paint);
        }
        default -> {
          for (int y = 5; y < 22; y += 7) c.drawLine(3, y, 21, y, paint);
          c.drawCircle(8, 5, 2, paint);
          c.drawCircle(16, 12, 2, paint);
          c.drawCircle(10, 19, 2, paint);
        }
      }
      c.restore();
    }

    @Override
    public void setAlpha(int a) {
      paint.setAlpha(a);
    }

    @Override
    public void setColorFilter(ColorFilter f) {
      paint.setColorFilter(f);
    }

    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }
}
