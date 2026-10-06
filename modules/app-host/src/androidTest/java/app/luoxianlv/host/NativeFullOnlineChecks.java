package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import app.luoxianlv.hot.*;
import app.luoxianlv.hot.contract.NativePlaybackSession;
import app.luoxianlv.hot.contract.PlaybackBridge;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** 显式本机验收：已安装完整 APP → 不同业务 APK → 整组许可/首帧/真实观察。 */
final class NativeFullOnlineChecks {
  private static final String BADGE = "app.luoxianlv.hot.probe.HotProbeFactory$NewNativeBadge";
  private final Instrumentation runner;

  private NativeFullOnlineChecks(Instrumentation runner) {
    this.runner = runner;
  }

  static void run(
      Instrumentation runner, Activity home, String server, boolean rollback, boolean persist)
      throws Exception {
    new NativeFullOnlineChecks(runner).run(home, URI.create(server), rollback, persist);
  }

  private void run(Activity home, URI origin, boolean rollback, boolean persist) throws Exception {
    check(origin.getHost().equals("127.0.0.1") && origin.getScheme().equals("http"), "只允许显式本机热更接口");
    var context = runner.getTargetContext();
    check(context.getPackageName().equals("app.luoxianlv.debug"), "在线验收不能运行在正式包");
    File fixture = new File(context.getFilesDir(), "native-full-test");
    File area = new File(context.getNoBackupFilesDir(), "native-online-test/" + UUID.randomUUID());
    check(area.mkdirs(), "无法创建独立在线验收目录");
    var root =
        new HotSignatures.PublicKey(
            StrictJson.object(Files.readAllBytes(new File(fixture, "root.public.json").toPath())));
    HostStartup startup = persist ? Bootstrap.startupState() : null;
    if (persist) {
      check(
          !rollback && startup != null && startup.config.root.id.equals(root.id),
          "跨进程验收必须使用安装包内测试根");
      check(
          startup.config.environment.equals("test") && startup.config.origin.equals(origin),
          "跨进程验收配置范围不符");
      check(startup.journal.state().stable.isEmpty(), "跨进程验收要求未激活的独立基线");
    }
    var store = persist ? startup.store : new ContentStore(new File(area, "store"));
    var journal = persist ? startup.journal : new ActivationJournal(new File(area, "state"));
    var quarantine =
        persist ? startup.quarantine : new ContentQuarantine(new File(area, "quarantine"));
    var trust = persist ? startup.trust : new TrustStore(new File(area, "trust"), root);
    var controller =
        new ActivationController(journal, trust, quarantine, 1, SystemClock::elapsedRealtime);
    ContentStore.Snapshot baseline;
    try (var pack =
        new HotPackage(
            new File(fixture, "base.lxhp"),
            new HotPackage.Policy(
                root,
                context.getPackageName(),
                "test",
                1,
                1,
                Instant.now(),
                Collections.emptySet(),
                null,
                null))) {
      baseline = store.prepare(pack);
    }
    // 仅为已安装 APK、已经启动成功的基础组合布置日志；不是远端候选的健康观察。
    try (var input = context.getAssets().open("baseline/index.json")) {
      var bundled = StrictJson.object(input.readAllBytes());
      check(
          baseline.manifest.runtime.sha256.equals(bundled.object("runtime").string("sha256"))
              && baseline.manifest.business.sha256.equals(
                  bundled.object("business").string("sha256")),
          "测试基线与实际已安装 APK 不一致");
    }
    String initial =
        journal.begin(
            baseline.manifest.snapshotId,
            1,
            1,
            android.os.Process.myPid(),
            System.currentTimeMillis());
    journal.firstFrame(initial);
    journal.healthy(initial, 60000);
    if (persist) {
      try (var input = context.getAssets().open("hot/config.json")) {
        // 恢复基础组合已由安装 APK 逐项证明；保存根授权，不从外部替换安装包信任根。
        trust.accept(
            Files.readAllBytes(new File(baseline.directory, "trust.json").toPath()),
            Files.readAllBytes(new File(baseline.directory, "trust.sig.json").toPath()),
            journal,
            Instant.now());
      }
    }
    ClassLoader old = Bootstrap.source().prepared.classLoader();
    boolean absent = false;
    try {
      Class.forName(BADGE, false, old);
    } catch (ClassNotFoundException expected) {
      absent = true;
    }
    check(absent, "基线已经含有待下载的新组件");
    Bundle[] originalPlayback = new Bundle[1];
    main(
        () -> {
          originalPlayback[0] = playbackSession().snapshot();
          Bundle hidden = new Bundle();
          hidden.putBoolean("enabled", false);
          PlaybackBridge.current().command("showFloating", hidden);
        });

    var identity =
        InstallationIdentity.open(new File(area, "installation"), context.getPackageName(), "test");
    var api =
        new HotApiClient(
            origin,
            identity,
            1,
            HotSignatures.hash(
                "full-native-host-contract-1".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            true);
    var budget = new DownloadBudget(new File(area, "budget"));
    var downloader =
        new ObjectDownloader(
            new File(context.getExternalFilesDir(null), "native-online-test/" + area.getName()),
            budget);
    var client =
        new UpdateClient(
            api,
            root,
            store,
            trust,
            journal,
            controller,
            quarantine,
            downloader,
            budget,
            Collections.emptySet(),
            SystemClock::elapsedRealtime);
    long receivedBefore = downloader.receivedBytes();
    var candidate = client.prepare(1, () -> false, () -> false);
    check(
        candidate != null
            && !candidate.snapshot.manifest.business.sha256.equals(
                baseline.manifest.business.sha256),
        "接口没有返回不同内容的完整业务 APK");
    check(
        !candidate.needsRestart(baseline.manifest.runtime.sha256, baseline.manifest.runtimeAbi),
        "实际共享运行时不一致");
    check(downloader.receivedBytes() > receivedBefore, "候选没有经过实际网络下载");
    var ticket = client.authorize(candidate, 1, android.os.Process.myPid(), () -> false);
    var next =
        new NativeLoader(context, store, quarantine, 1)
            .prepare(candidate.snapshot, journal.state());
    check(next.classLoader().getParent() == old.getParent(), "完整在线更新重复加载共享运行时");
    Class.forName(BADGE, false, next.classLoader());
    step("通过：不同完整业务 APK 已下载、验签并取得当前激活许可，新组件不在基线中");

    var worker = Executors.newSingleThreadExecutor();
    var activation = new AtomicReference<GroupActivation>();
    var outcome = new AtomicReference<GroupActivation.Result>();
    var error = new AtomicReference<Throwable>();
    var exposed = new java.util.concurrent.atomic.AtomicLong();
    try {
      main(
          () ->
              activation.set(
                  Bootstrap.activate(
                      controller,
                      ticket,
                      next,
                      worker,
                      new GroupActivation.Listener() {
                        @Override
                        public void exposed() {
                          exposed.set(SystemClock.elapsedRealtime());
                        }

                        @Override
                        public void finished(GroupActivation.Result result, Throwable problem) {
                          outcome.set(result);
                          error.set(problem);
                        }
                      })));
      await("整组在线激活没有曝光", 60000, () -> exposed.get() != 0 || outcome.get() != null);
      check(exposed.get() != 0, "整组在线激活失败：" + error.get());
      main(
          () -> {
            check(badge(home.getWindow().getDecorView()) != null, "下载的新原生组件没有显示");
            check(
                badge(home.getWindow().getDecorView()).getClass().getClassLoader()
                    == next.classLoader(),
                "新组件来自错误代际");
            check(Bootstrap.source().prepared == next, "新页面已显示但进程来源没有一起切换");
          });

      Activity picker =
          runner.startActivitySync(
              new Intent()
                  .setClassName(context, "app.luoxianlv.ui.practice.WallpaperPickerActivity")
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        await(
            "试运行中新开的窗口未加入",
            15000,
            () -> onMain(() -> Bootstrap.pages().size() == 2 && picker.hasWindowFocus()));
        main(
            () -> {
              for (var page : Bootstrap.pages())
                check(pageClassLoader(page.host()) == next.classLoader(), "新开窗口没有使用候选");
            });
        if (rollback) {
          // 真实退后台暂停观察，留出系统重新连接的时间，不靠缩短或伪造健康时钟制造失败场景。
          main(
              () ->
                  picker.startActivity(
                      new Intent(Intent.ACTION_MAIN)
                          .addCategory(Intent.CATEGORY_HOME)
                          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
          await(
              "回退场景没有退到后台",
              15000,
              () ->
                  onMain(() -> Bootstrap.pages().stream().noneMatch(page -> page.host().inUse())));
          NativePlaybackHost reconnected = reconnectPlayback();
          main(
              () -> {
                check(
                    playbackSession().getClass().getClassLoader() == next.classLoader(),
                    "试运行重连没有使用候选播放业务");
                Bundle speed = new Bundle();
                speed.putFloat("speed", 1.25f);
                PlaybackBridge.current().command("setSpeed", speed);
                Bundle position = new Bundle();
                position.putLong("position", 1200);
                PlaybackBridge.current().command("seek", position);
                Bootstrap.pages().stream()
                    .filter(page -> page.route().equals("wallpaper.picker"))
                    .findFirst()
                    .orElseThrow()
                    .host()
                    .failActive(new IllegalStateException("测试注入：试运行新窗口业务失败"));
              });
          await("新窗口故障没有完成整组回退", 40000, () -> outcome.get() != null);
          check(
              outcome.get() == GroupActivation.Result.ROLLED_BACK,
              "在线回退失败：" + outcome.get() + ", " + error.get());
          main(
              () -> {
                check(Bootstrap.source().prepared.classLoader() == old, "进程来源未恢复旧组");
                for (var page : Bootstrap.pages())
                  check(pageClassLoader(page.host()) == old, "试运行中新开的页面未恢复旧实现");
                check(playbackOwner() == reconnected, "回退再次重连了系统无障碍身份");
                check(playbackSession().getClass().getClassLoader() == old, "试运行中新连接的播放业务未恢复旧实现");
                Bundle latest = playbackSession().snapshot();
                check(
                    latest.getFloat("speed") == 1.25f && latest.getLong("position") == 1200,
                    "新连接回退丢失了最新速度或进度");
                check(badge(home.getWindow().getDecorView()) == null, "回退后仍显示候选组件");
              });
          check(journal.state().stable.equals(baseline.manifest.snapshotId), "回退后的磁盘选择仍指向候选");
          boolean isolated = false;
          try {
            quarantine.requireAllowed(candidate.snapshot.manifest, 1);
          } catch (IllegalArgumentException expected) {
            isolated = true;
          }
          check(isolated, "明确的模块故障未进入内容隔离");
          api.report(
              List.of(
                  new HealthEvent(ticket.attemptId, 1, "activated", "full_group_exposed"),
                  new HealthEvent(
                      ticket.attemptId, 2, "load_failed", "injected_new_window_failure"),
                  new HealthEvent(ticket.attemptId, 3, "recovered", "whole_group_restored")),
              true);
          var report =
              new org.json.JSONObject()
                  .put("passed", true)
                  .put("productionTouched", false)
                  .put("baseline", baseline.manifest.snapshotId)
                  .put("target", candidate.snapshot.manifest.snapshotId)
                  .put("injectedFailure", true)
                  .put("newWindowRestored", true)
                  .put("newPlaybackConnectionRestored", true)
                  .put("latestPlaybackStateRetained", true)
                  .put("contentIsolated", true);
          Files.write(
              new File(context.getFilesDir(), "native-full-rollback-report.json").toPath(),
              report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
          context.startActivity(
              new Intent()
                  .setClassName(context, "app.luoxianlv.ui.practice.WallpaperPickerActivity")
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
          await("回退后的旧版壁纸页无法恢复前台", 15000, picker::hasWindowFocus);
          step("通过：完整 APP 在线回退，试运行中新窗口和重连播放恢复旧组并保留最新状态");
          return;
        }
      } finally {
        main(picker::finish);
      }
      await("关闭壁纸页后首页未恢复", 15000, home::hasWindowFocus);
      check(outcome.get() == null, "正常关闭窗口被误判为候选故障：" + error.get());
      step("通过：试运行中新开壁纸页采用候选，关闭窗口没有取消整组更新");

      main(
          () ->
              home.startActivity(
                  new Intent(Intent.ACTION_MAIN)
                      .addCategory(Intent.CATEGORY_HOME)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
      await(
          "退到后台后使用计时未停",
          15000,
          () -> onMain(() -> Bootstrap.pages().stream().noneMatch(page -> page.host().inUse())));
      long pausedAt = SystemClock.elapsedRealtime();
      SystemClock.sleep(12000);
      context.startActivity(
          new Intent()
              .setClassName(context, "app.luoxianlv.MainActivity")
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
      await("前台恢复没有回到原窗口", 15000, home::hasWindowFocus);
      long paused = SystemClock.elapsedRealtime() - pausedAt;
      while (SystemClock.elapsedRealtime() - exposed.get() < 61000 && outcome.get() == null)
        SystemClock.sleep(250);
      check(
          outcome.get() == null && journal.state().phase == ActivationJournal.Phase.TRIAL,
          "将后台等待错误计入了 60 秒有效观察：" + outcome.get() + ", " + error.get());
      step("通过：后台间隔没有计入健康观察，墙钟超过 60 秒仍保留旧组");
      await("真实前台观察完成后没有统一确认", 45000, () -> outcome.get() != null);
      check(
          outcome.get() == GroupActivation.Result.STABLE && error.get() == null,
          "整组在线更新没有稳定：" + outcome.get() + ", " + error.get());
      check(journal.state().stable.equals(candidate.snapshot.manifest.snapshotId), "稳定磁盘指针没有更新");
      long observedWall = SystemClock.elapsedRealtime() - exposed.get();
      var receipt =
          api.report(
              List.of(
                  new HealthEvent(ticket.attemptId, 1, "activated", "full_group_exposed"),
                  new HealthEvent(ticket.attemptId, 2, "module_used", "main"),
                  new HealthEvent(ticket.attemptId, 3, "module_used", "wallpaper_picker"),
                  new HealthEvent(ticket.attemptId, 4, "healthy", "foreground_observed")),
              true);
      check(receipt != null && !receipt.autoPaused, "本机整组健康回报未被接受");
      var report =
          new org.json.JSONObject()
              .put("passed", true)
              .put("productionTouched", false)
              .put("baseline", baseline.manifest.snapshotId)
              .put("target", candidate.snapshot.manifest.snapshotId)
              .put("newNativeClass", BADGE)
              .put("runtimeReused", true)
              .put("windowAddedDuringTrial", true)
              .put("foregroundObservationOnly", true)
              .put("pausedWallMillis", paused)
              .put("trialWallMillis", observedWall)
              .put("healthReportAccepted", true);
      report.put("persistentStartupState", persist);
      Files.write(
          new File(context.getFilesDir(), "native-full-online-report.json").toPath(),
          report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var screenshot =
          runner
              .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
              .takeScreenshot();
      if (screenshot != null)
        try (var output =
            new FileOutputStream(new File(context.getFilesDir(), "native-full-online.png"))) {
          screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } finally {
          if (screenshot != null) screenshot.recycle();
        }
      step("通过：完整 APP 真实在线整组更新、前台有效观察与本机健康回报");
    } finally {
      if (activation.get() != null && outcome.get() == null) {
        main(() -> activation.get().stop(new IllegalStateException("本机验收结束，取消未完成试运行"), false));
        await("结束验收时整组恢复未结束", 35000, () -> outcome.get() != null);
      }
      main(
          () -> {
            if (PlaybackBridge.current() == null) return;
            Bundle speed = new Bundle();
            speed.putFloat("speed", originalPlayback[0].getFloat("speed"));
            PlaybackBridge.current().command("setSpeed", speed);
            Bundle position = new Bundle();
            position.putLong("position", originalPlayback[0].getLong("position"));
            PlaybackBridge.current().command("seek", position);
          });
      worker.shutdown();
    }
  }

  private NativePlaybackHost reconnectPlayback() throws Exception {
    var previous = PlaybackBridge.current();
    // 播放会话归普通宿主所有，重新绑定无障碍不再销毁或重建播放器。
    main(() -> playbackOwner().reconnect());
    await(
        "候选播放会话未重新连接",
        20000,
        () -> PlaybackBridge.current() != null && PlaybackBridge.current() != previous
            && onMain(() -> playbackOwner().playbackCanReplace()));
    return playbackOwner();
  }

  private String shell(String command) throws Exception {
    try (var input =
        new android.os.ParcelFileDescriptor.AutoCloseInputStream(
            runner
                .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .executeShellCommand(command))) {
      return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
    }
  }

  private static Object playbackField(String name) {
    try {
      var field = PlaybackBridge.current().getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(PlaybackBridge.current());
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }

  private static NativePlaybackHost playbackOwner() {
    return (NativePlaybackHost) playbackField("this$0");
  }

  private static NativePlaybackSession playbackSession() {
    return (NativePlaybackSession) playbackField("session");
  }

  private static ClassLoader pageClassLoader(PageSwapHost host) {
    try {
      var active = PageSwapHost.class.getDeclaredField("active");
      active.setAccessible(true);
      var slot = active.get(host);
      var page = slot.getClass().getDeclaredField("page");
      page.setAccessible(true);
      return page.get(slot).getClass().getClassLoader();
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static View badge(View root) {
    if (root.getClass().getName().equals(BADGE)) return root;
    if (root instanceof ViewGroup parent)
      for (int i = 0; i < parent.getChildCount(); i++) {
        View found = badge(parent.getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  private boolean onMain(BooleanSupplier condition) {
    boolean[] value = new boolean[1];
    main(() -> value[0] = condition.getAsBoolean());
    return value[0];
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
    if (failure.get() != null) throw new AssertionError("完整在线主线程检查失败", failure.get());
  }

  private void await(String message, long budget, BooleanSupplier condition) {
    long until = SystemClock.elapsedRealtime() + budget;
    while (!condition.getAsBoolean()) {
      check(SystemClock.elapsedRealtime() < until, message);
      SystemClock.sleep(60);
    }
  }

  private void step(String message) {
    Bundle status = new Bundle();
    status.putString("stream", message + "\n");
    runner.sendStatus(0, status);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
