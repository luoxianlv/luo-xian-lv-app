package app.luoxianlv.hot;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.*;

/** 按卷合并整组空间需求；预算只做准入，不占用文件系统或删除正在运行的版本。 */
public final class PreparationSpace {
  private static final long RESERVE = 16L << 20;

  public record Volume(String id, long available, long blockSize) {}

  public interface Probe {
    Volume inspect(File directory) throws Exception;
  }

  public record Demand(File directory, long bytes, long extraBlocks) {
    public Demand(File directory, long bytes) {
      this(directory, bytes, 0);
    }
  }

  public static final class Deferred extends Exception {
    public Deferred() {
      super("整组热更空间不足，保留当前版本与恢复副本");
    }
  }

  private final Probe probe;
  private final String[] supportedAbis;

  public PreparationSpace() {
    this(PreparationSpace::systemVolume);
  }

  /** 应用入口提供稳定 Context，按系统卷 UUID 识别 FUSE 与内部存储共享的底层容量。 */
  public PreparationSpace(android.content.Context context) {
    var platform = app.luoxianlv.hot.contract.PlatformApplication.of(context);
    var manager = platform.getSystemService(android.os.storage.StorageManager.class);
    this.probe = directory -> AndroidVolume.inspect(directory.getCanonicalFile(), manager);
    supportedAbis = NativeLibraries.systemAbis();
  }

  public PreparationSpace(Probe probe) {
    this(probe, NativeLibraries.systemAbis());
  }

  public PreparationSpace(Probe probe, String... supportedAbis) {
    this.probe = Objects.requireNonNull(probe);
    this.supportedAbis = supportedAbis.clone();
    StrictJson.require(this.supportedAbis.length > 0, "设备 ABI 不可确认");
  }

  /** 同卷内部/外部目录共享空闲容量，不能分别检查并重复使用同一份空间。 */
  public void admit(List<Demand> demands) throws Exception {
    Map<String, Long> needed = new HashMap<>(), available = new HashMap<>();
    for (Demand demand : demands) {
      StrictJson.require(demand.bytes >= 0 && demand.extraBlocks >= 0, "空间需求无效");
      if (demand.bytes == 0 && demand.extraBlocks == 0) continue;
      Volume volume = probe.inspect(demand.directory);
      StrictJson.require(
          volume.id != null
              && !volume.id.isEmpty()
              && volume.available >= 0
              && volume.blockSize > 0
              && volume.blockSize <= (1L << 20),
          "文件系统容量不可确认");
      available.merge(volume.id, volume.available, Math::min);
      long bytes =
          Math.addExact(
              rounded(demand.bytes, volume.blockSize),
              Math.multiplyExact(demand.extraBlocks, volume.blockSize));
      needed.merge(volume.id, bytes, Math::addExact);
    }
    for (String id : needed.keySet())
      if (Math.addExact(needed.get(id), RESERVE) > available.get(id)) throw new Deferred();
  }

  /** 缺失归档尚不可检查时采用宿主展开上限；已有归档则按其真实目录计算。 */
  public void beforeDownload(
      ContentStore store, ObjectDownloader downloads, HotManifest manifest, String residentRuntime)
      throws Exception {
    Map<String, File> sources = new HashMap<>();
    List<Demand> needs = new ArrayList<>();
    for (var entry : manifest.objects.entrySet()) {
      File object = store.objectFile(entry.getKey());
      if (Files.exists(object.toPath(), LinkOption.NOFOLLOW_LINKS))
        sources.put(entry.getKey(), object);
      else {
        needs.add(new Demand(store.rootDirectory(), entry.getValue()));
        File partial = downloads.partial(entry.getKey());
        StrictJson.require(
            !Files.isSymbolicLink(partial.toPath()) && (!partial.exists() || partial.isFile()),
            "下载暂存类型异常");
        long present = partial.isFile() ? partial.length() : 0;
        StrictJson.require(present <= entry.getValue(), "下载暂存超过对象声明");
        if (present == entry.getValue()) {
          ContentStore.verifyFile(partial, entry.getKey(), entry.getValue());
          sources.put(entry.getKey(), partial);
        }
        needs.add(new Demand(downloads.directory(), entry.getValue() - present));
      }
    }
    needs.addAll(materialization(store, manifest, sources, residentRuntime));
    admit(needs);
  }

  /** 所有来源已验哈希；内部复制前同时预算原始对象、APK 副本、资源与运行时库。 */
  public void beforeCommit(
      ContentStore store,
      HotManifest manifest,
      Map<String, File> downloaded,
      String residentRuntime)
      throws Exception {
    Map<String, File> sources = new HashMap<>();
    List<Demand> needs = new ArrayList<>();
    for (var entry : manifest.objects.entrySet()) {
      File object = store.objectFile(entry.getKey());
      if (object.isFile()) sources.put(entry.getKey(), object);
      else {
        File source = downloaded.get(entry.getKey());
        StrictJson.require(source != null, "缺少完整空间预算的归档来源");
        sources.put(entry.getKey(), source);
        needs.add(new Demand(store.rootDirectory(), entry.getValue()));
      }
    }
    needs.addAll(materialization(store, manifest, sources, residentRuntime));
    admit(needs);
  }

