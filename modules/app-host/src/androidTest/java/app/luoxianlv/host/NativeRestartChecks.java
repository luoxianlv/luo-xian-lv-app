package app.luoxianlv.host;

import android.app.Instrumentation;
import android.os.SystemClock;
import app.luoxianlv.hot.ActivationJournal;
import app.luoxianlv.hot.HotManifest;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipFile;

/** 仅观察普通入口的缓存决定，不提前建立候选类加载器或取得激活许可。 */
final class NativeRestartChecks {
  static void run(Instrumentation runner, String target) throws Exception {
    if (!runner.getTargetContext().getPackageName().equals("app.luoxianlv.debug")
        || !HotManifest.validHash(target)) throw new AssertionError("仅允许明确 Debug 快照");
    var state = Bootstrap.startupState();
    if (state == null
        || !state.config.automatic
        || !state.config.origin.toString().equals("http://127.0.0.1:18472"))
      throw new AssertionError("缺少显式本机配置");
    var original = Bootstrap.source().prepared;
    long until = SystemClock.elapsedRealtime() + 90000;
    while (!state.pendingRestart.current().equals(target)) {
      if (SystemClock.elapsedRealtime() >= until) throw new AssertionError("运行时组合没有完整缓存并持久记录");
      SystemClock.sleep(100);
    }
    var cached = state.store.snapshot(target);
    state.store.verifySnapshotObjects(cached);
    if (cached.manifest.runtime.sha256.equals(original.runtimeHash))
      throw new AssertionError("候选运行时内容未改变");
    if (Bootstrap.source().prepared != original
        || state.journal.state().phase != ActivationJournal.Phase.STABLE)
      throw new AssertionError("缓存运行时提前改变了活动组合");
    String marker = "app.luoxianlv.runtime.probe.NewRuntimeMarker";
    boolean absent = false;
    try {
      Class.forName(marker, false, original.classLoader().getParent());
    } catch (ClassNotFoundException expected) {
      absent = true;
    }
    if (!absent) throw new AssertionError("基线运行时已混入测试类");
    boolean contains = false;
    try (var apk = new ZipFile(state.store.objectFile(cached.manifest.runtime.sha256))) {
      var entries = apk.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        if (!entry.getName().matches("classes([0-9]+)?\\.dex")) continue;
        try (var input = apk.getInputStream(entry)) {
          if (new String(input.readAllBytes(), StandardCharsets.ISO_8859_1)
              .contains("Lapp/luoxianlv/runtime/probe/NewRuntimeMarker;")) contains = true;
        }
      }
    }
    if (!contains) throw new AssertionError("缓存的运行时没有新增类");
    var report =
        new org.json.JSONObject()
            .put("passed", true)
            .put("productionTouched", false)
            .put("target", target)
            .put("runtimeHash", cached.manifest.runtime.sha256)
            .put("cachedCompletely", true)
            .put("pendingPersisted", true)
            .put("activeSourceUnchanged", true)
            .put("newRuntimeClassNotLoaded", true);
    Files.write(
        new File(runner.getTargetContext().getFilesDir(), "native-restart-prepared-report.json")
            .toPath(),
        report.toString(2).getBytes(StandardCharsets.UTF_8));
  }
}
