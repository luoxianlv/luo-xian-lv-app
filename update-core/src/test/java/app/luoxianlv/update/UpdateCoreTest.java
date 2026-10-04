package app.luoxianlv.update;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;

public final class UpdateCoreTest {
  @Test public void authenticationPrecedesParsingAndFailureCannotFallback() throws Exception {
    SignedDelivery invalid = new SignedDelivery(1, Base64.getEncoder().encodeToString("not json".getBytes(StandardCharsets.UTF_8)), "sig", "trust", "root");
    AtomicBoolean called = new AtomicBoolean();
    try {
      UpdateManifest.authenticate(invalid, (delivery, bytes) -> { called.set(true); throw new SecurityException("bad signature"); });
      fail("must reject");
    } catch (SecurityException expected) { assertEquals("bad signature", expected.getMessage()); }
    assertTrue(called.get());
  }
  @Test public void signedTargetAndObjectIdentityAreBound() throws Exception {
    JSONObject value = manifest();
    UpdateManifest parsed = UpdateManifest.authenticate(envelope(value), (delivery, bytes) -> {});
    assertEquals(19, parsed.target.versionCode);
    assertTrue(UpdateManifest.worthwhile(parsed.target.size, parsed.deltas.get(0).patch.size));
    value.getJSONArray("deltas").getJSONObject(0).getJSONObject("patch").put("object", "other/object");
    assertThrows(SecurityException.class, () -> UpdateManifest.authenticate(envelope(value), (delivery, bytes) -> {}));
  }
  @Test public void savingThresholdIncludesBothThirtyPercentAnd64KiBWithoutOverflow() {
    assertFalse(UpdateManifest.worthwhile(100000, 40000));
    assertTrue(UpdateManifest.worthwhile(100000, 34464));
    assertTrue(UpdateManifest.worthwhile(1000000, 700000));
    assertFalse(UpdateManifest.worthwhile(1000000, 700001));
    assertFalse(UpdateManifest.worthwhile(Long.MAX_VALUE, Long.MAX_VALUE));
    assertTrue(UpdateManifest.worthwhile(Long.MAX_VALUE, Long.MAX_VALUE / 2));
  }
  @Test public void downloadResumesExactRangeAndCountsActualNetworkBytes() throws Exception {
    byte[] content = content();
    File file = Files.createTempDirectory("lx-range").resolve("target.part").toFile();
    Files.write(file.toPath(), Arrays.copyOf(content, 3000));
    AtomicReference<String> range = new AtomicReference<>();
    MiniServer server = serve(content, range, true, false);
    try {
      AtomicLong downloaded = new AtomicLong(3000);
      new ResumableDownloader().download(() -> address(server), file, content.length, digest(content), new Cancellation(), Progress.NONE, downloaded);
      assertEquals("bytes=3000-", range.get()); assertArrayEquals(content, Files.readAllBytes(file.toPath()));
      assertEquals(content.length, downloaded.get());
    } finally { server.stop(0); }
  }
  @Test public void serverIgnoringRangeRestartsWithoutAppendingWrongBytes() throws Exception {
    byte[] content = content(); File file = Files.createTempDirectory("lx-full").resolve("target.part").toFile();
    Files.write(file.toPath(), Arrays.copyOf(content, 3000));
    MiniServer server = serve(content, new AtomicReference<>(), false, false);
    try {
      long received = new ResumableDownloader().download(() -> address(server), file, content.length, digest(content), new Cancellation(), Progress.NONE);
      assertEquals(content.length, received); assertArrayEquals(content, Files.readAllBytes(file.toPath()));
    } finally { server.stop(0); }
  }
  @Test public void truncatedTransferRemainsResumableAndNeverClaimsSuccess() throws Exception {
    byte[] content = content(); File file = Files.createTempDirectory("lx-short").resolve("target.part").toFile();
    MiniServer server = serve(content, new AtomicReference<>(), false, true);
    try {
      assertThrows(IOException.class, () -> new ResumableDownloader().download(() -> address(server), file, content.length, digest(content), new Cancellation(), Progress.NONE));
      assertTrue(file.length() > 0); assertTrue(file.length() < content.length);
    } finally { server.stop(0); }
  }
  @Test public void damagedCompleteCacheIsRedownloadedAndCancelledDownloadDoesNotContinue() throws Exception {
    byte[] content = content(); File file = Files.createTempDirectory("lx-corrupt").resolve("target.part").toFile();
    Files.write(file.toPath(), new byte[content.length]); MiniServer server = serve(content, new AtomicReference<>(), false, false);
    try {
      new ResumableDownloader().download(() -> address(server), file, content.length, digest(content), new Cancellation(), Progress.NONE);
      assertArrayEquals(content, Files.readAllBytes(file.toPath()));
      Files.write(file.toPath(), new byte[0]); Cancellation cancellation = new Cancellation();
      assertThrows(Cancellation.CancelledException.class, () -> new ResumableDownloader().download(() -> address(server), file, content.length, digest(content), cancellation,
          (phase, done, total, received, message) -> cancellation.cancel()));
      assertTrue(cancellation.isCancelled());
    } finally { server.stop(0); }
  }
  @Test public void incorrectContentRangeAndOversizeAreRejected() throws Exception {
    byte[] content = content(); File file = Files.createTempDirectory("lx-bad-range").resolve("target.part").toFile();
    Files.write(file.toPath(), Arrays.copyOf(content, 3));
    MiniServer server = new MiniServer(content, new AtomicReference<>(), true, false, true);
    try { assertThrows(IOException.class, () -> new ResumableDownloader().download(() -> address(server), file, content.length, digest(content), new Cancellation(), Progress.NONE)); }
    finally { server.stop(0); }
  }
  @Test public void insecureAndCredentialBearingDownloadUrlsAreRejected() {
    assertThrows(IOException.class, () -> ResumableDownloader.validate("http://example.com/app.apk"));
    assertThrows(IOException.class, () -> ResumableDownloader.validate("https://user:password@example.com/app.apk"));
    assertThrows(IOException.class, () -> ResumableDownloader.validate("https://example.com/app.apk#fragment"));
  }
  @Test public void duplicateKeysLooseSyntaxAndTrailingDataAreRejected() {
    assertThrows(SecurityException.class, () -> StrictJson.check("{\"schema\":1,\"schema\":2}"));
    assertThrows(SecurityException.class, () -> StrictJson.check("{schema:1}"));
    assertThrows(SecurityException.class, () -> StrictJson.check("{\"schema\":01}"));
    assertThrows(SecurityException.class, () -> StrictJson.check("{} {}"));
    assertThrows(SecurityException.class, () -> StrictJson.check("{\"x\":\"\\ud800\"}"));
    StrictJson.check("{\"x\":[true,false,null,1.25e+3,\"\\ud83c\\udfb5\"]}");
  }
  @Test public void symlinkPartialCannotOverwriteUnrelatedFile() throws Exception {
    File root = Files.createTempDirectory("lx-link").toFile(); File original = new File(root, "original");
    Files.write(original.toPath(), new byte[]{7}); File partial = new File(root, "part");
    try { Files.createSymbolicLink(partial.toPath(), original.toPath()); }
    catch (IOException | UnsupportedOperationException noPrivilege) { org.junit.Assume.assumeNoException(noPrivilege); }
    assertThrows(IOException.class, () -> new ResumableDownloader().download(() -> "https://example.com/unused", partial, 1, digest(new byte[]{7}), new Cancellation(), Progress.NONE));
    assertArrayEquals(new byte[]{7}, Files.readAllBytes(original.toPath()));
  }
  @Test public void cancellingMarksImmediatelyAndRunsAllHooksOutsideCallerUntilDrained() throws Exception {
    Cancellation cancellation = new Cancellation();
    Thread caller = Thread.currentThread(); java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
    cancellation.onCancel(() -> { assertNotSame(caller, Thread.currentThread()); entered.countDown(); try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } completed.incrementAndGet(); });
    cancellation.onCancel(() -> { completed.incrementAndGet(); throw new IllegalStateException("one hook failed"); });
    cancellation.onCancel(completed::incrementAndGet);
    cancellation.cancel();
    assertTrue(cancellation.isCancelled()); assertThrows(Cancellation.CancelledException.class, cancellation::check);
    assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, completed.get());
    release.countDown(); cancellation.awaitClosures(); assertEquals(3, completed.get());
  }
  @Test public void cacheCleanupRetainsCurrentActiveAndTwoHistoricalCompleteTasksOnly() throws Exception {
    File root = Files.createTempDirectory("lx-cache").toFile();
    String current = "a".repeat(64), active = "b".repeat(64), oldPartial = "c".repeat(64);
    for (String hash : new String[]{current, active, oldPartial, "d".repeat(64), "e".repeat(64), "f".repeat(64)}) {
      File directory = new File(root, hash); assertTrue(directory.mkdir());
      if (hash.charAt(0) >= 'd') { File apk = new File(directory, "verified.apk"); Files.write(apk.toPath(), new byte[]{1}); assertTrue(apk.setLastModified(hash.charAt(0) * 1000L)); }
      else Files.write(new File(directory, "partial").toPath(), new byte[]{1});
    }
    File unrelated = new File(root, "user-data"); assertTrue(unrelated.mkdir());
    ApkCacheCleaner.collect(root, current, active);
    assertTrue(new File(root, current).isDirectory()); assertTrue(new File(root, active).isDirectory());
    assertFalse(new File(root, oldPartial).exists()); assertFalse(new File(root, "d".repeat(64)).exists());
    assertTrue(new File(root, "e".repeat(64)).isDirectory()); assertTrue(new File(root, "f".repeat(64)).isDirectory()); assertTrue(unrelated.isDirectory());
  }
  @Test public void isolatedProcessComponentSuffixStillSkipsHostApplicationInitialization() {
    assertTrue(DeltaWorkerService.patchProcessName("app.luoxianlv", "app.luoxianlv:delta_worker"));
    assertTrue(DeltaWorkerService.patchProcessName("app.luoxianlv", "app.luoxianlv:delta_worker:app.luoxianlv.update.DeltaWorkerService"));
    assertFalse(DeltaWorkerService.patchProcessName("app.luoxianlv", "app.luoxianlv:delta_worker_other"));
    assertFalse(DeltaWorkerService.patchProcessName("app.luoxianlv", "app.luoxianlv"));
  }
  private static JSONObject manifest() throws org.json.JSONException {
    String target = "a".repeat(64), base = "b".repeat(64), patch = "c".repeat(64), certificate = "d".repeat(64);
    return new JSONObject("{\"schema\":1,\"type\":\"apk-update\",\"applicationId\":\"app.luoxianlv\",\"environment\":\"production\",\"variant\":\"release-universal\",\"issuedAt\":\"2026-10-03T00:00:00Z\",\"target\":{\"versionCode\":19,\"versionName\":\"example\",\"sha256\":\"" + target + "\",\"size\":1000000,\"certificateSha256\":\"" + certificate + "\"},\"deltas\":[{\"base\":{\"versionCode\":18,\"sha256\":\"" + base + "\",\"size\":1000000},\"patch\":{\"algorithm\":\"hdiff-w26-zstd-v1\",\"sha256\":\"" + patch + "\",\"size\":1000,\"object\":\"luoxianlv/delta/" + target + "/" + base + "/" + patch + ".hpatch\"}}]}");
  }
  private static SignedDelivery envelope(JSONObject value) { return new SignedDelivery(1, Base64.getEncoder().encodeToString(value.toString().getBytes(StandardCharsets.UTF_8)), "sig", "trust", "root"); }
  private static byte[] content() { byte[] bytes = new byte[10000]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)(i * 19); return bytes; }
  private static String digest(byte[] bytes) throws Exception { return ArtifactVerifier.hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
  private static String address(MiniServer server) { return "http://127.0.0.1:" + server.socket.getLocalPort() + "/file"; }
  private static MiniServer serve(byte[] content, AtomicReference<String> requestedRange, boolean range, boolean truncate) throws IOException {
    return new MiniServer(content, requestedRange, range, truncate, false);
  }
  private static final class MiniServer {
    final ServerSocket socket;
    MiniServer(byte[] content, AtomicReference<String> requestedRange, boolean range, boolean truncate, boolean badRange) throws IOException {
      socket = new ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"));
      Thread thread = new Thread(() -> {
        while (!socket.isClosed()) try (Socket client = socket.accept()) {
          java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
          String header = null, line;
          while ((line = input.readLine()) != null && !line.isEmpty()) if (line.toLowerCase(java.util.Locale.ROOT).startsWith("range:")) header = line.substring(6).trim();
          requestedRange.set(header);
          int offset = range && header != null && !badRange ? Integer.parseInt(header.substring(6, header.length() - 1)) : 0;
          boolean partial = range && header != null;
          String response = "HTTP/1.1 " + (partial ? "206 Partial Content" : "200 OK") + "\r\nConnection: close\r\nContent-Length: " + (content.length - offset) + "\r\n";
          if (partial) response += "Content-Range: bytes " + offset + "-" + (content.length - 1) + "/" + content.length + "\r\n";
          client.getOutputStream().write((response + "\r\n").getBytes(StandardCharsets.US_ASCII));
          client.getOutputStream().write(content, offset, truncate ? 100 : content.length - offset);
        } catch (IOException closed) { if (!socket.isClosed()) throw new RuntimeException(closed); }
      }, "update-test-http"); thread.setDaemon(true); thread.start();
    }
    void stop(int ignored) { try { socket.close(); } catch (IOException impossible) { throw new AssertionError(impossible); } }
  }
}
