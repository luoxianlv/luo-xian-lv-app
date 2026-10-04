package app.luoxianlv.host;

import android.util.Log;
import app.luoxianlv.hot.ActivationJournal;
import app.luoxianlv.hot.ContentCollector;
import app.luoxianlv.hot.ContentStore;
import app.luoxianlv.hot.ExecutionJournal;
import app.luoxianlv.hot.PendingRestart;
import app.luoxianlv.hot.UpdateClient;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/** 根据持久选择、执行记录和待激活候选保护组合，在更新工作线程回收闲置内容。 */
final class HostUpdateContent {
  private final File journalDirectory;
  private final ContentStore store;
  private final PendingRestart pendingRestart;
  private final ExecutionJournal execution;

  HostUpdateContent(
      File journalDirectory,
      ContentStore store,
      PendingRestart pendingRestart,
      ExecutionJournal execution) {
    this.journalDirectory = journalDirectory;
    this.store = store;
    this.pendingRestart = pendingRestart;
    this.execution = execution;
  }

  void collect(Supplier<UpdateClient.PreparedUpdate> pending) throws Exception {
    var result =
        new ContentCollector(store)
            .collect(() -> protectedSnapshots(pending), 2, 512L << 20);
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

  private Set<String> protectedSnapshots(Supplier<UpdateClient.PreparedUpdate> pending)
      throws Exception {
    // 必须在回收锁内重读磁盘，避免损坏或其他进程更新被内存旧值掩盖。
    var current = new ActivationJournal(journalDirectory).state();
    Set<String> protectedIds = new HashSet<>();
    for (String id :
        new String[] {
          current.stable,
          current.active,
          current.candidate,
          current.previousStable,
          pendingRestart.current()
        }) if (!id.isEmpty()) protectedIds.add(id);
    var running = execution.current();
    if (running != null) protectedIds.add(running.snapshot);
    var waiting = pending.get();
    if (waiting != null) protectedIds.add(waiting.snapshot.manifest.snapshotId);
    return protectedIds;
  }
}
