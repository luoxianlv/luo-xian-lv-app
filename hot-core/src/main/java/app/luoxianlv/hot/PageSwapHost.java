package app.luoxianlv.hot;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import app.luoxianlv.hot.contract.HostActions;
import app.luoxianlv.hot.contract.NativePage;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** 同窗口替换页面：旧页保留到新页首帧与日志提交，试运行期间可原位恢复。 */
public final class PageSwapHost extends FrameLayout implements AutoCloseable {
  public interface Listener {
    void event(String code, Throwable error);
  }

  private ActivationController controller;
  private final Executor worker;
  private final NativePage.Events events;
  private final Listener listener;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Handler releases = new Handler(Looper.getMainLooper());
  private Bundle hostState = new Bundle();
  private Slot active, candidate, previous;
  private int lifecycle = NativePage.CREATED, pointers;
  private long inputEpoch;
  private boolean closed, blocked, settling, externallyBusy, transitioning;
  private boolean groupInput = true;
  private HostActions platform;

  private static final class Slot {
    String generation = UUID.randomUUID().toString().replace("-", "");
    String identity;
    NativePage page;
    View view;
    PageContainer container;
    Context context;
    NativeLoader.Prepared prepared;
    PageTarget target;
    Change change;
    ActivationController.Ticket ticket;
    long epoch;
    int width, height, pins;
    boolean frameObserved, ready, disposed, disposing;
    Runnable timeout;
    Runnable committed;
    ViewTreeObserver.OnDrawListener draw;
  }

  /** 组件只预绘制和报告状态；整组协调器决定何时提交、恢复与释放。 */
  public interface ChangeListener {
    void ready(Change change);

    void failed(Change change, String code, Throwable failure, boolean contentFailure);
  }

  public final class Change {
    private final Slot slot;
    private final Slot original;
    private final ChangeListener callback;
    private boolean reported, ended, committed;
    private Bundle restoreState;

    private Change(Slot slot, ChangeListener callback) {
      this.slot = slot;
      this.original = active;
      this.callback = callback;
    }

    public boolean valid() {
      requireMain();
      return !ended
          && !reported
          && pending(slot)
          && slot.frameObserved
          && (restoreState != null || unchanged(slot));
    }

    public boolean commit() {
      requireMain();
      if (!valid()) return false;
      committed = true;
      try {
        swap(slot);
        slot.page.lifecycle(lifecycle);
        return true;
      } catch (Throwable failure) {
        // 新页接管之前的错误来自旧页停用，不能据此隔离候选包。
        fail("candidate_commit_failed", failure, active == slot);
        throw failure;
      }
    }

    /** 新内容发生错误时，由调用方恢复所有组件；这里不单独写成功/失败日志。 */
    public void rollback(NativePage.Ready completion) {
      requireMain();
      if (ended) {
        completion.ready();
        return;
      }
      ended = true;
      if (closed) {
        completion.ready();
        return;
      }
      if (!committed) {
        candidate = null;
        dispose(slot);
        completion.ready();
        return;
      }
      Bundle latest = null;
      Throwable exportFailure = null;
      try {
        latest = PageState.copy(slot.page.save());
      } catch (Throwable failure) {
        exportFailure = failure;
      }
      active = original;
      previous = null;
      candidate = null;
      dispose(slot);
      if (original.container.getParent() == null) addView(original.container);
      if (latest == null || original.target == null) {
        original.container.input(true);
        original.page.lifecycle(lifecycle);
        if (exportFailure == null) completion.ready();
        else completion.failed(exportFailure);
        return;
      }
      original.container.input(false);
      Slot restored = new Slot();
      restored.target = original.target;
      restored.identity = original.identity;
      restored.change =
          new Change(
              restored,
              new ChangeListener() {
                @Override
                public void ready(Change change) {
                  boolean activated = false;
                  try {
                    if (!change.commit()) throw new IllegalStateException("旧页面恢复已失效");
                    activated = true;
                    change.finish();
                    completion.ready();
                  } catch (Throwable failure) {
                    // 恢复页已接管后，收尾失败不能再次使用已经关闭的原始页面。
                    if (activated) completion.failed(failure);
                    else failed(change, "restore_failed", failure, false);
                  }
                }

                @Override
                public void failed(
                    Change change, String code, Throwable failure, boolean confirmed) {
                  change.ended = true;
                  candidate = null;
                  active = original;
                  previous = null;
                  dispose(restored);
                  original.container.input(true);
                  if (original.container.getParent() == null && !closed)
                    addView(original.container);
                  try {
                    if (!closed) original.page.lifecycle(lifecycle);
                  } catch (Throwable fallback) {
                    if (failure != null) failure.addSuppressed(fallback);
                    else failure = fallback;
                  }
                  completion.failed(
                      failure == null ? new IllegalStateException("页面恢复中断：" + code) : failure);
                }
              });
      restored.change.restoreState = latest;
      candidate = restored;
      main.post(() -> prepare(restored));
    }

