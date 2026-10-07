package app.luoxianlv.host;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;

/**
 * 冷启动开屏：全屏壁纸 + 标题 + 加载进度。
 *
 * <p>业务页在下层照常准备。进度条按默认时长缓出推进，页面未就绪前停在 {@link #LOADING_CAP}，就绪后走满再整体淡出。只在从桌面冷启动时出现，深链、恢复重开和测试直接启动不显示。
 */
public final class StartupSplash extends FrameLayout {
  /** 壁纸素材按序取第一个能解码的；都不存在时回退到暮色渐变，缺图不影响启动。 */
  private static final String[] WALLPAPERS = {
    "splash/splash.webp", "splash/splash.jpg", "splash/splash.png"
  };

  /** 横向裁剪焦点（0 左、1 右）：当前横版夕阳图在竖屏里偏右取景，夕阳和人物同时入画；竖版图几乎不受影响。 */
  private static final float FOCUS_X = .64f;

  private static final long DURATION_MS = 3000;

  /** 页面未就绪时进度条最多走到这里，避免显示走满却还没进入。 */
  private static final float LOADING_CAP = .9f;

  private static final int ACCENT = 0xFF12B7F5;

  private final Activity activity;
  private final long deadline = SystemClock.elapsedRealtime() + DURATION_MS;
  private final ImageView wallpaper;
  private final LinearLayout copy;
  private final LinearLayout loading;
  private final TextView loadingLabel;
  private final TextView percent;
  private final SplashProgress bar;
  private final Runnable frame = this::frame;
  private float shown;
  private int shownPercent = -1;
  private final ViewTreeObserver.OnPreDrawListener barsGuard =
      () -> {
        guardBars();
        return true;
      };
  private boolean entered, pageReady, dismissed;
  /** 页面最近一次设定的系统栏深浅；null 表示页面没有设过与开屏不同的值。 */
  private Integer pageBars;

  public static boolean wanted(Intent intent, Bundle state) {
    return state == null
        && intent != null
        && Intent.ACTION_MAIN.equals(intent.getAction())
        && intent.hasCategory(Intent.CATEGORY_LAUNCHER);
  }

