package app.luoxianlv.buildlogic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;

/** 当前最终 APK 的可追溯报告；只准备产物，不签名、上传或发布。 */
public abstract class ExportNativeBuildReport extends DefaultTask {
  @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
  public abstract DirectoryProperty getApkDirectory();
  @Input public abstract Property<String> getRole();
  @Input public abstract Property<String> getVariantName();
  @Input public abstract Property<String> getApplicationId();
  @Input public abstract Property<Integer> getPackageId();
  @Input public abstract Property<Boolean> getR8();
  @Input public abstract Property<Boolean> getResourceShrink();
  @Input public abstract Property<String> getCommit();
  @Input public abstract Property<Boolean> getSourceDirty();
  @Input public abstract Property<String> getToolchain();
  @Optional @Input public abstract Property<String> getEntryClass();
  @Optional @InputFile @PathSensitive(PathSensitivity.NONE)
  public abstract RegularFileProperty getSdk();
  @Optional @InputFile @PathSensitive(PathSensitivity.NONE)
  public abstract RegularFileProperty getMapping();
  @Optional @InputFile @PathSensitive(PathSensitivity.NONE)
  public abstract RegularFileProperty getRuntimeApk();
  @Optional @InputFile @PathSensitive(PathSensitivity.NONE)
  public abstract RegularFileProperty getBusinessApk();
  @Optional @InputFile @PathSensitive(PathSensitivity.NONE)
  public abstract RegularFileProperty getRuntimeReport();
  @Optional @InputFile @PathSensitive(PathSensitivity.NONE)
  public abstract RegularFileProperty getBusinessReport();
  @Internal public abstract MapProperty<String, String> getDependencyPaths();
  @Input public abstract ListProperty<String> getDependencyIdentities();
  @InputFiles @PathSensitive(PathSensitivity.NONE)
  public abstract ConfigurableFileCollection getDependencyFiles();
  @OutputDirectory public abstract DirectoryProperty getOutput();