    public void finish() {
      requireMain();
      StrictJson.require(!ended && committed && active == slot && !reported, "页面交接尚未提交或已失效");
      ended = true;
      slot.change = null;
      retirePrevious();
      StrictJson.require(!blocked, "旧页面释放失败，禁止继续交接");
    }

    private void fail(String code, Throwable failure, boolean contentFailure) {
      if (reported || ended) return;
      reported = true;
      callback.failed(this, code, failure, contentFailure);
    }
  }

  public void initialTarget(PageTarget target) {
    requireMain();
    StrictJson.require(
        active != null && candidate == null && previous == null && active.target == null,
        "初始路由只能在页面首次创建后登记");
    StrictJson.require(target.identity().equals(active.identity), "初始路由与页面内容不一致");
    active.target = target;
  }

  public Change stage(PageTarget target, ChangeListener callback) {
    requireMain();
    if (closed
        || blocked
        || settling
        || active == null
        || active.target == null
        || candidate != null
        || previous != null
        || !safeForGroup()) return null;
    Slot slot = new Slot();
    slot.target = target;
    slot.identity = target.identity();
    slot.change = new Change(slot, callback);
    candidate = slot;
    // 整组调用方持有准备许可的同步边界；不能排队后在过期许可下才开始执行构造器。
    prepare(slot);
    return slot.change;
  }

  public boolean canStage() {
    requireMain();
    return !closed
        && !blocked
        && !settling
        && active != null
        && active.target != null
        && candidate == null
        && previous == null
        && safeForGroup();
  }

