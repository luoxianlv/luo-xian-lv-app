package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import app.luoxianlv.hot.*;
import app.luoxianlv.hot.contract.*;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** 同一普通宿主进程持续观察；外部只投放签名候选，测试不直接下载或激活。 */
final class NativeContinuousChecks {
  private static final String AREA = "native-continuous";
  static final long STAGE_TIMEOUT_MILLIS = 330000;
  private static final String BADGE = "app.luoxianlv.hot.probe.HotProbeFactory$NewNativeBadge";
  private final Instrumentation test;
  private final Activity home;
  private final Consumer<String> click;
  private final File area;
  private final JSONObject plan;
  private final JSONArray results = new JSONArray();
  private final List<WeakReference<ClassLoader>> retiredLoaders = new ArrayList<>();
  private final int pid = android.os.Process.myPid();
  private final HostUpdates updates;
  private final ClassLoader sharedRuntime;
  private Long migrationRegisteredAt;
  private long stageDeadline;

  /** 在打开任何页面之前停住测试调度，避免已有本机发布抢在阶段握手之前更新。 */
  static void pauseBeforeActivity(Instrumentation test) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 60000;
    while (true) {
      try {
        Bootstrap.source();
        break;
      } catch (IllegalStateException notReady) {
        check(SystemClock.elapsedRealtime() < deadline, "连续验收的内置业务未就绪");
        SystemClock.sleep(100);
      }
    }
    main(
        test,
        () -> {
          var startup = Bootstrap.startupState();
          requireLocal(startup, test);
          check(Bootstrap.source().prepared.manifest == null, "连续验收必须从最新 APK 内置基线开始");
          var updates = (HostUpdates) field(Bootstrap.class, "updates");
          check(updates != null && !Boolean.TRUE.equals(field(updates, "blocked")), "自动更新已停用");
          set(updates, "blocked", true);
          updates.usageChanged();
        });
  }

  static void run(
      Instrumentation test,
      Activity home,
      String path,
      Consumer<String> click,
      String previousServices)
      throws Exception {
    check(path.equals("files/" + AREA + "/plan.json"), "连续计划路径必须固定在应用测试目录");
    var checks = new NativeContinuousChecks(test, home, click);
    checks.run(previousServices);
  }

  private NativeContinuousChecks(Instrumentation test, Activity home, Consumer<String> click)
      throws Exception {
    this.test = test;
    this.home = home;
    this.click = click;
    area = new File(test.getTargetContext().getFilesDir(), AREA);
    byte[] raw = Files.readAllBytes(new File(area, "plan.json").toPath());
    check(raw.length <= 32768, "连续计划过大");
    plan = new JSONObject(new String(raw, StandardCharsets.UTF_8));
    check(
        plan.getInt("schema") == 1 && plan.getString("runId").matches("[a-f0-9]{32}"), "连续计划身份无效");
    check(plan.getLong("stageTimeoutMillis") == STAGE_TIMEOUT_MILLIS, "连续阶段总期限与本轮仪器不匹配");
    var stages = plan.getJSONArray("stages");
    check(stages.length() >= 4 && stages.length() <= 12, "至少三轮健康候选及最后一轮回退");
    Set<String> snapshots = new HashSet<>(), businesses = new HashSet<>();
    for (int i = 0; i < stages.length(); i++) {
      var stage = stages.getJSONObject(i);
      check(
          HotManifest.validHash(stage.getString("snapshot"))
              && HotManifest.validHash(stage.getString("business"))
              && snapshots.add(stage.getString("snapshot"))
              && businesses.add(stage.getString("business")),
          "每轮必须是不同快照和不同业务 APK 字节");
      check(
          stage.getString("mode").equals(i == stages.length() - 1 ? "rollback" : "healthy"),
          "只有最后一轮用于明确业务故障回退");
    }
    requireLocal(Bootstrap.startupState(), test);
    check(Build.VERSION.SDK_INT >= 30, "资源提供器持有检查需要 Android 11 或更新版本");
    updates = (HostUpdates) field(Bootstrap.class, "updates");
    sharedRuntime = Bootstrap.source().prepared.classLoader().getParent();
  }

  private void run(String previousServices) throws Exception {
    Bundle[] originalPlayback = new Bundle[1];
    boolean completed = false;
    var report =
        new JSONObject()
            .put("schema", 1)
            .put("runId", plan.getString("runId"))
            .put("pid", pid)
            .put("productionTouched", false)
            .put("stages", results)
            .put("stageTimeoutMillis", STAGE_TIMEOUT_MILLIS)
            .put("scheduleClockModified", false)
            .put("officialMountedResourceStressVerified", false)
            .put("healthClockModified", false)
            .put("explicitActivationCalledByTest", false)
            .put("classLoaderUnloadingRequired", false);
    try {
      check(
          previousServices != null && previousServices.matches("null|[A-Za-z0-9_.$/:]*"),
          "无障碍设置格式不支持安全还原");
      String component = "app.luoxianlv.debug/app.luoxianlv.service.MusicAccessibilityService";
      String services =
          previousServices.equals("null") || previousServices.isEmpty()
              ? component
              : previousServices.contains(component)
                  ? previousServices
                  : previousServices + ":" + component;
      shell("settings put secure enabled_accessibility_services '" + services + "'");
      shell("settings put secure accessibility_enabled 1");
      await("连续验收播放会话未连接", 20000, () -> PlaybackBridge.current() != null);
      main(
          test,
          () -> {
            originalPlayback[0] = PlaybackBridge.current().query("state");
            Bundle hidden = new Bundle();
            hidden.putBoolean("enabled", false);
            PlaybackBridge.current().command("showFloating", hidden);
          });
      await("连续验收没有交接安全点", 30000, () -> onMain(() -> Bootstrap.canAutoActivate() && idle()));
      migrationRegisteredAt = ProcessOnce.completedAt("storage-migration");
      check(migrationRegisteredAt != null, "最新基线没有登记稳定层存储迁移");
      check(
          Bootstrap.source().prepared.runtimeHash.equals(plan.getString("runtime")), "冻结运行时与宿主不符");
      for (int index = 0; index < plan.getJSONArray("stages").length(); index++) stage(index);
      // 只用于测试观察，回收时机由 ART 决定；硬性结论来自宿主根、资源及工作退出证据。
      System.gc();
      System.runFinalization();
      SystemClock.sleep(500);
      report
          .put("explicitGcForObservation", true)
          .put("retiredLoaderWeakReferences", retiredLoaders.size())
          .put(
              "retiredLoadersObservedAlive",
              retiredLoaders.stream().filter(ref -> ref.get() != null).count())
          .put("finalBounds", bounds())
          .put("passed", true);
      save("report.json", report);
      checkpoint("complete", plan.getJSONArray("stages").length(), "");
      step("通过：同一 PID 连续三份不同业务 APK 的真实健康确认及第四份整组回退，宿主持有边界成立");
      completed = true;
    } catch (Throwable failure) {
      report.put("passed", false).put("failureType", failure.getClass().getSimpleName());
      save("report.json", report);
      checkpoint("failed", results.length(), "");
      throw failure;
    } finally {
      boolean finished = completed;
      main(
          test,
          () -> {
            if (originalPlayback[0] != null && PlaybackBridge.current() != null) {
              Bundle speed = new Bundle();
              speed.putFloat("speed", originalPlayback[0].getFloat("speed", 1f));
              PlaybackBridge.current().command("setSpeed", speed);
              Bundle position = new Bundle();
              position.putLong("position", originalPlayback[0].getLong("positionMs"));
              PlaybackBridge.current().command("seek", position);
              Bundle floating = new Bundle();
              floating.putBoolean("enabled", originalPlayback[0].getBoolean("floatingEnabled"));
              PlaybackBridge.current().command("showFloating", floating);
            }
            // 失败后不清除真实故障门禁；外部driver停止测试进程并恢复原系统设置。
            if (finished && idle()) {
              set(updates, "blocked", false);
              updates.usageChanged();
            }
          });
    }
  }

  /** 此方法返回后不再强持有旧代际；跨阶段只保存基础字段与加载器弱引用。 */
  private void stage(int index) throws Exception {
    var stage = plan.getJSONArray("stages").getJSONObject(index);
    String target = stage.getString("snapshot");
    boolean rollback = stage.getString("mode").equals("rollback");
    await("上一事务未退出", 35000, () -> onMain(this::idle));
    Bootstrap.Source previous = Bootstrap.source();
    ProcessHooks previousProcess = (ProcessHooks) field(Bootstrap.class, "process");
    PlaybackPort previousPort = PlaybackBridge.current();
    NativePlaybackSession previousSession = (NativePlaybackSession) field(previousPort, "session");
    String previousStable = Bootstrap.startupState().journal.state().stable;
    long priorRevision = Bootstrap.startupState().journal.state().revision;
    check(android.os.Process.myPid() == pid, "阶段之间发生了进程重启");
    checkpoint("ready", index, target);
    await("没有收到对应本机发布阶段确认", 180000, () -> go(index, target));
    long startedAt = SystemClock.elapsedRealtime();
    stageDeadline = startedAt + STAGE_TIMEOUT_MILLIS;
    checkpoint("running", index, target);
    main(
        test,
        () -> {
          set(updates, "blocked", false);
          updates.usageChanged();
        });
    awaitStage(
        "普通入口未自动曝光本轮候选",
        () ->
            onMain(
                () ->
                    Bootstrap.source().prepared.manifest != null
                        && Bootstrap.source().prepared.identity().equals(target)
                        && field(updates, "group") != null
                        && ((GroupActivation) field(updates, "group")).phase()
                            == GroupActivation.Phase.OBSERVING));
    long observedAt = SystemClock.elapsedRealtime();
    var next = Bootstrap.source().prepared;
    var candidateProcess = (ProcessHooks) field(Bootstrap.class, "process");
    var candidatePort = PlaybackBridge.current();
    var candidateSession = (NativePlaybackSession) field(candidatePort, "session");
    var activation = (GroupActivation) field(updates, "group");
    var ticket = (ActivationController.Ticket) field(activation, "ticket");
    var health = (HealthWindow) field(ticket, "health");
    check(next.manifest.business.sha256.equals(stage.getString("business")), "来源清单不是计划的业务 APK");
    check(next.manifest.artifacts.size() == 2 && next.manifest.artifacts.stream().allMatch(value -> value.mount.isEmpty()),
        "本轮连续检查不接受官方资源挂载，不能冒充资源压力验收");
    check(
        next.classLoader() != previous.prepared.classLoader()
            && next.classLoader().getParent() == sharedRuntime,
        "业务加载器没有隔离或共享运行时被重复加载");
    main(test, () -> assertSources(next.classLoader()));
    var row =
        new JSONObject()
            .put("index", index)
            .put("pid", pid)
            .put("snapshot", target)
            .put("business", next.manifest.business.sha256)
            .put("mode", stage.getString("mode"))
            .put("attempt", ticket.attemptId)
            .put("runtimeReused", true);
    // 每轮新增真实窗口，证明下一代资源与页面注册表一起跟随来源；不改变健康时钟。
    Activity picker =
        test.startActivitySync(
            new Intent()
                .setClassName(
                    test.getTargetContext(), "app.luoxianlv.ui.practice.WallpaperPickerActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      awaitStage(
          "本轮新增窗口未进入候选",
          () -> onMain(() -> Bootstrap.pages().size() == 2 && picker.hasWindowFocus()));
      main(test, () -> assertSources(next.classLoader()));
    } finally {
      main(test, picker::finish);
    }
    awaitStage("新增窗口关闭后首页未恢复焦点", home::hasWindowFocus);
    if (rollback) {
      check(health.observedMillis() < 60000, "故障注入必须发生在试运行健康确认之前");
      click.accept("设置");
      awaitStage(
          "回退前最新导航尚未保存",
          () ->
              onMain(
                  () ->
                      "settings"
                          .equals(mainHost().save().getBundle("navigation").getString("tab"))));
      long[] expectedPosition = new long[1];
      main(
          test,
          () -> {
            Bundle speed = new Bundle();
            speed.putFloat("speed", 1.25f);
            PlaybackBridge.current().command("setSpeed", speed);
            expectedPosition[0] =
                Math.min(
                    1200,
                    Math.max(0, PlaybackBridge.current().query("state").getLong("durationMs")));
            Bundle position = new Bundle();
            position.putLong("position", expectedPosition[0]);
            PlaybackBridge.current().command("seek", position);
            check(
                health.observedMillis() < 60000
                    && Bootstrap.startupState().journal.state().phase
                        == ActivationJournal.Phase.TRIAL,
                "故障注入已错过真实试运行窗口");
            mainHost().failActive(new IllegalStateException("测试：连续候选业务失败"));
          });
      awaitStage(
          "本轮故障未完成整组回退",
          () -> onMain(() -> idle() && Bootstrap.source().prepared == previous.prepared));
      check(
          Bootstrap.startupState().journal.state().stable.equals(previousStable)
              && Bootstrap.startupState().journal.state().quarantine.contains(target),
          "磁盘回退或内容隔离未完成");
      main(
          test,
          () -> {
            assertSources(previous.prepared.classLoader());
            check(
                "settings".equals(mainHost().save().getBundle("navigation").getString("tab")),
                "回退丢失最新导航");
            var latest =
                ((NativePlaybackSession) field(PlaybackBridge.current(), "session")).snapshot();
            check(
                latest.getFloat("speed") == 1.25f
                    && latest.getLong("position") == expectedPosition[0],
                "回退丢失最新播放速度或位置");
            assertRetired(next, candidateProcess, candidateSession);
            check(
                field(candidatePort, "session") == null
                    && field(candidatePort, "retiredSession") == null,
                "回退后候选播放句柄仍持有业务实例");
          });
      retiredLoaders.add(new WeakReference<>(next.classLoader()));
      row.put("restoredSnapshot", previousStable)
          .put("latestUserStatePreserved", true)
          .put("expectedRestoredPositionMillis", expectedPosition[0])
          .put("candidateRetired", true);
    } else {
      awaitStage(
          "本轮没有完成真实健康确认及旧代退出",
          () ->
              onMain(
                  () -> idle() && Bootstrap.startupState().journal.state().stable.equals(target)));
      check(health.observedMillis() >= 60000, "本轮不足 60 秒真实有效观察");
      main(
          test,
          () -> {
            assertSources(next.classLoader());
            assertRetired(previous.prepared, previousProcess, previousSession);
            check(
                field(previousPort, "session") == null
                    && field(previousPort, "retiredSession") == null,
                "旧播放句柄仍持有业务实例");
          });
      retiredLoaders.add(new WeakReference<>(previous.prepared.classLoader()));
      row.put("previousRetired", true).put("observedActiveMillis", health.observedMillis());
    }
    var state = Bootstrap.startupState().journal.state();
    check(
        state.revision >= priorRevision && state.phase == ActivationJournal.Phase.STABLE,
        "版本下限或稳定事务错误");
    check(
        Objects.equals(migrationRegisteredAt, ProcessOnce.completedAt("storage-migration")),
        "存储迁移在热更时重复执行");
    var outbox =
        new HealthOutbox(
            new File(test.getTargetContext().getNoBackupFilesDir(), "native-update/health"));
    awaitStage(
        "本轮持久健康回执没有完成联网确认",
        () -> {
          try {
            return outbox.batch(100).isEmpty()
                && Bootstrap.startupState().journal.state().outcomes.isEmpty();
          } catch (Exception error) {
            throw new AssertionError(error);
          }
        });
    main(
        test,
        () -> {
          set(updates, "blocked", true);
          updates.usageChanged();
        });
    awaitStage("已退役业务线程仍在重复持有", NativeContinuousChecks::ownedThreadBound);
    row.put("wallMillisSinceExposureObserved", SystemClock.elapsedRealtime() - observedAt)
        .put("stageElapsedMillis", SystemClock.elapsedRealtime() - startedAt)
        .put("stageTimeoutMillis", STAGE_TIMEOUT_MILLIS)
        .put("revision", state.revision)
        .put("healthReportAcknowledged", true)
        .put("bounds", bounds());
    results.put(row);
    save(
        "report.json",
        new JSONObject()
            .put("runId", plan.getString("runId"))
            .put("pid", pid)
            .put("stages", results));
    check(SystemClock.elapsedRealtime() <= stageDeadline, "连续阶段超过共同总期限");
    checkpoint("passed", index, target);
    step("通过：连续阶段 " + (index + 1) + " / " + stage.getString("mode") + " / " + target);
  }

  private JSONObject bounds() throws Exception {
    var result = new JSONObject();
    main(
        test,
        () -> {
          assertSources(Bootstrap.source().prepared.classLoader());
          for (var page : Bootstrap.pages())
            check(
                field(page.host(), "previous") == null && field(page.host(), "candidate") == null,
                "结束阶段仍保留旧页面或候选页面");
          var service = (NativeAccessibilityService) field(Bootstrap.class, "playback");
          check(
              ((Collection<?>) field(service, "retired")).isEmpty()
                  && field(service, "handover") == null,
              "结束阶段仍保留旧播放或播放事务");
          Object resources = field(Bootstrap.source().prepared, "resources");
          var loader = (android.content.res.loader.ResourcesLoader) field(resources, "loader");
          check(loader != null && loader.getProviders().size() == 2, "当前资源提供器不是一组运行时/业务 APK");
          try {
            result
                .put("strongHostBusinessSources", 1)
                .put("pageCount", Bootstrap.pages().size())
                .put(
                    "checkedHostRoots",
                    new JSONArray(
                        List.of("source", "process", "pages", "playback", "HostDiagnostics")))
                .put("previousPageCount", 0)
                .put("retiredPlaybackCount", 0)
                .put("activeResourceProviders", loader.getProviders().size())
                .put("activeResourceOwners", ((Collection<?>) field(resources, "owners")).size());
          } catch (Exception error) {
            throw new AssertionError(error);
          }
        });
    Class<?> leases =
        Class.forName(
            "app.luoxianlv.hot.ContentLeases", false, test.getTargetContext().getClassLoader());
    String root =
        new File(test.getTargetContext().getNoBackupFilesDir(), "native-update").getCanonicalPath();
    int count = 0;
    synchronized (leases) {
      var pins = (Map<?, ?>) field(leases, "pins");
      for (var pin : pins.entrySet()) {
        if (!root.equals(field(pin.getKey(), "root"))) continue;
        String snapshot = (String) field(pin.getKey(), "snapshot");
        check(
            snapshot.isEmpty() || snapshot.equals(Bootstrap.source().prepared.identity()),
            "内容租约仍保护已退出业务代际");
        check(field(pin.getKey(), "runtime").equals(plan.getString("runtime")), "内容租约混入另一运行时");
        count += (Integer) pin.getValue();
      }
    }
    check(count >= 1 && count <= 2, "稳定阶段内容租约超过当前模块及共享运行时上界");
    String[] descriptors = new File("/proc/self/fd").list();
    result
        .put("contentLeaseCount", count)
        .put("businessWorkerThreads", new JSONObject(threadCounts()))
        .put("processThreadCount", Thread.getAllStackTraces().size())
        .put("openFileDescriptors", descriptors == null ? JSONObject.NULL : descriptors.length)
        .put(
            "javaHeapUsedBytes",
            Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
        .put("nativeHeapAllocatedBytes", android.os.Debug.getNativeHeapAllocatedSize())
        .put("storageMigrationRegisteredAt", migrationRegisteredAt)
        .put("migrationTimestampRepresentsAsyncCompletion", false);
    return result;
  }

  private static void assertRetired(
      NativeLoader.Prepared prepared, ProcessHooks process, NativePlaybackSession playback) {
    check(
        Boolean.TRUE.equals(field(prepared, "retired")) && field(prepared, "contentLease") == null,
        "退役业务没有释放内容租约");
    check(process != null && process.released(), "退役进程业务的工作队列尚未退出");
    if (playback != null) check(playback.released(), "退役播放业务的线程/协程尚未退出");
    Object resources = field(prepared, "resources");
    check(
        Boolean.TRUE.equals(field(resources, "closed"))
            && ((Collection<?>) field(resources, "owners")).isEmpty(),
        "退役资源仍绑定窗口");
    var loader = (android.content.res.loader.ResourcesLoader) field(resources, "loader");
    check(loader != null && loader.getProviders().isEmpty(), "退役资源提供器仍持有 APK");
    Object app = field(prepared, "moduleApplication");
    if (app != null)
      for (String name : List.of("componentCallbacks", "activityCallbacks", "assistCallbacks"))
        check(((Collection<?>) field(app, name)).isEmpty(), "退役 Application 仍持有监听");
  }

  private void assertSources(ClassLoader expected) {
    check(
        Bootstrap.source().prepared.classLoader() == expected
            && Bootstrap.source().factory.getClass().getClassLoader() == expected,
        "宿主工厂来源代际不一致");
    check(
        ((ProcessHooks) field(Bootstrap.class, "process")).getClass().getClassLoader() == expected,
        "进程业务来源代际不一致");
    for (var page : Bootstrap.pages()) {
      Object active = field(page.host(), "active");
      check(
          field(active, "page").getClass().getClassLoader() == expected
              && ((android.content.Context) field(active, "context")).getClassLoader() == expected,
          "页面/窗口资源上下文来源代际不一致");
    }
    check(
        field(PlaybackBridge.current(), "session").getClass().getClassLoader() == expected,
        "播放来源代际不一致");
    var current = (AtomicReference<?>) field(HostDiagnostics.class, "CURRENT");
    check(
        current.get() != null
            && field(current.get(), "sink").getClass().getClassLoader() == expected,
        "稳定层日志接收器仍持有其他业务代际");
    check(expected.getParent() == sharedRuntime, "运行时加载器身份改变");
    View badge = badge(home.getWindow().getDecorView());
    check(badge != null && badge.getClass().getClassLoader() == expected, "新原生组件来源代际不一致");
  }

  private boolean idle() {
    return !Boolean.TRUE.equals(field(updates, "busy"))
        && field(updates, "group") == null
        && field(Bootstrap.class, "activation") == null
        && !Boolean.TRUE.equals(field(Bootstrap.class, "updateBlocked"));
  }

  private boolean go(int index, String snapshot) {
    File file = new File(area, "go-" + index + ".json");
    if (!file.isFile()) return false;
    try {
      byte[] raw = Files.readAllBytes(file.toPath());
      check(raw.length <= 1024, "阶段确认过大");
      var go = new JSONObject(new String(raw, StandardCharsets.UTF_8));
      return go.getString("runId").equals(plan.getString("runId"))
          && go.getInt("index") == index
          && go.getString("snapshot").equals(snapshot);
    } catch (Exception error) {
      throw new AssertionError("阶段确认损坏", error);
    }
  }

  private void checkpoint(String phase, int index, String snapshot) throws Exception {
    save(
        "state.json",
        new JSONObject()
            .put("runId", plan.getString("runId"))
            .put("phase", phase)
            .put("index", index)
            .put("snapshot", snapshot)
            .put("stageTimeoutMillis", STAGE_TIMEOUT_MILLIS)
            .put("pid", pid));
  }

  private void save(String name, JSONObject value) throws Exception {
    var temporary = new File(area, name + ".part").toPath();
    Files.writeString(temporary, value.toString(2), StandardCharsets.UTF_8);
    Files.move(
        temporary,
        new File(area, name).toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
  }

  private void shell(String command) throws Exception {
    try (var input =
        new android.os.ParcelFileDescriptor.AutoCloseInputStream(
            test.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .executeShellCommand(command))) {
      input.readAllBytes();
    }
  }

  private PageSwapHost mainHost() {
    return Bootstrap.pages().stream()
        .filter(page -> page.route().equals("main"))
        .findFirst()
        .orElseThrow()
        .host();
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

  private static Map<String, Integer> threadCounts() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String name : List.of("playback-diagnostics", "谱面播放准备", "曲目时长")) counts.put(name, 0);
    for (Thread thread : Thread.getAllStackTraces().keySet())
      if (thread.isAlive() && counts.containsKey(thread.getName()))
        counts.merge(thread.getName(), 1, Integer::sum);
    return counts;
  }

  private static boolean ownedThreadBound() {
    return threadCounts().values().stream().allMatch(count -> count <= 1);
  }

  private static void requireLocal(HostStartup state, Instrumentation test) {
    check(
        state != null
            && test.getTargetContext().getPackageName().equals("app.luoxianlv.debug")
            && state.config.environment.equals("test")
            && state.config.automatic
            && state.config.testHealthReports
            && state.config.origin.toString().equals("http://127.0.0.1:18472"),
        "仅允许显式本机 Debug 自动验收");
  }

  private static Field member(Object owner, String name) {
    for (Class<?> type = owner instanceof Class<?> value ? value : owner.getClass();
        type != null;
        type = type.getSuperclass()) {
      try {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
      } catch (NoSuchFieldException ignored) {
      }
    }
    throw new AssertionError("缺少测试测量字段：" + name);
  }

  private static Object field(Object owner, String name) {
    check(owner != null, "测量对象为空：" + name);
    try {
      return member(owner, name).get(owner instanceof Class<?> ? null : owner);
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }

  private static void set(Object owner, String name, Object value) {
    try {
      member(owner, name).set(owner instanceof Class<?> ? null : owner, value);
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }

  private static void main(Instrumentation test, Runnable action) {
    var failure = new AtomicReference<Throwable>();
    test.runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable error) {
            failure.set(error);
          }
        });
    if (failure.get() != null) throw new AssertionError("连续验收主线程检查失败", failure.get());
  }

  private boolean onMain(BooleanSupplier action) {
    boolean[] result = new boolean[1];
    main(test, () -> result[0] = action.getAsBoolean());
    return result[0];
  }

  private static void await(String message, long timeout, BooleanSupplier condition) {
    long deadline = SystemClock.elapsedRealtime() + timeout;
    while (!condition.getAsBoolean()) {
      check(SystemClock.elapsedRealtime() < deadline, message);
      SystemClock.sleep(100);
    }
  }

  private void awaitStage(String message, BooleanSupplier condition) {
    long remaining = stageDeadline - SystemClock.elapsedRealtime();
    check(remaining > 0, "连续阶段总期限耗尽：" + message);
    await(message, remaining, condition);
    check(SystemClock.elapsedRealtime() <= stageDeadline, "连续阶段超过共同总期限：" + message);
  }

  private void step(String message) {
    Bundle status = new Bundle();
    status.putString("stream", message + "\n");
    test.sendStatus(0, status);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
