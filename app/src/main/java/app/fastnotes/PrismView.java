package app.fastnotes;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import java.util.Random;

/**
 * Анимация голосового ассистента: луч белого света входит в стеклянную призму, преломляется
 * по закону Снеллиуса и раскладывается в спектр. Геометрия лучей считается в {@link Prism};
 * громкость голоса усиливает свечение и слегка раскачивает призму.
 */
final class PrismView extends View {
  static final int IDLE = 0, LISTENING = 1, THINKING = 2, DONE = 3;

  private static final int RAYS = 32;
  private static final int SPARKS = 46;
  private static final double BEAM = Math.toRadians(-32);

  private final float density;
  private final int[] colors = new int[RAYS];
  private final double[] index = new double[RAYS];
  private final Prism.Path[] paths = new Prism.Path[RAYS];
  private final float[] sparkU = new float[SPARKS], sparkV = new float[SPARKS];
  private final float[] sparkPhase = new float[SPARKS], sparkSpeed = new float[SPARKS];
  private final Paint scrim = new Paint(), add = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path shape = new Path(), wedge = new Path();
  private final Matrix matrix = new Matrix();
  private final float[] widths = new float[3];
  private static final float[] BEAM_ALPHA = {0.08f, 0.2f, 0.95f};
  // Шейдеры создаются один раз в единичных координатах и ставятся на место матрицей,
  // чтобы кадр анимации не выделял память.
  private final Shader glow = new RadialGradient(0, 0, 1, 0xFFFFFFFF, 0, Shader.TileMode.CLAMP);
  private final Shader beamFade = new LinearGradient(0, 0, 1, 0, 0, 0xFFFFFFFF, Shader.TileMode.CLAMP);
  private final Shader glassFill =
      new LinearGradient(0, 0, 1, 0, 0x33E8F4FF, 0x0F9FB8D0, Shader.TileMode.CLAMP);
  private final Shader[] band = new Shader[RAYS], thread = new Shader[RAYS];
  private SweepGradient halo;
  private int state = IDLE;
  private float level, rawLevel, energy, flash;
  private boolean leaving;
  private long started, lastFrame;

  PrismView(Context context) {
    super(context);
    density = context.getResources().getDisplayMetrics().density;
    for (int i = 0; i < RAYS; i++) {
      double nm = Prism.RED - (Prism.RED - Prism.VIOLET) * i / (RAYS - 1.0);
      colors[i] = Prism.color(nm);
      index[i] = Prism.index(nm);
      int c = colors[i];
      band[i] =
          new LinearGradient(
              0,
              0,
              1,
              0,
              new int[] {c, alpha(c, 0.35f), alpha(c, 0)},
              new float[] {0, 0.35f, 1},
              Shader.TileMode.CLAMP);
      thread[i] = new LinearGradient(0, 0, 1, 0, c, alpha(c, 0), Shader.TileMode.CLAMP);
    }
    Random random = new Random(7);
    for (int i = 0; i < SPARKS; i++) {
      sparkU[i] = random.nextFloat();
      sparkV[i] = random.nextFloat();
      sparkPhase[i] = random.nextFloat() * 6.283f;
      sparkSpeed[i] = 0.025f + random.nextFloat() * 0.06f;
    }
    if (Build.VERSION.SDK_INT >= 29) add.setBlendMode(BlendMode.PLUS);
    else add.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
    line.setStyle(Paint.Style.STROKE);
    line.setStrokeCap(Paint.Cap.ROUND);
    if (Build.VERSION.SDK_INT >= 29) line.setBlendMode(BlendMode.PLUS);
    else line.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
    edge.setStyle(Paint.Style.STROKE);
    edge.setStrokeJoin(Paint.Join.ROUND);
    started = SystemClock.uptimeMillis();
  }

  void setState(int value) {
    if (value == DONE && state != DONE) flash = 1;
    state = value;
    invalidate();
  }

  /** Уровень сигнала микрофона, 0..1. */
  void setLevel(float value) {
    rawLevel = Math.max(rawLevel, Math.max(0, Math.min(1, value)));
  }

