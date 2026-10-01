package app.luoxianlv.buildlogic;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.*;

/** APK 内置恢复模块及校验清单一起生成，完整内容由最终宿主 APK 签名保护。 */
public abstract class BundleBaseline extends DefaultTask {
  @InputFile
  @PathSensitive(PathSensitivity.NONE)
  public abstract RegularFileProperty getRuntimeApk();

  @InputFile
  @PathSensitive(PathSensitivity.NONE)
  public abstract RegularFileProperty getBusinessApk();

  @OutputDirectory
  public abstract DirectoryProperty getOutput();

  @TaskAction
  public void bundle() throws Exception {
    Path runtime = getRuntimeApk().get().getAsFile().toPath(),
        business = getBusinessApk().get().getAsFile().toPath();
    String abi;
    try (ZipFile zip = new ZipFile(runtime.toFile());
        InputStream marker = zip.getInputStream(zip.getEntry("assets/runtime-abi.txt"))) {
      abi = new String(marker.readAllBytes(), StandardCharsets.UTF_8).trim();
    }
    if (!abi.matches("[a-z][a-z0-9._-]{0,95}")) throw new IllegalArgumentException("运行时 ABI 无效");
    Path directory = getOutput().get().getAsFile().toPath().resolve("baseline");
    Files.createDirectories(directory);
    Files.copy(runtime, directory.resolve("runtime.apk"), StandardCopyOption.REPLACE_EXISTING);
    Files.copy(business, directory.resolve("business.apk"), StandardCopyOption.REPLACE_EXISTING);
    String json =
        "{\"schema\":1,\"runtimeAbi\":\""
            + abi
            + "\",\"entryClass\":\"app.luoxianlv.business.AppBusinessFactory\",\"runtime\":{\"sha256\":\""
            + hash(runtime)
            + "\",\"size\":"
            + Files.size(runtime)
            + "},\"business\":{\"sha256\":\""
            + hash(business)
            + "\",\"size\":"
            + Files.size(business)
            + "}}\n";
    Files.writeString(directory.resolve("index.json"), json, StandardCharsets.UTF_8);
  }

  private static String hash(Path file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = Files.newInputStream(file)) {
      byte[] buffer = new byte[65536];
      int n;
      while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
