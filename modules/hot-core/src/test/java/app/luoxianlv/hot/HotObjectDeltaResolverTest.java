package app.luoxianlv.hot;

import static org.junit.Assert.*;

import app.luoxianlv.update.Cancellation;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.zip.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 使用真实签名、回环 HTTP 与文件校验；注入合并器不代表 Android 隔离进程或健康验收。 */
public final class HotObjectDeltaResolverTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");
  private static final String HOST = "f".repeat(64);

  private static String text(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static String b64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static KeyPair pair() throws Exception {
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  private static Map<String, Object> publicKey(KeyPair pair, String purpose) throws Exception {
    return JsonWire.fields(
        "schema",
        1,
        "algorithm",
        HotSignatures.ALGORITHM,
        "keyId",
        HotSignatures.hash(pair.getPublic().getEncoded()),
        "purpose",
        purpose,
        "spki",
        b64(pair.getPublic().getEncoded()));
  }

  private static byte[] sign(KeyPair key, String domain, byte[] raw) throws Exception {
    Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(key.getPrivate());
    signer.update(domain.getBytes(StandardCharsets.US_ASCII));
    signer.update((byte) 0);
    signer.update(raw);
    return JsonWire.encode(
        JsonWire.fields(
            "algorithm",
            HotSignatures.ALGORITHM,
            "keyId",
            HotSignatures.hash(key.getPublic().getEncoded()),
            "signature",
            b64(signer.sign())));
  }

  private static byte[] apk(String marker, int payload, byte fill) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      for (var item :
          Map.of(
                  "assets/hot/host-contract.sha256",
                  (marker + "\n").getBytes(StandardCharsets.US_ASCII),
                  "classes.dex",
                  new byte[payload])
              .entrySet()) {
        byte[] value = item.getValue();
        if (item.getKey().equals("classes.dex")) Arrays.fill(value, fill);
        CRC32 crc = new CRC32();
        crc.update(value);
        ZipEntry entry = new ZipEntry(item.getKey());
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(value.length);
        entry.setCompressedSize(value.length);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(value);
        zip.closeEntry();
      }
    }
    return output.toByteArray();
  }

  private final class Fixture implements AutoCloseable {
    final KeyPair rootPair = pair(), contentPair = pair();
    final HotSignatures.PublicKey root =
        new HotSignatures.PublicKey(
            StrictJson.object(JsonWire.encode(publicKey(rootPair, "root"))));
    final InstallationIdentity identity =
        InstallationIdentity.open(temporary.newFolder(), "app.luoxianlv.debug", "test");
    final ContentStore store = new ContentStore(temporary.newFolder());
    final ActivationJournal journal = new ActivationJournal(temporary.newFolder());
    final TrustStore trust = new TrustStore(temporary.newFolder(), root);
    final ContentQuarantine quarantine = new ContentQuarantine(temporary.newFolder());
    final DownloadBudget budget = new DownloadBudget(temporary.newFolder());
    final ObjectDownloader downloads = new ObjectDownloader(temporary.newFolder(), budget);
    final AtomicInteger fullRequests = new AtomicInteger(),
        patchRequests = new AtomicInteger(),
        hints = new AtomicInteger(),
        merges = new AtomicInteger();
    final AtomicBoolean cancel = new AtomicBoolean();
    final byte[] runtime, base, target, patch = new byte[65536], authority, authoritySignature;
    final String runtimeHash, baseHash, targetHash, patchHash;
    final File runtimeFile, baseFile;
    final File deltaDirectory = temporary.newFolder();
    final CountDownLatch closureEntered = new CountDownLatch(1),
        closureReleased = new CountDownLatch(1);
    final SignedSnapshot candidate;
    final HotApiClient api;
    final LoopbackHttp server, cdn;
    final UpdateClient client;
    volatile boolean corruptPatch, mergeFails, cancelMerge, tamperManifest, noHint, delayedClosure;
    volatile String wrongHost = "";
    volatile long wrongVersion;

    Fixture(int targetPayload, boolean stable) throws Exception {
      this(targetPayload, stable, 19, HOST);
    }

    Fixture(int targetPayload, boolean stable, long baselineVersion, String baselineHost)
        throws Exception {
      runtime = apk(HOST, 100, (byte) 1);
      base = apk(baselineHost, 1024 * 1024, (byte) 2);
      target = apk(HOST, targetPayload, (byte) 3);
      Arrays.fill(patch, (byte) 4);
      runtimeHash = HotSignatures.hash(runtime);
      baseHash = HotSignatures.hash(base);
      targetHash = HotSignatures.hash(target);
      patchHash = HotSignatures.hash(patch);
      runtimeFile = temporary.newFile();
      baseFile = temporary.newFile();
      Files.write(runtimeFile.toPath(), runtime);
      Files.write(baseFile.toPath(), base);
      authority =
          JsonWire.encode(
              JsonWire.fields(
                  "schema",
                  1,
                  "version",
                  1,
                  "applicationId",
                  identity.applicationId,
                  "environment",
                  "test",
                  "notBefore",
                  "2026-01-01T00:00:00Z",
                  "notAfter",
                  "2027-01-01T00:00:00Z",
                  "keys",
                  List.of(
                      JsonWire.fields(
                          "key",
                          publicKey(contentPair, "content"),
                          "notBefore",
                          "2026-01-01T00:00:00Z",
                          "notAfter",
                          "2027-01-01T00:00:00Z")),
                  "revokedKeyIds",
                  List.of()));
      authoritySignature = sign(rootPair, HotSignatures.TRUST, authority);
      candidate = snapshot(targetHash, target.length, "target", 19);
      if (stable) {
        SignedSnapshot baseline = snapshot(baseHash, base.length, "stable", baselineVersion);
        store.prepare(
            new DownloadedSnapshot(baseline, Map.of(runtimeHash, runtimeFile, baseHash, baseFile)));
        String attempt = journal.begin(baseline.manifest.snapshotId, 1, 1, 42, 1);
        journal.firstFrame(attempt);
        journal.healthy(attempt, 60000);
      }
      cdn =
          new LoopbackHttp(
              request -> {
                assertFalse(
                    "Bearer reached another origin", request.headers.containsKey("authorization"));
                patchRequests.incrementAndGet();
                byte[] bytes = patch.clone();
                if (corruptPatch) bytes[0] ^= 1;
                long offset =
                    request.headers.containsKey("range")
                        ? Long.parseLong(request.headers.get("range").substring(6).replace("-", ""))
                        : 0;
                return LoopbackHttp.reply(
                    offset == 0 ? 200 : 206,
                    offset == 0
                        ? ""
                        : "Content-Range: bytes "
                            + offset
                            + "-"
                            + (bytes.length - 1)
                            + "/"
                            + bytes.length
                            + "\r\n",
                    Arrays.copyOfRange(bytes, (int) offset, bytes.length));
              });
      server =
          new LoopbackHttp(
              request -> {
                if (request.path.endsWith("/installations")) {
                  var body = StrictJson.object(request.body);
                  assertEquals(HotByteDeltaPlan.ALGORITHM, body.string("deltaCapability"));
                  return json(
                      JsonWire.fields(
                          "installationId",
                          identity.id,
                          "credentialFormat",
                          "Bearer <installationId>.<secret>",
                          "scope",
                          "installation"));
                }
                assertEquals(
                    "Bearer " + identity.credential(), request.headers.get("authorization"));
                if (request.path.endsWith("/check"))
                  return json(
                      JsonWire.fields(
                          "decision",
                          "candidate",
                          "channelRevision",
                          2,
                          "snapshotId",
                          candidate.manifest.snapshotId,
                          "releaseId",
                          "test",
                          "candidate",
                          signed(candidate),
                          "objects",
                          candidate.manifest.objects,
                          "trustVersion",
                          1,
                          "trust",
                          text(authority),
                          "trustSignature",
                          text(authoritySignature)));
                if (request.path.endsWith("/byte-deltas") && request.method.equals("POST")) {
                  hints.incrementAndGet();
                  var body = StrictJson.object(request.body);
                  assertEquals(candidate.manifest.snapshotId, body.string("snapshotId"));
                  if (!body.strings("baseHashes").contains(baseHash)) return json(Map.of());
                  if (noHint) return json(Map.of());
                  Map<String, Object> envelope = envelope();
                  if (tamperManifest)
                    envelope.put("manifest", b64("{}".getBytes(StandardCharsets.UTF_8)));
                  return json(
                      JsonWire.fields(
                          "byteDeltaV1",
                          envelope,
                          "sources",
                          List.of(
                              JsonWire.fields(
                                  "patchSha256",
                                  patchHash,
                                  "url",
                                  "/api/hot/v2/byte-deltas/"
                                      + patchHash
                                      + "?snapshotId="
                                      + candidate.manifest.snapshotId))));
                }
                if (request.path.contains("/byte-deltas/")) {
                  assertEquals("HEAD", request.method);
                  return LoopbackHttp.reply(
                      307,
                      "Location: " + cdn.origin().resolve("/private-patch") + "\r\n",
                      new byte[0]);
                }
                if (request.path.endsWith("/grant"))
                  return json(
                      JsonWire.fields(
                          "transport",
                          "local-test",
                          "method",
                          "GET",
                          "url",
                          "/api/hot/v2/objects/"
                              + targetHash
                              + "?snapshotId="
                              + candidate.manifest.snapshotId,
                          "headers",
                          Map.of(),
                          "sha256",
                          targetHash,
                          "size",
                          target.length,
                          "expiresAt",
                          NOW.getEpochSecond() + 120));
                if (request.path.contains("/objects/")) {
                  fullRequests.incrementAndGet();
                  return LoopbackHttp.reply(200, "", target);
                }
                throw new AssertionError("Unexpected path " + request.path);
              });
      api = new HotApiClient(server.origin(), identity, 1, HOST, true, 19);
      UpdateClient.LocalObjects installed =
          new UpdateClient.LocalObjects() {
            public File find(String hash, long size) {
              return hash.equals(runtimeHash)
                  ? runtimeFile
                  : !stable && hash.equals(baseHash) ? baseFile : null;
            }

            public Map<String, Long> baselines() {
              return stable
                  ? Map.of(runtimeHash, (long) runtime.length)
                  : Map.of(runtimeHash, (long) runtime.length, baseHash, (long) base.length);
            }
          };
      var resolver =
          new HotObjectDeltaResolver(
              api,
              root,
              store,
              trust,
              journal,
              installed,
              (old, diff, out, size, cancellation) -> {
                merges.incrementAndGet();
                assertArrayEquals(base, Files.readAllBytes(old.toPath()));
                assertArrayEquals(patch, Files.readAllBytes(diff.toPath()));
                if (stable) {
                  assertTrue(
                      ContentLeases.snapshots(store.rootDirectory())
                          .contains(journal.state().stable));
                  try {
                    assertEquals(
                        0, new ContentCollector(store).collect(Set::of, 0, 0).removedSnapshots());
                  } catch (Exception failure) {
                    throw new IOException("GC fixture failed", failure);
                  }
                  assertTrue(old.isFile());
                }
                if (cancelMerge) {
                  if (delayedClosure)
                    cancellation.onCancel(
                        () -> {
                          closureEntered.countDown();
                          try {
                            closureReleased.await();
                          } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                          }
                        });
                  cancel.set(true);
                  cancellation.cancel();
                  throw new Cancellation.CancelledException();
                }
                if (mergeFails) throw new IOException("Injected worker failure");
                Files.write(out.toPath(), target);
              },
              deltaDirectory);
      var controller = new ActivationController(journal, trust, quarantine, 1, () -> 1000);
      client =
          new UpdateClient(
              api,
              root,
              store,
              trust,
              journal,
              controller,
              quarantine,
              downloads,
              budget,
              Set.of(),
              () -> 1000,
              new PreparationSpace(),
              installed,
              resolver);
    }

    SignedSnapshot snapshot(String business, long size, String label, long version)
        throws Exception {
      byte[] raw =
          JsonWire.encode(
              JsonWire.fields(
                  "schema",
                  1,
                  "kind",
                  "hot",
                  "applicationId",
                  identity.applicationId,
                  "environment",
                  "test",
                  "label",
                  label,
                  "createdAt",
                  NOW.toString(),
                  "hostContract",
                  JsonWire.fields("min", 1, "max", 1),
                  "targetVersionCode",
                  version,
                  "runtimeAbi",
                  "fixture-v1",
                  "activation",
                  "live",
                  "stateSchema",
                  JsonWire.fields("current", 1, "readable", JsonWire.fields("min", 1, "max", 1)),
                  "artifacts",
                  List.of(
                      JsonWire.fields(
                          "id",
                          "runtime",
                          "role",
                          "runtime",
                          "sha256",
                          runtimeHash,
                          "size",
                          runtime.length,
                          "requires",
                          List.of()),
                      JsonWire.fields(
                          "id",
                          "business",
                          "role",
                          "business",
                          "sha256",
                          business,
                          "size",
                          size,
                          "requires",
                          List.of("runtime"),
                          "entryClass",
                          "app.fixture.Entry"))));
      return new SignedSnapshot(
          raw,
          sign(contentPair, HotSignatures.MANIFEST, raw),
          authority,
          authoritySignature,
          new HotPackage.Policy(
              root, identity.applicationId, "test", 1, 1, NOW, Set.of(), null, null));
    }

    Map<String, Object> signed(SignedSnapshot value) {
      return JsonWire.fields(
          "manifest",
          text(value.manifestBytes()),
          "manifestSignature",
          text(value.manifestSignature()),
          "trust",
          text(value.trustBytes()),
          "trustSignature",
          text(value.trustSignature()));
    }

    Map<String, Object> envelope() throws Exception {
      byte[] raw =
          JsonWire.encode(
              JsonWire.fields(
                  "schema",
                  1,
                  "type",
                  "hot-object-deltas",
                  "snapshotId",
                  candidate.manifest.snapshotId,
                  "applicationId",
                  identity.applicationId,
                  "environment",
                  "test",
                  "targetVersionCode",
                  wrongVersion == 0 ? 19 : wrongVersion,
                  "hostFingerprint",
                  wrongHost.isEmpty() ? HOST : wrongHost,
                  "deltas",
                  List.of(
                      JsonWire.fields(
                          "baseSha256",
                          baseHash,
                          "baseSize",
                          base.length,
                          "targetSha256",
                          targetHash,
                          "targetSize",
                          target.length,
                          "patch",
                          JsonWire.fields(
                              "algorithm",
                              HotByteDeltaPlan.ALGORITHM,
                              "sha256",
                              patchHash,
                              "size",
                              patch.length,
                              "object",
                              "hot/v2/objects/" + patchHash)))));
      return JsonWire.fields(
          "schema",
          1,
          "manifest",
          b64(raw),
          "signature",
          b64(sign(contentPair, HotSignatures.TRANSPORT, raw)),
          "trust",
          b64(authority),
          "trustSignature",
          b64(authoritySignature));
    }

    byte[] json(Map<String, ?> value) throws Exception {
      return LoopbackHttp.reply(
          200, "X-Lxhot-Time: " + NOW.getEpochSecond() + "\r\n", JsonWire.encode(value));
    }

    UpdateClient.PreparedUpdate prepare() throws Exception {
      return client.prepare(1, () -> true, cancel::get);
    }

    public void close() throws Exception {
      try {
        server.close();
      } finally {
        cdn.close();
      }
    }
  }

  @Test
  public void patchTrafficCanPrepareFullTargetAboveMeteredLimit() throws Exception {
    try (Fixture f = new Fixture(21 * 1024 * 1024, false)) {
      var result = f.prepare();
      assertNotNull(result);
      f.store.verifySnapshotObjects(result.snapshot);
      assertEquals(65536, f.downloads.receivedBytes());
      assertEquals(65536, f.budget.used(f.candidate.manifest.contentId));
      assertEquals(0, f.fullRequests.get());
      assertEquals(1, f.merges.get());
      assertEquals("", f.journal.state().active);
      assertArrayEquals(f.base, Files.readAllBytes(f.baseFile.toPath()));
    }
  }

  @Test
  public void resumesPatchThroughFreshGrantWithoutLeakingBearerOrLosingRange() throws Exception {
    try (Fixture f = new Fixture(1024 * 1024, false)) {
      Files.write(f.downloads.partial(f.patchHash).toPath(), Arrays.copyOf(f.patch, 32768));
      assertNotNull(f.prepare());
      assertEquals(32768, f.downloads.receivedBytes());
      assertEquals(32768, f.budget.used(f.candidate.manifest.contentId));
      assertEquals(0, f.fullRequests.get());
    }
  }

  @Test
  public void mergeOrPatchFailureFallsBackToSameFullTargetAndRetainsBaseline() throws Exception {
    for (boolean corrupt : new boolean[] {false, true})
      try (Fixture f = new Fixture(1024 * 1024, false)) {
        f.corruptPatch = corrupt;
        f.mergeFails = !corrupt;
        var prepared = f.prepare();
        f.store.verifySnapshotObjects(prepared.snapshot);
        assertEquals(1, f.fullRequests.get());
        assertEquals(f.patch.length + f.target.length, f.downloads.receivedBytes());
        assertArrayEquals(f.base, Files.readAllBytes(f.baseFile.toPath()));
      }
  }

  @Test
  public void cancellationNeverFallsBackOrCommitsCandidate() throws Exception {
    try (Fixture f = new Fixture(1024 * 1024, false)) {
      f.cancelMerge = true;
      assertThrows(UpdateCancellation.Cancelled.class, f::prepare);
      assertEquals(0, f.fullRequests.get());
      assertEquals("", f.journal.state().active);
      assertFalse(f.store.objectFile(f.targetHash).exists());
    }
  }

  @Test
  public void cancellationClosureFinishesBeforeTransportLockAndTaskAreReleased() throws Exception {
    try (Fixture f = new Fixture(1024 * 1024, false)) {
      f.cancelMerge = true;
      f.delayedClosure = true;
      AtomicReference<Throwable> failure = new AtomicReference<>();
      CountDownLatch finished = new CountDownLatch(1);
      Thread worker =
          new Thread(
              () -> {
                try {
                  f.prepare();
                } catch (Throwable stopped) {
                  failure.set(stopped);
                } finally {
                  finished.countDown();
                }
              },
              "hot-closure-fixture");
      worker.start();
      try {
        assertTrue(f.closureEntered.await(3, TimeUnit.SECONDS));
        assertEquals(1, finished.getCount());
        try (var lock =
            FileChannel.open(
                new File(f.deltaDirectory, "transport.lock").toPath(), StandardOpenOption.WRITE)) {
          assertThrows(OverlappingFileLockException.class, lock::tryLock);
        }
      } finally {
        f.closureReleased.countDown();
      }
      assertTrue(finished.await(3, TimeUnit.SECONDS));
      worker.join();
      assertTrue(failure.get() instanceof UpdateCancellation.Cancelled);
      assertEquals(0, f.fullRequests.get());
      try (var channel =
              FileChannel.open(
                  new File(f.deltaDirectory, "transport.lock").toPath(), StandardOpenOption.WRITE);
          var lock = channel.tryLock()) {
        assertNotNull(lock);
      }
    }
  }

  @Test
  public void invalidMetadataVersionAndFingerprintRejectWithoutObjectDownloads() throws Exception {
    for (int mode = 0; mode < 3; mode++)
      try (Fixture f = new Fixture(1024 * 1024, false)) {
        f.tamperManifest = mode == 0;
        f.wrongVersion = mode == 1 ? 18 : 0;
        f.wrongHost = mode == 2 ? "e".repeat(64) : "";
        assertThrows(IllegalArgumentException.class, f::prepare);
        assertEquals(0, f.patchRequests.get());
        assertEquals(0, f.fullRequests.get());
        assertFalse(f.store.objectFile(f.targetHash).exists());
      }
  }

  @Test
  public void currentStableContractAndVersionProvidePinnedAccurateBaselines() throws Exception {
    try (Fixture f = new Fixture(1024 * 1024, true)) {
      String stable = f.journal.state().stable;
      assertNotNull(f.prepare());
      assertEquals(1, f.patchRequests.get());
      assertEquals(0, f.fullRequests.get());
      assertEquals(stable, f.journal.state().stable);
      f.store.verifySnapshotObjects(f.store.snapshot(stable));
      assertFalse(ContentLeases.snapshots(f.store.rootDirectory()).contains(stable));
    }
  }

  @Test
  public void stableFromOtherVersionOrSdkCannotSupplyPatchBaseline() throws Exception {
    for (boolean version : new boolean[] {true, false})
      try (Fixture f =
          new Fixture(1024 * 1024, true, version ? 18 : 19, version ? HOST : "e".repeat(64))) {
        assertNotNull(f.prepare());
        assertEquals(0, f.patchRequests.get());
        assertEquals(1, f.fullRequests.get());
        assertEquals(0, f.merges.get());
      }
  }

  @Test
  public void absentOptionalHintRetainsFullObjectCompatibility() throws Exception {
    try (Fixture f = new Fixture(1024 * 1024, false)) {
      f.noHint = true;
      assertNotNull(f.prepare());
      assertEquals(0, f.patchRequests.get());
      assertEquals(1, f.fullRequests.get());
    }
  }
}
