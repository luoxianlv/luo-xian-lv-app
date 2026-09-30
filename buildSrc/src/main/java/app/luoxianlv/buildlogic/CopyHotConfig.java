package app.luoxianlv.buildlogic;

import java.nio.file.Files;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

/** 显式打包公钥配置；不从源码外的默认路径猜测密钥或部署地址。 */
public abstract class CopyHotConfig extends DefaultTask {
  @InputFile
  public abstract RegularFileProperty getConfig();

  @OutputDirectory
  public abstract DirectoryProperty getOutput();

  @TaskAction
  public void copy() throws Exception {
    var source = getConfig().get().getAsFile().toPath();
    if (Files.size(source) > 65536) throw new IllegalArgumentException("热更公钥配置超限");
    var target = getOutput().file("hot/config.json").get().getAsFile().toPath();
    Files.createDirectories(target.getParent());
    Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }
}
