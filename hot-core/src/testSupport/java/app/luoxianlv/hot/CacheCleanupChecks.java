package app.luoxianlv.hot;

import android.content.Context;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;

/** 测试APK随机目录的真实文件系统检查；小内存Source，不冒充HTTP或真实低空间。 */
final class CacheCleanupChecks {
  static void run(Context context) throws Exception {
    check(context.getPackageName().equals("app.luoxianlv.hot.test"), "仅允许独立核心测试APK");
    File privateBase = context.getNoBackupFilesDir(), externalBase = context.getExternalFilesDir("cache-check");
    check(externalBase != null && (externalBase.isDirectory() || externalBase.mkdirs()), "测试外部目录不可用");
    File internal = Files.createTempDirectory(privateBase.toPath(), "cache-check-").toFile();
    File external = Files.createTempDirectory(externalBase.toPath(), "cache-check-").toFile();
    try {
      var budget = new DownloadBudget(new File(internal, "budget"));
      var downloads = new ObjectDownloader(new File(internal, "downloads"), budget, new File(internal, "records"));
      String old = download(downloads, new byte[] {1, 2, 3});
      String current = download(downloads, new byte[] {4, 5, 6});
      var first = downloads.collect(Set.of(current), 0);
      check(first.removed() == 1 && !downloads.partial(old).exists(), "真实内部同卷认领未清旧断点");
      check(downloads.partial(current).isFile(), "当前候选断点丢失");
      byte[] changed = {9, 5, 6}; Files.write(downloads.partial(current).toPath(), changed);
      String unknown = HotSignatures.hash(new byte[] {7, 7});
      Files.write(downloads.partial(unknown).toPath(), new byte[] {7, 7});
      check(downloads.collect(Set.of(), 0).removed() == 0, "未知或改动断点被回收");
      check(Arrays.equals(changed, Files.readAllBytes(downloads.partial(current).toPath()))
          && downloads.partial(unknown).isFile(), "未知字节未保留");
      String active = download(downloads, new byte[] {10, 11, 12});
      try (var channel = FileChannel.open(downloads.partial(active).toPath(), StandardOpenOption.WRITE);
          var lock = channel.lock()) {
        check(downloads.collect(Set.of(), 0).removed() == 0 && downloads.partial(active).isFile(), "活跃锁断点被回收");
      }
      check(downloads.collect(Set.of(), 0).removed() == 1, "锁释放后未能安全回收");
      File sentinel = new File(internal, "user-sentinel"); byte[] user = {3, 1, 4}; Files.write(sentinel.toPath(), user);
      String linked = download(downloads, new byte[] {13, 14, 15});
      Files.delete(downloads.partial(linked).toPath());
      Files.createSymbolicLink(downloads.partial(linked).toPath(), sentinel.toPath());
      check(downloads.collect(Set.of(), 0).removed() == 0 && Files.isSymbolicLink(downloads.partial(linked).toPath()), "链接被回收");
      check(Arrays.equals(user, Files.readAllBytes(sentinel.toPath())), "链接目标被改变");

      String[] atomicFailure = {""};
      var outside = new ObjectDownloader(external, budget, new File(internal, "external-records"),
          (source, target) -> {
            try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (Exception error) {
              atomicFailure[0] = error.getClass().getSimpleName()
                  + (error instanceof FileSystemException fs ? ":" + fs.getReason() : "");
              throw error;
            }
          });
      byte[] outsideBytes = {20, 21, 22}; String outsideHash = download(outside, outsideBytes);
      var externalResult = outside.collect(Set.of(), 0);
      boolean moved = atomicFailure[0].isEmpty();
      if (moved) check(externalResult.removed() == 1 && !outside.partial(outsideHash).exists(), "真实跨目录原子认领成功但清理结果异常");
      else check(externalResult.removed() == 0 && Arrays.equals(outsideBytes,
          Files.readAllBytes(outside.partial(outsideHash).toPath())), "不支持跨挂载认领时原断点丢失");
      var report = new JSONObject().put("passed", true).put("productionTouched", false)
          .put("source", "ByteArrayInputStream").put("httpTested", false)
          .put("internalAtomicCleanup", true).put("currentCandidatePreserved", true)
          .put("unknownBytesPreserved", true).put("symbolicLinkPreserved", true)
          .put("activeLockPreserved", true).put("releasedLockCleanup", true)
          .put("mountDevicesDiffer", android.system.Os.stat(internal.getPath()).st_dev != android.system.Os.stat(external.getPath()).st_dev)
          .put("externalAtomicClaimSucceeded", moved).put("externalAtomicFailure", atomicFailure[0])
          .put("externalPartPreservedWhenUnsupported", !moved).put("externalRemoved", externalResult.removed());
      Files.write(new File(context.getFilesDir(), "native-cache-report.json").toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
    } finally {
      deleteOwn(internal, privateBase); deleteOwn(external, externalBase);
    }
  }

  private static String download(ObjectDownloader downloader, byte[] bytes) throws Exception {
    String hash = HotSignatures.hash(bytes);
    downloader.download(hash, hash, bytes.length,
        offset -> new ObjectDownloader.Response(offset, bytes.length, bytes.length - offset,
            new ByteArrayInputStream(bytes, (int) offset, bytes.length - (int) offset), () -> {}),
        () -> false, () -> false, () -> bytes.length - downloader.partial(hash).length());
    return hash;
  }

  private static void deleteOwn(File root, File parent) throws Exception {
    check(root.getName().startsWith("cache-check-") && root.getCanonicalFile().getParentFile().equals(parent.getCanonicalFile())
        && !Files.isSymbolicLink(root.toPath()), "独立测试清理路径越界");
    Files.walkFileTree(root.toPath(), new SimpleFileVisitor<>() {
      @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes) throws java.io.IOException {
        Files.delete(file); return FileVisitResult.CONTINUE;
      }
      @Override public FileVisitResult postVisitDirectory(Path dir, java.io.IOException error) throws java.io.IOException {
        if (error != null) throw error;
        Files.delete(dir); return FileVisitResult.CONTINUE;
      }
    });
  }

  private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
