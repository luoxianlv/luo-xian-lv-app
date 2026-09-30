package app.luoxianlv.buildlogic;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;
import org.junit.Test;

public final class NativeBuildToolsTest {
  @Test public void finalDexApiChangesAreDistinctFromImplementationChanges() throws Exception {
    var base = NativeApkContents.read(fixture("host-base"));
    var body = NativeApkContents.read(fixture("host-body"));
    var api = NativeApkContents.read(fixture("host-api"));
    assertEquals(Set.of(0x80), base.packageIds);
    assertTrue(base.exports.contains("M|Lapp/luoxianlv/hot/contract/Entry;|1|sum(I)I"));
    assertNotEquals(base.sha256, body.sha256);
    assertEquals(NativeApkContents.fingerprint(base.exports), NativeApkContents.fingerprint(body.exports));
    assertNotEquals(NativeApkContents.fingerprint(base.exports), NativeApkContents.fingerprint(api.exports));
    assertEquals(base.resourceFingerprint, body.resourceFingerprint);
  }

  @Test public void readsActualRuntimeMarkerResourceSpaceAndUnicodeMember() throws Exception {
    var runtime = NativeApkContents.read(fixture("runtime"));
    var business = NativeApkContents.read(fixture("business"));
    assertEquals(Set.of(0x7f), runtime.packageIds);
    assertEquals(Set.of(0x81), business.packageIds);
    assertEquals("fixture-runtime-v1", runtime.runtimeAbi);
    assertTrue(runtime.exports.stream().anyMatch(line -> line.endsWith("名称:Ljava/lang/String;")));
    assertTrue(business.classes.contains("Lapp/luoxianlv/business/AppBusinessFactory;"));
  }

  @Test public void mappingOnlyComesFromExplicitCurrentAgpArtifact() throws Exception {
    Path stale = Files.createTempFile("mapping-stale-", ".txt"), current = Files.createTempFile("mapping-current-", ".txt");
    Files.writeString(stale, "旧产物 mapping", StandardCharsets.UTF_8);
    Files.writeString(current, "当前产物 mapping", StandardCharsets.UTF_8);
    assertEquals("", ExportNativeBuildReport.mappingHash(false, null));
    assertThrows(IllegalStateException.class, () -> ExportNativeBuildReport.mappingHash(false, stale));
    assertThrows(IllegalStateException.class, () -> ExportNativeBuildReport.mappingHash(true, null));
    assertEquals(NativeApkContents.hash(current), ExportNativeBuildReport.mappingHash(true, current));
    assertNotEquals(NativeApkContents.hash(stale), ExportNativeBuildReport.mappingHash(true, current));
  }

  @Test public void finalArtifactOverlapIsRejectedAcrossRoles() throws Exception {
    var host = NativeApkContents.read(fixture("host-base"));
    var runtime = NativeApkContents.read(fixture("runtime"));
    ExportNativeBuildReport.rejectOverlap(host, runtime);
    assertThrows(IllegalStateException.class,
        () -> ExportNativeBuildReport.rejectOverlap(host, NativeApkContents.read(fixture("host-body"))));
  }

  @Test public void rejectsDuplicateTypesAcrossDexFilesAndMalformedResourceChunks() throws Exception {
    Path original = fixture("host-base"), duplicate = Files.createTempFile("duplicate-dex-", ".apk"), broken = Files.createTempFile("broken-res-", ".apk");
    try (var source = new ZipFile(original.toFile());
        var repeated = new ZipOutputStream(Files.newOutputStream(duplicate));
        var malformed = new ZipOutputStream(Files.newOutputStream(broken))) {
      var entries = source.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        byte[] bytes;
        try (var input = source.getInputStream(entry)) { bytes = input.readAllBytes(); }
        add(repeated, entry.getName(), bytes);
        if (entry.getName().equals("classes.dex")) add(repeated, "classes2.dex", bytes);
        if (entry.getName().equals("resources.arsc")) bytes[4] = 0;
        add(malformed, entry.getName(), bytes);
      }
    }
    assertThrows(Exception.class, () -> NativeApkContents.read(duplicate));
    assertThrows(Exception.class, () -> NativeApkContents.read(broken));
  }

  @Test public void exportedSdkCompilesConsumerAndIsDeterministicAcrossInputOrder() throws Exception {
    Path work = Files.createTempDirectory("compile-sdk-"), first = work.resolve("first"), second = work.resolve("second");
    Files.createDirectories(first);
    Files.createDirectories(second);
    Path shared = work.resolve("Shared.java"), other = work.resolve("Other.java"), consumer = work.resolve("Consumer.java");
    Files.writeString(shared, "package sample; public class Shared { public static long value() { return 1; } }", StandardCharsets.UTF_8);
    Files.writeString(other, "package sample; public class Other {}", StandardCharsets.UTF_8);
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", first.toString(), shared.toString()));
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", second.toString(), other.toString()));
    Path sdk = work.resolve("sdk.jar"), reverse = work.resolve("reverse.jar");
    CompileSdk.write(List.of(), List.of(first, second), sdk);
    CompileSdk.write(List.of(), List.of(second, first), reverse);
    assertEquals(NativeApkContents.hash(sdk), NativeApkContents.hash(reverse));
    Files.writeString(consumer, "class Consumer { long result() { return sample.Shared.value(); } }", StandardCharsets.UTF_8);
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
        "-cp", sdk.toString(), "-d", work.resolve("consumer").toString(), consumer.toString()));
    // 与第一次同名类型不同的真实编译类必须拒绝合并。
    Files.writeString(shared, "package sample; public class Shared { public static String value() { return \"x\"; } }", StandardCharsets.UTF_8);
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", second.toString(), shared.toString()));
    assertThrows(Exception.class, () -> CompileSdk.write(List.of(), List.of(first, second), work.resolve("duplicate.jar")));
  }

  private static Path fixture(String name) throws Exception {
    Path file = Files.createTempFile("native-fixture-", ".apk");
    try (var input = NativeBuildToolsTest.class.getResourceAsStream("/native/" + name + ".apk")) {
      assertNotNull("真实编译 APK 测试资料缺失", input);
      Files.copy(input, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    return file;
  }
  private static void add(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(bytes);
    zip.closeEntry();
  }
}
