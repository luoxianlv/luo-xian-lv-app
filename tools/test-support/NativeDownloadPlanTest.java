package app.luoxianlv.host;

import static org.junit.Assert.*;

import app.luoxianlv.hot.StrictJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** 测试实际helper计划/文件握手；不模拟Android下载、取消或成功首帧。 */
public final class NativeDownloadPlanTest {
  private static final class JsonWire {
    @SuppressWarnings("unchecked")
    static Map<String, Object> fields(Object... values) {
      try {
        var method =
            Class.forName("app.luoxianlv.hot.JsonWire").getDeclaredMethod("fields", Object[].class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(null, new Object[] {values});
      } catch (ReflectiveOperationException error) {
        throw new AssertionError(error);
      }
    }

    static byte[] encode(Map<String, ?> values) {
      try {
        var method =
            Class.forName("app.luoxianlv.hot.JsonWire").getDeclaredMethod("encode", Map.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(null, values);
      } catch (ReflectiveOperationException error) {
        throw new AssertionError(error);
      }
    }
  }

  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final String runId = "a".repeat(32), hash = "b".repeat(64);

  private Map<String, Object> fields(String mode, long prefix) {
    return JsonWire.fields(
        "schema",
        1,
        "runId",
        runId,
        "mode",
        mode,
        "targetSnapshotId",
        "c".repeat(64),
        "sourceIdentity",
        "",
        "slowObjectSha",
        hash,
        "expectedObjects",
        List.of(JsonWire.fields("sha256", hash, "size", 554)),
        "expectedMissingBytes",
        554,
        "prefixBytes",
        prefix);
  }

  private Path planDirectory(Path files, Map<String, Object> fields) throws Exception {
    Path directory = files.resolve("native-download-checks").resolve(runId);
    Files.createDirectories(directory);
    Files.write(directory.resolve("plan.json"), JsonWire.encode(fields));
    return directory;
  }

  private NativeDownloadChecks.Session session() throws Exception {
    Path files = temporary.newFolder().toPath();
    planDirectory(files, fields("cancellation", 64));
    return NativeDownloadChecks.Session.open(files, runId);
  }

  @Test
  public void actualPlanAcceptsOnlyBoundedConsistentMissingObjects() throws Exception {
    var plan = new NativeDownloadChecks.Plan(JsonWire.encode(fields("cancellation", 64)), runId);
    assertEquals(554, plan.missing);
    assertEquals(64, plan.prefix);
    assertEquals(1, plan.objects.size());
    assertEquals(
        0, new NativeDownloadChecks.Plan(JsonWire.encode(fields("delta", 0)), runId).prefix);
    assertThrows(UnsupportedOperationException.class, () -> plan.objects.clear());
  }

  @Test
  public void objectAliasesUnknownFieldsWrongSumAndModesAreRejected() throws Exception {
    for (String change :
        List.of("schema", "mode", "runId", "target", "slow", "sum", "objects", "extra")) {
      var fields = fields("cancellation", 64);
      switch (change) {
        case "schema" -> fields.put("schema", 2);
        case "mode" -> fields.put("mode", "all-matrix");
        case "runId" -> fields.put("runId", "d".repeat(32));
        case "target" -> fields.put("targetSnapshotId", "../target");
        case "slow" -> fields.put("slowObjectSha", "d".repeat(64));
        case "sum" -> fields.put("expectedMissingBytes", 555);
        case "objects" ->
            fields.put(
                "expectedObjects",
                List.of(
                    JsonWire.fields("sha256", hash, "size", 277),
                    JsonWire.fields("sha256", hash, "size", 277)));
        case "extra" -> fields.put("token", "not-accepted");
      }
      assertThrows(
          change,
          Throwable.class,
          () -> new NativeDownloadChecks.Plan(JsonWire.encode(fields), runId));
    }
  }

  @Test
  public void cancellationCannotCaptureEmptyOrCompletePrefix() throws Exception {
    for (long prefix : new long[] {-1, 0, 554, 32769})
      assertThrows(
          Throwable.class,
          () ->
              new NativeDownloadChecks.Plan(
                  JsonWire.encode(fields("cancellation", prefix)), runId));
  }

  @Test
  public void staleRunAndExistingHandshakeAreNeverReusedOrRemoved() throws Exception {
    Path files = temporary.newFolder().toPath();
    Path directory = planDirectory(files, fields("delta", 0));
    Path unknown = directory.resolve("unknown-marker");
    Files.write(unknown, new byte[] {1, 2});
    var session = NativeDownloadChecks.Session.open(files, runId);
    assertThrows(
        FileAlreadyExistsException.class, () -> NativeDownloadChecks.Session.open(files, runId));
    assertArrayEquals(new byte[] {1, 2}, Files.readAllBytes(unknown));
    assertFalse(Files.exists(session.control));
    assertFalse(Files.exists(session.ack));
    Path otherFiles = temporary.newFolder().toPath();
    Path old = planDirectory(otherFiles, fields("delta", 0));
    Files.writeString(old.resolve("ack.json"), "{}");
    assertThrows(AssertionError.class, () -> NativeDownloadChecks.Session.open(otherFiles, runId));
    assertEquals("{}", Files.readString(old.resolve("ack.json")));
  }

  @Test
  public void onlyCurrentRunAndExactAcknowledgementPhaseAreAccepted() throws Exception {
    var session = session();
    assertFalse(session.acknowledged("armed"));
    Files.write(
        session.ack,
        JsonWire.encode(JsonWire.fields("schema", 1, "runId", runId, "phase", "armed")));
    assertTrue(session.acknowledged("armed"));
    assertFalse(session.acknowledged("released"));
    Files.write(
        session.ack,
        JsonWire.encode(
            JsonWire.fields("schema", 1, "runId", "d".repeat(32), "phase", "released")));
    assertFalse(session.acknowledged("released"));
    Files.write(
        session.ack,
        JsonWire.encode(JsonWire.fields("schema", 1, "runId", runId, "phase", "released")));
    assertTrue(session.acknowledged("released"));
  }

  @Test
  public void malformedDuplicateUnknownAndOversizedAckAreRefused() throws Exception {
    var session = session();
    for (String invalid :
        List.of(
            "{}",
            "not-json",
            "\ufeff{}",
            "{\"schema\":1,\"runId\":\"" + runId + "\",\"phase\":\"armed\",\"phase\":\"released\"}",
            "{\"schema\":1,\"runId\":\"" + runId + "\",\"phase\":\"arm\"}",
            "{\"schema\":1,\"runId\":\"" + runId + "\",\"phase\":\"armed\",\"extra\":true}")) {
      Files.writeString(session.ack, invalid, StandardCharsets.UTF_8);
      assertThrows(Throwable.class, () -> session.acknowledged("armed"));
    }
    Files.write(session.ack, new byte[4097]);
    assertThrows(AssertionError.class, () -> session.acknowledged("armed"));
  }

  @Test
  public void controlReplacementDoesNotWriteThroughHardlinkAlias() throws Exception {
    var session = session();
    Path outside = temporary.newFile().toPath();
    Files.write(outside, new byte[] {7, 8});
    Files.createLink(session.control, outside);
    session.request("arm", 123, 300000, "d".repeat(64));
    assertArrayEquals(new byte[] {7, 8}, Files.readAllBytes(outside));
    var value = StrictJson.object(Files.readAllBytes(session.control));
    assertEquals("arm", value.string("phase"));
    assertEquals(runId, value.string("runId"));
    assertEquals(554, value.number("expectedMissingBytes"));
  }

  @Test
  public void safeFailureChainPreservesOnlyOwnAssertionsAndBoundsCycles() {
    var external = new java.io.IOException("https://private.invalid/?token=secret /private/file");
    var failure = new NativeDownloadChecks.CheckFailure("下载主线程观察失败", external);
    failure.addSuppressed(new NativeDownloadChecks.CheckFailure("自己的演练场未关闭"));
    external.initCause(failure);
    var rows = NativeDownloadChecks.safeFailureChain(failure);
    assertEquals(3, rows.size());
    assertEquals("下载主线程观察失败", rows.get(0).get("message"));
    assertEquals("java.io.IOException", rows.get(1).get("type"));
    assertEquals("外部异常详情已隐藏", rows.get(1).get("message"));
    assertEquals("suppressed", rows.get(2).get("relation"));
    assertFalse(rows.toString().contains("private.invalid"));
    assertFalse(rows.toString().contains("secret"));
    var many = new NativeDownloadChecks.CheckFailure("实际演练场没有启动");
    for (int i = 0; i < 40; i++) many.addSuppressed(new java.io.IOException("secret"));
    assertEquals(24, NativeDownloadChecks.safeFailureChain(many).size());
  }

  @Test
  public void failureReportOverwritesSuccessBeforePublishingFailed() throws Exception {
    var session = session();
    Files.writeString(session.report, "{\"passed\":true}");
    byte[] safe = JsonWire.encode(JsonWire.fields("passed", false, "runId", runId));
    session.failed(safe, 123, 300000, "d".repeat(64));
    assertFalse(StrictJson.object(Files.readAllBytes(session.report)).bool("passed"));
    assertEquals("failed", StrictJson.object(Files.readAllBytes(session.control)).string("phase"));
    assertArrayEquals(safe, Files.readAllBytes(session.report));
  }

  @Test
  public void failureControlIsNotPublishedBeforeDiagnosticWriteSucceeds() throws Exception {
    var session = session();
    Files.createDirectory(session.report);
    Files.write(session.report.resolve("keep"), new byte[] {7});
    assertThrows(
        Exception.class,
        () -> session.failed(new byte[] {1}, 123, 300000, "d".repeat(64)));
    assertFalse(Files.exists(session.control));
    assertTrue(Files.isDirectory(session.report));
  }
}
