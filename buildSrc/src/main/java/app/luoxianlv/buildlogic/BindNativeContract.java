package app.luoxianlv.buildlogic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

/** 将本次实际编译的宿主 SDK 身份绑定到 APK；禁止靠手填契约版本猜测兼容性。 */
public abstract class BindNativeContract extends DefaultTask {
  @InputFile
  public abstract RegularFileProperty getSdk();

  @OutputDirectory
  public abstract DirectoryProperty getOutput();

  @TaskAction
  public void bind() throws Exception {
    var source = getSdk().get().getAsFile().toPath();
    if (Files.size(source) <= 0 || Files.size(source) > 64L * 1024 * 1024)
      throw new IllegalArgumentException("宿主契约 SDK 大小无效");
    var digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(source)) {
      byte[] buffer = new byte[32768];
      int count;
      while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
    }
    var target = getOutput().file("hot/host-contract.sha256").get().getAsFile().toPath();
    Files.createDirectories(target.getParent());
    Files.writeString(
        target, HexFormat.of().formatHex(digest.digest()) + "\n", StandardCharsets.UTF_8);
  }
}
