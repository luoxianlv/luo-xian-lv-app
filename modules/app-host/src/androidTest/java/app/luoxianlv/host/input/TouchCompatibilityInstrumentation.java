package app.luoxianlv.host.input;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.graphics.Point;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Display;
import app.luoxianlv.hot.contract.SharedInput;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** 只在显式开启的 Debug 测试环境检查标准注入；目标是独立触点接收 APK。 */
public final class TouchCompatibilityInstrumentation extends Instrumentation {
  private String mode, expected;
  private int port;
  private boolean hostTouch;
  private UiAutomation automation;

  @Override public void onCreate(Bundle args) {
    super.onCreate(args);
    if (args == null || !"true".equals(args.getString("fixture"))) throw new IllegalArgumentException("需要 fixture=true");
    mode = args.getString("mode", SharedInput.SHIZUKU);
    expected = args.getString("strategy", "standard");
    port = Integer.parseInt(args.getString("wirelessPort", "0"));
    hostTouch = "true".equals(args.getString("hostTouch"));
    start();
  }

  @Override public void onStart() {
    Bundle output = new Bundle();
    SharedInput.Bridge bridge = null;
    SharedInput.Session session = null;
    String previous = null;
    boolean passed = false;
    try {
      require(getTargetContext().getPackageName().endsWith(".debug"), "只允许 Debug 宿主");
      automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
      await(() -> SharedInput.current() != null, 10000);
      bridge = SharedInput.current();
      SharedInput.Bridge input = bridge;
      await(() -> input.state().getBoolean("initialized"), 10000);
      previous = input.state().getString("mode");
      if (SharedInput.WIRELESS.equals(mode)) {
        getTargetContext().startActivity(new android.content.Intent(getTargetContext(),app.luoxianlv.MainActivity.class)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> {
          var root = automation.getRootInActiveWindow();
          if (root == null) return false;
          boolean target = getTargetContext().getPackageName().contentEquals(root.getPackageName()); root.recycle(); return target;
        },10000);
      }
      input.select(mode);
      await(() -> mode.equals(input.state().getString("mode")), 5000);
      Bundle arguments = new Bundle(); arguments.putInt("port",port);
      input.command("connect",arguments);
      await(() -> input.state().getBoolean("connected") && input.state().getBoolean("touchReady") && !input.state().getBoolean("busy"), 25000);
      require(input.state().getInt("helperUid") == 2000, "必须使用实际 shell 助手");
      if (hostTouch) {
        getTargetContext().startActivity(new android.content.Intent(getTargetContext(),app.luoxianlv.MainActivity.class)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> {
          var root = automation.getRootInActiveWindow();
          if (root == null) return false;
          boolean target = getTargetContext().getPackageName().contentEquals(root.getPackageName()); root.recycle(); return target;
        },10000);
        require(shell("/data/local/tmp/luoxianlv-input-fixture/touch-fixture send down 16384 16384").trim().startsWith("OK"),"测试触屏不可用");
        SystemClock.sleep(50);
        require(shell("/data/local/tmp/luoxianlv-input-fixture/touch-fixture send up").trim().startsWith("OK"),"测试手指未抬起");
        await(() -> "android-host-touch".equals(input.state().getString("deviceSelectionMethod"))
            && input.state().getBoolean("coexistenceReady"),10000);
      }
      require(expected.equals(input.state().getString("inputStrategy")), "未覆盖指定策略：" + input.state().getString("inputStrategy"));
      require(input.state().getBoolean("coexistenceReady") == "merged".equals(expected), "共存能力状态错误");
      String run = "compat_" + SystemClock.elapsedRealtime();
      shell("am start -W -n app.luoxianlv.audit.touchprobe/.MainActivity --ez clear_logs true --es probe_run " + run);
      Display display = getTargetContext().getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0);
      Point size = new Point(); display.getRealSize(size);
      await(() -> {
        try {
          JSONObject ready = new JSONObject(shell("run-as app.luoxianlv.audit.touchprobe cat files/touch-ready.json"));
          return run.equals(ready.optString("runId")) && ready.optBoolean("focused");
        } catch (Exception notReady) { return false; }
      }, 10000);
      session = input.openSession();
      Outcome first = new Outcome();
      session.press(new float[] {size.x * .7f,size.y * .6f},150,size.x,size.y,display.getRotation(),first);
      require(first.done.await(5,TimeUnit.SECONDS) && first.success && first.main && first.calls.get() == 1, "完整点击未正确完成");
      String frames = shell("run-as app.luoxianlv.audit.touchprobe cat files/touch-events.jsonl");
      boolean down = false, up = false;
      for (String line : frames.split("\n")) {
        JSONObject frame = new JSONObject(line);
        if (!run.equals(frame.optString("runId")) || !"onTouchEvent".equals(frame.optString("callback"))) continue;
        down |= frame.optInt("actionMasked",-1) == 0;
        up |= frame.optInt("actionMasked",-1) == 1;
      }
      require(down && up, "接收页面没有实际收到按下和抬起，不能仅凭回调认定成功");
      Outcome cancelled = new Outcome();
      AutoCloseable token = session.press(new float[] {size.x * .65f,size.y * .55f},1500,size.x,size.y,display.getRotation(),cancelled);
      await(() -> input.state().getBoolean("active"),5000);
      token.close();
      require(cancelled.done.await(5,TimeUnit.SECONDS) && !cancelled.success, "取消没有完成");
      session.release();
      SharedInput.Session lease = session;
      await(lease::idle,5000);
      require(!input.state().getBoolean("active"), "释放后仍有触摸会话");
      SystemClock.sleep(1600);
      require(cancelled.calls.get() == 1 && !input.state().getBoolean("active"), "被取消的旧定时任务恢复了按键");
      input.select(SharedInput.ACCESSIBILITY);
      await(() -> SharedInput.ACCESSIBILITY.equals(input.state().getString("mode")),5000);
      require(input.state().getBundle("lastTouchCheck") != null, "切换模式后丢失触屏诊断");
      output.putString("stream", "触控兼容验收通过：" + mode + "/" + expected + "；实际按下与抬起、单次主线程回调、取消、释放、旧任务失效及诊断保留。\n");
      passed = true;
    } catch (Throwable failure) {
      output.putString("stream", "触控兼容验收失败：" + failure + "\n");
      output.putString("error", android.util.Log.getStackTraceString(failure));
      if (bridge != null) output.putString("stream",output.getString("stream") + "状态=" + bridge.state().getString("message") + "\n");
    } finally {
      if (session != null) session.close();
      if (bridge != null && previous != null) bridge.select(previous);
    }
    finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED,output);
  }

  private String shell(String command) throws Exception {
    try (var input = new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))) {
      return new String(input.readAllBytes(),StandardCharsets.UTF_8);
    }
  }

  private static void await(java.util.function.BooleanSupplier check,long timeout) {
    long end = SystemClock.elapsedRealtime() + timeout;
    while (!check.getAsBoolean()) { require(SystemClock.elapsedRealtime() < end,"等待条件超时"); SystemClock.sleep(20); }
  }

  private static void require(boolean valid,String message) { if (!valid) throw new AssertionError(message); }

  private static final class Outcome implements SharedInput.Completion {
    final CountDownLatch done = new CountDownLatch(1);
    final AtomicInteger calls = new AtomicInteger();
    volatile boolean success,main;
    @Override public void complete(boolean accepted,String message) {
      success = accepted; main = Looper.myLooper() == Looper.getMainLooper(); calls.incrementAndGet(); done.countDown();
    }
  }
}
