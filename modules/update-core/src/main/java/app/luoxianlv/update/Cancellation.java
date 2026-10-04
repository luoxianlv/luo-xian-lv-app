package app.luoxianlv.update;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 取消贯穿下载、校验和隔离合并，取消本身不会触发全包回退。 */
public final class Cancellation {
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final List<Runnable> hooks = new ArrayList<>();
  private int running;
  private static final ThreadPoolExecutor CLOSER =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(8),
          task -> {
            Thread thread = new Thread(task, "apk-update-close");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());

  public boolean isCancelled() {
    return cancelled.get() || Thread.currentThread().isInterrupted();
  }

  public void check() throws CancelledException {
    if (isCancelled()) {
      cancel();
      throw new CancelledException();
    }
  }

  public void cancel() {
    if (cancelled.compareAndSet(false, true)) scheduleClosures();
  }

  public AutoCloseable onCancel(Runnable hook) {
    java.util.Objects.requireNonNull(hook);
    synchronized (hooks) {
      if (hooks.size() >= 4) throw new IllegalStateException("更新取消资源数量超限");
      hooks.add(hook);
    }
    if (cancelled.get()) scheduleClosures();
    return () -> {
      synchronized (hooks) {
        if (!cancelled.get()) hooks.remove(hook);
      }
    };
  }

  private void scheduleClosures() {
    try {
      CLOSER.execute(this::closePending);
    } catch (java.util.concurrent.RejectedExecutionException full) {
      // 不丢弃回调，任务自身的后台 finally 在释放生命周期租约前补齐收尾。
    }
  }

  private void closePending() {
    List<Runnable> owned;
    synchronized (hooks) {
      owned = new ArrayList<>(hooks);
      hooks.clear();
      running += owned.size();
    }
    for (Runnable hook : owned) {
      try {
        hook.run();
      } catch (Throwable ignored) {
        /* 继续关闭本任务剩余资源 */
      } finally {
        synchronized (hooks) {
          running--;
          hooks.notifyAll();
        }
      }
    }
  }

  /** 仅在后台任务退出时调用，确保取消回调也在租约内真正完成。 */
  public void awaitClosures() {
    boolean interrupted = false;
    while (true) {
      if (cancelled.get()) closePending();
      synchronized (hooks) {
        if (running == 0 && (!cancelled.get() || hooks.isEmpty())) break;
        try {
          hooks.wait();
        } catch (InterruptedException retry) {
          interrupted = true;
        }
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  public static final class CancelledException extends InterruptedIOException {
    public CancelledException() {
      super("更新已取消");
    }
  }
}
