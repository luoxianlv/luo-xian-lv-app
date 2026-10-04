package app.luoxianlv.update;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 调用方须持有普通更新 transaction.lock；只清理本库生成的摘要任务目录。 */
final class ApkCacheCleaner {
  private ApkCacheCleaner() {}

  static void collect(File root, String current, String previousActive) throws IOException {
    SafeFiles.directory(root);
    File[] directories = root.listFiles();
    if (directories == null) throw new IOException("无法读取更新缓存目录");
    List<File> historicalComplete = new ArrayList<>(), removable = new ArrayList<>();
    Set<String> keep = new HashSet<>();
    keep.add(current);
    if (previousActive != null && previousActive.matches("[0-9a-f]{64}")) keep.add(previousActive);
    for (File directory : directories) {
      if (!directory.getName().matches("[0-9a-f]{64}")
          || Files.isSymbolicLink(directory.toPath())
          || !directory.isDirectory()) continue;
      File finalApk = new File(directory, "verified.apk");
      if (!directory.getName().equals(current)
          && finalApk.isFile()
          && !Files.isSymbolicLink(finalApk.toPath())) historicalComplete.add(directory);
      removable.add(directory);
    }
    historicalComplete.sort(
        Comparator.comparingLong((File file) -> new File(file, "verified.apk").lastModified())
            .reversed());
    int retainedHistory = 0;
    for (File historical : historicalComplete)
      if (keep.contains(historical.getName())) retainedHistory++;
    for (File historical : historicalComplete) {
      if (retainedHistory >= 2) break;
      if (keep.add(historical.getName())) retainedHistory++;
    }
    for (File directory : removable)
      if (!keep.contains(directory.getName())) deleteTask(root, directory);
  }

  private static void deleteTask(File root, File directory) throws IOException {
    Path boundary = root.getCanonicalFile().toPath();
    Path task = directory.toPath().toAbsolutePath().normalize();
    if (!directory.getName().matches("[0-9a-f]{64}")
        || !task.getParent().equals(boundary)
        || Files.isSymbolicLink(task)
        || !task.equals(directory.getCanonicalFile().toPath())) throw new IOException("更新缓存清理路径无效");
    Files.walkFileTree(
        task,
        new SimpleFileVisitor<Path>() {
          private void inside(Path file) throws IOException {
            if (!file.toAbsolutePath().normalize().startsWith(task))
              throw new IOException("更新缓存清理超出任务目录");
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            inside(file);
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException failure)
              throws IOException {
            if (failure != null) throw failure;
            inside(dir);
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
