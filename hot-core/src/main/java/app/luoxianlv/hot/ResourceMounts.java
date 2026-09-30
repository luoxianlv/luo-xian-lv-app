package app.luoxianlv.hot;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 资源只展开到候选自己的不可变目录；失败时不覆盖当前资源，也不执行包内文件。 */
public final class ResourceMounts {
  record Expansion(long bytes, long paths) {}

  static Expansion expansion(HotManifest.Artifact artifact, File source) throws Exception {
    if (artifact.role.equals("config")) return new Expansion(artifact.size, 2);
    long bytes = 0, paths = 1;
    try (ZipContainer zip =
        new ZipContainer(source, HotManifest.MAX_OBJECTS, HotManifest.MAX_EXPANDED, true)) {
      validateNames(zip);
      for (ZipContainer.Entry entry : zip.entries.values()) {
        bytes = Math.addExact(bytes, entry.size);
        paths = Math.addExact(paths, entry.name.split("/", -1).length);
      }
    }
    return new Expansion(bytes, paths);
  }

  private final ContentStore store;
  private final PreparationSpace space;

  public ResourceMounts(ContentStore store) {
    this(store, new PreparationSpace());
  }

  ResourceMounts(ContentStore store, PreparationSpace space) {
    this.store = store;
    this.space = space;
  }

  public File prepare(ContentStore.Snapshot snapshot, Set<String> allowedMounts) throws Exception {
    File destination = new File(snapshot.directory, "resources");
    // 不把已有目录名当作验证证据；新启动重新校验对象与资源归档边界。
    long total = 0, resourceBytes = 0, resourcePaths = 0;
    for (HotManifest.Artifact artifact : snapshot.manifest.artifacts) {
      if (artifact.mount.isEmpty()) {
        total += artifact.size;
        continue;
      }
      StrictJson.require(allowedMounts.contains(artifact.mount), "宿主不支持资源挂载点");
      File source = store.objectFile(artifact.sha256);
      ContentStore.verifyFile(source, artifact.sha256, artifact.size);
      var expanded = expansion(artifact, source);
      resourceBytes = Math.addExact(resourceBytes, expanded.bytes);
      resourcePaths = Math.addExact(resourcePaths, expanded.paths);
      total = Math.addExact(total, expanded.bytes);
      StrictJson.require(total <= HotManifest.MAX_EXPANDED, "整个资源候选展开大小超限");
    }
    StrictJson.require(total <= HotManifest.MAX_EXPANDED, "整个候选大小超限");
    if (destination.exists()) {
      StrictJson.require(
          destination.isDirectory() && !Files.isSymbolicLink(destination.toPath()), "资源目录类型已改变");
      verifyExisting(snapshot, destination);
      return destination;
    }
    space.admit(java.util.List.of(
        new PreparationSpace.Demand(snapshot.directory, resourceBytes, resourcePaths)));
    Path staging = Files.createTempDirectory(snapshot.directory.toPath(), "resources-");
    try {
      for (HotManifest.Artifact artifact : snapshot.manifest.artifacts) {
        if (artifact.mount.isEmpty()) continue;
        Path mount = staging.resolve(artifact.mount);
        Files.createDirectories(mount);
        File source = store.objectFile(artifact.sha256);
        if (artifact.role.equals("resources"))
          try (ZipContainer zip =
              new ZipContainer(source, HotManifest.MAX_OBJECTS, HotManifest.MAX_EXPANDED, true)) {
            for (ZipContainer.Entry entry : zip.entries.values()) {
              Path target = mount.resolve(entry.name).normalize();
              StrictJson.require(target.startsWith(mount), "资源路径越界");
              if (entry.directory) {
                Files.createDirectories(target);
                continue;
              }
              Files.createDirectories(target.getParent());
              try (InputStream input = zip.open(entry)) {
                copyReadonly(input, target.toFile(), entry.size);
              }
            }
          }
        else
          try (InputStream input = Files.newInputStream(source.toPath())) {
            copyReadonly(input, mount.resolve("value").toFile(), artifact.size);
          }
      }
      ContentStore.syncDirectory(staging.toFile());
      ContentStore.moveAtomic(staging, destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
      ContentStore.syncDirectory(snapshot.directory);
      return destination;
    } finally {
      removeOwnStaging(staging);
    }
  }

  private void verifyExisting(ContentStore.Snapshot snapshot, File destination) throws Exception {
    Set<Path> expected = new java.util.HashSet<>();
    Path root = destination.toPath().toAbsolutePath().normalize();
    for (HotManifest.Artifact artifact : snapshot.manifest.artifacts) {
      if (artifact.mount.isEmpty()) continue;
      File mount = new File(destination, artifact.mount);
      expectPath(root, mount.toPath(), expected);
      if (artifact.role.equals("resources"))
        try (ZipContainer zip =
            new ZipContainer(
                store.objectFile(artifact.sha256),
                HotManifest.MAX_OBJECTS,
                HotManifest.MAX_EXPANDED,
                true)) {
          for (ZipContainer.Entry entry : zip.entries.values()) {
            File target = new File(mount, entry.name);
            StrictJson.require(!Files.isSymbolicLink(target.toPath()), "资源被替换为链接");
            expectPath(root, target.toPath(), expected);
            if (entry.directory) {
              StrictJson.require(target.isDirectory(), "资源目录缺失");
              continue;
            }
            String hash;
            try (InputStream input = zip.open(entry)) {
              hash = digest(input, entry.size);
            }
            ContentStore.verifyFile(target, hash, entry.size);
          }
        }
      else {
        File value = new File(mount, "value");
        expectPath(root, value.toPath(), expected);
        ContentStore.verifyFile(value, artifact.sha256, artifact.size);
      }
    }
    try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
      paths.forEach(
          path -> {
            if (!path.equals(root))
              StrictJson.require(
                  !Files.isSymbolicLink(path) && expected.contains(path), "资源目录存在额外文件或链接");
          });
    }
  }