  /** Гасит свет и вызывает {@code done} по окончании. */
  void leave(Runnable done) {
    leaving = true;
    animate().alpha(0).setDuration(220).withEndAction(done).start();
  }

  @Override
  protected void onSizeChanged(int w, int h, int ow, int oh) {
    scrim.setShader(
        new LinearGradient(
            0,
            0,
            0,
            h,
            new int[] {0x66000000, 0xD005070D, 0xF505070D},
            new float[] {0, 0.42f, 1},
            Shader.TileMode.CLAMP));
    int[] ring = new int[RAYS + 1];
    for (int i = 0; i < RAYS; i++) ring[i] = colors[i];
    ring[RAYS] = colors[0];
    halo = new SweepGradient(0, 0, ring, null);
  }

  /** Ставит единичный шейдер вдоль отрезка (x0, y0) → (x1, y1). */
  private Shader along(Shader shader, float x0, float y0, float x1, float y1) {
    matrix.setScale((float) Math.hypot(x1 - x0, y1 - y0), 1);
    matrix.postRotate((float) Math.toDegrees(Math.atan2(y1 - y0, x1 - x0)));
    matrix.postTranslate(x0, y0);
    shader.setLocalMatrix(matrix);
    return shader;
  }

  /** Круглое свечение с центром (x, y) и радиусом r. */
  private void glow(Canvas canvas, float x, float y, float r, float a) {
    if (a <= 0.004f) return;
    matrix.setScale(r, r);
    matrix.postTranslate(x, y);
    glow.setLocalMatrix(matrix);
    add.setShader(glow);
    add.setAlpha(Math.round(255 * Math.min(1, a)));
    canvas.drawCircle(x, y, r, add);
    add.setShader(null);
    add.setAlpha(255);
  }

  private float dp(float v) {
    return v * density;
  }

  private static float ease(float x) {
    x = Math.max(0, Math.min(1, x));
    return 1 - (1 - x) * (1 - x) * (1 - x);
  }

