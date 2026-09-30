package app.luoxianlv.hot;

import android.os.Handler;
import android.os.Looper;
import app.luoxianlv.hot.contract.BusinessFactory;
import app.luoxianlv.hot.contract.NativePage;
import java.util.List;
import java.util.concurrent.Executor;

/** 一个已获许可的完整业务更新：后台落盘，主线程整组提交，所有参与者共用观察与恢复。 */
public final class GroupActivation implements GroupHandover.Listener {
  public interface Environment {
    default boolean workSafe() {
      return true;
    }

    List<GroupHandover.Page> pages();

    NativeAccessibilityService playback();

    boolean inUse();

    GroupHandover.Publication publication(NativeLoader.Prepared prepared, BusinessFactory factory);

    NativeLoader.Prepared recoverySource();

    BusinessFactory recoveryFactory();
  }

  public enum Phase {
    WAITING,
    PREPARING,
    PERSISTING,
    EXPOSING,
    OBSERVING,
    FINALIZING,
    RESTORING,
    FINISHED
  }

  public enum Result {
    STABLE,
    CANCELLED,
    ROLLED_BACK,
    RECOVERY_FAILED,
    CLEANUP_FAILED
  }

  public interface Listener {
    default void exposed() {}

    void finished(Result result, Throwable failure);
  }

  private final Handler main = new Handler(Looper.getMainLooper());
  private final ActivationController controller;
  private final ActivationController.Ticket ticket;
  private final NativeLoader.Prepared prepared;
  private final Environment environment;
  private final Executor worker;
  private final Listener listener;
  private final Runnable pulse = this::observe;
  private Phase phase = Phase.WAITING;
  private GroupHandover group;
  private GroupHandover.Publication publication;
  private boolean checking, wasExposed, diskRestored, componentsRestored, recoveryFailed;
  private Throwable failure;
  private boolean confirmedFailure;
  private long retirementStarted;

  public boolean contentFailure() {
    requireMain();
    return confirmedFailure;
  }

  /** 只接受同一次签名许可的即时业务快照；运行时和显式 restart 候选由冷启动流程处理。 */
  public GroupActivation(
      ActivationController controller,
      ActivationController.Ticket ticket,
      NativeLoader.Prepared prepared,
      Environment environment,
      Executor worker,
      Listener listener) {
    requireMain();
    StrictJson.require(
        prepared.manifest != null
            && prepared.manifest.activation.equals("live")
            && prepared.manifest.snapshotId.equals(ticket.snapshot.manifest.snapshotId)
            && controller.valid(ticket),
        "整组候选与即时激活许可不一致");
    this.controller = controller;
    this.ticket = ticket;
    this.prepared = prepared;
    this.environment = environment;
    this.worker = worker;
    this.listener = listener;
    // 宿主先登记本次事务，后续窗口/服务的生命周期才不会漏报。
    main.post(this::prepare);
  }

  public Phase phase() {
    requireMain();
    return phase;
  }

  private void prepare() {
    if (phase != Phase.WAITING) return;
    try {
      boolean obtained =
          controller.prepareView(
              ticket,
              () -> {
                List<GroupHandover.Page> pages = environment.pages();
                NativeAccessibilityService playback = environment.playback();
                if (!environment.workSafe()
                    || (pages.isEmpty() && playback == null)
                    || pages.stream().anyMatch(page -> !page.host().canStage())
                    || (playback != null
                        && (!playback.playbackCanReplace() || playback.playbackRetiring()))) return;
                phase = Phase.PREPARING;
                final BusinessFactory factory;
                try {
                  factory = prepared.factory();
                } catch (Throwable error) {
                  stop(error, true);
                  return;
                }
                publication = environment.publication(prepared, factory);
                group =
                    GroupHandover.prepare(prepared, factory, pages, playback, publication, this);
                if (group == null) stop(new IllegalStateException("整组安全点已改变"), false);
              });
      if (!obtained || phase == Phase.WAITING) main.postDelayed(this::prepare, 125);
    } catch (Throwable error) {
      stop(error, false);
    }
  }

  @Override
  public void ready(GroupHandover value) {
    main.post(
        () -> {
          if (value != group || phase != Phase.PREPARING) return;
          phase = Phase.PERSISTING;
          execute(
              () -> {
                try {
                  controller.firstFrame(ticket);
                  main.post(this::expose);
                } catch (Throwable error) {
                  main.post(() -> stop(error, false));
                }
              });
        });
  }

  private void expose() {
    if (phase != Phase.PERSISTING && phase != Phase.EXPOSING) return;
    phase = Phase.EXPOSING;
    if (!group.valid()) {
      stop(new IllegalStateException("整组准备结果已失效"), false);
      return;
    }
    try {
      boolean exposed =
          controller.expose(
              ticket,
              () -> {
                if (!group.commit()) throw new IllegalStateException("组件在曝光边界变为忙碌");
              });
      if (!exposed) {
        main.postDelayed(this::expose, 16);
        return;
      }
      wasExposed = true;
      phase = Phase.OBSERVING;
      usageChanged();
      listener.exposed();
      main.post(pulse);
    } catch (Throwable error) {
      // 组件已向 Listener 报告明确归因；延后一条主线程消息，避免用泛化错误覆盖它。
      main.post(() -> stop(error, false));
    }
  }

  @Override
  public void failed(GroupHandover value, Throwable error, boolean contentFailure) {
    if (value == group) stop(error, contentFailure);
  }

  public void pageOpened(GroupHandover.Page page) {
    requireMain();
    if (group != null)
      group.pageOpened(
          page, environment.recoverySource().page(page.route(), environment.recoveryFactory()));
    usageChanged();
  }

