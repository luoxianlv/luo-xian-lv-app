package app.luoxianlv.input;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Binder 冻结时及时归还状态线程；阻塞调用最多两个，禁止不断增开线程。 */
final class BoundedInputRpc implements AutoCloseable {
  private final long timeoutMs;
  private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 15,
      TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), task -> {
        Thread thread = new Thread(task, "input-rpc"); thread.setDaemon(true); return thread;
      });

  BoundedInputRpc(long timeoutMs) {
    if (timeoutMs <= 0) throw new IllegalArgumentException("RPC 超时必须为正数");
    this.timeoutMs = timeoutMs;
  }

  <T> T call(Callable<T> task) throws Exception {
    var pending = executor.submit(task);
    try { return pending.get(timeoutMs, TimeUnit.MILLISECONDS); }
    catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof Exception error) throw error;
      if (cause instanceof Error error) throw error;
      throw new IllegalStateException(cause);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt(); throw interrupted;
    } finally {
      // cancel 不保证能打断 Binder；容量仍由线程池控制，旧调用不能写回新状态。
      pending.cancel(false);
      executor.remove((Runnable) pending);
    }
  }

  @Override public void close() { executor.shutdownNow(); }
}
