package app.fastnotes;

/** Проверяет числа колоды с наклоном (этап 8) без Android. */
public final class DeckGeometryTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  public static void main(String[] args) {
    check(DeckGeometry.layers(0) == 0 && DeckGeometry.layers(1) == 1, "few members");
    check(DeckGeometry.layers(2) == 2 && DeckGeometry.layers(9) == 3, "at most three layers");
    float prev = 0;
    for (int i = 1; i <= 3; i++) {
      float a = DeckGeometry.angle(i);
      check(Math.abs(a) >= 2 && Math.abs(a) <= 4, "angle within 2–4 degrees: " + a);
      check(prev == 0 || Math.signum(a) != Math.signum(prev), "signs alternate");
      prev = a;
      int step = DeckGeometry.dy(i) - DeckGeometry.dy(i - 1 < 1 ? 1 : i - 1);
      check(i == 1 || (step >= 4 && step <= 6), "shift 4–6 dp per layer");
      check(Math.abs(DeckGeometry.dx(i)) <= 3, "small side shift");
    }
    check(DeckGeometry.dy(1) >= 4 && DeckGeometry.dy(1) <= 6, "first layer shift 4–6 dp");
    check(DeckGeometry.angle(0) == DeckGeometry.angle(1) && DeckGeometry.angle(7) == DeckGeometry.angle(3), "layer clamped");
    // Место под повёрнутыми слоями: угол карточки шириной 340 dp при 4° опускается примерно на 11 dp.
    int wide = DeckGeometry.extra(3, 340, 156);
    check(wide >= DeckGeometry.dy(3) + 11 && wide <= DeckGeometry.dy(3) + 14, "extra for a wide card: " + wide);
    int tile = DeckGeometry.extra(3, 160, 160);
    check(tile < wide && tile >= DeckGeometry.dy(3), "tiles need less: " + tile);
    check(DeckGeometry.extra(0, 340, 156) == 0, "no layers no extra");
    check(DeckGeometry.extra(1, 340, 156) < wide, "one layer needs less");
    // Веер
    check(DeckGeometry.delay(1) == 0 && DeckGeometry.delay(2) == DeckGeometry.FAN_STAGGER, "stagger");
    check(DeckGeometry.delay(20) == DeckGeometry.delay(6), "stagger capped");
    check(DeckGeometry.delay(6) + DeckGeometry.FAN_MS <= 600, "whole fan under 0.6 s");
    check(DeckGeometry.fanAngle(1) == DeckGeometry.angle(1) && DeckGeometry.fanAngle(4) == DeckGeometry.angle(1), "fan angle cycles layers");
    System.out.println("DeckGeometryTest: " + count + " checks passed");
  }
}
