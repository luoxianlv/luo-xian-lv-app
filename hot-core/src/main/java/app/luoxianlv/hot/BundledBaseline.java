package app.luoxianlv.hot;

import android.content.Context;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** 仅从已安装 APK 的 assets 读取恢复组合；不接受外部路径或网络清单。 */
public final class BundledBaseline {
  // 只保存路径字符串；本进程准备过的恢复组合即使尚未建立加载器也不能被清理。
  private static final Set<String> requested = new HashSet<>();
  final File directory, runtime, business;
  final String runtimeHash, businessHash, abi, entry;
  private final long runtimeSize, businessSize;

  private BundledBaseline(
      File directory,
      File runtime,
      File business,
      String runtimeHash,
      String businessHash,
      String abi,
      String entry,
      long runtimeSize,
      long businessSize) {
    this.directory = directory;
    this.runtime = runtime;
    this.business = business;
    this.runtimeHash = runtimeHash;
    this.businessHash = businessHash;
    this.abi = abi;
    this.entry = entry;
    this.runtimeSize = runtimeSize;
    this.businessSize = businessSize;
  }

  void verify() throws Exception {
    ContentStore.verifyFile(runtime, runtimeHash, runtimeSize);
    ContentStore.verifyFile(business, businessHash, businessSize);
  }

  /** 在线准备仅复用安装包声明的准确对象；不以外部索引、旧缓存记录或文件名推断身份。 */
  public static UpdateClient.LocalObjects objects(Context context) {
    return new UpdateClient.LocalObjects() {
      @Override
      public synchronized Map<String, Long> baselines() throws Exception {
        StrictJson.require(Looper.myLooper() != Looper.getMainLooper(), "安装包基线检查必须在后台执行");
        Context installed = context.createPackageContext(context.getPackageName(), 0);
        try (InputStream input = installed.getAssets().open("baseline/index.json")) {
          var index =
              StrictJson.object(HotPackage.read(input, 8192))
                  .only("schema", "runtimeAbi", "entryClass", "runtime", "business");
          StrictJson.require(index.number("schema") == 1, "安装包恢复索引无效");
          Map<String, Long> result = new LinkedHashMap<>();
          for (String role : List.of("runtime", "business")) {
            var artifact = index.object(role).only("sha256", "size");
            String hash = artifact.string("sha256");
            long size = artifact.number("size");
            StrictJson.require(
                HotManifest.validHash(hash) && size > 0 && size <= HotManifest.MAX_EXPANDED,
                "安装包恢复对象身份无效");
            Long prior = result.put(hash, size);
            StrictJson.require(prior == null || prior == size, "安装包恢复对象大小冲突");
          }
          return Collections.unmodifiableMap(result);
        }
      }

      @Override
      public synchronized File find(String hash, long size) throws Exception {
        StrictJson.require(Looper.myLooper() != Looper.getMainLooper(), "安装包对象检查必须在后台执行");
        Context installed = context.createPackageContext(context.getPackageName(), 0);
        StrictJson.Obj index;
        byte[] raw;
        try (InputStream input = installed.getAssets().open("baseline/index.json")) {
          raw = HotPackage.read(input, 8192);
          index =
              StrictJson.object(raw)
                  .only("schema", "runtimeAbi", "entryClass", "runtime", "business");
        }
        StrictJson.require(index.number("schema") == 1, "安装包恢复索引无效");
        for (String role : List.of("runtime", "business")) {
          var artifact = index.object(role).only("sha256", "size");
          if (!artifact.string("sha256").equals(hash) || artifact.number("size") != size) continue;
          // 仅准备本候选准确匹配的对象，不为无关的配对模块额外复制或占空间。
          synchronized (BundledBaseline.class) {
            return copy(installed, directory(installed, raw), role, artifact);
          }
        }
        return null;
      }
    };
  }

  public static synchronized BundledBaseline prepare(Context context) throws Exception {
    StrictJson.require(Looper.myLooper() != Looper.getMainLooper(), "内置恢复组合必须在后台准备");
    // 使用真实应用 Context，不能从业务模块覆盖的 AssetManager 读取信任根。
    Context installed = context.createPackageContext(context.getPackageName(), 0);
    byte[] raw;
    try (InputStream input = installed.getAssets().open("baseline/index.json")) {
      raw = HotPackage.read(input, 8192);
    }
    StrictJson.Obj index =
        StrictJson.object(raw).only("schema", "runtimeAbi", "entryClass", "runtime", "business");
    StrictJson.require(index.number("schema") == 1, "内置恢复组合版本不支持");
    String abi = index.string("runtimeAbi"), entry = index.string("entryClass");
    StrictJson.require(
        HotManifest.validId(abi) && entry.matches("[A-Za-z_$][A-Za-z0-9_.$]+"), "内置恢复入口无效");
    File directory = directory(context, raw);
    StrictJson.Obj runtime = index.object("runtime").only("sha256", "size");
    StrictJson.Obj business = index.object("business").only("sha256", "size");
    File runtimeApk = copy(installed, directory, "runtime", runtime);
    File businessApk = copy(installed, directory, "business", business);
    var baseline =
        new BundledBaseline(
            directory,
            runtimeApk,
            businessApk,
            runtime.string("sha256"),
            business.string("sha256"),
            abi,
            entry,
            runtime.number("size"),
            business.number("size"));
    baseline.verify();
    try {
      remember(directory, raw, context.getPackageName());
    } catch (Exception unavailable) {
      /* 清理记录失败不能阻断已验证恢复组合。 */
    }
    collectOld(
        directory.getParentFile(),
        context.getPackageName(),
        HotSignatures.hash(raw),
        requested,
        NativeLoader.residentRuntimeHash());
    return baseline;
  }

