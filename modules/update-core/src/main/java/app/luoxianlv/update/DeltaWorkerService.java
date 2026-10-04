package app.luoxianlv.update;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 无网络权限的隔离进程，仅持有宿主显式授予的三个描述符。 */
public final class DeltaWorkerService extends Service {
  private final AtomicLong active = new AtomicLong();
  private final Object jobLock = new Object();
  private boolean merging, cancelled;
  private long reservedTimeout;
  private java.util.concurrent.ScheduledFuture<?> alarm;
  private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
  private final IDeltaWorker.Stub binder =
      new IDeltaWorker.Stub() {
        @Override
        public int reserve(long token, long timeoutMillis) {
          if (token == 0 || timeoutMillis < 1000 || timeoutMillis > 180000) return -1;
          synchronized (jobLock) {
            if (!active.compareAndSet(0, token)) return -2;
            merging = false;
            cancelled = false;
            reservedTimeout = timeoutMillis;
            // 保留后尚未开始 merge 的异常客户端也不能永久占用 worker。
            alarm =
                watchdog.schedule(
                    () -> killOwned(token), timeoutMillis + 2000, TimeUnit.MILLISECONDS);
            return 0;
          }
        }

        @Override
        public int merge(
            ParcelFileDescriptor base,
            ParcelFileDescriptor patch,
            ParcelFileDescriptor output,
            long targetSize,
            long timeoutMillis,
            long token) {
          boolean started = false;
          // 所有参数拒绝、忙碌和已取消分支也必须关闭 Binder 转交的 FD。
          try (base;
              patch;
              output) {
            if (base == null
                || patch == null
                || output == null
                || targetSize <= 0
                || targetSize > 2L * 1024 * 1024 * 1024
                || timeoutMillis < 1000
                || timeoutMillis > 180000
                || token == 0) return -1;
            synchronized (jobLock) {
              if (active.get() != token || merging || reservedTimeout != timeoutMillis) return -2;
              merging = true;
              started = true;
              if (cancelled) return -4;
            }
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            return NativePatch.merge(
                base.getFd(), patch.getFd(), output.getFd(), targetSize, timeoutMillis, token);
          } catch (IOException | RuntimeException failure) {
            return -3;
          } finally {
            if (started)
              synchronized (jobLock) {
                finishOwned(token);
              }
          }
        }

        @Override
        public void cancel(long token) {
          synchronized (jobLock) {
            // idle、迟到或另一个任务的 token 都不留下预取消状态。
            if (token == 0 || active.get() != token || cancelled) return;
            cancelled = true;
            NativePatch.cancel(token);
            watchdog.schedule(() -> killOwned(token), 2000, TimeUnit.MILLISECONDS);
          }
        }

        @Override
        public void release(long token) {
          synchronized (jobLock) {
            if (active.get() == token && !merging) finishOwned(token);
          }
        }
      };

  private void killOwned(long token) {
    synchronized (jobLock) {
      if (active.get() == token) Process.killProcess(Process.myPid());
    }
  }

  private void finishOwned(long token) {
    if (!active.compareAndSet(token, 0)) return;
    try {
      NativePatch.finish(token);
    } finally {
      alarm.cancel(false);
    }
  }

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  @Override
  public void onDestroy() {
    try {
      binder.cancel(active.get());
    } catch (android.os.RemoteException local) {
      /* 本地 Binder 不会出现远端异常 */
    }
    watchdog.shutdown();
    super.onDestroy();
  }

  public static boolean isPatchProcess(Context context) {
    if (android.os.Build.VERSION.SDK_INT >= 28)
      return patchProcessName(context.getPackageName(), android.app.Application.getProcessName());
    try (java.io.FileInputStream input = new java.io.FileInputStream("/proc/self/cmdline")) {
      byte[] bytes = new byte[256];
      int count = input.read(bytes);
      int length = 0;
      while (length < count && bytes[length] != 0) length++;
      return patchProcessName(
          context.getPackageName(),
          new String(bytes, 0, length, java.nio.charset.StandardCharsets.UTF_8));
    } catch (IOException unavailable) {
      return false;
    }
  }

  static boolean patchProcessName(String applicationId, String name) {
    String prefix = applicationId + ":delta_worker";
    // 新系统为 isolated service 追加组件名，仍须在 Application 中跳过业务初始化。
    return name != null && (name.equals(prefix) || name.startsWith(prefix + ":"));
  }
}
