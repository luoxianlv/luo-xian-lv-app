package app.luoxianlv.buildlogic;

import app.luoxianlv.hot.HostConfigRules;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Input;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

/** 显式打包公钥配置；不从源码外的默认路径猜测密钥或部署地址。 */
public abstract class CopyHotConfig extends DefaultTask {
  @InputFile
  public abstract RegularFileProperty getConfig();

  @Input public abstract Property<String> getApplicationId();
  @Input public abstract Property<Boolean> getDebuggable();

  @OutputDirectory
  public abstract DirectoryProperty getOutput();

  @TaskAction
  public void copy() throws Exception {
    var source = getConfig().get().getAsFile().toPath();
    var target = getOutput().file("hot/config.json").get().getAsFile().toPath();
    validateAndCopy(source, target, getApplicationId().get(), getDebuggable().get());
  }

  static void validateAndCopy(Path source, Path target, String applicationId, boolean debuggable)
      throws Exception {
    if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())
        || (Files.exists(source) && Files.exists(target) && Files.isSameFile(source, target)))
      throw new IllegalArgumentException("热更配置输入不能使用生成资产自身");
    Files.deleteIfExists(target);
    if (!Files.isRegularFile(source) || Files.size(source) > HostConfigRules.MAX_BYTES)
      throw new IllegalArgumentException("热更公钥配置类型或大小无效");
    byte[] raw;
    try (var input = Files.newInputStream(source)) {
      raw = input.readNBytes(HostConfigRules.MAX_BYTES + 1);
    }
    try {
      HostConfigRules.validate(raw, applicationId, debuggable);
    } catch (Exception invalid) {
      // 不附原始异常/配置内容，URI、公钥及未知字段可能含用户误填的秘密。
      throw new IllegalArgumentException("热更配置预检失败，请核对实际变体包名、源站和根公钥格式");
    }
    Files.createDirectories(target.getParent());
    Path temporary = Files.createTempFile(target.getParent(), "hot-config-", ".tmp");
    try {
      Files.write(temporary, raw);
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
