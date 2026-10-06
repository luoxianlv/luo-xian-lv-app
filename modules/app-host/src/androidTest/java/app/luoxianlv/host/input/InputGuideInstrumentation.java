package app.luoxianlv.host.input;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import app.luoxianlv.host.Bootstrap;
import app.luoxianlv.hot.contract.PlaybackBridge;
import app.luoxianlv.hot.contract.SharedInput;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 真实业务页面的配对指引验收；不读取配对码或安装凭据，不实际建立无线连接。 */
public final class InputGuideInstrumentation extends Instrumentation {
  private UiAutomation automation;
  private SharedInput.Bridge bridge;
  private NotificationManager notifications;
  private final AtomicBoolean settingsEvent = new AtomicBoolean();
  private final AtomicBoolean notifiedAtSettings = new AtomicBoolean();

  @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

  @Override public void onStart() {
    Bundle report = new Bundle();
    boolean success = false, saved = false;
    String previousMode = SharedInput.ACCESSIBILITY;
    String previousPreference = SharedInput.ACCESSIBILITY;
    boolean modePresent = false, pairedPresent = false, previousPaired = false;
    SharedPreferences modes = null, wireless = null;
    try {
      require(getTargetContext().getPackageName().endsWith(".debug"), "仅允许运行 Debug 宿主");
      require(Build.VERSION.SDK_INT >= 30, "无线指南启动验收需要 Android 11 或更新版本");
      require(android.provider.Settings.canDrawOverlays(getTargetContext()),
          "请先允许悬浮窗权限，再运行输入模式配对指引验收");
      automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
      var info = automation.getServiceInfo();
      info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
      automation.setServiceInfo(info);
      openMain();
      await("独立业务包没有准备好", () -> {
        try { return Bootstrap.source().prepared != null; }
        catch (IllegalStateException pending) { return false; }
      });
      ClassLoader business = Bootstrap.source().prepared.classLoader();
      Class<?> page = Class.forName("app.luoxianlv.settings.InputModeScreenKt", false, business);
      require(page.getClassLoader() == business && business != getTargetContext().getClassLoader(),
          "输入页面未由独立业务加载器提供");
      await("输入桥尚未初始化", () -> SharedInput.current() != null
          && SharedInput.current().state().getBoolean("initialized"));
      bridge = SharedInput.current();
      var playback = PlaybackBridge.current();
      require(playback == null || !playback.query("state").getBoolean("playing"),
          "请先暂停演奏，测试不会抢占正在使用的触屏");
      previousMode = bridge.state().getString("mode", SharedInput.ACCESSIBILITY);
      modes = getTargetContext().getSharedPreferences("input-mode", Context.MODE_PRIVATE);
      wireless = getTargetContext().getSharedPreferences("input-wireless", Context.MODE_PRIVATE);
      modePresent = modes.contains("mode");
      previousPreference = modes.getString("mode", SharedInput.ACCESSIBILITY);
      pairedPresent = wireless.contains("paired");
      previousPaired = wireless.getBoolean("paired", false);
      saved = true;
      notifications = getTargetContext().getSystemService(NotificationManager.class);
      bridge.command("disconnect", new Bundle());
      await("旧输入连接尚未退出", () -> !bridge.state().getBoolean("connected")
          && !bridge.state().getBoolean("active") && !hasPairingNotification());
      bridge.select(SharedInput.ACCESSIBILITY);
      await("测试前未回到无障碍模式", () -> SharedInput.ACCESSIBILITY.equals(bridge.state().getString("mode")));
      require(wireless.edit().putBoolean("paired", false).commit(), "无法保存临时配对标记");

      openInputPage();
      click("无线调试（内置）");
      await("无线模式未刷新", () -> SharedInput.WIRELESS.equals(bridge.state().getString("mode")));
      require(!bridge.state().getBoolean("paired"), "旧配对标记掩盖了首次使用界面");
      await("首次使用没有显示配对入口", () -> find("配对并连接", false) != null);
      require(count("配对并连接") == 1, "首次使用出现多个同名主操作");
      for (String obsolete : new String[] {"自动连接", "开始配对", "填写配对码", "重新检查"})
        require(find(obsolete, false) == null, "首次使用仍显示分散操作：" + obsolete);
      click("配对并连接");
      await("没有进入无线指南", () -> find("无线调试使用帮助", false) != null);
      for (String step : new String[] {"1. 准备 Wi-Fi 和通知", "2. 开启无线调试", "3. 显示配对码", "4. 在通知里填写"})
        scrollTo(step, false);
      scrollTo("开源许可", false);
      click("开源许可");
      await("许可说明没有读取实际 NOTICE，或丢失 MIT／Apache 来源", () -> {
        AccessibilityNodeInfo notice = find("Shizuku-API", true);
        if (notice == null || notice.getText() == null) return false;
        String text = notice.getText().toString();
        return text.contains("MIT") && text.contains("Apache-2.0") && text.contains("LibADB");
      });
      click("关闭");
      back();
      await("指南返回后输入设置没有恢复", () -> find("配对并连接", false) != null);
      require(SharedInput.WIRELESS.equals(bridge.state().getString("mode"))
          && !bridge.state().getBoolean("paired"), "指南返回改变了模式或配对状态");
      click("配对并连接");
      scrollTo("开始配对，打开设置", false);
      Bundle state = bridge.state();
      require(state.getBoolean("wifiConnected"), "请先连接 Wi-Fi，再运行系统设置跳转验收");
      require(state.getBoolean("notificationGranted"), "请先允许应用及配对通道通知，再运行启动验收");
      require(Build.VERSION.SDK_INT < 37 || state.getBoolean("localNetworkGranted"),
          "请先允许本地网络权限，再运行启动验收");
      require(!hasPairingNotification(), "点击前已有配对通知，无法验证先启动通知的顺序");
      automation.setOnAccessibilityEventListener(event -> {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            && "com.android.settings".contentEquals(String.valueOf(event.getPackageName()))) {
          if (settingsEvent.compareAndSet(false, true)) notifiedAtSettings.set(hasPairingNotification());
        }
      });
      click("开始配对，打开设置");
      await("开始配对后没有打开系统设置", this::settingsForeground);
      await("没有观察到系统设置进入前台", settingsEvent::get);
      require(notifiedAtSettings.get() && hasPairingNotification(),
          "系统设置已打开，但应用 ID7212 的前台配对通知尚未启动");
      require(!bridge.state().getBoolean("paired"), "指南测试不应绕过真实配对步骤");
      report.putString("stream", "输入指南验收通过：独立业务页面、唯一配对入口、四步说明、真实 NOTICE、返回保留状态，以及配对通知先启动再打开设置；未填写配对码。\n");
      success = true;
    } catch (Throwable failure) {
      report.putString("stream", "输入指南验收失败：" + failure + "\n");
      report.putString("error", android.util.Log.getStackTraceString(failure));
    } finally {
      if (automation != null) automation.setOnAccessibilityEventListener(null);
      if (saved) {
        try {
          bridge.command("disconnect", new Bundle());
          await("测试清理没有退出配对会话", () -> !bridge.state().getBoolean("connected")
              && !bridge.state().getBoolean("active") && !hasPairingNotification());
          var pairedEditor = wireless.edit();
          if (pairedPresent) pairedEditor.putBoolean("paired", previousPaired);
          else pairedEditor.remove("paired");
          require(pairedEditor.commit(), "无法恢复原配对标记");
          String restoreMode = previousMode;
          bridge.select(restoreMode);
          await("无法恢复原输入模式", () -> restoreMode.equals(bridge.state().getString("mode")));
          var modeEditor = modes.edit();
          if (modePresent) modeEditor.putString("mode", previousPreference);
          else modeEditor.remove("mode");
          require(modeEditor.commit(), "无法恢复原输入偏好");
          openMain();
        } catch (Throwable cleanup) {
          success = false;
          report.putString("cleanup", android.util.Log.getStackTraceString(cleanup));
        }
      }
    }
    finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, report);
  }

  private void openMain() {
    getTargetContext().startActivity(new Intent().setClassName(getTargetContext(), "app.luoxianlv.MainActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
  }

  private void openInputPage() {
    // 上一次运行可能留在子页，先返回实际设置页，再从真实设置项进入。
    for (int attempts = 0; attempts < 5 && find("网站账号", false) == null; attempts++) {
      if (find("设置", false) != null) { click("设置"); break; }
      if (find("返回", false) != null) back();
      else SystemClock.sleep(500);
    }
    await("真实设置页没有显示", () -> find("网站账号", false) != null);
    scrollTo("输入模式", false);
    click("输入模式");
    await("真实输入模式页面没有显示", () -> find("无线调试（内置）", false) != null);
  }

  private boolean hasPairingNotification() {
    if (notifications == null) return false;
    for (var item : notifications.getActiveNotifications()) {
      if (item.getId() == 7212 && getTargetContext().getPackageName().equals(item.getPackageName())
          && (item.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return true;
    }
    return false;
  }

  private boolean settingsForeground() {
    AccessibilityNodeInfo root = automation.getRootInActiveWindow();
    return root != null && "com.android.settings".contentEquals(String.valueOf(root.getPackageName()));
  }

  private AccessibilityNodeInfo find(String text, boolean contains) {
    if (Build.VERSION.SDK_INT >= 33) automation.clearCache();
    for (var window : automation.getWindows()) {
      AccessibilityNodeInfo value = find(window.getRoot(), text, contains);
      if (value != null) return value;
    }
    return find(automation.getRootInActiveWindow(), text, contains);
  }

  private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String text, boolean contains) {
    if (node == null) return null;
    if (node.isVisibleToUser() && getTargetContext().getPackageName().contentEquals(String.valueOf(node.getPackageName()))) {
      String value = String.valueOf(node.getText()), description = String.valueOf(node.getContentDescription());
      if (contains ? value.contains(text) || description.contains(text) : text.equals(value) || text.equals(description)) return node;
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfo value = find(node.getChild(i), text, contains);
      if (value != null) return value;
    }
    return null;
  }

  private int count(String text) {
    int total = 0;
    for (var window : automation.getWindows()) total += count(window.getRoot(), text);
    return total;
  }

  private int count(AccessibilityNodeInfo node, String text) {
    if (node == null) return 0;
    int total = node.isVisibleToUser() && getTargetContext().getPackageName().contentEquals(String.valueOf(node.getPackageName()))
        && text.equals(String.valueOf(node.getText())) ? 1 : 0;
    for (int i = 0; i < node.getChildCount(); i++) total += count(node.getChild(i), text);
    return total;
  }

  private void click(String text) {
    AccessibilityNodeInfo node = find(text, false);
    require(node != null, "未显示：" + text);
    while (!node.isClickable() && node.getParent() != null) node = node.getParent();
    require(node.isEnabled() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "不能点击：" + text);
    SystemClock.sleep(250);
  }

  private void scrollTo(String text, boolean contains) {
    for (int attempts = 0; attempts < 15; attempts++) {
      if (find(text, contains) != null) return;
      require(scroll(automation.getRootInActiveWindow()), "无法滚动到：" + text);
      SystemClock.sleep(200);
    }
    require(find(text, contains) != null, "滚动后未显示：" + text);
  }

  private boolean scroll(AccessibilityNodeInfo node) {
    if (node == null) return false;
    if (node.isVisibleToUser() && node.isScrollable()
        && getTargetContext().getPackageName().contentEquals(String.valueOf(node.getPackageName()))
        && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
    for (int i = 0; i < node.getChildCount(); i++) if (scroll(node.getChild(i))) return true;
    return false;
  }

  private void back() {
    require(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK), "无法执行系统返回");
    SystemClock.sleep(300);
  }

  private static void await(String message, BooleanSupplier condition) {
    long until = SystemClock.elapsedRealtime() + 30000;
    while (!condition.getAsBoolean()) {
      require(SystemClock.elapsedRealtime() < until, message);
      SystemClock.sleep(50);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
