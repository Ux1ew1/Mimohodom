package app.fastnotes;

import android.app.Activity;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Работа агента с интерфейсом как у пользователя: обход дерева видов, касания, ввод, жесты.
 * Все методы вызываются на главном потоке, кроме помеченных иначе.
 */
final class AgentUi {
  private AgentUi() {}

  /** Один видимый элемент экрана. */
  static final class Node {
    WeakReference<View> view;
    int ref, window, clickRef = -1;
    String cls, text = "", desc = "", hint = "";
    int x, y, w, h;
    boolean visible, clickable, longClickable, editable, focused, enabled, selected, checked;
    boolean scrollUp, scrollDown, scrollLeft, scrollRight, scroller, secret;

    View get() {
      return view == null ? null : view.get();
    }

    String describe() {
      StringBuilder out = new StringBuilder("@").append(ref).append(' ').append(cls);
      if (!text.isEmpty()) out.append(" \"").append(shorten(text)).append('"');
      if (!desc.isEmpty() && !desc.equals(text)) out.append(" desc=\"").append(shorten(desc)).append('"');
      if (!hint.isEmpty()) out.append(" hint=\"").append(shorten(hint)).append('"');
      StringBuilder flags = new StringBuilder();
      if (clickable) flags.append(" click");
      if (longClickable) flags.append(" longclick");
      if (editable) flags.append(" edit");
      if (focused) flags.append(" focused");
      if (!enabled) flags.append(" disabled");
      if (selected) flags.append(" selected");
      if (checked) flags.append(" checked");
      if (scroller) {
        flags.append(" scroll=");
        if (scrollUp) flags.append('↑');
        if (scrollDown) flags.append('↓');
        if (scrollLeft) flags.append('←');
        if (scrollRight) flags.append('→');
        if (!scrollUp && !scrollDown && !scrollLeft && !scrollRight) flags.append("нет");
      }
      if (!visible) flags.append(" скрыт-за-краем");
      if (flags.length() > 0) out.append(" [").append(flags.substring(1)).append(']');
      out.append(" (").append(x).append(',').append(y).append(' ').append(w).append('x').append(h).append(')');
      if (clickRef >= 0) out.append(" в @").append(clickRef);
      return out.toString();
    }

    String label() {
      String main = !text.isEmpty() ? text : !desc.isEmpty() ? desc : hint;
      return cls + (main.isEmpty() ? "" : " \"" + shorten(main) + "\"");
    }
  }

  /** Окно приложения: основной экран, диалог или всплывающее меню. */
  static final class Win {
    View decor;
    String kind;
    int index;
  }

  private static List<Node> last = new ArrayList<>();
  private static List<Win> lastWindows = new ArrayList<>();
  static volatile String windowsNote = "";

  static String shorten(String value) {
    String flat = value.replace('\n', '⏎');
    return flat.length() <= 160 ? flat : flat.substring(0, 157) + "…(" + value.length() + ")";
  }

  // ===== Окна =====

  /** Окна снизу вверх. Диалоги и меню видны через скрытый список окон; без него — только экран. */
  @SuppressWarnings("unchecked")
  static List<Win> windows(Activity activity) {
    List<View> views = new ArrayList<>();
    windowsNote = "";
    try {
      Class<?> global = Class.forName("android.view.WindowManagerGlobal");
      Object instance = global.getMethod("getInstance").invoke(null);
      Field field = global.getDeclaredField("mViews");
      field.setAccessible(true);
      List<View> all = (List<View>) field.get(instance);
      if (all != null) views.addAll(all);
    } catch (Throwable error) {
      windowsNote = "диалоги и меню могут быть не видны (" + error.getClass().getSimpleName() + ")";
    }
    View decor = activity.getWindow().getDecorView();
    if (!views.contains(decor)) views.add(0, decor);
    List<Win> out = new ArrayList<>();
    for (View view : views) {
      if (view.getVisibility() != View.VISIBLE || view.getWindowToken() == null) continue;
      Win win = new Win();
      win.decor = view;
      win.index = out.size();
      int type = view.getLayoutParams() instanceof WindowManager.LayoutParams p ? p.type : 0;
      win.kind = view == decor ? "экран" : type >= 1000 ? "меню" : "диалог";
      out.add(win);
    }
    return out;
  }

  static Win topModal(List<Win> windows) {
    for (int i = windows.size() - 1; i >= 0; i--)
      if (windows.get(i).kind.equals("диалог")) return windows.get(i);
    return null;
  }

  static Win top(List<Win> windows) {
    return windows.get(windows.size() - 1);
  }

  // ===== Обход дерева =====

  static List<Node> snapshot(Activity activity, boolean everything) {
    List<Node> nodes = new ArrayList<>();
    List<Win> windows = windows(activity);
    for (Win win : windows) walk(win.decor, win.index, null, nodes, everything);
    last = nodes;
    lastWindows = windows;
    return nodes;
  }

  static List<Win> lastWindows() {
    return lastWindows;
  }

