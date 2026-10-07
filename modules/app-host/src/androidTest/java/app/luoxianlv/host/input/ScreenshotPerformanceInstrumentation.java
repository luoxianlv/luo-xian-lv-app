package app.luoxianlv.host.input;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IInterface;
import android.os.SystemClock;
import app.luoxianlv.hot.contract.AccessibilityBinding;
import app.luoxianlv.hot.contract.SharedInput;
import app.luoxianlv.input.IInputService;
import app.luoxianlv.input.InputController;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** 实际 shell 捕获、Binder 传输与解码；首个快速通道请求及连续请求不作为真机速度保证。 */
public final class ScreenshotPerformanceInstrumentation extends Instrumentation {
  private String mode;
  private int port;
  private boolean landscape;
  private String keyboardPath;
  private SharedInput.Bridge bridge;
  private int width, height;

  @Override public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    mode = arguments == null ? SharedInput.SHIZUKU : arguments.getString("mode", SharedInput.SHIZUKU);
    port = arguments == null ? 0 : Integer.parseInt(arguments.getString("wirelessPort", "0"));
    landscape = arguments != null && "true".equals(arguments.getString("landscape"));
    keyboardPath = arguments == null ? null : arguments.getString("keyboardPath");
    start();
  }

  @Override public void onStart() {
    Bundle output = new Bundle(); boolean patternOpened = false; String previous = null;
    boolean passed = false;
    try {
      require(getTargetContext().getPackageName().endsWith(".debug"), "只允许 Debug 截图验收");
      require(SharedInput.SHIZUKU.equals(mode) || SharedInput.WIRELESS.equals(mode), "输入通道无效");
      long end = SystemClock.elapsedRealtime() + 10000;
      while ((SharedInput.current() == null || !SharedInput.current().state().getBoolean("initialized"))
          && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20);
      bridge = SharedInput.current(); require(bridge != null, "输入桥未安装");
      if (keyboardPath != null) {
        getTargetContext().startActivity(new Intent().setClassName(getTargetContext(), "app.luoxianlv.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        end = SystemClock.elapsedRealtime() + 15000;
        while (true) {
          try { if (app.luoxianlv.host.Bootstrap.source().prepared != null) break; }
          catch (IllegalStateException pending) { }
          require(SystemClock.elapsedRealtime() < end, "业务识别器未加载"); SystemClock.sleep(20);
        }
      }
      previous = bridge.state().getString("mode", SharedInput.ACCESSIBILITY);
      bridge.select(mode);
      end = SystemClock.elapsedRealtime() + 5000;
      while (!mode.equals(bridge.state().getString("mode")) && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20);
      Bundle connection = new Bundle();
      if (port > 0) connection.putInt("port", port);
      bridge.command("connect", connection);
      end = SystemClock.elapsedRealtime() + 20000;
      while ((!bridge.state().getBoolean("connected") || bridge.state().getInt("helperUid") != 2000)
          && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20);
      require(bridge.state().getBoolean("connected") && bridge.state().getInt("helperUid") == 2000,
          "必须先建立真正的 shell 输入连接：" + bridge.state().getString("message"));
      getTargetContext().startActivity(new Intent().setClassName(getContext(), ScreenshotPatternActivity.class.getName())
          .putExtra("landscape", landscape).putExtra("keyboardPath", keyboardPath)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
      patternOpened = true;
      waitForIdleSync(); SystemClock.sleep(700);
      var field = InputController.class.getDeclaredField("service"); field.setAccessible(true);
      IInterface target = (IInterface) field.get(InputController.current());
      IInputService remote = IInputService.Stub.asInterface(target.asBinder());
      // Activity 在另一个进程创建，主进程空闲不代表其首帧已经绘制。
      end = SystemClock.elapsedRealtime() + 10000;
      boolean visible = false;
      while (!visible && SystemClock.elapsedRealtime() < end) {
        Bitmap probe;
        try (var fd = remote.screenshot(0)) {
          require(fd != null, "兼容捕获没有返回管道");
          probe = BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor());
        }
        if (probe != null) {
          if (keyboardPath != null) visible = recognize(new AccessibilityBinding.Frame(probe)) != null;
          else try { visible = Color.red(probe.getPixel(probe.getWidth() / 6, probe.getHeight() / 2)) > 245
              && Color.green(probe.getPixel(probe.getWidth() / 6, probe.getHeight() / 2)) < 8; }
          finally { probe.recycle(); }
        }
        if (!visible) SystemClock.sleep(100);
      }
      require(visible, "测试图案窗口没有完成绘制");
      Class<?> packets = Class.forName("app.luoxianlv.input.ScreenshotPacket", true, InputController.class.getClassLoader());
      var decode = packets.getDeclaredMethod("decode", packets); decode.setAccessible(true);
      JSONObject report = new JSONObject().put("api", android.os.Build.VERSION.SDK_INT).put("mode", mode);
      int[] expectedColors = {Color.RED, Color.GREEN, Color.BLUE};
      String[] groups = new String[] {"png-baseline","raw","auto"};
      for (String kind : groups) {
        ArrayList<Long> samples = new ArrayList<>(); JSONArray paths = new JSONArray();
        long first = 0;
        for (int i = 0; i < 6; i++) {
          long began = SystemClock.elapsedRealtime(); Bitmap bitmap = null; AccessibilityBinding.Frame frame = null;
          try {
            if (kind.equals("png-baseline")) {
              try (var fd = remote.screenshot(0)) {
                require(fd != null, "兼容捕获没有返回管道");
                bitmap = BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor());
              }
              paths.put("png");
            } else if (kind.equals("raw")) {
              try (var packet = remote.screenshotFrame(0, true)) {
                paths.put(android.os.Build.VERSION.SDK_INT < 30 ? "png-fallback" : "raw");
                frame = (AccessibilityBinding.Frame) decode.invoke(null, packet);
              }
            } else {
              CountDownLatch done = new CountDownLatch(1);
              AtomicReference<AccessibilityBinding.Frame> result = new AtomicReference<>();
              runOnMainSync(() -> bridge.screenshot(0, new AccessibilityBinding.ScreenshotCallback() {
                @Override public void success(AccessibilityBinding.Frame value) { result.set(value); done.countDown(); }
                @Override public void failure(int code) { done.countDown(); }
              }));
              require(done.await(8, TimeUnit.SECONDS), "宿主截图没有完成");
              frame = result.get(); require(frame != null, "宿主截图失败");
              paths.put(frame.buffer == null ? "software" : "hardware");
            }
            if (frame != null) {
              bitmap = frame.takeBitmap();
              if (bitmap == null) {
                Bitmap hardware = Bitmap.wrapHardwareBuffer(frame.buffer, frame.colorSpace);
                try { bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false); }
                finally { hardware.recycle(); }
              }
            }
            long elapsed = SystemClock.elapsedRealtime() - began;
            if (i == 0) first = elapsed;
            samples.add(elapsed);
            require(bitmap != null, "截图没有像素");
            if (width == 0) { width = bitmap.getWidth(); height = bitmap.getHeight(); }
            require(bitmap.getWidth() == width && bitmap.getHeight() == height, "快速截图改变了捕获尺寸");
            for (int column = 0; column < 3; column++) {
              int actual = bitmap.getPixel((column * 2 + 1) * bitmap.getWidth() / 6, bitmap.getHeight() / 2);
              if (keyboardPath != null && kind.equals("png-baseline") && i == 0) expectedColors[column] = actual;
              int expected = expectedColors[column];
              boolean valid = Math.abs(Color.red(actual) - Color.red(expected)) < 8
                  && Math.abs(Color.green(actual) - Color.green(expected)) < 8
                  && Math.abs(Color.blue(actual) - Color.blue(expected)) < 8;
              if (!valid) {
                try (var image = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),
                    "screenshot-failed-" + kind + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, image); }
              }
              require(valid, "截图通道、方向或完整画面错误：路径=" + kind + " 样本=" + i
                  + " 列=" + column + " 颜色=" + Integer.toHexString(actual)
                  + " 尺寸=" + bitmap.getWidth() + "x" + bitmap.getHeight());
            }
          } finally {
            if (bitmap != null) bitmap.recycle();
            if (frame != null) frame.close();
          }
        }
        ArrayList<Long> sorted = new ArrayList<>(samples); Collections.sort(sorted);
        report.put(kind, new JSONObject().put("firstMs", first).put("p50Ms", sorted.get(3))
            .put("p95Ms", sorted.get(5)).put("samples", new JSONArray(samples)).put("paths", paths));
      }
      if (keyboardPath != null) {
        Object recognized;
        long analysisBegan;
        try (var packet = remote.screenshotFrame(0, false)) {
          var frame = (AccessibilityBinding.Frame) decode.invoke(null, packet);
          boolean hardwareFrame = frame.buffer != null;
          report.put("analysisFrameKind", hardwareFrame ? "hardware" : "software");
          analysisBegan=SystemClock.elapsedRealtime();
          recognized = recognize(frame);
          if (hardwareFrame) require(frame.buffer.isClosed(), "识别器没有释放硬件缓冲");
        }
        require(recognized != null, "快速硬件截图无法识别琴键");
        require((Integer)recognized.getClass().getMethod("getObservedNotes").invoke(recognized)==8
            && (Integer)recognized.getClass().getMethod("getObservedModes").invoke(recognized)==4,"公开样本应直接识别全部琴键");
        report.put("analyzerMs",SystemClock.elapsedRealtime()-analysisBegan);
        ArrayList<Long> completeSamples = new ArrayList<>();
        for (int i=0;i<21;i++) {
          long began=SystemClock.elapsedRealtime();
          CountDownLatch done=new CountDownLatch(1);
          AtomicReference<AccessibilityBinding.Frame> captured=new AtomicReference<>();
          runOnMainSync(()->bridge.screenshot(0,new AccessibilityBinding.ScreenshotCallback(){
            @Override public void success(AccessibilityBinding.Frame value){captured.set(value);done.countDown();}
            @Override public void failure(int code){done.countDown();}
          }));
          require(done.await(8,TimeUnit.SECONDS) && captured.get()!=null,"完整链路没有交付截图");
          Object result=recognize(captured.get());
          require(result!=null && (Integer)result.getClass().getMethod("getObservedNotes").invoke(result)==8
              && (Integer)result.getClass().getMethod("getObservedModes").invoke(result)==4,"完整链路未直接识别全部琴键");
          completeSamples.add(SystemClock.elapsedRealtime()-began);
        }
        var completeSorted=new ArrayList<>(completeSamples);Collections.sort(completeSorted);
        report.put("captureAndRecognition",new JSONObject().put("p50Ms",completeSorted.get(completeSorted.size()/2))
            .put("maxMs",completeSorted.get(completeSorted.size()-1)).put("samples",new JSONArray(completeSamples)));
        report.put("observedNotes", recognized.getClass().getMethod("getObservedNotes").invoke(recognized))
            .put("observedModes", recognized.getClass().getMethod("getObservedModes").invoke(recognized));
      }
      report.put("width", width).put("height", height).put("colorsVerified", true);
      Files.write(new java.io.File(getTargetContext().getExternalFilesDir(null), "screenshot-performance.json").toPath(),
          report.toString(2).getBytes(StandardCharsets.UTF_8));
      output.putString("stream", "真实截图验收通过：" + report + "\n");
      passed = true;
    } catch (Throwable failure) {
      if (failure instanceof java.lang.reflect.InvocationTargetException)
        failure = ((java.lang.reflect.InvocationTargetException) failure).getTargetException();
      output.putString("stream", "真实截图验收失败：" + failure + "\n");
      output.putString("error", android.util.Log.getStackTraceString(failure));
    } finally {
      if (patternOpened) getTargetContext().startActivity(new Intent()
          .setClassName(getContext(), ScreenshotPatternActivity.class.getName()).putExtra("finishPattern", true)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
      if (bridge != null && previous != null) bridge.select(previous);
    }
    finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, output);
  }

  private Object recognize(AccessibilityBinding.Frame frame) throws Exception {
    try (frame) {
      Class<?> analyzer = Class.forName("app.luoxianlv.recognition.ScreenshotAnalyzer", true,
          app.luoxianlv.host.Bootstrap.source().prepared.classLoader());
      var method = java.util.Arrays.stream(analyzer.getDeclaredMethods())
          .filter(value -> value.getName().equals("recognize")).findFirst().orElseThrow();
      method.setAccessible(true);
      return method.invoke(analyzer.getField("INSTANCE").get(null), frame, null);
    }
  }

  private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
