package app.luoxianlv.hot;

import android.app.Service;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import app.luoxianlv.hot.contract.HostDiagnostics;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/** 停止服务的 Binder 请求在后台执行；生命周期结果仍在主线程按启动序号应用。 */
public final class ForegroundStopper {
  private static final Executor WORKER =
      Executors.newSingleThreadExecutor(
          task -> {
            Thread thread = new Thread(task, "playback-service-stop");
            thread.setDaemon(true);
            return thread;
          });

  private final Executor worker;
  private final Executor main;
  private final IntPredicate stop;
  private final IntConsumer stopped;
  private volatile Request pending;

  public ForegroundStopper(Service service, IntConsumer stopped) {
    this(
        WORKER,
        task -> new Handler(Looper.getMainLooper()).post(task),
        startId -> {
          try {
            return service.stopSelfResult(startId);
          } catch (RuntimeException failure) {
            HostDiagnostics.log(Log.WARN, "播放服务", "系统拒绝停止播放前台服务", failure);
            return false;
          }
        },
        stopped);
  }

  ForegroundStopper(Executor worker, Executor main, IntPredicate stop, IntConsumer stopped) {
    this.worker = worker;
    this.main = main;
    this.stop = stop;
    this.stopped = stopped;
  }

  /** 主线程调用；重复关闭同一启动命令只发送一次系统请求。 */
  public void stop(int startId) {
    if (startId == 0 || (pending != null && pending.startId == startId)) return;
    Request request = new Request(startId);
    pending = request;
    worker.execute(
        () -> {
          if (pending != request) return;
          boolean accepted = stop.test(startId);
          main.execute(
              () -> {
                if (pending != request) return;
                pending = null;
                if (accepted) stopped.accept(startId);
              });
        });
  }

  /** 主线程调用；返回是否需要重新发送启动请求，覆盖已进入系统的旧停止请求。 */
  public boolean cancel() {
    boolean wasPending = pending != null;
    pending = null;
    return wasPending;
  }

  private static final class Request {
    final int startId;

    Request(int startId) {
      this.startId = startId;
    }
  }
}
