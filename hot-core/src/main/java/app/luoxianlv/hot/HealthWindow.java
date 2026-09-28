package app.luoxianlv.hot;

import java.util.function.LongSupplier;

/** 只累计真实前台或正在演奏的单调时间；后台等待不算试运行通过。 */
public final class HealthWindow {
  private final LongSupplier monotonicMillis;
  private long accumulated, since;
  private boolean active;

  public HealthWindow(LongSupplier monotonicMillis) {
    this.monotonicMillis = monotonicMillis;
  }

  public synchronized void setActive(boolean value) {
    long now = monotonicMillis.getAsLong();
    if (active) accumulated += Math.max(0, now - since);
    active = value;
    since = now;
  }

  public synchronized long observedMillis() {
    return accumulated + (active ? Math.max(0, monotonicMillis.getAsLong() - since) : 0);
  }
}
