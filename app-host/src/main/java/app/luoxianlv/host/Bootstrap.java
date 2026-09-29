package app.luoxianlv.host;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import app.luoxianlv.hot.BundledBaseline;
import app.luoxianlv.hot.ContentQuarantine;
import app.luoxianlv.hot.ContentStore;
import app.luoxianlv.hot.GroupHandover;
import app.luoxianlv.hot.NativeLoader;
import app.luoxianlv.hot.PageSwapHost;
import app.luoxianlv.hot.contract.BusinessFactory;
import app.luoxianlv.hot.contract.ProcessHooks;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Executors;

/** 先后台校验 APK 恢复组合，再在主线程安装业务；页面和服务共用同一次准备。 */
public final class Bootstrap {
  public static final class Source {
    public final NativeLoader.Prepared prepared;
    public final BusinessFactory factory;

    private Source(NativeLoader.Prepared prepared, BusinessFactory factory) {
      this.prepared = prepared;
      this.factory = factory;
    }
  }

  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final ArrayList<Waiting> WAITING = new ArrayList<>();
  private static volatile Source source;
  private static ProcessHooks process;
  private static volatile Throwable failure;
  private static boolean started, finished;
  private static Application application;
  private static final LinkedHashMap<PageSwapHost, String> PAGES = new LinkedHashMap<>();

  static void pageOpened(PageSwapHost page, String route) {
    PAGES.put(page, route);
  }

  static void pageClosed(PageSwapHost page) {
    PAGES.remove(page);
  }

  public static List<GroupHandover.Page> pages() {
    if (Looper.myLooper() != Looper.getMainLooper())
      throw new IllegalStateException("页面注册表必须在主线程读取");
    var result = new ArrayList<GroupHandover.Page>();
    PAGES.forEach((page, route) -> result.add(new GroupHandover.Page(page, route)));
    return List.copyOf(result);
  }

  private Bootstrap() {}

  /** 仅为已验证候选建立发布动作；构造动作本身不初始化业务、不改变当前来源。 */
  public static GroupHandover.Publication publication(
      NativeLoader.Prepared prepared, BusinessFactory factory) {
    if (Looper.myLooper() != Looper.getMainLooper())
      throw new IllegalStateException("业务来源切换必须在主线程");
    Source previous = source();
    ProcessHooks previousProcess = process;
    return new GroupHandover.Publication() {
      private final Source candidate = new Source(prepared, factory);
      private ProcessHooks candidateProcess;

      @Override
      public void selectCandidate() {
        if (source != previous) throw new IllegalStateException("业务来源已改变");
        candidateProcess = factory.process(prepared.context(application));
        source = candidate;
        process = candidateProcess;
        app.luoxianlv.service.PlaybackForegroundService.businessChanged();
        candidateProcess.initialize();
      }

      @Override
      public void restorePrevious() {
        if (source != candidate && source != previous)
          throw new IllegalStateException("恢复来源已经被其他事务替换");
        source = previous;
        process = previousProcess;
        app.luoxianlv.service.PlaybackForegroundService.businessChanged();
        try {
          if (candidateProcess != null) candidateProcess.close();
        } finally {
          previousProcess.initialize();
        }
      }

      @Override
      public void finish() {
        previousProcess.close();
      }
    };
  }

  public static void start(Application application) {
    if (Looper.myLooper() != Looper.getMainLooper())
      throw new IllegalStateException("业务启动必须在主线程登记");
    if (started) return;
    started = true;
    Bootstrap.application = application;
    var worker =
        Executors.newSingleThreadExecutor(
            task -> {
              Thread thread = new Thread(task, "native-baseline");
              thread.setDaemon(true);
              return thread;
            });
    worker.execute(
        () -> {
          try {
            File root = new File(application.getNoBackupFilesDir(), "native-update");
            NativeLoader loader =
                new NativeLoader(
                    application,
                    new ContentStore(root),
                    new ContentQuarantine(new File(root, "quarantine")),
                    1);
            NativeLoader.Prepared prepared =
                loader.prepareBaseline(BundledBaseline.prepare(application));
            MAIN.post(
                () -> {
                  try {
                    BusinessFactory factory = prepared.factory();
                    process = factory.process(prepared.context(application));
                    process.initialize();
                    source = new Source(prepared, factory);
                  } catch (Throwable error) {
                    failure = error;
                    if (process != null) {
                      try {
                        process.close();
                      } catch (Throwable closing) {
                        error.addSuppressed(closing);
                      }
                      process = null;
                    }
                  }
                  complete();
                });
          } catch (Throwable error) {
            MAIN.post(
                () -> {
                  failure = error;
                  complete();
                });
          } finally {
            worker.shutdown();
          }
        });
  }

  private static void complete() {
    finished = true;
    if (failure != null) Log.e("原生宿主", "内置业务准备失败", failure);
    var callbacks = new ArrayList<>(WAITING);
    WAITING.clear();
    for (Waiting callback : callbacks) {
      try {
        callback.fire();
      } catch (Throwable error) {
        Log.e("原生宿主", "业务就绪监听失败", error);
      }
    }
  }

  public static AutoCloseable ready(Runnable callback) {
    if (Looper.myLooper() != Looper.getMainLooper())
      throw new IllegalStateException("组件准备监听必须在主线程登记");
    Waiting waiting = new Waiting(callback);
    if (finished) waiting.fire();
    else WAITING.add(waiting);
    return waiting;
  }

  private static final class Waiting implements AutoCloseable {
    private Runnable callback;

    Waiting(Runnable callback) {
      this.callback = callback;
    }

    void fire() {
      Runnable action = callback;
      callback = null;
      if (action != null) action.run();
    }

    @Override
    public void close() {
      callback = null;
      WAITING.remove(this);
    }
  }

  public static Source source() {
    if (source == null) throw new IllegalStateException("业务尚未准备完成", failure);
    return source;
  }

  public static void trim(int level) {
    if (process != null) process.trimMemory(level);
  }
}
