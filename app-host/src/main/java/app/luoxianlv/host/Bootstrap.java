package app.luoxianlv.host;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import app.luoxianlv.hot.ActivationController;
import app.luoxianlv.hot.BundledBaseline;
import app.luoxianlv.hot.ContentQuarantine;
import app.luoxianlv.hot.ContentStore;
import app.luoxianlv.hot.GroupActivation;
import app.luoxianlv.hot.GroupHandover;
import app.luoxianlv.hot.NativeAccessibilityService;
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
  private static volatile ProcessHooks process;
  private static volatile Throwable failure;
  private static boolean started, finished;
  private static Application application;
  private static final LinkedHashMap<PageSwapHost, String> PAGES = new LinkedHashMap<>();
  private static NativeAccessibilityService playback;
  private static GroupActivation activation;
  private static boolean updateBlocked;
  private static HostStartup startup;
  private static HostUpdates updates;
  private static volatile boolean businessStopped, recoveryAvailable;
  private static final ArrayList<BusinessActivity> WINDOWS = new ArrayList<>();

  static void windowOpened(BusinessActivity activity) {
    WINDOWS.add(activity);
  }

  static void windowClosed(BusinessActivity activity) {
    WINDOWS.remove(activity);
  }

  static boolean businessStopped() {
    return businessStopped;
  }

  static boolean recoveryAvailable() {
    return !businessStopped || recoveryAvailable;
  }

  static boolean startedForRecoveryCheck() {
    return started;
  }

  static void pageOpened(PageSwapHost page, String route) {
    PAGES.put(page, route);
    if (activation != null) activation.pageOpened(new GroupHandover.Page(page, route));
    usageChanged();
  }

  static void pageClosed(PageSwapHost page) {
    PAGES.remove(page);
    if (activation != null) activation.pageClosed();
    usageChanged();
  }

  public static void playbackOpened(NativeAccessibilityService service) {
    playback = service;
    if (activation != null) activation.playbackOpened(service);
    usageChanged();
  }

  public static void playbackClosed(NativeAccessibilityService service) {
    if (playback == service) playback = null;
    usageChanged();
  }

  public static void usageChanged() {
    if (activation != null) activation.usageChanged();
    if (updates != null) updates.usageChanged();
  }

  static boolean inUse() {
    return PAGES.keySet().stream().anyMatch(PageSwapHost::inUse) || playbackInUse();
  }

  static boolean playbackInUse() {
    return playback != null && playback.playbackInUse();
  }

  static boolean playbackReady() {
    return playback != null && (playback.playbackInUse() || playback.playbackCanReplace());
  }

  static boolean canAutoActivate() {
    return source != null
        && activation == null
        && (updates == null || !updates.coldPending())
        && !updateBlocked
        && process != null
        && process.canReplace()
        && (!PAGES.isEmpty() || playback != null)
        && PAGES.keySet().stream().allMatch(PageSwapHost::canStage)
        && (playback == null || (playback.playbackCanReplace() && !playback.playbackRetiring()));
  }

  static boolean diagnosticsAllowed() {
    ProcessHooks selected = process;
    return selected != null && selected.diagnosticsAllowed();
  }

  static boolean supportsLiveWork() {
    return process != null && process.supportsRetirement();
  }

  public static boolean initialCreation(Runnable create) {
    if (businessStopped) throw new IllegalStateException("故障业务已停用，须重新打开恢复", failure);
    if (updates != null) return updates.initialCreation(create);
    create.run();
    return true;
  }

  public static void componentFailed(Throwable error) {
    if (businessStopped) return;
    if (updates != null && updates.coldPending()) {
      updateBlocked = true;
      updates.failCold(error, HostUpdates.contentFailure(error));
      return;
    }
    if (activation != null) {
      activation.stop(
          error,
          activation.phase() == GroupActivation.Phase.OBSERVING
              || activation.phase() == GroupActivation.Phase.FINALIZING);
      return;
    }
    Source selected = source;
    stopBusiness(error);
    var recovery =
        Executors.newSingleThreadExecutor(
            task -> {
              Thread thread = new Thread(task, "native-recovery");
              thread.setDaemon(true);
              return thread;
            });
    recovery.execute(
        () -> {
          try {
            if (startup != null
                && selected != null
                && selected.prepared.manifest != null
                && startup.journal.state().stable.equals(selected.prepared.manifest.snapshotId))
              startup.journal.stableContentFailed(
                  selected.prepared.manifest, startup.quarantine, startup.config.hostContract);
            if (startup != null
                && startup.journal.state().phase
                    != app.luoxianlv.hot.ActivationJournal.Phase.STABLE)
              throw new IllegalStateException("恢复事务尚未完成，不能重启");
            MAIN.post(() -> recoveryPersisted(true));
          } catch (Throwable persistence) {
            Log.e("原生宿主", "故障恢复选择未能保存，不能重启执行旧候选", persistence);
          } finally {
            recovery.shutdown();
          }
        });
  }

  /** 先撤掉输入与前台策略，再关闭业务实例；持久恢复确认由调用方后台完成。 */
  static void stopBusiness(Throwable error) {
    if (businessStopped) return;
    businessStopped = true;
    recoveryAvailable = false;
    updateBlocked = true;
    failure = error;
    if (updates != null) updates.stopScheduling();
    NativeAccessibilityService service = playback;
    try {
      if (service != null) service.stopBusiness();
    } catch (Throwable closing) {
      Log.e("原生宿主", "故障播放关闭失败，仍停止其余组件", closing);
    }
    app.luoxianlv.service.PlaybackForegroundService.stop();
    for (BusinessActivity window : new ArrayList<>(WINDOWS)) {
      try {
        window.stopBusiness(error);
      } catch (Throwable closing) {
        Log.e("原生宿主", "故障页面关闭失败，仍停止其余组件", closing);
      }
    }
    try {
      if (process != null) process.close();
    } catch (Throwable closing) {
      Log.w("原生宿主", "故障业务关闭失败，恢复须使用新进程", closing);
    }
  }

  static void recoveryPersisted(boolean saved) {
    recoveryAvailable = saved;
  }

  /** 在线下载器取得当前许可后调用；同一进程只接受一组，恢复或收尾失败须先完成宿主恢复。 */
  public static GroupActivation activate(
      ActivationController controller,
      ActivationController.Ticket ticket,
      NativeLoader.Prepared prepared,
      java.util.concurrent.Executor worker,
      GroupActivation.Listener listener) {
    if (Looper.myLooper() != Looper.getMainLooper() || activation != null || updateBlocked)
      throw new IllegalStateException("当前进程不能开始新的整组更新");
    Source previous = source();
    activation =
        new GroupActivation(
            controller,
            ticket,
            prepared,
            new GroupActivation.Environment() {
              @Override
              public boolean workSafe() {
                return process != null && process.canReplace();
              }

              @Override
              public List<GroupHandover.Page> pages() {
                return Bootstrap.pages();
              }

              @Override
              public NativeAccessibilityService playback() {
                return playback;
              }

              @Override
              public boolean inUse() {
                return PAGES.keySet().stream().anyMatch(PageSwapHost::inUse)
                    || (playback != null && playback.playbackInUse());
              }

              @Override
              public GroupHandover.Publication publication(
                  NativeLoader.Prepared value, BusinessFactory factory) {
                if (source != previous) throw new IllegalStateException("准备期间业务来源已改变");
                return Bootstrap.publication(value, factory);
              }

              @Override
              public NativeLoader.Prepared recoverySource() {
                return previous.prepared;
              }

              @Override
              public void beforeHealthy(NativeLoader.Prepared prepared, String attempt)
                  throws Exception {
                if (startup != null) startup.recordExecution(prepared.manifest, attempt);
              }

              @Override
              public BusinessFactory recoveryFactory() {
                return previous.factory;
              }
            },
            worker,
            new GroupActivation.Listener() {
              @Override
              public void exposed() {
                listener.exposed();
              }

              @Override
              public void finished(GroupActivation.Result result, Throwable error) {
                updateBlocked =
                    result == GroupActivation.Result.RECOVERY_FAILED
                        || result == GroupActivation.Result.CLEANUP_FAILED;
                activation = null;
                if (updateBlocked) {
                  stopBusiness(error == null ? new IllegalStateException("整组恢复或退出失败") : error);
                  recoveryPersisted(
                      startup != null
                          && startup.journal.state().phase
                              == app.luoxianlv.hot.ActivationJournal.Phase.STABLE);
                }
                listener.finished(result, error);
              }
            });
    return activation;
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
    // 预绘制页也会启动 I/O；先取得本代退出钩子，但只在正式提交时初始化进程业务。
    ProcessHooks candidateProcess = factory.process(prepared.context(application));
    return new GroupHandover.Publication() {
      private final Source candidate = new Source(prepared, factory);
      private final NativeAccessibilityService previousPlayback = playback;
      private boolean oldFrozen;
      private boolean candidateClosed;

      private void closeCandidate() {
        if (candidateProcess == null || candidateClosed) return;
        candidateClosed = true;
        candidateProcess.close();
      }

      @Override
      public boolean canCommit() {
        return !oldFrozen && previousProcess.canReplace();
      }

      @Override
      public void selectCandidate() {
        if (source != previous) throw new IllegalStateException("业务来源已改变");
        if (!candidateProcess.supportsRetirement())
          throw new app.luoxianlv.hot.contract.HandoverDeferred("候选业务缺少代际退出协议", true);
        if (!previousProcess.quiesce())
          throw new app.luoxianlv.hot.contract.HandoverDeferred("旧代际还有后台工作");
        oldFrozen = true;
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
          closeCandidate();
        } finally {
          previousProcess.resumeWork();
          oldFrozen = false;
          previousProcess.initialize();
        }
      }

      @Override
      public void finish() {
        previousProcess.close();
      }

      @Override
      public boolean released() {
        if (!previousProcess.released()
            || (previousPlayback != null && previousPlayback.playbackRetiring())) return false;
        previous.prepared.closeCallbacks();
        return true;
      }

      @Override
      public void discardCandidate() {
        closeCandidate();
      }

      @Override
      public boolean candidateReleased() {
        if ((candidateProcess != null && !candidateProcess.released())
            || (playback != null && playback.playbackRetiring())
            || (previousPlayback != null && previousPlayback.playbackRetiring())) return false;
        prepared.closeCallbacks();
        return true;
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
            try {
              startup = HostStartup.open(application);
            } catch (Exception invalidState) {
              updateBlocked = true;
              Log.e("原生宿主", "热更状态不可用，保留信任下限并使用安装包恢复组合", invalidState);
            }
            NativeLoader loader =
                startup == null
                    ? new NativeLoader(
                        application,
                        new ContentStore(root),
                        new ContentQuarantine(new File(root, "quarantine")),
                        1)
                    : startup.loader;
            if (startup != null && startup.config.automatic && !updateBlocked) {
              try {
                updates = new HostUpdates(application, startup);
              } catch (Exception invalidQueue) {
                updateBlocked = true;
                Log.e("原生宿主", "自动热更状态不可用，保留已有组合", invalidQueue);
              }
            }
            NativeLoader.Prepared cold = updates == null ? null : updates.prepareCold();
            NativeLoader.Prepared stable =
                cold != null || startup == null ? null : startup.prepareStable();
            NativeLoader.Prepared prepared =
                cold != null
                    ? cold
                    : stable != null
                        ? stable
                        : loader.prepareBaseline(BundledBaseline.prepare(application));
            if (startup != null && prepared.manifest != null)
              startup.recordExecution(
                  prepared.manifest,
                  cold != null
                      ? startup.journal.state().attempt
                      : startup.journal.state().stableAttempt);
            MAIN.post(() -> initializePrepared(prepared, worker));
          } catch (Throwable error) {
            MAIN.post(
                () -> {
                  failure = error;
                  complete();
                  worker.shutdown();
                });
          }
        });
  }

  private static void initializePrepared(
      NativeLoader.Prepared prepared, java.util.concurrent.ExecutorService worker) {
    try {
      boolean created =
          initialCreation(
              () -> {
                try {
                  BusinessFactory factory = prepared.factory();
                  process = factory.process(prepared.context(application));
                  process.initialize();
                  source = new Source(prepared, factory);
                } catch (Exception error) {
                  throw new IllegalStateException("业务初始化失败", error);
                }
              });
      if (!created) {
        MAIN.postDelayed(() -> initializePrepared(prepared, worker), 16);
        return;
      }
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
      if (updates != null && updates.coldPending()) {
        updates.failCold(error, HostUpdates.contentFailure(error));
      } else if (startup != null && prepared.manifest != null) {
        worker.execute(
            () -> {
              try {
                startup.journal.stableContentFailed(
                    prepared.manifest, startup.quarantine, startup.config.hostContract);
              } catch (Throwable recoveryFailure) {
                error.addSuppressed(recoveryFailure);
                updateBlocked = true;
              } finally {
                MAIN.post(Bootstrap::complete);
                worker.shutdown();
              }
            });
        return;
      }
    }
    complete();
    if (updates != null) updates.startColdObservation();
    worker.shutdown();
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
    if (businessStopped) throw new IllegalStateException("当前业务已停用，等待新进程恢复", failure);
    if (source == null) throw new IllegalStateException("业务尚未准备完成", failure);
    return source;
  }

  static HostStartup startupState() {
    return startup;
  }

  /** 未捕获异常路径只保存宿主拥有的运行身份；不调用故障业务的诊断或持久化代码。 */
  public static String recordCrash(Thread thread, Throwable error) throws Exception {
    HostStartup state = startup;
    if (state == null) return "";
    Source selected = source;
    if (selected != null && selected.prepared.manifest != null)
      state.ensureExecution(selected.prepared.manifest);
    if (state.running != null) {
      state.execution.crashed(
          state.running, android.os.Process.myPid(), System.currentTimeMillis());
      return "；热更组合=" + state.running.snapshot;
    }
    return "";
  }

  public static void trim(int level) {
    if (process != null) process.trimMemory(level);
  }
}
