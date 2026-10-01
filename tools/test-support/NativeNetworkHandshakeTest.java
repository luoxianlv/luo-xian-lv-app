package app.luoxianlv.host;

import static org.junit.Assert.*;
import app.luoxianlv.hot.StrictJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** 测试实际helper文件协议/等待预算；不模拟网络恢复成功、不修改宿主时钟。 */
public final class NativeNetworkHandshakeTest {
  public static void main(String[] args) {
    var skipped = new java.util.ArrayList<String>();
    var core = new org.junit.runner.JUnitCore();
    core.addListener(new org.junit.runner.notification.RunListener() {
      @Override public void testAssumptionFailure(org.junit.runner.notification.Failure failure) {
        skipped.add(failure.getDescription().getMethodName());
      }
    });
    var result = core.run(NativeNetworkHandshakeTest.class);
    System.out.println("NativeNetworkHandshakeTest: tests=" + result.getRunCount() + " passed="
        + (result.getRunCount() - result.getFailureCount() - skipped.size()) + " failed=" + result.getFailureCount()
        + " assumptionSkipped=" + skipped.size() + " skipped=" + skipped);
    if (!result.wasSuccessful()) throw new AssertionError(result.getFailures().toString());
  }
  @Rule public final TemporaryFolder temporary = new TemporaryFolder();
  private final String runId = "a".repeat(32);
  private NativeNetworkChecks.Session session() throws Exception {
    return NativeNetworkChecks.Session.open(temporary.newFolder().toPath(), runId);
  }
  private void ack(NativeNetworkChecks.Session session, String value) throws Exception {
    Files.write(session.acknowledgement, value.getBytes(StandardCharsets.UTF_8));
  }
  @Test public void onlyExactRunAndPhaseAcknowledgeExternalAction() throws Exception {
    var session = session();
    assertFalse(session.acknowledged("disconnect"));
    assertFalse(session.acknowledged("reconnect"));
    ack(session, "{\"runId\":\"" + runId + "\",\"phase\":\"disconnect\"}");
    assertTrue(session.acknowledged("disconnect"));
    assertFalse(session.acknowledged("reconnect"));
    ack(session, "{\"runId\":\"" + "b".repeat(32) + "\",\"phase\":\"reconnect\"}");
    assertFalse(session.acknowledged("reconnect"));
    ack(session, "{\"runId\":\"" + runId + "\",\"phase\":\"reconnect\"}");
    assertTrue(session.acknowledged("reconnect"));
  }
  @Test public void missingAckNeverCreatesOneOrPretendsExternalActionCompleted() throws Exception {
    var session = session();
    session.request("disconnect", 100, 285100, "apk:" + "a".repeat(64) + ":" + "b".repeat(64));
    for (int i = 0; i < 10; i++) assertFalse(session.acknowledged("disconnect"));
    assertFalse(Files.exists(session.acknowledgement));
    assertFalse(Files.exists(session.report));
    var control = StrictJson.object(Files.readAllBytes(session.control));
    assertEquals("disconnect", control.string("phase"));
    assertEquals(runId, control.string("runId"));
    assertFalse(control.bool("systemNetworkChangedByHelper"));
    assertEquals(285100, control.number("deadlineElapsedMs"));
  }
  @Test public void runDirectoryCannotTraverseOrOverwriteOldRun() throws Exception {
    Path root = temporary.newFolder().toPath();
    assertThrows(AssertionError.class, () -> NativeNetworkChecks.Session.open(root, "../old"));
    assertFalse(Files.exists(root.resolve("native-host-network-checks")));
    var session = NativeNetworkChecks.Session.open(root, runId);
    Path unknown = session.directory.resolve("unknown-user-test-marker");
    Files.write(unknown, new byte[] {1,2,3});
    assertThrows(java.nio.file.FileAlreadyExistsException.class,
        () -> NativeNetworkChecks.Session.open(root, runId));
    assertArrayEquals(new byte[] {1,2,3}, Files.readAllBytes(unknown));
  }
  @Test public void ackRejectsUnknownDuplicateMalformedAndOversizedInput() throws Exception {
    var session = session();
    for (String value : new String[] {
        "{\"runId\":\"" + runId + "\",\"phase\":\"disconnect\",\"extra\":true}",
        "{\"runId\":\"" + runId + "\",\"phase\":\"disconnect\",\"phase\":\"reconnect\"}",
        "{\"runId\":\"" + runId + "\",\"phase\":\"complete\"}", "{}", "not-json", "\ufeff{}"}) {
      ack(session, value);
      assertThrows(Throwable.class, () -> session.acknowledged("disconnect"));
    }
    Files.write(session.acknowledgement, new byte[4097]);
    assertThrows(AssertionError.class, () -> session.acknowledged("disconnect"));
  }
  @Test public void symbolicAckIsRefusedWithoutChangingItsTarget() throws Exception {
    var session = session();
    Path outside = temporary.newFile().toPath();
    byte[] bytes = ("{\"runId\":\"" + runId + "\",\"phase\":\"disconnect\"}").getBytes(StandardCharsets.UTF_8);
    Files.write(outside, bytes);
    try { Files.createSymbolicLink(session.acknowledgement, outside); }
    catch (UnsupportedOperationException | java.io.IOException denied) { Assume.assumeNoException(denied); }
    assertThrows(AssertionError.class, () -> session.acknowledged("disconnect"));
    assertArrayEquals(bytes, Files.readAllBytes(outside));
  }
  @Test public void reportReplacementReplacesAliasEntryWithoutWritingItsTarget() throws Exception {
    var session = session();
    Path outside = temporary.newFile().toPath();
    Files.write(outside, new byte[] {4,5,6});
    Files.createLink(session.report, outside);
    session.writeReport("{\"passed\":true}");
    assertArrayEquals(new byte[] {4,5,6}, Files.readAllBytes(outside));
    assertEquals("{\"passed\":true}", Files.readString(session.report));
  }
  @Test public void controlledTokensCannotInjectPathsOrJsonAndTerminalPhasesAreNotAck() throws Exception {
    var session = session();
    assertThrows(AssertionError.class, () -> session.request("disconnect", 1, 2, "invalid\"source"));
    assertThrows(AssertionError.class, () -> session.request("other", 1, 2, "a"));
    assertThrows(AssertionError.class, () -> session.acknowledged("complete"));
    session.request("complete", 1, 2, "a");
    assertEquals("complete", StrictJson.object(Files.readAllBytes(session.control)).string("phase"));
    session.request("failed", 2, 2, "a");
    assertEquals("failed", StrictJson.object(Files.readAllBytes(session.control)).string("phase"));
  }
  @Test public void offlineWindowKeepsFull75SecondsAndCrossesActualDueBy3Seconds() {
    assertEquals(76000, NativeNetworkChecks.offlineDeadline(1000, 10000));
    assertEquals(83000, NativeNetworkChecks.offlineDeadline(1000, 80000));
    assertEquals(76000, NativeNetworkChecks.offlineDeadline(1000, -1));
  }
  @Test public void ownDeadlineClampsStageAndNeverShortensRequiredOfflineWindow() {
    AtomicLong now = new AtomicLong(1000);
    var deadline = new NativeNetworkChecks.Deadline(now::get);
    assertEquals(286000, deadline.expires);
    assertEquals(45000, deadline.remaining(45000));
    now.set(280000);
    assertEquals(6000, deadline.remaining(45000));
    assertTrue(NativeNetworkChecks.offlineDeadline(now.get(), now.get() + 1000) > deadline.expires);
    now.set(286000);
    assertThrows(AssertionError.class, deadline::check);
    now.set(301000); deadline.complete();
    now.set(301001); assertThrows(AssertionError.class, deadline::complete);
  }
}
