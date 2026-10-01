package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import app.luoxianlv.hot.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 只观察普通冷启动重新授权与实际共享加载器，不直接调用激活或健康确认。 */
final class NativeColdRuntimeChecks {
  static void run(Instrumentation runner, Activity home, String target) throws Exception {
    var context = runner.getTargetContext();
    var startup = Bootstrap.startupState();
    if (!context.getPackageName().equals("app.luoxianlv.debug")
        || !HotManifest.validHash(target)
        || startup == null
        || !startup.config.origin.toString().equals("http://127.0.0.1:18472"))
      throw new AssertionError("仅允许明确本机冷启动验收");
    var source = Bootstrap.source().prepared;
    if (source.manifest == null || !target.equals(source.manifest.snapshotId))
      throw new AssertionError("冷启动未选择已重新授权组合");
    Class<?> marker =
        Class.forName("app.luoxianlv.runtime.probe.NewRuntimeMarker", false, source.classLoader());
    ClassLoader runtime =
        Class.forName("kotlin.Unit", false, source.classLoader()).getClassLoader();
    if (marker.getClassLoader() != runtime || runtime != source.classLoader().getParent())
      throw new AssertionError("新增类与业务没有使用同一新共享运行时");
    long started = SystemClock.elapsedRealtime(), until = started + 100000;
    var queue = new HealthOutbox(new File(context.getNoBackupFilesDir(), "native-update/health"));
    while (!startup.journal.state().stable.equals(target)
        || startup.journal.state().phase != ActivationJournal.Phase.STABLE
        || !startup.pendingRestart.current().isEmpty()
        || !queue.batch(100).isEmpty()) {
      if (SystemClock.elapsedRealtime() >= until) throw new AssertionError("新共享运行时未完成真实启动观察与确认");
      SystemClock.sleep(100);
    }
    if (SystemClock.elapsedRealtime() - started < 55000) throw new AssertionError("冷启动没有经历真实使用观察");
    var report =
        new org.json.JSONObject()
            .put("passed", true)
            .put("productionTouched", false)
            .put("target", target)
            .put("runtimeHash", source.runtimeHash)
            .put("newRuntimeClassLoaded", true)
            .put("sameRuntimeParent", true)
            .put("ordinaryStartup", true)
            .put("healthy", true)
            .put("pendingCleared", true)
            .put("observationWallMillis", SystemClock.elapsedRealtime() - started);
    Files.write(
        new File(context.getFilesDir(), "native-cold-runtime-report.json").toPath(),
        report.toString(2).getBytes(StandardCharsets.UTF_8));
  }
}