  private static int alpha(int color, float a) {
    int v = Math.round(255 * Math.max(0, Math.min(1, a)));
    return (color & 0x00FFFFFF) | (v << 24);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    long now = SystemClock.uptimeMillis();
    float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
    lastFrame = now;
    float t = (now - started) / 1000f;
    int w = getWidth(), h = getHeight();
    if (w == 0 || h == 0) return;

    level += (rawLevel - level) * (rawLevel > level ? 0.45f : 0.12f);
    rawLevel *= 0.86f;
    float goal =
        leaving
            ? 0
            : switch (state) {
              case LISTENING -> 0.78f + 0.22f * level;
              case THINKING -> 0.9f;
              case DONE -> 1;
              default -> 0.42f;
            };
    energy += (goal - energy) * Math.min(1, dt * 4);
    flash = Math.max(0, flash - dt * 1.4f);
    float appear = ease(t / 0.75f);
    float fan = ease((t - 0.45f) / 0.9f);

    scrim.setAlpha(Math.round(255 * appear));
    canvas.drawRect(0, 0, w, h, scrim);

    float radius = Math.min(w * 0.17f, h * 0.12f);
    float cx = w * 0.32f, cy = h * 0.4f;
    double rotation =
        0.06 * Math.sin(t * 0.55)
            + (state == THINKING ? 0.1 * Math.sin(t * 1.9) : 0)
            + level * 0.035 * Math.sin(t * 11);
    double[] tri = Prism.triangle(cx, cy, radius, rotation);

    // Точка входа скользит по левой грани (от вершины 2 к вершине 0).
    double along = 0.52 + 0.1 * Math.sin(t * 0.8);
    double ex = tri[4] + (tri[0] - tri[4]) * along, ey = tri[5] + (tri[1] - tri[5]) * along;
    double beam = BEAM + Math.toRadians(2.5) * Math.sin(t * 0.43);
    double dx = Math.cos(beam), dy = Math.sin(beam);
    double back = (ex + dp(40)) / dx;
    double ox = ex - dx * back, oy = ey - dy * back;
    for (int i = 0; i < RAYS; i++) paths[i] = Prism.trace(tri, ox, oy, dx, dy, index[i]);

    // Мягкое свечение вокруг стекла.
    float glowR = radius * (2.4f + 0.5f * level);
    glow(canvas, cx, cy, glowR, 0.13f * energy * appear);

    // Входящий белый луч вырастает из левого края.
    float grow = appear;
    float bx = (float) (ox + (ex - ox) * grow), by = (float) (oy + (ey - oy) * grow);
    widths[0] = dp(16) + dp(10) * level;
    widths[1] = dp(7) + dp(4) * level;
    widths[2] = dp(2.2f);
    line.setShader(along(beamFade, (float) ox, (float) oy, (float) ex, (float) ey));
    for (int k = 0; k < widths.length; k++) {
      line.setStrokeWidth(widths[k]);
      line.setAlpha(Math.round(255 * Math.min(1, BEAM_ALPHA[k] * energy)));
      canvas.drawLine((float) ox, (float) oy, bx, by, line);
    }
    line.setShader(null);
    line.setAlpha(255);

    if (grow >= 0.999f) {
      // Лучи внутри стекла.
      line.setStrokeWidth(dp(1.4f));
      for (int i = 0; i < RAYS; i++) {
        Prism.Path p = paths[i];
        if (p == null) continue;
        line.setColor(alpha(colors[i], 0.22f * energy));
        for (int s = 1; s < p.count - 1; s++)
          canvas.drawLine(
              (float) p.points[s * 2],
              (float) p.points[s * 2 + 1],
              (float) p.points[s * 2 + 2],
              (float) p.points[s * 2 + 3],
              line);
      }

      // Веер спектра: клинья через одну длину волны перекрываются вдвое, поэтому при
      // сложении света между полосами нет швов, а края спектра мягко гаснут.
      float length = (float) Math.hypot(w, h) * (0.15f + 0.85f * fan);
      float spectrum = (0.3f + 0.2f * level) * energy;
      for (int i = -1; i < RAYS - 1; i++) {
        Prism.Path a = paths[Math.max(0, i)], b = paths[Math.min(RAYS - 1, i + 2)];
        int tone = Math.max(0, Math.min(RAYS - 1, i + 1));
        if (a == null || b == null || a.reflections != b.reflections) continue;
        float ax = (float) a.exitX(), ay = (float) a.exitY();
        float bx2 = (float) b.exitX(), by2 = (float) b.exitY();
        float fx = (float) (ax + a.dx * length), fy = (float) (ay + a.dy * length);
        float gx = (float) (bx2 + b.dx * length), gy = (float) (by2 + b.dy * length);
        wedge.rewind();
        wedge.moveTo(ax, ay);
        wedge.lineTo(fx, fy);
        wedge.lineTo(gx, gy);
        wedge.lineTo(bx2, by2);
        wedge.close();
        float shimmer = 0.85f + 0.15f * (float) Math.sin(t * 2.3 + i * 0.7);
        add.setShader(along(band[tone], (ax + bx2) / 2, (ay + by2) / 2, (fx + gx) / 2, (fy + gy) / 2));
        add.setAlpha(Math.round(255 * Math.min(1, spectrum * shimmer)));
        canvas.drawPath(wedge, add);
      }
      add.setShader(null);
      add.setAlpha(255);

      // Яркие нити на краях и в середине спектра.
      line.setStrokeWidth(dp(1.1f));
      for (int i = 0; i < RAYS; i += 5) {
        Prism.Path p = paths[i];
        if (p == null) continue;
        float sx = (float) p.exitX(), sy = (float) p.exitY();
        float l = length * 0.55f;
        line.setShader(along(thread[i], sx, sy, (float) (sx + p.dx * l), (float) (sy + p.dy * l)));
        line.setAlpha(Math.round(255 * Math.min(1, 0.6f * energy)));
        canvas.drawLine(sx, sy, (float) (sx + p.dx * l), (float) (sy + p.dy * l), line);
      }
      line.setShader(null);
      line.setAlpha(255);

      // Искры пыли, подсвеченные спектром.
      for (int i = 0; i < SPARKS; i++) {
        sparkU[i] += sparkSpeed[i] * dt * (1 + level * 2);
        if (sparkU[i] > 1) sparkU[i] -= 1;
        float pos = sparkV[i] * (RAYS - 1);
        int k = Math.min(RAYS - 2, (int) pos);
        Prism.Path a = paths[k], b = paths[k + 1];
        if (a == null || b == null) continue;
        float f = pos - k, d = sparkU[i] * length * 0.8f;
        float sx = (float) (a.exitX() + (b.exitX() - a.exitX()) * f + (a.dx + (b.dx - a.dx) * f) * d);
        float sy = (float) (a.exitY() + (b.exitY() - a.exitY()) * f + (a.dy + (b.dy - a.dy) * f) * d);
        float twinkle = (float) Math.pow(0.5 + 0.5 * Math.sin(sparkPhase[i] + t * 3.1), 3);
        float a2 = twinkle * (1 - sparkU[i]) * energy * fan;
        if (a2 < 0.02f) continue;
        add.setColor(alpha(colors[k], a2));
        canvas.drawCircle(sx, sy, dp(1.1f + 1.4f * twinkle), add);
      }

      // Блик в точке выхода.
      Prism.Path mid = paths[RAYS / 2];
      if (mid != null) {
        float mx = (float) mid.exitX(), my = (float) mid.exitY();
        glow(canvas, mx, my, dp(14) + dp(10) * level, 0.45f * energy);
      }
    }

    // Стекло призмы.
    shape.rewind();
    shape.moveTo((float) tri[0], (float) tri[1]);
    shape.lineTo((float) tri[2], (float) tri[3]);
    shape.lineTo((float) tri[4], (float) tri[5]);
    shape.close();
    glass.setShader(
        along(glassFill, (float) tri[0], (float) tri[1], cx, (float) Math.max(tri[3], tri[5])));
    glass.setAlpha(Math.round(255 * appear));
    canvas.drawPath(shape, glass);
    edge.setStrokeWidth(dp(1.3f));
    edge.setColor(alpha(0xFFFFFFFF, 0.55f * appear));
    canvas.drawPath(shape, edge);
    // Внутренний отсвет — второй, меньший контур.
    canvas.save();
    canvas.scale(0.86f, 0.86f, cx, cy);
    edge.setStrokeWidth(dp(0.8f));
    edge.setColor(alpha(0xFFFFFFFF, 0.12f * appear));
    canvas.drawPath(shape, edge);
    canvas.restore();
    for (int i = 0; i < 3; i++) {
      float vx = (float) tri[i * 2], vy = (float) tri[i * 2 + 1];
      float twinkle = 0.5f + 0.5f * (float) Math.sin(t * 1.7 + i * 2.1);
      float r = dp(7) * (0.6f + twinkle);
      glow(canvas, vx, vy, r, 0.5f * twinkle * appear);
    }

    // Радужное кольцо: вращается во время ответа и вспыхивает при сохранении.
    if (halo != null && (state == THINKING || flash > 0)) {
      float ringR = radius * (1.55f + (1 - flash) * (flash > 0 ? 1.4f : 0));
      float ringA = state == THINKING ? 0.35f * energy : 0;
      ringA = Math.max(ringA, flash * 0.8f);
      canvas.save();
      canvas.translate(cx, cy);
      canvas.rotate(t * 80);
      line.setShader(halo);
      line.setStrokeWidth(dp(2.2f) + dp(6) * flash);
      line.setAlpha(Math.round(255 * ringA));
      canvas.drawCircle(0, 0, ringR, line);
      line.setShader(null);
      line.setAlpha(255);
      canvas.restore();
    }

    if (isShown()) postInvalidateOnAnimation();
  }

  @Override
  protected void onVisibilityChanged(View changed, int visibility) {
    super.onVisibilityChanged(changed, visibility);
    if (visibility == VISIBLE) {
      lastFrame = 0;
      invalidate();
    }
  }
}
