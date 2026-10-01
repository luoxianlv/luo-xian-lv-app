package app.luoxianlv.hot;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;

/** 预算与加载共用：只展开设备选中的 ABI，已按签名归档内容验证的只读副本直接复用。 */
final class NativeLibraries {
  private record Library(String entry, String name, long size, String hash, boolean cached) {}

  static final class Plan {
    final File archive, directory;
    private final List<Library> libraries;
    final long bytes, paths;

    private Plan(File archive, File directory, List<Library> libraries, long bytes, long paths) {
      this.archive = archive;
      this.directory = directory;
      this.libraries = List.copyOf(libraries);
      this.bytes = bytes;
      this.paths = paths;
    }

    String materialize() throws Exception {
      if (libraries.isEmpty()) return null;
      StrictJson.require(!Files.isSymbolicLink(directory.toPath())
          && (directory.isDirectory() || directory.mkdirs()), "无法创建内部原生库目录");
      try (var zip = new ZipContainer(archive, 50_000, Long.MAX_VALUE, true)) {
        for (Library library : libraries) {
          File target = new File(directory, library.name);
          if (reusable(target, library.hash, library.size)) continue;
          // 缓存身份若在准入之后变化，重新检查；不消耗预算之外的一份库副本。
          StrictJson.require(!library.cached, "原生库缓存在准备期间改变，暂缓加载");
          File temporary = File.createTempFile("native-", ".part", directory);
          try {
            try (InputStream input = zip.open(zip.entries.get(library.entry));
                FileOutputStream output = new FileOutputStream(temporary)) {
              StrictJson.require(temporary.setReadOnly(), "无法将原生库设为只读");
              ContentStore.copyVerified(input, output, library.hash, library.size);
              output.getFD().sync();
            }
            if (System.getProperty("os.name", "").startsWith("Windows") && target.exists())
              target.setWritable(true, true);
            ContentStore.moveAtomic(temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
          } finally {
            if (temporary.exists()) {
              temporary.setWritable(true, true);
              Files.deleteIfExists(temporary.toPath());
            }
          }
        }
      }
      ContentStore.syncDirectory(directory);
      return directory.getAbsolutePath();
    }
  }

  static Plan inspect(File archive, File directory, String[] supportedAbis) throws Exception {
    StrictJson.require(!Files.isSymbolicLink(directory.toPath())
        && (!directory.exists() || directory.isDirectory()), "原生库目录类型异常");
    try (var zip = new ZipContainer(archive, 50_000, Long.MAX_VALUE, true)) {
      Set<String> available = new HashSet<>();
      for (var entry : zip.entries.values()) {
        if (!entry.name.startsWith("lib/") || !entry.name.endsWith(".so")) continue;
        String[] parts = entry.name.split("/", -1);
        StrictJson.require(parts.length == 3, "原生库路径无效");
        available.add(parts[1]);
      }
      if (available.isEmpty()) return new Plan(archive, directory, List.of(), 0, 0);
      String chosen = null;
      for (String abi : supportedAbis)
        if (available.contains(abi)) { chosen = abi; break; }
      StrictJson.require(chosen != null, "运行时不支持当前设备 CPU 架构");
      List<Library> libraries = new ArrayList<>();
      long expanded = 0, missing = 0, paths = directory.isDirectory() ? 0 : 1;
      for (var entry : zip.entries.values()) {
        if (!entry.name.startsWith("lib/" + chosen + "/") || !entry.name.endsWith(".so")) continue;
        String name = entry.name.substring(entry.name.lastIndexOf('/') + 1);
        StrictJson.require(name.matches("[A-Za-z0-9_.-]+\\.so")
            && entry.size > 0 && entry.size <= HotManifest.MAX_EXPANDED, "原生库名称或大小无效");
        expanded = Math.addExact(expanded, entry.size);
        StrictJson.require(expanded <= HotManifest.MAX_EXPANDED, "原生库展开总量超限");
        String hash;
        try (InputStream input = zip.open(entry)) { hash = hash(input, entry.size); }
        boolean cached = reusable(new File(directory, name), hash, entry.size);
        if (!cached) {
          missing = Math.addExact(missing, entry.size);
          paths++;
        }
        libraries.add(new Library(entry.name, name, entry.size, hash, cached));
      }
      return new Plan(archive, directory, libraries, missing, paths);
    }
  }

  static String[] systemAbis() {
    if (System.getProperty("java.vm.name", "").equals("Dalvik"))
      return android.os.Build.SUPPORTED_ABIS.clone();
    // 离线 JVM 验证不加载 Android Build；测试可显式传入 ABI 顺序。
    return switch (System.getProperty("os.arch", "").toLowerCase(Locale.ROOT)) {
      case "aarch64", "arm64" -> new String[] {"arm64-v8a", "armeabi-v7a"};
      case "arm", "armv7" -> new String[] {"armeabi-v7a"};
      case "x86", "i386" -> new String[] {"x86"};
      default -> new String[] {"x86_64", "x86"};
    };
  }

  private static boolean reusable(File target, String hash, long size) throws Exception {
    if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return false;
    StrictJson.require(!Files.isSymbolicLink(target.toPath()) && target.isFile(), "原生库缓存类型异常");
    if (target.length() != size || target.canWrite()) return false;
    try {
      ContentStore.verifyFile(target, hash, size);
      return true;
    } catch (java.io.InterruptedIOException cancelled) {
      throw cancelled;
    } catch (IllegalArgumentException corrupted) {
      return false;
    }
  }

  private static String hash(InputStream input, long size) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    byte[] buffer = new byte[32768];
    long total = 0;
    int count;
    while ((count = input.read(buffer)) != -1) {
      if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("原生库校验已取消");
      total = Math.addExact(total, count);
      StrictJson.require(total <= size, "原生库展开大小改变");
      digest.update(buffer, 0, count);
    }
    StrictJson.require(total == size, "原生库展开不完整");
    return HotSignatures.hex(digest.digest());
  }
}
