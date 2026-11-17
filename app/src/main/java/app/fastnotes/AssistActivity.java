package app.fastnotes;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Экспериментальный голосовой ассистент. Открывается системным действием ASSIST — долгим
 * удержанием кнопки питания, если «Мимоходом» выбран цифровым ассистентом в настройках Android.
 * Слушает речь, показывает распознанный текст и сохраняет его заметкой или отправляет в ИИ-чат.
 */
public final class AssistActivity extends Activity implements RecognitionListener {
  private static final int AUDIO = 41;

  private PrismView prism;
  private TextView hint;
  private EditText transcript;
  private TextView mic;
  private LinearLayout panel;
  private SpeechRecognizer recognizer;
  private boolean listening, closing;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

    FrameLayout root = new FrameLayout(this);
    prism = new PrismView(this);
    root.addView(prism, new FrameLayout.LayoutParams(-1, -1));

    panel = new LinearLayout(this);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setClickable(true);
    panel.setPadding(dp(24), dp(16), dp(24), dp(20));
    hint = new TextView(this);
    hint.setTextColor(0xA6FFFFFF);
    hint.setTextSize(14);
    hint.setLetterSpacing(0.02f);
    panel.addView(hint);

    transcript = new EditText(this);
    transcript.setBackground(null);
    transcript.setPadding(0, dp(8), 0, dp(12));
    transcript.setTextColor(Color.WHITE);
    transcript.setHintTextColor(0x66FFFFFF);
    transcript.setHint("Говорите…");
    transcript.setTextSize(24);
    transcript.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
    transcript.setMaxLines(6);
    transcript.setOnFocusChangeListener(
        (v, focused) -> {
          // Пользователь решил поправить текст руками — микрофон больше не мешает.
          if (focused && listening) stopListening();
        });
    panel.addView(transcript, new LinearLayout.LayoutParams(-1, -2));

