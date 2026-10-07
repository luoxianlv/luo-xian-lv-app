package app.luoxianlv.host;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import java.util.Random;

/**
 * 开屏加载条：流动的四色渐变、外发光、扫光、脉动的光头，以及从光头飘出的火花和音符。
 *
 * <p>只负责绘制；进度由 {@link StartupSplash} 每帧写入。挂在窗口上时自行逐帧重绘，摘下即停。
 */
final class SplashProgress extends View {
  /** 与壁纸暮色呼应：青蓝 → 雾紫 → 樱粉 → 落日金。 */
  private static final int[] COLORS = {0xFF12B7F5, 0xFF7C8CFF, 0xFFFF7EB3, 0xFFFFC371};

  private static final int PARTICLES = 40;
  private static final String[] NOTES = {"♪", "♫", "♩"};

  private final float barHeight = dp(6);
  private final float radius = barHeight / 2;
  private final float glowPad = dp(10);
  private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint spark = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint note = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Matrix matrix = new Matrix();
  private final RectF rect = new RectF();
  private final Path clip = new Path();
  private final Random random = new Random();
  private final long start = SystemClock.uptimeMillis();
  private Shader fillShader, shineShader, headShader;

  // 粒子池：x、y、vx、vy、剩余寿命、总寿命、尺寸、颜色、是否音符。
  private final float[] px = new float[PARTICLES], py = new float[PARTICLES];
  private final float[] vx = new float[PARTICLES], vy = new float[PARTICLES];
  private final float[] life = new float[PARTICLES], span = new float[PARTICLES];
  private final float[] size = new float[PARTICLES];
  private final int[] tint = new int[PARTICLES];
  private final boolean[] glyph = new boolean[PARTICLES];

  private float progress, emitDebt;
  private long lastFrame;
  private boolean burst;

