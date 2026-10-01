package app.luoxianlv.host;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import app.luoxianlv.hot.contract.PlaybackBridge;
import app.luoxianlv.hot.contract.PracticeBridge;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** 测试 APK 本身也不引入 Kotlin，避免掩盖宿主类加载边界。 */
public final class NativeAppInstrumentation extends Instrumentation {
  private UiAutomation automation;
  private boolean groupChecks;
  private String online;
  private boolean onlineRollback;
  private boolean onlinePersist;
  private String startupSnapshot;
  private String automaticSnapshot;
  private String resourceSnapshot;
  private String rejectedResource, resourceFallback;
  private boolean prepareUserWallpaper;
  private String renderUserWallpaper;
  private boolean schedulerOnly, schedulerPractice, schedulerOffline;
  private boolean receiptFault;
  private boolean receiptRecovered;
  private String restartPrepared;
  private String coldRuntime;
  private String offlineRestart;
  private boolean holdWork;
  private String crashStable, recoveredSnapshot, recoveredFrom;
  private String prepareStable;
  private String controlledRecovery;
  private boolean collectionOnly;
  private String continuousPlan;
  private boolean compiledContractOnly;
  private app.luoxianlv.hot.contract.ProcessHooks heldProcess;
  private final java.util.concurrent.CountDownLatch workRelease =
      new java.util.concurrent.CountDownLatch(1);

