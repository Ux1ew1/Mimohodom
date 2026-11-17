package app.fastnotes;

/**
 * Оптика стеклянной призмы для анимации ассистента: показатель преломления по формуле Коши,
 * закон Снеллиуса в векторной форме, полное внутреннее отражение и цвет длины волны. Не
 * зависит от Android, поэтому проверяется обычным тестом.
 */
public final class Prism {
  /** Коэффициенты Коши n(λ) = A + B / λ², λ в микрометрах. Дисперсия усилена ради зрелищности. */
  public static final double CAUCHY_A = 1.50, CAUCHY_B = 0.05;

  /** Видимый диапазон, нм. */
  public static final double VIOLET = 400, RED = 700;

  private Prism() {}

  public static double index(double nm) {
    double um = nm / 1000.0;
    return CAUCHY_A + CAUCHY_B / (um * um);
  }

  /**
   * Угол отклонения луча призмой с преломляющим углом {@code apex} при угле падения {@code
   * incidence} (радианы). Возвращает NaN при полном внутреннем отражении на второй грани.
   */
  public static double deviation(double apex, double incidence, double n) {
    double inside = Math.asin(Math.sin(incidence) / n);
    double second = apex - inside;
    double s = n * Math.sin(second);
    if (Math.abs(s) > 1) return Double.NaN;
    return incidence + Math.asin(s) - apex;
  }

  /**
   * Преломляет единичный вектор {@code d} на поверхности с единичной нормалью {@code nx, ny},
   * направленной навстречу лучу. {@code eta} — отношение n1 / n2. При полном внутреннем
   * отражении возвращает отражённый луч и {@code reflected[0] = true}.
   */
  public static double[] refract(
      double dx, double dy, double nx, double ny, double eta, boolean[] reflected) {
    double cosi = -(dx * nx + dy * ny);
    double k = 1 - eta * eta * (1 - cosi * cosi);
    if (k < 0) {
      if (reflected != null) reflected[0] = true;
      return new double[] {dx + 2 * cosi * nx, dy + 2 * cosi * ny};
    }
    if (reflected != null) reflected[0] = false;
    double f = eta * cosi - Math.sqrt(k);
    return new double[] {eta * dx + f * nx, eta * dy + f * ny};
  }

  /** Путь луча сквозь треугольник: точки ломаной и направление после выхода. */
  public static final class Path {
    public final double[] points;
    public final int count;
    public final double dx, dy;
    public final int reflections;

    Path(double[] points, int count, double dx, double dy, int reflections) {
      this.points = points;
      this.count = count;
      this.dx = dx;
      this.dy = dy;
      this.reflections = reflections;
    }

    public double exitX() {
      return points[(count - 1) * 2];
    }

    public double exitY() {
      return points[(count - 1) * 2 + 1];
    }
  }

  /**
   * Трассирует луч из точки (ox, oy) с направлением (dx, dy) через треугольник {@code tri} = {x0,
   * y0, x1, y1, x2, y2} с показателем {@code n}. Возвращает null, если луч не попадает в призму.
   */
  public static Path trace(double[] tri, double ox, double oy, double dx, double dy, double n) {
    double len = Math.hypot(dx, dy);
    dx /= len;
    dy /= len;
    double cx = (tri[0] + tri[2] + tri[4]) / 3, cy = (tri[1] + tri[3] + tri[5]) / 3;
    double[] pts = new double[2 * 8];
    int count = 0;
    pts[count * 2] = ox;
    pts[count * 2 + 1] = oy;
    count++;
    double[] hit = nearest(tri, ox, oy, dx, dy, -1);
    if (hit == null) return null;
    int edge = (int) hit[2];
    double px = hit[0], py = hit[1];
    pts[count * 2] = px;
    pts[count * 2 + 1] = py;
    count++;
    double[] nrm = outward(tri, edge, cx, cy);
    boolean[] reflected = new boolean[1];
    double[] d = refract(dx, dy, nrm[0], nrm[1], 1 / n, reflected);
    int reflections = 0;
    for (int bounce = 0; bounce < 5; bounce++) {
      hit = nearest(tri, px, py, d[0], d[1], edge);
      if (hit == null) break;
      edge = (int) hit[2];
      px = hit[0];
      py = hit[1];
      pts[count * 2] = px;
      pts[count * 2 + 1] = py;
      count++;
      nrm = outward(tri, edge, cx, cy);
      // Изнутри нормаль, обращённая навстречу лучу, смотрит внутрь призмы.
      d = refract(d[0], d[1], -nrm[0], -nrm[1], n, reflected);
      if (!reflected[0]) {
        double l = Math.hypot(d[0], d[1]);
        return new Path(pts, count, d[0] / l, d[1] / l, reflections);
      }
      reflections++;
    }
    return null;
  }

