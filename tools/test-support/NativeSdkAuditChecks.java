package app.luoxianlv.tools;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 独立真实 classfile + SDK 成员负向向量，不依赖 Gradle/JUnit/设备。 */
public final class NativeSdkAuditChecks {
  public static class SdkFixture implements Runnable {
    public int value;
    protected String protectedValue;
    public void bar(int value) {}
    protected void extension() {}
    public void run() {}
  }

  private static int checks;

  public static void main(String[] args) throws Exception {
    byte[] bytes;
    try (var input = SdkFixture.class.getResourceAsStream("NativeSdkAuditChecks$SdkFixture.class")) {
      bytes = input.readAllBytes();
    }
    var sdk = NativeSdkAudit.readClass(bytes);
    var actual = new TreeSet<>(sdk.exports());
    String owner = "Lapp/luoxianlv/tools/NativeSdkAuditChecks$SdkFixture;";
    String method = "M|" + owner + "|1|bar(I)V";
    String field = "F|" + owner + "|1|value:I";
    String protectedMethod = "M|" + owner + "|4|extension()V";
    String classLine = sdk.exports().stream().filter(line -> line.startsWith("C|")).findFirst().orElseThrow();
    require(actual.contains(method) && actual.contains(field), "Real classfile API not read");
    NativeSdkAudit.verify(sdk, sdk.classes(), actual);
    checks++;
    // 允许真实 D8 合成的额外公开符号，而不是要求 SDK 与 DEX 完全相等。
    actual.add("M|" + owner + "|1|syntheticAdded()V");
    NativeSdkAudit.verify(sdk, sdk.classes(), actual);
    checks++;
    reject(sdk, actual, method, null, "deleted method with class still present");
    reject(sdk, actual, method, method.replace("bar(", "a("), "renamed method with class still present");
    reject(sdk, actual, field, null, "deleted field");
    reject(sdk, actual, field, field.replace("|1|", "|9|"), "instance field changed to static");
    reject(sdk, actual, method, method.replace("|1|", "|9|"), "instance method changed to static");
    reject(sdk, actual, method, method.replace("|1|", "|4|"), "public method narrowed");
    reject(sdk, actual, classLine, classLine.replace("Ljava/lang/Object;", "Ljava/lang/String;"), "changed parent");
    reject(sdk, actual, classLine, classLine.replace("Ljava/lang/Runnable;", ""), "removed interface");
    reject(sdk, actual, classLine, classLine.replace("|33|", "|545|"), "changed class to interface");
    reject(sdk, actual, classLine, classLine.replace("|33|", "|49|"), "extendable class became final");
    reject(sdk, actual, classLine, classLine.replace("|33|", "|1057|"), "concrete class became abstract");
    reject(sdk, actual, method, method.replace("|1|", "|17|"), "overridable method became final");
    reject(sdk, actual, method, method.replace("|1|", "|1025|"), "concrete method became abstract");
    reject(sdk, actual, field, field.replace("|1|", "|17|"), "mutable field became final");
    var widened = new TreeSet<>(actual);
    widened.remove(protectedMethod);
    widened.add(protectedMethod.replace("|4|", "|1|"));
    NativeSdkAudit.verify(sdk, sdk.classes(), widened);
    checks++;
    var constructorFlags = new TreeSet<>(actual);
    constructorFlags.remove("M|" + owner + "|1|<init>()V");
    constructorFlags.add("M|" + owner + "|65537|<init>()V");
    NativeSdkAudit.verify(sdk, sdk.classes(), constructorFlags);
    checks++;
    expectReject(() -> NativeSdkAudit.verify(sdk, Set.of(), actual), "renamed/deleted SDK class");
    String name = owner.substring(1, owner.length() - 1).replace('/', '.');
    expectReject(() -> NativeSdkAudit.verifyMapping(sdk.classes(), List.of(name + " -> a.b:")), "SDK class renamed in mapping");
    NativeSdkAudit.verifyMapping(sdk.classes(), List.of(name + " -> " + name + ":", "    1:1:void bar(int):20:22 -> bar", "unrelated.Internal -> x.y:"));
    checks++;
    expectReject(() -> NativeSdkAudit.readClass("not-classfile".getBytes(StandardCharsets.UTF_8)), "bad classfile");
    var duplicates = new TreeSet<>(actual);
    duplicates.add(method.replace("|1|", "|9|"));
    expectReject(() -> NativeSdkAudit.verify(sdk, sdk.classes(), duplicates), "duplicate DEX identity");
    // 实际 R8 v2：即使父链仍实现某接口，也必须保持 SDK 的精确 direct interfaces。
    var hierarchySdk = new NativeSdkAudit.Api(Set.of("Ltest/Parent;", "Ltest/Child;"), Set.of(
        "C|Ltest/Parent;|1|Ljava/lang/Object;|Ljava/lang/Runnable;",
        "C|Ltest/Child;|1|Ltest/Parent;|Ljava/lang/Runnable;"));
    var removedRedundant = Set.of(
        "C|Ltest/Parent;|1|Ljava/lang/Object;|Ljava/lang/Runnable;",
        "C|Ltest/Child;|1|Ltest/Parent;|");
    expectReject(() -> NativeSdkAudit.verify(hierarchySdk, hierarchySdk.classes(), removedRedundant), "removed redundant direct interface");
    var multiChanged = new TreeSet<>(actual);
    multiChanged.remove(method);
    multiChanged.remove(field);
    multiChanged.add(field.replace("|1|", "|17|"));
    var diagnostics = NativeSdkAudit.differences(sdk, sdk.classes(), multiChanged);
    require(diagnostics.size() == 2
        && diagnostics.stream().anyMatch(d -> d.reason().equals("SDK export missing/renamed in DEX") && d.identity().endsWith("bar(I)V"))
        && diagnostics.stream().anyMatch(d -> d.reason().equals("SDK export became final") && d.identity().endsWith("value:I")),
        "All diagnostic differences must share the strict verifier's rules");
    checks++;
    var directory = Files.createTempDirectory("native-marker-checks-");
    var markerZip = directory.resolve("public-marker.zip");
    String expected = "a".repeat(64);
    try {
      marker(markerZip, expected + "\n");
      NativeSdkAudit.verifyContractMarker(markerZip, expected);
      checks++;
      marker(markerZip, null);
      expectReject(() -> NativeSdkAudit.verifyContractMarker(markerZip, expected), "missing marker");
      marker(markerZip, "b".repeat(64) + "\n");
      expectReject(() -> NativeSdkAudit.verifyContractMarker(markerZip, expected), "wrong SDK marker");
      marker(markerZip, expected + "\r\n");
      expectReject(() -> NativeSdkAudit.verifyContractMarker(markerZip, expected), "CRLF marker");
      marker(markerZip, expected + "x");
      expectReject(() -> NativeSdkAudit.verifyContractMarker(markerZip, expected), "missing LF marker");
      marker(markerZip, "G".repeat(64) + "\n");
      expectReject(() -> NativeSdkAudit.verifyContractMarker(markerZip, expected), "non-hex marker");
    } finally {
      Files.deleteIfExists(markerZip);
      Files.delete(directory);
    }
    System.out.println("NativeSdkAuditChecks: " + checks + " passed");
  }

  private static void marker(Path file, String value) throws Exception {
    try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
      zip.putNextEntry(new ZipEntry(value == null ? "unknown" : "assets/hot/host-contract.sha256"));
      if (value != null) zip.write(value.getBytes(StandardCharsets.US_ASCII));
      zip.closeEntry();
    }
  }

  private static void reject(NativeSdkAudit.Api sdk, Set<String> original, String remove, String add,
      String description) throws Exception {
    var changed = new TreeSet<>(original);
    require(changed.remove(remove), "Test vector did not alter original API: " + description);
    if (add != null) changed.add(add);
    expectReject(() -> NativeSdkAudit.verify(sdk, sdk.classes(), changed), description);
  }

  interface Checked { void run() throws Exception; }
  private static void expectReject(Checked action, String description) throws Exception {
    try { action.run(); } catch (java.io.IOException expected) { checks++; return; }
    throw new AssertionError("Unexpected acceptance: " + description);
  }
  private static void require(boolean success, String message) {
    if (!success) throw new AssertionError(message);
  }
}