  /** 冷启动/离线导入也须覆盖整组，不能只靠逐个文件写入时的余额检查。 */
  void beforeLoad(ContentStore store, ContentStore.Snapshot snapshot, String residentRuntime)
      throws Exception {
    Map<String, File> sources = new HashMap<>();
    for (String hash : snapshot.manifest.objects.keySet())
      sources.put(hash, store.objectFile(hash));
    admit(materialization(store, snapshot.manifest, sources, residentRuntime));
  }

  private List<Demand> materialization(
      ContentStore store, HotManifest manifest, Map<String, File> sources, String residentRuntime)
      throws Exception {
    File root = store.rootDirectory();
    File snapshot = new File(root, "snapshots/" + manifest.snapshotId);
    List<Demand> needs = new ArrayList<>();
    if (!new File(snapshot, "business.apk").isFile())
      needs.add(new Demand(root, manifest.business.size));
    if (!manifest.runtime.sha256.equals(residentRuntime)) {
      File runtime = new File(root, "runtimes/" + manifest.runtime.sha256);
      if (!new File(runtime, "runtime.apk").isFile())
        needs.add(new Demand(root, manifest.runtime.size));
      File archive = sources.get(manifest.runtime.sha256);
      var nativeFiles =
          archive == null
              ? new ResourceMounts.Expansion(HotManifest.MAX_EXPANDED, 0)
              : nativeExpansion(archive, new File(runtime, "native"));
      needs.add(new Demand(root, nativeFiles.bytes(), nativeFiles.paths()));
    }
    long resources = 0, code = 0;
    boolean unknown = false;
    boolean mounted = new File(snapshot, "resources").isDirectory();
    for (var artifact : manifest.artifacts) {
      if (artifact.mount.isEmpty()) {
        code = Math.addExact(code, artifact.size);
        continue;
      }
      File archive = sources.get(artifact.sha256);
      if (artifact.role.equals("resources") && archive == null) {
        unknown = true;
        continue;
      }
      var expansion = ResourceMounts.expansion(artifact, archive);
      resources = Math.addExact(resources, expansion.bytes());
      // 每个文件和路径段留一个分配块；重复父目录按上界计，兼容小文件与深目录。
      if (!mounted) needs.add(new Demand(root, 0, expansion.paths()));
    }
    if (!unknown)
      StrictJson.require(
          Math.addExact(code, resources) <= HotManifest.MAX_EXPANDED, "整个资源候选展开大小超限");
    if (!mounted) needs.add(new Demand(root, unknown ? HotManifest.MAX_EXPANDED : resources));
    return needs;
  }

  private ResourceMounts.Expansion nativeExpansion(File archive, File directory) throws Exception {
    // 独立协议向量允许不含 APK 的占位对象；执行前仍由 NativeLoader 拒绝无效运行时。
    try (var input = Files.newInputStream(archive.toPath())) {
      if (input.read() != 'P' || input.read() != 'K')
        return new ResourceMounts.Expansion(HotManifest.MAX_EXPANDED, 0);
    }
    var nativeFiles = NativeLibraries.inspect(archive, directory, supportedAbis);
    return new ResourceMounts.Expansion(nativeFiles.bytes, nativeFiles.paths);
  }

  private static long rounded(long bytes, long block) {
    return Math.multiplyExact(Math.floorDiv(Math.addExact(bytes, block - 1), block), block);
  }

  private static Volume systemVolume(File directory) throws Exception {
    File existing = directory.getCanonicalFile();
    while (!existing.exists()) existing = existing.getParentFile();
    if (System.getProperty("java.vm.name", "").equals("Dalvik"))
      return AndroidVolume.inspect(existing, null);
    var volume = Files.getFileStore(existing.toPath());
    return new Volume(volume.name() + ":" + volume.type(), volume.getUsableSpace(), 4096);
  }

  private static final class AndroidVolume {
    static Volume inspect(File directory, android.os.storage.StorageManager manager)
        throws Exception {
      var space = android.system.Os.statvfs(directory.getPath());
      String identity = "android-conservative";
      if (manager != null) {
        try {
          identity = manager.getUuidForPath(directory).toString();
        } catch (java.io.IOException unavailable) {
          var volume = manager.getStorageVolume(directory);
          if (volume == null || volume.isEmulated() || volume.getUuid() == null) throw unavailable;
          identity = "public:" + volume.getUuid();
        }
      }
      return new Volume(identity, directory.getUsableSpace(), space.f_frsize);
    }
  }
}
