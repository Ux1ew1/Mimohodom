package app.fastnotes;

import android.content.Context;
import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ReplacementSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.EditText;
import java.util.ArrayList;
import java.util.List;

/** Renders note markup in place so editing and persistence share the same text offsets. */
@SuppressLint("AppCompatCustomView") // MainActivity uses the framework theme and widgets.
final class NoteBodyEditText extends EditText {
  private final List<Object> decorations = new ArrayList<>();
  private final float tapSlop;
  private final int accent;
  private MarkerSpan pressedCheck;
  private boolean checkGesture;
  private float downX, downY;

  NoteBodyEditText(Context context, int accent) {
    super(context);
    this.accent = accent;
    tapSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
      @Override public void afterTextChanged(Editable value) {
        renderMarkup(value);
      }
    });
  }

  private void renderMarkup(Editable value) {
    for (Object decoration : decorations) value.removeSpan(decoration);
    decorations.clear();
    for (TextFormat.Decoration decoration : TextFormat.decorations(value.toString())) {
      Object span = switch (decoration.kind()) {
        case TextFormat.BOLD -> new StyleSpan(Typeface.BOLD);
        case TextFormat.ITALIC -> new StyleSpan(Typeface.ITALIC);
        case TextFormat.STRIKE -> new StrikethroughSpan();
        default -> new MarkerSpan(decoration.kind(), accent);
      };
      value.setSpan(span, decoration.start(), decoration.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      decorations.add(span);
    }
  }

  @Override
  public boolean performClick() {
    return super.performClick();
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN -> {
        downX = event.getX();
        downY = event.getY();
        pressedCheck = checkAt(downX, downY);
        checkGesture = pressedCheck != null;
      }
      case MotionEvent.ACTION_MOVE -> {
        if (Math.hypot(event.getX() - downX, event.getY() - downY) > tapSlop) pressedCheck = null;
      }
      case MotionEvent.ACTION_POINTER_DOWN -> pressedCheck = null;
      case MotionEvent.ACTION_CANCEL -> {
        if (checkGesture) {
          checkGesture = false;
          pressedCheck = null;
          return true;
        }
      }
      case MotionEvent.ACTION_UP -> {
        if (checkGesture) {
          MarkerSpan hit = checkAt(event.getX(), event.getY());
          if (hit != null && hit == pressedCheck
              && Math.hypot(event.getX() - downX, event.getY() - downY) <= tapSlop) {
            Editable value = getText();
            int start = value.getSpanStart(hit);
            char next = TextFormat.toggledCheck(value, start);
            if (next != 0) {
              value.replace(start + 3, start + 4, String.valueOf(next));
              performClick();
            }
          }
          checkGesture = false;
          pressedCheck = null;
          return true;
        }
      }
    }
    return checkGesture || super.onTouchEvent(event);
  }

  private MarkerSpan checkAt(float x, float y) {
    Layout layout = getLayout();
    if (layout == null) return null;
    float horizontal = x - getTotalPaddingLeft() + getScrollX();
    float vertical = y - getTotalPaddingTop() + getScrollY();
    if (vertical < 0 || vertical >= layout.getHeight()) return null;
    int line = layout.getLineForVertical((int) vertical);
    Editable value = getText();
    Path path = new Path();
    RectF bounds = new RectF();
    for (MarkerSpan span : value.getSpans(
        layout.getLineStart(line), layout.getLineEnd(line), MarkerSpan.class)) {
      if (!span.check()) continue;
      layout.getSelectionPath(value.getSpanStart(span), value.getSpanEnd(span), path);
      path.computeBounds(bounds, true);
      if (bounds.contains(horizontal, vertical)) return span;
    }
    return null;
  }

  private static final class MarkerSpan extends ReplacementSpan {
    private final int kind, accent;

    MarkerSpan(int kind, int accent) {
      this.kind = kind;
      this.accent = accent;
    }

    boolean check() {
      return kind == TextFormat.CHECK || kind == TextFormat.CHECKED;
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
      if (fm != null) paint.getFontMetricsInt(fm);
      if (kind == TextFormat.HIDDEN) return 0;
      return (int) Math.ceil(paint.getTextSize() * (check() ? 1f : 0.5f));
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
        int bottom, Paint paint) {
      if (kind == TextFormat.HIDDEN) return;
      float size = paint.getTextSize();
      float centerY = y + (paint.ascent() + paint.descent()) / 2;
      Paint icon = new Paint(paint);
      icon.setColor(accent);
      if (kind == TextFormat.BULLET) {
        icon.setStyle(Paint.Style.FILL);
        canvas.drawCircle(x + size * 0.25f, centerY, size * 0.115f, icon);
        return;
      }
      float half = size * 0.36f;
      float centerX = x + size * 0.5f;
      icon.setStyle(Paint.Style.STROKE);
      icon.setStrokeWidth(size * 0.08f);
      canvas.drawRoundRect(centerX - half, centerY - half, centerX + half, centerY + half,
          size * 0.12f, size * 0.12f, icon);
      if (kind == TextFormat.CHECKED) {
        icon.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(centerX - half * 0.52f, centerY, centerX - half * 0.12f,
            centerY + half * 0.36f, icon);
        canvas.drawLine(centerX - half * 0.12f, centerY + half * 0.36f, centerX + half * 0.55f,
            centerY - half * 0.38f, icon);
      }
    }
  }
}
