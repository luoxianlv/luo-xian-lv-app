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
  private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
  private final IDeltaWorker.Stub binder =
      new IDeltaWorker.Stub() {
        @Override
        public int merge(
            ParcelFileDescriptor base,
            ParcelFileDescriptor patch,
            ParcelFileDescriptor output,
            long targetSize,
            long timeoutMillis,
            long token) {
          if (base == null
              || patch == null
              || output == null
              || targetSize <= 0
              || targetSize > 2L * 1024 * 1024 * 1024
              || timeoutMillis < 1000
              || timeoutMillis > 180000
              || token == 0) return -1;
          if (!active.compareAndSet(0, token)) return -2;
          // 原生库即使遇到无法返回的异常，也只终止隔离工作进程。
          java.util.concurrent.ScheduledFuture<?> alarm =
              watchdog.schedule(
                  () -> {
                    if (active.get() == token) Process.killProcess(Process.myPid());
                  },
                  timeoutMillis + 2000,
                  TimeUnit.MILLISECONDS);
          try (base;
              patch;
              output) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            return NativePatch.merge(
                base.getFd(), patch.getFd(), output.getFd(), targetSize, timeoutMillis);
          } catch (IOException | RuntimeException failure) {
            return -3;
          } finally {
            active.compareAndSet(token, 0);
            alarm.cancel(false);
          }
        }

        @Override
        public void cancel(long token) {
          if (token == 0 || active.get() != token) return;
          NativePatch.cancel();
          watchdog.schedule(
              () -> {
                if (active.get() == token) Process.killProcess(Process.myPid());
              },
              2000,
              TimeUnit.MILLISECONDS);
        }
      };

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
