package app.luoxianlv.host.input;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import app.luoxianlv.host.Bootstrap;
import app.luoxianlv.hot.contract.SharedInput;
import java.util.function.BooleanSupplier;

/** 检查实际业务设置入口及独立播放策略；不授予权限，不改用户偏好。 */
public final class PermissionEntryInstrumentation extends Instrumentation {
  private static final String KEY = ":settings:fragment_args_key";
  private UiAutomation automation;
  private boolean startupRaces;

  @Override public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    startupRaces = arguments != null && "true".equals(arguments.getString("startupRaces"));
    start();
  }

  @Override public void onStart() {
    Bundle report = new Bundle();
    boolean success = false;
    try {
      Context context = getTargetContext();
      require(context.getPackageName().endsWith(".debug"), "仅允许 Debug 宿主验收");
      automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
      openMain();
      await("业务包尚未准备好", () -> {
        try { return Bootstrap.source().prepared != null; }
        catch (IllegalStateException pending) { return false; }
      });
      ClassLoader loader = Bootstrap.source().prepared.classLoader();
      Class<?> entries = Class.forName("app.luoxianlv.app.PermissionSettings", true, loader);
      Object instance = entries.getField("INSTANCE").get(null);
      Intent overlay = (Intent) entries.getMethod("overlay", Context.class).invoke(instance, context);
      Intent fallback = (Intent) entries.getMethod("overlayList", Context.class).invoke(instance, context);
      Intent accessibility = (Intent) entries.getMethod("accessibility", Context.class).invoke(instance, context);
      require(entries.getClassLoader() == loader, "权限入口未由实际业务包提供");
      require(("package:" + context.getPackageName()).equals(overlay.getDataString()), "悬浮窗入口未定位真实宿主包");
      require((Build.VERSION.SDK_INT >= 30 ? Settings.ACTION_APPLICATION_DETAILS_SETTINGS
          : Settings.ACTION_MANAGE_OVERLAY_PERMISSION).equals(overlay.getAction()), "使用了非公开悬浮窗入口");
      checkHighlight(overlay, Build.VERSION.SDK_INT >= 30 ? "system_alert_window" : context.getPackageName());
      require(Settings.ACTION_MANAGE_OVERLAY_PERMISSION.equals(fallback.getAction()), "缺少公开权限列表兜底");
      require(Settings.ACTION_ACCESSIBILITY_SETTINGS.equals(accessibility.getAction()), "使用了受保护的无障碍详情入口");
      checkHighlight(accessibility, new ComponentName(context.getPackageName(),
          "app.luoxianlv.service.MusicAccessibilityService").flattenToString());
      require(overlay.resolveActivity(context.getPackageManager()) != null, "当前系统无法处理悬浮窗入口");
      require(fallback.resolveActivity(context.getPackageManager()) != null, "当前系统无法处理权限列表");
      require(accessibility.resolveActivity(context.getPackageManager()) != null, "当前系统无法处理无障碍入口");
      boolean actualPermission = (Boolean) entries.getMethod("overlayGranted", Context.class).invoke(instance, context);
      require(actualPermission == Settings.canDrawOverlays(context), "权限快照与真实宿主系统授权不一致");
      require(SharedInput.current() != null, "输入桥未初始化");
      SharedInput.current().command("refresh", new Bundle());
      await("宿主授权快照未与真实权限一致", () -> {
        Bundle cached = SharedInput.current().state();
        return cached.containsKey("overlayGranted") && cached.getBoolean("overlayGranted", false) == actualPermission;
      });
      Object repository = Class.forName("app.luoxianlv.library.SongRepository", true, loader)
          .getConstructor(Context.class).newInstance(context);
      boolean desired = (Boolean) repository.getClass().getMethod("getFloatingEnabled").invoke(repository);
      Object policy = Class.forName("app.luoxianlv.playback.PlaybackServicePolicy", true, loader)
          .getConstructor(Context.class).newInstance(context);
      boolean shouldRun = (Boolean) policy.getClass().getMethod("shouldRun").invoke(policy);
      require(shouldRun == (desired && actualPermission), "播放服务策略没有按用户意图和悬浮窗授权判断");
      if (startupRaces) StartupPermissionChecks.run(this, loader);
      context.startActivity(new Intent(overlay).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      await("悬浮窗入口没有打开系统设置", () -> {
        var root = automation.getRootInActiveWindow();
        return root != null && "com.android.settings".contentEquals(String.valueOf(root.getPackageName()));
      });
      report.putString("stream", "权限入口验收通过：公开系统设置、真实宿主包定位、权限列表兜底、实际授权快照及独立播放服务策略；已提交标准高亮请求，当前系统是否滚动或高亮需实际界面判断。\n");
      if (startupRaces) report.putString("startup", "真实业务启动竞态通过：等待中模式变化、断连及取消均未开启悬浮窗；未注入触摸。\n");
      success = true;
    } catch (Throwable failure) {
      report.putString("stream", "权限入口验收失败：" + failure + "\n");
      report.putString("error", android.util.Log.getStackTraceString(failure));
    } finally {
      try { openMain(); }
      catch (Throwable cleanup) { success = false; report.putString("cleanup", android.util.Log.getStackTraceString(cleanup)); }
    }
    finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, report);
  }

  private void openMain() {
    getTargetContext().startActivity(new Intent().setClassName(getTargetContext(), "app.luoxianlv.MainActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
  }

  private static void checkHighlight(Intent intent, String key) {
    require(key.equals(intent.getStringExtra(KEY)), "缺少系统高亮键");
    Bundle arguments = intent.getBundleExtra(":settings:show_fragment_args");
    require(arguments != null && key.equals(arguments.getString(KEY)), "缺少系统设置片段的高亮参数");
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
