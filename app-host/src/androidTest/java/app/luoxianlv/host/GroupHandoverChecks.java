package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import app.luoxianlv.hot.*;
import app.luoxianlv.hot.contract.*;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 真实页面、播放和进程来源共用事务；使用同一 APK 的不同加载器，不冒充在线新版验证。 */
final class GroupHandoverChecks {
  private final Instrumentation runner;
  private final Activity home;
  private final Consumer<String> click;
  private NativeLoader.Prepared next;
  private BusinessFactory factory;
  private final Bootstrap.Source baseline;
  private final NativeAccessibilityService service;
  private Probe current;

  private GroupHandoverChecks(
      Instrumentation runner, Activity home, Consumer<String> click, NativeLoader.Prepared next)
      throws Exception {
    this.runner = runner;
    this.home = home;
    this.click = click;
    this.next = next;
    baseline = Bootstrap.source();
    service = (NativeAccessibilityService) field(PlaybackBridge.current(), "this$0");
    BusinessFactory[] value = new BusinessFactory[1];
    main(
        () -> {
          try {
            value[0] = next.factory();
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
        });
    factory = value[0];
  }

  static void run(Instrumentation runner, Activity home, Consumer<String> click) throws Exception {
    var application = home.getApplication();
    File root = new File(application.getNoBackupFilesDir(), "native-update");
    var loader =
        new NativeLoader(
            application,
            new ContentStore(root),
            new ContentQuarantine(new File(root, "quarantine")),
            1);
    new GroupHandoverChecks(
            runner, home, click, loader.prepareBaseline(BundledBaseline.prepare(application)))
        .run();
  }

  private void run() throws Exception {
    PlaybackPort original = PlaybackBridge.current();
    Bundle[] originalState = new Bundle[1];
    main(
        () -> {
          originalState[0] = session(original).snapshot();
          Bundle hidden = new Bundle();
          hidden.putBoolean("enabled", false);
          original.command("showFloating", hidden);
        });
    idle();
    try {
      check(next.classLoader() != baseline.prepared.classLoader(), "候选没有独立业务加载器");
      Probe stale = prepare(false);
      ready(stale);
      var candidateWork = holdCandidateWork();
      main(
          () -> {
            check(
                Bootstrap.source() == baseline && PlaybackBridge.current() == original,
                "准备期间提前发布了候选");
            mainHost().updateHostState(new Bundle());
            check(!stale.group.valid() && !stale.group.commit(), "整组接受了已变化的页面快照");
          });
      restore(stale, candidateWork);
      step("通过：整组准备不提前发布，失效页面快照阻止全部提交，预绘制候选工作实际退出后才完成取消");

      idle();
      AutoCloseable[] pin = new AutoCloseable[1];
      main(() -> pin[0] = mainHost().pinActive());
      Probe trial = prepare(false);
      ready(trial);
      main(
          () -> {
            check(trial.group.commit(), "整组提交失败");
            assertGeneration(next.classLoader());
            try {
              pin[0].close();
            } catch (Exception failure) {
              throw new AssertionError(failure);
            }
          });
      main(() -> check(field(mainHost(), "previous") != null, "旧任务释放租约后，整组健康确认前丢掉了恢复页面"));
      click.accept("设置");
      await(
          "设置页切换尚未完成",
          () ->
              onMain(
                  () ->
                      activePage(mainHost()).canReplace()
                          && "settings"
                              .equals(mainHost().save().getBundle("navigation").getString("tab"))));
      Bundle[] latest = new Bundle[1];
      main(
          () -> {
            latest[0] = mainHost().save().getBundle("navigation");
            Bundle speed = new Bundle();
            speed.putFloat("speed", 1.5f);
            PlaybackBridge.current().command("setSpeed", speed);
          });
      restore(trial);
      main(
          () -> {
            assertRetired(next);
            check(
                Bootstrap.source() == baseline && PlaybackBridge.current() == original,
                "整组回退没有恢复来源或播放控制身份");
            assertGeneration(baseline.prepared.classLoader());
            check(
                latest[0]
                    .getString("tab")
                    .equals(mainHost().save().getBundle("navigation").getString("tab")),
                "回退丢失试运行期间的最新页面位置：预期="
                    + latest[0].getString("tab")
                    + "，实际="
                    + mainHost().save().getBundle("navigation").getString("tab"));
            check(
                session(PlaybackBridge.current()).snapshot().getFloat("speed") == 1.5f,
                "回退丢失试运行期间的播放速度");
          });
      step("通过：整组真实提交，旧页面保留到确认，回退保留最新导航与播放速度");

      idle();
      Probe broken = prepare(true);
      ready(broken);
      main(
          () -> {
            boolean rejected = false;
            try {
              broken.group.commit();
            } catch (IllegalStateException expected) {
              rejected = true;
            }
            check(rejected, "页面激活故障没有中止整组提交");
            check(PlaybackBridge.current() != original, "故障注入未覆盖播放已经提交的边界");
          });
      await("页面激活错误没有报告", () -> broken.failure != null);
      restore(broken);
      main(
          () -> {
            assertRetired(next);
            check(
                Bootstrap.source() == baseline && PlaybackBridge.current() == original,
                "页面激活失败后留下了部分新版");
            assertGeneration(baseline.prepared.classLoader());
          });
      step("通过：播放已交接后页面激活出错，页面、播放及进程来源全部回退");

      Activity picker =
          runner.startActivitySync(
              new Intent()
                  .setClassName(home, "app.luoxianlv.ui.practice.WallpaperPickerActivity")
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        await(
            "第二个真实窗口未登记",
            () -> onMain(() -> Bootstrap.pages().size() == 2 && picker.hasWindowFocus()));
        idle();
        Probe multi = prepare(false);
        ready(multi);
        main(
            () -> {
              check(multi.group.commit(), "后台主页面和前台壁纸页没有同时提交");
              check(Bootstrap.pages().size() == 2, "多窗口检查遗漏后台主页面");
              assertGeneration(next.classLoader());
              for (var page : Bootstrap.pages()) {
                String type = activePage(page.host()).getClass().getName();
                check(
                    type.endsWith(
                        page.route().equals("main") ? ".MainPage" : ".WallpaperPickerPage"),
                    "交接丢失页面路由：" + page.route());
              }
              multi.group.finish();
            });
        await(
            "整组确认后旧代际没有退出",
            () ->
                onMain(
                    () -> {
                      if (multi.group.phase() == GroupHandover.Phase.FINISHING)
                        multi.group.finish();
                      return multi.group.retired();
                    }));
        main(
            () -> {
              check(session(original) == null, "整组确认后旧播放句柄仍保留业务实例");
              assertRetired(baseline.prepared);
              for (var page : Bootstrap.pages())
                check(field(page.host(), "previous") == null, "整组确认后仍保留没有租约的旧页面");
            });
        current = null;
        step("通过：后台主页面、前台壁纸页和播放同时交接，确认后释放旧组件");
      } finally {
        main(picker::finish);
      }
      await("返回后的主页面未恢复焦点", home::hasWindowFocus);
    } finally {
      try {
        if (current != null && onMain(() -> current.group.phase() != GroupHandover.Phase.FINISHED))
          restore(current);
      } finally {
        main(
            () -> {
              if (PlaybackBridge.current() == null) return;
              Bundle speed = new Bundle();
              speed.putFloat("speed", originalState[0].getFloat("speed"));
              PlaybackBridge.current().command("setSpeed", speed);
            });
      }
    }
  }

  private Probe prepare(boolean faulty) throws Exception {
    idle();
    // 退役加载器不可复活；每次独立事务都创建新的业务代际。
    var application = home.getApplication();
    File root = new File(application.getNoBackupFilesDir(), "native-update");
    next =
        new NativeLoader(
                application,
                new ContentStore(root),
                new ContentQuarantine(new File(root, "quarantine")),
                1)
            .prepareBaseline(BundledBaseline.prepare(application));
    Probe probe = new Probe();
    main(
        () -> {
          try {
            factory = next.factory();
          } catch (Exception error) {
            throw new AssertionError(error);
          }
          probe.group =
              GroupHandover.prepare(
                  next,
                  faulty ? new FaultFactory(factory) : factory,
                  Bootstrap.pages(),
                  service,
                  Bootstrap.publication(next, factory),
                  probe);
          check(probe.group != null, "安全点未建立整组事务");
        });
    current = probe;
    return probe;
  }

  private void ready(Probe probe) {
    await("整组候选未准备完成", () -> probe.ready || probe.failure != null);
    check(probe.failure == null, "整组准备失败：" + android.util.Log.getStackTraceString(probe.failure));
    main(() -> check(probe.group.valid(), "整组就绪时已经失效"));
  }

  private void restore(Probe probe) {
    restore(probe, null);
  }

  private void restore(Probe probe, java.util.concurrent.CountDownLatch workRelease) {
    var finished = new java.util.concurrent.atomic.AtomicBoolean();
    var failure = new AtomicReference<Throwable>();
    main(
        () ->
            probe.group.rollback(
                new NativePage.Ready() {
                  @Override
                  public void ready() {
                    finished.set(true);
                  }

                  @Override
                  public void failed(Throwable error) {
                    failure.set(error);
                    finished.set(true);
                  }
                }));
    try {
      if (workRelease != null) {
        SystemClock.sleep(300);
        check(!finished.get(), "尚未提交的候选工作仍在执行就宣布取消完成");
      }
    } finally {
      if (workRelease != null) workRelease.countDown();
    }
    await("整组回退没有结束", finished::get);
    if (failure.get() != null) throw new AssertionError("整组回退失败", failure.get());
    current = null;
  }

  private java.util.concurrent.CountDownLatch holdCandidateWork() throws Exception {
    var loader = next.classLoader();
    var jobs = Class.forName("app.luoxianlv.business.BusinessJobs", true, loader);
    var function = Class.forName("kotlin.jvm.functions.Function0", false, loader);
    var began = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    Object action =
        java.lang.reflect.Proxy.newProxyInstance(
            loader,
            new Class<?>[] {function},
            (proxy, method, arguments) -> {
              if (method.getName().equals("invoke")) {
                began.countDown();
                check(release.await(20, java.util.concurrent.TimeUnit.SECONDS), "候选工作未获释放");
                return Class.forName("kotlin.Unit", false, loader).getField("INSTANCE").get(null);
              }
              return null;
            });
    check(
        (Boolean)
            jobs.getMethod("thread", String.class, function)
                .invoke(jobs.getField("INSTANCE").get(null), "预绘制代际工作验收", action),
        "候选拒绝测试工作");
    check(began.await(5, java.util.concurrent.TimeUnit.SECONDS), "候选测试工作没有开始");
    return release;
  }

  private void idle() {
    await(
        "组件持续忙碌",
        () ->
            onMain(
                () ->
                    service.playbackCanReplace()
                        && !service.playbackRetiring()
                        && Bootstrap.canAutoActivate()
                        && !Bootstrap.pages().isEmpty()
                        && Bootstrap.pages().stream().allMatch(page -> page.host().canStage())));
  }

  private void assertGeneration(ClassLoader expected) {
    check(Bootstrap.source().prepared.classLoader() == expected, "进程来源代际不一致");
    check(session(PlaybackBridge.current()).getClass().getClassLoader() == expected, "播放代际不一致");
    for (var page : Bootstrap.pages())
      check(
          activePage(page.host()).getClass().getClassLoader() == expected,
          "页面代际不一致：" + page.route());
  }

  private void assertRetired(NativeLoader.Prepared prepared) {
    if (android.os.Build.VERSION.SDK_INT >= 30) {
      Object resources = field(prepared, "resources");
      var loader = (android.content.res.loader.ResourcesLoader) field(resources, "loader");
      check(loader.getProviders().isEmpty(), "退役模块仍持有资源提供器");
    }
    boolean rejected = false;
    try {
      prepared.context(home);
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    check(rejected, "退役模块仍能创建业务上下文");
  }

  private PageSwapHost mainHost() {
    return Bootstrap.pages().stream()
        .filter(page -> page.route().equals("main"))
        .findFirst()
        .orElseThrow()
        .host();
  }

  private static NativePage activePage(PageSwapHost host) {
    return (NativePage) field(field(host, "active"), "page");
  }

  private static NativePlaybackSession session(PlaybackPort port) {
    return (NativePlaybackSession) field(port, "session");
  }

  private static Object field(Object owner, String name) {
    try {
      var field = owner.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(owner);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private void main(Runnable action) {
    var failure = new AtomicReference<Throwable>();
    runner.runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable error) {
            failure.set(error);
          }
        });
    if (failure.get() != null) throw new AssertionError("主线程检查失败", failure.get());
  }

  private boolean onMain(BooleanSupplier condition) {
    boolean[] result = new boolean[1];
    main(() -> result[0] = condition.getAsBoolean());
    return result[0];
  }

  private void await(String message, BooleanSupplier condition) {
    long until = SystemClock.uptimeMillis() + 60000;
    while (!condition.getAsBoolean()) {
      check(SystemClock.uptimeMillis() < until, message);
      SystemClock.sleep(60);
    }
  }

  private void step(String text) {
    Bundle status = new Bundle();
    status.putString("stream", text + "\n");
    runner.sendStatus(0, status);
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static final class Probe implements GroupHandover.Listener {
    GroupHandover group;
    volatile boolean ready;
    volatile Throwable failure;

    @Override
    public void ready(GroupHandover group) {
      ready = true;
    }

    @Override
    public void failed(GroupHandover group, Throwable error, boolean contentFailure) {
      failure = error;
    }
  }

  private record FaultFactory(BusinessFactory delegate) implements BusinessFactory {
    @Override
    public NativePage page(String route) {
      return new FaultPage(delegate.page(route));
    }

    @Override
    public NativePlaybackSession playback() {
      return delegate.playback();
    }

    @Override
    public ProcessHooks process(Context context) {
      return delegate.process(context);
    }

    @Override
    public ForegroundPolicy foreground(Context context) {
      return delegate.foreground(context);
    }
  }

  private record FaultPage(NativePage delegate) implements NativePage {
    @Override
    public void attachHost(HostActions host) {
      delegate.attachHost(host);
    }

    @Override
    public View create(
        Context context, Bundle state, Bundle hostState, Events events, Ready ready) {
      return delegate.create(context, state, hostState, events, ready);
    }

    @Override
    public Bundle save() {
      return delegate.save();
    }

    @Override
    public boolean canReplace() {
      return delegate.canReplace();
    }

    @Override
    public void updateHostState(Bundle state) {
      delegate.updateHostState(state);
    }

    @Override
    public void lifecycle(int state) {
      if (state == RESUMED) throw new IllegalStateException("测试：页面恢复前台失败");
      delegate.lifecycle(state);
    }

    @Override
    public void close() {
      delegate.close();
    }

    @Override
    public void newIntent(Intent intent) {
      delegate.newIntent(intent);
    }

    @Override
    public boolean result(String key, int code, Intent data) {
      return delegate.result(key, code, data);
    }

    @Override
    public boolean back() {
      return delegate.back();
    }

    @Override
    public void windowTouch() {
      delegate.windowTouch();
    }

    @Override
    public void configurationChanged(Configuration configuration) {
      delegate.configurationChanged(configuration);
    }

    @Override
    public void finishing() {
      delegate.finishing();
    }
  }
}
