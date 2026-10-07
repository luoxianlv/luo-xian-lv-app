package app.luoxianlv.host.input;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;

/** 仅测试 APK 的静态色块，校验捕获尺寸、通道、方向与完整画面。 */
public final class ScreenshotPatternActivity extends Activity {
  private android.graphics.Bitmap keyboard;
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    overridePendingTransition(0, 0);
    if (getIntent().getBooleanExtra("finishPattern", false)) { finish(); return; }
    if (getIntent().getBooleanExtra("landscape", false))
      setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    String path = getIntent().getStringExtra("keyboardPath");
    if (path != null) {
      if (path.startsWith("asset:")) try (var input = getAssets().open(path.substring(6))) {
        keyboard = android.graphics.BitmapFactory.decodeStream(input);
      } catch (java.io.IOException failure) { throw new IllegalArgumentException("琴键样本无法读取", failure); }
      else keyboard = android.graphics.BitmapFactory.decodeFile(path);
      if (keyboard == null) throw new IllegalArgumentException("琴键样本无法读取");
      var image = new android.widget.ImageView(this);
      image.setBackgroundColor(Color.BLACK);
      image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
      image.setImageBitmap(keyboard);
      setContentView(image);
      return;
    }
    setContentView(new View(this) {
      private final Paint paint = new Paint();
      @Override protected void onDraw(Canvas canvas) {
        int[] colors = {Color.RED, Color.GREEN, Color.BLUE};
        for (int i = 0; i < 3; i++) {
          paint.setColor(colors[i]);
          canvas.drawRect(i * getWidth() / 3f, 0, (i + 1) * getWidth() / 3f, getHeight(), paint);
        }
      }
    });
  }
  @Override protected void onDestroy() {
    if (keyboard != null) keyboard.recycle();
    super.onDestroy();
  }
  @Override protected void onNewIntent(android.content.Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (intent.getBooleanExtra("finishPattern", false)) finish();
    else recreate();
  }
}
