package app.luoxianlv.hot;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** 按完整快照引用回收内部缓存；调用方在更新工作线程空闲时提供持久状态中的保护集合。 */
public final class ContentCollector {
  public static final class Deferred extends Exception {
    public Deferred() {
      super("受保护的热更缓存超过预算，保留当前版本并暂缓下载");
    }
  }

  public interface ProtectedSnapshots {
    Set<String> read() throws Exception;
  }

  public record Result(
      long beforeBytes, long afterBytes, int removedSnapshots, boolean overBudget) {}

  private final ContentStore store;
  private final Path root;

  public ContentCollector(ContentStore store) {
    this.store = store;
    root = store.rootDirectory().toPath();
  }

  /** 预算为软上限，受保护的版本可以超限；此时只报告，不能牺牲运行和回退内容。 */
  public Result collect(ProtectedSnapshots protection, int recentCount, long budgetBytes)
      throws Exception {
    StrictJson.require(recentCount >= 0 && recentCount <= 16 && budgetBytes >= 0, "对象回收预算无效");
    try (var channel =
            FileChannel.open(
                root.resolve("prepare.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock()) {
      StrictJson.require(lock != null, "更新任务正在准备，推迟对象回收");
      Set<String> protectedIds = new HashSet<>(protection.read());
      protectedIds.addAll(ContentLeases.snapshots(store.rootDirectory()));
      for (String id : protectedIds) StrictJson.require(HotManifest.validHash(id), "受保护快照身份无效");
      Map<String, ContentStore.Snapshot> snapshots = new HashMap<>();
      Map<String, Long> verifiedObjects = new HashMap<>();
      List<Path> snapshotPaths = children("snapshots");
      // 先验证全部保护内容，任何损坏都使本次回收整体推迟，避免误删唯一恢复对象。
      for (String id : protectedIds) {
        Path path = root.resolve("snapshots").resolve(id);
        validateTree(path);
        var snapshot = store.snapshot(id);
        for (var entry : snapshot.manifest.objects.entrySet()) {
          Path object = store.objectFile(entry.getKey()).toPath();
          validateTree(object);
          Long verified = verifiedObjects.get(entry.getKey());
          if (verified == null) {
            ContentStore.verifyFile(object.toFile(), entry.getKey(), entry.getValue());
            verifiedObjects.put(entry.getKey(), entry.getValue());
          } else StrictJson.require(verified.equals(entry.getValue()), "保护快照对象大小不一致");
        }
        snapshots.put(id, snapshot);
      }
      List<Path> candidates = new ArrayList<>();
      for (Path path : snapshotPaths) {
        String id = path.getFileName().toString();
        if (!HotManifest.validHash(id)) continue;
        validateTree(path);
        if (protectedIds.contains(id)) continue;
        try {
          snapshots.put(id, store.snapshot(id));
        } catch (Exception invalid) {
          /* 未被保护的损坏元数据只作为待清理项。 */
        }
        candidates.add(path);
      }
      candidates.sort(
          Comparator.comparingLong(ContentCollector::modified)
              .reversed()
              .thenComparing(Path::toString));
      List<Path> objectPaths = children("objects"), runtimePaths = children("runtimes");
      List<Path> stagingPaths = children("staging");
      // 所有计划涉及的路径先做边界检查；递归删除不跟随链接，也不越过内部对象库。
      for (Path path : objectPaths) if (ownedHash(path)) validateTree(path);
      for (Path path : runtimePaths) if (ownedHash(path)) validateTree(path);
      for (Path path : stagingPaths) if (ownedStaging(path)) validateTree(path);
      long before = usage();
      Set<String> keep = new HashSet<>(protectedIds);
      int retained = 0;
      for (Path candidate : candidates) {
        String id = candidate.getFileName().toString();
        if (snapshots.containsKey(id) && retained < recentCount) {
          keep.add(id);
          retained++;
        }
      }
      Set<String> runtimePins = ContentLeases.runtimes(store.rootDirectory());
      for (String hash : runtimePins) {
        Path object = store.objectFile(hash).toPath();
        validateTree(object);
        if (!verifiedObjects.containsKey(hash))
          ContentStore.verifyFile(object.toFile(), hash, Files.size(object));
      }
      // 额外历史从最旧的开始让出预算；保护快照和共享运行时租约始终保留。
      for (int i = candidates.size() - 1; i >= 0; i--) {
        if (retainedUsage(keep, snapshots, runtimePins) <= budgetBytes) break;
        keep.remove(candidates.get(i).getFileName().toString());
      }
      Set<String> referenced = references(keep, snapshots);
      referenced.addAll(runtimePins);
      Set<String> runtimes = new HashSet<>(runtimePins);
      for (String id : keep) runtimes.add(snapshots.get(id).manifest.runtime.sha256);
      int removed = 0;
      for (Path candidate : candidates)
        if (!keep.contains(candidate.getFileName().toString())) {
          deleteOwned(candidate);
          removed++;
        }
      for (Path path : objectPaths)
        if (ownedHash(path) && !referenced.contains(path.getFileName().toString()))
          deleteOwned(path);
      for (Path path : runtimePaths)
        if (ownedHash(path) && !runtimes.contains(path.getFileName().toString())) deleteOwned(path);
      for (Path path : stagingPaths) if (ownedStaging(path)) deleteOwned(path);
      for (String name : List.of("snapshots", "objects", "runtimes", "staging")) {
        File directory = root.resolve(name).toFile();
        if (directory.isDirectory()) ContentStore.syncDirectory(directory);
      }
      long after = usage();
      return new Result(before, after, removed, after > budgetBytes);
    }
  }

  private Set<String> references(Set<String> keep, Map<String, ContentStore.Snapshot> snapshots) {
    Set<String> objects = new HashSet<>();
    for (String id : keep) objects.addAll(snapshots.get(id).manifest.objects.keySet());
    return objects;
  }

  private long retainedUsage(
      Set<String> keep, Map<String, ContentStore.Snapshot> snapshots, Set<String> runtimePins)
      throws Exception {
    long size = 0;
    for (String id : keep) size = Math.addExact(size, bytes(root.resolve("snapshots").resolve(id)));
    Set<String> objects = references(keep, snapshots);
    objects.addAll(runtimePins);
    for (String hash : objects)
      size = Math.addExact(size, bytes(root.resolve("objects").resolve(hash)));
    Set<String> runtimes = new HashSet<>(runtimePins);
    for (String id : keep) runtimes.add(snapshots.get(id).manifest.runtime.sha256);
    for (String hash : runtimes)
      size = Math.addExact(size, bytes(root.resolve("runtimes").resolve(hash)));
    return size;
  }

  private List<Path> children(String name) throws Exception {
    Path directory = root.resolve(name);
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
    StrictJson.require(
        !Files.isSymbolicLink(directory) && Files.isDirectory(directory), "对象库目录不是内部普通目录");
    try (var stream = Files.list(directory)) {
      return stream.collect(java.util.stream.Collectors.toList());
    }
  }

  private long usage() throws Exception {
    long size = 0;
    for (String name : List.of("objects", "snapshots", "runtimes", "staging"))
      for (Path path : children(name)) size = Math.addExact(size, bytes(path));
    return size;
  }

  private static long bytes(Path path) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return 0;
    final long[] size = {0};
    Files.walkFileTree(
        path,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            size[0] = Math.addExact(size[0], attrs.size());
            return FileVisitResult.CONTINUE;
          }
        });
    return size[0];
  }

  private static long modified(Path path) {
    try {
      return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
    } catch (IOException ignored) {
      return 0;
    }
  }

  private static boolean ownedHash(Path path) {
    return HotManifest.validHash(path.getFileName().toString());
  }

  private static boolean ownedStaging(Path path) {
    String name = path.getFileName().toString();
    return name.startsWith("snapshot-") || (name.startsWith("object-") && name.endsWith(".part"));
  }

  private void validateTree(Path path) throws Exception {
    StrictJson.require(path.normalize().startsWith(root) && !path.equals(root), "回收路径越界");
    StrictJson.require(
        path.toFile().getCanonicalFile().toPath().equals(path.toAbsolutePath()), "回收路径不是内部真实路径");
    Path parent = path;
    while (!parent.equals(root)) {
      StrictJson.require(!Files.isSymbolicLink(parent), "回收路径含符号链接");
      parent = parent.getParent();
    }
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
    Files.walkFileTree(
        path,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            if (!dir.toFile().getCanonicalFile().toPath().equals(dir.toAbsolutePath()))
              throw new IOException("对象库目录指向外部路径，推迟回收");
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (attrs.isSymbolicLink() || !attrs.isRegularFile())
              throw new IOException("对象库含链接或特殊文件，推迟回收");
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private void deleteOwned(Path path) throws Exception {
    validateTree(path);
    Files.walkFileTree(
        path,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (System.getProperty("os.name", "").startsWith("Windows"))
              file.toFile().setWritable(true, true);
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException error)
              throws IOException {
            if (error != null) throw error;
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