  public StartupSplash(Activity activity) {
    super(activity);
    this.activity = activity;
    // 盖在已就绪的页面上时吞掉触摸，避免点穿到下层。
    setClickable(true);

    var metrics = getResources().getDisplayMetrics();
    wallpaper = new ImageView(activity);
    wallpaper.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    Bitmap image = decode(activity, metrics.widthPixels, metrics.heightPixels);
    if (image != null) {
      wallpaper.setScaleType(ImageView.ScaleType.MATRIX);
      wallpaper.setImageBitmap(image);
      wallpaper.addOnLayoutChangeListener(
          (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
              cropToFill(image, right - left, bottom - top));
    } else
      wallpaper.setBackground(
          gradient(
              GradientDrawable.Orientation.TOP_BOTTOM,
              0xFF1E2A4A,
              0xFF4B4F86,
              0xFFC98A86,
              0xFFF3C79A));
    addView(wallpaper, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

    // 上下两段暗角：保证状态栏图标、底部标题和进度在任何壁纸上都看得清。
    View top = new View(activity);
    top.setBackground(gradient(GradientDrawable.Orientation.TOP_BOTTOM, 0x66000000, 0x00000000));
    addView(top, new LayoutParams(LayoutParams.MATCH_PARENT, dp(180), Gravity.TOP));
    View bottom = new View(activity);
    bottom.setBackground(
        gradient(GradientDrawable.Orientation.BOTTOM_TOP, 0xCC000000, 0x66000000, 0x00000000));
    addView(
        bottom,
        new LayoutParams(LayoutParams.MATCH_PARENT, metrics.heightPixels / 2, Gravity.BOTTOM));

    copy = new LinearLayout(activity);
    copy.setOrientation(LinearLayout.VERTICAL);
    TextView brand = text("LUOXIANLV", 12, 0xB3FFFFFF);
    brand.setLetterSpacing(.32f);
    copy.addView(brand);
    TextView title = text("落弦律", 46, 0xFFFFFFFF);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    title.setLetterSpacing(.12f);
    copy.addView(title, topMargin(dp(4)));
    View accent = new View(activity);
    accent.setBackground(gradient(GradientDrawable.Orientation.LEFT_RIGHT, ACCENT, 0x0012B7F5));
    var accentParams = new LinearLayout.LayoutParams(dp(56), dp(3));
    accentParams.topMargin = dp(14);
    copy.addView(accent, accentParams);
    copy.addView(text("弦落之处，自有旋律", 16, 0xE6FFFFFF), topMargin(dp(14)));
    var copyParams =
        new LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
    copyParams.leftMargin = dp(30);
    addView(copy, copyParams);

    loading = new LinearLayout(activity);
    loading.setOrientation(LinearLayout.VERTICAL);
    LinearLayout status = new LinearLayout(activity);
    loadingLabel = text("正在加载", 12, 0xCCFFFFFF);
    status.addView(
        loadingLabel, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
    percent = text("0%", 12, 0xCCFFFFFF);
    percent.setFontFeatureSettings("tnum");
    status.addView(percent);
    loading.addView(status);
    percent.setTypeface(Typeface.DEFAULT_BOLD);
    bar = new SplashProgress(activity);
    loading.addView(
        bar, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, bar.preferredHeight()));
    // 火花和音符会飘出进度条自身的边界。
    loading.setClipChildren(false);
    setClipChildren(false);
    var loadingParams =
        new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
    loadingParams.leftMargin = dp(30);
    loadingParams.rightMargin = dp(30);
    addView(loading, loadingParams);

    // 宿主窗口是边到边布局：按导航栏让出标题和进度的位置。
    setOnApplyWindowInsetsListener(
        (view, insets) -> {
          int[] bars = Api.systemBars(insets);
          var progressParams = (LayoutParams) loading.getLayoutParams();
          progressParams.bottomMargin = bars[1] + dp(28);
          loading.setLayoutParams(progressParams);
          var params = (LayoutParams) copy.getLayoutParams();
          params.bottomMargin = bars[1] + dp(108);
          copy.setLayoutParams(params);
          return insets;
        });
  }

  /** 宿主页面已经装好。返回 false 表示开屏已结束，宿主直接显示页面。 */
  public boolean pageReady() {
    if (dismissed) return false;
    pageReady = true;
    pageBars = null;
    guardBars();
    return true;
  }

  /**
   * 开屏期间系统栏固定用浅色图标压在暗角上。页面（Compose 主题）随时可能写入自己的深浅设置， 这里每帧检查：被改动就记下页面的值并改回浅色图标，淡出时再归还给页面。
   */
  private void guardBars() {
    int current = Api.lightBars(window());
    if (current == 0) return;
    if (pageReady) pageBars = current;
    Api.setLightBars(window(), 0);
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    // 宿主换上页面时会先摘下再重新挂上本视图，倒计时按绝对截止时间续上。
    if (!entered) {
      entered = true;
      wallpaper.setScaleX(1.08f);
      wallpaper.setScaleY(1.08f);
      wallpaper
          .animate()
          .scaleX(1f)
          .scaleY(1f)
          .setDuration(DURATION_MS + 400)
          .setInterpolator(new DecelerateInterpolator());
      copy.setAlpha(0f);
      copy.setTranslationY(dp(24));
      copy.animate()
          .alpha(1f)
          .translationY(0f)
          .setStartDelay(150)
          .setDuration(700)
          .setInterpolator(new DecelerateInterpolator());
      loading.setAlpha(0f);
      loading.animate().alpha(1f).setStartDelay(300).setDuration(500);
    }
    removeCallbacks(frame);
    if (dismissed) return;
    guardBars();
    getViewTreeObserver().addOnPreDrawListener(barsGuard);
    postOnAnimation(frame);
  }

  @Override
  protected void onDetachedFromWindow() {
    removeCallbacks(frame);
    getViewTreeObserver().removeOnPreDrawListener(barsGuard);
    super.onDetachedFromWindow();
  }

  /** 每帧推进进度：显示值平滑追赶目标值，走满且页面就绪后淡出。 */
  private void frame() {
    if (dismissed) return;
    float time =
        Math.min(1f, 1f - (deadline - SystemClock.elapsedRealtime()) / (float) DURATION_MS);
    float target = 1f - (1f - time) * (1f - time);
    if (!pageReady) target = Math.min(target, LOADING_CAP);
    shown += (target - shown) * .2f;
    if (Math.abs(target - shown) < .002f) shown = target;
    bar.setProgress(shown);
    int value = Math.round(shown * 100);
    if (value != shownPercent) {
      shownPercent = value;
      percent.setText(value + "%");
      if (value == 100) loadingLabel.setText("加载完成");
    }
    if (shown >= 1f && pageReady) dismiss();
    else postOnAnimation(frame);
  }

  /** 淡出期间页面才完成首帧主题设置，系统栏守卫保持到淡出结束再归还页面的深浅设置。 */
  private void dismiss() {
    dismissed = true;
    removeCallbacks(frame);
    setClickable(false);
    animate()
        .alpha(0f)
        .setDuration(320)
        .withEndAction(
            () -> {
              getViewTreeObserver().removeOnPreDrawListener(barsGuard);
              if (pageBars != null) Api.setLightBars(window(), pageBars);
              if (getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) getParent()).removeView(this);
            });
  }

  /** 等同 CENTER_CROP，但横向按 [FOCUS_X] 取景。 */
  private void cropToFill(Bitmap image, int width, int height) {
    if (width <= 0 || height <= 0) return;
    float scale = Math.max((float) width / image.getWidth(), (float) height / image.getHeight());
    var matrix = new android.graphics.Matrix();
    matrix.setScale(scale, scale);
    matrix.postTranslate(
        (width - image.getWidth() * scale) * FOCUS_X, (height - image.getHeight() * scale) / 2f);
    wallpaper.setImageMatrix(matrix);
  }

  private Window window() {
    return activity.getWindow();
  }

  private TextView text(String value, int sp, int color) {
    TextView view = new TextView(getContext());
    view.setText(value);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    view.setTextColor(color);
    view.setShadowLayer(dp(6), 0, dp(1), 0x66000000);
    return view;
  }

  private static LinearLayout.LayoutParams topMargin(int top) {
    var params =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    params.topMargin = top;
    return params;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private static GradientDrawable gradient(GradientDrawable.Orientation orientation, int... colors) {
    return new GradientDrawable(orientation, colors);
  }

  /** 按屏幕尺寸降采样解码，避免大图在冷启动时占用过多内存和主线程时间。 */
  private static Bitmap decode(Context context, int width, int height) {
    for (String name : WALLPAPERS) {
      var bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      try (InputStream in = context.getAssets().open(name)) {
        BitmapFactory.decodeStream(in, null, bounds);
      } catch (IOException missing) {
        continue;
      }
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue;
      var options = new BitmapFactory.Options();
      options.inSampleSize = 1;
      while (bounds.outWidth / (options.inSampleSize * 2) >= width
          && bounds.outHeight / (options.inSampleSize * 2) >= height) options.inSampleSize *= 2;
      try (InputStream in = context.getAssets().open(name)) {
        Bitmap bitmap = BitmapFactory.decodeStream(in, null, options);
        if (bitmap != null) return bitmap;
      } catch (IOException unreadable) {
        // 继续尝试下一个候选。
      }
    }
    return null;
  }

  /** 系统栏相关 API 按版本隔离，旧设备解析本类时不触及 30 以上才有的类型。深浅图标统一编码为位：1 状态栏、2 导航栏。 */
  private static final class Api {
    static int[] systemBars(WindowInsets insets) {
      return Build.VERSION.SDK_INT >= 30
          ? Api30.systemBars(insets)
          : new int[] {insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetBottom()};
    }

    static int lightBars(Window window) {
      if (Build.VERSION.SDK_INT >= 30) return Api30.lightBars(window);
      int flags = window.getDecorView().getSystemUiVisibility();
      return ((flags & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0 ? 1 : 0)
          | ((flags & View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) != 0 ? 2 : 0);
    }

    static void setLightBars(Window window, int bits) {
      if (Build.VERSION.SDK_INT >= 30) {
        Api30.setLightBars(window, bits);
        return;
      }
      View decor = window.getDecorView();
      int flags =
          decor.getSystemUiVisibility()
              & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
      if ((bits & 1) != 0) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
      if ((bits & 2) != 0) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
      decor.setSystemUiVisibility(flags);
    }
  }

  private static final class Api30 {
    private static final int STATUS =
        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
    private static final int NAVIGATION =
        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;

    static int[] systemBars(WindowInsets insets) {
      var bars = insets.getInsets(WindowInsets.Type.systemBars());
      return new int[] {bars.top, bars.bottom};
    }

    static int lightBars(Window window) {
      int appearance = window.getInsetsController().getSystemBarsAppearance();
      return ((appearance & STATUS) != 0 ? 1 : 0) | ((appearance & NAVIGATION) != 0 ? 2 : 0);
    }

    static void setLightBars(Window window, int bits) {
      window
          .getInsetsController()
          .setSystemBarsAppearance(
              ((bits & 1) != 0 ? STATUS : 0) | ((bits & 2) != 0 ? NAVIGATION : 0),
              STATUS | NAVIGATION);
    }
  }
}
