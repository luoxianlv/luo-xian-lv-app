package app.luoxianlv.buildlogic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.*;

/** 共用原始资源；链接时给外部主题/属性加运行时包名，避免生成另一套冲突的资源 ID。 */
public abstract class LinkBusinessResources extends DefaultTask {
  @InputDirectory
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract DirectoryProperty getSource();

  @InputFile
  @PathSensitive(PathSensitivity.NONE)
  public abstract RegularFileProperty getSymbols();

  @OutputDirectory
  public abstract DirectoryProperty getOutput();

  @TaskAction
  public void link() throws Exception {
    Set<String> runtime = new HashSet<>();
    for (String line :
        Files.readAllLines(getSymbols().get().getAsFile().toPath(), StandardCharsets.UTF_8)) {
      String[] parts = line.trim().split("\\s+");
      if (parts.length >= 3 && parts[0].equals("int")) runtime.add(parts[1] + "/" + parts[2]);
    }
    ResourceLinker linker = new ResourceLinker(runtime);
    var documents = new java.util.LinkedHashMap<Path, org.w3c.dom.Document>();
    Path source = getSource().get().getAsFile().toPath();
    try (var files = Files.walk(source)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        String directory = source.relativize(file).getName(0).toString().split("-")[0];
        if (file.toString().endsWith(".xml")) {
          var document = ResourceLinker.parse(Files.readString(file, StandardCharsets.UTF_8));
          documents.put(file, document);
          linker.definitions(document);
        }
        if (!directory.equals("values")) {
          String name = file.getFileName().toString().replaceFirst("(?:\\.9)?\\.[^.]+$", "");
          linker.file(directory, name);
        }
      }
    }
    Path output = getOutput().get().getAsFile().toPath().toAbsolutePath().normalize();
    Path build =
        getProject()
            .getLayout()
            .getBuildDirectory()
            .get()
            .getAsFile()
            .toPath()
            .toAbsolutePath()
            .normalize();
    if (!output.startsWith(build) || output.equals(build) || Files.isSymbolicLink(output))
      throw new IllegalStateException("生成资源必须位于本模块 build 子目录");
    if (Files.exists(output)) {
      try (var old = Files.walk(output)) {
        for (Path file : old.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
      }
    }
    try (var files = Files.walk(source)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        Path target = output.resolve(source.relativize(file));
        Files.createDirectories(target.getParent());
        if (!file.toString().endsWith(".xml")) {
          Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
          continue;
        }
        Files.writeString(target, linker.link(documents.get(file)), StandardCharsets.UTF_8);
      }
    }
  }
}
