package app.luoxianlv.host.input;

import android.accessibilityservice.GestureDescription;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import app.luoxianlv.host.Bootstrap;
import app.luoxianlv.hot.contract.AccessibilityBinding;
import app.luoxianlv.hot.contract.NativePage;
import app.luoxianlv.hot.contract.NativePlaybackSession;
import app.luoxianlv.hot.contract.PracticeBridge;
import app.luoxianlv.hot.contract.SharedInput;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** 真实业务浮窗与 shell 合流引擎：长音期间用物理触屏点一次暂停，不能用 performClick 代替。 */
final class FloatingPauseChecks {
  static void run(Instrumentation test, UiAutomation automation, String fixtureCommand) throws Exception {
    Context target = test.getTargetContext();
    SharedInput.Bridge bridge = SharedInput.current();
    require(Settings.canDrawOverlays(target), "请预先允许测试 APP 悬浮窗权限");
    require(bridge.state().getBoolean("coexistenceReady"), "本项需要真实触控合流");
    require(!PracticeBridge.active(), "本项不能在演练场中运行");
    ClassLoader loader = Bootstrap.source().prepared.classLoader();
    Class<?> kv = Class.forName("app.luoxianlv.shared.Kv", true, loader);
    AtomicReference<Context> preparedContext = new AtomicReference<>();
    main(test, () -> preparedContext.set(Bootstrap.source().prepared.context(target)));
    Context context = preparedContext.get();
    SharedPreferences options = (SharedPreferences) kv.getMethod("of", Context.class, String.class)
        .invoke(kv.getField("INSTANCE").get(null), context, "experimental_options");
    boolean present = options.contains("fixed_harmonica_keys");
    boolean previous = options.getBoolean("fixed_harmonica_keys", false);
    AtomicReference<NativePlaybackSession> session = new AtomicReference<>();
    AtomicBoolean ready = new AtomicBoolean(), enabled = new AtomicBoolean();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    boolean fingerDown = false;
    try {
      require(options.edit().putBoolean("fixed_harmonica_keys", true).commit(), "无法设置测试固定按键");
      main(test, () -> {
        NativePlaybackSession actual = Bootstrap.source().factory.playback();
        session.set(actual);
        actual.prepareRecovery(context, new AccessibilityBinding() {
          @Override public boolean current() { return enabled.get(); }
          @Override public boolean gesture(GestureDescription gesture, GestureCallback done) {
            throw new AssertionError("ADB 暂停测试不应使用无障碍手势");
          }
          @Override public void screenshot(int displayId, ScreenshotCallback callback) {
            throw new AssertionError("固定按键测试不应请求截图");
          }
          @Override public void foreground(boolean visible) { }
        }, snapshot(), new NativePage.Ready() {
          @Override public void ready() { ready.set(true); }
          @Override public void failed(Throwable error) { failure.set(error); }
        });
      });
      await("测试曲目未准备好", () -> ready.get() || failure.get() != null, 10000);
      require(failure.get() == null, "测试曲目准备失败：" + failure.get());
      main(test, () -> { enabled.set(true); session.get().activate(); });
      AtomicReference<View> button = new AtomicReference<>();
      await("真实展开浮窗未挂载", () -> {
        main(test, () -> {
          Object floating = field(session.get(), "floating");
          Object panel = field(floating, "panel");
          if (panel != null) button.set((View) field(panel, "play"));
        });
        return button.get() != null && button.get().isAttachedToWindow();
      }, 10000);
      Display display = target.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
      Point pixels = new Point(); display.getRealSize(pixels);
      for (int round = 0; round < 6; ++round) {
        main(test, () -> session.get().command("play", new Bundle()));
        await("没有开始真实合流演奏", () -> bridge.state().getBoolean("active")
            && state(test, session.get()).getBoolean("playing"), 6000);
        // 覆盖首音与后续长音；此时自动触点仍按在下层页面。
        SystemClock.sleep(round % 2 == 0 ? 350 : 2300);
        Rect bounds = new Rect();
        main(test, () -> {
          View view = button.get();
          require(view.isShown(), "暂停按钮不可见");
          int[] location = new int[2]; view.getLocationOnScreen(location);
          bounds.set(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
        });
        // 交替点圆钮与上下留白：后者以前点不到，不能只用中心命中掩盖小热区问题。
        float edge = 3 * target.getResources().getDisplayMetrics().density;
        float targetY = switch (round % 3) {
          case 1 -> bounds.top - edge;
          case 2 -> bounds.bottom + edge;
          default -> bounds.exactCenterY();
        };
        float x = bounds.exactCenterX() / pixels.x, y = targetY / pixels.y;
        float u = x, v = y;
        switch (display.getRotation()) {
          case 1 -> { u = 1 - y; v = x; }
          case 2 -> { u = 1 - x; v = 1 - y; }
          case 3 -> { u = y; v = 1 - x; }
          default -> { }
        }
        long pressedAt = SystemClock.uptimeMillis();
        shell(automation, fixtureCommand + " send down " + Math.round(u * 32767) + " " + Math.round(v * 32767));
        fingerDown = true;
        try {
          await("长音期间一次按下暂停未生效，轮次=" + round,
              () -> !state(test, session.get()).getBoolean("playing"), 400);
        } catch (AssertionError error) {
          try (var input = new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys input"));
              var output = new java.io.FileOutputStream(new java.io.File(target.getFilesDir(), "pause-input-failure.txt"))) {
            output.write(("button=" + bounds + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            input.transferTo(output);
          }
          throw error;
        } finally { shell(automation, fixtureCommand + " send up"); fingerDown = false; }
        long elapsed = SystemClock.uptimeMillis() - pressedAt;
        require(elapsed < 500, "暂停没有及时响应：" + elapsed + "ms");
        await("暂停后触屏未归还", () -> !bridge.state().getBoolean("active"), 3000);
        long position = state(test, session.get()).getLong("positionMs");
        SystemClock.sleep(2200);
        Bundle stopped = state(test, session.get());
        require(!stopped.getBoolean("playing") && !stopped.getBoolean("preparing")
            && stopped.getLong("positionMs") == position && !bridge.state().getBoolean("active"),
            "抬手或旧音符定时器恢复了播放");
        Bundle rewind = new Bundle(); rewind.putLong("position", 0);
        main(test, () -> session.get().command("seek", rewind));
      }
      main(test, () -> session.get().command("play", new Bundle()));
      await("选歌前没有开始真实合流演奏", () -> bridge.state().getBoolean("active")
          && state(test, session.get()).getBoolean("playing"), 6000);
      SystemClock.sleep(350);
      main(test, () -> {
        View root = (View) field(field(session.get(), "floating"), "root");
        View picker = find(root, "选歌");
        require(picker != null && picker.performClick(), "找不到真实选歌入口");
        require(!session.get().query("state").getBoolean("playing")
            && !session.get().query("state").getBoolean("preparing"), "读取选歌列表前没有立即暂停");
      });
      AtomicReference<View> list = new AtomicReference<>();
      await("实际选歌窗口没有打开", () -> {
        main(test, () -> list.set((View) field(field(field(session.get(), "floating"), "playlistWindow"), "view")));
        return list.get() != null && list.get().isAttachedToWindow();
      }, 10000);
      await("选歌期间没有归还触屏", () -> !bridge.state().getBoolean("active"), 3000);
      long position = state(test, session.get()).getLong("positionMs");
      SystemClock.sleep(2200);
      require(!state(test, session.get()).getBoolean("playing")
          && state(test, session.get()).getLong("positionMs") == position
          && !bridge.state().getBoolean("active"), "选歌期间仍然自动点击或推进进度");
      main(test, () -> {
        View close = find(list.get(), "关闭");
        require(close != null && close.performClick(), "真实选歌窗口无法关闭");
      });
      SystemClock.sleep(300);
      require(!state(test, session.get()).getBoolean("playing") && !bridge.state().getBoolean("active"),
          "关闭选歌窗口后自动恢复了演奏");
    } finally {
      if (fingerDown) shell(automation, fixtureCommand + " send up");
      if (session.get() != null) {
        main(test, () -> { enabled.set(false); session.get().close(); });
        await("测试会话未释放", session.get()::released, 7000);
      }
      var editor = options.edit();
      if (present) editor.putBoolean("fixed_harmonica_keys", previous);
      else editor.remove("fixed_harmonica_keys");
      require(editor.commit(), "无法恢复固定按键设置");
    }
  }

  private static Bundle snapshot() {
    Bundle song = new Bundle();
    song.putInt("schema", 1); song.putString("id", "instrument-floating-pause");
    song.putString("title", "浮窗单次暂停测试"); song.putString("score", "1 1 1 1 1 1 1 1 1 1");
    song.putInt("bpm", 30); song.putString("source", "仪器内存测试曲");
    Bundle window = new Bundle(); window.putInt("schema", 1);
    window.putInt("x", 40); window.putInt("y", 250); window.putString("dock", "NONE");
    window.putBoolean("expanded", true);
    Bundle snapshot = new Bundle(); snapshot.putInt("schema", 1); snapshot.putBundle("song", song);
    snapshot.putString("mode", "NATURAL"); snapshot.putBoolean("fixed", true);
    snapshot.putBoolean("floating", true); snapshot.putBundle("floatingState", window);
    snapshot.putFloat("speed", 1); return snapshot;
  }

  private static Object field(Object value, String name) {
    try { Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value); }
    catch (ReflectiveOperationException error) { throw new AssertionError(error); }
  }
  private static View find(View view, String description) {
    if (view.getContentDescription() != null && description.contentEquals(view.getContentDescription())) return view;
    if (view instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); ++i) {
        View found = find(group.getChildAt(i), description);
        if (found != null) return found;
      }
    }
    return null;
  }
  private static Bundle state(Instrumentation test, NativePlaybackSession session) {
    AtomicReference<Bundle> state = new AtomicReference<>();
    main(test, () -> state.set(session.query("state"))); return state.get();
  }
  private static void main(Instrumentation test, Runnable task) {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    test.runOnMainSync(() -> { try { task.run(); } catch (Throwable error) { failure.set(error); } });
    if (failure.get() != null) throw new AssertionError(failure.get());
  }
  private static void shell(UiAutomation automation, String command) throws Exception {
    try (var input = new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))) {
      String output = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      require(output.trim().startsWith("OK"), "物理触屏测试命令失败：" + output);
    }
  }
  private static void await(String message, BooleanSupplier condition, long timeout) {
    long until = SystemClock.elapsedRealtime() + timeout;
    while (!condition.getAsBoolean()) {
      require(SystemClock.elapsedRealtime() < until, message); SystemClock.sleep(10);
    }
  }
  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
