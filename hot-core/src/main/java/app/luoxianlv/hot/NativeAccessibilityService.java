package app.luoxianlv.hot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import app.luoxianlv.hot.contract.AccessibilityBinding;
import app.luoxianlv.hot.contract.HostDiagnostics;
import app.luoxianlv.hot.contract.NativePlaybackSession;
import app.luoxianlv.hot.contract.PlaybackBridge;
import app.luoxianlv.hot.contract.PlaybackPort;
import app.luoxianlv.hot.contract.PlaybackValues;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** 稳定无障碍身份；业务只持有受会话约束的系统能力，不能接管系统服务本身。 */
public abstract class NativeAccessibilityService extends AccessibilityService {
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
    return this;
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

  @Override
  protected final void onServiceConnected() {
    connected = true;
    closePreparation();
    closeSession();
    try {
      preparation = whenPlaybackReady(this::openPreparedSession);
    } catch (Throwable failure) {
      failed(binding, failure);
    }
  }

  private void openPreparedSession() {
    if (!connected || binding != null) return;
    try {
      if (!initialCreation(this::installPreparedSession))
        new android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed(this::openPreparedSession, 16);
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

  @Override
  public final void onAccessibilityEvent(AccessibilityEvent event) {
    Binding current = binding;
    if (current != null) current.invoke(() -> current.session.event(event));
  }

  @Override
  public final void onInterrupt() {
    Binding current = binding;
    if (current != null) current.invoke(() -> current.session.interrupt());
  }

  @Override
  public final boolean onUnbind(Intent intent) {
    connected = false;
    closePreparation();
    closeSession();
    return super.onUnbind(intent);
  }

  @Override
  public final void onDestroy() {
    connected = false;
    closePreparation();
    closeSession();
    super.onDestroy();
  }

  private void closePreparation() {
    if (preparation == null) return;
    try {
      preparation.close();
    } catch (Exception failure) {
      HostDiagnostics.log(Log.WARN, "无障碍宿主", "取消业务准备监听失败", failure);
    }
    preparation = null;
  }

  private void failed(Binding owner, Throwable failure) {
    HostDiagnostics.log(Log.ERROR, "无障碍宿主", "播放业务失败，已关闭当前会话", failure);
    if (handover != null && handover.failed(owner, failure)) return;
    playbackFailed(failure);
    if (binding == owner) closeSession();
  }

  private void closeSession() {
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
        HostDiagnostics.log(Log.WARN, "无障碍宿主", "释放播放连接失败", failure);
      }
      registration = null;
    }
    retire(previous);
    if (change != null) change.disconnect();
    if (previous != null) playbackClosed();
    if (ownsForeground) foregroundRequested(false);
  }

  /** 故障版本不再接收系统输入；无障碍系统身份保留，下一进程可重新建立业务会话。 */
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
      change.prepare(source.context(this), factory);
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
    handover = new PlaybackHandover(this, recovery.context(this), factory, listener);
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
        HostDiagnostics.log(Log.WARN, "无障碍宿主", "释放播放连接失败", failure);
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
    try {
      value.retiredSession.close();
    } catch (Throwable failure) {
      value.closeFailed = true;
      HostDiagnostics.log(Log.WARN, "无障碍宿主", "关闭播放业务失败，延后后续热更", failure);
    }
    retired.add(value);
    if (!draining) {
      draining = true;
      main.post(this::drainRetired);
    }
  }

  private void drainRetired() {
    for (var iterator = retired.iterator(); iterator.hasNext(); ) {
      Binding value = iterator.next();
      if (value.closeFailed || value.gestures != 0 || value.captures != 0) continue;
      try {
        if (!value.retiredSession.released()) continue;
        value.retiredSession = null;
        iterator.remove();
      } catch (Throwable failure) {
        value.closeFailed = true;
        HostDiagnostics.log(Log.WARN, "无障碍宿主", "检查退役业务失败，延后后续热更", failure);
      }
    }
    // 关闭已明确失败的记录保留为阻断证据，不每 250ms 永久空转。
    draining = retired.stream().anyMatch(value -> !value.closeFailed);
    if (draining) main.postDelayed(this::drainRetired, 250);
  }

  final class Binding implements AccessibilityBinding, PlaybackPort {
    NativePlaybackSession session;
    NativePlaybackSession retiredSession;
    boolean enabled, closeFailed, used;
    volatile long activationEpoch;
    private int gestures;
    private int captures;

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
      if (Looper.myLooper() == Looper.getMainLooper())
        invoke(epoch, () -> session.command(action, data));
      else main.post(() -> invoke(epoch, () -> session.command(action, data)));
    }

    @Override
    public boolean gesture(GestureDescription description, GestureCallback callback) {
      if (!current() || Looper.myLooper() != Looper.getMainLooper()) return false;
      long epoch = activationEpoch;
      gestures++;
      boolean accepted = false;
      try {
        accepted =
            dispatchGesture(
                description,
                new GestureResultCallback() {
                  @Override
                  public void onCompleted(GestureDescription gesture) {
                    gestures--;
                    invoke(epoch, () -> callback.completed(true));
                  }

                  @Override
                  public void onCancelled(GestureDescription gesture) {
                    gestures--;
                    invoke(epoch, () -> callback.completed(false));
                  }
                },
                main);
        return accepted;
      } finally {
        if (!accepted) gestures--;
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
      captures++;
      try {
        Api30.capture(NativeAccessibilityService.this, displayId, this, activationEpoch, callback);
      } catch (Throwable failure) {
        captures--;
        throw failure;
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
            if (!current(epoch) || used == playing) return;
            used = playing;
            playbackUsageChanged();
          };
      if (Looper.myLooper() == Looper.getMainLooper()) report.run();
      else main.post(report);
    }
  }

  /** 旧系统不解析 Android 11 才存在的截图回调类型。 */
  private static final class Api30 {
    static void capture(
        NativeAccessibilityService service,
        int displayId,
        Binding owner,
        long epoch,
        AccessibilityBinding.ScreenshotCallback callback) {
      service.takeScreenshot(
          displayId,
          service.getMainExecutor(),
          new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
              owner.captures--;
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
                service.failed(owner, failure);
              }
            }

            @Override
            public void onFailure(int code) {
              owner.captures--;
              owner.invoke(epoch, () -> callback.failure(code));
            }
          });
    }
  }
}
