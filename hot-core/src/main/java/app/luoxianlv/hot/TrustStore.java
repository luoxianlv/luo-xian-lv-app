package app.luoxianlv.hot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;

/** 根授权在内部原子保存；授权文件与激活日志任一方见过新版本，都不能退回旧版本。 */
public final class TrustStore {
  private static final int MAGIC = 0x4c584854;
  private final File file;
  private final HotSignatures.PublicKey root;

  public static final class Record {
    public final HotTrust authority;
    private final byte[] document, signature;

    Record(HotTrust authority, byte[] document, byte[] signature) {
      this.authority = authority;
      this.document = document.clone();
      this.signature = signature.clone();
    }

    public byte[] document() {
      return document.clone();
    }

    public byte[] signature() {
      return signature.clone();
    }
  }

  public TrustStore(File internalDirectory, HotSignatures.PublicKey root) throws Exception {
    StrictJson.require(root.purpose.equals("root"), "信任库必须绑定 APK 信任根");
    StrictJson.require(
        !Files.isSymbolicLink(internalDirectory.toPath())
            && (internalDirectory.isDirectory() || internalDirectory.mkdirs()),
        "无法创建内部信任目录");
    this.root = root;
    file = new File(internalDirectory, "trust.bin");
  }

  /** 读取历史授权不因到期删除稳定离线版本；新激活必须另行调用 authority.current。 */
  public synchronized Record current() throws Exception {
    if (!file.exists()) return null;
    StrictJson.require(
        file.isFile()
            && file.length() <= 2L * StrictJson.MAX_BYTES + 16
            && !Files.isSymbolicLink(file.toPath()),
        "内部信任记录损坏");
    byte[] raw;
    try (InputStream input = Files.newInputStream(file.toPath())) {
      raw = HotPackage.read(input, 2 * StrictJson.MAX_BYTES + 16);
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(raw))) {
      StrictJson.require(input.readInt() == MAGIC && input.readInt() == 1, "内部信任格式无效");
      byte[] document = part(input), signature = part(input);
      StrictJson.require(input.available() == 0, "内部信任记录含尾随内容");
      return new Record(new HotTrust(root, document, signature), document, signature);
    }
  }

  public synchronized Record accept(
      byte[] document, byte[] signature, ActivationJournal journal, Instant now) throws Exception {
    Record candidate = new Record(new HotTrust(root, document, signature), document, signature);
    try (FileChannel channel =
            FileChannel.open(
                new File(file.getParentFile(), "trust.lock").toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock()) {
      StrictJson.require(lock != null, "另一个控制器正在更新根授权");
      Record existing = current();
      long floor = journal.state().trustVersion;
      if (existing != null) floor = Math.max(floor, existing.authority.version);
      candidate.authority.current(Math.max(1, floor), now);
      if (existing != null && candidate.authority.version == existing.authority.version) {
        StrictJson.require(Arrays.equals(existing.document, candidate.document), "同版本根授权内容冲突");
      } else {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
          out.writeInt(MAGIC);
          out.writeInt(1);
          out.writeInt(document.length);
          out.write(document);
          out.writeInt(signature.length);
          out.write(signature);
        }
        File temporary = File.createTempFile("trust-", ".part", file.getParentFile());
        try {
          ContentStore.writeSynced(temporary.toPath(), buffer.toByteArray());
          ContentStore.replaceSynced(temporary, file);
        } finally {
          Files.deleteIfExists(temporary.toPath());
        }
      }
      // 先保存完整授权再推进下限，断电后不会只剩下“需要新版本”却没有对应授权的指针。
      journal.observeVersions(journal.state().revision, candidate.authority.version);
      return candidate;
    }
  }

  /** 已稳定的版本可离线继续运行，但不能绕过验签、已见撤销或损坏的信任下限。 */
  public synchronized void verifyStable(
      ContentStore.Snapshot snapshot, ActivationJournal.State state) throws Exception {
    StrictJson.require(
        state.phase == ActivationJournal.Phase.STABLE
            && state.stable.equals(snapshot.manifest.snapshotId)
            && state.active.equals(state.stable)
            && !state.quarantine.contains(state.stable),
        "快照不是已确认的稳定版本");
    Record latest = current();
    StrictJson.require(
        latest != null && latest.authority.version >= state.trustVersion, "稳定版本缺少对应信任记录");
    byte[] authority = ContentStore.readBounded(new File(snapshot.directory, "trust.json"));
    HotTrust accepted =
        new HotTrust(
            root,
            authority,
            ContentStore.readBounded(new File(snapshot.directory, "trust.sig.json")));
    StrictJson.require(accepted.version <= latest.authority.version, "稳定快照授权高于已保存的信任版本");
    if (accepted.version == latest.authority.version)
      StrictJson.require(Arrays.equals(authority, latest.document), "同版本根授权内容冲突");
    HotManifest manifest = snapshot.manifest;
    StrictJson.require(
        manifest.applicationId.equals(accepted.applicationId)
            && manifest.environment.equals(accepted.environment)
            && manifest.applicationId.equals(latest.authority.applicationId)
            && manifest.environment.equals(latest.authority.environment),
        "稳定快照超出根授权范围");
    byte[] raw = ContentStore.readBounded(new File(snapshot.directory, "manifest.json"));
    StrictJson.require(HotSignatures.hash(raw).equals(manifest.snapshotId), "稳定快照清单已改变");
    byte[] signature = ContentStore.readBounded(new File(snapshot.directory, "manifest.sig.json"));
    latest.authority.requireNotRevoked(signature);
    accepted.verifyAccepted(raw, signature);
  }

  private static byte[] part(DataInputStream input) throws Exception {
    int size = input.readInt();
    StrictJson.require(size > 0 && size <= StrictJson.MAX_BYTES, "信任记录字段大小无效");
    byte[] data = new byte[size];
    input.readFully(data);
    return data;
  }
}
