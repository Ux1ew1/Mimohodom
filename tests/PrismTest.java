package app.fastnotes;

/** Проверяет оптику анимации ассистента: дисперсию, закон Снеллиуса и трассировку призмы. */
public final class PrismTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static boolean near(double a, double b, double eps) {
    return Math.abs(a - b) < eps;
  }

  public static void main(String[] args) {
    // Нормальная дисперсия: фиолетовый преломляется сильнее красного.
    check(Prism.index(Prism.VIOLET) > Prism.index(550), "violet index above green");
    check(Prism.index(550) > Prism.index(Prism.RED), "green index above red");

    // Закон Снеллиуса: n1 sin θ1 = n2 sin θ2.
    double theta = Math.toRadians(40);
    double[] t = Prism.refract(Math.sin(theta), Math.cos(theta), 0, -1, 1 / 1.6, null);
    check(near(Math.sin(theta), 1.6 * t[0], 1e-9), "snell law");
    check(near(Math.hypot(t[0], t[1]), 1, 1e-9), "refracted ray is unit");

    // Полное внутреннее отражение из стекла в воздух за критическим углом.
    boolean[] reflected = new boolean[1];
    double steep = Math.toRadians(60);
    double[] r = Prism.refract(Math.sin(steep), Math.cos(steep), 0, -1, 1.6, reflected);
    check(reflected[0], "total internal reflection");
    check(near(r[0], Math.sin(steep), 1e-9) && near(r[1], -Math.cos(steep), 1e-9), "mirror ray");

    // Трассировка через треугольник совпадает с аналитическим углом отклонения.
    double[] tri = Prism.triangle(0, 0, 100, 0);
    double beam = Math.toRadians(-32);
    double dx = Math.cos(beam), dy = Math.sin(beam);
    double lastDeviation = -1;
    for (double nm = Prism.RED; nm >= Prism.VIOLET; nm -= 50) {
      double n = Prism.index(nm);
      // Луч целится в середину левой грани.
      Prism.Path p = Prism.trace(tri, -43.3 - 300 * dx, -25 - 300 * dy, dx, dy, n);
      check(p != null && p.reflections == 0, "ray passes prism at " + nm);
      double traced = Math.atan2(p.dy, p.dx) - beam;
      // Нормаль левой грани направлена под 210°, угол падения — между нею и встречным лучом.
      double incidence = Math.acos(-(dx * Math.cos(Math.toRadians(210)) + dy * Math.sin(Math.toRadians(210))));
      double analytic = Prism.deviation(Math.toRadians(60), incidence, n);
      check(near(traced, analytic, 1e-6), "trace matches analytic deviation at " + nm);
      check(traced > lastDeviation, "shorter waves deviate more at " + nm);
      lastDeviation = traced;
    }

    // Минимальное отклонение: n = sin((A + δ) / 2) / sin(A / 2).
    double n = Prism.index(550), apex = Math.toRadians(60);
    double best = Double.MAX_VALUE;
    for (double i = 0.5; i < 1.5; i += 1e-5) {
      double d = Prism.deviation(apex, i, n);
      if (!Double.isNaN(d)) best = Math.min(best, d);
    }
    check(near(Math.sin((apex + best) / 2) / Math.sin(apex / 2), n, 1e-6), "minimum deviation");

    // Луч мимо призмы не трассируется.
    check(Prism.trace(tri, -300, 500, 1, 0, 1.5) == null, "miss");

    // Цвета: красный, зелёный и фиолетовый края спектра.
    int red = Prism.color(680), green = Prism.color(530), violet = Prism.color(410);
    check(((red >> 16) & 255) > 200 && (red & 255) == 0, "red color");
    check(((green >> 8) & 255) > 200, "green color");
    check((violet & 255) > 150 && ((violet >> 16) & 255) > 0, "violet color");
    check((Prism.color(500) >>> 24) == 255, "opaque color");

    System.out.println("Prism tests passed: " + count);
  }
}
