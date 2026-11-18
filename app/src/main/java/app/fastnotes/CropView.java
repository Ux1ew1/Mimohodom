package app.fastnotes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Показывает изображение и рамку обрезки: углы меняют размер, внутри рамки её можно сдвинуть. */
public final class CropView extends View {
  private static final int NONE = 0, MOVE = 1, TOP_LEFT = 2, TOP_RIGHT = 3, BOTTOM_LEFT = 4, BOTTOM_RIGHT = 5;
  private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Paint dim = new Paint();
  private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint thirds = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF dest = new RectF(), crop = new RectF();
  private final float density, touchRadius, handleRadius;
  private Bitmap bitmap;
  private float[] pending;
  private int mode = NONE;
  private float lastX, lastY;

  public CropView(Context context) {
    super(context);
    density = context.getResources().getDisplayMetrics().density;
    touchRadius = 32 * density;
    handleRadius = 9 * density;
    dim.setColor(0xAA000000);
    line.setColor(Color.WHITE);
    line.setStyle(Paint.Style.STROKE);
    line.setStrokeWidth(2 * density);
    thirds.setColor(0x88FFFFFF);
    thirds.setStyle(Paint.Style.STROKE);
    thirds.setStrokeWidth(1 * density);
    handle.setColor(Color.WHITE);
    ring.setColor(0xFF28634B);
    ring.setStyle(Paint.Style.STROKE);
    ring.setStrokeWidth(2 * density);
  }

  /** Новое изображение сбрасывает рамку на весь кадр. */
  public void setBitmap(Bitmap value) {
    bitmap = value;
    pending = null;
    layoutImage(null);
    invalidate();
  }

  public void resetCrop() {
    crop.set(dest);
    invalidate();
  }

  /** Рамка в долях изображения: {левый, верхний, правый, нижний}. */
  public float[] fractions() {
    if (bitmap == null || dest.width() <= 0 || dest.height() <= 0) return new float[] {0, 0, 1, 1};
    return CropMath.clamp(
        (crop.left - dest.left) / dest.width(),
        (crop.top - dest.top) / dest.height(),
        (crop.right - dest.left) / dest.width(),
        (crop.bottom - dest.top) / dest.height());
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    // Поворот экрана сохраняет выбранную область.
    layoutImage(bitmap == null || oldw == 0 ? pending : fractions());
  }

  private void layoutImage(float[] keep) {
    if (bitmap == null || getWidth() == 0 || getHeight() == 0) {
      pending = keep;
      return;
    }
    float scale = Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
    float w = bitmap.getWidth() * scale, h = bitmap.getHeight() * scale;
    dest.set((getWidth() - w) / 2f, (getHeight() - h) / 2f, (getWidth() + w) / 2f, (getHeight() + h) / 2f);
    if (keep == null) crop.set(dest);
    else
      crop.set(
          dest.left + keep[0] * w,
          dest.top + keep[1] * h,
          dest.left + keep[2] * w,
          dest.top + keep[3] * h);
  }

  private float minSide() {
    return Math.min(Math.min(dest.width(), dest.height()) * CropMath.MIN_FRACTION * 2, 48 * density);
  }

  private int hit(float x, float y) {
    float best = touchRadius;
    int found = NONE;
    float[][] corners = {
      {crop.left, crop.top, TOP_LEFT},
      {crop.right, crop.top, TOP_RIGHT},
      {crop.left, crop.bottom, BOTTOM_LEFT},
      {crop.right, crop.bottom, BOTTOM_RIGHT}
    };
    for (float[] corner : corners) {
      float distance = (float) Math.hypot(x - corner[0], y - corner[1]);
      if (distance < best) {
        best = distance;
        found = (int) corner[2];
      }
    }
    if (found != NONE) return found;
    return crop.contains(x, y) ? MOVE : NONE;
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (bitmap == null) return false;
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN -> {
        mode = hit(event.getX(), event.getY());
        lastX = event.getX();
        lastY = event.getY();
        return true;
      }
      case MotionEvent.ACTION_MOVE -> {
        float dx = event.getX() - lastX, dy = event.getY() - lastY;
        lastX = event.getX();
        lastY = event.getY();
        float min = minSide();
        switch (mode) {
          case MOVE -> {
            float mx = Math.max(dest.left - crop.left, Math.min(dx, dest.right - crop.right));
            float my = Math.max(dest.top - crop.top, Math.min(dy, dest.bottom - crop.bottom));
            crop.offset(mx, my);
          }
          case TOP_LEFT -> {
            crop.left = Math.max(dest.left, Math.min(crop.left + dx, crop.right - min));
            crop.top = Math.max(dest.top, Math.min(crop.top + dy, crop.bottom - min));
          }
          case TOP_RIGHT -> {
            crop.right = Math.min(dest.right, Math.max(crop.right + dx, crop.left + min));
            crop.top = Math.max(dest.top, Math.min(crop.top + dy, crop.bottom - min));
          }
          case BOTTOM_LEFT -> {
            crop.left = Math.max(dest.left, Math.min(crop.left + dx, crop.right - min));
            crop.bottom = Math.min(dest.bottom, Math.max(crop.bottom + dy, crop.top + min));
          }
          case BOTTOM_RIGHT -> {
            crop.right = Math.min(dest.right, Math.max(crop.right + dx, crop.left + min));
            crop.bottom = Math.min(dest.bottom, Math.max(crop.bottom + dy, crop.top + min));
          }
          default -> {
            return true;
          }
        }
        invalidate();
        return true;
      }
      case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        mode = NONE;
        return true;
      }
      default -> {
        return true;
      }
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    if (bitmap == null) return;
    canvas.drawBitmap(bitmap, null, dest, image);
    canvas.drawRect(dest.left, dest.top, dest.right, crop.top, dim);
    canvas.drawRect(dest.left, crop.bottom, dest.right, dest.bottom, dim);
    canvas.drawRect(dest.left, crop.top, crop.left, crop.bottom, dim);
    canvas.drawRect(crop.right, crop.top, dest.right, crop.bottom, dim);
    float thirdW = crop.width() / 3f, thirdH = crop.height() / 3f;
    for (int i = 1; i <= 2; i++) {
      canvas.drawLine(crop.left + thirdW * i, crop.top, crop.left + thirdW * i, crop.bottom, thirds);
      canvas.drawLine(crop.left, crop.top + thirdH * i, crop.right, crop.top + thirdH * i, thirds);
    }
    canvas.drawRect(crop, line);
    float[][] corners = {
      {crop.left, crop.top}, {crop.right, crop.top}, {crop.left, crop.bottom}, {crop.right, crop.bottom}
    };
    for (float[] corner : corners) {
      canvas.drawCircle(corner[0], corner[1], handleRadius, handle);
      canvas.drawCircle(corner[0], corner[1], handleRadius, ring);
    }
  }
}
