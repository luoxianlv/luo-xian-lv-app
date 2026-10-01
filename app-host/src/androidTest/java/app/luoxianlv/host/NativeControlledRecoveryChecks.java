package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import app.luoxianlv.hot.contract.PlaybackBridge;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** 真实两个窗口和无障碍会话被停用；只在恢复落盘后点击宿主的用户恢复按钮。 */
final class NativeControlledRecoveryChecks {
  static void run(
      Instrumentation runner,
      Activity home,
      String target,
      String previousServices,
      String previousEnabled)
      throws Exception {
    NativeStableRecoveryChecks.prepare(runner, home, target);
    var startup = Bootstrap.startupState();
    var automation = runner.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    String component =
        runner.getTargetContext().getPackageName()
            + "/app.luoxianlv.service.MusicAccessibilityService";
    String enabled =
        previousServices == null || previousServices.equals("null") || previousServices.isEmpty()
            ? component
            : previousServices.contains(component)
                ? previousServices
                : previousServices + ":" + component;
    shell(automation, "settings put secure enabled_accessibility_services " + enabled);
    shell(automation, "settings put secure accessibility_enabled 1");
    long deadline = SystemClock.elapsedRealtime() + 15000;
    while (PlaybackBridge.current() == null) {
      check(SystemClock.elapsedRealtime() < deadline, "故障前无障碍业务未连接");
      SystemClock.sleep(100);
    }
    Activity picker =
        runner.startActivitySync(
            new Intent()
                .setClassName(
                    runner.getTargetContext(), "app.luoxianlv.ui.practice.WallpaperPickerActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    while (!picker.hasWindowFocus()) {
      check(SystemClock.elapsedRealtime() < deadline, "故障前第二个窗口未就绪");
      SystemClock.sleep(100);
    }
    AtomicReference<Button> reopen = new AtomicReference<>();
    runner.runOnMainSync(
        () -> {
          check(Bootstrap.pages().size() == 2, "故障检查遗漏后台窗口");
          ((BusinessActivity) home).stopBusiness(new IllegalStateException("测试：已捕获的稳定页面业务错误"));
          check(Bootstrap.businessStopped() && Bootstrap.pages().isEmpty(), "故障后页面仍在执行");
          check(PlaybackBridge.current() == null && !Bootstrap.canAutoActivate(), "故障后播放或热更仍能启用");
          boolean[] called = {false};
          try {
            Bootstrap.initialCreation(() -> called[0] = true);
          } catch (IllegalStateException expected) {
          }
          check(!called[0], "故障后仍构造新业务");
          check(button(home.getWindow().getDecorView()) != null, "后台窗口没有原生恢复入口");
          reopen.set(button(picker.getWindow().getDecorView()));
          check(reopen.get() != null, "前台窗口没有原生恢复入口");
        });
    deadline = SystemClock.elapsedRealtime() + 15000;
    while (!Bootstrap.recoveryAvailable()) {
      check(SystemClock.elapsedRealtime() < deadline, "恢复选择未保存");
      SystemClock.sleep(100);
    }
    check(
        startup.journal.state().stable.isEmpty()
            && startup.journal.state().quarantine.contains(target),
        "恢复入口启用时磁盘仍指向错误版本");
    var report =
        new JSONObject()
            .put("passed", true)
            .put("productionTouched", false)
            .put("failed", target)
            .put("oldPid", android.os.Process.myPid())
            .put("allWindowsStopped", true)
            .put("playbackDisconnected", true)
            .put("creationBlocked", true)
            .put("recoveryPersisted", true);
    Files.write(
        new File(runner.getTargetContext().getFilesDir(), "native-controlled-recovery-report.json")
            .toPath(),
        report.toString(2).getBytes(StandardCharsets.UTF_8));
    // 设置先交还原值再结束测试进程；恢复 Activity 是真实用户按钮路径。
    shell(
        automation,
        previousServices == null || previousServices.equals("null")
            ? "settings delete secure enabled_accessibility_services"
            : "settings put secure enabled_accessibility_services " + previousServices);
    shell(
        automation,
        previousEnabled == null || previousEnabled.equals("null")
            ? "settings delete secure accessibility_enabled"
            : "settings put secure accessibility_enabled " + previousEnabled);
    runner.sendStatus(0, bundle("通过：全部窗口和播放停用、业务创建被拒绝、恢复选择落盘；即将点击重新打开。\n"));
    runner.runOnMainSync(
        () -> {
          check(reopen.get().isEnabled(), "恢复按钮尚未启用");
          reopen.get().performClick();
        });
    SystemClock.sleep(10000);
    throw new AssertionError("用户恢复按钮没有结束故障进程");
  }

  private static void shell(UiAutomation automation, String command) throws Exception {
    try (var input =
        new android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command))) {
      input.readAllBytes();
    }
  }

  private static android.os.Bundle bundle(String text) {
    var value = new android.os.Bundle();
    value.putString("stream", text);
    return value;
  }

  private static Button button(View view) {
    if (view instanceof Button button && button.getText().toString().equals("重新打开")) return button;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        Button value = button(group.getChildAt(i));
        if (value != null) return value;
      }
    return null;
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
