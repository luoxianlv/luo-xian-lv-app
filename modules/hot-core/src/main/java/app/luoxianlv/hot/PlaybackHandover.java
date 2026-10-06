package app.luoxianlv.hot;

import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import app.luoxianlv.hot.contract.NativePage;
import app.luoxianlv.hot.contract.NativePlaybackSession;
import app.luoxianlv.hot.contract.PlaybackValues;
import java.util.function.Supplier;

/** 无障碍身份不重连；候选只预热，提交和健康确认由整组激活事务控制。 */
public final class PlaybackHandover {
  public interface Listener {
    void ready(PlaybackHandover change);

    void failed(PlaybackHandover change, Throwable failure, boolean contentFailure);

    default void closed(PlaybackHandover change) {}
  }

  private final NativePlaybackHost owner;
  private final NativePlaybackHost.Binding previous;
  private NativePlaybackHost.Binding next;
  private final long revision;
  private final Listener listener;
  private boolean ready, committed, restoring, ended, reported;
  private boolean rejectedContent;
  private Runnable timeout;
  private NativePage.Ready recovery;
  private Context recoveryContext;
  private Supplier<NativePlaybackSession> recoveryFactory;

  PlaybackHandover(NativePlaybackHost owner, Listener listener) {
    this.owner = owner;
    this.previous = owner.binding;
    this.revision = previous.session.revision();
    this.listener = listener;
  }

  PlaybackHandover(
      NativePlaybackHost owner,
      Context recoveryContext,
      Supplier<NativePlaybackSession> recoveryFactory,
      Listener listener) {
    this.owner = owner;
    this.previous = owner.new Binding();
    this.next = owner.binding;
    this.revision = 0;
    this.listener = listener;
    this.recoveryContext = recoveryContext;
    this.recoveryFactory = recoveryFactory;
    committed = ready = true;
  }

  boolean observing() {
    return current()
        && committed
        && !reported
        && !restoring
        && owner.binding == next
        && next.enabled;
  }

  void prepare(Context context, Supplier<NativePlaybackSession> factory) {
    timeout = () -> reject(new java.util.concurrent.TimeoutException("播放候选准备超时"), false);
    owner.main.postDelayed(timeout, 30000);
    Bundle state;
    try {
      state = PlaybackValues.copy(previous.session.snapshot());
    } catch (Throwable failure) {
      reject(failure, false);
      return;
    }
    try {
      next = owner.new Binding();
      next.session = factory.get();
      StrictJson.require(next.session != null, "播放工厂没有返回候选会话");
      if (!next.session.supportsHandover())
        throw new UnsupportedOperationException("候选播放业务需下次启动生效");
      next.session.prepare(
          context,
          next,
          state,
          new NativePage.Ready() {
            @Override
            public void ready() {
              owner.main.post(
                  () -> {
                    if (!current() || reported || restoring || ready) return;
                    clearTimeout();
                    ready = true;
                    listener.ready(PlaybackHandover.this);
                  });
            }

            @Override
            public void failed(Throwable failure) {
              owner.main.post(() -> reject(failure, true));
            }
          });
    } catch (Throwable failure) {
      reject(failure, !(failure instanceof UnsupportedOperationException));
    }
  }

  /** 预热期间用户继续操作旧会话；哪怕操作已经结束，也不能提交旧快照。 */
  public boolean valid() {
    requireMain();
    return current()
        && ready
        && !reported
        && !committed
        && !restoring
        && owner.binding == previous
        && owner.playbackCanReplace()
        && previous.session.revision() == revision;
  }

  /** 调用者必须置于整组 expose 临界区；此方法不落盘，不提前确认健康。 */
  public boolean commit() {
    requireMain();
    if (!valid()) return false;
    committed = true;
    try {
      previous.session.deactivate();
    } catch (Throwable failure) {
      reject(failure, false);
      throw failure;
    }
    try {
      owner.publish(next);
      next.session.activate();
      return true;
    } catch (Throwable failure) {
      reject(failure, true);
      throw failure;
    }
  }

