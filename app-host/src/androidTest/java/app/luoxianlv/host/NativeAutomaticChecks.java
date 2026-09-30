package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import app.luoxianlv.hot.ActivationJournal;
import app.luoxianlv.hot.HealthOutbox;
import app.luoxianlv.hot.HotManifest;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;

/** 只观察普通 Application 自动链路；不得直接下载、取许可、调用 activate 或伪造健康时钟。 */
final class NativeAutomaticChecks {
  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static void await(String message, long timeout, BooleanSupplier condition) {
    long until = SystemClock.elapsedRealtime() + timeout;
    while (!condition.getAsBoolean()) {
      check(SystemClock.elapsedRealtime() < until, message);
      SystemClock.sleep(100);
    }
  }

  private static View badge(View root) {
    if (root.getClass().getName().equals("app.luoxianlv.hot.probe.HotProbeFactory$NewNativeBadge"))
      return root;
    if (root instanceof ViewGroup parent)
      for (int i = 0; i < parent.getChildCount(); i++) {
        View value = badge(parent.getChildAt(i));
        if (value != null) return value;
      }
    return null;
  }

  static void run(Instrumentation runner, Activity home, String target) throws Exception {
    var context = runner.getTargetContext();
    check(
        context.getPackageName().equals("app.luoxianlv.debug") && HotManifest.validHash(target),
        "自动验收仅允许明确 Debug 快照");
    var startup = Bootstrap.startupState();
    check(
        startup != null
            && startup.config.automatic
            && startup.config.testHealthReports
            && startup.config.origin.toString().equals("http://127.0.0.1:18472")
            && startup.config.environment.equals("test"),
        "未启用显式本机自动验收配置");
    await(
        "普通入口未自动展示候选",
        90000,
        () -> {
          var source = Bootstrap.source().prepared;
          return source.manifest != null && target.equals(source.manifest.snapshotId);
        });
    long exposedAt = SystemClock.elapsedRealtime();
    runner.runOnMainSync(
        () -> {
          var component = badge(home.getWindow().getDecorView());
          check(
              component != null
                  && component.getClass().getClassLoader()
                      == Bootstrap.source().prepared.classLoader(),
              "自动更新未呈现下载的新原生组件");
        });
    await(
        "自动更新未通过真实使用观察",
        90000,
        () ->
            startup.journal.state().phase == ActivationJournal.Phase.STABLE
                && startup.journal.state().stable.equals(target));
    check(SystemClock.elapsedRealtime() - exposedAt >= 55000, "自动验收未经历真实观察");
    var outbox = new HealthOutbox(new File(context.getNoBackupFilesDir(), "native-update/health"));
    await(
        "接口接收后健康事件未确认",
        30000,
        () -> {
          try {
            return outbox.batch(100).isEmpty();
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
        });
    var report =
        new org.json.JSONObject()
            .put("passed", true)
            .put("productionTouched", false)
            .put("normalLifecycle", true)
            .put("explicitActivationCalledByTest", false)
            .put("target", target)
            .put("newNativeClassVisible", true)
            .put("healthy", true)
            .put("outboxAcknowledged", true)
            .put("observationWallMillis", SystemClock.elapsedRealtime() - exposedAt);
    Files.write(
        new File(context.getFilesDir(), "native-automatic-report.json").toPath(),
        report.toString(2).getBytes(StandardCharsets.UTF_8));
    Bundle status = new Bundle();
    status.putString("stream", "通过：普通入口自动发现、签名下载、许可激活、新原生组件、真实观察和持久健康回报。\n");
    runner.sendStatus(0, status);
  }
}
