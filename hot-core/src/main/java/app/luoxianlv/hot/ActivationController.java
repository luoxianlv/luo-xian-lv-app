package app.luoxianlv.hot;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** 统一许可、日志和曝光边界；落盘方法在更新工作线程调用，expose 只做主线程事务。 */
public final class ActivationController {
  private final ActivationJournal journal;
  private final TrustStore trust;
  private final ContentQuarantine quarantine;
  private final long hostContract;
  private final LongSupplier elapsedMillis;
  private final ReentrantLock transaction = new ReentrantLock();
  private volatile Ticket current;

  public static final class Ticket {
    public final ContentStore.Snapshot snapshot;
    public final String attemptId;
    private final ActivationPermit permit;
    private final HealthWindow health;
    private volatile boolean exposed, finished;

    private Ticket(ContentStore.Snapshot snapshot, ActivationPermit permit, LongSupplier clock) {
      this.snapshot = snapshot;
      this.permit = permit;
      attemptId = permit.attemptId;
      health = new HealthWindow(clock);
    }
  }

  public ActivationController(
      ActivationJournal journal,
      TrustStore trust,
      ContentQuarantine quarantine,
      long hostContract,
      LongSupplier elapsedMillis) {
    this.journal = journal;
    this.trust = trust;
    this.quarantine = quarantine;
    this.hostContract = hostContract;
    this.elapsedMillis = elapsedMillis;
  }

  /** 新类初始化、构造器或页面代码执行前调用；单个控制器只允许一个试运行。 */
  public Ticket begin(
      ContentStore.Snapshot snapshot, ActivationPermit permit, Instant serverTime, int processId)
      throws Exception {
    transaction.lock();
    try {
      StrictJson.require(current == null, "已有热更正在准备或观察");
      HotManifest manifest = snapshot.manifest;
      StrictJson.require(
          permit.snapshotId.equals(manifest.snapshotId)
              && hostContract >= manifest.hostMin
              && hostContract <= manifest.hostMax,
          "候选与激活许可或宿主不一致");
      TrustStore.Record record = trust.current();
      StrictJson.require(record != null, "激活前缺少已保存的根授权");
      record.authority.current(journal.state().trustVersion, serverTime);
      StrictJson.require(
          record.authority.applicationId.equals(manifest.applicationId)
              && record.authority.environment.equals(manifest.environment),
          "候选不属于当前授权范围");
      byte[] raw = ContentStore.readBounded(new java.io.File(snapshot.directory, "manifest.json"));
      StrictJson.require(HotSignatures.hash(raw).equals(manifest.snapshotId), "内部候选清单已改变");
      record.authority.verify(
          HotSignatures.MANIFEST,
          raw,
          ContentStore.readBounded(new java.io.File(snapshot.directory, "manifest.sig.json")),
          serverTime);
      quarantine.requireAllowed(manifest, hostContract);
      permit.requireCurrent(
          journal.state().revision, record.authority.version, elapsedMillis.getAsLong());
      permit.consume(journal.state().revision, elapsedMillis.getAsLong());
      journal.begin(
          manifest.snapshotId,
          permit.attemptId,
          permit.revision,
          record.authority.version,
          processId,
          System.currentTimeMillis());
      current = new Ticket(snapshot, permit, elapsedMillis);
      return current;
    } finally {
      transaction.unlock();
    }
  }

  /** 更新决定与新授权也经同一控制器推进，防止旧许可在撤回后完成首帧。 */
  public void observe(long revision, byte[] authority, byte[] signature, Instant now)
      throws Exception {
    transaction.lock();
    try {
      StrictJson.require(revision >= journal.state().revision, "拒绝过时的渠道修订");
      if (authority != null || signature != null) {
        StrictJson.require(authority != null && signature != null, "根授权与签名必须同时提供");
        trust.accept(authority, signature, journal, now);
      }
      journal.observeVersions(revision, journal.state().trustVersion);
      if (current != null && !current.exposed) {
        ActivationJournal.State state = journal.state();
        if (state.revision > current.permit.revision
            || state.trustVersion != current.permit.trustVersion
            || !state.attempt.equals(current.attemptId)) abort(current, false);
      }
    } finally {
      transaction.unlock();
    }
  }

