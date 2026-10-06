package app.luoxianlv.host.input;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import app.luoxianlv.hot.contract.PlaybackBridge;
import app.luoxianlv.hot.contract.SharedInput;
import app.luoxianlv.hot.NativePlaybackHost;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 真实业务 VM 等待权限快照时切模式、断连或取消；假桥不读取设备，也不注入触摸。 */
final class StartupPermissionChecks {
  static void run(Instrumentation test, ClassLoader business) throws Exception {
    Context context = test.getTargetContext();
    require(android.provider.Settings.canDrawOverlays(context), "启动竞态验收需要预先允许悬浮窗");
    SharedInput.Bridge previous = SharedInput.current();
    require(previous != null && !previous.state().getBoolean("active"), "请先结束当前输入捕获");
    Class<?> repositoryType = Class.forName("app.luoxianlv.library.SongRepository", true, business);
    Object repository = repositoryType.getConstructor(Context.class).newInstance(context);
    boolean desired = (Boolean) repositoryType.getMethod("getFloatingEnabled").invoke(repository);
    boolean[] visible = {false};
    Object[] store = {null}, model = {null};
    Class<?> modelType = Class.forName("app.luoxianlv.library.LibraryViewModel", true, business);
    Class<?> storeType = Class.forName("androidx.lifecycle.ViewModelStore", true, business);
    Field local = modelType.getDeclaredField("_local");
    local.setAccessible(true);
    FakeBridge fake = new FakeBridge();
    try {
      main(test, () -> {
        var port = PlaybackBridge.current();
        if (port != null) {
          Bundle state = port.query("state");
          require(!state.getBoolean("playing") && !state.getBoolean("preparing"), "请先结束演奏或准备过程");
          visible[0] = state.getBoolean("floatingVisible");
          port.command("showFloating", bundle("enabled", false));
        }
        repositoryType.getMethod("setFloatingEnabled", boolean.class).invoke(repository, false);
      });
      await(test, "旧播放宿主未完全退出", () -> PlaybackBridge.current() == null
          && NativePlaybackHost.current() == null && !NativePlaybackHost.anyRetiring());
      main(test, () -> {
        SharedInput.connect(fake);
        store[0] = storeType.getConstructor().newInstance();
        Class<?> providerType = Class.forName("androidx.lifecycle.ViewModelProvider", true, business);
        Class<?> factoryType = Class.forName("androidx.lifecycle.ViewModelProvider$Factory", true, business);
        Object factory = Class.forName("androidx.lifecycle.ViewModelProvider$AndroidViewModelFactory", true, business)
            .getConstructor(Application.class).newInstance(context.getApplicationContext());
        Object provider = providerType.getConstructor(storeType, factoryType).newInstance(store[0], factory);
        model[0] = providerType.getMethod("get", Class.class).invoke(provider, modelType);
      });
      for (String scenario : new String[] {"mode", "disconnect", "cancel"}) {
        fake.reset();
        main(test, () -> modelType.getMethod("setFloatingEnabled", boolean.class).invoke(model[0], true));
        require(fake.refresh.await(10, TimeUnit.SECONDS), "实际 VM 未请求刷新权限快照：" + scenario);
        if ("cancel".equals(scenario))
          main(test, () -> modelType.getMethod("setFloatingEnabled", boolean.class).invoke(model[0], false));
        fake.publish("mode".equals(scenario) ? SharedInput.WIRELESS : SharedInput.SHIZUKU,
            !"disconnect".equals(scenario), true);
        await(test, "实际 VM 没有结束准备：" + scenario, () -> !flag(local, model[0], "StartingFloating"));
        SystemClock.sleep(300);
        main(test, () -> {
          require(!(Boolean) repositoryType.getMethod("getFloatingEnabled").invoke(repository),
              "等待结束后仍提交了开启意图：" + scenario);
          require(!flag(local, model[0], "FloatingEnabled"), "界面错误显示开启：" + scenario);
          var port = PlaybackBridge.current();
          require(port == null || !port.query("state").getBoolean("floatingVisible"),
              "迟到启动打开了悬浮窗：" + scenario);
          if ("mode".equals(scenario)) {
            Object state = local.get(model[0]).getClass().getMethod("getValue").invoke(local.get(model[0]));
            String notice = (String) state.getClass().getMethod("getNotice").invoke(state);
            require(notice != null && notice.contains("输入模式已变化"), "没有说明模式已变化");
          }
          if ("disconnect".equals(scenario))
            require(flag(local, model[0], "ShowInputModePrompt"), "断连后没有提供重新设置入口");
        });
      }
    } finally {
      main(test, () -> {
        if (model[0] != null) modelType.getMethod("setFloatingEnabled", boolean.class).invoke(model[0], false);
        if (store[0] != null) storeType.getMethod("clear").invoke(store[0]);
        SharedInput.connect(previous);
        repositoryType.getMethod("setFloatingEnabled", boolean.class).invoke(repository, desired);
        if (visible[0] && desired)
          context.startForegroundService(new Intent().setClassName(context.getPackageName(),
              "app.luoxianlv.service.PlaybackForegroundService")
              .setAction("app.luoxianlv.START_FLOATING_PLAYER")
              .putExtra("playbackStartAtNanos", SystemClock.elapsedRealtimeNanos()));
      });
    }
  }

  private static boolean flag(Field field, Object model, String name) throws Exception {
    Object flow = field.get(model);
    Object value = flow.getClass().getMethod("getValue").invoke(flow);
    return (Boolean) value.getClass().getMethod("get" + name).invoke(value);
  }

  private static Bundle bundle(String key, boolean value) {
    Bundle result = new Bundle(); result.putBoolean(key, value); return result;
  }

  private static void main(Instrumentation test, Checked action) {
    Throwable[] failure = {null};
    test.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
    if (failure[0] != null) throw new AssertionError(failure[0]);
  }

  private static void await(Instrumentation test, String message, Condition condition) {
    long until = SystemClock.elapsedRealtime() + 10000;
    while (true) {
      boolean[] done = {false}; main(test, () -> done[0] = condition.test());
      if (done[0]) return;
      require(SystemClock.elapsedRealtime() < until, message); SystemClock.sleep(50);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
  private interface Checked { void run() throws Exception; }
  private interface Condition { boolean test() throws Exception; }

  private static final class FakeBridge implements SharedInput.Bridge {
    private volatile Bundle value;
    volatile CountDownLatch refresh;
    FakeBridge() { reset(); }
    void reset() { refresh = new CountDownLatch(1); publish(SharedInput.SHIZUKU, true, false); }
    void publish(String mode, boolean ready, boolean overlay) {
      Bundle next = new Bundle(); next.putString("mode", mode);
      next.putBoolean("connected", ready); next.putBoolean("touchReady", ready);
      next.putBoolean("overlayGranted", overlay); next.putBoolean("initialized", true); value = next;
    }
    @Override public Bundle state() { return new Bundle(value); }
    @Override public void command(String action, Bundle arguments) { if ("refresh".equals(action)) refresh.countDown(); }
    @Override public void select(String mode) { throw new AssertionError("竞态验收不应选择实际设备模式"); }
    @Override public AutoCloseable observe(Runnable changed) { return () -> {}; }
    @Override public SharedInput.Session openSession() { throw new AssertionError("竞态验收不应启动触摸会话"); }
    @Override public AutoCloseable press(float[] p, int d, int w, int h, int r, SharedInput.Completion c) {
      throw new AssertionError("竞态验收不应注入触摸");
    }
    @Override public void release() {}
  }
}
