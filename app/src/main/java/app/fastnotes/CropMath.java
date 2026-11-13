package app.fastnotes;

/** Арифметика поворота и обрезки изображения без зависимости от Android. */
public final class CropMath {
  /** Наименьшая доля стороны, которую можно оставить при обрезке. */
  public static final float MIN_FRACTION = 0.05f;

  private CropMath() {}

  /** Число четвертей оборота в диапазоне 0–3, в том числе для отрицательных значений. */
  public static int turns(int value) {
    return ((value % 4) + 4) % 4;
  }

  /** Размер после поворота на {@code quarterTurns} четвертей оборота. */
  public static int[] rotatedSize(int width, int height, int quarterTurns) {
    return turns(quarterTurns) % 2 == 0 ? new int[] {width, height} : new int[] {height, width};
  }

  /** Приводит доли {левый, верхний, правый, нижний} к диапазону 0–1 с минимальным размером. */
  public static float[] clamp(float left, float top, float right, float bottom) {
    float l = Math.max(0f, Math.min(left, 1f - MIN_FRACTION));
    float t = Math.max(0f, Math.min(top, 1f - MIN_FRACTION));
    float r = Math.min(1f, Math.max(right, l + MIN_FRACTION));
    float b = Math.min(1f, Math.max(bottom, t + MIN_FRACTION));
    return new float[] {l, t, r, b};
  }

  /** Обрезка не меняет изображение. */
  public static boolean isFull(float[] fractions) {
    return fractions == null
        || (fractions[0] <= 0.001f
            && fractions[1] <= 0.001f
            && fractions[2] >= 0.999f
            && fractions[3] >= 0.999f);
  }

  /** Область в пикселях {x, y, ширина, высота}: не пустая и целиком внутри изображения. */
  public static int[] pixels(int width, int height, float[] fractions) {
    float[] f =
        fractions == null
            ? new float[] {0, 0, 1, 1}
            : clamp(fractions[0], fractions[1], fractions[2], fractions[3]);
    int x = Math.min(width - 1, Math.round(f[0] * width));
    int y = Math.min(height - 1, Math.round(f[1] * height));
    int right = Math.max(x + 1, Math.min(width, Math.round(f[2] * width)));
    int bottom = Math.max(y + 1, Math.min(height, Math.round(f[3] * height)));
    return new int[] {x, y, right - x, bottom - y};
  }
}
