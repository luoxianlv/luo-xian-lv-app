package app.luoxianlv.hot.contract;

import android.util.Log;
import java.util.concurrent.atomic.AtomicReference;

/** 宿主诊断可接入业务文件日志；业务未就绪或记录失败时仍写入系统日志。 */
public final class HostDiagnostics {
  public interface Sink {
    void log(int priority, String tag, String message, Throwable failure);
  }

  private static final AtomicReference<Registration> CURRENT = new AtomicReference<>();

  private HostDiagnostics() {}

  public static AutoCloseable install(Sink sink) {
    Registration registration = new Registration(sink);
    CURRENT.set(registration);
    return registration;
  }

  public static void log(int priority, String tag, String message, Throwable failure) {
    Registration current = CURRENT.get();
    if (current != null) {
      try {
        current.sink.log(priority, tag, message, failure);
        return;
      } catch (Throwable ignored) {
        // 故障业务不能阻断宿主的前台登记、停止或恢复路径。
      }
    }
    Log.println(
        priority, tag, message + (failure == null ? "" : "\n" + Log.getStackTraceString(failure)));
  }

  private static final class Registration implements AutoCloseable {
    final Sink sink;

    Registration(Sink sink) {
      this.sink = java.util.Objects.requireNonNull(sink);
    }

    @Override
    public void close() {
      // 旧业务释放时不能摘掉已接替它的新日志接收器。
      CURRENT.compareAndSet(this, null);
    }
  }
}
