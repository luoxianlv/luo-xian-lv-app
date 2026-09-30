package app.luoxianlv.host;

import android.app.Instrumentation;
import android.os.SystemClock;
import app.luoxianlv.hot.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** 只调整测试进程的检查时刻，实际回收仍由宿主的空闲更新线程执行。 */
final class NativeContentCollectionChecks {
  static void run(Instrumentation test) throws Exception {
    var field = Bootstrap.class.getDeclaredField("updates");
    field.setAccessible(true);
    var updates = (HostUpdates) field.get(null);
    if (updates == null) throw new AssertionError("没有启用测试更新配置");
    var busy = HostUpdates.class.getDeclaredField("busy");
    busy.setAccessible(true);
    var nextCheck = HostUpdates.class.getDeclaredField("nextCheck");
    nextCheck.setAccessible(true);
    long until = SystemClock.elapsedRealtime() + 70000;
    var idle = new AtomicBoolean();
    do {
      test.runOnMainSync(
          () -> {
            try {
              idle.set(!busy.getBoolean(updates));
            } catch (Exception error) {
              throw new AssertionError(error);
            }
          });
      if (idle.get()) break;
      SystemClock.sleep(100);
    } while (SystemClock.elapsedRealtime() < until);
    if (!idle.get()) throw new AssertionError("更新线程没有到达空闲点");
    var state = Bootstrap.startupState();
    var source = Bootstrap.source();
    byte[] bytes = java.util.UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
    String hash = HotSignatures.hash(bytes);
    var orphan = state.store.objectFile(hash).toPath();
    if (Files.exists(orphan)) throw new AssertionError("测试对象身份冲突");
    Files.write(orphan, bytes);
    test.runOnMainSync(
        () -> {
          try {
            nextCheck.setLong(updates, 0);
            updates.usageChanged();
          } catch (Exception error) {
            throw new AssertionError(error);
          }
        });
    until = SystemClock.elapsedRealtime() + 20000;
    while (Files.exists(orphan) && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
    if (Files.exists(orphan)) throw new AssertionError("自动更新工作线程未回收孤立对象");
    Set<String> protectedIds = new HashSet<>();
    var journal = state.journal.state();
    for (String id :
        new String[] {
          journal.stable,
          journal.previousStable,
          journal.active,
          journal.candidate,
          state.pendingRestart.current()
        }) if (!id.isEmpty()) protectedIds.add(id);
    for (String id : protectedIds) state.store.verifySnapshotObjects(state.store.snapshot(id));
    if (source.prepared.manifest != null)
      state.store.verifySnapshotObjects(state.store.snapshot(source.prepared.identity()));
    if (Bootstrap.source() != source) throw new AssertionError("离线回收改变了活动业务");
    verifyBaseline(test);
    var report =
        new org.json.JSONObject()
            .put("passed", true)
            .put("productionTouched", false)
            .put("backgroundWorkerCollected", true)
            .put("orphanBytes", bytes.length)
            .put("protectedSnapshots", protectedIds.size())
            .put("bundledRecoveryVerified", true)
            .put("activeSourceUnchanged", true);
    Files.write(
        new File(test.getTargetContext().getFilesDir(), "native-content-collection-report.json")
            .toPath(),
        report.toString(2).getBytes(StandardCharsets.UTF_8));
  }

  private static void verifyBaseline(Instrumentation test) throws Exception {
    byte[] raw;
    try (var input = test.getTargetContext().getAssets().open("baseline/index.json")) {
      var output = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[1024];
      int length;
      while ((length = input.read(buffer)) != -1) {
        if (output.size() + length > 8192) throw new AssertionError("恢复清单超限");
        output.write(buffer, 0, length);
      }
      raw = output.toByteArray();
    }
    var index = StrictJson.object(raw);
    File directory =
        new File(
            test.getTargetContext().getNoBackupFilesDir(),
            "native-baseline/" + HotSignatures.hash(raw));
    for (String name : new String[] {"runtime", "business"}) {
      var artifact = index.object(name);
      var path = new File(directory, name + ".apk").toPath();
      if (!Files.isRegularFile(path) || Files.size(path) != artifact.number("size"))
        throw new AssertionError("回收破坏了内置恢复模块");
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(path)) {
        byte[] buffer = new byte[32768];
        int length;
        while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
      }
      var hex = new StringBuilder(64);
      for (byte value : digest.digest())
        hex.append(Character.forDigit((value & 255) >>> 4, 16))
            .append(Character.forDigit(value & 15, 16));
      if (!hex.toString().equals(artifact.string("sha256"))) throw new AssertionError("内置恢复模块内容改变");
    }
  }
}
