package app.luoxianlv.host;

import android.app.Application;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import app.luoxianlv.hot.*;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** 普通页面与既有演奏生命周期驱动更新；不新建保活服务，下载、验签和日志均在后台。 */
final class HostUpdates {
  private static final long INTERVAL = 300_000;
  private final Application application;
  private final HostStartup state;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ScheduledExecutorService worker =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            Thread thread = new Thread(task, "native-update");
            thread.setDaemon(true);
            return thread;
          });
  private final HotApiClient api;
  private final UpdateClient client;
  private final ActivationController controller;
  private final HealthOutbox outbox;
  private final Runnable pulse = this::tick;
  private volatile boolean active, blocked;
  private boolean busy;
  private long nextCheck, preparedAt;
  private int failures;
  private UpdateClient.PreparedUpdate pending;
  private GroupActivation group;
  private String attempt;
  private final Set<String> used = new HashSet<>();
  private ActivationController.Ticket coldTicket;
  private boolean coldPersisting, coldExposed, coldChecking, coldFailed;
  private final Runnable coldPulse = this::observeCold;

  /** 在运行时加载前为缓存组合取得本次许可；失败不启用新代码。 */
  NativeLoader.Prepared prepareCold() throws Exception {
    String target = state.pendingRestart.current();
    if (target.isEmpty()) return null;
    if (target.equals(state.journal.state().stable)
        || state.journal.state().quarantine.contains(target)) {
      state.pendingRestart.clear(target);
      return null;
    }
    ActivationController.Ticket ticket = null;
    UpdateClient.PreparedUpdate cached = null;
    try {
      api.timeouts(750, 750);
      cached = client.cached(target);
      long schema =
          state.journal.state().stable.isEmpty()
              ? 1
              : state.store.snapshot(state.journal.state().stable).manifest.stateCurrent;
      ticket =
          client.authorize(
              cached,
              schema,
              android.os.Process.myPid(),
              () -> Thread.currentThread().isInterrupted());
      outbox.append(ticket.attemptId, "prepared", "cold_group_prepared");
      var prepared = state.loader.prepare(cached.snapshot, state.journal.state());
      coldTicket = ticket;
      busy = true;
      return prepared;
    } catch (Throwable error) {
      boolean confirmed =
          error instanceof ReflectiveOperationException || error instanceof LinkageError;
      if (ticket != null) controller.abort(ticket, confirmed);
      if (cached != null
          && !state.loader.discardUninitializedRuntime(cached.snapshot.manifest.runtime.sha256))
        throw new IllegalStateException("未能安全放弃启动运行时", error);
      if (confirmed) state.pendingRestart.clear(target);
      Log.w("原生宿主", "待重启组合暂未启用，继续稳定版本：" + error.getClass().getSimpleName());
      return null;
    } finally {
      api.timeouts(10000, 15000);
    }
  }

  boolean coldPending() {
    return coldTicket != null;
  }

  private static final class ColdGuardFailure extends IllegalStateException {
    ColdGuardFailure(Throwable cause) {
      super("启动许可已失效或组合已停用", cause);
    }
  }

  static boolean contentFailure(Throwable error) {
    for (Throwable value = error; value != null; value = value.getCause())
      if (value instanceof ColdGuardFailure) return false;
    return true;
  }

  boolean initialCreation(Runnable create) {
    requireMain();
    if (coldFailed) throw new ColdGuardFailure(null);
    if (coldTicket == null || coldExposed) {
      create.run();
      return true;
    }
    if (coldPersisting) return false;
    var creationError = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    try {
      return controller.prepareView(
          coldTicket,
          () -> {
            try {
              create.run();
            } catch (RuntimeException | Error error) {
              creationError.set(error);
              throw error;
            }
          });
    } catch (RuntimeException | Error error) {
      if (creationError.get() != null) throw error;
      throw new ColdGuardFailure(error);
    }
  }

  void startColdObservation() {
    requireMain();
    if (coldTicket != null) main.post(coldPulse);
  }

  private boolean coldFramesReady() {
    var pages = Bootstrap.pages();
    return pages.isEmpty()
        ? Bootstrap.playbackReady()
        : pages.stream().allMatch(value -> value.host().readyFrame());
  }

  private void observeCold() {
    requireMain();
    var ticket = coldTicket;
    if (ticket == null || blocked) return;
    if (!coldExposed) {
      if (coldPersisting) return;
      if (!coldFramesReady()) {
        try {
          controller.prepareView(ticket, () -> {});
        } catch (Throwable expired) {
          failCold(expired, false);
          return;
        }
        main.postDelayed(coldPulse, 100);
        return;
      }
      coldPersisting = true;
      worker.execute(
          () -> {
            try {
              controller.firstFrame(ticket);
              main.post(() -> exposeCold(ticket));
            } catch (Throwable error) {
              main.post(() -> failCold(error, false));
            }
          });
      return;
    }
    coldUsage();
    if (!coldChecking && coldFramesReady()) {
      coldChecking = true;
      worker.execute(
          () -> {
            try {
              boolean healthy =
                  controller.healthy(
                      ticket,
                      () -> state.recordExecution(ticket.snapshot.manifest, ticket.attemptId));
              if (healthy) {
                state.pendingRestart.clear(ticket.snapshot.manifest.snapshotId);
                OutcomeRecovery.reconcile(state.journal, outbox);
              }
              main.post(
                  () -> {
                    coldChecking = false;
                    if (coldTicket != ticket) return;
                    if (healthy) {
                      coldTicket = null;
                      busy = false;
                      nextCheck = SystemClock.elapsedRealtime() + INTERVAL;
                      worker.execute(
                          () -> {
                            try {
                              flush();
                            } catch (Exception error) {
                              Log.w("原生宿主", "启动健康回报保留，等待联网");
                            }
                          });
                      usageChanged();
                    }
                  });
            } catch (Throwable error) {
              main.post(() -> failCold(error, false));
            }
          });
    }
    main.postDelayed(coldPulse, 1000);
  }

  private void exposeCold(ActivationController.Ticket ticket) {
    if (coldTicket != ticket || blocked) return;
    try {
      if (!controller.expose(ticket, () -> {})) {
        main.postDelayed(() -> exposeCold(ticket), 16);
        return;
      }
      coldPersisting = false;
      coldExposed = true;
      attempt = ticket.attemptId;
      used.clear();
      enqueue(attempt, "activated", "cold_group_exposed");
      coldUsage();
      main.post(coldPulse);
    } catch (Throwable error) {
      failCold(error, false);
    }
  }

  private void coldUsage() {
    if (coldTicket == null) return;
    boolean observed = coldExposed && coldFramesReady();
    controller.setActive(coldTicket, observed && Bootstrap.inUse());
    if (observed) {
      for (var page : Bootstrap.pages())
        if (page.host().inUse()) recordUse(page.route().replace('.', '_'));
      if (Bootstrap.playbackInUse()) recordUse("playback");
    }
  }

  void failCold(Throwable error, boolean confirmed) {
    requireMain();
    var ticket = coldTicket;
    if (ticket == null) return;
    coldTicket = null;
    coldFailed = true;
    blocked = true;
    main.removeCallbacks(coldPulse);
    controller.setActive(ticket, false);
    Bootstrap.stopBusiness(error);
    worker.execute(
        () -> {
          try {
            controller.revert(ticket, confirmed);
            if (confirmed) state.pendingRestart.clear(ticket.snapshot.manifest.snapshotId);
            main.post(() -> Bootstrap.recoveryPersisted(true));
            // 恢复选择已保存后，回报队列故障不应堵住用户重新打开；原始回执仍在日志中。
            OutcomeRecovery.reconcile(state.journal, outbox);
          } catch (Throwable recovery) {
            error.addSuppressed(recovery);
          }
          Log.e("原生宿主", "启动组合未通过，已停止自动激活，等待下一进程恢复", error);
        });
  }

  void stopScheduling() {
    blocked = true;
    active = false;
    main.removeCallbacksAndMessages(null);
  }

  HostUpdates(Application application, HostStartup state) throws Exception {
    this.application = application;
    this.state = state;
    File root = new File(application.getNoBackupFilesDir(), "native-update");
    var identity =
        InstallationIdentity.open(
            new File(root, "installation"), state.config.applicationId, state.config.environment);
    api =
        new HotApiClient(
            state.config.origin,
            identity,
            state.config.hostContract,
            state.config.fingerprint,
            state.config.environment.equals("test"));
    controller =
        new ActivationController(
            state.journal,
            state.trust,
            state.quarantine,
            state.config.hostContract,
            SystemClock::elapsedRealtime);
    outbox = new HealthOutbox(new File(root, "health"));
    compactHistory();
    var budget = new DownloadBudget(new File(root, "budget"));
    // 热更断点使用内部目录，便于原子隔离后安全回收；用户壁纸/日志继续使用各自外部目录。
    var downloads =
        new ObjectDownloader(new File(root, "downloads"), budget,
            new File(root, "download-records-private-v1"));
    client =
        new UpdateClient(
            api,
            state.config.root,
            state.store,
            state.trust,
            state.journal,
            controller,
            state.quarantine,
            downloads,
            budget,
            state.config.mounts,
            SystemClock::elapsedRealtime,
            new PreparationSpace(application));
  }

  void usageChanged() {
    requireMain();
    active = Bootstrap.inUse();
    coldUsage();
    if (group != null && attempt != null && group.phase() == GroupActivation.Phase.OBSERVING) {
      for (var page : Bootstrap.pages())
        if (page.host().inUse()) recordUse(page.route().replace('.', '_'));
      if (Bootstrap.playbackInUse()) recordUse("playback");
    }
    main.removeCallbacks(pulse);
    if (active && !blocked) main.post(pulse);
  }

  private void recordUse(String route) {
    if (used.add(route)) enqueue(attempt, "module_used", route);
  }

  private void tick() {
    requireMain();
    if (!active || blocked) return;
    long now = SystemClock.elapsedRealtime();
    if (busy) return;
    if (pending != null && now - preparedAt >= INTERVAL) pending = null;
    if (pending != null) {
      if (!Bootstrap.canAutoActivate()) {
        main.postDelayed(pulse, 500);
        return;
      }
      var candidate = pending;
      pending = null;
      busy = true;
      worker.execute(() -> authorize(candidate));
    } else if (now >= nextCheck) {
      busy = true;
      worker.execute(this::prepare);
    } else main.postDelayed(pulse, nextCheck - now);
  }

  private long schema() {
    var manifest = Bootstrap.source().prepared.manifest;
    return manifest == null ? 1 : manifest.stateCurrent;
  }

  private boolean metered() {
    try {
      var connectivity = application.getSystemService(ConnectivityManager.class);
      return connectivity == null || connectivity.isActiveNetworkMetered();
    } catch (RuntimeException unavailable) {
      return true;
    }
  }

  private void prepare() {
    try {
      collectIdleContent();
      flush();
      var candidate = client.prepare(schema(), this::metered, () -> !active || blocked);
      var current = Bootstrap.source().prepared;
      boolean restart =
          candidate != null
              && (candidate.needsRestart(current.runtimeHash, current.runtimeAbi)
                  || !Bootstrap.supportsLiveWork()
                  || state.pendingRestart.current().equals(candidate.snapshot.manifest.snapshotId));
      if (restart) state.pendingRestart.record(state.store, candidate.snapshot);
      main.post(
          () -> {
            busy = false;
            failures = 0;
            nextCheck = SystemClock.elapsedRealtime() + INTERVAL;
            if (candidate != null && !restart) {
              pending = candidate;
              preparedAt = SystemClock.elapsedRealtime();
            } else if (restart) Log.i("原生宿主", "完整运行时组合已缓存，等待下次启动重新授权");
            usageChanged();
          });
    } catch (Throwable failure) {
      retry(failure);
    }
  }

  /** 与下载、准备、激活收尾共用单线程；候选转交主线程后不插入清理任务。 */
  private void collectIdleContent() throws Exception {
    File root = new File(application.getNoBackupFilesDir(), "native-update");
    var result =
        new ContentCollector(state.store)
            .collect(
                () -> {
                  // 从磁盘重新读取，损坏或另一个进程的更新不能被内存旧值掩盖。
                  var current = new ActivationJournal(new File(root, "state")).state();
                  Set<String> protectedIds = new HashSet<>();
                  for (String id :
                      new String[] {
                        current.stable,
                        current.active,
                        current.candidate,
                        current.previousStable,
                        state.pendingRestart.current()
                      }) if (!id.isEmpty()) protectedIds.add(id);
                  var execution = state.execution.current();
                  if (execution != null) protectedIds.add(execution.snapshot);
                  return protectedIds;
                },
                2,
                512L << 20);
    if (result.beforeBytes() != result.afterBytes())
      Log.i(
          "原生宿主",
          "热更缓存已回收 "
              + (result.beforeBytes() - result.afterBytes())
              + " 字节，删除历史组合 "
              + result.removedSnapshots()
              + " 个");
    if (result.overBudget()) throw new ContentCollector.Deferred();
  }

  private void authorize(UpdateClient.PreparedUpdate candidate) {
    ActivationController.Ticket ticket = null;
    try {
      ticket =
          client.authorize(
              candidate, schema(), android.os.Process.myPid(), () -> !active || blocked);
      outbox.append(ticket.attemptId, "prepared", "whole_group_prepared");
      NativeLoader.Prepared prepared =
          state.loader.prepare(candidate.snapshot, state.journal.state());
      var authorized = ticket;
      main.post(() -> activate(candidate, authorized, prepared));
    } catch (Throwable failure) {
      if (ticket != null) {
        try {
          boolean contentFailure =
              failure instanceof ReflectiveOperationException || failure instanceof LinkageError;
          controller.abort(ticket, contentFailure);
          OutcomeRecovery.reconcile(state.journal, outbox);
        } catch (Throwable restoring) {
          failure.addSuppressed(restoring);
          blocked = true;
        }
      }
      retry(failure);
    }
  }

  private void activate(
      UpdateClient.PreparedUpdate candidate,
      ActivationController.Ticket ticket,
      NativeLoader.Prepared prepared) {
    requireMain();
    if (!active || blocked || !Bootstrap.canAutoActivate()) {
      try {
        prepared.closeCallbacks();
      } catch (Throwable closing) {
        blocked = true;
        Log.e("原生宿主", "取消候选资源释放失败，停止后续更新", closing);
      }
      worker.execute(
          () -> {
            try {
              controller.abort(ticket, false);
            } catch (Throwable failure) {
              blocked = true;
              Log.e("原生宿主", "过期激活恢复失败", failure);
            }
            main.post(
                () -> {
                  busy = false;
                  pending = candidate;
                  preparedAt = SystemClock.elapsedRealtime();
                  usageChanged();
                });
          });
      return;
    }
    attempt = ticket.attemptId;
    used.clear();
    try {
      group =
          Bootstrap.activate(
              controller,
              ticket,
              prepared,
              worker,
              new GroupActivation.Listener() {
                @Override
                public void exposed() {
                  enqueue(ticket.attemptId, "activated", "whole_group_exposed");
                  usageChanged();
                }

                @Override
                public void finished(GroupActivation.Result result, Throwable failure) {
                  if (failure instanceof app.luoxianlv.hot.contract.HandoverDeferred deferred
                      && deferred.restartRequired)
                    worker.execute(
                        () -> {
                          try {
                            state.pendingRestart.record(state.store, candidate.snapshot);
                          } catch (Exception error) {
                            blocked = true;
                            Log.e("原生宿主", "暂缓组合未能保存", error);
                          }
                        });
                  if (failure instanceof app.luoxianlv.hot.contract.HandoverDeferred deferred
                      && !deferred.restartRequired
                      && (result == GroupActivation.Result.CANCELLED
                          || result == GroupActivation.Result.ROLLED_BACK)) {
                    pending = candidate;
                    preparedAt = SystemClock.elapsedRealtime();
                  }
                  if (result == GroupActivation.Result.RECOVERY_FAILED
                      || result == GroupActivation.Result.CLEANUP_FAILED) blocked = true;
                  group = null;
                  attempt = null;
                  busy = false;
                  nextCheck = SystemClock.elapsedRealtime() + INTERVAL;
                  if (failure != null)
                    Log.w("原生宿主", "自动热更已结束：" + result + "；" + failure.getClass().getSimpleName());
                  if (active && !blocked)
                    worker.execute(
                        () -> {
                          try {
                            flush();
                          } catch (Exception deferred) {
                            Log.w("原生宿主", "健康回报保留，等待下次联网");
                          }
                        });
                  usageChanged();
                }
              });
    } catch (Throwable failure) {
      try {
        prepared.closeCallbacks();
      } catch (Throwable closing) {
        blocked = true;
        failure.addSuppressed(closing);
      }
      worker.execute(
          () -> {
            try {
              controller.abort(ticket, false);
            } catch (Throwable restoring) {
              blocked = true;
              failure.addSuppressed(restoring);
            }
            retry(failure);
          });
    }
  }

  private void enqueue(String id, String kind, String code) {
    worker.execute(
        () -> {
          try {
            outbox.append(id, kind, code);
          } catch (Throwable failure) {
            blocked = true;
            Log.e("原生宿主", "健康事件未能持久化，停止新的自动激活", failure);
          }
        });
  }

  private void flush() throws Exception {
    compactHistory();
    OutcomeRecovery.reconcile(state.journal, outbox);
    if (!active || !(state.config.testHealthReports || Bootstrap.diagnosticsAllowed())) return;
    var batch = outbox.batch(100);
    if (batch.isEmpty()) return;
    var reply = api.report(batch, true);
    // 接口已接收的幂等回执可能早于后来收到的修订；仍可确认事件，但不能回退渠道下限。
    if (reply.revision >= state.journal.state().revision)
      controller.observe(reply.revision, null, null, reply.serverTime);
    outbox.acknowledge(batch);
  }

  private void compactHistory() throws Exception {
    // 在转入新回执前让已结束历史让出容量；读取磁盘保护身份，不能重置当前/恢复版序号。
    File root = new File(application.getNoBackupFilesDir(), "native-update");
    outbox.compact(() -> new ActivationJournal(new File(root, "state")).state(), 64);
  }

  private void retry(Throwable failure) {
    main.post(
        () -> {
          busy = false;
          long retry = Math.min(INTERVAL, 5000L << Math.min(6, failures++));
          if (failure instanceof HotApiClient.Failure apiFailure)
            retry = Math.max(retry, apiFailure.retryAfterMillis);
          if (failure instanceof HttpObjectSource.Failure sourceFailure)
            retry = Math.max(retry, sourceFailure.retryAfterMillis);
          if (failure instanceof DownloadBudget.Deferred
              || failure instanceof ContentCollector.Deferred
              || failure instanceof PreparationSpace.Deferred) retry = INTERVAL;
          nextCheck = SystemClock.elapsedRealtime() + retry;
          Log.w("原生宿主", "自动热更暂缓，当前版本继续使用：" + failure.getClass().getSimpleName());
          usageChanged();
        });
  }

  private void requireMain() {
    if (Looper.myLooper() != Looper.getMainLooper())
      throw new IllegalStateException("自动更新状态必须在主线程协调");
  }
}
