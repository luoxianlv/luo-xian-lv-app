package app.luoxianlv.hot;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import app.luoxianlv.hot.contract.NativePage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** 同窗口替换页面：旧页保留到新页首帧与日志提交，试运行期间可原位恢复。 */
public final class PageSwapHost extends FrameLayout implements AutoCloseable {
  public interface Listener {
    void event(String code, Throwable error);
  }

  private final ActivationController controller;
  private final Executor worker;
  private final NativePage.Events events;
  private final Listener listener;
  private final Handler main = new Handler(Looper.getMainLooper());
  private Bundle hostState = new Bundle();
  private Slot active, candidate, previous;
  private int lifecycle = NativePage.CREATED, pointers;
  private long inputEpoch;
  private boolean closed, blocked, settling, externallyBusy;

  private static final class Slot {
    NativePage page;
    View view;
    Context context;
    NativeLoader.Prepared prepared;
    ActivationController.Ticket ticket;
    long epoch;
    int width, height, pins;
    boolean frameObserved, ready, disposed, disposing;
    Runnable timeout;
    ViewTreeObserver.OnDrawListener draw;
  }

  public PageSwapHost(
      Context context,
      ActivationController controller,
      Executor worker,
      NativePage.Events events,
      Listener listener) {
    super(context);
    this.controller = controller;
    this.worker = worker;
    this.events = events;
    this.listener = listener;
    setSaveFromParentEnabled(false);
  }