  /** 恢复试运行期间的最新选曲/进度；发生故障后保持暂停，不重新发出旧手势。 */
  public void rollback(NativePage.Ready completion) {
    requireMain();
    if (restoring) throw new IllegalStateException("播放回退正在执行");
    if (!current()) {
      completion.ready();
      return;
    }
    if (!committed) {
      cancel();
      completion.ready();
      return;
    }
    NativePage.Ready result = once(completion);
    recovery = result;
    restoring = true;
    timeout = () -> recoveryFailed(new java.util.concurrent.TimeoutException("旧播放业务恢复超时"), result);
    owner.main.postDelayed(timeout, 30000);
    Bundle state = null;
    if (next != null && next.session != null) {
      try {
        state = PlaybackValues.copy(next.session.snapshot());
      } catch (Throwable ignored) {
        /* 无法读取故障实例时，旧实现从当前持久化选曲恢复。 */
      }
    }
    owner.disablePlayback();
    try {
      if (next != null && next.session != null) next.session.deactivate();
      if (previous.session != null) previous.session.deactivate();
      owner.retire(next);
      NativePage.Ready prepared =
          new NativePage.Ready() {
            @Override
            public void ready() {
              owner.main.post(() -> resumePrevious(result));
            }

            @Override
            public void failed(Throwable failure) {
              owner.main.post(() -> recoveryFailed(failure, result));
            }
          };
      if (recoveryFactory != null) {
        previous.session = recoveryFactory.get();
        StrictJson.require(
            previous.session != null && previous.session.supportsHandover(), "旧播放工厂不支持恢复");
        previous.session.prepareRecovery(recoveryContext, previous, state, prepared);
      } else previous.session.restore(state, prepared);
    } catch (Throwable failure) {
      recoveryFailed(failure, result);
    }
  }

  private void resumePrevious(NativePage.Ready result) {
    if (!current()) {
      result.ready();
      return;
    }
    if (!owner.retiredInputIdle()) {
      owner.main.postDelayed(() -> resumePrevious(result), 16);
      return;
    }
    try {
      owner.publish(previous);
      previous.session.activate();
      finishRollback();
      result.ready();
    } catch (Throwable failure) {
      recoveryFailed(failure, result);
    }
  }

  private void finishRollback() {
    clearTimeout();
    recovery = null;
    ended = true;
    restoring = false;
    owner.handover = null;
    recoveryContext = null;
    recoveryFactory = null;
  }

  private void recoveryFailed(Throwable failure, NativePage.Ready completion) {
    if (current()) {
      owner.disablePlayback();
      owner.retire(next);
      owner.retire(previous);
      finishRollback();
      owner.foregroundRequested(false);
    }
    completion.failed(failure);
  }

  /** 整组健康确认后才释放旧会话；仍有后台工作时宿主继续保留退役记录。 */
  public void finish() {
    requireMain();
    StrictJson.require(
        current() && committed && !restoring && owner.binding == next && next.enabled,
        "播放交接尚未提交或已失败");
    ended = true;
    owner.handover = null;
    clearTimeout();
    owner.retire(previous);
  }

  boolean previousReleased() {
    requireMain();
    return previous.released();
  }

  boolean candidateReleased() {
    requireMain();
    return next == null || next.released();
  }

  public void cancel() {
    requireMain();
    if (!current()) return;
    StrictJson.require(!committed, "已经展示的播放会话必须通过回退恢复");
    ended = true;
    owner.handover = null;
    clearTimeout();
    owner.retire(next);
  }

  void disconnect() {
    ended = true;
    clearTimeout();
    owner.retire(previous);
    owner.retire(next);
    if (recovery != null) {
      recovery.ready();
      recovery = null;
    }
    recoveryContext = null;
    recoveryFactory = null;
    listener.closed(this);
  }

  boolean failed(NativePlaybackHost.Binding binding, Throwable failure) {
    if (!current() || (binding != next && binding != previous)) return false;
    if (binding == previous) {
      if (!committed) {
        reject(failure, false);
        cancel();
        // 旧业务本身出错也要通知整组事务；cancel 后不能依赖仍然 current 的候选回调。
        owner.main.post(() -> listener.failed(this, failure, false));
        return false;
      }
      return false;
    }
    reject(failure, true);
    return true;
  }

  private void reject(Throwable failure, boolean contentFailure) {
    if (!current() || reported || restoring) return;
    reported = true;
    rejectedContent = contentFailure;
    clearTimeout();
    ready = false;
    if (committed) {
      owner.disablePlayback();
      try {
        if (next != null && next.session != null) next.session.deactivate();
      } catch (Throwable stopping) {
        failure.addSuppressed(stopping);
      }
    } else owner.retire(next);
    owner.main.post(
        () -> {
          if (current()) listener.failed(this, failure, contentFailure);
        });
  }

  private boolean current() {
    return !ended && owner.connected && owner.handover == this;
  }

  boolean rejectedContent() {
    return reported && rejectedContent;
  }

  private void clearTimeout() {
    if (timeout != null) owner.main.removeCallbacks(timeout);
    timeout = null;
  }

  private static NativePage.Ready once(NativePage.Ready target) {
    var done = new java.util.concurrent.atomic.AtomicBoolean();
    return new NativePage.Ready() {
      @Override
      public void ready() {
        if (done.compareAndSet(false, true)) target.ready();
      }

      @Override
      public void failed(Throwable failure) {
        if (done.compareAndSet(false, true)) target.failed(failure);
      }
    };
  }

  private static void requireMain() {
    StrictJson.require(Looper.myLooper() == Looper.getMainLooper(), "播放交接必须在主线程执行");
  }
}
