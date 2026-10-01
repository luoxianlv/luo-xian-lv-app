package app.luoxianlv.hot;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 签名包验证与有界对象复制；只产生候选内容，不触发类加载或版本激活。 */
public final class HotPackage implements Closeable, SnapshotSource {
  public final HotManifest manifest;
  public final HotTrust trust;
  public final String mode, baseSnapshotId;
  public final Set<String> included;
  private final ZipContainer zip;
  private final SignedSnapshot signed;
  private final Map<String, ZipContainer.Entry> objects;

  public static final class Policy {
    public final HotSignatures.PublicKey root;
    public final String applicationId, environment;
    public final long hostContract, minimumTrustVersion;
    public final Instant now;
    public final Set<String> mounts;
    final byte[] trust, signature;

    public Policy(
        HotSignatures.PublicKey root,
        String app,
        String environment,
        long host,
        long minimumTrustVersion,
        Instant now,
        Set<String> mounts,
        byte[] trust,
        byte[] signature) {
      this.root = root;
      applicationId = app;
      this.environment = environment;
      hostContract = host;
      this.minimumTrustVersion = minimumTrustVersion;
      this.now = now;
      this.mounts = Collections.unmodifiableSet(new HashSet<>(mounts));
      this.trust = trust == null ? null : trust.clone();
      this.signature = signature == null ? null : signature.clone();
      StrictJson.require(
          (trust == null) == (signature == null) && minimumTrustVersion >= 1 && host >= 1,
          "验证策略无效");
    }
  }

  public HotPackage(File file, Policy policy) throws Exception {
    ZipContainer container =
        new ZipContainer(
            file,
            HotManifest.MAX_OBJECTS + 6,
            HotManifest.MAX_EXPANDED + 6L * StrictJson.MAX_BYTES,
            false);
    try {
      Map<String, byte[]> metadata = new HashMap<>();
      Map<String, ZipContainer.Entry> objectEntries = new HashMap<>();
      List<String> names =
          Arrays.asList(
              "manifest.json",
              "manifest.sig.json",
              "transport.json",
              "transport.sig.json",
              "trust.json",
              "trust.sig.json");
      for (ZipContainer.Entry entry : container.entries.values()) {
        if (names.contains(entry.name)) {
          StrictJson.require(entry.size > 0 && entry.size <= StrictJson.MAX_BYTES, "包元数据大小无效");
          try (InputStream input = container.open(entry)) {
            metadata.put(entry.name, read(input, StrictJson.MAX_BYTES));
          }
        } else {
          StrictJson.require(
              entry.name.startsWith("objects/")
                  && HotManifest.validHash(entry.name.substring(8))
                  && entry.size <= HotManifest.MAX_EXPANDED,
              "外层包路径未声明");
          objectEntries.put(entry.name.substring(8), entry);
        }
      }
      for (String name : names.subList(0, 4))
        StrictJson.require(metadata.containsKey(name), "缺少包元数据: " + name);
      StrictJson.require(
          metadata.containsKey("trust.json") == metadata.containsKey("trust.sig.json"),
          "授权列表和签名必须成对提供");
      signed =
          new SignedSnapshot(
              metadata.get("manifest.json"),
              metadata.get("manifest.sig.json"),
              metadata.get("trust.json"),
              metadata.get("trust.sig.json"),
              policy);
      signed.trust.verify(
          HotSignatures.TRANSPORT,
          metadata.get("transport.json"),
          metadata.get("transport.sig.json"),
          policy.now);
      manifest = signed.manifest;
      StrictJson.Obj transport =
          StrictJson.object(metadata.get("transport.json"))
              .only("schema", "snapshotId", "mode", "baseSnapshotId", "included");
      StrictJson.require(
          transport.number("schema") == 1
              && transport.string("snapshotId").equals(manifest.snapshotId),
          "传输清单指向其他快照");
      mode = transport.string("mode");
      baseSnapshotId = transport.optionalString("baseSnapshotId");
      if (mode.equals("full")) StrictJson.require(baseSnapshotId.isEmpty(), "完整包不能指定增量基线");
      else
        StrictJson.require(
            mode.equals("delta")
                && HotManifest.validHash(baseSnapshotId)
                && !baseSnapshotId.equals(manifest.snapshotId),
            "增量基线或传输类型无效");
      List<String> hashes = transport.strings("included");
      Set<String> unique = new HashSet<>(hashes);
      StrictJson.require(
          unique.size() == hashes.size()
              && manifest.objects.keySet().containsAll(unique)
              && unique.equals(objectEntries.keySet()),
          "传输对象缺失、重复或超出快照");
      StrictJson.require(
          !mode.equals("full") || unique.equals(manifest.objects.keySet()), "完整包缺少对象");
      included = Collections.unmodifiableSet(unique);
      objects = Collections.unmodifiableMap(objectEntries);
      trust = signed.trust;
      zip = container;
      for (String hash : included) copyObject(hash, DISCARD);
    } catch (Exception | Error error) {
      container.close();
      throw error;
    }
  }

  public byte[] manifestBytes() {
    return signed.manifestBytes();
  }

  public byte[] manifestSignature() {
    return signed.manifestSignature();
  }

  public byte[] trustBytes() {
    return signed.trustBytes();
  }

  public byte[] trustSignature() {
    return signed.trustSignature();
  }

  @Override
  public SignedSnapshot metadata() {
    return signed;
  }

  @Override
  public Set<String> included() {
    return included;
  }

  @Override
  public String baseline() {
    return baseSnapshotId;
  }

  public void copyObject(String hash, OutputStream destination) throws Exception {
    ZipContainer.Entry entry = objects.get(hash);
    Long size = manifest.objects.get(hash);
    StrictJson.require(entry != null && size != null && entry.size == size, "对象缺失或大小不符");
    try (InputStream input = zip.open(entry)) {
      ContentStore.copyVerified(input, destination, hash, size);
    }
  }

  static byte[] read(InputStream input, int max) throws Exception {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int n;
    while ((n = input.read(buffer)) != -1) {
      StrictJson.require(result.size() + n <= max, "输入大小超限");
      result.write(buffer, 0, n);
    }
    return result.toByteArray();
  }

  private static final OutputStream DISCARD =
      new OutputStream() {
        @Override
        public void write(int b) {}

        @Override
        public void write(byte[] b, int off, int len) {}
      };

  @Override
  public void close() throws java.io.IOException {
    zip.close();
  }
}
