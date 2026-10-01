package app.luoxianlv.buildlogic;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** 从 AGP 的公开 APK 产物目录导出完整模块，不猜测构建类型的文件名。 */
public abstract class ExportNativeApk extends DefaultTask {
  @InputDirectory
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract DirectoryProperty getApkDirectory();

  @OutputFile
  public abstract RegularFileProperty getApk();

  @TaskAction
  public void export() throws Exception {
    File[] files =
        getApkDirectory().get().getAsFile().listFiles((dir, name) -> name.endsWith(".apk"));
    if (files == null || files.length != 1) throw new IllegalStateException("原生模块必须产出一个完整 APK");
    var output = getApk().get().getAsFile().toPath();
    Files.createDirectories(output.getParent());
    Files.copy(files[0].toPath(), output, StandardCopyOption.REPLACE_EXISTING);
  }
}
