package app.luoxianlv.buildlogic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** 确定性 SDK 编译 JAR；仅从 AGP 当前变体的编译类读取，不复制到业务 DEX。 */
public final class CompileSdk {
  private CompileSdk() {}

  public static void write(List<Path> jars, List<Path> directories, Path output) throws Exception {
    write(jars, directories, List.of(), output);
  }

  public static void write(List<Path> jars, List<Path> directories, List<Path> metadataSources, Path output) throws Exception {
    Map<String, byte[]> entries = new TreeMap<>();
    for (Path jar : jars) {
      try (var zip = new ZipFile(jar.toFile())) {
        var source = zip.entries();
        while (source.hasMoreElements()) {
          var entry = source.nextElement();
          if (!accepted(entry.getName())) continue;
          try (var input = zip.getInputStream(entry)) {
            add(entries, entry.getName(), input.readNBytes(16 * 1024 * 1024 + 1));
          }
        }
      }
    }
    for (Path directory : directories) {
      try (var files = Files.walk(directory)) {
        for (Path file : files.filter(Files::isRegularFile).toList()) {
          String name = directory.relativize(file).toString().replace('\\', '/');
          if (accepted(name)) {
            if (Files.size(file) > 16 * 1024 * 1024) throw new IOException("SDK 条目超限");
            add(entries, name, Files.readAllBytes(file));
          }
        }
      }
    }
    // AGP 将 Android AAR 的 Kotlin module metadata 放在 Java resources，与 CLASSES 分开。
    for (Path metadata : metadataSources) {
      if (Files.isDirectory(metadata)) {
        try (var files = Files.walk(metadata)) {
          for (Path file : files.filter(Files::isRegularFile).toList()) {
            String name = metadata.relativize(file).toString().replace('\\', '/');
            if (moduleMetadata(name)) {
              if (Files.size(file) > 16 * 1024 * 1024) throw new IOException("SDK 元数据条目超限");
              add(entries, name, Files.readAllBytes(file));
            }
          }
        }
      } else {
        try (var zip = new ZipFile(metadata.toFile())) {
          var source = zip.entries();
          while (source.hasMoreElements()) {
            var entry = source.nextElement();
            if (!moduleMetadata(entry.getName())) continue;
            try (var input = zip.getInputStream(entry)) {
              add(entries, entry.getName(), input.readNBytes(16 * 1024 * 1024 + 1));
            }
          }
        }
      }
    }
    if (entries.keySet().stream().noneMatch(name -> name.endsWith(".class")))
      throw new IOException("SDK 缺少真实编译类");
    Files.createDirectories(output.toAbsolutePath().getParent());
    Path temporary = Files.createTempFile(output.toAbsolutePath().getParent(), "sdk-", ".tmp");
    try {
      try (var zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
        for (var entry : entries.entrySet()) {
          var target = new ZipEntry(entry.getKey());
          target.setTime(0);
          zip.putNextEntry(target);
          zip.write(entry.getValue());
          zip.closeEntry();
        }
      }
      Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static boolean accepted(String name) {
    return !name.startsWith("META-INF/versions/")
        && !name.equals("module-info.class")
        && (name.endsWith(".class") || moduleMetadata(name));
  }

  private static boolean moduleMetadata(String name) {
    return name.endsWith(".kotlin_module") || name.endsWith(".kotlin_builtins");
  }

  private static void add(Map<String, byte[]> entries, String name, byte[] bytes) throws IOException {
    if (name.startsWith("/") || name.contains("..") || name.contains("\\") || bytes.length > 16 * 1024 * 1024)
      throw new IOException("SDK 条目路径或大小无效");
    byte[] prior = entries.putIfAbsent(name, bytes);
    if (prior != null && !Arrays.equals(prior, bytes))
      throw new IOException((moduleMetadata(name) ? "SDK Kotlin 模块元数据冲突: " : "SDK 重复类型定义: ") + name);
  }
}
