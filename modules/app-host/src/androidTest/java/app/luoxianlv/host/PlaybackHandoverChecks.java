package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import app.luoxianlv.hot.*;
import app.luoxianlv.hot.contract.*;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 使用完整业务 APK 的两个真实加载器，不把同加载器新对象当作跨版本交接证据。 */
final class PlaybackHandoverChecks {
  private final Instrumentation runner;
  private final NativePlaybackHost service;
  private final NativeLoader.Prepared next;
  private final BusinessFactory factory;

  private PlaybackHandoverChecks(
      Instrumentation runner, NativeLoader.Prepared next, Activity activity) throws Exception {
    this.runner = runner;
    this.next = next;
    var field = PlaybackBridge.current().getClass().getDeclaredField("this$0");
    field.setAccessible(true);
    service = (NativePlaybackHost) field.get(PlaybackBridge.current());
    Object[] values = new Object[1];
    main(
        () -> {
          try {
            values[0] = next.factory();
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    factory = (BusinessFactory) values[0];
  }

  static void run(Instrumentation runner, Activity activity) throws Exception {
    var application = activity.getApplication();
    File root = new File(application.getNoBackupFilesDir(), "native-update");
    var loader =
        new NativeLoader(
            application,
            new ContentStore(root),
            new ContentQuarantine(new File(root, "quarantine")),
            1);
    var next = loader.prepareBaseline(BundledBaseline.prepare(application));
    var checks = new PlaybackHandoverChecks(runner, next, activity);
    checks.run();
  }

  private static NativePlaybackSession session(PlaybackPort port) {
    try {
      var field = port.getClass().getDeclaredField("session");
      field.setAccessible(true);
      return (NativePlaybackSession) field.get(port);
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private void run() {
    idle();
    PlaybackPort originalPort = PlaybackBridge.current();
    NativePlaybackSession original = session(originalPort);
    Bundle[] saved = new Bundle[1];
    main(() -> saved[0] = PlaybackValues.copy(original.snapshot()));
    try {
      check(original.getClass().getClassLoader() != next.classLoader(), "未建立真实第二业务加载器");
      main(
          () -> {
            Bundle speed = new Bundle();
            speed.putFloat("speed", 1.25f);
            originalPort.command("setSpeed", speed);
            Bundle seek = new Bundle();
            seek.putLong("position", 1000);
            originalPort.command("seek", seek);
          });
      queuedCommandCannotRevive(originalPort);
      Probe stale = prepare(factory::playback);
      await("候选未完成无声准备", () -> stale.ready || stale.error != null);
      main(
          () -> {
            check(stale.error == null && stale.change.valid(), "候选准备失败");
            check(PlaybackBridge.current() == originalPort, "候选预热提前夺走播放连接");
            Bundle seek = new Bundle();
            seek.putLong("position", 1500);
            originalPort.command("seek", seek);
            check(!stale.change.valid() && !stale.change.commit(), "提交了用户已经改变的旧快照");
            stale.change.cancel();
          });
      idle();

      Probe prepared = prepare(factory::playback);
      await("候选没有就绪", () -> prepared.ready || prepared.error != null);
      Bundle[] expected = new Bundle[1];
      main(
          () -> {
            expected[0] = original.snapshot();
            check(prepared.error == null && prepared.change.commit(), "真实业务交接失败");
            check(PlaybackBridge.current() != originalPort, "新会话未接管稳定无障碍身份");
            check(
                session(PlaybackBridge.current()).getClass().getClassLoader() == next.classLoader(),
                "播放仍在旧加载器");
            sameState(expected[0], session(PlaybackBridge.current()).snapshot());
            check(!PlaybackBridge.current().query("state").getBoolean("playing"), "交接自动开始了暂停的播放");
            check(session(originalPort) == original, "健康确认前释放了恢复会话");
            prepared.change.finish();
            check(
                session(originalPort) == null && originalPort.query("state").isEmpty(),
                "退役句柄仍能控制或读取播放");
          });
      await("交接后的真实浮窗未挂载", this::floatingAttached);
      idle();
      check(original.released(), "退役业务的线程或任务没有结束");

      var release = new java.util.concurrent.atomic.AtomicBoolean();
      Probe held =
          prepare(
              () ->
                  new FaultSession(factory.playback(), false) {
                    @Override
                    public boolean released() {
                      return release.get() && super.released();
                    }
                  });
      await("租约测试候选未就绪", () -> held.ready || held.error != null);
      try {
        main(
            () -> {
              held.change.cancel();
              check(service.playbackRetiring(), "候选后台工作未结束却丢掉退役记录");
              check(
                  service.preparePlayback(
                          next,
                          () -> {
                            throw new AssertionError("退役未完成时构造了第三个会话");
                          },
                          new Probe())
                      == null,
                  "退役期间接受了新候选");
            });
      } finally {
        release.set(true);
      }
      idle();

      PlaybackPort stable = PlaybackBridge.current();
      Probe brokenPrepare = prepare(() -> new FaultSession(factory.playback(), true));
      await("准备错误没有报告", () -> brokenPrepare.error != null);
      main(
          () -> {
            check(brokenPrepare.contentFailure && PlaybackBridge.current() == stable, "准备失败影响了旧会话");
            brokenPrepare.change.cancel();
          });
      idle();

      Probe brokenCommit = prepare(() -> new FaultSession(factory.playback(), false));
      await("故障候选没有完成准备", () -> brokenCommit.ready || brokenCommit.error != null);
      main(
          () -> {
            boolean rejected = false;
            try {
              brokenCommit.change.commit();
            } catch (IllegalStateException expectedFailure) {
              rejected = true;
            }
            check(rejected, "激活故障未抛出");
          });
      await("激活错误没有报告", () -> brokenCommit.error != null);
      restore(brokenCommit);
      main(() -> check(PlaybackBridge.current() == stable, "激活失败未恢复旧控制句柄"));
      await("回退后的真实浮窗未挂载", this::floatingAttached);
      idle();

      // 试运行中继续操作，再回退必须保留新进度和速度，不恢复到提交前。
      Probe trial = prepare(factory::playback);
      await("试运行候选未就绪", () -> trial.ready || trial.error != null);
      Bundle[] latest = new Bundle[1];
      main(
          () -> {
            check(trial.change.commit(), "无法进入试运行");
            Bundle changedSong = PlaybackValues.copy(saved[0].getBundle("song"));
            changedSong.putString("id", "hot-handover-latest");
            changedSong.putString("title", "热更交接测试曲目");
            PlaybackBridge.current().command("select", changedSong);
          });
      idle();
      main(
          () -> {
            Bundle speed = new Bundle();
            speed.putFloat("speed", 1.5f);
            PlaybackBridge.current().command("setSpeed", speed);
            Bundle seek = new Bundle();
            seek.putLong("position", 2000);
            PlaybackBridge.current().command("seek", seek);
            latest[0] = session(PlaybackBridge.current()).snapshot();
          });
      var staleGestures = new java.util.concurrent.atomic.AtomicInteger();
      main(
          () -> {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(1, 1);
            var gesture =
                new android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(
                        new android.accessibilityservice.GestureDescription.StrokeDescription(
                            path, 0, 600))
                    .build();
            check(
                ((AccessibilityBinding) PlaybackBridge.current())
                    .gesture(gesture, success -> staleGestures.incrementAndGet()),
                "测试手势未被系统接受");
            check(!service.playbackCanReplace(), "未结束的系统手势被当成空闲");
          });
      restore(trial);
      check(staleGestures.get() == 0, "回退后仍向退役会话投递手势结果");
      main(() -> sameState(latest[0], session(PlaybackBridge.current()).snapshot()));
      idle();

      Probe silent =
          prepare(
              () ->
                  new FaultSession(factory.playback(), false) {
                    @Override
                    public void prepare(
                        Context context,
                        AccessibilityBinding binding,
                        Bundle state,
                        NativePage.Ready ready) {}
                  });
      await("无响应候选没有按预算超时", 40000, () -> silent.error != null);
      main(
          () -> {
            check(
                !silent.contentFailure
                    && silent.error instanceof java.util.concurrent.TimeoutException,
                "准备超时被错记成已确认内容错误");
            silent.change.cancel();
          });
      idle();
    } finally {
      // 恢复用户原来的选曲、速度和浮窗偏好，不保留测试操作。
      main(
          () -> {
            PlaybackPort port = PlaybackBridge.current();
            if (port == null) return;
            port.command("select", saved[0].getBundle("song"));
            Bundle speed = new Bundle();
            speed.putFloat("speed", saved[0].getFloat("speed"));
            port.command("setSpeed", speed);
          });
      idle();
      main(
          () -> {
            Bundle seek = new Bundle();
            seek.putLong("position", saved[0].getLong("position"));
            PlaybackBridge.current().command("seek", seek);
          });
    }
  }

  private void restore(Probe probe) {
    Probe recovery = new Probe();
    main(
        () ->
            probe.change.rollback(
                new NativePage.Ready() {
                  @Override
                  public void ready() {
                    recovery.ready = true;
                  }

                  @Override
                  public void failed(Throwable failure) {
                    recovery.error = failure;
                  }
                }));
    await("播放回退未完成", () -> recovery.ready || recovery.error != null);
    if (recovery.error != null) throw new AssertionError("播放回退失败", recovery.error);
  }

  private void queuedCommandCannotRevive(PlaybackPort port) {
    main(
        () -> {
          var posted = new java.util.concurrent.CountDownLatch(1);
          Thread caller =
              new Thread(
                  () -> {
                    Bundle stale = new Bundle();
                    stale.putFloat("speed", .5f);
                    port.command("setSpeed", stale);
                    posted.countDown();
                  });
          caller.start();
          try {
            check(posted.await(2, java.util.concurrent.TimeUnit.SECONDS), "无法排队测试命令");
            var disable = NativePlaybackHost.class.getDeclaredMethod("disablePlayback");
            disable.setAccessible(true);
            var publish =
                NativePlaybackHost.class.getDeclaredMethod("publish", port.getClass());
            publish.setAccessible(true);
            disable.invoke(service);
            publish.invoke(service, port);
          } catch (Exception error) {
            throw new AssertionError(error);
          }
        });
    main(() -> check(port.query("state").getFloat("speed") == 1.25f, "重新启用旧会话后执行了先前排队的命令"));
  }

  private Probe prepare(Supplier<NativePlaybackSession> factory) {
    idle();
    Probe probe = new Probe();
    main(
        () -> {
          probe.change = service.preparePlayback(next, factory, probe);
          check(probe.change != null, "服务没有接受交接准备");
        });
    return probe;
  }

  private void idle() {
    await(
        "播放服务或退役任务未空闲",
        () -> {
          boolean[] ready = {false};
          main(() -> ready[0] = service.playbackCanReplace() && !service.playbackRetiring());
          return ready[0];
        });
  }

  private boolean floatingAttached() {
    boolean[] attached = {false};
    main(
        () -> {
          try {
            NativePlaybackSession value = session(PlaybackBridge.current());
            var field = value.getClass().getDeclaredField("floating");
            field.setAccessible(true);
            Object controls = field.get(value);
            var root = controls.getClass().getDeclaredField("root");
            root.setAccessible(true);
            View view = (View) root.get(controls);
            attached[0] =
                view != null
                    && view.isAttachedToWindow()
                    && view.getContext().getClassLoader() == value.getClass().getClassLoader();
          } catch (Exception error) {
            throw new AssertionError(error);
          }
        });
    return attached[0];
  }

  private static void sameState(Bundle before, Bundle after) {
    check(
        before.getBundle("song").getString("id").equals(after.getBundle("song").getString("id")),
        "交接丢失选曲");
    check(before.getLong("position") == after.getLong("position"), "交接丢失播放进度");
    check(before.getFloat("speed") == after.getFloat("speed"), "交接丢失速度");
    check(before.getBoolean("floating") == after.getBoolean("floating"), "交接丢失浮窗显示状态");
    check(
        before.getBundle("floatingState").getBoolean("expanded")
            == after.getBundle("floatingState").getBoolean("expanded"),
        "交接丢失浮窗展开状态");
  }

  private void main(Runnable action) {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    runner.runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable error) {
            failure.set(error);
          }
        });
    if (failure.get() != null) throw new AssertionError("播放交接主线程检查失败", failure.get());
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static void await(String message, BooleanSupplier condition) {
    await(message, 20000, condition);
  }

  private static void await(String message, long budget, BooleanSupplier condition) {
    long end = SystemClock.uptimeMillis() + budget;
    while (!condition.getAsBoolean()) {
      check(SystemClock.uptimeMillis() < end, message);
      SystemClock.sleep(40);
    }
  }

  private static final class Probe implements PlaybackHandover.Listener {
    PlaybackHandover change;
    volatile boolean ready, contentFailure;
    volatile Throwable error;

    @Override
    public void ready(PlaybackHandover change) {
      ready = true;
    }

    @Override
    public void failed(PlaybackHandover change, Throwable failure, boolean contentFailure) {
      this.contentFailure = contentFailure;
      error = failure;
    }
  }

  private static class FaultSession implements NativePlaybackSession {
    private final NativePlaybackSession real;
    private final boolean prepareFailure;

    FaultSession(NativePlaybackSession real, boolean prepareFailure) {
      this.real = real;
      this.prepareFailure = prepareFailure;
    }

    @Override
    public void connect(Context context, AccessibilityBinding binding) {
      real.connect(context, binding);
    }

    @Override
    public void prepare(
        Context context, AccessibilityBinding binding, Bundle state, NativePage.Ready ready) {
      check(!binding.current(), "候选已经获得输入能力");
      android.graphics.Path path = new android.graphics.Path();
      path.moveTo(1, 1);
      var gesture =
          new android.accessibilityservice.GestureDescription.Builder()
              .addStroke(
                  new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 1))
              .build();
      check(
          !binding.gesture(
              gesture,
              success -> {
                throw new AssertionError("候选执行了系统手势");
              }),
          "候选拥有系统手势权限");
      if (prepareFailure) throw new IllegalStateException("测试准备故障");
      real.prepare(context, binding, state, ready);
    }

    @Override
    public boolean supportsHandover() {
      return true;
    }

    @Override
    public void restore(Bundle state, NativePage.Ready ready) {
      real.restore(state, ready);
    }

    @Override
    public void activate() {
      real.activate();
      throw new IllegalStateException("测试激活故障");
    }

    @Override
    public void deactivate() {
      real.deactivate();
    }

    @Override
    public Bundle snapshot() {
      return real.snapshot();
    }

    @Override
    public long revision() {
      return real.revision();
    }

    @Override
    public boolean released() {
      return real.released();
    }

    @Override
    public void interrupt() {
      real.interrupt();
    }

    @Override
    public boolean canReplace() {
      return real.canReplace();
    }

    @Override
    public Bundle query(String kind) {
      return real.query(kind);
    }

    @Override
    public void command(String action, Bundle arguments) {
      real.command(action, arguments);
    }

    @Override
    public void close() {
      real.close();
    }
  }
}
