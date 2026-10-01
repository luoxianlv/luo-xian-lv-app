package app.luoxianlv.host;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import app.luoxianlv.hot.ActivationJournal;
import app.luoxianlv.hot.HealthWindow;
import app.luoxianlv.hot.HostUpdateConfig;
import app.luoxianlv.hot.HotManifest;
import app.luoxianlv.hot.NativeAccessibilityService;
import app.luoxianlv.hot.NativeLoader;
import app.luoxianlv.hot.contract.AccessibilityBinding;
import app.luoxianlv.hot.contract.PlaybackBridge;
import app.luoxianlv.service.PlaybackForegroundService;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 仅观察真实 Application 与服务入口，不打开 Activity，也不调用激活或健康确认。
 * 主代理先 force-stop，再通过显式 instrumentation 组件运行；本仪器不负责构建、签包或渠道准备。
 * consumer=accessibility|foreground，expectedSnapshot/expectedStable=apk|SHA256，
 * expectedPending=none|SHA256，expectedPhase=STABLE|TRIAL，observeMillis 缺省 3000。
 * TRIAL 模式证明服务连接和空闲不计时，不声称完成真实播放的 60 秒健康观察。
 */
public final class NativeServiceColdInstrumentation extends Instrumentation {
  private Bundle arguments;
  private UiAutomation automation;
  private String stage = "参数检查";
  private volatile String failureCode = "", failedCheck = "", mainFailureClass = "";
  private volatile Observation lastObservation;
  private boolean foregroundStartObserved, foregroundPolicyObserved;
  private final AtomicInteger activityCreations = new AtomicInteger();
  private final AtomicInteger activityStarts = new AtomicInteger();
  private final Application.ActivityLifecycleCallbacks activities =
      new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityCreated(Activity activity, Bundle state) { activityCreations.incrementAndGet(); }
        @Override public void onActivityStarted(Activity activity) { activityStarts.incrementAndGet(); }
        @Override public void onActivityResumed(Activity activity) {}
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        @Override public void onActivityDestroyed(Activity activity) {}
      };

  @Override
  public void onCreate(Bundle values) {
    super.onCreate(values);
    arguments = values == null ? new Bundle() : new Bundle(values);
    start();
  }

  private void require(boolean condition, String message) {
    if (condition) return;
    // message 仅来自本文件的固定检查文案，不取 Throwable、配置或网络响应正文。
    failedCheck = message;
    failureCode = switch (message) {
      case "工厂未使用已选择业务加载器" -> "FACTORY_LOADER_MISMATCH";
      case "业务已停止，不能报告服务冷启动成功" -> "BUSINESS_STOPPED";
      case "无界面模式出现业务窗口或页面" -> "BUSINESS_WINDOW_PRESENT";
      case "无界面模式创建或启动了 Activity" -> "ACTIVITY_STARTED";
      case "业务来源始终未准备完成" -> "SOURCE_NOT_READY";
      case "未选择预期真实组合" -> "SOURCE_SNAPSHOT_MISMATCH";
      case "未进入预期真实日志阶段" -> "JOURNAL_PHASE_MISMATCH";
      case "未观察到真实服务业务连接或前台策略" -> "SERVICE_NOT_READY";
      case "无障碍会话尚未建立" -> "PLAYBACK_SESSION_MISSING";
      case "无障碍会话未使用已选择业务加载器" -> "PLAYBACK_LOADER_MISMATCH";
      case "原稳定指针与预期不符" -> "STABLE_MISMATCH";
      case "待重启缓存选择与预期不符" -> "PENDING_MISMATCH";
      case "未选择预期运行时" -> "RUNTIME_MISMATCH";
      case "实际常驻运行时与所选来源不符" -> "RESIDENT_RUNTIME_MISMATCH";
      case "试运行没有真实候选与健康窗口" -> "TRIAL_TICKET_MISSING";
      case "稳定启动仍残留候选事务" -> "UNEXPECTED_CANDIDATE";
      case "空闲验收已有实际播放，应使用独立真实播放矩阵" -> "ALREADY_PLAYING";
      case "空闲观察期间业务停止或开始实际播放" -> "IDLE_USAGE_CHANGED";
      case "空闲服务启动改变了选择、待重启或稳定事务" -> "IDLE_SELECTION_CHANGED";
      case "无页面且未播放的服务仍累计了健康时间" -> "IDLE_HEALTH_ADVANCED";
      case "版本或信任下限下降", "本次观察回滚了版本下限" -> "VERSION_FLOOR_LOWERED";
      default -> "CONTROLLED_CHECK_FAILED";
    };
    throw new AssertionError(message);
  }

  private void onMain(Runnable action) {
    AtomicReference<Throwable> failed = new AtomicReference<>();
    runOnMainSync(() -> {
      try { action.run(); }
      catch (Throwable error) { failed.set(error); }
    });
    if (failed.get() != null) {
      if (mainFailureClass.isEmpty()) mainFailureClass = failed.get().getClass().getName();
      if (failureCode.isEmpty()) failureCode = "MAIN_OPERATION_FAILED";
      throw new AssertionError("主线程检查未通过", failed.get());
    }
  }

  private String option(String name, String fallback) {
    String value = arguments.getString(name);
    return value == null ? fallback : value;
  }

  private String snapshotOption(String name, String fallback) {
    String value = option(name, fallback);
    require(value.equals("apk") || HotManifest.validHash(value), "组合参数必须为 apk 或 SHA256");
    return value.equals("apk") ? "" : value;
  }

  private long numberOption(String name, long fallback, long maximum) {
    String value = arguments.getString(name);
    long number = value == null ? fallback : Long.parseLong(value);
    require(number >= 0 && number <= maximum, "数值参数越界");
    return number;
  }

  private static Object read(Class<?> type, Object owner, String name) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private String shell(String command) throws Exception {
    try (ParcelFileDescriptor descriptor = automation.executeShellCommand(command);
        var input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
    }
  }

  private void restoreSetting(String name, String value) throws Exception {
    if (value == null) return;
    if (value.equals("null") || value.isEmpty()) shell("settings delete secure " + name);
    else {
      // UiAutomation 按参数执行命令，单引号会成为设置值本身；只接受系统组件列表或数字。
      require(value.matches("[A-Za-z0-9_.$:/]+"), "系统设置值格式异常");
      shell("settings put secure " + name + " " + value);
    }
  }

  private static final class Observation {
    String snapshot, stable, candidate, pending, runtime, phase;
    long revision, trustVersion;
    boolean journalAvailable, playbackConnected, playbackUsed, stopped;
    boolean foregroundStarted, foregroundPolicyCreated;
    boolean factoryLoaderMatches, playbackLoaderMatches;
    boolean playbackServicePresent, playbackBindingPresent, playbackServiceConnected;
    String businessLoaderType = "", factoryLoaderType = "", playbackLoaderType = "";
    int pages, windows;
    ClassLoader businessLoader;
    Object playbackSession;
    HealthWindow health;
  }

  /** 私有字段只用于读取宿主实际状态；不写连接、许可、使用标志或激活阶段。 */
  private Observation observe() {
    Observation value = new Observation();
    lastObservation = value;
    onMain(() -> {
      try {
        value.pages = Bootstrap.pages().size();
        value.windows = ((List<?>) read(Bootstrap.class, null, "WINDOWS")).size();
        value.stopped = Bootstrap.businessStopped();
        if (!value.stopped) {
          var source = Bootstrap.source();
          value.snapshot = source.prepared.manifest == null ? "" : source.prepared.manifest.snapshotId;
          value.runtime = source.prepared.runtimeHash;
          value.businessLoader = source.prepared.classLoader();
          ClassLoader factoryLoader = source.factory.getClass().getClassLoader();
          value.factoryLoaderMatches = factoryLoader == value.businessLoader;
          value.businessLoaderType = loaderType(value.businessLoader);
          value.factoryLoaderType = loaderType(factoryLoader);
          require(value.factoryLoaderMatches,
              "工厂未使用已选择业务加载器");
        }
        var state = Bootstrap.startupState();
        value.journalAvailable = state != null;
        value.stable = "";
        value.candidate = "";
        value.pending = "";
        value.phase = "STABLE";
        if (state != null) {
          var journal = state.journal.state();
          value.stable = journal.stable;
          value.candidate = journal.candidate;
          value.pending = state.pendingRestart.current();
          value.phase = journal.phase.name();
          value.revision = journal.revision;
          value.trustVersion = journal.trustVersion;
        }
        var port = PlaybackBridge.current();
        value.playbackConnected = port instanceof AccessibilityBinding binding && binding.current();
        value.playbackUsed = Bootstrap.playbackInUse();
        Object playback = read(Bootstrap.class, null, "playback");
        value.playbackServicePresent = playback != null;
        if (playback != null) {
          value.playbackServiceConnected = (Boolean) read(NativeAccessibilityService.class, playback, "connected");
          Object binding = read(NativeAccessibilityService.class, playback, "binding");
          value.playbackBindingPresent = binding != null;
          if (binding != null) value.playbackSession = read(binding.getClass(), binding, "session");
          if (value.playbackSession != null) {
            ClassLoader sessionLoader = value.playbackSession.getClass().getClassLoader();
            value.playbackLoaderMatches = sessionLoader == value.businessLoader;
            value.playbackLoaderType = loaderType(sessionLoader);
          }
        }
        Object foreground = read(PlaybackForegroundService.class, null, "instance");
        if (foreground != null) {
          value.foregroundStarted = (Integer) read(PlaybackForegroundService.class, foreground, "lastStartId") > 0;
          value.foregroundPolicyCreated = read(PlaybackForegroundService.class, foreground, "policy") != null;
        }
        Object updates = read(Bootstrap.class, null, "updates");
        if (updates != null) {
          Object ticket = read(HostUpdates.class, updates, "coldTicket");
          if (ticket != null) value.health = (HealthWindow) read(ticket.getClass(), ticket, "health");
        }
      } catch (Exception error) {
        throw new IllegalStateException("读取冷启动状态失败", error);
      }
    });
    return value;
  }

  private boolean sourceReady() {
    AtomicReference<Boolean> ready = new AtomicReference<>(false);
    onMain(() -> {
      require(!Bootstrap.businessStopped(), "业务已停止，不能报告服务冷启动成功");
      try { Bootstrap.source(); ready.set(true); }
      catch (IllegalStateException waiting) { /* 仅等待正常后台准备，不把未就绪当 SDK 故障。 */ }
    });
    return ready.get();
  }

  private void noActivity(Observation value) {
    require(value.pages == 0 && value.windows == 0, "无界面模式出现业务窗口或页面");
    require(activityCreations.get() == 0 && activityStarts.get() == 0, "无界面模式创建或启动了 Activity");
  }

  private static String loaderType(ClassLoader loader) {
    return loader == null ? "bootstrap" : loader.getClass().getName();
  }

  private static String diagnosticHash(String value, boolean apk) {
    if (value == null) return "unobserved";
    if (value.isEmpty()) return apk ? "apk" : "";
    return HotManifest.validHash(value) ? value : "invalid";
  }

  /** 失败时也只输出宿主持有的布尔状态、公开身份与类型名，不输出对象/加载器 toString。 */
  private void diagnostic(org.json.JSONObject report) throws Exception {
    report.put("failureCode", failureCode.isEmpty() ? "OPERATION_FAILED" : failureCode)
        .put("failedCheck", failedCheck).put("mainFailureClass", mainFailureClass)
        .put("pid", android.os.Process.myPid())
        .put("activityCreations", activityCreations.get()).put("activityStarts", activityStarts.get())
        .put("foregroundStartObserved", foregroundStartObserved)
        .put("foregroundPolicyObserved", foregroundPolicyObserved);
    Observation value = lastObservation;
    report.put("selectionObserved", value != null);
    if (value != null) report.put("selected", new org.json.JSONObject()
        .put("snapshot", diagnosticHash(value.snapshot, true))
        .put("stable", diagnosticHash(value.stable, true))
        .put("candidate", diagnosticHash(value.candidate, false))
        .put("pending", diagnosticHash(value.pending, false))
        .put("runtimeHash", diagnosticHash(value.runtime, false))
        .put("phase", value.phase == null ? "unobserved" : value.phase)
        .put("revision", value.revision).put("trustVersion", value.trustVersion)
        .put("journalAvailable", value.journalAvailable).put("stopped", value.stopped)
        .put("pages", value.pages).put("windows", value.windows)
        .put("playbackConnected", value.playbackConnected).put("playbackUsed", value.playbackUsed)
        .put("playbackServicePresent", value.playbackServicePresent)
        .put("playbackServiceConnected", value.playbackServiceConnected)
        .put("playbackBindingPresent", value.playbackBindingPresent)
        .put("playbackSessionPresent", value.playbackSession != null)
        .put("factoryLoaderMatches", value.factoryLoaderMatches)
        .put("playbackLoaderMatches", value.playbackLoaderMatches)
        .put("businessLoaderType", value.businessLoaderType)
        .put("factoryLoaderType", value.factoryLoaderType)
        .put("playbackLoaderType", value.playbackLoaderType)
        .put("coldHealthPresent", value.health != null));
    AtomicReference<org.json.JSONObject> bootstrap = new AtomicReference<>();
    onMain(() -> {
      try {
        var state = new org.json.JSONObject()
            .put("finished", read(Bootstrap.class, null, "finished"))
            .put("stopped", Bootstrap.businessStopped())
            .put("sourcePresent", read(Bootstrap.class, null, "source") != null)
            .put("residentRuntimeHash", diagnosticHash(NativeLoader.residentRuntimeHash(), false));
        Throwable failure = (Throwable) read(Bootstrap.class, null, "failure");
        var types = new org.json.JSONArray();
        for (int count = 0; failure != null && count < 6; count++, failure = failure.getCause())
          types.put(failure.getClass().getName());
        state.put("failureTypes", types);
        bootstrap.set(state);
      } catch (Exception error) { throw new IllegalStateException("读取宿主诊断失败", error); }
    });
    report.put("bootstrap", bootstrap.get());
    String enabled = android.provider.Settings.Secure.getString(
        getTargetContext().getContentResolver(), "enabled_accessibility_services");
    String own = getTargetContext().getPackageName() + "/app.luoxianlv.service.MusicAccessibilityService";
    report.put("ownAccessibilitySettingPresent", enabled != null && List.of(enabled.split(":")).contains(own));
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    org.json.JSONObject report = new org.json.JSONObject();
    Application application = (Application) getTargetContext().getApplicationContext();
    String previousServices = null, previousEnabled = null;
    boolean settingsChanged = false, success = false;
    try {
      String consumer = option("consumer", "accessibility");
      require(consumer.equals("accessibility") || consumer.equals("foreground"), "未知服务入口");
      String expectedSnapshot = snapshotOption("expectedSnapshot", "apk");
      String expectedPhase = option("expectedPhase", "STABLE");
      require(expectedPhase.equals("STABLE") || expectedPhase.equals("TRIAL"), "未知预期阶段");
      require(!expectedPhase.equals("TRIAL") || arguments.containsKey("expectedStable"), "试运行必须明确原稳定组合");
      String expectedStable = snapshotOption("expectedStable", option("expectedSnapshot", "apk"));
      String pendingOption = option("expectedPending", "none");
      require(pendingOption.equals("none") || HotManifest.validHash(pendingOption), "待重启参数无效");
      String expectedPending = pendingOption.equals("none") ? "" : pendingOption;
      long observeMillis = numberOption("observeMillis", 3000, 120000);
      require(observeMillis >= 1000, "观察不能短于一秒");
      long minimumRevision = numberOption("minRevision", 0, Long.MAX_VALUE);
      long minimumTrust = numberOption("minTrustVersion", 0, Long.MAX_VALUE);
      long previousPid = numberOption("previousPid", 0, Integer.MAX_VALUE);
      String expectedRuntime = option("expectedRuntime", "");
      require(expectedRuntime.isEmpty() || HotManifest.validHash(expectedRuntime), "运行时参数无效");
      report.put("expected", new org.json.JSONObject().put("consumer", consumer)
          .put("snapshot", diagnosticHash(expectedSnapshot, true))
          .put("stable", diagnosticHash(expectedStable, true))
          .put("pending", diagnosticHash(expectedPending, false)).put("phase", expectedPhase)
          .put("runtimeHash", diagnosticHash(expectedRuntime, false)));

      stage = "受控环境检查";
      require(getTargetContext().getPackageName().equals("app.luoxianlv.debug")
          && (application.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0,
          "仅允许 Debug 测试宿主");
      var config = HostUpdateConfig.read(getTargetContext());
      require(config == null || (config.environment.equals("test")
          && config.origin.toString().equals("http://127.0.0.1:18472")), "仅允许本机测试渠道");
      require(previousPid == 0 || previousPid != android.os.Process.myPid(), "未使用新进程冷启动");
      application.registerActivityLifecycleCallbacks(activities);
      automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);

      stage = "真实服务启动";
      if (consumer.equals("accessibility")) {
        previousServices = shell("settings get secure enabled_accessibility_services");
        previousEnabled = shell("settings get secure accessibility_enabled");
        String own = getTargetContext().getPackageName() + "/app.luoxianlv.service.MusicAccessibilityService";
        String enabled = previousServices.equals("null") || previousServices.isEmpty() ? own
            : List.of(previousServices.split(":")).contains(own) ? previousServices : previousServices + ":" + own;
        settingsChanged = true;
        restoreSetting("enabled_accessibility_services", enabled);
        shell("settings put secure accessibility_enabled 1");
      } else {
        onMain(() -> getTargetContext().startForegroundService(
            new Intent(getTargetContext(), PlaybackForegroundService.class)));
      }

      long until = SystemClock.elapsedRealtime() + 90000;
      Observation selected = null;
      boolean foregroundStarted = false, foregroundPolicyCreated = false;
      stage = "真实来源与服务连接";
      while (SystemClock.elapsedRealtime() < until) {
        if (sourceReady()) {
          selected = observe();
          noActivity(selected);
          foregroundStarted |= selected.foregroundStarted;
          foregroundPolicyCreated |= selected.foregroundPolicyCreated;
          foregroundStartObserved = foregroundStarted;
          foregroundPolicyObserved = foregroundPolicyCreated;
          boolean serviceReady = consumer.equals("accessibility") ? selected.playbackConnected
              : foregroundStarted && foregroundPolicyCreated;
          if (serviceReady && expectedSnapshot.equals(selected.snapshot)
              && expectedPhase.equals(selected.phase)) break;
        }
        SystemClock.sleep(10);
      }
      require(selected != null, "业务来源始终未准备完成");
      require(expectedSnapshot.equals(selected.snapshot), "未选择预期真实组合");
      require(expectedPhase.equals(selected.phase), "未进入预期真实日志阶段");
      require(consumer.equals("accessibility") ? selected.playbackConnected
          : foregroundStarted && foregroundPolicyCreated, "未观察到真实服务业务连接或前台策略");
      if (consumer.equals("accessibility")) {
        require(selected.playbackSession != null, "无障碍会话尚未建立");
        require(selected.playbackLoaderMatches, "无障碍会话未使用已选择业务加载器");
      }
      require(expectedStable.equals(selected.stable), "原稳定指针与预期不符");
      require(expectedPending.equals(selected.pending), "待重启缓存选择与预期不符");
      require(expectedRuntime.isEmpty() || expectedRuntime.equals(selected.runtime), "未选择预期运行时");
      require(selected.runtime.equals(NativeLoader.residentRuntimeHash()), "实际常驻运行时与所选来源不符");
      if (expectedPhase.equals("TRIAL")) {
        require(expectedSnapshot.equals(selected.candidate) && selected.health != null,
            "试运行没有真实候选与健康窗口");
      } else require(selected.candidate.isEmpty(), "稳定启动仍残留候选事务");

      stage = "服务空闲健康观察";
      require(!selected.playbackUsed, "空闲验收已有实际播放，应使用独立真实播放矩阵");
      HealthWindow health = selected.health;
      long observedBefore = health == null ? 0 : health.observedMillis();
      long started = SystemClock.elapsedRealtime();
      while (SystemClock.elapsedRealtime() - started < observeMillis) {
        Observation idle = observe();
        noActivity(idle);
        require(!idle.stopped && !idle.playbackUsed, "空闲观察期间业务停止或开始实际播放");
        require(expectedSnapshot.equals(idle.snapshot) && expectedPhase.equals(idle.phase)
            && expectedStable.equals(idle.stable) && expectedPending.equals(idle.pending),
            "空闲服务启动改变了选择、待重启或稳定事务");
        SystemClock.sleep(100);
      }
      long observedAfter = health == null ? 0 : health.observedMillis();
      require(observedAfter == observedBefore, "无页面且未播放的服务仍累计了健康时间");
      Observation end = observe();
      require(end.revision >= minimumRevision && end.trustVersion >= minimumTrust, "版本或信任下限下降");
      require(end.revision >= selected.revision && end.trustVersion >= selected.trustVersion, "本次观察回滚了版本下限");
      report.put("passed", true).put("productionTouched", false)
          .put("consumer", consumer).put("pid", android.os.Process.myPid())
          .put("activityCreations", activityCreations.get()).put("activityStarts", activityStarts.get())
          .put("pages", end.pages).put("windows", end.windows).put("journalAvailable", end.journalAvailable)
          .put("snapshot", end.snapshot.isEmpty() ? "apk" : end.snapshot)
          .put("stable", end.stable.isEmpty() ? "apk" : end.stable).put("candidate", end.candidate)
          .put("pending", end.pending).put("phase", end.phase).put("runtimeHash", end.runtime)
          .put("revision", end.revision).put("trustVersion", end.trustVersion)
          .put("playbackConnected", end.playbackConnected).put("playbackUsed", end.playbackUsed)
          .put("foregroundStartObserved", foregroundStarted).put("foregroundPolicyObserved", foregroundPolicyCreated)
          .put("idleObservationMillis", SystemClock.elapsedRealtime() - started)
          .put("healthBeforeMillis", observedBefore).put("healthAfterMillis", observedAfter)
          .put("healthyPromotionChecked", false).put("stateInjected", false);
      success = true;
      result.putString("stream", "通过：真实服务无 Activity 冷启动、来源与待重启选择符合预期，空闲服务未累计健康时间。\n");
    } catch (Throwable error) {
      // 不输出异常正文、配置或网络地址，避免错误链携带下载授权 URL。
      try {
        diagnostic(report);
        report.put("passed", false).put("stage", stage).put("failureClass", error.getClass().getName());
      } catch (Exception diagnosticError) {
        try { report.put("diagnosticFailureClass", diagnosticError.getClass().getName()); }
        catch (org.json.JSONException ignored) {}
      }
      result.putString("stream", "失败阶段：" + stage + "；检查编号："
          + (failureCode.isEmpty() ? "OPERATION_FAILED" : failureCode) + "；检查：" + failedCheck
          + "；异常类型：" + error.getClass().getSimpleName() + "\n");
    } finally {
      application.unregisterActivityLifecycleCallbacks(activities);
      if (settingsChanged) {
        try {
          restoreSetting("enabled_accessibility_services", previousServices);
          restoreSetting("accessibility_enabled", previousEnabled);
        } catch (Throwable error) {
          success = false;
          result.putString("cleanup", "无障碍设置恢复失败：" + error.getClass().getSimpleName());
        }
      }
      try {
        report.put("passed", success);
        Files.write(new File(getTargetContext().getFilesDir(), "native-service-cold-report.json").toPath(),
            report.toString(2).getBytes(StandardCharsets.UTF_8));
      } catch (Throwable error) {
        success = false;
        result.putString("report", "报告保存失败：" + error.getClass().getSimpleName());
      }
    }
    finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }
}