  private static void walk(View v, int window, Node parentClick, List<Node> out, boolean everything) {
    if (v.getVisibility() != View.VISIBLE) return;
    Rect visible = new Rect();
    boolean isVisible = v.getGlobalVisibleRect(visible);
    int[] at = new int[2];
    v.getLocationOnScreen(at);
    boolean scroller =
        v instanceof ScrollView
            || v instanceof HorizontalScrollView
            || v instanceof AbsListView
            || v.canScrollVertically(1)
            || v.canScrollVertically(-1)
            || v.canScrollHorizontally(1)
            || v.canScrollHorizontally(-1);
    Node node = new Node();
    node.view = new WeakReference<>(v);
    node.window = window;
    node.cls = v.getClass().getSimpleName().isEmpty() ? "View" : v.getClass().getSimpleName();
    CharSequence description = v.getContentDescription();
    node.desc = description == null ? "" : description.toString();
    if (v instanceof TextView tv) {
      boolean secret =
          tv.getTransformationMethod() instanceof PasswordTransformationMethod
              || (tv.getInputType() & InputType.TYPE_MASK_VARIATION)
                  == InputType.TYPE_TEXT_VARIATION_PASSWORD
              || String.valueOf(tv.getHint()).toLowerCase(Locale.ROOT).contains("ключ");
      node.secret = secret;
      node.text = secret && tv.length() > 0 ? "•••(" + tv.length() + ")" : String.valueOf(tv.getText());
      CharSequence hint = tv.getHint();
      node.hint = hint == null ? "" : hint.toString();
      node.editable = v instanceof EditText;
    }
    node.x = at[0];
    node.y = at[1];
    node.w = v.getWidth();
    node.h = v.getHeight();
    node.visible = isVisible;
    node.clickable = v.isClickable();
    node.longClickable = v.isLongClickable();
    node.focused = v.isFocused();
    node.enabled = v.isEnabled();
    node.selected = v.isSelected();
    node.checked = v instanceof android.widget.Checkable c && c.isChecked();
    node.scroller = scroller;
    node.scrollUp = v.canScrollVertically(-1);
    node.scrollDown = v.canScrollVertically(1);
    node.scrollLeft = v.canScrollHorizontally(-1);
    node.scrollRight = v.canScrollHorizontally(1);
    boolean interesting =
        everything
            || node.clickable
            || node.longClickable
            || node.editable
            || scroller
            || !node.text.isEmpty()
            || !node.desc.isEmpty();
    boolean emit = interesting && (everything || (node.w > 0 && node.h > 0));
    Node click = parentClick;
    if (emit) {
      node.ref = out.size();
      node.clickRef = parentClick == null ? -1 : parentClick.ref;
      out.add(node);
      if (node.clickable) click = node;
    }
    if (v instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), window, click, out, everything);
  }

  static String dumpText(List<Node> nodes, List<Win> windows, boolean everything) {
    StringBuilder out = new StringBuilder();
    int hidden = 0;
    int current = -1;
    for (Node node : nodes) {
      if (!node.visible && !everything) {
        hidden++;
        continue;
      }
      if (node.window != current) {
        current = node.window;
        Win win = windows.get(current);
        out.append("== окно ").append(current).append(": ").append(win.kind).append(" ==\n");
      }
      out.append(node.describe()).append('\n');
    }
    if (hidden > 0)
      out.append("(+").append(hidden).append(" элементов за краем экрана; --all покажет их)\n");
    return out.toString();
  }

  /** Тексты экрана в порядке чтения — быстро понять, что видит пользователь. */
  static String readText(List<Node> nodes, List<Win> windows) {
    StringBuilder out = new StringBuilder();
    int topWindow = windows.isEmpty() ? 0 : windows.size() - 1;
    Win modal = topModal(windows);
    int from = modal != null ? modal.index : 0;
    for (Node node : nodes) {
      if (!node.visible || node.window < from || node.window > topWindow) continue;
      String line = !node.text.isEmpty() ? node.text : node.hint.isEmpty() ? "" : "(" + node.hint + ")";
      if (line.isEmpty() && !node.desc.isEmpty() && node.clickable) line = "[" + node.desc + "]";
      if (!line.isEmpty()) out.append(shorten(line)).append('\n');
    }
    return out.toString();
  }

  // ===== Поиск цели =====

  /**
   * Цель: {@code @N} — ссылка из последнего ui.dump; {@code desc:…}, {@code text:…}, {@code hint:…},
   * {@code class:…} — по полю; без префикса — по тексту, описанию или подсказке. Точное совпадение
   * без учёта регистра важнее вхождения; верхнее окно важнее нижних.
   */
  static List<Node> find(List<Node> nodes, String target, boolean exactOnly) {
    if (target.startsWith("@")) {
      List<Node> hit = new ArrayList<>();
      try {
        int ref = Integer.parseInt(target.substring(1));
        if (ref >= 0 && ref < last.size() && last.get(ref).get() != null) hit.add(last.get(ref));
      } catch (NumberFormatException ignored) {
      }
      return hit;
    }
    String field = "any", needle = target;
    for (String prefix : new String[] {"desc", "text", "hint", "class"})
      if (target.startsWith(prefix + ":")) {
        field = prefix;
        needle = target.substring(prefix.length() + 1);
      }
    needle = needle.toLowerCase(Locale.ROOT);
    List<Node> exact = new ArrayList<>(), partial = new ArrayList<>();
    for (int i = nodes.size() - 1; i >= 0; i--) {
      Node node = nodes.get(i);
      String[] values;
      switch (field) {
        case "desc" -> values = new String[] {node.desc};
        case "text" -> values = new String[] {node.text};
        case "hint" -> values = new String[] {node.hint};
        case "class" -> values = new String[] {node.cls};
        default -> values = new String[] {node.text, node.desc, node.hint};
      }
      boolean isExact = false, isPartial = false;
      for (String value : values) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) continue;
        if (lower.equals(needle)) isExact = true;
        else if (lower.contains(needle)) isPartial = true;
      }
      if (isExact) exact.add(node);
      else if (isPartial && !exactOnly) partial.add(node);
    }
    List<Node> chosen = !exact.isEmpty() ? exact : partial;
    // Порядок: верхнее окно первым, внутри окна — как на экране.
    List<Node> ordered = new ArrayList<>();
    int topWindow = -1;
    for (Node node : chosen) topWindow = Math.max(topWindow, node.window);
    for (int window = topWindow; window >= 0; window--) {
      List<Node> inWindow = new ArrayList<>();
      for (Node node : chosen) if (node.window == window) inWindow.add(node);
      java.util.Collections.reverse(inWindow);
      ordered.addAll(inWindow);
    }
    return ordered;
  }

  /** Элемент, который обработает касание: сам или ближайший нажимаемый предок. */
  static Node clickTarget(Node node) {
    if (node.clickable || node.clickRef < 0 || node.clickRef >= last.size()) return node;
    return last.get(node.clickRef);
  }

  // ===== Касания и жесты =====

  private static void deliver(View decor, MotionEvent event) {
    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    decor.dispatchTouchEvent(event);
    event.recycle();
  }

  static void touch(View decor, int action, long downTime, float screenX, float screenY) {
    int[] origin = new int[2];
    decor.getLocationOnScreen(origin);
    long now = SystemClock.uptimeMillis();
    deliver(decor, MotionEvent.obtain(downTime, now, action, screenX - origin[0], screenY - origin[1], 0));
  }

  /** Середина видимой части элемента в экранных координатах. */
  static float[] center(Node node) {
    View view = node.get();
    Rect visible = new Rect();
    if (view != null && view.getGlobalVisibleRect(visible)) {
      // Видимый прямоугольник задан в координатах окна; переводим в экранные.
      int[] origin = new int[2];
      view.getRootView().getLocationOnScreen(origin);
      return new float[] {visible.exactCenterX() + origin[0], visible.exactCenterY() + origin[1]};
    }
    return new float[] {node.x + node.w / 2f, node.y + node.h / 2f};
  }

  /** Подводит элемент в видимую область, как прокрутка пальцем. Возвращает, виден ли он теперь. */
  static boolean reveal(Node node) {
    View view = node.get();
    if (view == null) return false;
    Rect visible = new Rect();
    if (view.getGlobalVisibleRect(visible)) return true;
    view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true);
    return view.getGlobalVisibleRect(visible);
  }

  static void key(View decor, int code) {
    long now = SystemClock.uptimeMillis();
    decor.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
    decor.dispatchKeyEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0));
  }

  static int keyCode(String name) {
    switch (name.toLowerCase(Locale.ROOT)) {
      case "back":
        return KeyEvent.KEYCODE_BACK;
      case "enter":
        return KeyEvent.KEYCODE_ENTER;
      case "delete":
      case "backspace":
        return KeyEvent.KEYCODE_DEL;
      case "tab":
        return KeyEvent.KEYCODE_TAB;
      case "search":
        return KeyEvent.KEYCODE_SEARCH;
      case "menu":
        return KeyEvent.KEYCODE_MENU;
      case "escape":
        return KeyEvent.KEYCODE_ESCAPE;
      default:
        int code = KeyEvent.keyCodeFromString(name.toUpperCase(Locale.ROOT));
        if (code == KeyEvent.KEYCODE_UNKNOWN) code = KeyEvent.keyCodeFromString("KEYCODE_" + name.toUpperCase(Locale.ROOT));
        return code;
    }
  }

  // ===== Ввод текста =====

  /** Вводит текст так же, как клавиатура: через InputConnection, поэтому срабатывают все слушатели. */
  static String type(EditText field, String value, boolean replace) {
    field.requestFocus();
    if (replace) field.getText().clear();
    field.setSelection(field.length());
    InputConnection connection = field.onCreateInputConnection(new EditorInfo());
    if (connection != null) connection.commitText(value, 1);
    else field.getText().insert(field.length(), value);
    return field.getText().toString();
  }

  static boolean isSecret(View view) {
    return view instanceof TextView tv
        && (tv.getTransformationMethod() instanceof PasswordTransformationMethod
            || String.valueOf(tv.getHint()).toLowerCase(Locale.ROOT).contains("ключ"));
  }
}
