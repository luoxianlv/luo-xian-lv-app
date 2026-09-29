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
  private final Handler main = new Handler(Looper.getMainLooper());
  private volatile Binding binding;
  private AutoCloseable registration;
  private AutoCloseable preparation;
  private boolean connected;

  protected abstract NativePlaybackSession createPlaybackSession();

  protected abstract void foregroundRequested(boolean enabled);

  protected android.content.Context playbackContext() {
    return this;
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
    Binding candidate = new Binding();
    binding = candidate;
    try {
      candidate.session = createPlaybackSession();
      candidate.session.connect(playbackContext(), candidate);
      if (binding == candidate) registration = PlaybackBridge.connect(candidate);
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
    if (binding == owner) closeSession();
  }

  private void closeSession() {
    Binding previous = binding;
    boolean ownsForeground =
        previous != null
            && (PlaybackBridge.current() == previous || PlaybackBridge.current() == null);
    binding = null;
    if (registration != null) {
      try {
        registration.close();
      } catch (Exception failure) {
        HostDiagnostics.log(Log.WARN, "无障碍宿主", "释放播放连接失败", failure);
      }
      registration = null;
    }
    if (previous != null && previous.session != null) {
      NativePlaybackSession retired = previous.session;
      previous.session = null;
      try {
        retired.close();
      } catch (Throwable failure) {
        HostDiagnostics.log(Log.WARN, "无障碍宿主", "关闭播放业务失败", failure);
      }
    }
    if (ownsForeground) foregroundRequested(false);
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

  private final class Binding implements AccessibilityBinding, PlaybackPort {
    private NativePlaybackSession session;
    private int gestures;
    private int captures;

    @Override
    public boolean current() {
      return binding == this;
    }

    void invoke(Runnable action) {
      if (!current() || session == null) return;
      try {
        action.run();
      } catch (Throwable failure) {
        failed(this, failure);
      }
    }

    @Override
    public Bundle query(String kind) {
      if (Looper.myLooper() == Looper.getMainLooper()) return queryOnMain(kind);
      FutureTask<Bundle> task = new FutureTask<>(() -> queryOnMain(kind));
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

    private Bundle queryOnMain(String kind) {
      if (!current()) return new Bundle();
      try {
        return PlaybackValues.copy(session.query(kind));
      } catch (Throwable failure) {
        failed(this, failure);
        return new Bundle();
      }
    }

    @Override
    public void command(String action, Bundle arguments) {
      Bundle data = PlaybackValues.copy(arguments);
      if (Looper.myLooper() == Looper.getMainLooper()) invoke(() -> session.command(action, data));
      else main.post(() -> invoke(() -> session.command(action, data)));
    }

    @Override
    public boolean gesture(GestureDescription description, GestureCallback callback) {
      if (!current() || Looper.myLooper() != Looper.getMainLooper()) return false;
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
                    invoke(() -> callback.completed(true));
                  }

                  @Override
                  public void onCancelled(GestureDescription gesture) {
                    gestures--;
                    invoke(() -> callback.completed(false));
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
        Api30.capture(NativeAccessibilityService.this, displayId, this, callback);
      } catch (Throwable failure) {
        captures--;
        throw failure;
      }
    }

    @Override
    public void foreground(boolean enabled) {
      if (Looper.myLooper() == Looper.getMainLooper()) invoke(() -> foregroundRequested(enabled));
      else main.post(() -> invoke(() -> foregroundRequested(enabled)));
    }
  }

  /** 旧系统不解析 Android 11 才存在的截图回调类型。 */
  private static final class Api30 {
    static void capture(
        NativeAccessibilityService service,
        int displayId,
        Binding owner,
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
              if (!owner.current()) {
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
              owner.invoke(() -> callback.failure(code));
            }
          });
    }
  }
}