  @TaskAction public void export() throws Exception {
    Path output = getOutput().get().getAsFile().toPath().toAbsolutePath().normalize();
    Path build = getProject().getLayout().getBuildDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
    if (!output.startsWith(build) || output.equals(build) || Files.isSymbolicLink(output))
      throw new IllegalStateException("原生报告必须位于本模块 build 子目录");
    // 验证失败后不留下看起来仍可用的上一轮报告。
    Files.deleteIfExists(output.resolve("report.json"));
    Files.deleteIfExists(output.resolve("sdk-contract.json"));
    Files.deleteIfExists(output.resolve("exports.txt"));
    Files.deleteIfExists(output.resolve("classes.txt"));
    Files.deleteIfExists(output.resolve("mapping.txt"));
    var apks = getApkDirectory().get().getAsFile().listFiles((dir, name) -> name.endsWith(".apk"));
    if (apks == null || apks.length != 1) throw new IllegalStateException("原生报告要求唯一完整 APK");
    var own = NativeApkContents.read(apks[0].toPath());
    String role = getRole().get();
    if (!Set.of("host", "runtime", "business").contains(role)) throw new IllegalStateException("原生角色无效");
    if (role.equals("business") && !own.nativeLibraries.isEmpty())
      throw new IllegalStateException("业务 APK 不允许携带进程级原生库，应放入共享运行时");
    if (!own.packageIds.equals(Set.of(getPackageId().get())))
      throw new IllegalStateException("实际 APK 资源编号与三层约定不符");
    String commit = getCommit().get();
    if (!commit.matches("[a-f0-9]{40}|[a-f0-9]{64}")) throw new IllegalStateException("缺少实际源码提交身份");
    String abi = own.runtimeAbi;
    var related = new ArrayList<RawJson>();
    if (!role.equals("runtime")) {
      var runtime = NativeApkContents.read(getRuntimeApk().get().getAsFile().toPath());
      if (runtime.runtimeAbi.isEmpty() || !runtime.packageIds.equals(Set.of(0x7f)))
        throw new IllegalStateException("关联运行时 ABI 或资源编号无效");
      rejectOverlap(own, runtime);
      abi = runtime.runtimeAbi;
      related.add(readReport(getRuntimeReport()));
      if (role.equals("host")) {
        var business = NativeApkContents.read(getBusinessApk().get().getAsFile().toPath());
        if (!business.packageIds.equals(Set.of(0x81))) throw new IllegalStateException("业务资源编号无效");
        rejectOverlap(own, business);
        rejectOverlap(runtime, business);
        if (!business.classes.contains("Lapp/luoxianlv/business/AppBusinessFactory;"))
          throw new IllegalStateException("业务缺少真实核心入口");
        related.add(readReport(getBusinessReport()));
      }
    }
    if (abi.isEmpty()) throw new IllegalStateException("缺少实际运行时 ABI 标记");
    String entry = getEntryClass().getOrElse("");
    if (!entry.isEmpty() && !own.classes.contains("L" + entry.replace('.', '/') + ";"))
      throw new IllegalStateException("实际业务 DEX 缺少入口类");
    Set<String> exports = own.exports(role.equals("host") ? "Lapp/luoxianlv/hot/contract/" : "L");
    if (exports.isEmpty()) throw new IllegalStateException("APK 缺少实际导出接口");
    var dependencies = new TreeMap<String, String>();
    var dependencyObjects = new TreeMap<String, Object>();
    Files.createDirectories(output.resolve("dependencies"));
    for (var dependency : getDependencyPaths().get().entrySet()) {
      Path source = Path.of(dependency.getValue());
      String hash = NativeApkContents.hash(source);
      dependencies.put(dependency.getKey(), hash);
      if (!dependencyObjects.containsKey(hash)) {
        String relative = "dependencies/" + hash + ".jar";
        Files.copy(source, output.resolve(relative), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        if (!NativeApkContents.hash(output.resolve(relative)).equals(hash))
          throw new IllegalStateException("依赖在冻结期间发生变化");
        dependencyObjects.put(hash, Map.of("path", relative, "sha256", hash,
            "size", Files.size(output.resolve(relative))));
      }
    }
    String mapping = mappingHash(getR8().get(), getMapping().isPresent() ? getMapping().get().getAsFile().toPath() : null);
    if (!mapping.isEmpty()) {
      Files.copy(getMapping().get().getAsFile().toPath(), output.resolve("mapping.txt"),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      if (!NativeApkContents.hash(output.resolve("mapping.txt")).equals(mapping))
        throw new IllegalStateException("mapping 在冻结期间发生变化");
    }
    String sdkHash = "";
    if (getSdk().isPresent()) {
      Path sdk = getSdk().get().getAsFile().toPath();
      sdkHash = NativeApkContents.hash(sdk);
      try (var zip = new ZipFile(sdk.toFile())) {
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
          String name = entries.nextElement().getName();
          if (name.endsWith(".class") && !own.classes.contains("L" + name.substring(0, name.length() - 6) + ";"))
            throw new IllegalStateException("SDK 编译类型不在实际 APK 中: " + name);
        }
      }
    }
    var report = new TreeMap<String, Object>();
    report.put("schema", 1);
    report.put("role", role);
    report.put("variant", getVariantName().get());
    report.put("applicationId", getApplicationId().get());
    report.put("apk", Map.of("sha256", own.sha256, "size", own.size));
    report.put("runtimeAbi", abi);
    report.put("entryClass", entry);
    report.put("resourcePackageIds", own.packageIds);
    report.put("resourceFingerprint", own.resourceFingerprint);
    report.put("nativeAbis", own.nativeAbis);
    report.put("nativeLibraries", own.nativeLibraries);
    report.put("classCount", own.classes.size());
    report.put("classFingerprint", NativeApkContents.fingerprint(own.classes));
    report.put("exportFingerprint", NativeApkContents.fingerprint(exports));
    report.put("exportAlgorithm", "dex-public-protected-signatures-v1");
    report.put("dependencies", dependencies);
    report.put("dependencyObjects", dependencyObjects);
    if (!mapping.isEmpty()) report.put("mappingObject", Map.of("path", "mapping.txt", "sha256", mapping,
        "size", Files.size(output.resolve("mapping.txt"))));
    report.put("dependencyFingerprint", NativeApkContents.fingerprint(dependencies.entrySet()));
    report.put("requires", role.equals("business") ? List.of("runtime") : List.of());
    report.put("compileContracts", role.equals("business") ? List.of("host-contract-sdk", "runtime-sdk") : List.of());
    report.put("build", Map.of("commit", commit, "sourceDirty", getSourceDirty().get(),
        "toolchain", getToolchain().get(), "r8", getR8().get(),
        "resourceShrink", getResourceShrink().get(), "mappingHash", mapping));
    report.put("sdkJarHash", sdkHash);
    report.put("related", related);
    report.put("verification", "compiled-artifact-only");
    Files.createDirectories(output);
    Files.writeString(output.resolve("classes.txt"), String.join("\n", own.classes) + "\n", StandardCharsets.UTF_8);
    Files.writeString(output.resolve("exports.txt"), String.join("\n", exports) + "\n", StandardCharsets.UTF_8);
    var sdkContract = Map.of("schema", 1, "role", role, "runtimeAbi", abi,
        "apkHash", own.sha256, "sdkJarHash", sdkHash,
        "exportFingerprint", NativeApkContents.fingerprint(exports), "exports", exports);
    Files.writeString(output.resolve("sdk-contract.json"), json(sdkContract) + "\n", StandardCharsets.UTF_8);
    Files.writeString(output.resolve("report.json"), json(report) + "\n", StandardCharsets.UTF_8);
  }

  static String mappingHash(boolean r8, Path currentMapping) throws Exception {
    if (!r8) {
      if (currentMapping != null) throw new IllegalStateException("R8 关闭时不得绑定残留 mapping");
      return "";
    }
    if (currentMapping == null || !Files.isRegularFile(currentMapping) || Files.size(currentMapping) == 0)
      throw new IllegalStateException("R8 开启但缺少当前 AGP 变体 mapping 产物");
    return NativeApkContents.hash(currentMapping);
  }

  static void rejectOverlap(NativeApkContents left, NativeApkContents right) {
    var duplicates = new TreeSet<>(left.classes);
    duplicates.retainAll(right.classes);
    duplicates.removeIf(type -> left.toolAnnotations.containsKey(type)
        && left.toolAnnotations.get(type).equals(right.toolAnnotations.get(type)));
    if (!duplicates.isEmpty()) throw new IllegalStateException("三层 APK 重复定义共享类型: " + duplicates.first());
  }

  private static RawJson readReport(RegularFileProperty property) throws Exception {
    Path file = property.get().getAsFile().toPath();
    if (Files.size(file) > 16 * 1024 * 1024) throw new IllegalStateException("关联构建报告超限");
    return new RawJson(Files.readString(file, StandardCharsets.UTF_8).trim());
  }

  private record RawJson(String value) {}

  static String json(Object value) {
    if (value instanceof RawJson raw) return raw.value;
    if (value instanceof String text) {
      var escaped = new StringBuilder("\"");
      for (char letter : text.toCharArray()) {
        switch (letter) {
          case '"' -> escaped.append("\\\"");
          case '\\' -> escaped.append("\\\\");
          case '\n' -> escaped.append("\\n");
          case '\r' -> escaped.append("\\r");
          case '\t' -> escaped.append("\\t");
          default -> { if (letter < 32) escaped.append(String.format("\\u%04x", (int) letter)); else escaped.append(letter); }
        }
      }
      return escaped.append('"').toString();
    }
    if (value instanceof Map<?, ?> map) {
      var sorted = new TreeMap<String, Object>();
      map.forEach((key, item) -> sorted.put(key.toString(), item));
      var parts = new ArrayList<String>();
      sorted.forEach((key, item) -> parts.add(json(key) + ":" + json(item)));
      return "{" + String.join(",", parts) + "}";
    }
    if (value instanceof Iterable<?> list) {
      var parts = new ArrayList<String>();
      list.forEach(item -> parts.add(json(item)));
      return "[" + String.join(",", parts) + "]";
    }
    if (value instanceof Number || value instanceof Boolean) return value.toString();
    throw new IllegalArgumentException("构建报告 JSON 类型无效");
  }
}