  SplashProgress(Context context) {
    super(context);
    track.setColor(0x2EFFFFFF);
    note.setTextAlign(Paint.Align.CENTER);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  /** 进度 0..1；首次走满时从光头炸开一圈粒子。 */
  void setProgress(float value) {
    if (value >= 1f && progress < 1f) burst = true;
    progress = Math.max(0f, Math.min(1f, value));
    invalidate();
  }

  /** 视图高度只留出光晕空间，进度条贴近底部；粒子靠父容器关闭裁剪飘出边界。 */
  int preferredHeight() {
    return Math.round(dp(28));
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    // MIRROR 平铺后平移着色器即得流动效果，无需每帧新建对象。
    fillShader = new LinearGradient(0, 0, w * .6f, 0, COLORS, null, Shader.TileMode.MIRROR);
    fill.setShader(fillShader);
    shineShader =
        new LinearGradient(
            0,
            0,
            dp(90),
            0,
            new int[] {0x00FFFFFF, 0x99FFFFFF, 0x00FFFFFF},
            null,
            Shader.TileMode.CLAMP);
    shine.setShader(shineShader);
    headShader =
        new RadialGradient(
            0,
            0,
            1f,
            new int[] {0xFFFFFFFF, 0xCCBFEFFF, 0x6612B7F5, 0x0012B7F5},
            new float[] {0f, .25f, .55f, 1f},
            Shader.TileMode.CLAMP);
    head.setShader(headShader);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    long now = SystemClock.uptimeMillis();
    float dt = lastFrame == 0 ? 0f : Math.min(.05f, (now - lastFrame) / 1000f);
    lastFrame = now;
    float t = (now - start) / 1000f;
    float w = getWidth();
    float cy = getHeight() - glowPad - radius;
    float top = cy - radius, bottom = cy + radius;

    rect.set(0, top, w, bottom);
    canvas.drawRoundRect(rect, radius, radius, track);

    float end = progress <= 0f ? 0f : Math.max(barHeight, w * progress);
    if (end > 0f) {
      matrix.setTranslate(t * dp(120), 0);
      fillShader.setLocalMatrix(matrix);
      // 外发光：同一支渐变画两圈放大的半透明圆角条。
      for (int i = 2; i >= 1; i--) {
        float grow = dp(3) * i;
        rect.set(-grow, top - grow, end + grow, bottom + grow);
        fill.setAlpha(i == 2 ? 28 : 64);
        canvas.drawRoundRect(rect, radius + grow, radius + grow, fill);
      }
      fill.setAlpha(255);
      rect.set(0, top, end, bottom);
      canvas.drawRoundRect(rect, radius, radius, fill);

      // 扫光：一道白色高光周期性掠过已填充部分。
      float band = dp(90);
      float sweep = (t % 1.4f) / 1.4f * (end + band) - band;
      clip.reset();
      clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
      canvas.save();
      canvas.clipPath(clip);
      matrix.setTranslate(sweep, 0);
      shineShader.setLocalMatrix(matrix);
      canvas.drawRect(rect, shine);
      canvas.restore();

      // 光头：随时间轻微脉动的径向光晕。
      float pulse = dp(13) * (1f + .18f * (float) Math.sin(t * 7f));
      float hx = end - radius;
      matrix.setScale(pulse, pulse);
      matrix.postTranslate(hx, cy);
      headShader.setLocalMatrix(matrix);
      canvas.drawCircle(hx, cy, pulse, head);

      if (burst) {
        burst = false;
        for (int i = 0; i < 18; i++) emit(hx, cy, true);
      }
      emitDebt += dt * (progress < 1f ? 26f : 6f);
      while (emitDebt >= 1f) {
        emitDebt -= 1f;
        emit(hx, cy, false);
      }
    }
    drawParticles(canvas, dt);
    if (isAttachedToWindow()) postInvalidateOnAnimation();
  }

  private void emit(float x, float y, boolean explosive) {
    for (int i = 0; i < PARTICLES; i++) {
      if (life[i] > 0f) continue;
      boolean isNote = !explosive && random.nextFloat() < .22f;
      double angle =
          explosive
              ? random.nextFloat() * Math.PI * 2
              : Math.PI + (random.nextFloat() - .5f) * 1.6f - .5f;
      float speed = dp(explosive ? 70 : isNote ? 26 : 40) * (.5f + random.nextFloat());
      px[i] = x;
      py[i] = y;
      vx[i] = (float) Math.cos(angle) * speed;
      vy[i] = (float) Math.sin(angle) * speed - dp(isNote ? 22 : 8);
      span[i] = life[i] = (isNote ? 1.1f : .6f) + random.nextFloat() * .5f;
      size[i] = isNote ? dp(10) + random.nextFloat() * dp(5) : dp(1) + random.nextFloat() * dp(1.6f);
      tint[i] = random.nextFloat() < .4f ? 0xFFFFFFFF : COLORS[random.nextInt(COLORS.length)];
      glyph[i] = isNote;
      return;
    }
  }

  private void drawParticles(Canvas canvas, float dt) {
    for (int i = 0; i < PARTICLES; i++) {
      if (life[i] <= 0f) continue;
      life[i] -= dt;
      if (life[i] <= 0f) continue;
      px[i] += vx[i] * dt;
      py[i] += vy[i] * dt;
      vx[i] *= 1f - 1.6f * dt;
      float fade = life[i] / span[i];
      int alpha = Math.round(255 * fade * (glyph[i] ? .85f : 1f));
      if (glyph[i]) {
        note.setColor(tint[i]);
        note.setAlpha(alpha);
        note.setTextSize(size[i]);
        canvas.drawText(NOTES[i % NOTES.length], px[i], py[i], note);
      } else {
        spark.setColor(tint[i]);
        spark.setAlpha(alpha);
        canvas.drawCircle(px[i], py[i], size[i] * (.6f + .4f * fade), spark);
      }
    }
  }

  private float dp(float value) {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
  }
}
