package app.luoxianlv.hot;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import app.luoxianlv.hot.contract.SharedUpdate;
import app.luoxianlv.update.ApkUpdateExecutor;
import app.luoxianlv.update.Cancellation;
import app.luoxianlv.update.SignedDelivery;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;

/** 唯一宿主普通更新入口；业务持有调用租约，下载和合并不切换业务来源。 */
public final class ApkUpdateBridge implements SharedUpdate.Bridge {
  private static final AtomicInteger ACTIVE = new AtomicInteger();
  private final Application application;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable changed;
  private final BooleanSupplier idle;
  private final AtomicReference<Run> current = new AtomicReference<>();
  private static final ScheduledExecutorService PROBES =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            Thread thread = new Thread(task, "apk-update-priority");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
          });

  private static final class Run {
    final Cancellation cancellation = new Cancellation();
    final AtomicBoolean userCancelled = new AtomicBoolean();
  }

  /** 只排一个主线程状态读取；工作线程以有界频率探测，不在主线程等待网络或 Binder。 */
  static final class PriorityWatch implements AutoCloseable {
    private final Consumer<Runnable> dispatch;
    private final BooleanSupplier idle;
    private final Cancellation cancellation;
    private final AtomicBoolean pending = new AtomicBoolean(), closed = new AtomicBoolean();
    final AtomicBoolean deferred = new AtomicBoolean();
    private ScheduledFuture<?> task;

    PriorityWatch(Consumer<Runnable> dispatch, BooleanSupplier idle, Cancellation cancellation) {
      this.dispatch = dispatch;
      this.idle = idle;
      this.cancellation = cancellation;
    }

    void start() {
      task = PROBES.scheduleWithFixedDelay(this::probe, 0, 200, TimeUnit.MILLISECONDS);
    }

    void probe() {
      if (closed.get() || cancellation.isCancelled() || !pending.compareAndSet(false, true)) return;
      try {
        dispatch.accept(
            () -> {
              try {
                synchronized (this) {
                  if (closed.get() || cancellation.isCancelled()) return;
                  boolean allowed;
                  try {
                    allowed = idle.getAsBoolean();
                  } catch (Throwable unavailable) {
                    allowed = false;
                  }
                  if (!allowed) {
                    deferred.set(true);
                    cancellation.cancel();
                  }
                }
              } finally {
                pending.set(false);
              }
            });
      } catch (RuntimeException unavailableMain) {
        pending.set(false);
        if (!closed.get()) {
          deferred.set(true);
          cancellation.cancel();
        }
      }
    }

    @Override
    public void close() {
      synchronized (this) {
        closed.set(true);
      }
      if (task != null) task.cancel(false);
    }
  }

  private ApkUpdateBridge(Application application, Runnable changed, BooleanSupplier idle) {
    this.application = application;
    this.changed = changed;
    this.idle = idle;
  }

  public static void install(Application application, Runnable changed, BooleanSupplier idle) {
    SharedUpdate.connect(new ApkUpdateBridge(application, changed, idle));
  }

  public static boolean busy() {
    return ACTIVE.get() > 0;
  }

  @Override
  public boolean supportsIncremental() {
    try {
      return HostUpdateConfig.read(application) != null;
    } catch (Exception unavailable) {
      return false;
    }
  }

  @Override
  public SharedUpdate.Result prepare(SharedUpdate.Request request, SharedUpdate.Progress progress)
      throws Exception {
    return run(request, progress);
  }

  @Override
  public SharedUpdate.Result resume(SharedUpdate.Progress progress) throws Exception {
    return run(null, progress);
  }

  @Override
  public SharedUpdate.Pending pending() throws Exception {
    if (Looper.myLooper() == Looper.getMainLooper())
      throw new IllegalStateException("普通更新恢复检查必须后台调用");
    HostUpdateConfig config = HostUpdateConfig.read(application);
    if (config == null) return null;
    var pending = executor(config).pending();
    if (pending == null) return null;
    var request = pending.request;
    return new SharedUpdate.Pending(
        new SharedUpdate.Request(
            request.delivery.toJson().toString(),
            request.fullUrls,
            request.outerVersionCode,
            request.outerSha256,
            request.outerSize),
        pending.target.versionName);
  }

  private ApkUpdateExecutor executor(HostUpdateConfig config) throws Exception {
    return new ApkUpdateExecutor(
        application,
        new ApkDeliveryVerifier(application),
        hash -> config.origin.resolve("/api/update/delta/" + hash).toString());
  }

  private SharedUpdate.Result run(SharedUpdate.Request request, SharedUpdate.Progress progress)
      throws Exception {
    if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("普通更新必须后台调用");
    Run run = new Run();
    Cancellation cancellation = run.cancellation;
    if (!current.compareAndSet(null, run)) throw new IllegalStateException("已有普通更新正在运行");
    ACTIVE.incrementAndGet();
    main.post(changed);
    PriorityWatch watch =
        new PriorityWatch(
            task -> {
              if (!main.post(task)) throw new IllegalStateException("主线程暂不可用");
            },
            idle,
            cancellation);
    ApkUpdateExecutor.VerifiedApk result = null;
    Exception failure = null;
    try {
      // 即使持有业务租约也不抢演奏准备的 CPU；取消不会触发整包回退。
      while (!playbackIdle(cancellation)) {
        cancellation.check();
        progress.onProgress("waiting", 0, 0, 0, "等待演奏结束后准备更新");
        Thread.sleep(200);
        cancellation.check();
      }
      cancellation.check();
      watch.start();
      HostUpdateConfig config = HostUpdateConfig.read(application);
      if (config == null) throw new SecurityException("当前安装包没有增量更新信任配置");
      ApkUpdateExecutor executor = executor(config);
      app.luoxianlv.update.Progress guardedProgress =
          (phase, completed, total, bytes, detail) -> {
            if (!cancellation.isCancelled())
              progress.onProgress(phase, completed, total, bytes, detail);
          };
      cancellation.check();
      result =
          request == null
              ? executor.resume(cancellation, guardedProgress)
              : executor.prepare(
                  new ApkUpdateExecutor.Request(
                      SignedDelivery.fromJson(new JSONObject(request.signedEnvelope)),
                      request.fullUrls,
                      request.versionCode,
                      request.sha256,
                      request.size),
                  cancellation,
                  guardedProgress);
      cancellation.check();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      cancellation.cancel();
      failure = new Cancellation.CancelledException();
    } catch (Exception failed) {
      failure = failed;
    } finally {
      watch.close();
      cancellation.awaitClosures();
      // 合并、连接与描述符真正离开后台 finally 后，才释放普通更新的优先权。
      current.compareAndSet(run, null);
      ACTIVE.decrementAndGet();
      main.post(changed);
    }
    if (cancellation.isCancelled()) {
      if (watch.deferred.get() && !run.userCancelled.get())
        throw new SharedUpdate.DeferredException();
      throw new Cancellation.CancelledException();
    }
    if (failure != null) throw failure;
    return new SharedUpdate.Result(
        result.file,
        result.target.versionCode,
        result.downloadedBytes,
        result.usedDelta,
        result.fallbackReason);
  }

  private boolean playbackIdle(Cancellation cancellation) throws Exception {
    cancellation.check();
    CompletableFuture<Boolean> result = new CompletableFuture<>();
    main.post(
        () -> {
          try {
            result.complete(idle.getAsBoolean());
          } catch (Throwable failed) {
            result.complete(false);
          }
        });
    boolean allowed = result.get(2, TimeUnit.SECONDS);
    cancellation.check();
    return allowed;
  }

  @Override
  public void cancel() {
    Run run = current.get();
    if (run != null) {
      run.userCancelled.set(true);
      run.cancellation.cancel();
    }
  }
}
