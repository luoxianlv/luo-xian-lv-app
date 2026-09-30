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

  private HostStartup(Application application, HostUpdateConfig config) throws Exception {
    this.config = config;
    File root = new File(application.getNoBackupFilesDir(), "native-update");
    store = new ContentStore(root);
    journal = new ActivationJournal(new File(root, "state"));
    trust = new TrustStore(new File(root, "trust"), config.root);
    quarantine = new ContentQuarantine(new File(root, "quarantine"));
    loader = new NativeLoader(application, store, quarantine, config.hostContract, config.mounts);
    pendingRestart = new PendingRestart(new File(root, "restart"));
    recoverPending(application);
  }

  static HostStartup open(Application application) throws Exception {
    HostUpdateConfig config = HostUpdateConfig.read(application);
    return config == null ? null : new HostStartup(application, config);
  }

  NativeLoader.Prepared prepareStable() throws Exception {
    while (!journal.state().stable.isEmpty()) {
      String id = journal.state().stable;
      try {
        return loader.prepareStable(store.snapshot(id), journal.state(), trust, config.environment);
      } catch (Exception unavailable) {
        Log.w("原生宿主", "稳定组合不可用，恢复上一版本：" + unavailable.getClass().getSimpleName());
        journal.unavailableStable(id);
      }
    }
    return null;
  }

  private void recoverPending(Application application) throws Exception {
    var state = journal.state();
    if (state.phase == ActivationJournal.Phase.STABLE) return;
    if (Build.VERSION.SDK_INT >= 30) {
      try {
        ActivityManager manager = application.getSystemService(ActivityManager.class);
        for (var exit :
            manager.getHistoricalProcessExitReasons(
                application.getPackageName(), state.processId, 16)) {
          if (exit.getPid() != state.processId
              || exit.getTimestamp() < state.startedAt
              || !application.getPackageName().equals(exit.getProcessName())) continue;
          ActivationJournal.ExitReason reason =
              switch (exit.getReason()) {
                case ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE ->
                    ActivationJournal.ExitReason.CRASH;
                case ApplicationExitInfo.REASON_ANR -> ActivationJournal.ExitReason.ANR;
                case ApplicationExitInfo.REASON_USER_REQUESTED,
                        ApplicationExitInfo.REASON_USER_STOPPED ->
                    ActivationJournal.ExitReason.USER;
                default -> ActivationJournal.ExitReason.SYSTEM;
              };
          journal.recover(reason, exit.getPid(), exit.getTimestamp());
          return;
        }
      } catch (RuntimeException unavailable) {
        Log.w("原生宿主", "系统退出记录不可用，保守恢复已稳定组合");
      }
    }
    journal.recover(ActivationJournal.ExitReason.UNKNOWN, 0, 0);
  }
}
