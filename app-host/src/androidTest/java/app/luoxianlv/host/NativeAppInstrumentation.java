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
    automation.clearCache();
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

  private String shell(String command) throws Exception {
    try (var input =
        new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
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
          app instanceof Application && app != main.getApplication(), "业务 Application 没有保留本代资源边界");
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
          getTargetContext().getPackageName() + "/app.luoxianlv.service.MusicAccessibilityService";
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
      await("独立业务演练场未准备好", () -> PracticeBridge.ready() && playing.hasWindowFocus());
      onMain(
          () -> {
            View keyboard =
                view(
                    playing.getWindow().getDecorView(),
                    "app.luoxianlv.ui.practice.PracticeKeyboard");
            require(
                keyboard != null
                    && keyboard.getClass().getClassLoader() == business
                    && keyboard.getAlpha() > .99f,
                "口琴没有由业务包真实呈现");
            require(
                keyboard.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
                "业务窗口未跟随横屏配置");
            require(
                playing.getResources().getIdentifier("AppTheme", "style", "app.luoxianlv.business")
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
    } catch (Throwable failure) {
      report.putString("stream", android.util.Log.getStackTraceString(failure));
    } finally {
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
