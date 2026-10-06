package app.luoxianlv.hot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import app.luoxianlv.hot.contract.SharedInput;
import app.luoxianlv.hot.contract.AccessibilityBinding;
import app.luoxianlv.hot.contract.HostDiagnostics;
import app.luoxianlv.hot.contract.NativePlaybackSession;
import app.luoxianlv.hot.contract.PlaybackBridge;
import app.luoxianlv.hot.contract.PlaybackPort;
import app.luoxianlv.hot.contract.PlaybackValues;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** 普通前台服务持有唯一播放会话；无障碍仅作为可选的手势与截图适配器。 */
public abstract class NativePlaybackHost implements AutoCloseable {
  public static final int SCREENSHOT_ACCESSIBILITY_UNAVAILABLE = -1001;
  private static volatile NativePlaybackHost instance;
  private static final java.util.Set<NativePlaybackHost> retiringOwners =
      java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
  private final Context context;
  private final Runnable openPrepared = this::openPreparedSession;

  protected NativePlaybackHost(Context context) {
    this.context = java.util.Objects.requireNonNull(context);
  }

  public static NativePlaybackHost current() { return instance; }

  /** 包含已经关闭的服务；系统回调或业务租约未结束时，不能绕过它们激活新代码。 */
  public static boolean anyRetiring() {
    requireMain();
    return !retiringOwners.isEmpty();
  }
  final Handler main = new Handler(Looper.getMainLooper());
  volatile Binding binding;
  private AutoCloseable registration;
  private AutoCloseable preparation;
  boolean connected;
  PlaybackHandover handover;
  private final java.util.ArrayList<Binding> retired = new java.util.ArrayList<>();
  private boolean draining;

  protected abstract NativePlaybackSession createPlaybackSession();

  protected abstract void foregroundRequested(boolean enabled);

  protected android.content.Context playbackContext() {
    return context;
  }

  protected void playbackOpened() {}

  protected void playbackClosed() {}

  protected void playbackFailed(Throwable failure) {}

  protected void playbackUsageChanged() {}

  public final boolean playbackInUse() {
    return binding != null && binding.current() && binding.used;
  }

  protected AutoCloseable whenPlaybackReady(Runnable ready) {
    ready.run();
    return () -> {};
  }

  /** 只在前台服务完成通知登记后打开；权限页面本身不产生第二个播放会话。 */
  public final void open() {
    requireMain();
    if (connected) return;
    StrictJson.require(instance == null || instance == this, "已有播放宿主，禁止重复创建会话");
    instance = this;
    connected = true;
    try {
      AutoCloseable ready = whenPlaybackReady(openPrepared);
      if (connected) preparation = ready;
      else ready.close();
    } catch (Throwable failure) {
      failed(binding, failure);
    }
  }

  /** 系统服务重建检查使用；旧请求和旧业务回调仍通过原来的代次隔离。 */
  public final void reconnect() {
    requireMain();
    if (!connected) return;
    closePreparation();
    closeSession(false);
    openPreparedSession();
  }

  private void openPreparedSession() {
    if (!connected || binding != null) return;
    try {
      if (!initialCreation(this::installPreparedSession))
        main.postDelayed(openPrepared, 16);
    } catch (Throwable failure) {
      failed(binding, failure);
    }
  }

  protected boolean initialCreation(Runnable create) {
    create.run();
    return true;
  }

  private void installPreparedSession() {
    if (!connected || binding != null) return;
    Binding candidate = new Binding();
    binding = candidate;
    candidate.enabled = true;
    try {
      candidate.session = createPlaybackSession();
      candidate.session.connect(playbackContext(), candidate);
      if (binding == candidate) registration = PlaybackBridge.connect(candidate);
      if (binding == candidate) playbackOpened();
    } catch (Throwable failure) {
      failed(candidate, failure);
    }
  }

  final void accessibilityEvent(AccessibilityEvent event) {
    Binding value = binding;
    if (value != null) value.invoke(() -> value.session.event(event));
  }

  final void accessibilityInterrupted() {
    var input = SharedInput.current();
    if (input != null && !SharedInput.ACCESSIBILITY.equals(
        input.state().getString("mode", SharedInput.ACCESSIBILITY))) return;
    Binding value = binding;
    if (value != null) value.invoke(() -> value.session.interrupt());
  }

  @Override
  public final void close() {
    requireMain();
    connected = false;
    main.removeCallbacks(openPrepared);
    closePreparation();
    closeSession();
    if (instance == this) instance = null;
  }

  private static void requireMain() {
    StrictJson.require(Looper.myLooper() == Looper.getMainLooper(), "播放宿主生命周期必须在主线程");
  }

