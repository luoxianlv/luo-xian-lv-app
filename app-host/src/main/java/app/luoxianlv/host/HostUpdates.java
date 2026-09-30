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
    var budget = new DownloadBudget(new File(root, "budget"));
    // 可执行内容始终复制回内部只读对象库；外部仅保留可观察的有界下载暂存。
    File visible = application.getExternalFilesDir("hot-update");
    var downloads =
        new ObjectDownloader(new File(visible == null ? root : visible, "downloads"), budget);
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
            SystemClock::elapsedRealtime);
  }

  void usageChanged() {
    requireMain();
    active = Bootstrap.inUse();
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
      flush();
      var candidate = client.prepare(schema(), this::metered, () -> !active || blocked);
      var current = Bootstrap.source().prepared;
      boolean restart =
          candidate != null && candidate.needsRestart(current.runtimeHash, current.runtimeAbi);
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

  private void retry(Throwable failure) {
    main.post(
        () -> {
          busy = false;
          long retry = Math.min(INTERVAL, 5000L << Math.min(6, failures++));
          if (failure instanceof HotApiClient.Failure apiFailure)
            retry = Math.max(retry, apiFailure.retryAfterMillis);
          if (failure instanceof HttpObjectSource.Failure sourceFailure)
            retry = Math.max(retry, sourceFailure.retryAfterMillis);
          if (failure instanceof DownloadBudget.Deferred) retry = INTERVAL;
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
