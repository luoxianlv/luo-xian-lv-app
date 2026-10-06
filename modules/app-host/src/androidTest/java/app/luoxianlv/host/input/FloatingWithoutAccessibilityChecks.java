package app.luoxianlv.host.input;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Application;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import app.luoxianlv.host.Bootstrap;
import app.luoxianlv.hot.NativePlaybackHost;
import app.luoxianlv.hot.contract.PlaybackBridge;
import app.luoxianlv.hot.contract.SharedInput;

/** 在真实 Shizuku／无线连接下检查独立系统窗口，不启用应用无障碍，也不演奏。 */
final class FloatingWithoutAccessibilityChecks {
  static void run(Instrumentation test, UiAutomation automation) throws Exception {
    Context context = test.getTargetContext();
    require(context.getPackageName().endsWith(".debug"), "仅允许测试 Debug 宿主");
    require(!PlaybackBridge.isEnabled(context), "本项验收要求关闭落弦律无障碍");
    require(Settings.canDrawOverlays(context), "请预先允许测试 APP 显示悬浮窗");
    Bundle input = SharedInput.current().state();
    require(!SharedInput.ACCESSIBILITY.equals(input.getString("mode"))
        && input.getBoolean("connected") && input.getBoolean("touchReady"), "真实输入连接未就绪");
    require(!input.getBoolean("active"), "测试不能抢占正在捕获的触屏");
    main(test, () -> require(PlaybackBridge.current() == null && NativePlaybackHost.current() == null,
        "请先关闭已有播放器，本项测试不能抢占用户会话"));

    ClassLoader business = Bootstrap.source().prepared.classLoader();
    Class<?> repositoryType = Class.forName("app.luoxianlv.library.SongRepository", true, business);
    Object repository = repositoryType.getConstructor(Context.class).newInstance(context);
    boolean desired = (Boolean) repositoryType.getMethod("getFloatingEnabled").invoke(repository);
    Class<?> modelType = Class.forName("app.luoxianlv.library.LibraryViewModel", true, business);
    Class<?> storeType = Class.forName("androidx.lifecycle.ViewModelStore", true, business);
    Object[] store = {null}, model = {null};
    var info = automation.getServiceInfo();
    int originalFlags = info.flags;
    info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
    automation.setServiceInfo(info);
    try {
      repositoryType.getMethod("setFloatingEnabled", boolean.class).invoke(repository, false);
      context.startActivity(new Intent().setClassName(context.getPackageName(), "app.luoxianlv.MainActivity")
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
      main(test, () -> {
        store[0] = storeType.getConstructor().newInstance();
        Class<?> providerType = Class.forName("androidx.lifecycle.ViewModelProvider", true, business);
        Class<?> factoryType = Class.forName("androidx.lifecycle.ViewModelProvider$Factory", true, business);
        Object factory = Class.forName("androidx.lifecycle.ViewModelProvider$AndroidViewModelFactory", true, business)
            .getConstructor(Application.class).newInstance(context.getApplicationContext());
        Object provider = providerType.getConstructor(storeType, factoryType).newInstance(store[0], factory);
        model[0] = providerType.getMethod("get", Class.class).invoke(provider, modelType);
        modelType.getMethod("setFloatingEnabled", boolean.class).invoke(model[0], true);
      });
      await(test, "实际窗口没有挂载，不能只凭请求显示就算启动成功", () ->
          PlaybackBridge.current() != null && PlaybackBridge.current().query("state").getBoolean("floatingVisible")
              && contains(automation, "展开播放器"));
      require(!PlaybackBridge.isEnabled(context), "启动悬浮窗偷偷依赖了无障碍授权");
      click(automation, "展开播放器");
      await(test, "无障碍关闭时无法展开面板", () -> contains(automation, "选歌"));
      click(automation, "选歌");
      await(test, "无障碍关闭时没有显示选歌窗口", () -> contains(automation, "选择谱子"));
      require(PlaybackBridge.current().query("state").getBoolean("floatingVisible"), "选歌窗口期间错误报告播放器已关闭");
      click(automation, "关闭");
      await(test, "选歌关闭后没有恢复面板", () -> contains(automation, "收起"));
      click(automation, "收起");
      await(test, "无障碍关闭时无法恢复气泡", () -> contains(automation, "展开播放器"));
      main(test, () -> modelType.getMethod("setFloatingEnabled", boolean.class).invoke(model[0], false));
      await(test, "关闭后留下播放器窗口或宿主", () -> PlaybackBridge.current() == null
          && NativePlaybackHost.current() == null && !NativePlaybackHost.anyRetiring()
          && !contains(automation, "展开播放器"));
    } finally {
      main(test, () -> {
        if (model[0] != null) modelType.getMethod("setFloatingEnabled", boolean.class).invoke(model[0], false);
        if (store[0] != null) storeType.getMethod("clear").invoke(store[0]);
        repositoryType.getMethod("setFloatingEnabled", boolean.class).invoke(repository, desired);
      });
      var restored = automation.getServiceInfo();
      restored.flags = originalFlags;
      automation.setServiceInfo(restored);
    }
  }

  private static boolean contains(UiAutomation automation, String text) {
    AccessibilityNodeInfo node = find(automation, text);
    if (node == null) return false;
    node.recycle();
    return true;
  }

  private static void click(UiAutomation automation, String text) {
    AccessibilityNodeInfo node = find(automation, text);
    require(node != null, "找不到真实窗口按钮：" + text);
    try {
      require(node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "真实窗口按钮不能点击：" + text);
    } finally { node.recycle(); }
  }

  private static AccessibilityNodeInfo find(UiAutomation automation, String text) {
    for (var window : automation.getWindows()) {
      var root = window.getRoot();
      if (root == null) continue;
      try {
        var result = find(root, text);
        if (result != null) return result;
      } finally { root.recycle(); }
    }
    return null;
  }

  private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, String text) {
    if ((node.getText() != null && text.contentEquals(node.getText()))
        || (node.getContentDescription() != null && text.contentEquals(node.getContentDescription())))
      return AccessibilityNodeInfo.obtain(node);
    for (int i = 0; i < node.getChildCount(); i++) {
      var child = node.getChild(i);
      if (child == null) continue;
      try {
        var found = find(child, text);
        if (found != null) return found;
      } finally { child.recycle(); }
    }
    return null;
  }

  private static void main(Instrumentation test, Checked action) {
    Throwable[] failure = {null};
    test.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
    if (failure[0] != null) throw new AssertionError(failure[0]);
  }

  private static void await(Instrumentation test, String message, Condition condition) {
    long until = SystemClock.elapsedRealtime() + 15000;
    while (true) {
      boolean[] done = {false};
      main(test, () -> done[0] = condition.test());
      if (done[0]) return;
      require(SystemClock.elapsedRealtime() < until, message);
      SystemClock.sleep(50);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
  private interface Checked { void run() throws Exception; }
  private interface Condition { boolean test() throws Exception; }
}
