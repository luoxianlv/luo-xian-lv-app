package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import app.luoxianlv.hot.contract.AccessibilityBinding;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import org.json.JSONException;
import org.json.JSONObject;

/** 在模拟器屏幕显示仓库的游戏琴键样本，再通过系统截图进入实际识别流程。 */
final class EnsembleScreenProbe {
  private final Instrumentation test;
  private final Activity activity;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final File delayFile;
  private ImageView image;
  private long requestedUs, capturedUs, deliveredUs, inputReadyUs;
  private int requests;

  EnsembleScreenProbe(Instrumentation test, Activity activity) {
    this.test = test;
    this.activity = activity;
    delayFile = new File(activity.getFilesDir(), "ensemble-multi/screen-delay-ms");
  }

  void show() {
    image = new ImageView(activity);
    try (InputStream input = test.getContext().getAssets().open("keyboard-game-1280.png")) {
      image.setImageBitmap(BitmapFactory.decodeStream(input));
    } catch (Exception e) { throw new AssertionError(e); }
    image.setScaleType(ImageView.ScaleType.FIT_XY);
    activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    activity.addContentView(image, new ViewGroup.LayoutParams(-1, -1));
  }

  private static long nowUs() { return SystemClock.elapsedRealtimeNanos() / 1000; }

  void capture(AccessibilityBinding.ScreenshotCallback callback) {
    final long delay;
    try {
      delay = delayFile.isFile() ? Long.parseLong(new String(Files.readAllBytes(delayFile.toPath()),
          java.nio.charset.StandardCharsets.US_ASCII).trim()) : 0;
    } catch (Exception e) { throw new AssertionError(e); }
    if (delay < 0 || delay > 15000) throw new AssertionError("截图延迟越界");
    requests++;
    requestedUs = nowUs();
    deliveredUs = inputReadyUs = 0;
    Bitmap raw = test.getUiAutomation().takeScreenshot();
    if (raw == null) { callback.failure(-1); return; }
    Bitmap pixels = raw.copy(Bitmap.Config.ARGB_8888, false);
    raw.recycle();
    capturedUs = nowUs();
    main.postDelayed(() -> {
      deliveredUs = nowUs();
      callback.success(new AccessibilityBinding.Frame(pixels));
    }, delay);
  }

  void inputReady() {
    if (deliveredUs != 0 && inputReadyUs == 0) inputReadyUs = nowUs();
  }

  JSONObject snapshot() throws JSONException {
    return new JSONObject().put("requests", requests).put("requestedUs", requestedUs)
        .put("capturedUs", capturedUs).put("deliveredUs", deliveredUs).put("inputReadyUs", inputReadyUs);
  }

  void close() {
    if (image != null && image.getParent() instanceof ViewGroup parent) parent.removeView(image);
  }
}