  public void pageClosed() {
    requireMain();
    if (group != null) group.topologyChanged();
    usageChanged();
  }

  public void playbackOpened(NativeAccessibilityService service) {
    requireMain();
    if (group != null)
      group.playbackOpened(service, environment.recoverySource(), environment.recoveryFactory());
    usageChanged();
  }

  /** 生命周期事件立即停表；轮询只检查是否达标，不把整秒后台空档算成有效使用。 */
  public void usageChanged() {
    requireMain();
    controller.setActive(
        ticket, phase == Phase.OBSERVING && group.observing() && environment.inUse());
  }

  private void observe() {
    if (phase == Phase.FINALIZING) {
      finish();
      return;
    }
    if (phase != Phase.OBSERVING) return;
    usageChanged();
    if (!checking && group.observing()) {
      checking = true;
      execute(
          () -> {
            try {
              boolean healthy = controller.healthy(ticket);
              main.post(
                  () -> {
                    checking = false;
                    if (phase != Phase.OBSERVING) return;
                    if (healthy) {
                      phase = Phase.FINALIZING;
                      finish();
                    }
                  });
            } catch (Throwable error) {
              main.post(
                  () -> {
                    checking = false;
                    stop(error, false);
                  });
            }
          });
    }
    main.postDelayed(pulse, 1000);
  }

  private void finish() {
    if (phase != Phase.FINALIZING) return;
    if (group.phase() != GroupHandover.Phase.FINISHING && !group.observing()) {
      // 健康落盘后新窗口可能还在准备，旧组仍保留到它提交首帧。
      main.removeCallbacks(pulse);
      main.postDelayed(pulse, 125);
      return;
    }
    try {
      if (retirementStarted == 0) retirementStarted = android.os.SystemClock.elapsedRealtime();
      group.finish();
      if (!group.retired()) {
        if (android.os.SystemClock.elapsedRealtime() - retirementStarted >= 30000)
          throw new IllegalStateException("旧代际后台工作未能退出，停止后续更新");
        main.postDelayed(pulse, 125);
        return;
      }
      complete(Result.STABLE, null);
    } catch (Throwable error) {
      complete(Result.CLEANUP_FAILED, error);
    }
  }

  /** 撤回或明确模块错误均走整组恢复；只有明确内容故障才加入内容隔离。 */
  public void stop(Throwable error, boolean contentFailure) {
    requireMain();
    if (phase == Phase.FINISHED || phase == Phase.RESTORING) return;
    confirmedFailure = contentFailure;
    failure = error;
    wasExposed |= group != null && group.phase() == GroupHandover.Phase.TRIAL;
    phase = Phase.RESTORING;
    main.removeCallbacksAndMessages(null);
    controller.setActive(ticket, false);
    execute(
        () -> {
          Throwable persistence = null;
          try {
            controller.revert(ticket, contentFailure);
          } catch (Throwable rejected) {
            persistence = rejected;
          }
          Throwable result = persistence;
          main.post(
              () -> {
                diskRestored = true;
                addFailure(result);
                restored();
              });
        });
    if (group == null) {
      try {
        if (publication != null) publication.discardCandidate();
      } catch (Throwable closing) {
        addFailure(closing);
      }
      restoreUnstaged(android.os.SystemClock.elapsedRealtime());
      return;
    }
    try {
      group.rollback(
          new NativePage.Ready() {
            @Override
            public void ready() {
              componentsRestored = true;
              restored();
            }

            @Override
            public void failed(Throwable error) {
              componentsRestored = true;
              addFailure(error);
              restored();
            }
          });
    } catch (Throwable restoring) {
      componentsRestored = true;
      addFailure(restoring);
      restored();
    }
  }

  /** 创建页面之前取消也须退出已建立的进程钩子，不能绕过代际清理。 */
  private void restoreUnstaged(long started) {
    if (phase != Phase.RESTORING || componentsRestored) return;
    try {
      if (!recoveryFailed && publication != null && !publication.candidateReleased()) {
        if (android.os.SystemClock.elapsedRealtime() - started >= 30000)
          throw new IllegalStateException("未挂载候选的后台工作未能退出");
        main.postDelayed(() -> restoreUnstaged(started), 125);
        return;
      }
      if (publication == null) prepared.closeCallbacks();
    } catch (Throwable closing) {
      addFailure(closing);
    }
    componentsRestored = true;
    restored();
  }

  private void addFailure(Throwable error) {
    if (error == null) return;
    recoveryFailed = true;
    if (failure == null) failure = error;
    else if (failure != error) failure.addSuppressed(error);
  }

  private void restored() {
    if (phase == Phase.RESTORING && diskRestored && componentsRestored)
      complete(
          recoveryFailed
              ? Result.RECOVERY_FAILED
              : wasExposed ? Result.ROLLED_BACK : Result.CANCELLED,
          failure);
  }

  private void complete(Result result, Throwable error) {
    if (phase == Phase.FINISHED) return;
    phase = Phase.FINISHED;
    main.removeCallbacksAndMessages(null);
    listener.finished(result, error);
  }

  private void execute(Runnable action) {
    try {
      worker.execute(action);
    } catch (Throwable error) {
      if (phase == Phase.RESTORING) {
        diskRestored = true;
        addFailure(error);
        restored();
      } else stop(error, false);
    }
  }

  private static void requireMain() {
    StrictJson.require(Looper.myLooper() == Looper.getMainLooper(), "整组激活必须在主线程协调");
  }
}
