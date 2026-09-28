package app.luoxianlv.hot;

import android.app.Instrumentation;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

/** 独立测试 APK 验证 Android 密码提供器与只读对象；不会修改用户 APP 数据。 */
public final class HotCoreInstrumentation extends Instrumentation {
  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  @Override
  public void onStart() {
    Bundle report = new Bundle();
    try {
      File root = new File(getContext().getFilesDir(), "hot-check-" + UUID.randomUUID());
      check(root.mkdirs(), "无法创建独立测试目录");
      HotSignatures.PublicKey publicKey =
          new HotSignatures.PublicKey(StrictJson.object(asset("root.public.json")));
      HotPackage.Policy policy =
          new HotPackage.Policy(
              publicKey,
              "app.luoxianlv.debug",
              "test",
              1,
              1,
              Instant.parse("2026-09-29T00:00:00Z"),
              Collections.emptySet(),
              null,
              null);
      File basePath = fixture(root, "base.lxhp"), deltaPath = fixture(root, "delta.lxhp");
      ContentStore store = new ContentStore(new File(root, "internal"));
      try (HotPackage base = new HotPackage(basePath, policy);
          HotPackage delta = new HotPackage(deltaPath, policy)) {
        store.prepare(base);
        ContentStore.Snapshot prepared = store.prepare(delta);
        store.verifySnapshotObjects(prepared);
        StrictJson.Obj expected = StrictJson.object(asset("expected.json"));
        check(prepared.manifest.snapshotId.equals(expected.string("targetSnapshotId")), "目标身份不一致");
        File object = store.objectFile(prepared.manifest.business.sha256);
        check(!object.canWrite(), "内部对象未保持只读");
        check(store.snapshot(base.manifest.snapshotId).directory.isDirectory(), "旧稳定快照丢失");
        report.putString("snapshot", prepared.manifest.snapshotId);
        File stateDir = new File(root, "state");
        ActivationJournal journal = new ActivationJournal(stateDir);
        String attempt =
            journal.begin(prepared.manifest.snapshotId, 1, 1, android.os.Process.myPid(), 1000);
        journal.firstFrame(attempt);
        ActivationJournal restored = new ActivationJournal(stateDir);
        restored.recover(ActivationJournal.ExitReason.CRASH, android.os.Process.myPid(), 2000);
        check(
            restored.state().active.isEmpty()
                && restored.state().quarantine.contains(prepared.manifest.snapshotId),
            "候选故障恢复失败");
      }
      deleteOwnTree(root);
      report.putString("stream", "Android 热更核心检查通过：Go 验签、完整包、增量恢复、只读对象、旧快照保留、激活日志与故障隔离。\n");
      finish(-1, report);
    } catch (Throwable failure) {
      report.putString(
          "stream",
          "Android 热更核心检查失败：" + failure + "\n" + android.util.Log.getStackTraceString(failure));
      finish(0, report);
    }
  }

  private byte[] asset(String name) throws Exception {
    try (InputStream input = getContext().getAssets().open("protocol-v1/" + name)) {
      return HotPackage.read(input, StrictJson.MAX_BYTES);
    }
  }

  private File fixture(File root, String name) throws Exception {
    File file = new File(root, name);
    try (FileOutputStream output = new FileOutputStream(file)) {
      output.write(asset(name));
    }
    return file;
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void deleteOwnTree(File file) throws Exception {
    File[] children = file.listFiles();
    if (children != null) for (File child : children) deleteOwnTree(child);
    check(file.delete(), "无法清理本次测试文件");
  }
}