  private void closePreparation() {
    if (preparation == null) return;
    try {
      preparation.close();
    } catch (Exception failure) {
      HostDiagnostics.log(Log.WARN, "播放宿主", "取消业务准备监听失败", failure);
    }
    preparation = null;
  }

  private void failed(Binding owner, Throwable failure) {
    HostDiagnostics.log(Log.ERROR, "播放宿主", "播放业务失败，已关闭当前会话", failure);
    if (handover != null && handover.failed(owner, failure)) return;
    playbackFailed(failure);
    if (binding == owner) closeSession();
  }

  private void closeSession() { closeSession(true); }

  private void closeSession(boolean releaseForeground) {
    Binding previous = binding;
    boolean ownsForeground =
        previous != null
            && (PlaybackBridge.current() == previous || PlaybackBridge.current() == null);
    binding = null;
    PlaybackHandover change = handover;
    handover = null;
    if (registration != null) {
      try {
        registration.close();
      } catch (Exception failure) {
        HostDiagnostics.log(Log.WARN, "播放宿主", "释放播放连接失败", failure);
      }
      registration = null;
    }
    retire(previous);
    if (change != null) change.disconnect();
    if (previous != null) playbackClosed();
    if (ownsForeground && releaseForeground) foregroundRequested(false);
  }

  /** 故障版本不再接收系统输入；宿主系统身份保留，下一进程可重新建立业务会话。 */
  public final void stopBusiness() {
    StrictJson.require(Looper.myLooper() == Looper.getMainLooper(), "播放停用必须在主线程");
    closePreparation();
    closeSession();
  }

  /** 在主线程确认业务任务和系统输入都已结束，不能只看播放开关。 */
  public final boolean playbackCanReplace() {
    Binding current = binding;
    if (Looper.myLooper() != Looper.getMainLooper()
        || current == null
        || current.session == null
        || current.gestures != 0
        || current.captures != 0) return false;
    try {
      return current.session.canReplace();
    } catch (Throwable failure) {
      failed(current, failure);
      return false;
    }
  }

  /** 只在已验证候选的准备阶段调用；此连接串行交接，整组协调器另行检查其他组件的租约。 */
  public final PlaybackHandover preparePlayback(
      NativeLoader.Prepared source,
      java.util.function.Supplier<NativePlaybackSession> factory,
      PlaybackHandover.Listener listener) {
    StrictJson.require(Looper.myLooper() == Looper.getMainLooper(), "播放准备必须在主线程登记");
    if (handover != null
        || !retired.isEmpty()
        || !playbackCanReplace()
        || !binding.session.supportsHandover()) return null;
    PlaybackHandover change = new PlaybackHandover(this, listener);
    handover = change;
    try {
      change.prepare(source.context(context), factory);
    } catch (Throwable failure) {
      change.cancel();
      throw failure;
    }
    return change;
  }

  public final boolean playbackRetiring() {
    return !retired.isEmpty();
  }

  /** 试运行时刚建立的系统连接直接使用候选；只有回退才构造旧业务会话。 */
  public final PlaybackHandover watchPlayback(
      NativeLoader.Prepared recovery,
      java.util.function.Supplier<NativePlaybackSession> factory,
      PlaybackHandover.Listener listener) {
    StrictJson.require(
        Looper.myLooper() == Looper.getMainLooper()
            && connected
            && binding != null
            && binding.session != null
            && handover == null,
        "播放连接不能加入当前试运行");
    handover = new PlaybackHandover(this, recovery.context(context), factory, listener);
    return handover;
  }

  boolean retiredInputIdle() {
    return retired.stream().allMatch(value -> value.gestures == 0 && value.captures == 0);
  }

  void publish(Binding next) {
    disablePlayback();
    binding = next;
    next.activationEpoch++;
    next.enabled = true;
    registration = PlaybackBridge.connect(next);
  }

  void disablePlayback() {
    if (binding != null) {
      binding.enabled = false;
      binding.activationEpoch++;
    }
    playbackUsageChanged();
    if (registration != null) {
      try {
        registration.close();
      } catch (Exception failure) {
        HostDiagnostics.log(Log.WARN, "播放宿主", "释放播放连接失败", failure);
      }
      registration = null;
    }
  }

