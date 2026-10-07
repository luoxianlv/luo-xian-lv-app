package app.luoxianlv.host;

import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.os.Build;
import android.util.Log;
import app.luoxianlv.hot.*;
import java.io.File;

/** 冷启动先恢复未确认事务，再校验稳定组合；缓存故障只恢复选择，不隔离正常代码。 */
final class HostStartup {
  final HostUpdateConfig config;
  final ContentStore store;
  final ActivationJournal journal;
  final TrustStore trust;
  final ContentQuarantine quarantine;
  final NativeLoader loader;
  final PendingRestart pendingRestart;
  final ExecutionJournal execution;
  volatile ExecutionJournal.Run running;

  private HostStartup(Application application, HostUpdateConfig config) throws Exception {
    this.config = config;
    File root = new File(application.getNoBackupFilesDir(), "native-update");
    store = new ContentStore(root);
    journal = new ActivationJournal(new File(root, "state"));
    trust = new TrustStore(new File(root, "trust"), config.root);
    quarantine = new ContentQuarantine(new File(root, "quarantine"));
    loader = new NativeLoader(application, store, quarantine, config.hostContract, config.mounts);
    pendingRestart = new PendingRestart(new File(root, "restart"));
    execution = new ExecutionJournal(new File(root, "execution"));
    recoverPending(application);
  }

  static HostStartup open(Application application) throws Exception {
    HostUpdateConfig config = HostUpdateConfig.read(application);
    return config == null ? null : new HostStartup(application, config);
  }

  NativeLoader.Prepared prepareStable() throws Exception {
    while (!journal.state().stable.isEmpty()) {
      String id = journal.state().stable;
      ContentStore.Snapshot snapshot = null;
      try {
        snapshot = store.snapshot(id);
        return loader.prepareStable(snapshot, journal.state(), trust, config.environment);
      } catch (Exception | LinkageError unavailable) {
        if (unavailable instanceof PreparationSpace.Deferred
            || unavailable instanceof NativeLoader.ResourceUnsupported) {
          // 空间或资源所需 API 不满足不证明内容失效；本次采用 APK，下次仍保留稳定选择。
          Log.w("原生宿主", "稳定组合因设备条件暂缓，保留稳定选择并暂用安装包恢复组合");
          return null;
        }
        // 尚未执行业务时可以放弃失败加载器；不能拿残留的新运行时混装旧恢复组合。
        if (snapshot != null
            && !loader.discardUninitializedRuntime(snapshot.manifest.runtime.sha256))
          throw new IllegalStateException("稳定运行时已开始执行，不能在本进程混合恢复", unavailable);
        Log.w("原生宿主", "稳定组合不可用，恢复上一版本：" + unavailable.getClass().getSimpleName());
        if (snapshot != null
            && (unavailable instanceof ReflectiveOperationException
                || unavailable instanceof LinkageError))
          journal.stableContentFailed(snapshot.manifest, quarantine, config.hostContract);
        else journal.unavailableStable(id);
      }
    }
    return null;
  }

  private void recoverPending(Application application) throws Exception {
    var previous = execution.current();
    var state = journal.state();
    if (state.phase != ActivationJournal.Phase.STABLE) {
      Exit exit = recentExit(application, state.processId, state.startedAt);
      if (previous != null
          && previous.pid == state.processId
          && previous.crashedAt >= state.startedAt) {
        exit =
            new Exit(
                previous.snapshot.equals(state.candidate)
                    ? ActivationJournal.ExitReason.CRASH
                    : ActivationJournal.ExitReason.SYSTEM,
                previous.pid,
                previous.crashedAt);
      }
      if (exit.reason == ActivationJournal.ExitReason.CRASH
          || exit.reason == ActivationJournal.ExitReason.ANR)
        quarantine.isolate(store.snapshot(state.candidate).manifest, config.hostContract);
      journal.recover(exit.reason, exit.pid, exit.at);
    }
    state = journal.state();
    if (previous != null && previous.belongsTo(state.stable, config.fingerprint)) {
      Exit exit =
          previous.crashedAt != 0
              ? new Exit(ActivationJournal.ExitReason.CRASH, previous.pid, previous.crashedAt)
              : recentExit(application, previous.pid, previous.startedAt);
      if (previous.matches(exit.reason, exit.pid, exit.at)) {
        journal.stableProcessFailed(
            store.snapshot(state.stable).manifest, quarantine, config.hostContract, exit.reason);
        Log.w("原生宿主", "运行组合发生崩溃或未响应，在业务加载前恢复上一版本");
      }
    }
    if (previous != null) execution.clear(previous);
  }

  void recordExecution(HotManifest manifest, String attempt) throws Exception {
    running =
        execution.start(
            manifest.snapshotId,
            attempt,
            config.fingerprint,
            android.os.Process.myPid(),
            System.currentTimeMillis());
  }

  void ensureExecution(HotManifest manifest) throws Exception {
    var current = running;
    if (current != null
        && current.snapshot.equals(manifest.snapshotId)
        && current.pid == android.os.Process.myPid()) return;
    var state = journal.state();
    recordExecution(
        manifest,
        state.stable.equals(manifest.snapshotId)
            ? state.stableAttempt
            : state.candidate.equals(manifest.snapshotId) ? state.attempt : "");
  }

  private record Exit(ActivationJournal.ExitReason reason, int pid, long at) {}

  private static Exit recentExit(Application application, int pid, long startedAt) {
    if (Build.VERSION.SDK_INT >= 30) {
      try {
        return Api30.recentExit(application, pid, startedAt);
      } catch (RuntimeException unavailable) {
        Log.w("原生宿主", "系统退出记录不可用，不把未知退出计为坏包");
      }
    }
    return new Exit(ActivationJournal.ExitReason.UNKNOWN, 0, 0);
  }

  /** Android 8–10 不解析新平台的退出记录类型。 */
  @android.annotation.TargetApi(30)
  private static final class Api30 {
    static Exit recentExit(Application application, int pid, long startedAt) {
      ActivityManager manager = application.getSystemService(ActivityManager.class);
      Exit newest = new Exit(ActivationJournal.ExitReason.UNKNOWN, 0, 0);
      for (var exit :
          manager.getHistoricalProcessExitReasons(application.getPackageName(), pid, 16)) {
        if (exit.getPid() != pid
            || exit.getTimestamp() < startedAt
            || !application.getPackageName().equals(exit.getProcessName())
            || exit.getTimestamp() <= newest.at) continue;
        var reason =
            switch (exit.getReason()) {
              case ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE ->
                  ActivationJournal.ExitReason.CRASH;
              case ApplicationExitInfo.REASON_ANR -> ActivationJournal.ExitReason.ANR;
              case ApplicationExitInfo.REASON_USER_REQUESTED,
                      ApplicationExitInfo.REASON_USER_STOPPED ->
                  ActivationJournal.ExitReason.USER;
              default -> ActivationJournal.ExitReason.SYSTEM;
            };
        newest = new Exit(reason, exit.getPid(), exit.getTimestamp());
      }
      return newest;
    }
  }
}
