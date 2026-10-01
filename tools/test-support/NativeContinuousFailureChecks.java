package app.luoxianlv.host;

import java.util.*;

/** 实际helper安全异常链；不构造设备Source，不模拟健康或设备成功。 */
public final class NativeContinuousFailureChecks {
  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("--argv")) {
      for (int index = 1; index < args.length; index++) System.out.println(Base64.getEncoder().encodeToString(args[index].getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      return;
    }
    int passed = 0;
    String secret = "https://invalid.local/download?token=must-not-appear";
    var inner = new NativeContinuousChecks.CheckFailure("连续验收没有交接安全点");
    var outer = new NativeContinuousChecks.CheckFailure("连续验收主线程检查失败", inner);
    var rows = NativeContinuousChecks.safeFailureChain(outer);
    require(rows.size() == 2 && rows.get(0).get("relation").equals("root")
        && rows.get(1).get("relation").equals("cause")
        && rows.get(1).get("message").equals("连续验收没有交接安全点"), "Own fixed cause was lost");
    passed++;
    outer.addSuppressed(new java.io.IOException(secret));
    rows = NativeContinuousChecks.safeFailureChain(outer);
    require(rows.size() == 3 && rows.get(2).get("relation").equals("suppressed")
        && rows.get(2).get("type").equals("java.io.IOException")
        && !rows.toString().contains(secret), "External suppressed text leaked");
    passed++;
    rows = NativeContinuousChecks.safeFailureChain(new AssertionError(secret, new IllegalStateException(secret)));
    require(rows.size() == 2 && rows.stream().allMatch(row -> row.get("message").equals("外部异常详情已隐藏")),
        "Foreign assertion text leaked");
    passed++;
    var first = new RuntimeException(secret); var second = new RuntimeException(secret);
    first.initCause(second); second.initCause(first);
    require(NativeContinuousChecks.safeFailureChain(first).size() == 2, "Cause cycle was not bounded");
    passed++;
    var many = new RuntimeException(secret);
    for (int index = 0; index < 30; index++) many.addSuppressed(new java.io.IOException(secret));
    require(NativeContinuousChecks.safeFailureChain(many).size() == 24, "Failure chain limit missing");
    passed++;
    String hash = "a".repeat(64), apk = "apk:" + hash + ":" + "b".repeat(64);
    require(NativeContinuousChecks.safeIdentity(hash).equals(hash)
        && NativeContinuousChecks.safeIdentity(apk).equals(apk)
        && NativeContinuousChecks.safeIdentity("").isEmpty(), "Actual host identity format rejected");
    passed++;
    require(NativeContinuousChecks.safeIdentity(secret).equals("unavailable")
        && NativeContinuousChecks.safeIdentity(null).equals("unavailable"), "Unknown selection text leaked");
    passed++;
    String services = "com.other/com.other.Access$Inner:app.luoxianlv.debug/app.luoxianlv.service.MusicAccessibilityService";
    String command = NativeContinuousChecks.accessibilityServicesCommand(services);
    List<String> actual = argv(args[0], command);
    require(actual.equals(List.of("settings", "put", "secure", "enabled_accessibility_services", services)),
        "Actual Runtime.exec argv changed the dollar-containing service value");
    passed++;
    List<String> old = argv(args[0], "settings put secure enabled_accessibility_services '" + services + "'");
    require(old.get(4).equals("'" + services + "'") && !old.get(4).equals(services), "Original quote bug not reproduced");
    passed++;
    try {
      NativeContinuousChecks.accessibilityServicesCommand("bad value;command");
      throw new AssertionError("Invalid raw service value accepted");
    } catch (NativeContinuousChecks.CheckFailure expected) { passed++; }
    System.out.println("NativeContinuousFailureChecks: " + passed + " passed; no device/health state simulated");
  }
  private static List<String> argv(String classpath, String command) throws Exception {
    require(!classpath.contains(" "), "Runtime.exec fixture classpath must not need shell quoting");
    Process process = Runtime.getRuntime().exec("java -cp " + classpath + " app.luoxianlv.host.NativeContinuousFailureChecks --argv " + command);
    if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Local argv fixture timeout"); }
    require(process.exitValue() == 0, "Local argv fixture failed");
    var values = new ArrayList<String>();
    for (String line : new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R"))
      if (!line.isEmpty()) values.add(new String(Base64.getDecoder().decode(line), java.nio.charset.StandardCharsets.UTF_8));
    return values;
  }
  private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