  private static void expectPath(Path root, Path target, Set<Path> expected) {
    Path current = target.toAbsolutePath().normalize();
    StrictJson.require(current.startsWith(root), "资源路径越界");
    while (!current.equals(root)) {
      StrictJson.require(!Files.isSymbolicLink(current), "资源路径包含链接");
      expected.add(current);
      current = current.getParent();
    }
  }

  private static String digest(InputStream input, long size) throws Exception {
    java.security.MessageDigest hash = java.security.MessageDigest.getInstance("SHA-256");
    byte[] buffer = new byte[32768];
    long total = 0;
    int n;
    while ((n = input.read(buffer)) != -1) {
      total += n;
      StrictJson.require(total <= size, "资源展开超限");
      hash.update(buffer, 0, n);
    }
    StrictJson.require(total == size, "资源内容不完整");
    return HotSignatures.hex(hash.digest());
  }

  private static void copyReadonly(InputStream input, File destination, long size)
      throws Exception {
    try (FileOutputStream output = new FileOutputStream(destination)) {
      StrictJson.require(destination.setReadOnly(), "无法把资源设为只读");
      byte[] buffer = new byte[32768];
      long total = 0;
      int n;
      while ((n = input.read(buffer)) != -1) {
        total += n;
        StrictJson.require(total <= size, "资源展开超限");
        output.write(buffer, 0, n);
      }
      StrictJson.require(total == size, "资源展开不完整");
      output.getFD().sync();
    }
  }

  private static void validateNames(ZipContainer zip) {
    Map<String, Boolean> kinds = new HashMap<>();
    Map<String, String> spellings = new HashMap<>();
    for (ZipContainer.Entry entry : zip.entries.values()) {
      String name =
          entry.name.endsWith("/") ? entry.name.substring(0, entry.name.length() - 1) : entry.name;
      StrictJson.require(
          !name.isEmpty()
              && name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 512
              && !name.startsWith("/"),
          "资源路径无效");
      for (char c : name.toCharArray())
        StrictJson.require(
            !Character.isISOControl(c) && "\\:*?<>|\"".indexOf(c) < 0, "资源路径包含不安全字符");
      String prefix = "";
      for (String part : name.split("/", -1)) {
        StrictJson.require(
            !part.isEmpty()
                && !part.equals(".")
                && !part.equals("..")
                && !part.endsWith(".")
                && !part.endsWith(" "),
            "资源路径包含逃逸或歧义段");
        String device = part.split("\\.", 2)[0].toUpperCase(java.util.Locale.ROOT);
        StrictJson.require(!device.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"), "资源路径使用系统保留名称");
        prefix = prefix.isEmpty() ? part : prefix + "/" + part;
        String lower = lower(prefix);
        String previous = spellings.put(lower, prefix);
        StrictJson.require(previous == null || previous.equals(prefix), "资源目录大小写冲突");
      }
      StrictJson.require(kinds.put(lower(name), entry.directory) == null, "资源名称重复或大小写冲突");
    }
    for (String name : kinds.keySet()) {
      int end = name.lastIndexOf('/');
      while (end >= 0) {
        String parent = name.substring(0, end);
        StrictJson.require(!Boolean.FALSE.equals(kinds.get(parent)), "资源文件被当作目录使用");
        end = parent.lastIndexOf('/');
      }
    }
  }

  private static String lower(String name) {
    StringBuilder result = new StringBuilder();
    name.codePoints().forEach(c -> result.appendCodePoint(Character.toLowerCase(c)));
    return result.toString();
  }

  private static void removeOwnStaging(Path path) throws Exception {
    if (!Files.exists(path)) return;
    Files.walkFileTree(
        path,
        new java.nio.file.SimpleFileVisitor<Path>() {
          @Override
          public java.nio.file.FileVisitResult visitFile(
              Path file, java.nio.file.attribute.BasicFileAttributes attributes)
              throws java.io.IOException {
            file.toFile().setWritable(true, true);
            Files.delete(file);
            return java.nio.file.FileVisitResult.CONTINUE;
          }

          @Override
          public java.nio.file.FileVisitResult postVisitDirectory(
              Path directory, java.io.IOException failure) throws java.io.IOException {
            if (failure != null) throw failure;
            Files.delete(directory);
            return java.nio.file.FileVisitResult.CONTINUE;
          }
        });
  }
}
