package app.luoxianlv.host.realtime;

import android.content.Context;
import android.util.Log;
import app.luoxianlv.hot.contract.SharedRealtime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 宿主保管凭据；共享运行时提供 Kotlin 连接，故障时热更仍走独立 HTTP。 */
public final class HostRealtime implements SharedRealtime.Host, AutoCloseable {
  private static final String LOG_TAG = "LuoxianlvRealtime";
  public interface HotEvents { void changed(boolean subscribed, String revision, String trust); }
  private final RealtimeApi api;
  private final HotEvents hotEvents;
  private final Runnable usageChanged;
  private final ThreadPoolExecutor requests = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(8), task -> thread(task, "realtime-auth"));
  private final ExecutorService canceller = Executors.newSingleThreadExecutor(task -> thread(task, "realtime-cancel"));
  private final SharedRealtime.Engine engine;
  private final AutoCloseable registration;
  private final java.util.Set<AutoCloseable> operations = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private volatile boolean closed;

  public HostRealtime(Context context, ClassLoader runtime, RealtimeApi api,
      HotEvents hotEvents, Runnable usageChanged) throws Exception {
    this.api = api;
    this.hotEvents = hotEvents;
    this.usageChanged = usageChanged;
    requests.allowCoreThreadTimeOut(true);
    Class<?> implementation = Class.forName("app.luoxianlv.runtime.realtime.RealtimeEngine", true, runtime);
    if (implementation.getClassLoader() != runtime)
      throw new IllegalStateException("实时连接必须来自已验证的共享运行时");
    engine = (SharedRealtime.Engine) implementation
        .getConstructor(Context.class, SharedRealtime.Host.class, String.class)
        .newInstance(context, this, api.socketUri().toString());
    registration = SharedRealtime.connect(engine);
  }

  private static Thread thread(Runnable task, String name) {
    Thread thread = new Thread(task, name);
    thread.setDaemon(true);
    return thread;
  }
  private interface Work { String run(RealtimeApi.Request request) throws Exception; }
  private AutoCloseable request(RealtimeApi.Stage operation, Work work, SharedRealtime.Authorization callback) {
    RealtimeApi.Request request = new RealtimeApi.Request();
    AtomicBoolean cancelled = new AtomicBoolean();
    if (closed) { callback.complete("", 503, 60_000); return () -> {}; }
    var future = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
    AutoCloseable cancellation = () -> {
      if (!cancelled.compareAndSet(false, true)) return;
      Runnable disconnect = request.cancel();
      var selected = future.get();
      if (selected != null) selected.cancel(true);
      try { canceller.execute(disconnect); }
      catch (java.util.concurrent.RejectedExecutionException stopped) { /* close 已取消全部登记请求 */ }
    };
    operations.add(cancellation);
    try {
      var submitted = requests.submit(() -> {
        try {
        String value = "";
        int status = 0;
        long retry = 0;
        try { value = work.run(request); }
        catch (RealtimeApi.Failure failure) {
          // 回调保留原来的故障退避信号；日志只报告实际收到的 HTTP 状态。
          status = failure.kind == RealtimeApi.FailureKind.HTTP ? failure.status : 503;
          retry = failure.retryAfter;
          if (!closed && !cancelled.get())
            Log.w(LOG_TAG, failureSummary(operation, failure.stage, failure.kind.label,
                failure.status, retry, failure));
        }
        catch (Exception failure) {
          status = 503; retry = 1000;
          if (!closed && !cancelled.get())
            Log.w(LOG_TAG, failureSummary(operation, operation, "unexpected", 0, retry, failure));
        }
        if (!closed && !cancelled.get()) callback.complete(value, status, retry);
        } finally { operations.remove(cancellation); }
      });
      future.set(submitted);
      if (cancelled.get()) submitted.cancel(true);
      return () -> {
        cancellation.close();
        operations.remove(cancellation);
      };
    } catch (java.util.concurrent.RejectedExecutionException full) {
      operations.remove(cancellation);
      if (!closed)
        Log.w(LOG_TAG, failureSummary(operation, operation, "local", 0, 30_000, full));
      callback.complete("", 503, 30_000);
      return () -> {};
    }
  }
  /** 固定阶段与有限类型链；不包含异常正文、堆栈或任何授权资料。 */
  static String failureSummary(RealtimeApi.Stage operation, RealtimeApi.Stage stage, String kind,
      int httpStatus, long retryMillis, Throwable failure) {
    StringBuilder types = new StringBuilder();
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 4; depth++) {
      if (depth > 0) types.append("->");
      String name = current.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_$]", "_");
      if (name.isEmpty()) name = "Exception";
      types.append(name, 0, Math.min(64, name.length()));
      current = current.getCause();
    }
    return "authorization_failed operation=" + operation.label + " stage=" + stage.label
        + " kind=" + kind + " httpStatus=" + httpStatus + " types=" + types
        + " retryMs=" + retryMillis;
  }
  @Override public AutoCloseable ticket(SharedRealtime.Authorization callback) {
    return request(RealtimeApi.Stage.TICKET, api::ticket, callback);
  }
  @Override public AutoCloseable accountGrant(String token, String epoch, SharedRealtime.Authorization callback) {
    return request(RealtimeApi.Stage.ACCOUNT_GRANT,
        request -> api.accountGrant(request, token, epoch), callback);
  }
  @Override public void hotChanged(boolean subscribed, String revision, String trust) {
    if (!closed) hotEvents.changed(subscribed, revision, trust);
  }
  @Override public void usageChanged() { if (!closed) usageChanged.run(); }
  public void usage(boolean active) {
    // 用户显式开启的悬浮窗已有前台通知，也能在冷进程中恢复房间查询。
    if (!closed) engine.usage(active
        || app.luoxianlv.service.PlaybackForegroundService.foregroundRegistered());
  }
  public boolean performing() { return !closed && engine.performing(); }
  @Override public void close() throws Exception {
    if (closed) return;
    registration.close();
    closed = true;
    hotEvents.changed(false, "0", "0");
    engine.close();
    for (AutoCloseable operation : operations) operation.close();
    operations.clear();
    requests.shutdownNow();
    canceller.shutdown();
  }
}
