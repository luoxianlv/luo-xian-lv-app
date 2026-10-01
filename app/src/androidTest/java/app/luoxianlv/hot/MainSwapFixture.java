package app.luoxianlv.hot;

import android.content.Context;
import android.content.res.Resources;
import app.luoxianlv.hot.contract.NativePage;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 仅测试 APK：用包内的两次真实主页面实例检验状态迁移和系统回调。 Prepared 有意使用本地类，不能作为下载验签或跨 Dex 加载的证据；对应链路另有
 * NativeOnlineChecks。
 */
public final class MainSwapFixture {
  public final ActivationController controller;
  public final ActivationController.Ticket ticket;
  public final NativeLoader.Prepared prepared;
  public final AtomicLong elapsed = new AtomicLong(1200);
  public final ActivationJournal journal;
  private final File root;
  private final Context fixtures;

  public MainSwapFixture(
      Context fixtures, Context target, Class<? extends NativePage> entry, Resources resources)
      throws Exception {
    this.fixtures = fixtures;
    root = new File(target.getCacheDir(), "main-swap-check-" + UUID.randomUUID());
    if (!root.mkdirs()) throw new IllegalStateException("测试目录无法创建");
    journal = new ActivationJournal(new File(root, "journal"));
    ContentQuarantine quarantine = new ContentQuarantine(new File(root, "quarantine"));
    HotSignatures.PublicKey rootKey =
        new HotSignatures.PublicKey(StrictJson.object(read("root.public.json")));
    TrustStore trust = new TrustStore(new File(root, "trust"), rootKey);
    StrictJson.Obj permitFields = StrictJson.object(read("permit.json"));
    Instant serverTime = Instant.ofEpochSecond(permitFields.number("issuedAt") + 1);
    TrustStore.Record authority =
        trust.accept(read("trust.json"), read("trust.sig.json"), journal, serverTime);
    HotManifest manifest = new HotManifest(read("manifest.json"));
    File metadata = new File(root, "snapshot");
    if (!metadata.mkdirs()) throw new IllegalStateException("测试清单目录无法创建");
    Files.write(new File(metadata, "manifest.json").toPath(), read("manifest.json"));
    Files.write(new File(metadata, "manifest.sig.json").toPath(), read("manifest.sig.json"));
    ActivationPermit.Request request =
        new ActivationPermit.Request(
            permitFields.string("installationId"),
            permitFields.string("nonce"),
            permitFields.string("hostIdentity"),
            1,
            permitFields.number("channelRevision"),
            1000,
            manifest);
    ActivationPermit permit =
        new ActivationPermit(
            read("permit.json"),
            read("permit.sig.json"),
            authority.authority,
            request,
            1,
            1,
            serverTime,
            1100);
    controller = new ActivationController(journal, trust, quarantine, 1, elapsed::get);
    ticket =
        controller.begin(
            new ContentStore.Snapshot(manifest, metadata),
            permit,
            serverTime,
            android.os.Process.myPid());
    prepared = new NativeLoader.Prepared(manifest, entry, entry.getClassLoader(), resources, null);
  }

  private byte[] read(String name) throws Exception {
    try (InputStream input = fixtures.getAssets().open("permit-v1/" + name)) {
      return HotPackage.read(input, StrictJson.MAX_BYTES);
    }
  }

  public boolean stable() {
    return journal.state().stable.equals(prepared.manifest.snapshotId);
  }

  /** 只在成功结束后删除此次唯一的测试目录；失败保留记录便于检查。 */
  public void cleanSuccessfulRun() throws Exception {
    if (!stable()) throw new IllegalStateException("测试尚未结束，保留故障记录");
    try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) {
      for (java.nio.file.Path path :
          (Iterable<java.nio.file.Path>)
              paths.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(path);
    }
  }
}
