package app.luoxianlv.host;

import static org.junit.Assert.*;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.Test;

/** 实际只读计划及真实JVM父加载器；不假称Android服务或授权成功。 */
public final class NativeServiceMatrixPlanTest {
  private NativeServiceMatrixPlan plan(String scenario, String consumer) {
    return new NativeServiceMatrixPlan("a".repeat(32), scenario, consumer, "b".repeat(64),
        "c".repeat(64), "d".repeat(64), "e".repeat(64));
  }

  @Test public void validConsumersUseActualDifferentExposureStages() {
    var a11y = plan("valid", "accessibility");
    assertEquals("TRIAL", a11y.expectedPhase);
    var foreground = plan("valid", "foreground");
    assertEquals("PREPARING", foreground.expectedPhase);
    a11y.expected("b".repeat(64), "c".repeat(64), "b".repeat(64), "e".repeat(64), "TRIAL");
    assertThrows(IllegalArgumentException.class, () -> foreground.expected(
        "b".repeat(64), "c".repeat(64), "b".repeat(64), "e".repeat(64), "TRIAL"));
  }

  @Test public void rejectedCasesKeepStableRuntimeAndExactPending() {
    for (String scenario : List.of("revoked", "expired", "offline"))
      for (String consumer : List.of("accessibility", "foreground")) {
        var value = plan(scenario, consumer);
        value.expected("c".repeat(64), "c".repeat(64), "b".repeat(64), "d".repeat(64), "STABLE");
        assertThrows(IllegalArgumentException.class, () -> value.expected(
            "b".repeat(64), "c".repeat(64), "b".repeat(64), "e".repeat(64), "TRIAL"));
      }
  }

  @Test public void planRejectsUnknownCasesAndUnchangedOrUnsafeIdentities() {
    assertThrows(IllegalArgumentException.class, () -> plan("paused", "accessibility"));
    assertThrows(IllegalArgumentException.class, () -> plan("valid", "page"));
    assertThrows(IllegalArgumentException.class, () -> new NativeServiceMatrixPlan("old", "valid",
        "accessibility", "b".repeat(64), "c".repeat(64), "d".repeat(64), "e".repeat(64)));
    assertThrows(IllegalArgumentException.class, () -> new NativeServiceMatrixPlan("a".repeat(32), "valid",
        "accessibility", "b".repeat(64), "c".repeat(64), "d".repeat(64), "d".repeat(64)));
  }

  @Test public void exactMarkerUsesRuntimeParentWithoutClassInitialization() throws Exception {
    var root = Files.createTempDirectory("native-service-marker-");
    var marker = root.resolve("app/luoxianlv/runtime/probe/NewRuntimeMarker.java");
    var kotlin = root.resolve("kotlin/Unit.java");
    Files.createDirectories(marker.getParent());
    Files.createDirectories(kotlin.getParent());
    Files.writeString(marker, "package app.luoxianlv.runtime.probe; public final class NewRuntimeMarker {"
        + "static {if(System.nanoTime()!=0)throw new AssertionError(\"must not initialize\");}}");
    Files.writeString(kotlin, "package kotlin; public final class Unit {}");
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
        "-encoding", "UTF-8", "-d", root.toString(), marker.toString(), kotlin.toString()));
    try (var runtime = new URLClassLoader(new java.net.URL[] {root.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        var business = new URLClassLoader(new java.net.URL[0], runtime)) {
      assertTrue(plan("valid", "accessibility").marker(business));
      assertThrows(IllegalArgumentException.class, () -> plan("revoked", "accessibility").marker(business));
      assertThrows(IllegalArgumentException.class, () -> plan("valid", "accessibility").marker(runtime));
    }
  }

  @Test public void rejectedRuntimeHasNoMarkerAndValidMissingMarkerFails() throws Exception {
    try (var stable = new URLClassLoader(new java.net.URL[0], ClassLoader.getPlatformClassLoader())) {
      assertFalse(plan("offline", "foreground").marker(stable));
      assertThrows(IllegalArgumentException.class, () -> plan("valid", "foreground").marker(stable));
    }
  }
}
