package app.luoxianlv.hot;

/** 日志到持久队列的可重试交接；任何写入失败都保留日志回执，且不触发网络。 */
public final class OutcomeRecovery {
  private OutcomeRecovery() {}

  public static void reconcile(ActivationJournal journal, HealthOutbox outbox) throws Exception {
    for (var outcome : journal.state().outcomes) {
      outbox.appendOnce(outcome.attemptId, outcome.kind, outcome.code);
      journal.outcomeQueued(outcome);
    }
  }
}
