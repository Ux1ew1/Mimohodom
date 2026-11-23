package app.fastnotes;

/**
 * Вид свёрнутой колоды и её раскрытия веером (docs/AGENT-PLANNER.md, этап 8): 2–3 карточки под
 * верхней, повёрнутые на ±2–4° и сдвинутые на 4–6 dp; при раскрытии исходные карточки «вылетают»
 * из колоды по очереди. Здесь только числа (градусы, dp, мс); рисует MainActivity. Чистая Java без
 * Android.
 */
public final class DeckGeometry {
  /** Сколько слоёв рисуется под верхней карточкой. */
  public static final int MAX_LAYERS = 3;
  /** Наклон слоёв 1..3 (градусы): знак чередуется, чтобы колода выглядела живой, а не «лесенкой». */
  static final float[] ANGLES = {-2.5f, 3f, -4f};
  /** Боковой сдвиг слоёв 1..3 (dp). */
  static final int[] DX = {-2, 3, -3};
  /** Сдвиг вниз на каждый слой (dp). */
  public static final int STEP = 5;
  /** Отступ слоёв от краёв (dp), чтобы повёрнутые углы не обрезались. */
  public static final int INSET = 6;
  /** Длительность раскрытия одной карточки и задержка между карточками (мс). */
  public static final int FAN_MS = 320, FAN_STAGGER = 45;
  /** Больше стольких карточек задержка не растёт: длинная колода не раскрывается вечно. */
  static final int STAGGER_CAP = 6;

  private DeckGeometry() {}

  /** Сколько слоёв рисовать для колоды из {@code members} исходных. */
  public static int layers(int members) {
    return Math.max(0, Math.min(MAX_LAYERS, members));
  }

  /** Наклон слоя {@code layer} (1 — ближний к верхней карточке). */
  public static float angle(int layer) {
    return ANGLES[clamp(layer) - 1];
  }

  public static int dx(int layer) {
    return DX[clamp(layer) - 1];
  }

  public static int dy(int layer) {
    return clamp(layer) * STEP;
  }

  private static int clamp(int layer) {
    return Math.max(1, Math.min(MAX_LAYERS, layer));
  }

  /**
   * Сколько dp добавить под верхней карточкой, чтобы повёрнутые слои не обрезались: сдвиг слоя плюс
   * то, на сколько опускается угол прямоугольника {@code width × height} при повороте.
   */
  public static int extra(int layers, int widthDp, int heightDp) {
    int out = 0;
    for (int i = 1; i <= layers(layers); i++) {
      double a = Math.toRadians(Math.abs(angle(i)));
      double drop = (widthDp - 2.0 * INSET) / 2 * Math.sin(a) + heightDp / 2.0 * (1 - Math.cos(a));
      out = Math.max(out, dy(i) + (int) Math.ceil(drop));
    }
    return out;
  }

  /** Задержка вылета карточки {@code index} (1 — первая исходная) при раскрытии веером. */
  public static int delay(int index) {
    return Math.max(0, Math.min(index, STAGGER_CAP) - 1) * FAN_STAGGER;
  }

  /** С какого наклона карточка {@code index} начинает вылет: как у слоя, из которого она «выходит». */
  public static float fanAngle(int index) {
    return angle(1 + (Math.max(1, index) - 1) % MAX_LAYERS);
  }
}
