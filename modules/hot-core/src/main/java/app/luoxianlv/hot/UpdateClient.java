package app.luoxianlv.hot;

import java.io.File;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** 后台在线链路：发现与准备不执行新代码，激活前再次取当前决定和一次性许可。 */
public final class UpdateClient {
  /** 只由宿主提供已安装 APK 的恢复对象；返回 null 表示本地没有准确匹配的内容。 */
  public interface LocalObjects {
    File find(String hash, long size) throws Exception;
  }

  public static final class PreparedUpdate {
    public final ContentStore.Snapshot snapshot;
    public final boolean recovery;

    PreparedUpdate(ContentStore.Snapshot snapshot, boolean recovery) {
      this.snapshot = snapshot;
      this.recovery = recovery;
    }

    public boolean needsRestart(String runtimeHash, String runtimeAbi) {
      HotManifest target = snapshot.manifest;
      return target.activation.equals("restart")
          || !runtimeHash.equals(target.runtime.sha256)
          || !runtimeAbi.equals(target.runtimeAbi);
    }
  }

  private final HotApiClient api;
  private final HotSignatures.PublicKey root;
  private final ContentStore store;
  private final TrustStore trust;
  private final ActivationJournal journal;
  private final ActivationController controller;
  private final ContentQuarantine quarantine;
  private final ObjectDownloader downloads;
  private final DownloadBudget budget;
  private final Set<String> mounts;
  private final LongSupplier elapsed;
  private final PreparationSpace space;
  private final LocalObjects local;

  public UpdateClient(
      HotApiClient api,
      HotSignatures.PublicKey root,
      ContentStore store,
      TrustStore trust,
      ActivationJournal journal,
      ActivationController controller,
      ContentQuarantine quarantine,
      ObjectDownloader downloads,
      DownloadBudget budget,
      Set<String> mounts,
      LongSupplier elapsed) {
    this(
        api,
        root,
        store,
        trust,
        journal,
        controller,
        quarantine,
        downloads,
        budget,
        mounts,
        elapsed,
        new PreparationSpace());
  }

  public UpdateClient(
      HotApiClient api,
      HotSignatures.PublicKey root,
      ContentStore store,
      TrustStore trust,
      ActivationJournal journal,
      ActivationController controller,
      ContentQuarantine quarantine,
      ObjectDownloader downloads,
      DownloadBudget budget,
      Set<String> mounts,
      LongSupplier elapsed,
      PreparationSpace space) {
    this(
        api,
        root,
        store,
        trust,
        journal,
        controller,
        quarantine,
        downloads,
        budget,
        mounts,
        elapsed,
        space,
        (hash, size) -> null);
  }

  public UpdateClient(
      HotApiClient api,
      HotSignatures.PublicKey root,
      ContentStore store,
      TrustStore trust,
      ActivationJournal journal,
      ActivationController controller,
      ContentQuarantine quarantine,
      ObjectDownloader downloads,
      DownloadBudget budget,
      Set<String> mounts,
      LongSupplier elapsed,
      PreparationSpace space,
      LocalObjects local) {
    this.api = api;
    this.root = root;
    this.store = store;
    this.trust = trust;
    this.journal = journal;
    this.controller = controller;
    this.quarantine = quarantine;
    this.downloads = downloads;
    this.budget = budget;
    this.mounts = Collections.unmodifiableSet(new HashSet<>(mounts));
    this.elapsed = elapsed;
    this.space = java.util.Objects.requireNonNull(space);
    this.local = java.util.Objects.requireNonNull(local);
  }

