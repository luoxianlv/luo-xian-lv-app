package app.luoxianlv.host;

import app.luoxianlv.hot.ActivationController;
import app.luoxianlv.hot.ActivationJournal;
import app.luoxianlv.hot.HealthOutbox;
import app.luoxianlv.hot.HotApiClient;
import app.luoxianlv.hot.OutcomeRecovery;
import app.luoxianlv.hot.UpdateCancellation;
import java.io.File;
import java.util.function.BooleanSupplier;

/** 更新工作线程上的健康回报持久化与确认；许可和调度仍由宿主协调。 */
final class HostUpdateReports {
  private final File journalDirectory;
  private final ActivationJournal journal;
  private final HealthOutbox outbox;
  private final HotApiClient api;
  private final ActivationController controller;

  HostUpdateReports(
      File journalDirectory,
      ActivationJournal journal,
      HealthOutbox outbox,
      HotApiClient api,
      ActivationController controller) {
    this.journalDirectory = journalDirectory;
    this.journal = journal;
    this.outbox = outbox;
    this.api = api;
    this.controller = controller;
  }

  void flush(UpdateCancellation token, BooleanSupplier reportingAllowed) throws Exception {
    compactHistory();
    OutcomeRecovery.reconcile(journal, outbox);
    token.check();
    if (!reportingAllowed.getAsBoolean()) return;
    var batch = outbox.batch(100);
    if (batch.isEmpty()) return;
    var reply = api.report(batch, true, token);
    // 幂等回执可能早于已观察的修订；仍可确认事件，但不能回退渠道下限。
    if (reply.revision >= journal.state().revision)
      controller.observe(reply.revision, null, null, reply.serverTime);
    outbox.acknowledge(batch);
  }

  void compactHistory() throws Exception {
    // 转入新回执前让历史让出容量；重读磁盘保护当前和恢复身份及其序号。
    outbox.compact(() -> new ActivationJournal(journalDirectory).state(), 64);
  }
}