  void groupInput(boolean allowed) {
    requireMain();
    groupInput = allowed;
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

  /** 磁盘验证和更新协调器可晚于首屏准备；绑定本身不执行任何候选代码。 */
  public void bindController(ActivationController controller) {
    requireMain();
    StrictJson.require(
        !closed && controller != null && (this.controller == null || this.controller == controller),
        "页面更新控制器不能中途更换");
    this.controller = controller;
  }

  public void attachHost(HostActions platform) {
    requireMain();
    StrictJson.require(
        !closed && active == null && this.platform == null && platform != null, "系统入口只能在创建页面前绑定一次");
    this.platform = platform;
  }

  /** 系统结果身份随同一内容的 Activity 重建恢复，不能跨 APK/快照回退继承。 */
  public void initialSession(
      NativePage page, Context context, Bundle saved, String identity, NativePage.Ready ready) {
    requireMain();
    StrictJson.require(
        identity != null && !identity.isEmpty() && identity.length() <= 1024, "页面内容身份无效");
    Bundle state = saved;
    String generation = null;
    if (saved.containsKey("session.version")) {
      StrictJson.require(saved.getInt("session.version") == 1, "页面会话版本不支持");
      if (identity.equals(saved.getString("session.identity"))) {
        generation = saved.getString("session.generation");
        StrictJson.require(generation != null && generation.matches("[0-9a-f]{32}"), "页面会话身份无效");
        state = saved.getBundle("session.page");
        StrictJson.require(state != null, "页面会话状态缺失");
      } else state = new Bundle();
    }
    initial(page, context, state, ready, identity, generation);
  }

  /** 首次页面必须来自已验证稳定组合或 APK 恢复入口。 */
  public void initial(NativePage page, Context pageContext, Bundle state) {
    initial(page, pageContext, state, () -> {});
  }

  public void initial(
      NativePage page, Context pageContext, Bundle state, NativePage.Ready initialReady) {
    initial(page, pageContext, state, initialReady, "baseline", null);
  }

  private void initial(
      NativePage page,
      Context pageContext,
      Bundle state,
      NativePage.Ready initialReady,
      String identity,
      String generation) {
    requireMain();
    StrictJson.require(active == null && !closed, "初始页面已安装或宿主已关闭");
    Slot slot = new Slot();
    slot.page = page;
    slot.context = pageContext;
    slot.identity = identity;
    if (generation != null) slot.generation = generation;
    active = slot;
    try {
      page.attachHost(actions(slot));
      slot.view =
          page.create(
              pageContext,
              PageState.copy(state),
              PageState.copy(hostState),
              (event, payload) -> dispatch(slot, event, payload),
              readiness(slot, initialReady));
      slot.container = new PageContainer(getContext(), slot.view, true);
      addView(
          slot.container, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
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
    StrictJson.require(controller != null, "页面更新控制器尚未准备好");
    // 显式声明下次启动的模块不能被上层误送进即时页面替换通道。
    if (!prepared.manifest.activation.equals("live")) return false;
    if (closed || blocked || settling || active == null || candidate != null || previous != null)
      return false;
    StrictJson.require(
        controller.valid(ticket)
            && prepared.manifest.snapshotId.equals(ticket.snapshot.manifest.snapshotId),
        "候选不属于本次激活");
    candidate = new Slot();
    candidate.prepared = prepared;
    candidate.ticket = ticket;
    candidate.identity = prepared.manifest.snapshotId;
    Slot slot = candidate;
    main.post(() -> prepare(slot));
    return true;
  }

  public void lifecycle(int state) {
    requireMain();
    StrictJson.require(state >= NativePage.CREATED && state <= NativePage.RESUMED, "页面生命周期无效");
    if (lifecycle != state) inputEpoch++;
    lifecycle = state;
    withActive(() -> active.page.lifecycle(state));
    if (candidate != null && candidate.page != null) {
      try {
        candidate.page.lifecycle(Math.min(NativePage.STARTED, state));
      } catch (Throwable failure) {
        abort(candidate, "candidate_lifecycle_failed", failure, true);
      }
    }
    if (active != null && active.ticket != null)
      controller.setActive(active.ticket, state == NativePage.RESUMED);
    // 离开前台不留一个拿着短期许可的半成品，回来按新决定重试。
    if (state < NativePage.RESUMED && candidate != null && candidate.change == null)
      abort(candidate, "candidate_background", null, false);
  }

  public void updateHostState(Bundle state) {
    requireMain();
    hostState = PageState.copy(state);
    inputEpoch++;
    withActive(() -> active.page.updateHostState(PageState.copy(hostState)));
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

  public Bundle saveSession() {
    requireMain();
    StrictJson.require(active != null && !closed, "页面会话已关闭");
    Bundle state = new Bundle();
    state.putInt("session.version", 1);
    state.putString("session.identity", active.identity);
    state.putString("session.generation", active.generation);
    state.putBundle("session.page", save());
    return PageState.copy(state);
  }

  public NativePage.Retained retain() {
    requireMain();
    if (active == null || closed) return null;
    NativePage.Retained value = active.page.retain();
    return value == null ? null : new RetainedPage(active.identity, active.page.getClass(), value);
  }

  public void newIntent(Intent intent) {
    requireMain();
    inputEpoch++;
    withActive(() -> active.page.newIntent(intent));
  }

  public boolean back() {
    requireMain();
    inputEpoch++;
    boolean[] handled = {false};
    boolean survived = withActive(() -> handled[0] = active.page.back());
    return !survived || handled[0];
  }

  public void configurationChanged(Configuration configuration) {
    requireMain();
    inputEpoch++;
    withActive(() -> active.page.configurationChanged(configuration));
  }

  public void windowTouch() {
    requireMain();
    withActive(() -> active.page.windowTouch());
  }

  public void hostWarning(String code, Throwable error) {
    requireMain();
    if (active != null && !closed) active.page.hostWarning(code, error);
  }

  public void finishing() {
    requireMain();
    withActive(() -> active.page.finishing());
  }

  /** 失效代际的迟到结果只消费，不交给同名但不同内容的旧版/新版回调。 */
  public boolean result(String key, int resultCode, Intent data) {
    requireMain();
    if (active == null || closed || !key.startsWith(prefix(active))) return true;
    inputEpoch++;
    String businessKey = key.substring(prefix(active).length());
    checkBusinessKey(businessKey);
    boolean[] consumed = {false};
    boolean survived =
        withActive(() -> consumed[0] = active.page.result(businessKey, resultCode, data));
    return !survived || consumed[0];
  }

  private boolean withActive(Runnable action) {
    if (active == null || closed) return true;
    try {
      action.run();
      return true;
    } catch (RuntimeException | Error failure) {
      if (active.change != null && previous != null) {
        active.change.fail("candidate_callback_failed", failure, true);
        return false;
      }
      if (active.ticket == null || previous == null || settling) throw failure;
      recover("candidate_callback_failed", failure, true);
      return false;
    }
  }

  private static String prefix(Slot slot) {
    return "g." + slot.generation + ".";
  }

  private static void checkBusinessKey(String key) {
    StrictJson.require(key != null && key.matches("[a-z][a-z0-9._-]{0,95}"), "系统结果业务键无效");
  }

  private HostActions actions(Slot slot) {
    return new HostActions() {
      private boolean allowed() {
        requireMain();
        return !closed
            && groupInput
            && !slot.disposed
            && slot == active
            && !settling
            && !transitioning
            && lifecycle == NativePage.RESUMED;
      }

      private HostActions platform() {
        StrictJson.require(platform != null, "页面没有系统操作入口");
        return platform;
      }

      @Override
      public void launch(String key, Intent intent, Bundle options) {
        checkBusinessKey(key);
        if (allowed()) {
          inputEpoch++;
          platform().launch(prefix(slot) + key, intent, options);
        }
      }

      @Override
      public void permissions(String key, String[] permissions) {
        checkBusinessKey(key);
        if (allowed()) {
          inputEpoch++;
          platform().permissions(prefix(slot) + key, permissions);
        }
      }

      @Override
      public void resultReady(String key) {
        requireMain();
        checkBusinessKey(key);
        if (!closed && slot == active && platform != null) platform.resultReady(prefix(slot) + key);
      }

      @Override
      public boolean hasPendingResults() {
        requireMain();
        return platform != null && platform.hasPendingResults();
      }

      @Override
      public boolean isCurrent() {
        requireMain();
        return !closed && !slot.disposed && slot == active && !transitioning;
      }

      @Override
      public void open(Intent intent, boolean closeCurrent) {
        if (allowed()) {
          inputEpoch++;
          platform().open(intent, closeCurrent);
        }
      }

      @Override
      public void closePage() {
        // 用户已经确认的退出可在后台完成，但候选和旧代际不能关闭当前窗口。
        if (isCurrent() && !settling) {
          inputEpoch++;
          platform().closePage();
        }
      }

      @Override
      public void finish() {
        if (allowed()) {
          inputEpoch++;
          platform().finish();
        }
      }
    };
  }

  private NativePage.Ready readiness(Slot slot, NativePage.Ready initial) {
    return new NativePage.Ready() {
      @Override
      public void ready() {
        main.post(
            () -> {
              if (pending(slot)) PageSwapHost.this.ready(slot);
              else if (!closed && active == slot && initial != null) initial.ready();
            });
      }

      @Override
      public void failed(Throwable failure) {
        main.post(
            () -> {
              if (pending(slot)) abort(slot, "candidate_prepare_failed", failure, true);
              else if (!closed && active == slot) {
                if (slot.change != null)
                  slot.change.fail("candidate_prepare_failed", failure, true);
                else if (slot.ticket != null && previous != null)
                  recover("candidate_prepare_failed", failure, true);
                else listener.event("baseline_recovery_failed", failure);
              }
            });
      }
    };
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
        releases.post(
            () -> {
              slot.pins--;
              if (closed && slot.pins == 0) dispose(slot);
              else if (slot == previous
                  && !settling
                  && (active == null || (active.ticket == null && active.change == null)))
                retirePrevious();
            });
    };
  }

  private void prepare(Slot slot) {
    if (!pending(slot)) return;
    if (slot.change == null && !controller.valid(slot.ticket)) {
      abort(slot, "candidate_superseded", null, false);
      return;
    }
    if ((slot.change == null || slot.change.restoreState == null)
        && !(slot.change == null ? safe() : safeForGroup())) {
      if (slot.change != null) {
        abort(slot, "candidate_input_changed", null, false);
        return;
      }
      main.postDelayed(() -> prepare(slot), 32);
      return;
    }
    final Bundle state;
    try {
      state =
          PageState.copy(
              slot.change != null && slot.change.restoreState != null
                  ? slot.change.restoreState
                  : active.page.save());
    } catch (Throwable failure) {
      abort(slot, "state_export_failed", failure, false);
      return;
    }
    slot.epoch = inputEpoch;
    slot.width = getWidth();
    slot.height = getHeight();
    try {
      Runnable create =
          () -> {
            try {
              slot.context =
                  slot.target == null
                      ? slot.prepared.context(getContext())
                      : slot.target.context(getContext());
              slot.page = slot.target == null ? slot.prepared.instantiate() : slot.target.create();
              slot.page.attachHost(actions(slot));
              slot.view =
                  slot.page.create(
                      slot.context,
                      state,
                      PageState.copy(hostState),
                      (event, payload) -> dispatch(slot, event, payload),
                      readiness(slot, null));
              slot.container = new PageContainer(getContext(), slot.view, false);
              addView(
                  slot.container,
                  0,
                  new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
              slot.page.lifecycle(NativePage.STARTED);
              if (slot.change != null && lifecycle < NativePage.RESUMED) {
                slot.container.measure(
                    View.MeasureSpec.makeMeasureSpec(getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(getHeight(), View.MeasureSpec.EXACTLY));
                slot.container.layout(0, 0, getWidth(), getHeight());
              }
            } catch (Exception error) {
              throw new CandidateFailure(error);
            }
          };
      boolean made;
      if (slot.change == null) made = controller.prepareView(slot.ticket, create);
      else {
        create.run();
        made = true;
      }
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
    // 不可见窗口没有 Surface 提交帧；只确认布局与业务准备，显示中的窗口仍必须等待真实提交。
    if (slot.change != null && lifecycle < NativePage.RESUMED) {
      frame(slot);
      return;
    }
    if (Build.VERSION.SDK_INT >= 29 && isHardwareAccelerated()) {
      // ViewRoot 在 onDraw 前截取提交回调；在 onDraw 内注册会错过静态页面唯一一次绘制。
      slot.committed = () -> main.post(() -> frame(slot));
      slot.view.getViewTreeObserver().registerFrameCommitCallback(slot.committed);
      slot.view.invalidate();
      invalidate();
      return;
    }
    ViewTreeObserver.OnDrawListener draw =
        new ViewTreeObserver.OnDrawListener() {
          boolean sent;

          @Override
          public void onDraw() {
            if (sent) return;
            sent = true;
            main.post(() -> frame(slot));
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
    invalidate();
  }

  private void frame(Slot slot) {
    if (!pending(slot) || slot.frameObserved) return;
    slot.frameObserved = true;
    if (slot.timeout != null) main.removeCallbacks(slot.timeout);
    if ((slot.change == null || slot.change.restoreState == null) && !unchanged(slot)) {
      abort(slot, "candidate_input_changed", null, false);
      return;
    }
    if (slot.change != null) {
      slot.change.callback.ready(slot.change);
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
      boolean swapped = controller.expose(slot.ticket, () -> swap(slot));
      if (!swapped) {
        postOnAnimation(() -> commit(slot));
        return;
      }
      // 只有事务确认新页成为活动页后才发 RESUMED，避免候选初始化提前启动系统操作。
      slot.page.lifecycle(lifecycle);
      controller.setActive(slot.ticket, lifecycle == NativePage.RESUMED);
      listener.event("candidate_exposed", null);
      main.postDelayed(() -> observe(slot), 1000);
    } catch (Throwable failure) {
      if (active == slot) {
        active = old;
        previous = null;
        candidate = slot;
      }
      old.container.input(true);
      if (old.container.getParent() == null) addView(old.container);
      try {
        old.page.lifecycle(lifecycle);
      } catch (Throwable restoreFailure) {
        blocked = true;
        abort(slot, "candidate_swap_failed", failure, true);
        listener.event("baseline_recovery_failed", restoreFailure);
        return;
      }
      abort(slot, "candidate_swap_failed", failure, true);
    }
  }

  private void swap(Slot slot) {
    Slot old = active;
    transitioning = true;
    try {
      old.container.input(false);
      old.page.lifecycle(NativePage.CREATED);
      removeView(old.container);
      slot.container.input(true);
      active = slot;
      previous = old;
      candidate = null;
    } finally {
      transitioning = false;
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
    if (active != null && active.change != null) {
      active.change.fail("candidate_failed", failure, true);
      return;
    }
    recover("candidate_failed", failure, true);
  }

  private void recover(String code, Throwable failure, boolean confirmed) {
    if (closed || settling || active == null || active.ticket == null || previous == null) return;
    Slot broken = active;
    active = previous;
    previous = null;
    candidate = broken;
    broken.container.input(false);
    active.container.input(true);
    if (active.container.getParent() == null) addView(active.container);
    try {
      active.page.lifecycle(lifecycle);
    } catch (Throwable restoreFailure) {
      blocked = true;
      abort(broken, code, failure, confirmed);
      listener.event("baseline_recovery_failed", restoreFailure);
      return;
    }
    abort(broken, code, failure, confirmed);
  }

  private void abort(Slot slot, String code, Throwable failure, boolean confirmed) {
    if (candidate != slot || settling) return;
    if (slot.change != null) {
      slot.change.fail(code, failure, confirmed);
      return;
    }
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
    if (Build.VERSION.SDK_INT >= 29
        && slot.view != null
        && slot.committed != null
        && slot.view.getViewTreeObserver().isAlive())
      slot.view.getViewTreeObserver().unregisterFrameCommitCallback(slot.committed);
    if (slot.view != null && slot.draw != null && slot.view.getViewTreeObserver().isAlive())
      slot.view.getViewTreeObserver().removeOnDrawListener(slot.draw);
    if (slot.pins > 0) {
      if (slot.container != null) {
        slot.container.input(false);
        removeView(slot.container);
      }
      slot.disposing = true;
      // 故障代际若仍被任务租用，也计入两个代际上限，等待调用方结束任务。
      if (slot != active && previous == null && !closed) previous = slot;
      return;
    }
    slot.disposed = true;
    if (slot.container != null) {
      slot.container.input(false);
      removeView(slot.container);
      slot.container.removeAllViews();
    }
    try {
      if (slot.page != null) slot.page.close();
    } catch (Throwable failure) {
      blocked = true;
      listener.event("generation_close_failed", failure);
    }
    slot.page = null;
    slot.view = null;
    slot.container = null;
    slot.prepared = null;
    slot.target = null;
    slot.context = null;
    slot.draw = null;
    slot.timeout = null;
    slot.committed = null;
  }

  private boolean pending(Slot slot) {
    return !closed && candidate == slot && !settling;
  }

  /** 仅含宿主阶段与门限，不包含用户状态或网络地址，供本地超时诊断。 */
  String diagnosticState() {
    requireMain();
    return "生命周期="
        + lifecycle
        + " 焦点="
        + hasWindowFocus()
        + " 待布局="
        + isLayoutRequested()
        + " 手指="
        + pointers
        + " 忙碌="
        + externallyBusy
        + " 收尾="
        + settling
        + " 阻断="
        + blocked
        + " 可替换="
        + (active != null && active.page.canReplace())
        + " 候选="
        + (candidate != null)
        + " 已创建="
        + (candidate != null && candidate.page != null)
        + " 已就绪="
        + (candidate != null && candidate.ready)
        + " 已绘制="
        + (candidate != null && candidate.frameObserved)
        + " 已关闭="
        + closed;
  }

  private boolean safe() {
    return !closed
        && lifecycle == NativePage.RESUMED
        && hasWindowFocus()
        && isLaidOut()
        && !isLayoutRequested()
        && pointers == 0
        && (findFocus() == null || !findFocus().onCheckIsTextEditor())
        && !externallyBusy
        && (platform == null || !platform.hasPendingResults())
        && active.page.canReplace();
  }

  private boolean unchanged(Slot slot) {
    return slot.epoch == inputEpoch
        && slot.width == getWidth()
        && slot.height == getHeight()
        && (slot.change == null ? safe() : safeForGroup());
  }

  private boolean safeForGroup() {
    if (lifecycle == NativePage.RESUMED) return safe();
    return !closed
        && isLaidOut()
        && getWidth() > 0
        && getHeight() > 0
        && pointers == 0
        && !externallyBusy
        && (platform == null || !platform.hasPendingResults())
        && active.page.canReplace();
  }

  private void dispatch(Slot slot, String event, Bundle payload) {
    requireMain();
    if (slot != active || closed || !groupInput || transitioning || lifecycle != NativePage.RESUMED)
      return;
    inputEpoch++;
    events.emit(event, PageState.copy(payload));
  }

  @Override
  public void requestChildFocus(View child, View focused) {
    inputEpoch++;
    super.requestChildFocus(child, focused);
  }

  @Override
  public void clearChildFocus(View child) {
    inputEpoch++;
    super.clearChildFocus(child);
  }

  /** 隐藏预绘制页有独立挂载层：禁止抢焦点、触摸穿透或靠根 View 的 Z 值盖住旧页。 */
  private static final class PageContainer extends FrameLayout {
    private boolean input;

    PageContainer(Context context, View content, boolean input) {
      super(context);
      setSaveFromParentEnabled(false);
      input(input);
      if (!input) content.clearFocus();
      addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void input(boolean enabled) {
      input = enabled;
      setDescendantFocusability(enabled ? FOCUS_AFTER_DESCENDANTS : FOCUS_BLOCK_DESCENDANTS);
      setImportantForAccessibility(
          enabled
              ? IMPORTANT_FOR_ACCESSIBILITY_AUTO
              : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
      return input && super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
      return input && super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
      return input && super.dispatchKeyEvent(event);
    }
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    if (!groupInput) return true;
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
    if (!groupInput) return true;
    inputEpoch++;
    return super.dispatchKeyEvent(event);
  }

  @Override
  public boolean dispatchGenericMotionEvent(MotionEvent event) {
    if (!groupInput) return true;
    inputEpoch++;
    return super.dispatchGenericMotionEvent(event);
  }

  @Override
  public void close() {
    requireMain();
    if (closed) return;
    closed = true;
    if (candidate != null && candidate.change != null)
      candidate.change.fail("page_closed", null, false);
    if (active != null && active.change != null) active.change.fail("page_closed", null, false);
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