  /** 返回 null 表示当前无需准备；计费网络超限、取消和网络失败均保留原稳定版本。 */
  public synchronized PreparedUpdate prepare(
      long stateSchema, BooleanSupplier metered, BooleanSupplier cancelled) throws Exception {
    UpdateCancellation token = UpdateCancellation.from(cancelled);
    token.check();
    HotApiClient.Decision decision = api.check(journal.state().active, stateSchema, token);
    accept(decision);
    if (!decision.hasCandidate()) return null;
    SignedSnapshot candidate = decision.verify(policy(decision));
    candidate.manifest.requireVersion(api.appVersionCode);
    requireReadable(candidate.manifest, stateSchema);
    quarantine.requireAllowed(candidate.manifest, api.hostContract);
    Map<String, Long> missing = new LinkedHashMap<>();
    Map<String, File> obtained = new HashMap<>();
    for (Map.Entry<String, Long> object : candidate.manifest.objects.entrySet()) {
      token.check();
      if (store.containsVerified(object.getKey(), object.getValue())) continue;
      File installed = local.find(object.getKey(), object.getValue());
      if (installed == null) missing.put(object.getKey(), object.getValue());
      else {
        ContentStore.verifyFile(installed, object.getKey(), object.getValue());
        obtained.put(object.getKey(), installed);
      }
    }
    LongSupplier remaining = () -> missingBytes(missing);
    downloads.collect(candidate.manifest.objects.keySet(), 256L << 20);
    space.beforeDownload(
        store, downloads, candidate.manifest, NativeLoader.residentRuntimeHash(), obtained);
    // 连接第一个对象前检查整组；对象下载器在切网和预留预算时继续检查。
    budget.admit(candidate.manifest.contentId, remaining.getAsLong(), metered.getAsBoolean());
    for (Map.Entry<String, Long> object : missing.entrySet()) {
      token.check();
      obtained.put(
          object.getKey(),
          downloads.download(
              candidate.manifest.contentId,
              object.getKey(),
              object.getValue(),
              api.object(candidate, object.getKey()),
              metered,
              token,
              remaining));
    }
    token.check();
    space.beforeCommit(store, candidate.manifest, obtained, NativeLoader.residentRuntimeHash());
    ContentStore.Snapshot snapshot = store.prepare(new DownloadedSnapshot(candidate, obtained));
    token.check();
    downloads.committed(store, snapshot);
    return new PreparedUpdate(snapshot, decision.kind.equals("recover"));
  }

  /** 调用者在安全点/冷启动满足后进入；下载时的许可或旧 revision 不会被复用。 */
  public synchronized ActivationController.Ticket authorize(
      PreparedUpdate prepared, long stateSchema, int processId, BooleanSupplier cancelled)
      throws Exception {
    UpdateCancellation token = UpdateCancellation.from(cancelled);
    token.check();
    HotApiClient.Decision latest = api.check(journal.state().active, stateSchema, token);
    accept(latest);
    StrictJson.require(
        latest.hasCandidate() && latest.snapshotId.equals(prepared.snapshot.manifest.snapshotId),
        "候选已被暂停、撤回或新版本取代");
    SignedSnapshot current = latest.verify(policy(latest));
    current.manifest.requireVersion(api.appVersionCode);
    requireReadable(current.manifest, stateSchema);
    quarantine.requireAllowed(current.manifest, api.hostContract);
    store.verifySnapshotObjects(prepared.snapshot);
    token.check();
    byte[] nonce = new byte[32];
    new SecureRandom().nextBytes(nonce);
    ActivationPermit.Request request =
        new ActivationPermit.Request(
            api.installation.id,
            HotSignatures.hex(nonce),
            api.hostIdentity,
            api.hostContract,
            latest.revision,
            elapsed.getAsLong(),
            current.manifest);
    HotApiClient.PermitReply reply = api.activate(request, stateSchema, token);
    token.check();
    TrustStore.Record authority = trust.current();
    StrictJson.require(authority != null, "缺少当前根授权");
    ActivationPermit permit =
        new ActivationPermit(
            reply.permit,
            reply.signature,
            authority.authority,
            request,
            journal.state().trustVersion,
            journal.state().revision,
            reply.serverTime,
            elapsed.getAsLong());
    return controller.begin(prepared.snapshot, permit, reply.serverTime, processId);
  }

  /** 待重启身份仅找回缓存，不改变日志；调用者仍须 authorize 取得当前签名许可。 */
  public PreparedUpdate cached(String snapshotId) throws Exception {
    return new PreparedUpdate(store.snapshot(snapshotId), false);
  }

  private void accept(HotApiClient.Decision decision) throws Exception {
    controller.observe(
        decision.revision, decision.trust, decision.trustSignature, decision.serverTime);
  }

  private HotPackage.Policy policy(HotApiClient.Decision decision) throws Exception {
    TrustStore.Record record = trust.current();
    return new HotPackage.Policy(
        root,
        api.installation.applicationId,
        api.installation.environment,
        api.hostContract,
        Math.max(1, journal.state().trustVersion),
        decision.serverTime,
        mounts,
        record == null ? null : record.document(),
        record == null ? null : record.signature());
  }

  private long missingBytes(Map<String, Long> missing) {
    long total = 0;
    for (Map.Entry<String, Long> object : missing.entrySet()) {
      File partial = downloads.partial(object.getKey());
      StrictJson.require(!Files.isSymbolicLink(partial.toPath()), "下载暂存包含链接");
      long length = partial.exists() ? partial.length() : 0;
      StrictJson.require(length <= object.getValue(), "下载暂存大小超限");
      total = Math.addExact(total, object.getValue() - length);
    }
    return total;
  }

  private static void requireReadable(HotManifest manifest, long schema) {
    StrictJson.require(schema >= manifest.stateMin && schema <= manifest.stateMax, "候选无法读取当前数据格式");
  }
}