  void retire(Binding value) {
    if (value == null || value.session == null) return;
    value.enabled = false;
    value.activationEpoch++;
    value.retiredSession = value.session;
    value.session = null;
    retired.add(value);
    retiringOwners.add(this);
    try {
      value.retiredSession.close();
    } catch (Throwable failure) {
      value.closeFailed = true;
      HostDiagnostics.log(Log.WARN, "播放宿主", "关闭播放业务失败，延后后续热更", failure);
    }
    if (!draining) {
      draining = true;
      main.post(this::drainRetired);
    }
  }

  private void drainRetired() {
    boolean waitingForBusiness = false;
    for (var iterator = retired.iterator(); iterator.hasNext(); ) {
      Binding value = iterator.next();
      if (value.closeFailed || value.gestures != 0 || value.captures != 0) continue;
      try {
        if (!value.retiredSession.released()) {
          waitingForBusiness = true;
          continue;
        }
        value.retiredSession = null;
        iterator.remove();
      } catch (Throwable failure) {
        value.closeFailed = true;
        HostDiagnostics.log(Log.WARN, "播放宿主", "检查退役业务失败，延后后续热更", failure);
      }
    }
    // 平台请求等待真实回调唤醒；只有没有完成通知的业务任务需要定期检查。
    // 关闭失败的记录继续保留，但不反复执行已经失败的关闭。
    draining = waitingForBusiness;
    if (draining) main.postDelayed(this::drainRetired, 250);
    if (retired.isEmpty() && retiringOwners.remove(this)) playbackUsageChanged();
  }

  private void platformRequestFinished() {
    if (!retired.isEmpty() && !draining) {
      draining = true;
      main.post(this::drainRetired);
    }
  }

  final class Binding implements AccessibilityBinding, PlaybackPort {
    NativePlaybackSession session;
    NativePlaybackSession retiredSession;
    boolean enabled, closeFailed, used;
    volatile long activationEpoch;
    private int gestures;
    private int captures;

    @Override
    public void contentFailed(Throwable failure) {
      Runnable report =
          () -> {
            if (session == null) return;
            if (handover != null && handover.failed(this, failure)) return;
            if (current()) NativePlaybackHost.this.failed(this, failure);
          };
      if (Looper.myLooper() == Looper.getMainLooper()) report.run();
      else main.post(report);
    }

    boolean released() {
      StrictJson.require(!closeFailed, "播放业务退出失败，禁止结束整组交接");
      return session == null && retiredSession == null && gestures == 0 && captures == 0;
    }

    @Override
    public boolean current() {
      return connected && enabled && binding == this && session != null;
    }

    void invoke(Runnable action) {
      if (!current() || session == null) return;
      try {
        action.run();
      } catch (Throwable failure) {
        failed(this, failure);
      }
    }

    boolean current(long epoch) {
      return activationEpoch == epoch && current();
    }

    void invoke(long epoch, Runnable action) {
      if (current(epoch)) invoke(action);
    }

    @Override
    public Bundle query(String kind) {
      long epoch = activationEpoch;
      if (Looper.myLooper() == Looper.getMainLooper()) return queryOnMain(kind, epoch);
      FutureTask<Bundle> task = new FutureTask<>(() -> queryOnMain(kind, epoch));
      main.post(task);
      try {
        return task.get(1, TimeUnit.SECONDS);
      } catch (Exception failure) {
        task.cancel(false);
        main.removeCallbacks(task);
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        return new Bundle();
      }
    }

    private Bundle queryOnMain(String kind, long epoch) {
      if (!current(epoch)) return new Bundle();
      try {
        return PlaybackValues.copy(session.query(kind));
      } catch (Throwable failure) {
        failed(this, failure);
        return new Bundle();
      }
    }

    @Override
    public void command(String action, Bundle arguments) {
      long epoch = activationEpoch;
      Bundle data = PlaybackValues.copy(arguments);
      Runnable command =
          () ->
              invoke(
                  epoch,
                  () -> {
                    session.command(action, data);
                    // 同一主线程先完成业务状态变更，再通知宿主；迟到或退役命令不会取消新代更新。
                    if (current(epoch)) playbackUsageChanged();
                  });
      if (Looper.myLooper() == Looper.getMainLooper()) command.run();
      else main.post(command);
    }

    @Override
    public boolean gesture(GestureDescription description, GestureCallback callback) {
      if (!current() || Looper.myLooper() != Looper.getMainLooper()) return false;
      NativeAccessibilityService accessibility = NativeAccessibilityService.current();
      if (accessibility == null) return false;
      long epoch = activationEpoch;
      gestures++;
      boolean accepted = false;
      try {
        accepted =
            accessibility.dispatchGesture(
                description,
                new AccessibilityService.GestureResultCallback() {
                  @Override
                  public void onCompleted(GestureDescription gesture) {
                    gestures--;
                    platformRequestFinished();
                    invoke(epoch, () -> callback.completed(true));
                  }

                  @Override
                  public void onCancelled(GestureDescription gesture) {
                    gestures--;
                    platformRequestFinished();
                    invoke(epoch, () -> callback.completed(false));
                  }
                },
                main);
        return accepted;
      } finally {
        if (!accepted) {
          gestures--;
          platformRequestFinished();
        }
      }
    }