  /** 首次页面必须来自已验证稳定组合或 APK 恢复入口。 */
  public void initial(NativePage page, Context pageContext, Bundle state) {
    requireMain();
    StrictJson.require(active == null && !closed, "初始页面已安装或宿主已关闭");
    Slot slot = new Slot();
    slot.page = page;
    slot.context = pageContext;
    active = slot;
    try {
      slot.view =
          page.create(
              pageContext,
              PageState.copy(state),
              PageState.copy(hostState),
              (event, payload) -> dispatch(slot, event, payload),
              () -> {});
      addView(slot.view, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
      page.lifecycle(lifecycle);
    } catch (RuntimeException | Error failure) {
      dispose(slot);
      active = null;
      throw failure;
    }
  }

  /** 返回 false 时保留磁盘候选；不会为了新版丢弃仍被演奏任务租用的旧代际。 */
  public boolean offer(NativeLoader.Prepared prepared, ActivationController.Ticket ticket) {
    requireMain();
    if (closed || blocked || settling || active == null || candidate != null || previous != null)
      return false;
    StrictJson.require(
        controller.valid(ticket)
            && prepared.manifest.snapshotId.equals(ticket.snapshot.manifest.snapshotId),
        "候选不属于本次激活");
    candidate = new Slot();
    candidate.prepared = prepared;
    candidate.ticket = ticket;
    Slot slot = candidate;
    main.post(() -> prepare(slot));
    return true;
  }

  public void lifecycle(int state) {
    requireMain();
    StrictJson.require(state >= NativePage.CREATED && state <= NativePage.RESUMED, "页面生命周期无效");
    lifecycle = state;
    if (active != null) active.page.lifecycle(state);
    if (candidate != null && candidate.page != null)
      candidate.page.lifecycle(Math.min(NativePage.STARTED, state));
    if (active != null && active.ticket != null)
      controller.setActive(active.ticket, state == NativePage.RESUMED);
    // 离开前台不留一个拿着短期许可的半成品，回来按新决定重试。
    if (state < NativePage.RESUMED && candidate != null)
      abort(candidate, "candidate_background", null, false);
  }

  public void updateHostState(Bundle state) {
    requireMain();
    hostState = PageState.copy(state);
    inputEpoch++;
    if (active != null) active.page.updateHostState(PageState.copy(hostState));
  }

  /** 转屏、路由事务、手势执行等安全点由宿主统一报告；结束后待更新会自动再试。 */
  public void setBusy(boolean value) {
    requireMain();
    externallyBusy = value;
    if (value) inputEpoch++;
  }

  public Bundle save() {
    requireMain();
    return active == null ? new Bundle() : PageState.copy(active.page.save());
  }

  /** 正在演奏的任务固定租用创建时的代际，租约可在任意线程结束。 */
  public AutoCloseable pinActive() {
    requireMain();
    StrictJson.require(active != null && !closed, "没有可租用的页面代际");
    Slot slot = active;
    slot.pins++;
    AtomicBoolean released = new AtomicBoolean();
    return () -> {
      if (released.compareAndSet(false, true))
        main.post(
            () -> {
              slot.pins--;
              if (closed && slot.pins == 0) dispose(slot);
              else if (slot == previous && !settling && (active == null || active.ticket == null))
                retirePrevious();
            });
    };
  }

  private void prepare(Slot slot) {
    if (!pending(slot)) return;
    if (!controller.valid(slot.ticket)) {
      abort(slot, "candidate_superseded", null, false);
      return;
    }
    if (!safe()) {
      main.postDelayed(() -> prepare(slot), 32);
      return;
    }
    final Bundle state;
    try {
      state = PageState.copy(active.page.save());
    } catch (Throwable failure) {
      abort(slot, "state_export_failed", failure, false);
      return;
    }
    slot.epoch = inputEpoch;
    slot.width = getWidth();
    slot.height = getHeight();
    try {
      boolean made =
          controller.prepareView(
              slot.ticket,
              () -> {
                try {
                  slot.context = slot.prepared.context(getContext());
                  slot.page = slot.prepared.instantiate();
                  slot.view =
                      slot.page.create(
                          slot.context,
                          state,
                          PageState.copy(hostState),
                          (event, payload) -> dispatch(slot, event, payload),
                          () -> main.post(() -> ready(slot)));
                  addView(
                      slot.view,
                      0,
                      new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
                  slot.view.setImportantForAccessibility(
                      View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                  slot.page.lifecycle(NativePage.STARTED);
                } catch (Exception error) {
                  throw new CandidateFailure(error);
                }
              });
      if (!made) {
        postOnAnimation(() -> prepare(slot));
        return;
      }
      slot.timeout =
          () -> {
            if (pending(slot) && !slot.frameObserved)
              abort(slot, "candidate_frame_timeout", null, false);
          };
      main.postDelayed(slot.timeout, 30000);
    } catch (Throwable failure) {
      abort(
          slot,
          "candidate_create_failed",
          failure,
          failure instanceof CandidateFailure || failure instanceof LinkageError);
    }
  }

  private void ready(Slot slot) {
    if (!pending(slot) || slot.ready) return;
    slot.ready = true;
    ViewTreeObserver.OnDrawListener draw =
        new ViewTreeObserver.OnDrawListener() {
          boolean sent;

          @Override
          public void onDraw() {
            if (sent) return;
            sent = true;
            Runnable submitted = () -> main.post(() -> frame(slot));
            if (Build.VERSION.SDK_INT >= 29 && isHardwareAccelerated())
              slot.view.getViewTreeObserver().registerFrameCommitCallback(submitted);
            else main.post(submitted);
            main.post(
                () -> {
                  if (slot.view != null && slot.view.getViewTreeObserver().isAlive())
                    slot.view.getViewTreeObserver().removeOnDrawListener(this);
                });
          }
        };
    slot.draw = draw;
    slot.view.getViewTreeObserver().addOnDrawListener(draw);
    slot.view.invalidate();
  }

  private void frame(Slot slot) {
    if (!pending(slot) || slot.frameObserved) return;
    slot.frameObserved = true;
    if (slot.timeout != null) main.removeCallbacks(slot.timeout);
    if (!unchanged(slot)) {
      abort(slot, "candidate_input_changed", null, false);
      return;
    }
    worker.execute(
        () -> {
          try {
            controller.firstFrame(slot.ticket);
            main.post(() -> commit(slot));
          } catch (Exception failure) {
            main.post(() -> abort(slot, "candidate_commit_rejected", failure, false));
          }
        });
  }

  private void commit(Slot slot) {
    if (!pending(slot)) return;
    if (!unchanged(slot)) {
      abort(slot, "candidate_input_changed", null, false);
      return;
    }
    Slot old = active;
    try {
      boolean swapped =
          controller.expose(
              slot.ticket,
              () -> {
                slot.page.lifecycle(lifecycle);
                old.page.lifecycle(NativePage.CREATED);
                removeView(old.view);
                slot.view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
                active = slot;
                previous = old;
                candidate = null;
              });
      if (!swapped) {
        postOnAnimation(() -> commit(slot));
        return;
      }
      controller.setActive(slot.ticket, lifecycle == NativePage.RESUMED);
      listener.event("candidate_exposed", null);
      main.postDelayed(() -> observe(slot), 1000);
    } catch (Throwable failure) {
      if (active == slot) {
        active = old;
        previous = null;
        candidate = slot;
      }
      if (old.view.getParent() == null) addView(old.view);
      try {
        old.page.lifecycle(lifecycle);
      } catch (Throwable restoreFailure) {
        blocked = true;
      }
      abort(slot, "candidate_swap_failed", failure, true);
    }
  }

  private void observe(Slot slot) {
    if (closed || active != slot || slot.ticket == null || settling) return;
    worker.execute(
        () -> {
          try {
            boolean healthy = controller.healthy(slot.ticket);
            main.post(
                () -> {
                  if (closed || active != slot || settling) return;
                  if (healthy) {
                    slot.ticket = null;
                    retirePrevious();
                    listener.event("candidate_stable", null);
                  } else main.postDelayed(() -> observe(slot), 1000);
                });
          } catch (Exception failure) {
            main.post(() -> recover("candidate_observation_failed", failure, false));
          }
        });
  }

  /** 仅属于活动试运行模块的已捕获错误调用此入口；进程级崩溃由下次启动恢复。 */
  public void failActive(Throwable failure) {
    requireMain();
    recover("candidate_failed", failure, true);
  }

  private void recover(String code, Throwable failure, boolean confirmed) {
    if (closed || settling || active == null || active.ticket == null || previous == null) return;
    Slot broken = active;
    active = previous;
    previous = null;
    if (active.view.getParent() == null) addView(active.view);
    active.page.lifecycle(lifecycle);
    candidate = broken;
    abort(broken, code, failure, confirmed);
  }

  private void abort(Slot slot, String code, Throwable failure, boolean confirmed) {
    if (candidate != slot || settling) return;
    candidate = null;
    settling = true;
    dispose(slot);
    worker.execute(
        () -> {
          Throwable error = failure;
          boolean persisted = true;
          try {
            controller.abort(slot.ticket, confirmed);
          } catch (Exception persistenceFailure) {
            error = persistenceFailure;
            persisted = false;
          }
          Throwable reported = error;
          boolean safeToContinue = persisted;
          main.post(
              () -> {
                settling = false;
                if (!safeToContinue) blocked = true;
                if (!closed) listener.event(code, reported);
              });
        });
  }

  private void retirePrevious() {
    if (previous != null && previous.pins == 0) {
      dispose(previous);
      previous = null;
    }
  }

  private void dispose(Slot slot) {
    if (slot == null || slot.disposed) return;
    if (slot.timeout != null) main.removeCallbacks(slot.timeout);
    if (slot.view != null && slot.draw != null && slot.view.getViewTreeObserver().isAlive())
      slot.view.getViewTreeObserver().removeOnDrawListener(slot.draw);
    if (slot.pins > 0) {
      if (slot.view != null) removeView(slot.view);
      slot.disposing = true;
      // 故障代际若仍被任务租用，也计入两个代际上限，等待调用方结束任务。
      if (slot != active && previous == null && !closed) previous = slot;
      return;
    }
    slot.disposed = true;
    if (slot.view != null) removeView(slot.view);
    try {
      if (slot.page != null) slot.page.close();
    } catch (Throwable failure) {
      blocked = true;
      listener.event("generation_close_failed", failure);
    }
    slot.page = null;
    slot.view = null;
    slot.prepared = null;
    slot.context = null;
    slot.draw = null;
    slot.timeout = null;
  }

  private boolean pending(Slot slot) {
    return !closed && candidate == slot && !settling;
  }

  private boolean safe() {
    return !closed
        && lifecycle == NativePage.RESUMED
        && hasWindowFocus()
        && isLaidOut()
        && !isLayoutRequested()
        && pointers == 0
        && !externallyBusy
        && active.page.canReplace();
  }

  private boolean unchanged(Slot slot) {
    return slot.epoch == inputEpoch
        && slot.width == getWidth()
        && slot.height == getHeight()
        && safe();
  }

  private void dispatch(Slot slot, String event, Bundle payload) {
    requireMain();
    if (slot != active || closed || lifecycle != NativePage.RESUMED) return;
    inputEpoch++;
    events.emit(event, PageState.copy(payload));
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    inputEpoch++;
    int action = event.getActionMasked();
    pointers =
        action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
            ? 0
            : event.getPointerCount();
    if (action == MotionEvent.ACTION_POINTER_UP) pointers--;
    return super.dispatchTouchEvent(event);
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    inputEpoch++;
    return super.dispatchKeyEvent(event);
  }

  @Override
  public boolean dispatchGenericMotionEvent(MotionEvent event) {
    inputEpoch++;
    return super.dispatchGenericMotionEvent(event);
  }

  @Override
  public void close() {
    requireMain();
    if (closed) return;
    closed = true;
    main.removeCallbacksAndMessages(null);
    Slot pending = candidate != null ? candidate : active;
    if (pending != null && pending.ticket != null)
      worker.execute(
          () -> {
            try {
              controller.abort(pending.ticket, false);
            } catch (Exception ignored) {
              /* 持久化试运行记录留给下次启动恢复。 */
            }
          });
    dispose(candidate);
    dispose(previous);
    dispose(active);
    candidate = previous = active = null;
  }

  private static void requireMain() {
    StrictJson.require(Looper.myLooper() == Looper.getMainLooper(), "页面切换必须在主线程");
  }

  private static final class CandidateFailure extends RuntimeException {
    CandidateFailure(Throwable cause) {
      super(cause);
    }
  }
}
