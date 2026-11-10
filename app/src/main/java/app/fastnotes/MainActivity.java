package app.fastnotes;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.text.*;
import android.text.method.*;
import android.text.style.ClickableSpan;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.json.*;

public final class MainActivity extends Activity
    implements ChatRunner.Listener, ImageRunner.Listener {
  private static final int PAPER = 0xFFF7F8F2,
      INK = 0xFF1E3028,
      MUTED = 0xFF6C786F,
      GREEN = 0xFF28634B,
      LIME = 0xFFE9F1D6,
      LINE = 0xFFDFE4DA,
      WHITE = 0xFFFFFFFF,
      RED = 0xFFA74335;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private Store store;
  private SharedPreferences prefs;
  private LinearLayout root, content, dock;
  private FrameLayout frame;
  private View dockFade;
  private int navInset;
  private boolean keyboardUp, searchOpen;
  private Runnable closeSearch;
  private EditText searchField;
  private PopupWindow suggestionsPopup;
  private EditText titleField, bodyField, chatInput;
  // Вкладки слева направо: 0 «Заметки», 1 «Записать», 2 «ИИ-чат». 3 — настройки вне нижней панели.
  private int tab = 1;
  private String page = "home";
  private long editing = 0, reminder = 0, chat = 0;
  private boolean editorActive = false, rendering = false;
  private String returnPage = "notes";
  private TextView reminderButton, analysisStatusView;
  private AlertDialog managerPreview, analysisLogDialog;
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
    super.onCreate(state);
    store = new Store(this);
    prefs = getSharedPreferences("settings", 0);
    prefs.registerOnSharedPreferenceChangeListener(analysisListener);
    Reminders.channel(this);
    if (state != null) {
      tab = state.getInt("tab", 1);
      page = state.getString("page", "home");
      editing = state.getLong("editing");
      chat = state.getLong("chat");
      returnPage = state.getString("return", "notes");
      search = state.getString("search", "");
      pickerPage = state.getString("pickerPage", "");
      pickerTarget = state.getLong("pickerTarget");
    }
    long requested = getIntent().getLongExtra("note", 0);
    if (state == null && requested > 0) {
      editing = requested;
      page = "edit";
      tab = 0;
    }
    if (state == null && getIntent().getBooleanExtra("manager", false)) {
      page = "manager";
      tab = 0;
    }
    ChatRunner.listen(this);
    ImageRunner.listen(this);
    BackgroundAnalysis.configure(this);
    render();
    io.execute(
        () -> {
          try (Store reminders = new Store(getApplicationContext())) {
            for (Note n : reminders.notes())
              if (n.reminder > System.currentTimeMillis())
                Reminders.schedule(getApplicationContext(), n);
          }
        });
    if (Build.VERSION.SDK_INT >= 33)
      getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::goBack);
  }

  @Override
  protected void onResume() {
    super.onResume();
    ChatRunner.listen(this);
    ImageRunner.listen(this);
    imageFinished();
    if ("chat".equals(page) || "notes".equals(page)) {
      stash();
      render();
    }
    showPendingCreation();
  }

  @Override
  protected void onPause() {
    stash();
    persistEditedNote();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(analysisListener);
    io.shutdown();
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
    out.putLong("editing", editing);
    out.putLong("chat", chat);
    out.putString("return", returnPage);
    out.putString("search", search);
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
      tab = page.equals("chat") ? 2 : page.equals("home") ? 1 : 0;
      render();
    } else if ("manager".equals(page)) {
      page = "notes";
      render();
    } else if ("chat".equals(page)) {
      stash();
      page = "chats";
      render();
    } else if (tab != 1) {
      navigate(1);
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
          GradientDrawable background = shape(WHITE, 14);
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
    b.setBackground(ripple(primary ? GREEN : LIME, 12));
    b.setFocusable(true);
    b.setOnClickListener(v -> click.run());
    return b;
  }

  private TextView iconButton(String label, String icon, Runnable click) {
    TextView b = button(label, false, click);
    b.setCompoundDrawablesWithIntrinsicBounds(new Glyph(icon, GREEN, 20), null, null, null);
    b.setCompoundDrawablePadding(dp(8));
    return b;
  }

  private void secondary(LinearLayout l, String value) {
    TextView t = text(value, 13, MUTED);
    t.setLineSpacing(dp(3), 1);
    l.addView(t);
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
    EditText e = new EditText(this);
    e.setTextColor(INK);
    e.setHintTextColor(MUTED);
    e.setTextSize(prefs.getBoolean("large", false) ? 20 : 17);
    e.setHint(hint);
    e.setContentDescription(hint);
    e.setPadding(dp(16), dp(14), dp(16), dp(14));
    e.setBackground(shape(WHITE, 10));
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
    rendering = true;
    analysisStatusView = null;
    editorActive = false;
    titleField = null;
    bodyField = null;
    chatInput = null;
    if (suggestionsPopup != null) suggestionsPopup.dismiss();
    root = column();
    root.setBackgroundColor(PAPER);
    frame = new FrameLayout(this);
    frame.setBackgroundColor(PAPER);
    frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
    setContentView(frame);
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
    content.setPadding(
        dp(22), dp(8), dp(22), notesList ? 0 : dp("chat".equals(page) ? 16 : 100));
    if ("notes".equals(page) || "chat".equals(page)) {
      root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    } else root.addView(scroll(content), new LinearLayout.LayoutParams(-1, 0, 1));
    switch (page) {
      case "home" -> editor(0);
      case "edit" -> editor(editing);
      case "notes" -> notes();
      case "manager" -> managerPage();
      case "chats" -> chats();
      case "chat" -> chatPage();
      case "settings" -> settings();
    }
    bottomNav();
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
    if (LocalLlm.enabled(this)) {
      toast(
          "Общий обзор пока доступен только через Polza.AI. Выберите облачный режим в настройках.");
      return;
    }
    if (!persistEditedNote()) return;
    try {
      if (Secrets.read(this).isEmpty() || prefs.getString("model", "").isEmpty()) {
        new AlertDialog.Builder(this)
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
        new AlertDialog.Builder(this)
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
            GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x00F7F8F2, PAPER}));
    frame.addView(dockFade, new FrameLayout.LayoutParams(-1, dp(110), Gravity.BOTTOM));
    dock = row();
    dock.setPadding(dp(14), dp(9), dp(14), dp(9));
    GradientDrawable background = shape(0xF2FFFFFF, 20);
    background.setStroke(dp(1), LINE);
    dock.setBackground(background);
    dock.setElevation(dp(8));
    String[] labels = {"Заметки", "Записать", "ИИ-чат"};
    String[] icons = {"notes", "add", "chat"};
    for (int i = 0; i < 3; i++) {
      final int target = i;
      boolean add = i == 1, active = i == tab;
      ImageView item = new ImageView(this);
      item.setScaleType(ImageView.ScaleType.CENTER);
      item.setImageDrawable(new Glyph(icons[i], add ? WHITE : active ? GREEN : MUTED, 24));
      item.setBackground(
          ripple(add ? GREEN : active ? LIME : android.graphics.Color.TRANSPARENT, 14));
      item.setContentDescription(labels[i]);
      item.setFocusable(true);
      item.setClickable(true);
      item.setSelected(active);
      item.setOnClickListener(v -> navigate(target));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
      if (i > 0) lp.leftMargin = dp(14);
      dock.addView(item, lp);
    }
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
    dock.setVisibility(visible ? View.VISIBLE : View.GONE);
    dockFade.setVisibility(visible ? View.VISIBLE : View.GONE);
  }

  private ImageView barIcon(String icon, String description, Runnable click) {
    ImageView view = new ImageView(this);
    view.setScaleType(ImageView.ScaleType.CENTER);
    view.setImageDrawable(new Glyph(icon, INK, 24));
    view.setBackground(ripple(android.graphics.Color.TRANSPARENT, 14));
    view.setContentDescription(description);
    view.setFocusable(true);
    view.setOnClickListener(v -> click.run());
    return view;
  }

  /**
   * Верхняя строка: назад слева, поиск по центру, колокольчик с предложениями ИИ и настройки справа.
   * Поле поиска раскрывается поверх остальных кнопок, быстро в начале и медленнее к концу.
   */
  private View topBar(boolean back, boolean searchable, boolean actions) {
    searchOpen = false;
    closeSearch = null;
    searchField = null;
    FrameLayout bar = new FrameLayout(this);
    int size = dp(44);
    List<View> fading = new ArrayList<>();
    if (back) {
      View button = barIcon("back", "Назад", this::goBack);
      bar.addView(
          button, new FrameLayout.LayoutParams(size, size, Gravity.START | Gravity.CENTER_VERTICAL));
      fading.add(button);
    }
    if (actions) {
      View settingsButton = barIcon("settings", "Настройки", () -> navigate(3));
      bar.addView(
          settingsButton,
          new FrameLayout.LayoutParams(size, size, Gravity.END | Gravity.CENTER_VERTICAL));
      int pending = new ManagerStore(store).pending().size();
      FrameLayout bell = new FrameLayout(this);
      bell.addView(
          barIcon(
              "bell",
              pending > 0 ? "Предложения ИИ: " + pending : "Предложения ИИ",
              () -> showSuggestions(bar)),
          new FrameLayout.LayoutParams(size, size));
      if (pending > 0) {
        TextView badge = text(pending > 9 ? "9+" : String.valueOf(pending), 10, WHITE);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setIncludeFontPadding(false);
        badge.setMinWidth(dp(16));
        badge.setPadding(dp(4), 0, dp(4), 0);
        badge.setBackground(shape(GREEN, 6));
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
      fading.add(bell);
      fading.add(settingsButton);
    }
    if (!searchable) return bar;

    // Фокус по умолчанию держит сама строка, чтобы поле не раскрывалось само.
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
    String barText = "chats".equals(page) ? chatSearch : search;
    field.setContentDescription("chats".equals(page) ? "Поиск по чатам" : "Поиск по заметкам");
    field.setOnEditorActionListener(
        (v, action, event) -> {
          hideKeyboard();
          return true;
        });
    field.setText(barText);
    LinearLayout box = row();
    box.setPadding(dp(14), 0, dp(6), 0);
    GradientDrawable boxBackground = shape(WHITE, 14);
    boxBackground.setStroke(dp(1), LINE);
    box.setBackground(boxBackground);
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
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, size, Gravity.CENTER_VERTICAL);
    int left = back ? dp(52) : 0, right = actions ? dp(96) : 0;
    bar.addView(box, params);
    SearchMotion motion = new SearchMotion(params, box, clear, field, fading, left, right);
    searchField = field;
    closeSearch =
        () -> {
          field.setText("");
          bar.requestFocus();
          hideKeyboard();
          motion.set(false, true);
        };
    clear.setOnClickListener(v -> closeSearch.run());
    box.setOnClickListener(
        v -> {
          motion.set(true, true);
          field.requestFocus();
          getSystemService(InputMethodManager.class).showSoftInput(field, 0);
        });
    field.setOnFocusChangeListener(
        (v, focused) -> {
          if (focused) motion.set(true, true);
          else if (field.getText().length() == 0) motion.set(false, true);
        });
    motion.set(!barText.isEmpty(), false);
    return bar;
  }

  /** Раскрытие поля поиска: 480 мс с быстрым стартом, закрытие короче и спокойнее. */
  private final class SearchMotion {
    final FrameLayout.LayoutParams params;
    final View box, clear;
    final EditText field;
    final List<View> fading;
    final int left, right;
    android.animation.ValueAnimator running;
    float fraction;

    SearchMotion(
        FrameLayout.LayoutParams params,
        View box,
        View clear,
        EditText field,
        List<View> fading,
        int left,
        int right) {
      this.params = params;
      this.box = box;
      this.clear = clear;
      this.field = field;
      this.fading = fading;
      this.left = left;
      this.right = right;
    }

    void apply(float f) {
      fraction = f;
      params.leftMargin = Math.round(left * (1 - f));
      params.rightMargin = Math.round(right * (1 - f));
      box.setLayoutParams(params);
      clear.setAlpha(f);
      clear.setClickable(f > .5f);
    }

    void set(boolean open, boolean animated) {
      if (animated && open == searchOpen) return;
      searchOpen = open;
      field.setHint(
          open ? ("chats".equals(page) ? "Поиск по чатам" : "Поиск по заметкам") : "Поиск");
      if (running != null) running.cancel();
      float target = open ? 1 : 0;
      if (!animated || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
        apply(target);
        for (View v : fading) {
          v.animate().cancel();
          v.setAlpha(1 - target);
        }
        return;
      }
      running = android.animation.ValueAnimator.ofFloat(fraction, target);
      running.setDuration(open ? 480 : 320);
      running.setInterpolator(
          open
              ? new android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f)
              : new android.view.animation.PathInterpolator(0.65f, 0f, 0.35f, 1f));
      running.addUpdateListener(a -> apply((float) a.getAnimatedValue()));
      running.start();
      for (View v : fading)
        v.animate().alpha(1 - target).setStartDelay(open ? 0 : 160).setDuration(160).start();
    }
  }

  private TextView smallButton(String label, boolean primary, Runnable click) {
    TextView b = button(label, primary, click);
    b.setTextSize(13);
    b.setMinHeight(dp(38));
    b.setPadding(dp(14), dp(6), dp(14), dp(6));
    return b;
  }

  /** Выпадающее меню колокольчика. Предложения применяются только по явному нажатию. */
  private void showSuggestions(View anchor) {
    if (suggestionsPopup != null && suggestionsPopup.isShowing()) {
      suggestionsPopup.dismiss();
      return;
    }
    ManagerStore manager = new ManagerStore(store);
    List<JSONObject> proposals = manager.pending();
    int width = Math.min(dp(344), getResources().getDisplayMetrics().widthPixels - dp(44));
    LinearLayout panel = column();
    GradientDrawable background = shape(WHITE, 14);
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
      boolean stack = type.equals("link") || type.equals("merge");
      LinearLayout item = column();
      item.setPadding(dp(8), dp(12), dp(8), dp(12));
      LinearLayout top = row();
      top.setGravity(Gravity.TOP);
      ImageView mark = new ImageView(this);
      mark.setScaleType(ImageView.ScaleType.CENTER);
      mark.setBackground(shape(stack ? 0xFFF0E8FA : LIME, 10));
      mark.setImageDrawable(new Glyph(stack ? "notes" : "spark", stack ? 0xFF75449B : GREEN, 20));
      top.addView(mark, new LinearLayout.LayoutParams(dp(36), dp(36)));
      LinearLayout words = column();
      words.setPadding(dp(12), 0, 0, 0);
      TextView name = text(proposalLabel(type), 14, INK);
      name.setTypeface(null, Typeface.BOLD);
      words.addView(name);
      TextView reason = text(p.optString("reason"), 12, MUTED);
      reason.setMaxLines(3);
      reason.setEllipsize(TextUtils.TruncateAt.END);
      words.addView(reason);
      top.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
      item.addView(top);
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
    if (id > 0) {
      LinearLayout toolbar = row();
      ImageButton back = new ImageButton(this);
      back.setImageDrawable(new Glyph("back", GREEN, 22));
      back.setBackground(ripple(LIME, 10));
      back.setContentDescription("Назад");
      back.setOnClickListener(v -> goBack());
      toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
      toolbar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
      TextView menu = button("⋮", false, () -> {});
      menu.setTextSize(24);
      menu.setPadding(0, 0, 0, 0);
      menu.setContentDescription("Меню заметки");
      menu.setOnClickListener(
          v -> {
            PopupMenu popup = new PopupMenu(this, menu);
            popup.getMenu().add("Удалить заметку");
            popup.setOnMenuItemClickListener(
                item -> {
                  deleteNote(id);
                  return true;
                });
            popup.show();
          });
      toolbar.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
      toolbar.setPadding(dp(22), dp(4), dp(22), dp(4));
      root.addView(toolbar, 0);
    } else {
      content.addView(topBar(false, false, true), new LinearLayout.LayoutParams(-1, dp(44)));
      gap(content, 10);
    }
    heading(
        id == 0 ? "Поймать мысль." : "Ваша заметка",
        id == 0 ? "Запишите сейчас. Всё остальное подождёт." : "");
    String p = draftPrefix();
    noteImage = prefs.getString(p + "image", note == null ? "" : note.image);
    titleField = input("Название — необязательно", false);
    titleField.setText(prefs.getString(p + "title", note == null ? "" : note.title));
    titleField.setFilters(new InputFilter[] {new InputFilter.LengthFilter(160)});
    content.addView(titleField, new LinearLayout.LayoutParams(-1, -2));
    gap(content, 12);
    bodyField = input("Что хочется запомнить?", true);
    bodyField.setMinLines(12);
    bodyField.setMinHeight(dp(320));
    bodyField.setText(prefs.getString(p + "body", note == null ? "" : note.body));
    bodyField.setFilters(new InputFilter[] {new InputFilter.LengthFilter(100000)});
    content.addView(bodyField, new LinearLayout.LayoutParams(-1, -2));
    gap(content, 12);
    content.addView(
        button(
            noteImage.isEmpty() ? "Прикрепить изображение" : "Заменить изображение",
            false,
            () -> pickImage(false)));
    if (!noteImage.isEmpty()) {
      gap(content, 8);
      content.addView(imagePreview(noteImage, 220));
      gap(content, 8);
      content.addView(
          button(
              ImageRunner.running ? "Изображение анализируется…" : "Создать заметку по изображению",
              false,
              this::analyzeNoteImage));
      gap(content, 6);
      content.addView(
          button(
              "Убрать изображение",
              false,
              () -> {
                stash();
                noteImage = "";
                prefs.edit().putString(draftPrefix() + "image", "").apply();
                render();
              }));
    }
    gap(content, 12);
    reminder = prefs.getLong(p + "reminder", note == null ? 0 : note.reminder);
    reminderButton = iconButton(reminderLabel(), "clock", this::pickReminder);
    content.addView(reminderButton);
    if (note != null && note.suggestion > 0) {
      gap(content, 12);
      LinearLayout proposal = column();
      pad(proposal, 14);
      proposal.setBackground(shape(LIME, 12));
      secondary(
          proposal, "Предложение ИИ: " + date(note.suggestion) + "\n" + note.suggestionReason);
      gap(proposal, 8);
      proposal.addView(
          button(
              "Использовать дату",
              false,
              () -> {
                if (note.suggestion <= System.currentTimeMillis()) {
                  toast("Предложенное время уже прошло");
                  return;
                }
                reminder = note.suggestion;
                reminderButton.setText(reminderLabel());
                stash();
                if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED)
                  requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 42);
                toast("Напоминание сохранится при выходе из заметки.");
              }));
      gap(proposal, 6);
      proposal.addView(
          button(
              "Отклонить",
              false,
              () -> {
                store.dismissSuggestion(id);
                proposal.setVisibility(View.GONE);
              }));
      content.addView(proposal);
    }
    gap(content, 18);
    if (id == 0) content.addView(button("Сохранить заметку", true, this::saveNote));
    gap(content, 12);
    secondary(
        content,
        id == 0
            ? "Без названия? Возьмём первую строку.\nЧерновик сохраняется на устройстве."
            : "Изменения сохраняются при выходе из заметки.");
    editorActive = true;
    watch(titleField, this::stash);
    watch(bodyField, this::stash);
  }

  private void deleteNote(long id) {
    new AlertDialog.Builder(this)
        .setTitle("Удалить заметку?")
        .setMessage("Заметка и её напоминание будут удалены без возможности восстановления.")
        .setNegativeButton("Отмена", null)
        .setPositiveButton(
            "Удалить",
            (d, w) -> {
              Reminders.cancel(this, id);
              store.delete(id);
              clearDraft(id);
              editorActive = false;
              page = returnPage;
              render();
            })
        .show();
  }

  private boolean persistEditedNote() {
    if (!editorActive || editing == 0 || titleField == null || bodyField == null) return true;
    String title = titleField.getText().toString().trim(),
        body = bodyField.getText().toString().trim();
    try {
      Note old = store.note(editing);
      if (old == null) return true;
      String resolved = Note.autoTitle(title, body);
      if (old.title.equals(resolved)
          && old.body.equals(body)
          && old.reminder == reminder
          && old.image.equals(noteImage)) {
        clearDraft(editing);
        return true;
      }
      store.save(editing, title, body, reminder);
      store.image(editing, noteImage);
      clearDraft(editing);
      Reminders.schedule(this, store.note(editing));
      BackgroundAnalysis.enqueue(this);
      return true;
    } catch (Exception e) {
      stash();
      toast("Не удалось сохранить изменения. Черновик сохранён на устройстве.");
      return false;
    }
  }

  private String reminderLabel() {
    return reminder == 0 ? "Добавить напоминание" : date(reminder);
  }

  private void pickReminder() {
    if (reminder != 0) {
      new AlertDialog.Builder(this)
          .setTitle("Напоминание")
          .setItems(
              new String[] {"Изменить дату и время", "Убрать напоминание"},
              (d, w) -> {
                if (w == 0) datePicker();
                else {
                  reminder = 0;
                  reminderButton.setText(reminderLabel());
                  stash();
                }
              })
          .show();
    } else datePicker();
  }

  private void datePicker() {
    Calendar cal = Calendar.getInstance();
    cal.setTimeInMillis(
        reminder > System.currentTimeMillis() ? reminder : System.currentTimeMillis() + 3600000);
    DatePickerDialog picker =
        new DatePickerDialog(
            this,
            (v, y, m, d) -> {
              cal.set(y, m, d);
              new TimePickerDialog(
                      this,
                      (tv, h, min) -> {
                        cal.set(Calendar.HOUR_OF_DAY, h);
                        cal.set(Calendar.MINUTE, min);
                        cal.set(Calendar.SECOND, 0);
                        cal.set(Calendar.MILLISECOND, 0);
                        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                          toast("Выберите время в будущем");
                          return;
                        }
                        reminder = cal.getTimeInMillis();
                        reminderButton.setText(reminderLabel());
                        stash();
                        if (Build.VERSION.SDK_INT >= 33
                            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                                != PackageManager.PERMISSION_GRANTED)
                          requestPermissions(
                              new String[] {Manifest.permission.POST_NOTIFICATIONS}, 42);
                      },
                      cal.get(Calendar.HOUR_OF_DAY),
                      cal.get(Calendar.MINUTE),
                      true)
                  .show();
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH));
    picker.getDatePicker().setMinDate(System.currentTimeMillis());
    picker.show();
  }

  private void saveNote() {
    String title = titleField.getText().toString(), body = bodyField.getText().toString();
    if (title.trim().isEmpty() && body.trim().isEmpty() && noteImage.isEmpty()) {
      bodyField.setError("Напишите текст или название");
      bodyField.requestFocus();
      return;
    }
    Note old = editing == 0 ? null : store.note(editing);
    if (reminder != 0
        && reminder <= System.currentTimeMillis()
        && (old == null || old.reminder != reminder)) {
      toast("Время напоминания уже прошло. Выберите новое.");
      return;
    }
    try {
      long id = store.save(editing, title, body, reminder);
      store.image(id, noteImage);
      BackgroundAnalysis.enqueue(this);
      Reminders.schedule(this, store.note(id));
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
      if (denied) toast("Сохранено. Разрешите уведомления в настройках Android.");
    } catch (Exception e) {
      toast("Не удалось сохранить заметку. Черновик сохранён.");
    }
  }

  private void openNote(long id) {
    stash();
    hideKeyboard();
    returnPage = page;
    editing = id;
    page = "edit";
    render();
  }

  private void notes() {
    content.addView(topBar(true, true, true), new LinearLayout.LayoutParams(-1, dp(44)));
    EditText find = searchField;
    gap(content, 14);
    LinearLayout controls = row();
    TextView count = text("", 13, MUTED);
    controls.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
    boolean grid = prefs.getBoolean("grid", true);
    TextView switcher =
        iconButton(
            grid ? "Списком" : "Плиткой",
            grid ? "list" : "grid",
            () -> {
              prefs.edit().putBoolean("grid", !grid).apply();
              render();
            });
    controls.addView(switcher);
    content.addView(controls);
    gap(content, 12);
    LinearLayout listing = column();
    // Список прокручивается под плавающей панелью, поэтому внизу оставлен запас.
    listing.setPadding(0, 0, 0, dp(100));
    content.addView(scroll(listing), new LinearLayout.LayoutParams(-1, 0, 1));
    Runnable refresh =
        () -> {
          listing.removeAllViews();
          ManagerStore manager = new ManagerStore(store);
          List<Note> found = search.trim().isEmpty() ? store.notes() : store.searchNotes(search);
          visibleNotes.clear();
          for (Note n : found)
            if (!search.trim().isEmpty() || manager.top(n.id) == 0 || manager.top(n.id) == n.id)
              visibleNotes.add(n);
          count.setText(getString(R.string.notes_count, visibleNotes.size()));
          if (visibleNotes.isEmpty())
            listing.addView(
                text(
                    search.isEmpty() ? "Пока здесь чистый лист." : "Ничего не найдено.",
                    17,
                    MUTED));
          int columns = grid ? (getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2) : 1;
          int width =
              (getResources().getConfiguration().screenWidthDp - 44 - (columns - 1) * 10) / columns;
          LinearLayout line = null;
          int occupied = 0;
          for (Note n : visibleNotes) {
            List<Long> members = manager.members(n.id);
            boolean expanded =
                !members.isEmpty() && manager.expanded(n.id) && search.trim().isEmpty();
            if (expanded) {
              line = null;
              occupied = 0;
              LinearLayout group = column();
              group.addView(text("В стопке: " + members.size() + " исходных заметок", 15, MUTED));
              List<Note> all = new ArrayList<>();
              all.add(n);
              for (long member : members) {
                Note source = store.note(member);
                if (source != null) all.add(source);
              }
              LinearLayout cards = null;
              for (int i = 0; i < all.size(); i++) {
                if (i % columns == 0) {
                  cards = row();
                  group.addView(cards);
                  gap(group, 10);
                }
                View card = i == 0 ? groupedCard(n, grid, true) : noteCard(all.get(i), grid);
                LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(dp(width), card.getLayoutParams().height);
                if (i % columns != 0) lp.leftMargin = dp(10);
                cards.addView(card, lp);
              }
              listing.addView(group);
              gap(listing, 10);
            } else {
              if (line == null || occupied == columns) {
                line = row();
                listing.addView(line);
                gap(listing, 10);
                occupied = 0;
              }
              View card = groupedCard(n, grid, false);
              LinearLayout.LayoutParams lp =
                  new LinearLayout.LayoutParams(dp(width), card.getLayoutParams().height);
              if (occupied > 0) lp.leftMargin = dp(10);
              line.addView(card, lp);
              occupied++;
            }
          }
        };
    refresh.run();
    watch(
        find,
        () -> {
          search = find.getText().toString();
          refresh.run();
        });
  }

  private View noteCard(Note n, boolean grid) {
    LinearLayout card = column();
    pad(card, 16);
    card.setBackground(
        ripple(
            new ManagerStore(store).top(n.id) != 0
                ? 0xFFF0E8FA
                : n.reminder > 0 ? 0xFFEDF0E2 : WHITE,
            12));
    card.setOnClickListener(v -> openNote(n.id));
    TextView title = text(n.title, 17, INK);
    title.setTypeface(null, Typeface.BOLD);
    title.setMaxLines(grid ? 2 : 1);
    title.setEllipsize(TextUtils.TruncateAt.END);
    LinearLayout titleRow = row();
    titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    TextView menu = text("⋮", 24, INK);
    menu.setGravity(Gravity.CENTER);
    menu.setContentDescription("Меню заметки «" + n.title + "»");
    titleRow.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
    menu.setOnClickListener(
        v -> {
          ManagerStore manager = new ManagerStore(store);
          PopupMenu popup = new PopupMenu(this, menu);
          popup
              .getMenu()
              .add("Удалить")
              .setOnMenuItemClickListener(
                  item -> {
                    returnPage = "notes";
                    deleteNote(n.id);
                    return true;
                  });
          long top = manager.top(n.id);
          if (top != 0)
            popup
                .getMenu()
                .add(manager.expanded(top) ? "Сгруппировать" : "Разгруппировать")
                .setOnMenuItemClickListener(
                    item -> {
                      manager.toggle(top);
                      search = "";
                      render();
                      return true;
                    });
          popup.show();
        });
    card.addView(titleRow);
    if (!new ManagerStore(store).members(n.id).isEmpty())
      card.addView(
          text("Исходных: " + new ManagerStore(store).members(n.id).size(), 11, 0xFF75449B));
    gap(card, 8);
    TextView body =
        text(n.body.isEmpty() ? "Без текста" : Retrieval.excerpt(n.body, "", 320), 14, MUTED);
    body.setMaxLines(grid ? 4 : 2);
    body.setEllipsize(TextUtils.TruncateAt.END);
    card.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    gap(card, 12);
    if (n.suggestion > 0) {
      TextView suggestion = text("Есть предложение ИИ", 11, GREEN);
      card.addView(suggestion);
    }
    TextView stamp =
        text(
            n.reminder > 0 ? "Напомнить\n" + date(n.reminder) : shortDate(n.created),
            11,
            n.reminder > 0 ? GREEN : MUTED);
    card.addView(stamp);
    int columns = getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2;
    int tileSize =
        (getResources().getConfiguration().screenWidthDp - 44 - (columns - 1) * 10) / columns;
    int minimum = Math.round(156 * getResources().getConfiguration().fontScale);
    card.setLayoutParams(
        new android.widget.AbsListView.LayoutParams(
            -1, dp(grid ? Math.max(tileSize, minimum) : minimum)));
    card.setContentDescription(
        n.title + ", " + Retrieval.excerpt(n.body, "", 320) + ", создана " + date(n.created));
    return card;
  }

  private View groupedCard(Note n, boolean grid, boolean expanded) {
    View card = noteCard(n, grid);
    int layers = Math.min(3, new ManagerStore(store).members(n.id).size());
    if (layers == 0 || expanded) return card;
    FrameLayout stack = new FrameLayout(this);
    int height = card.getLayoutParams().height;
    stack.setLayoutParams(new LinearLayout.LayoutParams(-1, height + dp(layers * 5)));
    for (int i = layers; i > 0; i--) {
      View layer = new View(this);
      GradientDrawable shape = shape(0xFFE1CEEF, 12);
      shape.setStroke(dp(1), 0xFFAD83CA);
      layer.setBackground(shape);
      FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, height);
      lp.topMargin = dp(i * 5);
      lp.leftMargin = dp(i * 3);
      lp.rightMargin = dp(i * 3);
      stack.addView(layer, lp);
    }
    stack.addView(card, new FrameLayout.LayoutParams(-1, height));
    return stack;
  }

  private String proposalLabel(String type) {
    return switch (type) {
      case "merge" -> "Готовый общий текст";
      case "link" -> "Общая тема";
      case "task" -> "Идея для дела";
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
        new AlertDialog.Builder(this)
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
    secondary(content, "Находит связи и помогает собрать заметки в стопки.");
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
      card.setBackground(shape(WHITE, 12));
      String type = p.optString("type");
      TextView label = text(proposalLabel(type), 18, INK);
      label.setTypeface(null, Typeface.BOLD);
      card.addView(label);
      gap(card, 6);
      card.addView(text(p.optString("reason"), 14, MUTED));
      if (type.equals("link"))
        secondary(card, "ИИ подготовит выжимку отдельным платным запросом. Исходники сохранятся.");
      if (type.equals("merge"))
        secondary(card, "Подготовьте выжимку ИИ и проверьте её перед созданием стопки.");
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
                  : type.equals("link")
                      ? "Подготовить выжимку"
                      : type.equals("merge") ? "Подготовить выжимку" : "Посмотреть черновик",
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
    if (LocalLlm.enabled(this)) {
      toast("Для выжимки выберите Polza.AI в настройках.");
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
                  new AlertDialog.Builder(this)
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
    if ((p.optString("type").equals("link") || p.optString("type").equals("merge"))
        && !p.optBoolean("summaryReady")) {
      prepareSummary(p);
      return;
    }
    LinearLayout form = column();
    pad(form, 20);
    if (p.optString("type").equals("link") || p.optString("type").equals("merge")) {
      secondary(
          form,
          "Сверху стопки появится эта заметка. Исходники останутся под ней; их тексты и напоминания"
              + " сохранятся.");
      gap(form, 12);
    }
    EditText title = input("Заголовок", false), body = input("Текст", true);
    String key =
        (p.optBoolean("summaryReady") ? "managerSummaryDraft." : "managerDraft.") + p.optLong("id");
    title.setText(prefs.getString(key + ".title", p.optString("title")));
    body.setText(prefs.getString(key + ".body", p.optString("body")));
    form.addView(title);
    form.addView(body);
    watch(title, () -> prefs.edit().putString(key + ".title", title.getText().toString()).apply());
    watch(body, () -> prefs.edit().putString(key + ".body", body.getText().toString()).apply());
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(
                p.optString("type").equals("link") || p.optString("type").equals("merge")
                    ? "Новая стопка"
                    : "Сохранить заметку")
            .setView(scroll(form))
            .setNegativeButton("Назад", null)
            .setPositiveButton(
                p.optString("type").equals("link") || p.optString("type").equals("merge")
                    ? "Создать стопку"
                    : "Сохранить",
                null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      try {
                        manager.accept(
                            p.optLong("id"), title.getText().toString(), body.getText().toString());
                        prefs.edit().remove(key + ".title").remove(key + ".body").apply();
                        dialog.dismiss();
                        page = "notes";
                        search = "";
                        render();
                        toast(
                            p.optString("type").equals("link")
                                    || p.optString("type").equals("merge")
                                ? "✓ Стопка создана"
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

  private void chats() {
    content.addView(topBar(true, true, true), new LinearLayout.LayoutParams(-1, dp(44)));
    gap(content, 14);
    content.addView(
        button(
            "Новый чат",
            true,
            () -> {
              chat = 0;
              page = "chat";
              render();
            }));
    gap(content, 22);
    secondary(content, "История чатов");
    gap(content, 12);
    LinearLayout listing = column();
    content.addView(listing);
    EditText find = searchField;
    Runnable refresh =
        () -> {
          listing.removeAllViews();
          String term = chatSearch.trim().toLowerCase(Locale.ROOT);
          boolean any = false;
          for (String[] c : store.chats()) {
            if (!term.isEmpty() && !c[1].toLowerCase(Locale.ROOT).contains(term)) continue;
            any = true;
            long id = Long.parseLong(c[0]);
            LinearLayout item = row();
            pad(item, 14);
            item.setBackground(shape(WHITE, 12));
            LinearLayout words = column();
            TextView title = text(c[1], 16, INK);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(title);
            gap(words, 6);
            secondary(words, date(Long.parseLong(c[2])));
            words.setOnClickListener(
                v -> {
                  chat = id;
                  page = "chat";
                  render();
                });
            words.setFocusable(true);
            item.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            item.addView(chatMenu(id));
            listing.addView(item);
            gap(listing, 10);
          }
          if (!any) {
            gap(listing, 12);
            secondary(
                listing,
                term.isEmpty()
                    ? "Первый разговор ещё впереди.\nОтветы будут ссылаться на ваши заметки."
                    : "Чатов с такими словами нет.");
          }
        };
    refresh.run();
    watch(
        find,
        () -> {
          chatSearch = find.getText().toString();
          refresh.run();
        });
  }

  private TextView chatMenu(long id) {
    TextView menu = button("⋮", false, () -> {});
    menu.setTextSize(24);
    menu.setPadding(0, 0, 0, 0);
    menu.setMinWidth(dp(48));
    menu.setContentDescription("Меню чата");
    menu.setOnClickListener(
        v -> {
          PopupMenu popup = new PopupMenu(this, menu);
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
                new AlertDialog.Builder(this)
                    .setTitle("Удалить этот чат?")
                    .setMessage("Заметки останутся на месте.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton(
                        "Удалить",
                        (d, w) -> {
                          store.deleteChat(id);
                          prefs.edit().remove("chatDraft." + id).apply();
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
        });
    return menu;
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
        new LinearLayout.LayoutParams(dp(44), dp(44)));
    String chatTitle = "Новый чат";
    if (chat > 0)
      for (String[] c : store.chats()) if (Long.parseLong(c[0]) == chat) chatTitle = c[1];
    TextView titleView = text(chatTitle, 16, INK);
    titleView.setTypeface(null, Typeface.BOLD);
    titleView.setSingleLine(true);
    titleView.setEllipsize(TextUtils.TruncateAt.END);
    titleView.setPadding(dp(4), 0, dp(4), 0);
    actions.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
    if (chat > 0) {
      actions.addView(chatMenu(chat));
    }
    content.addView(actions);
    gap(content, 12);
    LinearLayout messages = column();
    ScrollView scroller = scroll(messages);
    content.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
    JSONArray history = chat == 0 ? new JSONArray() : store.messages(chat);
    if (history.length() == 0) {
      gap(messages, 24);
      TextView title = text("Память, с которой\nможно поговорить.", 28, INK);
      title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
      messages.addView(title);
      gap(messages, 12);
      secondary(messages, "Помощник найдёт подходящие заметки и даст ссылки на источники.");
      gap(messages, 22);
      for (String suggestion :
          new String[] {
            "Какие есть рецепты?", "Какой ближайший праздник?", "Что мне нужно сделать?"
          }) {
        messages.addView(
            button(
                suggestion,
                false,
                () -> {
                  chatInput.setText(suggestion);
                  chatInput.requestFocus();
                }));
        gap(messages, 8);
      }
    }
    for (int i = 0; i < history.length(); i++)
      try {
        JSONObject m = history.getJSONObject(i);
        boolean user = m.getString("role").equals("user");
        LinearLayout bubble = column();
        pad(bubble, 16);
        bubble.setBackground(shape(user ? LIME : WHITE, 12));
        secondary(bubble, user ? "Вы" : "Помощник");
        gap(bubble, 8);
        TextView body = text("", 16, INK);
        body.setLineSpacing(dp(4), 1);
        body.setTextIsSelectable(true);
        body.setText(linkedAnswer(m.getString("content"), m.getJSONArray("sources")));
        body.setMovementMethod(LinkMovementMethod.getInstance());
        bubble.addView(body);
        String draftJson = m.optString("draft", "");
        if (!user && !draftJson.isEmpty()) {
          JSONObject draft = new JSONObject(draftJson);
          long message = m.getLong("id");
          gap(bubble, 12);
          secondary(bubble, "Можно сохранить в заметку");
          gap(bubble, 6);
          TextView draftTitle = text(draft.getString("title"), 16, GREEN);
          draftTitle.setTypeface(null, Typeface.BOLD);
          bubble.addView(draftTitle);
          gap(bubble, 6);
          secondary(bubble, Retrieval.excerpt(draft.getString("body"), "", 300));
          gap(bubble, 10);
          bubble.addView(
              button("Проверить и сохранить", false, () -> reviewChatDraft(message, draft)));
          gap(bubble, 6);
          bubble.addView(
              button(
                  "Не сохранять",
                  false,
                  () -> {
                    store.dismissDraft(message);
                    stash();
                    render();
                  }));
        }
        JSONArray sources = m.getJSONArray("sources");
        if (sources.length() > 0) {
          gap(bubble, 12);
          secondary(bubble, "Источники");
          for (int j = 0; j < sources.length(); j++) {
            JSONObject s = sources.getJSONObject(j);
            long id = s.getLong("id");
            gap(bubble, 6);
            LinearLayout source = row();
            source.setMinimumHeight(dp(64));
            pad(source, 12);
            source.setBackground(ripple(LIME, 10));
            source.setGravity(Gravity.CENTER_VERTICAL);
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(new Glyph("notes", GREEN, 22));
            source.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
            LinearLayout words = column();
            words.setPadding(dp(12), 0, dp(8), 0);
            TextView title = text(s.getString("title"), 14, GREEN);
            title.setTypeface(null, Typeface.BOLD);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(title);
            TextView caption = text("Открыть заметку", 11, MUTED);
            words.addView(caption);
            source.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            TextView arrow = text("›", 24, GREEN);
            source.addView(arrow);
            source.setFocusable(true);
            source.setContentDescription("Открыть заметку: " + s.getString("title"));
            source.setOnClickListener(v -> openNote(id));
            bubble.addView(source);
          }
        }
        messages.addView(bubble);
        gap(messages, 12);
      } catch (JSONException ignored) {
      }
    if (ChatRunner.running == chat && chat != 0) {
      ProgressBar progress = new ProgressBar(this);
      messages.addView(progress, new LinearLayout.LayoutParams(dp(28), dp(28)));
      String stage = LocalLlm.stage();
      secondary(messages, stage.isEmpty() ? "Подготавливаю ответ…" : stage);
      gap(messages, 10);
    }
    if (ChatRunner.errorChat == chat && !ChatRunner.error.isEmpty()) {
      TextView error = text(ChatRunner.error, 14, RED);
      messages.addView(error);
      gap(messages, 8);
      if (history.length() > 0) {
        String last = history.optJSONObject(history.length() - 1).optString("content");
        messages.addView(
            button(
                "Повторить запрос",
                false,
                () -> {
                  chatInput.setText(last);
                  send();
                }));
      }
    }
    if (ChatRunner.usageChat == chat && !ChatRunner.usage.isEmpty() && history.length() > 0) {
      secondary(messages, ChatRunner.usage);
      gap(messages, 10);
    }
    gap(content, 8);
    secondary(
        content,
        LocalLlm.enabled(this)
            ? "Локальный режим GGUF · модель отвечает на телефоне"
            : "ИИ получает выбранные фрагменты через Polza.AI. Поиск может найти не всё.");
    gap(content, 8);
    String attached = prefs.getString("chatImage." + chat, "");
    if (!attached.isEmpty()) {
      content.addView(imagePreview(attached, 120));
      content.addView(
          button(
              "Убрать изображение",
              false,
              () -> {
                if (ChatRunner.running == chat) {
                  toast("Дождитесь завершения анализа");
                  return;
                }
                stash();
                prefs.edit().remove("chatImage." + chat).apply();
                render();
              }));
      secondary(
          content,
          "После отправки ИИ создаст отдельную заметку по картинке. Нужна модель с поддержкой"
              + " изображений.");
    }
    gap(content, 8);
    // Одна строка ввода: изображение, поле и отправка.
    chatInput = input("Сообщение или просьба создать заметку…", true);
    chatInput.setMinLines(1);
    chatInput.setMaxLines(4);
    chatInput.setFilters(new InputFilter[] {new InputFilter.LengthFilter(2000)});
    chatInput.setText(prefs.getString("chatDraft." + chat, ""));
    LinearLayout composer = row();
    composer.setGravity(Gravity.BOTTOM);
    ImageView attach = new ImageView(this);
    attach.setScaleType(ImageView.ScaleType.CENTER);
    attach.setImageDrawable(new Glyph("image", GREEN, 22));
    attach.setBackground(ripple(WHITE, 10));
    attach.setContentDescription("Прикрепить изображение");
    attach.setFocusable(true);
    attach.setOnClickListener(v -> pickImage(true));
    composer.addView(attach, new LinearLayout.LayoutParams(dp(50), dp(50)));
    LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, -2, 1);
    inputParams.leftMargin = dp(8);
    inputParams.rightMargin = dp(8);
    composer.addView(chatInput, inputParams);
    ImageView sendButton = new ImageView(this);
    sendButton.setScaleType(ImageView.ScaleType.CENTER);
    sendButton.setImageDrawable(new Glyph("send", WHITE, 22));
    sendButton.setBackground(ripple(GREEN, 10));
    sendButton.setContentDescription(ChatRunner.running == 0 ? "Отправить" : "Ожидаем ответ");
    sendButton.setFocusable(true);
    sendButton.setEnabled(ChatRunner.running == 0);
    sendButton.setAlpha(ChatRunner.running == 0 ? 1 : .4f);
    sendButton.setOnClickListener(v -> send());
    composer.addView(sendButton, new LinearLayout.LayoutParams(dp(50), dp(50)));
    content.addView(composer);
    if (LocalLlm.enabled(this) && ChatRunner.running != 0) {
      gap(content, 6);
      content.addView(
          button(
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
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Заметка из диалога")
            .setView(scroll(form))
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
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
    Set<Long> allowed = new HashSet<>();
    for (int i = 0; i < sources.length(); i++) allowed.add(sources.optJSONObject(i).optLong("id"));
    SpannableStringBuilder out = new SpannableStringBuilder();
    Matcher m = Pattern.compile("\\[\\[note:(\\d+)]]").matcher(raw);
    int last = 0;
    while (m.find()) {
      out.append(raw, last, m.start());
      long id;
      try {
        id = Long.parseLong(m.group(1));
      } catch (NumberFormatException e) {
        last = m.end();
        continue;
      }
      if (allowed.contains(id)) {
        int start = out.length();
        String title = "Заметка";
        for (int i = 0; i < sources.length(); i++) {
          JSONObject source = sources.optJSONObject(i);
          if (source.optLong("id") == id) {
            title = source.optString("title", "Заметка");
            break;
          }
        }
        if (title.length() > 44) title = title.substring(0, 44) + "…";
        out.append("«" + title + "»");
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
            start,
            out.length(),
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
      last = m.end();
    }
    out.append(raw.substring(last));
    return out;
  }

  private void send() {
    String query = chatInput.getText().toString().trim();
    String image = prefs.getString("chatImage." + chat, "");
    if (ChatRunner.running != 0 || (query.isEmpty() && image.isEmpty())) return;
    if (query.isEmpty()) query = "Создай краткую заметку по изображению";
    boolean local = LocalLlm.enabled(this);
    if (local && !image.isEmpty()) {
      toast(
          "Локальная GGUF пока работает только с текстом. Уберите картинку или выберите Polza.AI в"
              + " настройках.");
      return;
    }
    if (local && !LocalLlm.hasModel(this)) {
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
      new AlertDialog.Builder(this)
          .setTitle("Подключите Polza.AI")
          .setMessage("Укажите свой API-ключ и выберите модель в настройках.")
          .setNegativeButton("Позже", null)
          .setPositiveButton("Настройки", (d, w) -> navigate(3))
          .show();
      return;
    }
    if (!local && !prefs.getBoolean("consent", false)) {
      new AlertDialog.Builder(this)
          .setTitle("Отправить заметки в Polza.AI?")
          .setMessage(
              "Ваш вопрос, последние сообщения чата и до 8 найденных фрагментов заметок будут"
                  + " отправлены в Polza.AI и выбранной модели. Запросы оплачиваются с вашего"
                  + " баланса Polza.AI. Прикреплённое изображение также будет отправлено выбранной"
                  + " модели.")
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
        local
            ? "local-gguf:"
                + LocalLlm.model(this).lastModified()
                + ":"
                + LocalLlm.model(this).length()
            : prefs.getString("model", ""),
        query,
        prefs.getInt("maxTokens", 600),
        image);
    render();
  }

  private View imagePreview(String name, int height) {
    ImageView preview = new ImageView(this);
    preview.setAdjustViewBounds(true);
    preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
    preview.setContentDescription("Прикреплённое изображение");
    try {
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inSampleSize = 2;
      preview.setImageBitmap(
          BitmapFactory.decodeFile(ImageFiles.file(this, name).getPath(), options));
    } catch (Exception e) {
      preview.setContentDescription("Изображение недоступно");
    }
    preview.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(height)));
    return preview;
  }

  private void pickImage(boolean forChat) {
    if ((forChat && ChatRunner.running != 0) || ImageRunner.running) {
      toast("Дождитесь завершения анализа");
      return;
    }
    stash();
    pickerPage = forChat ? "chat" : "note";
    pickerTarget = forChat ? chat : editing;
    startActivityForResult(
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*"),
        88);
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
    try {
      String key = Secrets.read(this), model = prefs.getString("model", "");
      if (key.isEmpty() || model.isEmpty()) {
        toast("Сначала сохраните API-ключ и модель с поддержкой изображений в настройках");
        return;
      }
      new AlertDialog.Builder(this)
          .setTitle("Создать заметку по изображению?")
          .setMessage(
              "Картинка будет отправлена в Polza.AI и выбранную модель. С вашего баланса спишется"
                  + " стоимость анализа. Исходная заметка сохранится; результат появится отдельной"
                  + " заметкой.")
          .setNegativeButton("Отмена", null)
          .setPositiveButton(
              "Проанализировать",
              (d, w) -> {
                if (ImageRunner.start(this, editing, key, model, noteImage))
                  toast("Изображение отправлено на анализ");
              })
          .show();
    } catch (Exception e) {
      toast("Не удалось прочитать API-ключ");
    }
  }

  @Override
  public void imageFinished() {
    if (isDestroyed() || isFinishing()) return;
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

  private void settings() {
    content.addView(topBar(true, false, false), new LinearLayout.LayoutParams(-1, dp(44)));
    gap(content, 10);
    heading("По-вашему.", "Меньше лишнего. Больше полезного.");
    Switch local = new Switch(this);
    local.setText(R.string.local_gguf);
    local.setTextColor(INK);
    local.setMinHeight(dp(52));
    local.setChecked(LocalLlm.enabled(this));
    content.addView(local);
    gap(content, 8);
    secondary(
        content,
        "Текстовый ИИ на телефоне, без Polza.AI. ARM64, CPU, контекст 4096 токенов, ответ до 256"
            + " токенов. Начните с небольшой instruct-модели 0.5–1.5B в Q4 до 2 ГБ. Понадобятся"
            + " свободная память и место для копии файла. Картинки и фоновые проверки в этом режиме"
            + " пока недоступны.");
    gap(content, 10);
    secondary(
        content,
        LocalLlm.hasModel(this)
            ? "Модель: "
                + prefs.getString("localModelName", "model.gguf")
                + " ("
                + (LocalLlm.model(this).length() / 1024 / 1024)
                + " МБ)"
            : "GGUF-модель не выбрана");
    gap(content, 10);
    content.addView(
        button(
            "Импортировать GGUF",
            false,
            () -> {
              if (LocalLlm.busy()) {
                toast("Дождитесь завершения локального ответа");
                return;
              }
              startActivityForResult(
                  new Intent(Intent.ACTION_OPEN_DOCUMENT)
                      .addCategory(Intent.CATEGORY_OPENABLE)
                      .setType("*/*"),
                  89);
            }));
    gap(content, 8);
    content.addView(
        button(
            "Удалить локальную модель",
            false,
            () -> {
              if (LocalLlm.busy()) {
                toast("Сначала остановите локальный ответ");
                return;
              }
              new AlertDialog.Builder(this)
                  .setTitle("Удалить GGUF с устройства?")
                  .setMessage(
                      "Будет удалена только копия модели в приложении. Исходный файл и заметки"
                          + " сохранятся.")
                  .setNegativeButton("Отмена", null)
                  .setPositiveButton(
                      "Удалить",
                      (d, w) -> {
                        if (LocalLlm.busy()) {
                          toast("Локальный движок занят");
                          return;
                        }
                        if (LocalLlm.model(this).delete()) {
                          prefs.edit().remove("localModelName").apply();
                          toast("Модель удалена");
                          render();
                        } else toast("Нет модели для удаления");
                      })
                  .show();
            }));
    gap(content, 20);
    secondary(content, "Подключение Polza.AI");
    gap(content, 10);
    EditText key = input("API-ключ Polza.AI", false);
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
        });
    if (!LocalLlm.enabled(this) && !key.getText().toString().trim().isEmpty())
      refresh.post(updateBalance);
    EditText model = input("ID модели, например openai/gpt-4o-mini", false);
    model.setText(prefs.getString("model", ""));
    content.addView(model);
    gap(content, 10);
    TextView load =
        button(
            "Выбрать из каталога моделей",
            false,
            () -> loadModels(key.getText().toString().trim(), model));
    content.addView(load);
    gap(content, 16);
    secondary(content, "Максимальная длина ответа");
    Spinner tokens = new Spinner(this);
    String[] choices = {
      "Коротко — до 300 токенов", "Обычно — до 600 токенов", "Подробно — до 1200 токенов"
    };
    tokens.setAdapter(
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
    int current = prefs.getInt("maxTokens", 600);
    tokens.setSelection(current == 300 ? 0 : current == 1200 ? 2 : 1);
    tokens.setMinimumHeight(dp(50));
    content.addView(tokens);
    gap(content, 12);
    Switch large = new Switch(this);
    secondary(content, "Напоминать до события");
    Spinner lead = new Spinner(this);
    String[] leadLabels = {
      "В момент события",
      "За 5 минут",
      "За 10 минут",
      "За 15 минут",
      "За 30 минут",
      "За 1 час",
      "За 2 часа",
      "За 1 день"
    };
    lead.setAdapter(
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, leadLabels));
    int leadSelection = 0;
    for (int i = 0; i < Reminders.LEAD_MINUTES.length; i++)
      if (Reminders.LEAD_MINUTES[i] == Reminders.leadMinutes(this)) leadSelection = i;
    lead.setSelection(leadSelection);
    lead.setMinimumHeight(dp(50));
    content.addView(lead);
    secondary(
        content,
        "Применяется ко всем будущим событиям. Если выбранное время уже прошло, напоминание придёт"
            + " в ближайшее время. Уже доставленные напоминания не повторяются.");
    gap(content, 16);
    large.setText("Крупный текст заметок");
    large.setTextColor(INK);
    large.setMinHeight(dp(52));
    large.setChecked(prefs.getBoolean("large", false));
    content.addView(large);
    gap(content, 12);
    Switch background = new Switch(this);
    background.setText("Фоновый помощник ИИ");
    background.setTextColor(INK);
    background.setMinHeight(dp(52));
    background.setChecked(prefs.getBoolean("backgroundAi", false));
    content.addView(background);
    Switch manager = new Switch(this);
    manager.setText(R.string.note_manager);
    manager.setTextColor(INK);
    manager.setMinHeight(dp(52));
    manager.setChecked(prefs.getBoolean("managerAi", false));
    manager.setOnCheckedChangeListener(
        (v, checked) -> {
          if (checked && !prefs.getBoolean("managerAi", false))
            new AlertDialog.Builder(this)
                .setTitle("Включить менеджера заметок?")
                .setMessage(
                    "Выбранные тексты из всей коллекции будут передаваться в Polza.AI примерно"
                        + " каждые 6 часов, даже при закрытом приложении. По умолчанию — 4 платных"
                        + " запроса в сутки; дневной лимит можно снять. До 8 заметок и 12 000"
                        + " символов на запрос. Изменения — только после подтверждения. Сохраните"
                        + " настройки для включения.")
                .setPositiveButton("Согласен", (d, w) -> {})
                .setNegativeButton("Отмена", (d, w) -> manager.setChecked(false))
                .setOnCancelListener(d -> manager.setChecked(false))
                .show();
        });
    content.addView(manager);
    secondary(
        content,
        "Менеджер предлагает объединения, связи, дела и вопросы. Сводка доступна в заметках."
            + " Уведомления — не чаще раза в сутки.");
    gap(content, 8);
    secondary(
        content,
        "При включении новые и изменённые заметки передаются в Polza.AI даже при закрытом"
            + " приложении. По умолчанию до 4 платных пакетов в сутки; дневной лимит можно снять."
            + " Проверка примерно раз в 6 часов; Android может отложить её. ИИ улучшает"
            + " автоматические заголовки и предлагает напоминания. События назначаются только после"
            + " подтверждения.");
    gap(content, 10);
    showAnalysisStatus();
    content.addView(quietAction("История проверок", this::showAnalysisLog));
    content.addView(limitButton());
    gap(content, 10);
    content.addView(button("Проверить сейчас", false, this::manualAnalysis));
    secondary(
        content,
        "Ручная проверка запускает названия, напоминания и объединения независимо от фоновых"
            + " переключателей. Включает повтор записей после ошибок.");
    gap(content, 8);
    content.addView(button("Повторить неудачные проверки", false, this::manualAnalysis));
    gap(content, 12);
    content.addView(
        button(
            "Сохранить настройки",
            true,
            () -> {
              try {
                if (local.isChecked() && !LocalLlm.hasModel(this)) {
                  toast("Сначала импортируйте GGUF-модель");
                  return;
                }
                if (!local.isChecked()
                    && (background.isChecked() || manager.isChecked())
                    && (key.getText().toString().trim().isEmpty()
                        || model.getText().toString().trim().isEmpty())) {
                  toast("Для фонового ИИ укажите ключ и модель");
                  return;
                }
                Secrets.save(this, key.getText().toString().trim());
                prefs
                    .edit()
                    .putString("model", model.getText().toString().trim())
                    .putInt(
                        "maxTokens", new int[] {300, 600, 1200}[tokens.getSelectedItemPosition()])
                    .putBoolean("large", large.isChecked())
                    .putInt("reminderLead", Reminders.LEAD_MINUTES[lead.getSelectedItemPosition()])
                    .putBoolean("backgroundAi", background.isChecked())
                    .putBoolean("managerAi", manager.isChecked())
                    .putBoolean("localLlm", local.isChecked())
                    .apply();
                BackgroundAnalysis.configure(this);
                io.execute(
                    () -> {
                      try (Store reminders = new Store(getApplicationContext())) {
                        for (Note n : reminders.notes())
                          if (n.reminder > System.currentTimeMillis()
                              && !Reminders.delivered(getApplicationContext(), n))
                            Reminders.schedule(getApplicationContext(), n);
                      }
                    });
                if (background.isChecked() || manager.isChecked()) {
                  BackgroundAnalysis.enqueue(this);
                  if (Build.VERSION.SDK_INT >= 33
                      && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                          != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 42);
                }
                hideKeyboard();
                toast("Настройки сохранены");
              } catch (Exception e) {
                toast("Не удалось безопасно сохранить ключ");
              }
            }));
    gap(content, 24);
    secondary(
        content,
        "Экономия без подписки приложения\n\n"
            + "Локальный поиск по всем заметкам. До 8 фрагментов и 6 последних сообщений на запрос."
            + " Повторные одинаковые запросы — из кеша в течение суток. Точная цена зависит от"
            + " выбранной модели.");
    gap(content, 20);
    content.addView(
        iconButton(
            "Уведомления Android",
            "clock",
            () -> {
              Intent intent =
                  new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                      .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
              startActivity(intent);
            }));
    gap(content, 10);
    secondary(
        content,
        "Напоминания могут приходить с задержкой из-за энергосбережения Android. После"
            + " принудительной остановки откройте приложение снова.");
    gap(content, 20);
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
    gap(content, 16);
    secondary(
        content,
        "Мимоходом 0.4.2\n"
            + "Заметки и чаты хранятся на устройстве. API-ключ зашифрован Android Keystore."
            + " Удаление приложения удалит локальные данные; предварительно экспортируйте"
            + " заметки.");
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
                  if (isDestroyed() || !page.equals("settings")) return;
                  LinearLayout dialog = column();
                  pad(dialog, 16);
                  EditText filter = input("Поиск модели", false);
                  dialog.addView(filter);
                  ListView list = new ListView(this);
                  ArrayAdapter<String> adapter =
                      new ArrayAdapter<>(
                          this, android.R.layout.simple_list_item_1, new ArrayList<>(ids));
                  list.setAdapter(adapter);
                  dialog.addView(list, new LinearLayout.LayoutParams(-1, dp(350)));
                  AlertDialog picker =
                      new AlertDialog.Builder(this)
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
      io.execute(
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
              prefs.edit().putString("localModelName", name).putBoolean("localLlm", true).apply();
              BackgroundAnalysis.configure(getApplicationContext());
              runOnUiThread(
                  () -> {
                    if (!isDestroyed()) {
                      toast("GGUF скопирован. Локальный режим включён.");
                      if (page.equals("settings")) render();
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
    if (request == 88 && result == RESULT_OK && data != null && data.getData() != null) {
      android.net.Uri uri = data.getData();
      String targetPage = pickerPage;
      long target = pickerTarget;
      toast("Подготавливаю изображение…");
      io.execute(
          () -> {
            try {
              String imported = ImageFiles.importImage(getApplicationContext(), uri);
              if (targetPage.equals("chat"))
                prefs.edit().putString("chatImage." + target, imported).apply();
              else {
                if (target > 0)
                  try (Store images = new Store(getApplicationContext())) {
                    if (images.note(target) != null) images.image(target, imported);
                  }
                prefs.edit().putString("draft." + target + ".image", imported).apply();
              }
              runOnUiThread(
                  () -> {
                    if (isDestroyed()) return;
                    if (targetPage.equals("chat") && page.equals("chat") && chat == target) {
                      stash();
                      render();
                    } else if (targetPage.equals("note") && editorActive && editing == target) {
                      noteImage = imported;
                      stash();
                      render();
                    }
                  });
            } catch (Exception | OutOfMemoryError e) {
              runOnUiThread(
                  () -> {
                    if (!isDestroyed())
                      toast("Не удалось прикрепить изображение. Выберите картинку до 25 МБ.");
                  });
            }
          });
      return;
    }
    if (request == 77 && result == RESULT_OK && data != null && data.getData() != null) {
      android.net.Uri uri = data.getData();
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
                        .put("metadata", Metadata.read(n.metadata)));
              JSONObject export =
                  new JSONObject()
                      .put("version", 3)
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

  private String date(long ms) {
    return new java.text.SimpleDateFormat("d MMM yyyy, HH:mm", new Locale("ru"))
        .format(new Date(ms));
  }

  private String shortDate(long ms) {
    return new java.text.SimpleDateFormat("d MMM yyyy", new Locale("ru")).format(new Date(ms));
  }

  private void toast(String message) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
  }

  private void hideKeyboard() {
    getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(), 0);
  }

  private final class Glyph extends android.graphics.drawable.Drawable {
    private final String name;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;

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
        case "trash" -> {
          c.drawLine(4, 6, 20, 6, paint);
          c.drawRect(7, 6, 17, 21, paint);
          c.drawLine(9, 3, 15, 3, paint);
          c.drawLine(10, 10, 10, 17, paint);
          c.drawLine(14, 10, 14, 17, paint);
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
