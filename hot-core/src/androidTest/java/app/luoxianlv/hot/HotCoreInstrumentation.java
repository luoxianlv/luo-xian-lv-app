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
  private boolean nativePage;
  private String online;
  private boolean recoveryOnly;
  private boolean collectionOnly;
  private boolean historyOnly;
  private boolean spaceOnly;
  private boolean cacheOnly;

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    nativePage = arguments != null && "true".equals(arguments.getString("native"));
    online = arguments == null ? null : arguments.getString("online");
    recoveryOnly = arguments != null && "true".equals(arguments.getString("recoveryOnly"));
    collectionOnly = arguments != null && "true".equals(arguments.getString("collectionOnly"));
    historyOnly = arguments != null && "true".equals(arguments.getString("historyOnly"));
    spaceOnly = arguments != null && "true".equals(arguments.getString("spaceOnly"));
    cacheOnly = arguments != null && "true".equals(arguments.getString("cacheOnly"));
    start();
  }

  @Override
  public void onStart() {
    Bundle report = new Bundle();
    try {
      if (cacheOnly) {
        CacheCleanupChecks.run(getContext());
        report.putString("stream", "Android缓存检查通过：内部原子认领、候选/活跃锁/未知字节/链接保护及真实跨挂载行为。\n");
        finish(-1, report);
        return;
      }
      if (spaceOnly) {
        PreparationSpaceChecks.run(getContext());
        report.putString("stream", "Android 整组空间检查通过：真实存储卷/FUSE映射、同盘合并、下载前拒绝与用户文件保留。\n");
        finish(-1, report);
        return;
      }
      if (historyOnly) {
        File root = new File(getContext().getFilesDir(), "hot-history-" + UUID.randomUUID());
        check(root.mkdirs(), "无法创建独立历史目录");
        HealthHistoryChecks.run(root);
        deleteOwnTree(root);
        report.putString("stream", "Android 回报历史检查通过：旧格式迁移、健康回执跨实例去重、稳定与回退序号延续、未上传事件和迟到确认保留。\n");
        finish(-1, report);
        return;
      }
      if (collectionOnly) {
        checkCollection();
        report.putString("stream", "Android 对象回收检查通过：运行中快照与共享运行时保护、真实只读对象清理、符号链接拒绝、目录外文件保留。\n");
        finish(-1, report);
        return;
      }
      if (recoveryOnly) {
        GroupRecoveryChecks.run(this);
        GroupRetirementChecks.run(this);
        report.putString(
            "stream", "整组恢复收尾检查通过：关闭错误准确报告一次，保留最新状态并阻断后续交接；提交回退及准备取消等待真实后台工作退出，候选监听退役，退出故障准确报告。\n");
        finish(-1, report);
        return;
      }
      RetainedPageChecks.run();
      runOnMainSync(HostResultsChecks::run);
      NativeHostChecks.run(this);
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
      PageSwapChecks.run(this, root);
      GroupRecoveryChecks.run(this);
      GroupRetirementChecks.run(this);
      NativeTransferChecks.run(this, root);
      if (nativePage) NativePageChecks.run(this, root);
      if (online != null) NativeOnlineChecks.run(this, root, online);
      deleteOwnTree(root);
      report.putString(
          "stream",
          "Android 热更核心检查通过：Go 验签、完整包、增量恢复、只读对象、旧快照保留、激活日志与故障隔离。"
              + " 十三种同窗口替换、输入隔离、状态边界、代际租约及异步故障恢复检查通过。"
              + " 整组恢复后的关闭错误会阻断后续交接，不丢弃已恢复状态。"
              + " 同版本重建句柄的内容/类型隔离与关闭检查通过。"
              + " 系统选择/权限结果、延迟消费和重建检查通过。"
              + " 原生宿主生命周期、系统选择期间重建、内容身份隔离和创建/恢复/保存/关闭故障隔离通过。"
              + " Android HTTP 续传与外部私有下载目录检查通过。"
              + (nativePage ? " 实际原生关于页加载和生命周期检查通过。" : "")
              + (online != null ? " Rust API 下载、签名许可、Compose 原位热替换和真实 60 秒观察通过。" : "")
              + "\n");
      finish(-1, report);
    } catch (Throwable failure) {
      report.putString(
          "stream",
          "Android 热更核心检查失败：" + failure + "\n" + android.util.Log.getStackTraceString(failure));
      finish(0, report);
    }
  }

  private void checkCollection() throws Exception {
    File root = new File(getContext().getFilesDir(), "hot-collection-" + UUID.randomUUID());
    check(root.mkdirs(), "无法创建独立回收目录");
    var publicKey = new HotSignatures.PublicKey(StrictJson.object(asset("root.public.json")));
    var policy =
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
    var store = new ContentStore(new File(root, "internal"));
    var collector = new ContentCollector(store);
    try (var base = new HotPackage(fixture(root, "base.lxhp"), policy);
        var delta = new HotPackage(fixture(root, "delta.lxhp"), policy)) {
      var old = store.prepare(base);
      var next = store.prepare(delta);
      try (var lease = store.pin(old);
          var runtime = store.pinRuntime(old.manifest.runtime.sha256)) {
        var result = collector.collect(() -> java.util.Set.of(next.manifest.snapshotId), 0, 0);
        check(result.overBudget() && result.removedSnapshots() == 0, "预算不能删除运行中快照");
        store.verifySnapshotObjects(old);
        store.verifySnapshotObjects(next);
      }
      var result =
          collector.collect(() -> java.util.Set.of(next.manifest.snapshotId), 0, Long.MAX_VALUE);
      check(result.removedSnapshots() == 1 && !old.directory.exists(), "退役快照未回收");
      store.verifySnapshotObjects(next);
      var outside = new File(root, "outside.txt").toPath();
      java.nio.file.Files.write(outside, new byte[] {9});
      var link = store.objectFile("e".repeat(64)).toPath();
      java.nio.file.Files.createSymbolicLink(link, outside);
      boolean rejected = false;
      try {
        collector.collect(() -> java.util.Set.of(next.manifest.snapshotId), 0, 0);
      } catch (Exception expected) {
        rejected = true;
      }
      check(rejected && java.nio.file.Files.exists(outside), "链接越界未拒绝");
      java.nio.file.Files.delete(link);
      check(collector.collect(java.util.Set::of, 0, 0).afterBytes() == 0, "只读对象未清理完整");
      check(java.nio.file.Files.readAllBytes(outside)[0] == 9, "目录外文件改变");
    }
    deleteOwnTree(root);
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