  private void onMain(Runnable action) {
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable error) {
            failure.set(error);
          }
        });
    if (failure.get() != null) throw new AssertionError("主线程检查失败", failure.get());
  }

  private void step(String message) {
    Bundle status = new Bundle();
    status.putString("stream", message + "\n");
    sendStatus(0, status);
  }

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    groupChecks = arguments != null && "true".equals(arguments.getString("group"));
    online = arguments == null ? null : arguments.getString("online");
    onlineRollback = arguments != null && "true".equals(arguments.getString("onlineRollback"));
    onlinePersist = arguments != null && "true".equals(arguments.getString("onlinePersist"));
    startupSnapshot = arguments == null ? null : arguments.getString("startupSnapshot");
    automaticSnapshot = arguments == null ? null : arguments.getString("automaticSnapshot");
    resourceSnapshot = arguments == null ? null : arguments.getString("resourceSnapshot");
    rejectedResource = arguments == null ? null : arguments.getString("rejectedResource");
    resourceFallback = arguments == null ? null : arguments.getString("resourceFallback");
    prepareUserWallpaper =
        arguments != null && "true".equals(arguments.getString("prepareUserWallpaper"));
    renderUserWallpaper = arguments == null ? null : arguments.getString("renderUserWallpaper");
    schedulerOnly = arguments != null && "true".equals(arguments.getString("schedulerOnly"));
    schedulerPractice =
        arguments != null && "true".equals(arguments.getString("schedulerPractice"));
    schedulerOffline = arguments != null && "true".equals(arguments.getString("schedulerOffline"));
    receiptFault = arguments != null && "true".equals(arguments.getString("receiptFault"));
    receiptRecovered = arguments != null && "true".equals(arguments.getString("receiptRecovered"));
    restartPrepared = arguments == null ? null : arguments.getString("restartPrepared");
    coldRuntime = arguments == null ? null : arguments.getString("coldRuntime");
    offlineRestart = arguments == null ? null : arguments.getString("offlineRestart");
    holdWork = arguments != null && "true".equals(arguments.getString("holdWork"));
    crashStable = arguments == null ? null : arguments.getString("crashStable");
    recoveredSnapshot = arguments == null ? null : arguments.getString("recoveredSnapshot");
    recoveredFrom = arguments == null ? null : arguments.getString("recoveredFrom");
    prepareStable = arguments == null ? null : arguments.getString("prepareStable");
    controlledRecovery = arguments == null ? null : arguments.getString("controlledRecovery");
    collectionOnly = arguments != null && "true".equals(arguments.getString("collectionOnly"));
    continuousPlan = arguments == null ? null : arguments.getString("continuousPlan");
    compiledContractOnly =
        arguments != null && "true".equals(arguments.getString("compiledContractOnly"));
    start();
  }

  private void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private void await(String message, BooleanSupplier condition) {
    long until = SystemClock.uptimeMillis() + 60000;
    while (!condition.getAsBoolean()) {
      require(SystemClock.uptimeMillis() < until, message);
      SystemClock.sleep(60);
    }
  }

  private AccessibilityNodeInfo find(String text, AccessibilityNodeInfo node) {
    if (node == null) return null;
    if (node.isVisibleToUser()
        && (text.equals(String.valueOf(node.getText()))
            || text.equals(String.valueOf(node.getContentDescription())))) return node;
    for (int i = 0; i < node.getChildCount(); i++) {
      var result = find(text, node.getChild(i));
      if (result != null) return result;
    }
    return null;
  }

  private AccessibilityNodeInfo find(String text) {
    if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache();
    for (var window : automation.getWindows()) {
      var found = find(text, window.getRoot());
      if (found != null) return found;
    }
    return find(text, automation.getRootInActiveWindow());
  }

  private void click(String text) {
    AccessibilityNodeInfo node = find(text);
    require(node != null, "未显示：" + text);
    while (!node.isClickable() && node.getParent() != null) node = node.getParent();
    require(node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "不能点击：" + text);
    SystemClock.sleep(500);
  }

  private void dismissFullscreenHint() {
    var title = find("Viewing full screen");
    var button = find("Got it");
    if (button == null) button = find("GOT IT");
    if (title != null
        && button != null
        && ("com.android.systemui".contentEquals(title.getPackageName())
            || "android".contentEquals(title.getPackageName()))
        && title.getPackageName().equals(button.getPackageName()))
      require(button.performAction(AccessibilityNodeInfo.ACTION_CLICK), "系统全屏首次提示无法关闭");
  }

  private String shell(String command) throws Exception {
    try (var input =
        new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))) {
      var output = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      for (int count; (count = input.read(buffer)) != -1; ) {
        require(output.size() + count <= 1 << 20, "系统命令输出超限");
        output.write(buffer, 0, count);
      }
      return output.toString(StandardCharsets.UTF_8.name()).trim();
    }
  }

  private View view(View root, String className) {
    if (root.getClass().getName().equals(className)) return root;
    if (root instanceof ViewGroup) {
      ViewGroup parent = (ViewGroup) root;
      for (int i = 0; i < parent.getChildCount(); i++) {
        View result = view(parent.getChildAt(i), className);
        if (result != null) return result;
      }
    }
    return null;
  }

  private void checkTheme(Context context, ClassLoader business, ClassLoader runtime)
      throws Exception {
    int style =
        Class.forName("app.luoxianlv.R$style", false, business).getField("AppTheme").getInt(null);
    Context themed = new android.view.ContextThemeWrapper(context, style);
    StringBuilder diagnostics =
        new StringBuilder(
            "style="
                + Integer.toHexString(style)
                + ",table="
                + Integer.toHexString(
                    context
                        .getResources()
                        .getIdentifier("AppTheme", "style", "app.luoxianlv.business"))
                + ",name="
                + context.getResources().getResourceName(style)
                + "; ");
    boolean valid = true;
    for (String name : new String[] {"colorPrimaryVariant", "isMaterialTheme"}) {
      int id =
          Class.forName("com.google.android.material.R$attr", false, runtime)
              .getField(name)
              .getInt(null);
      android.util.TypedValue value = new android.util.TypedValue();
      boolean resolved = themed.getTheme().resolveAttribute(id, value, true);
      var direct = context.getResources().newTheme();
      direct.applyStyle(style, true);
      android.util.TypedValue raw = new android.util.TypedValue();
      boolean standalone = direct.resolveAttribute(id, raw, true);
      diagnostics
          .append(name)
          .append("=")
          .append(Integer.toHexString(id))
          .append(" resolved=")
          .append(resolved)
          .append(" value=")
          .append(value)
          .append(" direct=")
          .append(standalone)
          .append("/")
          .append(raw)
          .append("; ");
      valid &= resolved;
    }
    require(valid, "跨包主题没有正确解析：" + diagnostics);
  }

  private void checkConfigurations(Context context) {
    var portrait = new android.content.res.Configuration();
    portrait.orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT;
    portrait.screenWidthDp = 350;
    portrait.screenHeightDp = 780;
    portrait.fontScale = 1.3f;
    portrait.uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES;
    var landscape = new android.content.res.Configuration();
    landscape.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    landscape.screenWidthDp = 780;
    landscape.screenHeightDp = 350;
    landscape.fontScale = 1f;
    landscape.uiMode = android.content.res.Configuration.UI_MODE_NIGHT_NO;
    Context first = context.createConfigurationContext(portrait);
    Context second = context.createConfigurationContext(landscape);
    require(first.getResources() != second.getResources(), "两个窗口共用了配置资源");
    var retained = first.getResources();
    int color = retained.getIdentifier("app_window_background", "color", "app.luoxianlv.business");
    require(
        color != 0 && retained.getColor(color, null) != second.getResources().getColor(color, null),
        "业务夜间资源未按窗口隔离：id="
            + Integer.toHexString(color)
            + "，first="
            + retained.getConfiguration()
            + "，second="
            + second.getResources().getConfiguration()
            + "，colors="
            + (color == 0
                ? "missing"
                : Integer.toHexString(retained.getColor(color, null))
                    + "/"
                    + Integer.toHexString(second.getResources().getColor(color, null))));
    require(
        retained.getConfiguration().orientation == portrait.orientation
            && retained.getConfiguration().screenWidthDp == 350
            && retained.getConfiguration().fontScale == 1.3f,
        "第二窗口污染第一个窗口配置");
    require(
        first.getClassLoader() == context.getClassLoader()
            && first.getApplicationContext() == context.getApplicationContext(),
        "配置派生丢失代际加载器或 Application");
  }

  private void checkBaselineRepair() throws Exception {
    java.io.File root =
        new java.io.File(
            getTargetContext().getCacheDir(), "baseline-repair-" + SystemClock.uptimeMillis());
    require(root.mkdir(), "无法创建独立恢复验证目录");
    Context isolated =
        new android.content.ContextWrapper(getTargetContext()) {
          @Override
          public java.io.File getNoBackupFilesDir() {
            return root;
          }
        };
    try {
      app.luoxianlv.hot.BundledBaseline.prepare(isolated);
      var directories = new java.io.File(root, "native-baseline").listFiles();
      require(directories != null && directories.length == 1, "内置模块没有唯一内容目录");
      var business = new java.io.File(directories[0], "business.apk");
      byte[] expected =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(java.nio.file.Files.readAllBytes(business.toPath()));
      require(!business.canWrite() && business.setWritable(true, true), "内置模块没有设为只读");
      java.nio.file.Files.write(business.toPath(), new byte[] {1, 2, 3});
      app.luoxianlv.hot.BundledBaseline.prepare(isolated);
      require(
          java.util.Arrays.equals(
                  expected,
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(java.nio.file.Files.readAllBytes(business.toPath())))
              && !business.canWrite(),
          "损坏缓存未从 APK 恢复");
    } finally {
      try (var files = java.nio.file.Files.walk(root.toPath())) {
        for (var file :
            files
                .sorted(java.util.Comparator.reverseOrder())
                .collect(java.util.stream.Collectors.toList())) java.nio.file.Files.delete(file);
      }
    }
  }

  @Override
  public void onStart() {
    Bundle report = new Bundle();
    Activity main = null, stage = null;
    String previousServices = null, previousEnabled = null;
    Boolean previousFloating = null;
    boolean success = false;
    try {
      automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
      var info = automation.getServiceInfo();
      info.flags |=
          android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
      automation.setServiceInfo(info);
      previousServices = shell("settings get secure enabled_accessibility_services");
      previousEnabled = shell("settings get secure accessibility_enabled");
      if (continuousPlan != null) NativeContinuousChecks.pauseBeforeActivity(this);
      if (holdWork) {
        await(
            "持有工作前业务未就绪",
            () -> {
              try {
                Bootstrap.source();
                return true;
              } catch (Exception notReady) {
                return false;
              }
            });
        var field = Bootstrap.class.getDeclaredField("process");
        field.setAccessible(true);
        heldProcess = (app.luoxianlv.hot.contract.ProcessHooks) field.get(null);
        var loader = Bootstrap.source().prepared.classLoader();
        var jobs = Class.forName("app.luoxianlv.business.BusinessJobs", true, loader);
        var function = Class.forName("kotlin.jvm.functions.Function0", false, loader);
        var began = new java.util.concurrent.CountDownLatch(1);
        Object action =
            java.lang.reflect.Proxy.newProxyInstance(
                loader,
                new Class<?>[] {function},
                (proxy, method, args) -> {
                  if (method.getName().equals("invoke")) {
                    began.countDown();
                    workRelease.await();
                    return Class.forName("kotlin.Unit", false, loader)
                        .getField("INSTANCE")
                        .get(null);
                  }
                  return null;
                });
        require(
            (Boolean)
                jobs.getMethod("thread", String.class, function)
                    .invoke(jobs.getField("INSTANCE").get(null), "代际工作验收", action),
            "旧代际拒绝测试工作");
        require(began.await(5, java.util.concurrent.TimeUnit.SECONDS), "测试工作未运行");
      }
      main =
          startActivitySync(
              new Intent()
                  .setClassName(getTargetContext(), "app.luoxianlv.MainActivity")
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      await(
          "内置业务未就绪",
          () -> {
            try {
              Bootstrap.source();
              return true;
            } catch (Exception unavailable) {
              return false;
            }
          });
      Bootstrap.Source source = Bootstrap.source();
      if (holdWork) {
        SystemClock.sleep(3000);
        require(Bootstrap.source() == source && !heldProcess.canReplace(), "旧工作尚未完成就切换代际");
        workRelease.countDown();
      }
      if (schedulerOnly) {
        if (schedulerOffline) NativeSchedulerChecks.offline(this, main);
        else NativeSchedulerChecks.run(this, main, schedulerPractice);
        report.putString("stream", "通过：真实普通宿主调度观察，未注入时钟或更新状态。\n");
        success = true;
      } else if (prepareUserWallpaper) {
        var fixture = NativeUserWallpaperFixture.prepare(this, main);
        require(
            fixture.getInt("fileCount") > 0 && fixture.getBoolean("selectionRestored"),
            "用户项目准备未完成");
        report.putString("stream", "通过：生产导入器建立非空外部用户壁纸项目，原选择与既有项目保持。\n");
        success = true;
      } else if (renderUserWallpaper != null) {
        var result = NativeUserWallpaperFixture.render(this, main, renderUserWallpaper);
        require(
            result.getBoolean("passed")
                && result.getBoolean("selectionRestored")
                && result.getBoolean("allUserProjectsUnchanged"),
            "用户壁纸实际首帧或清理检查未完成");
        report.putString("stream", "通过：非空用户壁纸在真实演练场显示首帧，原选择与项目文件保持。\n");
        success = true;
      } else if (compiledContractOnly) {
        NativeCompiledContractChecks.run(this);
        report.putString("stream", "通过：签名有效但编译 SDK 错误的候选在加载前拒绝，当前业务与运行时保持。\n");
        success = true;
      } else if (continuousPlan != null) {
        NativeContinuousChecks.run(this, main, continuousPlan, this::click, previousServices);
        report.putString("stream", "通过：同一 PID 连续不同业务候选、真实健康观察、整组回退与宿主持有上界。\n");
        success = true;
      } else if (collectionOnly) {
        NativeContentCollectionChecks.run(this);
        report.putString("stream", "通过：真实宿主后台更新线程回收无引用对象，保留运行和恢复组合，页面继续可用。\n");
        success = true;
      } else if (controlledRecovery != null) {
        NativeControlledRecoveryChecks.run(
            this, main, controlledRecovery, previousServices, previousEnabled);
      } else if (prepareStable != null) {
        NativeStableRecoveryChecks.prepare(this, main, prepareStable);
        report.putString("stream", "通过：真实进程故障前的稳定组合与用户文件资料准备完成。\n");
        success = true;
      } else if (crashStable != null) {
        NativeStableRecoveryChecks.crash(this, main, crashStable);
      } else if (recoveredSnapshot != null) {
        NativeStableRecoveryChecks.recovered(this, main, recoveredSnapshot, recoveredFrom);
        report.putString("stream", "通过：稳定组合真实进程崩溃后，在业务加载前恢复旧版、隔离内容并保留用户文件和版本下限。\n");
        success = true;
      } else if (offlineRestart != null) {
        require(app.luoxianlv.hot.HotManifest.validHash(offlineRestart), "离线目标身份无效");
        var startup = Bootstrap.startupState();
        require(
            startup != null && startup.pendingRestart.current().equals(offlineRestart),
            "离线启动丢失待更新组合");
        var cached = startup.store.snapshot(offlineRestart);
        require(
            !source.prepared.runtimeHash.equals(cached.manifest.runtime.sha256),
            "离线仍加载了未取得新许可的运行时");
        require(
            startup.journal.state().phase == app.luoxianlv.hot.ActivationJournal.Phase.STABLE
                && startup.journal.state().quarantine.isEmpty(),
            "离线失败被错误记为内容故障");
        Activity retained = main;
        await("离线恢复页面没有显示", () -> find("演练场") != null && retained.hasWindowFocus());
        var result =
            new org.json.JSONObject()
                .put("passed", true)
                .put("productionTouched", false)
                .put("target", offlineRestart)
                .put("pendingPreserved", true)
                .put("oldRuntimeUsed", true)
                .put("oldPageVisible", true)
                .put("notQuarantined", true);
        java.nio.file.Files.write(
            new java.io.File(getTargetContext().getFilesDir(), "native-cold-offline-report.json")
                .toPath(),
            result.toString(2).getBytes(StandardCharsets.UTF_8));
        report.putString("stream", "通过：待更新组合离线启动保留缓存，原组合正常显示，不隔离内容。\n");
        success = true;
      } else if (coldRuntime != null) {
        NativeColdRuntimeChecks.run(this, main, coldRuntime);
        report.putString("stream", "通过：普通冷启动重新授权，实际新共享运行时与业务整组启用、真实观察及回报。\n");
        success = true;
      } else if (restartPrepared != null) {
        NativeRestartChecks.run(this, restartPrepared);
        report.putString("stream", "通过：不同共享运行时完整缓存并持久等待重启，当前组合未改变。\n");
        success = true;
      } else if (rejectedResource != null) {
        NativeResourceChecks.rejected(this, main, rejectedResource, resourceFallback);
        report.putString("stream", "通过：损坏的官方渲染资源隔离，原稳定首帧恢复，用户壁纸文件保持。\n");
        success = true;
      } else if (resourceSnapshot != null) {
        NativeResourceChecks.run(this, main, resourceSnapshot);
        report.putString("stream", "通过：普通资源更新、真实音色/主题/配置/AGSL消费及真实健康观察。\n");
        success = true;
      } else if (automaticSnapshot != null) {
        NativeAutomaticChecks.run(this, main, automaticSnapshot, receiptFault);
        if (holdWork) require(heldProcess.released(), "健康确认后旧进程业务队列未退出");
        report.putString("stream", "通过：普通入口自动更新验收。\n");
        success = true;
      } else if (startupSnapshot != null) {
        require(
            getTargetContext().getPackageName().equals("app.luoxianlv.debug")
                && app.luoxianlv.hot.HotManifest.validHash(startupSnapshot),
            "冷启动验收仅允许 Debug 和明确快照");
        require(
            source.prepared.manifest != null
                && startupSnapshot.equals(source.prepared.manifest.snapshotId),
            "普通 Application 启动未选择稳定热更");
        Activity restored = main;
        await(
            "重启后新增原生组件未显示",
            () ->
                view(
                            restored.getWindow().getDecorView(),
                            "app.luoxianlv.hot.probe.HotProbeFactory$NewNativeBadge")
                        != null
                    && restored.hasWindowFocus());
        if (receiptRecovered) {
          var queue =
              new app.luoxianlv.hot.HealthOutbox(
                  new java.io.File(
                      getTargetContext().getNoBackupFilesDir(), "native-update/health"));
          await(
              "普通冷启动未补发结果并完成确认",
              () -> {
                try {
                  return Bootstrap.startupState().journal.state().outcomes.isEmpty()
                      && queue.batch(100).isEmpty();
                } catch (Exception error) {
                  throw new AssertionError(error);
                }
              });
          var receipt =
              new org.json.JSONObject()
                  .put("passed", true)
                  .put("productionTouched", false)
                  .put("target", startupSnapshot)
                  .put("processRestarted", true)
                  .put("outcomeReplayed", true)
                  .put("serverAcknowledged", true)
                  .put("journalReceiptCleared", true);
          java.nio.file.Files.write(
              new java.io.File(
                      getTargetContext().getFilesDir(), "native-receipt-recovered-report.json")
                  .toPath(),
              receipt.toString(2).getBytes(StandardCharsets.UTF_8));
          report.putString("stream", "通过：真实健康结果跨进程补发，接口确认后队列与日志回执均完成交接。\n");
        } else report.putString("stream", "通过：普通冷启动离线读取稳定签名版本并显示新增原生组件。\n");
        success = true;
      } else {
        ClassLoader business = source.factory.getClass().getClassLoader();
        ClassLoader runtime = Class.forName("kotlin.Unit", false, business).getClassLoader();
        require(business != runtime && runtime == business.getParent(), "业务没有复用独立共享运行时");
        require(
            Class.forName("app.luoxianlv.business.MainPage", false, business).getClassLoader()
                == business,
            "主页不是业务模块提供");
        for (String name :
            new String[] {
              "kotlin.Unit",
              "androidx.compose.ui.platform.ComposeView",
              "app.luoxianlv.business.MainPage"
            }) {
          boolean missing = false;
          try {
            Class.forName(name, false, getTargetContext().getClassLoader());
          } catch (ClassNotFoundException expected) {
            missing = true;
          }
          require(missing, "宿主混入动态代码：" + name);
        }
        boolean noAnalytics = false;
        try {
          Class.forName("com.umeng.commonsdk.UMConfigure", false, runtime);
        } catch (ClassNotFoundException expected) {
          noAnalytics = true;
        }
        require(noAnalytics, "Debug 运行时混入统计 SDK");
        Context[] contexts = new Context[1];
        Activity home = main;
        onMain(() -> contexts[0] = source.prepared.context(home));
        Context app = contexts[0].getApplicationContext();
        require(
            app instanceof Application && app != main.getApplication(),
            "业务 Application 没有保留本代资源边界");
        try (var input = app.getAssets().open("builtin-scores/rain-love.txt")) {
          require(input.read() >= 0, "ViewModel 无法读取业务内置谱面");
        }
        checkTheme(contexts[0], business, runtime);
        onMain(() -> checkConfigurations(contexts[0]));
        checkBaselineRepair();
        require(
            main.getResources().getIdentifier("AppTheme", "style", "app.luoxianlv.business") == 0,
            "模块资源污染宿主窗口");
        step("通过：类加载边界、Material 跨包主题、独立窗口配置、损坏内置模块恢复");
        await("动态首页没有显示", () -> find("演练场") != null);
        click("设置");
        await("动态设置页没有显示", () -> find("网站账号") != null);
        click("曲库");
        await("动态曲库没有显示", () -> find("导入谱子") != null);
        step("通过：首页、设置和曲库");

        String component =
            getTargetContext().getPackageName()
                + "/app.luoxianlv.service.MusicAccessibilityService";
        String enabled =
            previousServices.equals("null") || previousServices.isEmpty()
                ? component
                : previousServices.contains(component)
                    ? previousServices
                    : previousServices + ":" + component;
        shell("settings put secure enabled_accessibility_services " + enabled);
        shell("settings put secure accessibility_enabled 1");
        await("独立业务播放服务未连接", () -> PlaybackBridge.current() != null);
        previousFloating = PlaybackBridge.current().query("state").getBoolean("floatingEnabled");
        onMain(
            () -> {
              Bundle value = new Bundle();
              value.putBoolean("enabled", true);
              PlaybackBridge.current().command("showFloating", value);
            });
        await("动态 Material 浮窗没有显示", () -> find("展开播放器") != null);
        click("展开播放器");
        await("动态 Material 面板没有显示", () -> find("选歌") != null);
        step("通过：无障碍服务与真实 Material 浮窗面板");
        if (online != null) {
          NativeFullOnlineChecks.run(this, main, online, onlineRollback, onlinePersist);
        } else if (groupChecks) {
          GroupHandoverChecks.run(this, main, this::click);
          step("通过：真实页面与播放整组交接、部分提交故障、最新状态回退及后台窗口准备");
        } else {
          PlaybackHandoverChecks.run(this, main);
          step("通过：两个业务加载器间的播放交接、过期快照拒绝、准备/激活故障与最新状态回退");
        }
        onMain(
            () -> {
              Bundle value = new Bundle();
              value.putBoolean("enabled", false);
              PlaybackBridge.current().command("showFloating", value);
            });

        stage =
            startActivitySync(
                new Intent()
                    .setClassName(getTargetContext(), "app.luoxianlv.ui.practice.PracticeActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Activity playing = stage;
        await(
            "独立业务演练场未准备好",
            () -> {
              dismissFullscreenHint();
              return PracticeBridge.ready() && playing.hasWindowFocus();
            });
        onMain(
            () -> {
              View keyboard =
                  view(
                      playing.getWindow().getDecorView(),
                      "app.luoxianlv.ui.practice.PracticeKeyboard");
              require(
                  keyboard != null
                      && keyboard.getClass().getClassLoader()
                          == Bootstrap.source().prepared.classLoader()
                      && keyboard.getAlpha() > .99f,
                  "口琴没有由业务包真实呈现");
              require(
                  keyboard.getResources().getConfiguration().orientation
                      == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
                  "业务窗口未跟随横屏配置");
              require(
                  playing
                          .getResources()
                          .getIdentifier("AppTheme", "style", "app.luoxianlv.business")
                      == 0,
                  "模块资源污染演奏宿主");
              playing.onBackPressed();
            });
        await("演练场退出未释放入口", () -> !PracticeBridge.active() && playing.isDestroyed());
        report.putString(
            "stream",
            "通过：宿主无 Kotlin/Compose/业务类，完整业务使用独立加载器并复用运行时；Debug 无统计 SDK；本代 Application"
                + " 资源、首页/设置/曲库、真实无障碍 Material 浮窗及演练场进入/退出。\n");
        success = true;
      }
    } catch (Throwable failure) {
      report.putString("stream", android.util.Log.getStackTraceString(failure));
      if (holdWork && heldProcess != null) {
        try {
          var loader = Bootstrap.source().prepared.classLoader();
          var jobs = Class.forName("app.luoxianlv.business.BusinessJobs", false, loader);
          var gate =
              (app.luoxianlv.hot.contract.WorkGate)
                  jobs.getMethod("getGate").invoke(jobs.getField("INSTANCE").get(null));
          report.putString(
              "stream",
              report.getString("stream")
                  + "\n工作诊断：支持退出="
                  + heldProcess.supportsRetirement()
                  + "，可替换="
                  + heldProcess.canReplace()
                  + "，活动任务="
                  + gate.activeCount()
                  + "\n");
          var updateField = Bootstrap.class.getDeclaredField("updates");
          updateField.setAccessible(true);
          Object updater = updateField.get(null);
          if (updater != null)
            for (String name : new String[] {"active", "busy", "blocked", "pending", "nextCheck"}) {
              var statusField = updater.getClass().getDeclaredField(name);
              statusField.setAccessible(true);
              Object value = statusField.get(updater);
              report.putString(
                  "stream",
                  report.getString("stream")
                      + name
                      + "="
                      + (name.equals("pending") ? value != null : value)
                      + "\n");
            }
          for (var thread : Thread.getAllStackTraces().entrySet()) {
            if (thread.getKey().getName().matches("平台请求|同步曲库|曲目版本检查|单曲修复|代际工作验收|谱面播放准备|曲目时长"))
              report.putString(
                  "stream",
                  report.getString("stream")
                      + thread.getKey().getName()
                      + ": "
                      + java.util.Arrays.toString(thread.getValue())
                      + "\n");
          }
        } catch (Throwable unavailable) {
          report.putString("stream", report.getString("stream") + "工作诊断不可用\n");
        }
      }
      var diagnostic = new java.util.concurrent.atomic.AtomicReference<String>("主线程未及时返回");
      var collected = new java.util.concurrent.CountDownLatch(1);
      new android.os.Handler(android.os.Looper.getMainLooper())
          .post(
              () -> {
                try {
                  var port = PlaybackBridge.current();
                  if (port == null) diagnostic.set("播放连接已关闭");
                  else {
                    var field = port.getClass().getDeclaredField("session");
                    field.setAccessible(true);
                    var session =
                        (app.luoxianlv.hot.contract.NativePlaybackSession) field.get(port);
                    var state = session.snapshot();
                    var floating = state.getBundle("floatingState");
                    diagnostic.set(
                        "请求显示="
                            + state.getBoolean("floating")
                            + "，展开="
                            + (floating != null && floating.getBoolean("expanded"))
                            + "，停靠="
                            + (floating == null ? "未知" : floating.getString("dock")));
                  }
                } catch (Throwable unavailable) {
                  diagnostic.set("读取失败：" + unavailable.getClass().getSimpleName());
                } finally {
                  collected.countDown();
                }
              });
      try {
        collected.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      report.putString("stream", report.getString("stream") + "\n浮窗诊断：" + diagnostic.get() + "\n");
      try {
        var screen = automation.takeScreenshot();
        if (screen != null)
          try (var output =
              new java.io.FileOutputStream(
                  new java.io.File(getTargetContext().getFilesDir(), "native-host-failure.png"))) {
            screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
          } finally {
            if (screen != null) screen.recycle();
          }
      } catch (Throwable unavailable) {
        report.putString(
            "stream",
            report.getString("stream")
                + "失败画面未保存："
                + unavailable.getClass().getSimpleName()
                + "\n");
      }
    } finally {
      workRelease.countDown();
      try {
        if (previousFloating != null && PlaybackBridge.current() != null) {
          Bundle restore = new Bundle();
          restore.putBoolean("enabled", previousFloating);
          onMain(
              () -> {
                if (PlaybackBridge.current() != null)
                  PlaybackBridge.current().command("showFloating", restore);
              });
        }
        if (previousServices != null)
          shell(
              previousServices.equals("null")
                  ? "settings delete secure enabled_accessibility_services"
                  : "settings put secure enabled_accessibility_services " + previousServices);
        if (previousEnabled != null)
          shell(
              previousEnabled.equals("null")
                  ? "settings delete secure accessibility_enabled"
                  : "settings put secure accessibility_enabled " + previousEnabled);
      } catch (Exception failure) {
        report.putString("cleanup", failure.toString());
      }
      Activity lastStage = stage, lastMain = main;
      onMain(
          () -> {
            if (lastStage != null && !lastStage.isDestroyed()) lastStage.finish();
            if (lastMain != null) lastMain.finish();
          });
    }
    finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, report);
  }
}