  private static File copy(Context installed, File directory, String name, StrictJson.Obj metadata)
      throws Exception {
    String hash = metadata.string("sha256");
    long size = metadata.number("size");
    return copyArtifact(
        directory, name, hash, size, () -> installed.getAssets().open("baseline/" + name + ".apk"));
  }

  @FunctionalInterface
  interface InstalledStream {
    InputStream open() throws Exception;
  }

  /** 纯文件边界便于实测取消；调用方保证来源仅来自已安装APK。 */
  static File copyArtifact(
      File directory, String name, String hash, long size, InstalledStream source)
      throws Exception {
    StrictJson.require(
        (name.equals("runtime") || name.equals("business"))
            && HotManifest.validHash(hash)
            && size > 0
            && size <= HotManifest.MAX_EXPANDED,
        "内置模块声明无效");
    File destination = new File(directory, name + ".apk");
    StrictJson.require(
        !Files.isSymbolicLink(destination.toPath())
            && (!destination.exists() || destination.isFile()),
        "内置模块缓存类型异常");
    if (destination.isFile()) {
      try {
        ContentStore.verifyFile(destination, hash, size);
        return destination;
      } catch (IllegalArgumentException corrupted) {
        /* 内容损坏才重建；取消或读取故障直接传播。 */
      }
    }
    StrictJson.require(directory.getUsableSpace() >= size + (16L << 20), "空间不足，无法准备内置恢复模块");
    File temporary = File.createTempFile(name, ".part", directory);
    try {
      try (InputStream input = source.open();
          FileOutputStream out = new FileOutputStream(temporary)) {
        StrictJson.require(temporary.setReadOnly(), "无法将内置模块设为只读");
        ContentStore.copyVerified(input, out, hash, size);
        out.getFD().sync();
      }
      ContentStore.verifyFile(temporary, hash, size);
      StrictJson.require(
          !Files.isSymbolicLink(destination.toPath())
              && (!destination.exists() || destination.isFile()),
          "内置模块提交目标类型异常");
      if (System.getProperty("os.name", "").startsWith("Windows") && destination.exists())
        destination.setWritable(true, true);
      ContentStore.moveAtomic(
          temporary.toPath(),
          destination.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
      ContentStore.syncDirectory(directory);
      return destination;
    } finally {
      if (temporary.exists()) {
        temporary.setWritable(true, true);
        Files.deleteIfExists(temporary.toPath());
      }
    }
  }

  private static File directory(Context context, byte[] index) throws Exception {
    File parent = new File(context.getNoBackupFilesDir(), "native-baseline");
    StrictJson.require(
        !Files.isSymbolicLink(parent.toPath()) && (parent.isDirectory() || parent.mkdirs()),
        "内置恢复根目录类型异常");
    File result = new File(parent, HotSignatures.hash(index));
    StrictJson.require(
        !Files.isSymbolicLink(result.toPath()) && (result.isDirectory() || result.mkdir()),
        "内置恢复目录类型异常");
    requested.add(result.getCanonicalPath());
    return result;
  }

  /** 只有由安装包逐项验证过的当前缓存才登记；既有异常记录不覆盖。 */
  static void remember(File directory, byte[] index, String applicationId) throws Exception {
    String hash = HotSignatures.hash(index);
    byte[] owner =
        ("{\"schema\":1,\"applicationId\":\""
                + applicationId
                + "\",\"indexHash\":\""
                + hash
                + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    for (var entry : Map.of("index.json", index, "owner.json", owner).entrySet()) {
      File target = new File(directory, entry.getKey());
      if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
          && (Files.isSymbolicLink(target.toPath())
              || !target.isFile()
              || !Arrays.equals(ContentStore.readBounded(target), entry.getValue()))) return;
    }
    for (var entry : Map.of("index.json", index, "owner.json", owner).entrySet()) {
      File target = new File(directory, entry.getKey());
      if (target.exists()) continue;
      File temporary = File.createTempFile("baseline-record-", ".part", directory);
      try {
        ContentStore.writeSynced(temporary.toPath(), entry.getValue());
        ContentStore.replaceSynced(temporary, target);
      } finally {
        Files.deleteIfExists(temporary.toPath());
      }
    }
  }

  /** 未知/旧无记录/损坏/含链接缓存保留；完整性证据通过后才清理旧目录。 */
  static synchronized int collectOld(
      File parent,
      String applicationId,
      String current,
      Set<String> protectedPaths,
      String residentRuntime) {
    int removed = 0;
    try {
      if (Files.isSymbolicLink(parent.toPath()) || !parent.isDirectory()) return 0;
      File root = parent.getCanonicalFile();
      File[] directories = root.listFiles();
      if (directories == null) return 0;
      for (File directory : directories) {
        if (!HotManifest.validHash(directory.getName())
            || directory.getName().equals(current)
            || Files.isSymbolicLink(directory.toPath())
            || !directory.isDirectory()
            || protectedPaths.contains(directory.getCanonicalPath())) continue;
        try {
          var index = complete(directory, applicationId);
          if (index == null
              || (!residentRuntime.isEmpty()
                  && index.object("runtime").string("sha256").equals(residentRuntime))) continue;
          StrictJson.require(directory.getCanonicalFile().getParentFile().equals(root), "基线回收路径越界");
          Files.walkFileTree(
              directory.toPath(),
              new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(
                    java.nio.file.Path file, java.nio.file.attribute.BasicFileAttributes attributes)
                    throws java.io.IOException {
                  if (attributes.isSymbolicLink() || !attributes.isRegularFile())
                    throw new java.io.IOException("基线类型改变");
                  if (System.getProperty("os.name", "").startsWith("Windows"))
                    file.toFile().setWritable(true, true);
                  Files.delete(file);
                  return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(
                    java.nio.file.Path dir, java.io.IOException error) throws java.io.IOException {
                  if (error != null) throw error;
                  Files.delete(dir);
                  return java.nio.file.FileVisitResult.CONTINUE;
                }
              });
          removed++;
        } catch (Exception unknown) {
          /* 不能证明完整归属时不删除；本次启动继续使用已验证当前组合。 */
        }
      }
      if (removed > 0) ContentStore.syncDirectory(root);
    } catch (Exception unavailable) {
      /* 清理故障不阻断当前恢复组合。 */
    }
    return removed;
  }

  private static StrictJson.Obj complete(File directory, String applicationId) throws Exception {
    Set<String> expected =
        Set.of("index.json", "owner.json", "runtime.apk", "business.apk", "native");
    File[] children = directory.listFiles();
    if (children == null) return null;
    for (File child : children)
      if (!expected.contains(child.getName()) || Files.isSymbolicLink(child.toPath())) return null;
    byte[] raw = ContentStore.readBounded(new File(directory, "index.json"));
    if (raw.length > 8192 || !HotSignatures.hash(raw).equals(directory.getName())) return null;
    var owner =
        StrictJson.object(ContentStore.readBounded(new File(directory, "owner.json")))
            .only("schema", "applicationId", "indexHash");
    if (owner.number("schema") != 1
        || !owner.string("applicationId").equals(applicationId)
        || !owner.string("indexHash").equals(directory.getName())) return null;
    var index =
        StrictJson.object(raw).only("schema", "runtimeAbi", "entryClass", "runtime", "business");
    if (index.number("schema") != 1
        || !HotManifest.validId(index.string("runtimeAbi"))
        || !index.string("entryClass").matches("[A-Za-z_$][A-Za-z0-9_.$]+")) return null;
    for (String role : List.of("runtime", "business")) {
      var artifact = index.object(role).only("sha256", "size");
      File apk = new File(directory, role + ".apk");
      if (apk.canWrite()
          || artifact.number("size") <= 0
          || artifact.number("size") > HotManifest.MAX_EXPANDED) return null;
      ContentStore.verifyFile(apk, artifact.string("sha256"), artifact.number("size"));
    }
    File nativeDirectory = new File(directory, "native");
    if (nativeDirectory.exists()) {
      if (!nativeDirectory.isDirectory()) return null;
      File runtime = new File(directory, "runtime.apk");
      var libraries =
          NativeLibraries.inspect(runtime, nativeDirectory, NativeLibraries.systemAbis());
      if (libraries.bytes != 0 || libraries.paths != 0) return null;
      Set<String> names = new HashSet<>();
      try (var zip = new ZipContainer(runtime, 50_000, Long.MAX_VALUE, true)) {
        String chosen = null;
        for (String abi : NativeLibraries.systemAbis())
          if (zip.entries.keySet().stream()
              .anyMatch(name -> name.startsWith("lib/" + abi + "/") && name.endsWith(".so"))) {
            chosen = abi;
            break;
          }
        if (chosen != null)
          for (String name : zip.entries.keySet())
            if (name.startsWith("lib/" + chosen + "/") && name.endsWith(".so"))
              names.add(name.substring(name.lastIndexOf('/') + 1));
      }
      File[] librariesOnDisk = nativeDirectory.listFiles();
      if (librariesOnDisk == null || librariesOnDisk.length != names.size()) return null;
      for (File library : librariesOnDisk)
        if (!names.contains(library.getName())
            || Files.isSymbolicLink(library.toPath())
            || !library.isFile()) return null;
    }
    return index;
  }
}
