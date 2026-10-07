package app.luoxianlv.input;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 截图只交付一次；超时、取消和重复回调都要归还缓冲。 */
final class CaptureTicket<T extends AutoCloseable> implements AutoCloseable {
  private final CountDownLatch ready = new CountDownLatch(1);
  private boolean ended, delivered;
  private T value;

  synchronized void offer(T next) {
    if (ended || delivered) { dispose(next); return; }
    delivered = true;
    value = next;
    ready.countDown();
  }

  T await(long timeoutMs) throws InterruptedException {
    if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) { close(); return null; }
    synchronized (this) {
      if (ended) return null;
      ended = true;
      T result = value;
      value = null;
      return result;
    }
  }

  @Override public synchronized void close() {
    ended = true;
    dispose(value);
    value = null;
    ready.countDown();
  }

  private static void dispose(AutoCloseable value) {
    if (value != null) try { value.close(); } catch (Exception ignored) { }
  }
}