    @Override
    public void screenshot(int displayId, ScreenshotCallback callback) {
      if (!current()) return;
      if (Looper.myLooper() != Looper.getMainLooper())
        throw new IllegalStateException("截图请求必须来自主线程");
      if (Build.VERSION.SDK_INT < 30) {
        callback.failure(-1);
        return;
      }
      var input = SharedInput.current();
      if (input != null && !SharedInput.ACCESSIBILITY.equals(input.state().getString("mode", SharedInput.ACCESSIBILITY))) {
        captureInput(input, displayId, callback);
        return;
      }
      NativeAccessibilityService accessibility = NativeAccessibilityService.current();
      if (accessibility == null) {
        callback.failure(SCREENSHOT_ACCESSIBILITY_UNAVAILABLE);
        return;
      }
      captures++;
      try {
        Api30.capture(NativePlaybackHost.this, accessibility, displayId, this, activationEpoch, callback);
      } catch (Throwable failure) {
        captures--;
        platformRequestFinished();
        throw failure;
      }
    }

    private void captureInput(SharedInput.Bridge input, int displayId, ScreenshotCallback callback) {
      captures++;
      long epoch = activationEpoch;
      var finished = new java.util.concurrent.atomic.AtomicBoolean();
      ScreenshotCallback result = new ScreenshotCallback() {
        private void deliver(Runnable action) {
          if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action);
        }
        @Override public void success(AccessibilityBinding.Frame frame) {
          deliver(() -> {
            if (!finished.compareAndSet(false, true)) { frame.close(); return; }
            captures--; platformRequestFinished();
            if (!current(epoch)) { frame.close(); return; }
            try { callback.success(frame); }
            catch (Throwable failure) { frame.close(); failed(Binding.this, failure); }
          });
        }
        @Override public void failure(int code) {
          deliver(() -> {
            if (!finished.compareAndSet(false, true)) return;
            captures--; platformRequestFinished();
            invoke(epoch, () -> callback.failure(code));
          });
        }
      };
      try { input.screenshot(displayId, result); }
      catch (Exception | LinkageError failure) {
        HostDiagnostics.log(Log.WARN, "截图", "输入服务截图请求失败：类型=" + failure.getClass().getSimpleName(), null);
        result.failure(-1);
      }
    }

    @Override
    public void foreground(boolean enabled) {
      long epoch = activationEpoch;
      if (Looper.myLooper() == Looper.getMainLooper())
        invoke(epoch, () -> foregroundRequested(enabled));
      else main.post(() -> invoke(epoch, () -> foregroundRequested(enabled)));
    }

    @Override
    public void usage(boolean playing) {
      long epoch = activationEpoch;
      Runnable report =
          () -> {
            if (!current(epoch)) return;
            used = playing;
            // 同一播放值也可能代表准备边沿；used仍只记录真实演奏，不拿准备时间充健康观察。
            playbackUsageChanged();
          };
      if (Looper.myLooper() == Looper.getMainLooper()) report.run();
      else main.post(report);
    }
  }

  /** 旧系统不解析 Android 11 才存在的截图回调类型。 */
  private static final class Api30 {
    static void capture(
        NativePlaybackHost host,
        NativeAccessibilityService service,
        int displayId,
        Binding owner,
        long epoch,
        AccessibilityBinding.ScreenshotCallback callback) {
      service.takeScreenshot(
          displayId,
          service.getMainExecutor(),
          new AccessibilityService.TakeScreenshotCallback() {
            @Override
            public void onSuccess(AccessibilityService.ScreenshotResult result) {
              owner.captures--;
              host.platformRequestFinished();
              AccessibilityBinding.Frame frame =
                  new AccessibilityBinding.Frame(
                      result.getHardwareBuffer(), result.getColorSpace());
              if (!owner.current(epoch)) {
                frame.close();
                return;
              }
              try {
                callback.success(frame);
              } catch (Throwable failure) {
                frame.close();
                host.failed(owner, failure);
              }
            }

            @Override
            public void onFailure(int code) {
              owner.captures--;
              host.platformRequestFinished();
              owner.invoke(epoch, () -> callback.failure(code));
            }
          });
    }
  }
}
