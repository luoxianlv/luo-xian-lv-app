package app.luoxianlv.hot;

import java.io.InterruptedIOException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** 单次更新操作的取消与连接归属；关闭连接不占主线程，也不打断其他业务线程。 */
public final class UpdateCancellation implements BooleanSupplier {
  public static final class Cancelled extends InterruptedIOException {
    public Cancelled() {
      super("更新已让位于播放或生命周期");
    }
  }

  public interface Registration extends AutoCloseable {
    @Override
    void close();
  }

  private static final java.util.concurrent.ThreadPoolExecutor CLOSER =
      new java.util.concurrent.ThreadPoolExecutor(
          2,
          2,
          0,
          java.util.concurrent.TimeUnit.MILLISECONDS,
          new java.util.concurrent.ArrayBlockingQueue<>(8),
          task -> {
            Thread thread = new Thread(task, "native-update-close");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
          },
          new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
  private final BooleanSupplier external;
  private final IdentityHashMap<AutoCloseable, Boolean> connections = new IdentityHashMap<>();
  private final AtomicLong objectRequests = new AtomicLong(), objectBytes = new AtomicLong();
  private volatile boolean cancelled;

  public UpdateCancellation(BooleanSupplier external) {
    this.external = java.util.Objects.requireNonNull(external);
  }

  static UpdateCancellation from(BooleanSupplier input) {
    return input instanceof UpdateCancellation token ? token : new UpdateCancellation(input);
  }

  static UpdateCancellation none() {
    return new UpdateCancellation(() -> false);
  }

  @Override
  public boolean getAsBoolean() {
    return cancelled || external.getAsBoolean();
  }

  public boolean isCancelled() {
    return cancelled;
  }

  public long objectRequests() {
    return objectRequests.get();
  }

  public long objectBytes() {
    return objectBytes.get();
  }

  void objectOpened() {
    objectRequests.incrementAndGet();
  }

  void objectRead(int bytes) {
    if (bytes > 0) objectBytes.addAndGet(bytes);
  }

  public void check() throws Cancelled {
    if (getAsBoolean() || Thread.currentThread().isInterrupted()) {
      cancel();
      throw new Cancelled();
    }
  }

  public Registration register(AutoCloseable connection) throws Cancelled {
    java.util.Objects.requireNonNull(connection);
    synchronized (connections) {
      if (!cancelled && !external.getAsBoolean()) {
        if (connections.size() >= 4) throw new IllegalStateException("单次更新连接数量超限");
        connections.put(connection, Boolean.TRUE);
        return () -> {
          synchronized (connections) {
            connections.remove(connection);
          }
        };
      }
    }
    closeAsync(connection);
    cancel();
    throw new Cancelled();
  }

  public void cancel() {
    List<AutoCloseable> owned;
    synchronized (connections) {
      if (cancelled) return;
      cancelled = true;
      owned = List.copyOf(connections.keySet());
      connections.clear();
    }
    for (AutoCloseable connection : owned) closeAsync(connection);
  }

  private static void closeAsync(AutoCloseable connection) {
    try {
      CLOSER.execute(
          () -> {
            try {
              connection.close();
            } catch (Exception ignored) {
              /* 原请求保留取消身份，不泄漏带签名地址的底层异常。 */
            }
          });
    } catch (java.util.concurrent.RejectedExecutionException saturated) {
      // 收尾队列有界；请求自身500ms读取切片仍会察觉取消并在finally关闭连接。
    }
  }
}
