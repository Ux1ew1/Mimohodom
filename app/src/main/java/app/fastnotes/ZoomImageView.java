package app.fastnotes;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** Просмотр изображения: масштаб щипком, перемещение, двойное касание увеличивает и возвращает. */
// Приложение собрано из обычных View без AppCompat-темы, поэтому базовый класс AppCompat не нужен.
@SuppressLint("AppCompatCustomView")
public final class ZoomImageView extends ImageView {
  private static final float MAX_ZOOM = 5f, DOUBLE_TAP_ZOOM = 2.5f;
  private final Matrix matrix = new Matrix();
  private final float[] values = new float[9];
  private final RectF rect = new RectF();
  private final ScaleGestureDetector scaler;
  private final GestureDetector gestures;
  private float fitScale = 1f;

  public ZoomImageView(Context context) {
    super(context);
    setScaleType(ScaleType.MATRIX);
    scaler =
        new ScaleGestureDetector(
            context,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              @Override
              public boolean onScale(ScaleGestureDetector detector) {
                zoomBy(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
              }
            });
    gestures =
        new GestureDetector(
            context,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onDown(MotionEvent e) {
                return true;
              }

              @Override
              public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                matrix.postTranslate(-dx, -dy);
                apply();
                return true;
              }

              @Override
              public boolean onDoubleTap(MotionEvent e) {
                if (scale() > fitScale * 1.05f) fit();
                else zoomBy(DOUBLE_TAP_ZOOM, e.getX(), e.getY());
                return true;
              }
            });
  }

  @Override
  public void setImageBitmap(Bitmap bitmap) {
    super.setImageBitmap(bitmap);
    fit();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    fit();
  }

  private float scale() {
    matrix.getValues(values);
    return values[Matrix.MSCALE_X];
  }

  /** Вписывает изображение в экран по центру. */
  private void fit() {
    Drawable d = getDrawable();
    if (d == null || getWidth() == 0 || getHeight() == 0) return;
    float dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
    if (dw <= 0 || dh <= 0) return;
    fitScale = Math.min(getWidth() / dw, getHeight() / dh);
    matrix.reset();
    matrix.postScale(fitScale, fitScale);
    matrix.postTranslate((getWidth() - dw * fitScale) / 2f, (getHeight() - dh * fitScale) / 2f);
    setImageMatrix(matrix);
  }

  private void zoomBy(float factor, float px, float py) {
    float current = scale();
    if (current <= 0) return;
    float target = Math.max(fitScale, Math.min(current * factor, fitScale * MAX_ZOOM));
    float change = target / current;
    matrix.postScale(change, change, px, py);
    apply();
  }

  /** Не даёт увести изображение за край; если оно меньше экрана, держит по центру. */
  private void apply() {
    Drawable d = getDrawable();
    if (d == null) return;
    rect.set(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
    matrix.mapRect(rect);
    float dx = 0, dy = 0;
    if (rect.width() <= getWidth()) dx = (getWidth() - rect.width()) / 2f - rect.left;
    else if (rect.left > 0) dx = -rect.left;
    else if (rect.right < getWidth()) dx = getWidth() - rect.right;
    if (rect.height() <= getHeight()) dy = (getHeight() - rect.height()) / 2f - rect.top;
    else if (rect.top > 0) dy = -rect.top;
    else if (rect.bottom < getHeight()) dy = getHeight() - rect.bottom;
    matrix.postTranslate(dx, dy);
    setImageMatrix(matrix);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    scaler.onTouchEvent(event);
    if (!scaler.isInProgress()) gestures.onTouchEvent(event);
    return true;
  }

  @Override
  public boolean performClick() {
    return super.performClick();
  }
}
