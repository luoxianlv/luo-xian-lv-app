package app.luoxianlv.hot;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Map;

/** 内部只读对象库。调用方必须放到后台执行；准备候选不会改变活动版本。 */
public final class ContentStore {
  private final File root, objects, snapshots, staging;

  public ContentStore(File internalRoot) throws Exception {
    root = internalRoot.getCanonicalFile();
    objects = directory("objects");
    snapshots = directory("snapshots");
    staging = directory("staging");
  }

  public static final class Snapshot {
    public final HotManifest manifest;
    public final File directory;

    Snapshot(HotManifest manifest, File directory) {
      this.manifest = manifest;
      this.directory = directory;
    }
  }

  public synchronized Snapshot prepare(HotPackage candidate) throws Exception {
    return prepareSource(candidate);
  }

  public synchronized Snapshot prepare(DownloadedSnapshot candidate) throws Exception {
    return prepareSource(candidate);
  }

  private Snapshot prepareSource(SnapshotSource source) throws Exception {
    SignedSnapshot candidate = source.metadata();
    try (FileChannel channel =
            FileChannel.open(
                new File(root, "prepare.lock").toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock()) {
      StrictJson.require(lock != null, "另一个更新任务正在准备，请稍后重试");
      Snapshot baseline = null;
      if (!source.baseline().isEmpty()) {
        baseline = snapshot(source.baseline());
        StrictJson.require(
            baseline.manifest.applicationId.equals(candidate.manifest.applicationId)
                && baseline.manifest.environment.equals(candidate.manifest.environment),
            "增量基线应用或环境不符");
      }
      long missing = 0;
      for (Map.Entry<String, Long> object : candidate.manifest.objects.entrySet()) {
        if (!objectFile(object.getKey()).isFile()) missing += object.getValue();
        if (!source.included().contains(object.getKey()) && !source.baseline().isEmpty()) {
          StrictJson.require(
              baseline != null
                  && object.getValue().equals(baseline.manifest.objects.get(object.getKey())),
              "增量省略的对象不属于准确基线");
        }
      }
      StrictJson.require(
          root.getUsableSpace() >= missing + 16L * StrictJson.MAX_BYTES, "空间不足，保留原稳定版本");
      for (Map.Entry<String, Long> object : candidate.manifest.objects.entrySet()) {
        File destination = objectFile(object.getKey());
        if (destination.exists()) verifyFile(destination, object.getKey(), object.getValue());
        else {
          StrictJson.require(source.included().contains(object.getKey()), "缓存对象已丢失，需重新下载缺失对象");
          commitObject(source, object.getKey(), destination);
        }
      }
      File target = new File(snapshots, candidate.manifest.snapshotId);
      if (target.exists()) {
        Snapshot existing = snapshot(candidate.manifest.snapshotId);
        verifySnapshotObjects(existing);
        return existing;
      }
      Path temporary = Files.createTempDirectory(staging.toPath(), "snapshot-");
      try {
        writeSynced(temporary.resolve("manifest.json"), candidate.manifestBytes());
        writeSynced(temporary.resolve("manifest.sig.json"), candidate.manifestSignature());
        writeSynced(temporary.resolve("trust.json"), candidate.trustBytes());
        writeSynced(temporary.resolve("trust.sig.json"), candidate.trustSignature());
        syncDirectory(temporary.toFile());
        moveAtomic(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        syncDirectory(snapshots);
      } finally {
        removeTemporary(temporary);
      }
      return new Snapshot(candidate.manifest, target);
    }
  }

  public synchronized Snapshot snapshot(String id) throws Exception {
    StrictJson.require(HotManifest.validHash(id), "快照身份无效");
    File directory = new File(snapshots, id);
    byte[] raw = readBounded(new File(directory, "manifest.json"));
    StrictJson.require(HotSignatures.hash(raw).equals(id), "内部快照清单损坏");
    return new Snapshot(new HotManifest(raw), directory);
  }

  public void verifySnapshotObjects(Snapshot snapshot) throws Exception {
    for (Map.Entry<String, Long> object : snapshot.manifest.objects.entrySet())
      verifyFile(objectFile(object.getKey()), object.getKey(), object.getValue());
  }

  public File objectFile(String hash) {
    StrictJson.require(HotManifest.validHash(hash), "对象身份无效");
    return new File(objects, hash);
  }

  File rootDirectory() {
    return root;
  }

  /** 验证和登记在清理共用的文件锁内；拿到租约后才能建立加载器或资源副本。 */
  AutoCloseable pin(Snapshot snapshot) throws Exception {
    try (var channel =
            FileChannel.open(
                new File(root, "prepare.lock").toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock()) {
      StrictJson.require(lock != null, "对象库正在准备或清理，稍后建立模块");
      Snapshot current = snapshot(snapshot.manifest.snapshotId);
      return ContentLeases.pin(root, current.manifest.snapshotId, current.manifest.runtime.sha256);
    }
  }

  AutoCloseable pinRuntime(String hash) throws Exception {
    StrictJson.require(HotManifest.validHash(hash), "运行时租约身份无效");
    try (var channel =
            FileChannel.open(
                new File(root, "prepare.lock").toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock()) {
      StrictJson.require(lock != null, "对象库正在准备或清理，稍后建立运行时");
      StrictJson.require(objectFile(hash).isFile(), "运行时对象已丢失");
      return ContentLeases.pin(root, "", hash);
    }
  }

  File runtimeDirectory(String hash) throws Exception {
    StrictJson.require(HotManifest.validHash(hash), "运行时身份无效");
    File result = new File(directory("runtimes"), hash);
    StrictJson.require(
        !Files.isSymbolicLink(result.toPath()) && (result.isDirectory() || result.mkdirs()),
        "无法创建运行时目录");
    return result;
  }

  private void commitObject(SnapshotSource source, String hash, File destination) throws Exception {
    File temporary = File.createTempFile("object-", ".part", staging);
    try {
      // Android 新版本要求可执行文件在写入期间即只读；已打开的描述符继续完成写入。
      try (FileOutputStream output = new FileOutputStream(temporary)) {
        StrictJson.require(temporary.setReadOnly(), "无法把可执行对象设为只读");
        source.copyObject(hash, output);
        output.getFD().sync();
      }
      moveAtomic(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
      syncDirectory(objects);
    } finally {
      if (temporary.exists()) {
        temporary.setWritable(true, true);
        Files.deleteIfExists(temporary.toPath());
      }
    }
  }

  static void verifyFile(File file, String expected, long size) throws Exception {
    StrictJson.require(
        file.isFile() && file.length() == size && !Files.isSymbolicLink(file.toPath()),
        "内部对象缺失或大小改变");
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    long count = 0;
    byte[] buffer = new byte[32768];
    try (InputStream input = new FileInputStream(file)) {
      int n;
      while ((n = input.read(buffer)) != -1) {
        if (Thread.currentThread().isInterrupted())
          throw new java.io.InterruptedIOException("对象校验已取消");
        count += n;
        StrictJson.require(count <= size, "内部对象大小超限");
        digest.update(buffer, 0, n);
      }
    }
    StrictJson.require(
        count == size && HotSignatures.hex(digest.digest()).equals(expected), "内部对象内容损坏");
  }

  static void copyVerified(
      InputStream input, java.io.OutputStream destination, String hash, long size)
      throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    long count = 0;
    byte[] buffer = new byte[32768];
    int n;
    while ((n = input.read(buffer)) != -1) {
      if (Thread.currentThread().isInterrupted())
        throw new java.io.InterruptedIOException("对象复制已取消");
      count += n;
      StrictJson.require(n > 0 && count <= size, "实际对象读取异常或大小超限");
      digest.update(buffer, 0, n);
      destination.write(buffer, 0, n);
    }
    StrictJson.require(
        count == size && HotSignatures.hex(digest.digest()).equals(hash), "对象实际内容校验失败");
  }

  static byte[] readBounded(File file) throws Exception {
    StrictJson.require(
        file.isFile()
            && file.length() <= StrictJson.MAX_BYTES
            && !Files.isSymbolicLink(file.toPath()),
        "内部元数据缺失或超限");
    try (InputStream input = new FileInputStream(file)) {
      return HotPackage.read(input, StrictJson.MAX_BYTES);
    }
  }

  static void writeSynced(Path path, byte[] raw) throws Exception {
    try (FileOutputStream output = new FileOutputStream(path.toFile())) {
      output.write(raw);
      output.getFD().sync();
    }
  }

  static void syncDirectory(File directory) throws Exception {
    // Windows 本地单测不支持目录句柄同步；Android/Linux 上失败必须中止激活准备。
    if (System.getProperty("os.name", "").startsWith("Windows")) return;
    try (FileChannel channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  /** 状态文件原子替换；Windows 偶发共享锁只短暂重试，绝不降级为先删后写。 */
  static void replaceSynced(File temporary, File target) throws Exception {
    StrictJson.require(
        temporary
            .getCanonicalFile()
            .getParentFile()
            .equals(target.getCanonicalFile().getParentFile()),
        "原子状态替换必须位于同一目录");
    moveAtomic(
        temporary.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
    syncDirectory(target.getParentFile());
  }

  static void moveAtomic(Path source, Path target, java.nio.file.CopyOption... options)
      throws Exception {
    for (int attempt = 0; ; attempt++) {
      try {
        Files.move(source, target, options);
        break;
      } catch (java.nio.file.AccessDeniedException error) {
        if (!System.getProperty("os.name", "").startsWith("Windows") || attempt >= 3) throw error;
        try {
          Thread.sleep(20L * (attempt + 1));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw interrupted;
        }
      }
    }
  }

  private File directory(String name) throws Exception {
    File directory = new File(root, name);
    StrictJson.require(
        !Files.isSymbolicLink(directory.toPath())
            && (directory.isDirectory() || directory.mkdirs()),
        "无法创建内部更新目录");
    return directory;
  }

  private static void removeTemporary(Path directory) throws Exception {
    if (!Files.exists(directory)) return;
    Files.walkFileTree(
        directory,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws java.io.IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, java.io.IOException error)
              throws java.io.IOException {
            if (error != null) throw error;
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
