package app.luoxianlv.input;

/** 冷启动等待与重试有界；旧回调、主动断开和普通状态刷新不能重开预算。 */
final class ShizukuConnectionPolicy {
  static final long START_TIMEOUT_MS = 35_000, STABLE_MS = 30_000;
  private static final int MAX_ATTEMPTS = 3;
  private long ticket, startedAt, retryAt, connectedAt = -1;
  private int attempts;
  private boolean inFlight, blocked;

  boolean mayStart(long now) {
    return !blocked && !inFlight && connectedAt < 0 && attempts < MAX_ATTEMPTS && now >= retryAt;
  }

  long start(long now) {
    if (!mayStart(now)) return 0;
    attempts++;
    inFlight = true;
    startedAt = now;
    return ++ticket;
  }

  boolean connected(long expected, long now) {
    if (blocked || !inFlight || expected != ticket) return false;
    inFlight = false;
    connectedAt = now;
    return true;
  }

  boolean expired(long expected, long now) {
    return inFlight && expected == ticket && now - startedAt >= START_TIMEOUT_MS;
  }

  long failed(long expected, long now, boolean retryable) {
    if (blocked || expected != ticket || (!inFlight && connectedAt < 0)) return -1;
    inFlight = false;
    connectedAt = -1;
    if (!retryable || attempts >= MAX_ATTEMPTS) {
      blocked = true;
      return -1;
    }
    long delay = attempts <= 1 ? 1000 : 2000;
    retryAt = now + delay;
    return delay;
  }

  void stable(long now) {
    if (connectedAt >= 0 && now - connectedAt >= STABLE_MS) attempts = 0;
  }

  void invalidate() { ++ticket; inFlight = false; connectedAt = -1; }
  void cancel() { invalidate(); blocked = true; }
  void reset() { invalidate(); attempts = 0; retryAt = 0; blocked = false; }
  long currentTicket() { return ticket; }
  int attempts() { return attempts; }
  boolean waitingToRetry(long now) { return !blocked && !inFlight && connectedAt < 0 && now < retryAt; }

  // Shizuku 远端删除按 tag 定位，不看 version；每次绑定必须隔离迟到的旧订阅和清理。
  static String serviceTag(int uid, int pid, int revision, long generation) {
    return "luoxianlv-touch-" + uid + "-" + pid + "-" + revision + "-" + generation;
  }
}
