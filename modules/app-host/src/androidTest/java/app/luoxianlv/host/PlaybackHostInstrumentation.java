package app.luoxianlv.host;

import android.accessibilityservice.GestureDescription;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import app.luoxianlv.hot.NativeAccessibilityService;
import app.luoxianlv.hot.NativePlaybackHost;
import app.luoxianlv.hot.contract.AccessibilityBinding;
import app.luoxianlv.hot.contract.NativePlaybackSession;
import app.luoxianlv.hot.contract.PlaybackBridge;
import app.luoxianlv.hot.contract.PlaybackPort;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 不打开系统窗口或注入触摸，验证无无障碍连接时的普通宿主和退役租约。 */
public final class PlaybackHostInstrumentation extends Instrumentation {
  private Host host;
  private final ArrayList<Host> hosts = new ArrayList<>();

  @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

  @Override public void onStart() {
    Bundle report = new Bundle();
    boolean success = false;
    try {
      require(getTargetContext().getPackageName().endsWith(".debug"), "仅允许测试 Debug 宿主");
      main(() -> {
        require(!NativeAccessibilityService.isConnected(), "请先关闭本应用无障碍连接");
        require(PlaybackBridge.current() == null && NativePlaybackHost.current() == null,
            "请先关闭真实播放前台服务，不能抢占用户会话");
        host = new Host(getTargetContext());
        hosts.add(host);
        host.open();
        require(host.sessions.size() == 1, "没有独立创建播放会话");
        require(host.playbackCanReplace(), "无无障碍时宿主未达到空闲状态");
        require(PlaybackBridge.current() == host.sessions.get(0).binding,
            "普通宿主没有登记唯一播放桥");
        require(host.sessions.get(0).binding.current(), "普通宿主仍依赖无障碍身份");
        Path path = new Path();
        path.moveTo(1, 1);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, 1)).build();
        require(!host.sessions.get(0).binding.gesture(gesture,
            accepted -> { throw new AssertionError("没有系统适配器却返回手势结果"); }),
            "缺少无障碍时仍接受系统手势");
        require(host.playbackCanReplace(), "被拒绝的手势留下了未完成平台请求");

        int[] failure = {Integer.MIN_VALUE};
        host.sessions.get(0).binding.screenshot(0, new AccessibilityBinding.ScreenshotCallback() {
          @Override public void success(AccessibilityBinding.Frame frame) {
            frame.close(); throw new AssertionError("无无障碍时不应取得截图");
          }
          @Override public void failure(int code) { failure[0] = code; }
        });
        require(failure[0] == (Build.VERSION.SDK_INT >= 30
            ? NativePlaybackHost.SCREENSHOT_ACCESSIBILITY_UNAVAILABLE : -1),
            "截图能力缺失没有返回明确错误");
        require(host.playbackCanReplace(), "拒绝截图留下了未完成平台请求");

        Session old = host.sessions.get(0);
        PlaybackPort previous = PlaybackBridge.current();
        old.released = false;
        host.reconnect();
        require(old.closed && host.sessions.size() == 2, "旧会话未关闭或新会话未创建");
        require(PlaybackBridge.current() != previous, "重建仍使用旧控制句柄");
        require(previous.query("state").isEmpty(), "旧会话仍能查询业务");
        previous.command("showFloating", new Bundle());
        require(old.commands == 0, "迟到命令进入退役业务");
        require(host.playbackRetiring(), "异步退役租约被提前清除");
        old.released = true;
      });

      long deadline = SystemClock.uptimeMillis() + 3000;
      while (true) {
        boolean[] retiring = {true};
        main(() -> retiring[0] = host.playbackRetiring());
        if (!retiring[0]) break;
        require(SystemClock.uptimeMillis() < deadline, "已释放的旧业务未完成退役");
        SystemClock.sleep(30);
      }
      main(() -> {
        Session held = host.sessions.get(1);
        held.holdTask();
        host.close();
        require(PlaybackBridge.current() == null && NativePlaybackHost.current() == null,
            "关闭普通宿主后连接仍存在");
        require(held.closed && NativePlaybackHost.anyRetiring(),
            "关闭旧宿主时遗漏仍在运行的后台任务");
        host = new Host(getTargetContext());
        hosts.add(host);
        host.open();
        require(!host.playbackRetiring() && NativePlaybackHost.anyRetiring(),
            "新宿主没有本地退休记录时绕过了旧宿主租约");
        held.releaseTask();
      });
      deadline = SystemClock.uptimeMillis() + 3000;
      while (true) {
        boolean[] retiring = {true};
        main(() -> retiring[0] = NativePlaybackHost.anyRetiring());
        if (!retiring[0]) break;
        require(SystemClock.uptimeMillis() < deadline, "后台任务完成后全局退休登记没有解除");
        SystemClock.sleep(30);
      }
      main(() -> {
        host.close();
        require(host.sessions.get(0).closed, "关闭新宿主遗漏当前业务");
      });
      report.putString("stream", "普通播放宿主验收通过：无无障碍创建、明确截图拒绝、旧命令隔离、跨宿主后台任务租约及关闭清理。未模拟公网或实际游戏触摸。\n");
      success = true;
    } catch (Throwable failure) {
      report.putString("stream", "普通播放宿主验收失败：" + failure + "\n");
      report.putString("error", android.util.Log.getStackTraceString(failure));
    } finally {
      for (Host value : hosts) {
        for (Session session : value.sessions) session.releaseTask();
        main(value::close);
      }
    }
    finish(success ? 0 : -1, report);
  }

  private void main(Runnable task) {
    Throwable[] failure = {null};
    runOnMainSync(() -> { try { task.run(); } catch (Throwable error) { failure[0] = error; } });
    if (failure[0] != null) throw new AssertionError(failure[0]);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static final class Host extends NativePlaybackHost {
    final ArrayList<Session> sessions = new ArrayList<>();
    Host(Context context) { super(context); }
    @Override protected NativePlaybackSession createPlaybackSession() {
      Session value = new Session(); sessions.add(value); return value;
    }
    @Override protected void foregroundRequested(boolean enabled) {}
  }

  private static final class Session implements NativePlaybackSession {
    AccessibilityBinding binding;
    boolean closed, released = true;
    final AtomicBoolean taskDone = new AtomicBoolean(true);
    CountDownLatch taskGate;
    int commands;
    @Override public void connect(Context context, AccessibilityBinding binding) { this.binding = binding; }
    @Override public void interrupt() {}
    @Override public Bundle query(String kind) { return new Bundle(); }
    @Override public void command(String action, Bundle arguments) { commands++; }
    @Override public boolean canReplace() { return !closed; }
    void holdTask() {
      taskGate = new CountDownLatch(1);
      taskDone.set(false);
      Thread worker = new Thread(() -> {
        try { taskGate.await(10, TimeUnit.SECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        finally { taskDone.set(true); }
      }, "playback-retirement-test");
      worker.setDaemon(true);
      worker.start();
    }
    void releaseTask() { if (taskGate != null) taskGate.countDown(); }
    @Override public boolean released() { return closed && released && taskDone.get(); }
    @Override public void close() { closed = true; }
  }
}
