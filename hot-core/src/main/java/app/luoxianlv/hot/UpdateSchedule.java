package app.luoxianlv.hot;

import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/** 只在前台或已有播放服务工作时检查；合并触发，不另建保活或并行轮询。 */
public final class UpdateSchedule {
  private static final long MIN_GAP = 30000, NORMAL = 60000, MAX_BACKOFF = 1800000;
  private final LongSupplier elapsed;
  private final DoubleSupplier random;
  private long due, retryNotBefore, lastStart = -MIN_GAP;
  private int failures;
  private boolean usable, online, priorityWork, inFlight, requested;

  public UpdateSchedule(LongSupplier elapsed, DoubleSupplier random) {
    this.elapsed = elapsed;
    this.random = random;
  }

  public synchronized void availability(
      boolean foreground, boolean playbackService, boolean connected, boolean preparingPlayback) {
    boolean nowUsable = foreground || playbackService;
    if ((nowUsable && !usable) || (connected && !online)) request();
    usable = nowUsable;
    online = connected;
    priorityWork = preparingPlayback;
  }

  public synchronized void request() {
    if (inFlight) requested = true;
    else due = Math.max(retryNotBefore, Math.max(elapsed.getAsLong(), lastStart + MIN_GAP));
  }

  /** -1 表示当前不应设置更新计时器；已有任务结束或生命周期变化后再计算。 */
  public synchronized long delayMillis() {
    if (!usable || !online || priorityWork || inFlight) return -1;
    return Math.max(0, due - elapsed.getAsLong());
  }

  public synchronized boolean beginIfDue() {
    if (delayMillis() != 0) return false;
    begin();
    return true;
  }

  /** 已下载候选的许可属于同一轮后续工作，不等待正常轮询；失败退避仍不能绕过。 */
  public synchronized boolean beginFollowupIfAllowed() {
    if (!usable || !online || priorityWork || inFlight || elapsed.getAsLong() < retryNotBefore)
      return false;
    begin();
    return true;
  }

  private void begin() {
    inFlight = true;
    requested = false;
    lastStart = elapsed.getAsLong();
  }

  /** 生命周期、断网或播放准备取消不计为网络失败；下一安全时刻仍遵守最小间隔。 */
  public synchronized void cancelled() {
    StrictJson.require(inFlight, "没有正在取消的更新检查");
    inFlight = false;
    due = Math.max(retryNotBefore, Math.max(elapsed.getAsLong(), lastStart + MIN_GAP));
    retryNotBefore = due;
    requested = false;
  }

  /** 本地预算/空间等待不归咎于服务端，但后续许可也应等到正常间隔，防止反复重试。 */
  public synchronized void deferred() {
    finish(true, 0);
    retryNotBefore = due;
  }

  public synchronized void finish(boolean success, long retryAfterMillis) {
    StrictJson.require(inFlight && retryAfterMillis >= 0, "更新检查回调无效");
    inFlight = false;
    failures = success ? 0 : Math.min(6, failures + 1);
    long base = success ? NORMAL : Math.min(MAX_BACKOFF, NORMAL << (failures - 1));
    double sample = random.getAsDouble();
    StrictJson.require(Double.isFinite(sample) && sample >= 0 && sample <= 1, "检查抖动参数无效");
    long wait = Math.min(MAX_BACKOFF, (long) (base * (0.8 + sample * 0.4)));
    if (requested && success) wait = MIN_GAP;
    wait = Math.max(wait, Math.min(MAX_BACKOFF, retryAfterMillis));
    retryNotBefore = Math.addExact(elapsed.getAsLong(), Math.min(MAX_BACKOFF, retryAfterMillis));
    due = Math.max(lastStart + MIN_GAP, Math.addExact(elapsed.getAsLong(), wait));
    // 恢复网络/重回前台只合并触发，不能把失败退避改成一次立即重试。
    if (!success) retryNotBefore = due;
    requested = false;
  }
}