    LinearLayout actions = new LinearLayout(this);
    actions.setGravity(Gravity.CENTER_VERTICAL);
    mic = pill("🎙", false);
    mic.setContentDescription("Слушать снова");
    mic.setOnClickListener(v -> startListening());
    actions.addView(mic, new LinearLayout.LayoutParams(dp(52), dp(48)));
    View spacer = new View(this);
    actions.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
    TextView note = pill("В заметку", false);
    note.setOnClickListener(v -> saveNote());
    LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-2, dp(48));
    noteParams.rightMargin = dp(10);
    actions.addView(note, noteParams);
    TextView ask = pill("Спросить ИИ", true);
    ask.setOnClickListener(v -> askChat());
    actions.addView(ask, new LinearLayout.LayoutParams(-2, dp(48)));
    panel.addView(actions, new LinearLayout.LayoutParams(-1, -2));

    FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    root.addView(panel, panelParams);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          panel.setPadding(
              dp(24),
              dp(16),
              dp(24),
              dp(20) + Math.max(insets.getSystemWindowInsetBottom(), 0));
          return insets;
        });
    // Касание тёмной области над панелью закрывает ассистента.
    prism.setOnClickListener(v -> close());
    setContentView(root);
    panel.setAlpha(0);
    panel.setTranslationY(dp(24));
    panel.animate().alpha(1).translationY(0).setStartDelay(180).setDuration(420).start();
    if (android.os.Build.VERSION.SDK_INT >= 33)
      getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::close);

    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
      startListening();
    else requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, AUDIO);
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(code, permissions, results);
    if (code != AUDIO) return;
    if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startListening();
    else typeInstead("Нет доступа к микрофону. Напишите текстом.");
  }

  private int dp(float v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  private TextView pill(String label, boolean primary) {
    TextView b = new TextView(this);
    b.setText(label);
    b.setGravity(Gravity.CENTER);
    b.setTextSize(15);
    b.setTypeface(null, Typeface.BOLD);
    b.setPadding(dp(18), 0, dp(18), 0);
    b.setTextColor(primary ? 0xFF0B1A13 : Color.WHITE);
    GradientDrawable bg = new GradientDrawable();
    bg.setCornerRadius(dp(14));
    if (primary) {
      bg.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
      bg.setColors(new int[] {0xFFB8F2D2, 0xFFE9F7B6});
    } else {
      bg.setColor(0x1FFFFFFF);
      bg.setStroke(dp(1), 0x40FFFFFF);
    }
    b.setBackground(bg);
    return b;
  }

  private void startListening() {
    if (closing) return;
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, AUDIO);
      return;
    }
    if (!SpeechRecognizer.isRecognitionAvailable(this)) {
      typeInstead("Распознавание речи недоступно на телефоне. Напишите текстом.");
      return;
    }
    hideKeyboard();
    transcript.clearFocus();
    if (recognizer == null) {
      recognizer = SpeechRecognizer.createSpeechRecognizer(this);
      recognizer.setRecognitionListener(this);
    }
    Intent intent =
        new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
    listening = true;
    hint.setText("Слушаю…");
    prism.setState(PrismView.LISTENING);
    mic.setAlpha(0.45f);
    try {
      recognizer.startListening(intent);
    } catch (RuntimeException e) {
      typeInstead("Не удалось включить микрофон. Напишите текстом.");
    }
  }

  private void stopListening() {
    listening = false;
    mic.setAlpha(1);
    if (recognizer != null) recognizer.cancel();
    prism.setState(PrismView.IDLE);
    hint.setText("Можно поправить текст");
  }

  private void typeInstead(String message) {
    listening = false;
    mic.setAlpha(1);
    prism.setState(PrismView.IDLE);
    hint.setText(message);
    transcript.requestFocus();
    transcript.post(
        () -> getSystemService(InputMethodManager.class).showSoftInput(transcript, 0));
  }

  private void hideKeyboard() {
    getSystemService(InputMethodManager.class).hideSoftInputFromWindow(transcript.getWindowToken(), 0);
  }

  private String text() {
    return transcript.getText().toString().trim();
  }

  private void saveNote() {
    String body = text();
    if (body.isEmpty()) {
      hint.setText("Сначала скажите или напишите текст");
      return;
    }
    if (listening) stopListening();
    try (Store store = new Store(this)) {
      store.save(0, "", body, 0);
      BackgroundAnalysis.enqueue(this);
    } catch (Exception e) {
      hint.setText("Не удалось сохранить заметку");
      return;
    }
    hideKeyboard();
    hint.setText("✓ Сохранено в заметки");
    prism.setState(PrismView.DONE);
    prism.postDelayed(this::close, 900);
  }

  private void askChat() {
    String query = text();
    if (query.isEmpty()) {
      hint.setText("Сначала скажите или напишите вопрос");
      return;
    }
    if (listening) stopListening();
    prism.setState(PrismView.THINKING);
    startActivity(
        new Intent(this, MainActivity.class)
            .setAction("app.fastnotes.action.NEW_CHAT")
            .putExtra(MainActivity.ASSIST_QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    finish();
    overridePendingTransition(0, android.R.anim.fade_out);
  }

  private void close() {
    if (closing) return;
    closing = true;
    if (recognizer != null) recognizer.cancel();
    hideKeyboard();
    panel.animate().alpha(0).translationY(dp(16)).setStartDelay(0).setDuration(200).start();
    prism.leave(
        () -> {
          finish();
          overridePendingTransition(0, 0);
        });
  }

  @Override
  public void onBackPressed() {
    close();
  }

  @Override
  protected void onStop() {
    super.onStop();
    // Ассистент — одноразовое окно: при уходе с экрана он закрывается.
    if (!isChangingConfigurations() && !isFinishing()) finish();
  }

  @Override
  protected void onDestroy() {
    if (recognizer != null) recognizer.destroy();
    recognizer = null;
    super.onDestroy();
  }

  @Override
  public void onReadyForSpeech(Bundle params) {
    hint.setText("Слушаю…");
  }

  @Override
  public void onBeginningOfSpeech() {}

  @Override
  public void onRmsChanged(float rmsdB) {
    prism.setLevel((rmsdB + 2) / 12f);
  }

  @Override
  public void onBufferReceived(byte[] buffer) {}

  @Override
  public void onEndOfSpeech() {
    if (!listening) return;
    hint.setText("Распознаю…");
    prism.setState(PrismView.THINKING);
  }

  @Override
  public void onError(int error) {
    if (!listening) return;
    listening = false;
    mic.setAlpha(1);
    prism.setState(PrismView.IDLE);
    hint.setText(
        switch (error) {
          case SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
              "Не расслышал. Нажмите микрофон или напишите текстом.";
          case SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
              "Нет сети для распознавания. Напишите текстом.";
          case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону.";
          case SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Микрофон занят. Попробуйте ещё раз.";
          default -> "Распознавание прервалось. Нажмите микрофон.";
        });
  }

  @Override
  public void onResults(Bundle results) {
    if (!listening) return;
    listening = false;
    mic.setAlpha(1);
    show(results);
    prism.setState(PrismView.IDLE);
    hint.setText(text().isEmpty() ? "Ничего не услышал" : "Сохранить или спросить ИИ?");
  }

  @Override
  public void onPartialResults(Bundle partial) {
    if (listening) show(partial);
  }

  private void show(Bundle bundle) {
    ArrayList<String> list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
    if (list == null || list.isEmpty() || list.get(0).trim().isEmpty()) return;
    String value = list.get(0).trim();
    value = Character.toUpperCase(value.charAt(0)) + value.substring(1);
    transcript.setText(value);
    transcript.setSelection(value.length());
  }

  @Override
  public void onEvent(int type, Bundle params) {}
}