  private static double[] outward(double[] tri, int edge, double cx, double cy) {
    double ax = tri[edge * 2], ay = tri[edge * 2 + 1];
    double bx = tri[((edge + 1) % 3) * 2], by = tri[((edge + 1) % 3) * 2 + 1];
    double nx = by - ay, ny = -(bx - ax);
    double l = Math.hypot(nx, ny);
    nx /= l;
    ny /= l;
    if ((ax - cx) * nx + (ay - cy) * ny < 0) {
      nx = -nx;
      ny = -ny;
    }
    return new double[] {nx, ny};
  }

  /** Ближайшее пересечение луча с гранями, кроме {@code skip}: {x, y, индекс грани}. */
  private static double[] nearest(
      double[] tri, double ox, double oy, double dx, double dy, int skip) {
    double best = Double.MAX_VALUE;
    double[] out = null;
    for (int e = 0; e < 3; e++) {
      if (e == skip) continue;
      double ax = tri[e * 2], ay = tri[e * 2 + 1];
      double bx = tri[((e + 1) % 3) * 2], by = tri[((e + 1) % 3) * 2 + 1];
      double ex = bx - ax, ey = by - ay;
      double den = dx * ey - dy * ex;
      if (Math.abs(den) < 1e-12) continue;
      double t = ((ax - ox) * ey - (ay - oy) * ex) / den;
      double u = ((ax - ox) * dy - (ay - oy) * dx) / den;
      if (t > 1e-7 && u >= 0 && u <= 1 && t < best) {
        best = t;
        out = new double[] {ox + t * dx, oy + t * dy, e};
      }
    }
    return out;
  }

  /** Вершины правильного треугольника вершиной вверх, повёрнутого на {@code rotation}. */
  public static double[] triangle(double cx, double cy, double radius, double rotation) {
    double[] tri = new double[6];
    for (int i = 0; i < 3; i++) {
      double a = -Math.PI / 2 + rotation + i * 2 * Math.PI / 3;
      tri[i * 2] = cx + radius * Math.cos(a);
      tri[i * 2 + 1] = cy + radius * Math.sin(a);
    }
    return tri;
  }

  /** Приближённый цвет длины волны (380–780 нм) в формате ARGB с непрозрачностью 255. */
  public static int color(double nm) {
    double r, g, b;
    if (nm < 440) {
      r = -(nm - 440) / 60;
      g = 0;
      b = 1;
    } else if (nm < 490) {
      r = 0;
      g = (nm - 440) / 50;
      b = 1;
    } else if (nm < 510) {
      r = 0;
      g = 1;
      b = -(nm - 510) / 20;
    } else if (nm < 580) {
      r = (nm - 510) / 70;
      g = 1;
      b = 0;
    } else if (nm < 645) {
      r = 1;
      g = -(nm - 645) / 65;
      b = 0;
    } else {
      r = 1;
      g = 0;
      b = 0;
    }
    double f;
    if (nm < 420) f = 0.3 + 0.7 * (nm - 380) / 40;
    else if (nm > 700) f = 0.3 + 0.7 * (780 - nm) / 80;
    else f = 1;
    return 0xFF000000 | (channel(r, f) << 16) | (channel(g, f) << 8) | channel(b, f);
  }

  private static int channel(double c, double f) {
    if (c <= 0) return 0;
    return (int) Math.round(255 * Math.pow(Math.max(0, Math.min(1, c * f)), 0.8));
  }
}
