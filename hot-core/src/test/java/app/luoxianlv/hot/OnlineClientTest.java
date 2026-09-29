package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class OnlineClientTest {
  @Rule public TemporaryFolder directory = new TemporaryFolder();

  private byte[] read(String name) throws Exception {
    try (InputStream input = getClass().getResourceAsStream("/protocol-v1/" + name)) {
      return HotPackage.read(input, StrictJson.MAX_BYTES);
    }
  }

  private HotSignatures.PublicKey root() throws Exception {
    return new HotSignatures.PublicKey(StrictJson.object(read("root.public.json")));
  }

  private HotPackage archive(String name) throws Exception {
    File file = directory.newFile();
    Files.write(file.toPath(), read(name));
    return new HotPackage(
        file,
        new HotPackage.Policy(
            root(),
            "app.luoxianlv.debug",
            "test",
            1,
            1,
            Instant.parse("2026-09-29T00:00:00Z"),
            Collections.emptySet(),
            null,
            null));
  }

  private final class Fixture implements AutoCloseable {
    final AtomicBoolean paused = new AtomicBoolean(), invalidSize = new AtomicBoolean();
    final AtomicInteger objectRequests = new AtomicInteger(), activations = new AtomicInteger();
    final AtomicLong clock = new AtomicLong(1000);
    final InstallationIdentity identity =
        InstallationIdentity.open(directory.newFolder(), "app.luoxianlv.debug", "test");
    final ContentStore store = new ContentStore(directory.newFolder());
    final TrustStore trust = new TrustStore(directory.newFolder(), root());
    final ActivationJournal journal = new ActivationJournal(directory.newFolder());
    final ContentQuarantine quarantine = new ContentQuarantine(directory.newFolder());
    final DownloadBudget budget = new DownloadBudget(directory.newFolder());
    final ObjectDownloader downloads = new ObjectDownloader(directory.newFolder(), budget);
    final ActivationController controller =
        new ActivationController(journal, trust, quarantine, 1, clock::get);
    final SignedSnapshot signed;
    final Map<String, byte[]> objects = new HashMap<>();
    final LoopbackHttp server;
    final UpdateClient client;

    Fixture() throws Exception {
      try (HotPackage target = archive("target.lxhp")) {
        signed = target.metadata();
        for (String hash : target.included) {
          ByteArrayOutputStream output = new ByteArrayOutputStream();
          target.copyObject(hash, output);
          objects.put(hash, output.toByteArray());
        }
      }
      server =
          new LoopbackHttp(
              request -> {
                if (request.path.endsWith("/installations")) {
                  StrictJson.Obj registration = StrictJson.object(request.body);
                  assertTrue(HotManifest.validHash(registration.string("secret")));
                  return json(
                      JsonWire.fields(
                          "installationId",
                          registration.string("installationId"),
                          "credentialFormat",
                          "Bearer <installationId>.<secret>",
                          "scope",
                          "installation"));
                }
                assertTrue(
                    request.headers.get("authorization").equals("Bearer " + identity.credential()));
                if (request.path.endsWith("/check")) {
                  Map<String, Object> reply =
                      JsonWire.fields(
                          "decision",
                          paused.get() ? "paused" : "candidate",
                          "channelRevision",
                          paused.get() ? 2 : 1,
                          "trustVersion",
                          1,
                          "trust",
                          text(signed.trustBytes()),
                          "trustSignature",
                          text(signed.trustSignature()));
                  if (!paused.get()) {
                    reply.put("snapshotId", signed.manifest.snapshotId);
                    reply.put("releaseId", "test-release");
                    reply.put(
                        "candidate",
                        JsonWire.fields(
                            "manifest",
                            text(signed.manifestBytes()),
                            "manifestSignature",
                            text(signed.manifestSignature()),
                            "trust",
                            text(signed.trustBytes()),
                            "trustSignature",
                            text(signed.trustSignature())));
                    Map<String, Long> catalogue = new HashMap<>(signed.manifest.objects);
                    if (invalidSize.get()) catalogue.put(signed.manifest.business.sha256, 999L);
                    reply.put("objects", catalogue);
                  }
                  return json(reply);
                }
                if (request.path.contains("/objects/")) {
                  objectRequests.incrementAndGet();
                  String hash =
                      request.path.substring(request.path.indexOf("/objects/") + 9).split("\\?")[0];
                  byte[] bytes = objects.get(hash);
                  assertNotNull(bytes);
                  return LoopbackHttp.reply(200, "", bytes);
                }
                activations.incrementAndGet();
                return LoopbackHttp.reply(
                    422,
                    "",
                    JsonWire.encode(JsonWire.fields("code", "paused", "message", "测试拒绝激活")));
              });
      HotApiClient api =
          new HotApiClient(server.origin(), identity, 1, HotSignatures.hash(new byte[] {4}), true);
      client =
          new UpdateClient(
              api,
              root(),
              store,
              trust,
              journal,
              controller,
              quarantine,
              downloads,
              budget,
              Collections.emptySet(),
              clock::get);
    }

    @Override
    public void close() throws Exception {
      server.close();
    }

    byte[] json(Map<String, ?> value) throws Exception {
      return LoopbackHttp.reply(
          200,
          "X-Lxhot-Time: " + Instant.parse("2026-09-29T00:00:00Z").getEpochSecond() + "\r\n",
          JsonWire.encode(value));
    }
  }

  private static String text(byte[] raw) {
    return new String(raw, StandardCharsets.UTF_8);
  }

  @Test
  public void actualHttpPreparationReusesObjectsAndPausedCandidateNeverGetsPermit()
      throws Exception {
    try (Fixture f = new Fixture()) {
      UpdateClient.PreparedUpdate prepared = f.client.prepare(1, () -> false, () -> false);
      assertEquals(f.signed.manifest.snapshotId, prepared.snapshot.manifest.snapshotId);
      f.store.verifySnapshotObjects(prepared.snapshot);
      assertEquals(ActivationJournal.Phase.STABLE, f.journal.state().phase);
      assertEquals("", f.journal.state().active);
      assertEquals(2, f.objectRequests.get());
      assertNotNull(f.client.prepare(1, () -> false, () -> false));
      assertEquals(2, f.objectRequests.get());
      f.paused.set(true);
      assertThrows(
          IllegalArgumentException.class, () -> f.client.authorize(prepared, 1, 123, () -> false));
      assertEquals(0, f.activations.get());
      assertEquals(2, f.journal.state().revision);
      assertEquals("", f.journal.state().active);
    }
  }

  @Test
  public void apiCannotChangeSignedObjectSizesBeforeDownloading() throws Exception {
    try (Fixture f = new Fixture()) {
      f.invalidSize.set(true);
      assertThrows(
          IllegalArgumentException.class, () -> f.client.prepare(1, () -> false, () -> false));
      assertEquals(0, f.objectRequests.get());
      assertEquals("", f.journal.state().active);
    }
  }

  @Test
  public void externalTamperingDuringImportDoesNotReplaceStableSnapshot() throws Exception {
    ContentStore store = new ContentStore(directory.newFolder());
    try (HotPackage base = archive("base.lxhp");
        HotPackage target = archive("target.lxhp")) {
      ContentStore.Snapshot previous = store.prepare(base);
      File external = directory.newFile();
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      target.copyObject(target.manifest.business.sha256, output);
      byte[] valid = output.toByteArray();
      byte[] tampered = valid.clone();
      tampered[0] ^= 1;
      Files.write(external.toPath(), tampered);
      Map<String, File> files = Collections.singletonMap(target.manifest.business.sha256, external);
      assertThrows(
          IllegalArgumentException.class,
          () -> store.prepare(new DownloadedSnapshot(target.metadata(), files)));
      store.verifySnapshotObjects(previous);
      assertThrows(Exception.class, () -> store.snapshot(target.manifest.snapshotId));
      Files.write(external.toPath(), valid);
      assertEquals(
          target.manifest.snapshotId,
          store.prepare(new DownloadedSnapshot(target.metadata(), files)).manifest.snapshotId);
    }
  }

  @Test
  public void installationScopePersistsAndCorruptionCannotResetIdentity() throws Exception {
    File root = directory.newFolder();
    InstallationIdentity first = InstallationIdentity.open(root, "app.test", "test");
    InstallationIdentity second = InstallationIdentity.open(root, "app.test", "test");
    assertEquals(first.id, second.id);
    assertTrue(first.credential().equals(second.credential()));
    assertThrows(
        IllegalArgumentException.class,
        () -> InstallationIdentity.open(root, "app.test", "production"));
    File file = new File(root, "installation.bin");
    byte[] raw = Files.readAllBytes(file.toPath());
    raw[10] ^= 1;
    Files.write(file.toPath(), raw);
    assertThrows(
        IllegalArgumentException.class, () -> InstallationIdentity.open(root, "app.test", "test"));
  }

  @Test
  public void disabledDiagnosticsNeverRegisterOrSendAndCodesCannotContainUserText()
      throws Exception {
    InstallationIdentity identity =
        InstallationIdentity.open(directory.newFolder(), "app.test", "test");
    HotApiClient api =
        new HotApiClient(
            java.net.URI.create("http://127.0.0.1:1"),
            identity,
            1,
            HotSignatures.hash(new byte[] {2}),
            true);
    HealthEvent event =
        new HealthEvent(
            java.util.UUID.randomUUID().toString(), 1, "healthy", "observed_60_seconds");
    assertNull(api.report(Collections.singletonList(event), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HealthEvent(event.attemptId, 2, "healthy", "用户谱名或链接"));
    assertThrows(
        IllegalArgumentException.class, () -> new HealthEvent(event.attemptId, 0, "healthy", "ok"));
  }

  @Test
  public void requestEncodingPreservesChineseAndRejectsAmbiguousValues() {
    String message = "中文\n\"引号\"\\😀";
    StrictJson.Obj parsed =
        StrictJson.object(JsonWire.encode(JsonWire.fields("text", message, "value", 100L)));
    assertEquals(message, parsed.string("text"));
    assertEquals(100, parsed.number("value"));
    assertThrows(
        IllegalArgumentException.class, () -> JsonWire.encode(JsonWire.fields("text", "\ud800")));
    assertThrows(
        IllegalArgumentException.class, () -> JsonWire.encode(JsonWire.fields("value", 1.0)));
  }
}
