package app.luoxianlv.hot.contract;

/** 每个业务代际独立持有：提交前冻结新工作，已接受工作必须完成 finally 才释放租约。 */
public final class WorkGate {
  private int active, retained;
  private boolean frozen, retired;

  public synchronized AutoCloseable acquire() {
    return acquire(true);
  }

  /** 已打开的页面资源允许在空闲时交接，但实际关闭前仍占用代际。 */
  public synchronized AutoCloseable retain() {
    return acquire(false);
  }

  private AutoCloseable acquire(boolean blocking) {
    if (frozen || retired) return null;
    if (blocking) active++;
    else retained++;
    return new AutoCloseable() {
      private boolean closed;

      @Override
      public void close() {
        synchronized (WorkGate.this) {
          if (!closed) {
            closed = true;
            if (blocking) active--;
            else retained--;
          }
        }
      }
    };
  }

  public synchronized boolean idle() {
    return !retired && !frozen && active == 0;
  }

  public synchronized boolean freeze() {
    if (retired || active != 0) return false;
    frozen = true;
    return true;
  }

  public synchronized void resume() {
    if (retired) throw new IllegalStateException("已退役业务不能重新接受工作");
    frozen = false;
  }

  public synchronized void retire() {
    retired = true;
    frozen = true;
  }

  public synchronized boolean released() {
    return retired && active == 0 && retained == 0;
  }

  public synchronized int activeCount() {
    return active;
  }
}