  /** 已产生候选首帧，先在后台提交日志；旧页面此时仍留在前景。 */
  public void firstFrame(Ticket ticket) throws Exception {
    transaction.lock();
    try {
      requireCurrent(ticket);
      requirePermit(ticket);
      journal.firstFrame(ticket.attemptId);
    } finally {
      transaction.unlock();
    }
  }

  /** 此处不读写文件；校验与 View/输入绑定必须在同一个主线程临界区内完成。 */
  public boolean prepareView(Ticket ticket, Runnable createView) {
    if (!transaction.tryLock()) return false;
    try {
      requireCurrent(ticket);
      StrictJson.require(journal.state().phase == ActivationJournal.Phase.PREPARING, "候选创建阶段已结束");
      requirePermit(ticket);
      createView.run();
      return true;
    } finally {
      transaction.unlock();
    }
  }

  /** 返回 false 时后台正在提交决定，调用方下一帧再试，不能阻塞主线程。 */
  public boolean expose(Ticket ticket, Runnable replaceViews) {
    // 后台正在验签或落盘时延后一帧，主线程不能等待文件锁或 fsync。
    if (!transaction.tryLock()) return false;
    try {
      requireCurrent(ticket);
      StrictJson.require(
          !ticket.exposed && journal.state().phase == ActivationJournal.Phase.TRIAL,
          "候选尚未提交首帧或已经展示");
      requirePermit(ticket);
      replaceViews.run();
      ticket.exposed = true;
      return true;
    } finally {
      transaction.unlock();
    }
  }

  public void setActive(Ticket ticket, boolean foregroundOrPlaying) {
    ticket.health.setActive(valid(ticket) && ticket.exposed && foregroundOrPlaying);
  }

  /** 仅真实使用时间达标后稳定；返回 false 表示还需保留可恢复的旧页面。 */
  public boolean healthy(Ticket ticket) throws Exception {
    transaction.lock();
    try {
      requireCurrent(ticket);
      if (!ticket.exposed || ticket.health.observedMillis() < 60000) return false;
      journal.healthy(ticket.attemptId, ticket.health.observedMillis());
      ticket.finished = true;
      current = null;
      return true;
    } finally {
      transaction.unlock();
    }
  }

  /** 取消、过期、离开页面不隔离内容；只有明确的模块错误才隔离同一内容的重签版本。 */
  public void abort(Ticket ticket, boolean confirmedContentFailure) throws Exception {
    transaction.lock();
    try {
      if (ticket == null) return;
      if (ticket.finished) {
        // 健康落盘与 UI 收到结果之间也可能报告故障，不能因观察刚结束而丢失归因。
        if (confirmedContentFailure
            && journal.state().stable.equals(ticket.snapshot.manifest.snapshotId))
          journal.stableContentFailed(ticket.snapshot.manifest, quarantine, hostContract);
        return;
      }
      if (ticket != current) return;
      if (confirmedContentFailure) quarantine.isolate(ticket.snapshot.manifest, hostContract);
      if (journal.state().attempt.equals(ticket.attemptId))
        journal.fail(ticket.attemptId, confirmedContentFailure);
      ticket.health.setActive(false);
      ticket.finished = true;
      current = null;
    } finally {
      transaction.unlock();
    }
  }

  public boolean valid(Ticket ticket) {
    return ticket != null && ticket == current && !ticket.finished;
  }

  /** 整组已经恢复旧实现时，磁盘也必须恢复；覆盖健康落盘与主线程确认之间的撤回竞态。 */
  public void revert(Ticket ticket, boolean confirmedContentFailure) throws Exception {
    transaction.lock();
    try {
      if (ticket != null
          && ticket.finished
          && journal.state().phase == ActivationJournal.Phase.STABLE
          && journal.state().stable.equals(ticket.snapshot.manifest.snapshotId)) {
        journal.revertStable(
            ticket.snapshot.manifest, quarantine, hostContract, confirmedContentFailure);
      } else abort(ticket, confirmedContentFailure);
    } finally {
      transaction.unlock();
    }
  }

  private void requireCurrent(Ticket ticket) {
    StrictJson.require(
        valid(ticket) && journal.state().attempt.equals(ticket.attemptId), "激活回调已被新决定或恢复操作取代");
  }

  private void requirePermit(Ticket ticket) {
    ticket.permit.requireCurrent(
        journal.state().revision, journal.state().trustVersion, elapsedMillis.getAsLong());
  }
}
