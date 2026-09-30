package app.luoxianlv.buildlogic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;

/** 用实际 javac、D8、AAPT2 重建小型公开测试 APK；不安装或执行其中代码。 */
public final class NativeFixtureCompiler {
  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("参数：SDK目录 平台编号 输出目录");
    Path sdk = Path.of(args[0]), output = Path.of(args[2]);
    Path tools = sdk.resolve("build-tools/36.1.0"), platform = sdk.resolve("platforms/android-" + args[1] + "/android.jar");
    String aapt = tools.resolve(System.getProperty("os.name").startsWith("Windows") ? "aapt2.exe" : "aapt2").toString();
    Files.createDirectories(output);
    compile(output, tools, platform, aapt, "host-base", 0x80,
        "app.luoxianlv.hot.contract.Entry", "public int sum(int value) { return value + 1; }", "");
    compile(output, tools, platform, aapt, "host-body", 0x80,
        "app.luoxianlv.hot.contract.Entry", "public int sum(int value) { return value + 2; }", "");
    compile(output, tools, platform, aapt, "host-api", 0x80,
        "app.luoxianlv.hot.contract.Entry", "public long sum(int value) { return value + 1; }", "");
    compile(output, tools, platform, aapt, "runtime", 0x7f,
        "sample.Runtime", "public String 名称;", "fixture-runtime-v1");
    compile(output, tools, platform, aapt, "business", 0x81,
        "app.luoxianlv.business.AppBusinessFactory", "public boolean ready() { return true; }", "");
  }

  private static void compile(Path output, Path tools, Path platform, String aapt, String name,
      int packageId, String className, String members, String abi) throws Exception {
    Path work = Files.createTempDirectory("native-fixture-"), classes = work.resolve("classes"), dex = work.resolve("dex");
    Files.createDirectories(classes);
    Files.createDirectories(dex);
    int dot = className.lastIndexOf('.');
    Path source = work.resolve(className.substring(dot + 1) + ".java");
    Files.writeString(source, "package " + className.substring(0, dot) + "; public class "
        + className.substring(dot + 1) + " { " + members + " }", StandardCharsets.UTF_8);
    int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null,
        "-encoding", "UTF-8", "--release", "17", "-d", classes.toString(), source.toString());
    if (compiled != 0) throw new IllegalStateException("测试类编译失败");
    run(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", tools.resolve("lib/d8.jar").toString(), "com.android.tools.r8.D8", "--min-api", "26",
        "--lib", platform.toString(), "--output", dex.toString(), classes.resolve(className.replace('.', '/') + ".class").toString()));
    Path res = work.resolve("res/values");
    Files.createDirectories(res);
    Files.writeString(res.resolve("strings.xml"), "<resources><string name=\"app_name\">测试</string></resources>", StandardCharsets.UTF_8);
    Path manifest = work.resolve("AndroidManifest.xml");
    Files.writeString(manifest, "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"sample.test\"><uses-sdk android:minSdkVersion=\"26\"/><application android:label=\"@string/app_name\"/></manifest>", StandardCharsets.UTF_8);
    Path resourceZip = work.resolve("compiled-res.zip"), resourceApk = work.resolve("resources.apk");
    run(List.of(aapt, "compile", "--dir", work.resolve("res").toString(), "-o", resourceZip.toString()));
    run(List.of(aapt, "link", "-I", platform.toString(), "--package-id", "0x" + Integer.toHexString(packageId),
        "--manifest", manifest.toString(), "-o", resourceApk.toString(), resourceZip.toString()));
    var entries = new TreeMap<String, byte[]>();
    try (var zip = new ZipFile(resourceApk.toFile())) {
      var sourceEntries = zip.entries();
      while (sourceEntries.hasMoreElements()) {
        var entry = sourceEntries.nextElement();
        try (var input = zip.getInputStream(entry)) { entries.put(entry.getName(), input.readAllBytes()); }
      }
    }
    entries.put("classes.dex", Files.readAllBytes(dex.resolve("classes.dex")));
    if (!abi.isEmpty()) entries.put("assets/runtime-abi.txt", (abi + "\n").getBytes(StandardCharsets.UTF_8));
    try (var zip = new ZipOutputStream(Files.newOutputStream(output.resolve(name + ".apk")))) {
      for (var entry : entries.entrySet()) {
        var target = new ZipEntry(entry.getKey());
        target.setTime(0);
        zip.putNextEntry(target);
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
  }

  private static void run(List<String> command) throws Exception {
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) throw new IllegalStateException("测试编译工具失败：" + output);
  }
}
