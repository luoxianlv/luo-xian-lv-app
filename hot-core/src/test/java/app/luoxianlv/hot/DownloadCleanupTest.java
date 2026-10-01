package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public final class DownloadCleanupTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private ObjectDownloader downloader(boolean tracked) throws Exception {
    return new ObjectDownloader(temporary.newFolder(), new DownloadBudget(temporary.newFolder()),
        tracked ? temporary.newFolder() : null);
  }

  private String download(ObjectDownloader downloader, byte[] bytes) throws Exception {
    String hash = HotSignatures.hash(bytes);
    downloader.download(hash, hash, bytes.length,
        offset -> new ObjectDownloader.Response(offset, bytes.length, bytes.length - offset,
            new ByteArrayInputStream(bytes, (int) offset, bytes.length - (int) offset), () -> {}),
        () -> false, () -> false, () -> bytes.length - downloader.partial(hash).length());
    return hash;
  }

  private byte[] resource(String name) throws Exception {
    try (var input = getClass().getResourceAsStream("/protocol-v1/" + name)) {
      return HotPackage.read(input, StrictJson.MAX_BYTES);
    }
  }

  private HotPackage archive() throws Exception {
    File file = temporary.newFile();
    Files.write(file.toPath(), resource("base.lxhp"));
    return new HotPackage(file, new HotPackage.Policy(
        new HotSignatures.PublicKey(StrictJson.object(resource("root.public.json"))),
        "app.luoxianlv.debug", "test", 1, 1, Instant.parse("2026-09-29T00:00:00Z"),
        Set.of(), null, null));
  }

  @After public void writable() throws Exception {
    try (var paths = Files.walk(temporary.getRoot().toPath())) {
      paths.forEach(path -> path.toFile().setWritable(true, true));
    }
  }

  @Test public void verifiedInternalSnapshotAllowsFullPartsToBeRemoved() throws Exception {
    var downloads = downloader(false);
    var store = new ContentStore(temporary.newFolder());
    try (var archive = archive()) {
      for (String hash : archive.included) {
        var bytes = new ByteArrayOutputStream();
        archive.copyObject(hash, bytes);
        assertEquals(hash, download(downloads, bytes.toByteArray()));
      }
      var snapshot = store.prepare(archive);
      assertEquals(snapshot.manifest.objects.size(), downloads.committed(store, snapshot));
      store.verifySnapshotObjects(snapshot);
      for (String hash : snapshot.manifest.objects.keySet()) assertFalse(downloads.partial(hash).exists());
    }
  }

  @Test public void internalCorruptionPreventsAnyCommittedCleanup() throws Exception {
    var downloads = downloader(false);
    var store = new ContentStore(temporary.newFolder());
    try (var archive = archive()) {
      for (String hash : archive.included) {
        var bytes = new ByteArrayOutputStream(); archive.copyObject(hash, bytes);
        download(downloads, bytes.toByteArray());
      }
      var snapshot = store.prepare(archive);
      File object = store.objectFile(snapshot.manifest.business.sha256);
      assertTrue(object.setWritable(true, true));
      byte[] corrupt = Files.readAllBytes(object.toPath()); corrupt[0] ^= 1;
      Files.write(object.toPath(), corrupt); assertTrue(object.setReadOnly());
      assertThrows(IllegalArgumentException.class, () -> downloads.committed(store, snapshot));
      for (String hash : snapshot.manifest.objects.keySet()) assertTrue(downloads.partial(hash).isFile());
    }
  }

  @Test public void privateCatalogueEvictsOldBytesButProtectsCurrentCandidate() throws Exception {
    var downloads = downloader(true);
    String old = download(downloads, new byte[] {1, 2, 3});
    String current = download(downloads, new byte[] {4, 5, 6, 7});
    var result = downloads.collect(Set.of(current), 0);
    assertEquals(7, result.beforeBytes()); assertEquals(4, result.afterBytes());
    assertEquals(1, result.removed()); assertTrue(result.overBudget());
    assertFalse(downloads.partial(old).exists()); assertTrue(downloads.partial(current).isFile());
  }

  @Test public void changedBytesUnknownFilesAndExternalSidecarsArePreserved() throws Exception {
    var downloads = downloader(true);
    String changed = download(downloads, new byte[] {1, 2, 3});
    Files.write(downloads.partial(changed).toPath(), new byte[] {9, 2, 3});
    String unknown = HotSignatures.hash(new byte[] {8, 8});
    Files.write(downloads.partial(unknown).toPath(), new byte[] {8, 8});
    File sidecar = new File(downloads.directory(), unknown + ".owner.json");
    Files.writeString(sidecar.toPath(), "{\"owned\":true}", StandardCharsets.UTF_8);
    var result = downloads.collect(Set.of(), 0);
    assertEquals(0, result.removed()); assertTrue(result.overBudget());
    assertArrayEquals(new byte[] {9, 2, 3}, Files.readAllBytes(downloads.partial(changed).toPath()));
    assertTrue(downloads.partial(unknown).exists()); assertTrue(sidecar.exists());
  }

  @Test public void activeDownloadLockPreventsEvictionAndThenAllowsRetry() throws Exception {
    var downloads = downloader(true);
    String hash = download(downloads, new byte[] {1, 2, 3});
    try (var channel = FileChannel.open(downloads.partial(hash).toPath(), StandardOpenOption.WRITE);
        var lock = channel.lock()) {
      assertEquals(0, downloads.collect(Set.of(), 0).removed());
      assertTrue(downloads.partial(hash).exists());
    }
    assertEquals(1, downloads.collect(Set.of(), 0).removed());
  }

  @Test public void failedTransferKeepsPrefixAndCanResumeWithoutDoubleCounting() throws Exception {
    var downloads = downloader(true);
    byte[] bytes = {1, 2, 3, 4, 5}; String hash = HotSignatures.hash(bytes);
    InputStream input = new InputStream() {
      int count;
      @Override public int read() throws IOException { throw new IOException("block only"); }
      @Override public int read(byte[] out, int offset, int length) throws IOException {
        if (count == 2) throw new IOException("disconnected");
        int n = Math.min(length, 2 - count);
        System.arraycopy(bytes, count, out, offset, n); count += n; return n;
      }
    };
    assertThrows(IOException.class, () -> downloads.download(hash, hash, bytes.length,
        offset -> new ObjectDownloader.Response(offset, bytes.length, bytes.length - offset, input, () -> {}),
        () -> false, () -> false, () -> bytes.length - downloads.partial(hash).length()));
    assertEquals(2, downloads.partial(hash).length()); assertEquals(2, downloads.receivedBytes());
    assertEquals(0, downloads.collect(Set.of(hash), 0).removed());
    download(downloads, bytes);
    assertEquals(bytes.length, downloads.receivedBytes());
    download(downloads, bytes); assertEquals(bytes.length, downloads.receivedBytes());
  }

  @Test public void legacyConstructorDoesNotEvictUntrackedParts() throws Exception {
    var downloads = downloader(false);
    String hash = download(downloads, new byte[] {1, 2, 3});
    assertEquals(0, downloads.collect(Set.of(), 0).removed());
    assertTrue(downloads.partial(hash).exists());
  }

  @Test public void symbolicReplacementIsPreserved() throws Exception {
    var downloads = downloader(true);
    String hash = download(downloads, new byte[] {1, 2, 3});
    File destination = temporary.newFile(); Files.write(destination.toPath(), new byte[] {1, 2, 3});
    Files.delete(downloads.partial(hash).toPath());
    try { Files.createSymbolicLink(downloads.partial(hash).toPath(), destination.toPath()); }
    catch (IOException | UnsupportedOperationException denied) { Assume.assumeNoException(denied); }
    assertEquals(0, downloads.collect(Set.of(), 0).removed());
    assertTrue(Files.isSymbolicLink(downloads.partial(hash).toPath()));
    assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(destination.toPath()));
  }

  @Test public void realPathReplacementAfterFingerprintDoesNotDeleteUnknownBytes() throws Exception {
    File external = temporary.newFolder(), metadata = temporary.newFolder();
    var ready = new CountDownLatch(1); var replaced = new CountDownLatch(1);
    var downloads = new ObjectDownloader(external, new DownloadBudget(temporary.newFolder()), metadata,
        (source, target) -> {
          ready.countDown(); assertTrue(replaced.await(5, TimeUnit.SECONDS));
          Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        });
    String hash = download(downloads, new byte[] {1, 2, 3});
    byte[] sentinel = {9, 8, 7, 6}; File replacement = temporary.newFile();
    Files.write(replacement.toPath(), sentinel);
    var executor = Executors.newSingleThreadExecutor();
    try {
      var collection = executor.submit(() -> downloads.collect(Set.of(), 0));
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      try { replaceWhileHeld(replacement, downloads.partial(hash)); }
      catch (IOException denied) { Assume.assumeNoException(denied); }
      finally { replaced.countDown(); }
      assertEquals(0, collection.get(5, TimeUnit.SECONDS).removed());
      assertArrayEquals(sentinel, Files.readAllBytes(downloads.partial(hash).toPath()));
      assertPrivateCopy(metadata, hash, sentinel);
    } finally { replaced.countDown(); executor.shutdownNow(); }
  }

  @Test public void committedCleanupReverifiesAtomicallyClaimedReplacement() throws Exception {
    var ready = new CountDownLatch(1); var replaced = new CountDownLatch(1);
    File external = temporary.newFolder(), metadata = temporary.newFolder();
    String[] business = {""};
    var downloads = new ObjectDownloader(external, new DownloadBudget(temporary.newFolder()), metadata,
        (source, target) -> {
          if (source.getFileName().toString().equals(business[0] + ".part")) {
            ready.countDown(); assertTrue(replaced.await(5, TimeUnit.SECONDS));
          }
          Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        });
    var store = new ContentStore(temporary.newFolder());
    try (var archive = archive()) {
      for (String hash : archive.included) {
        var output = new ByteArrayOutputStream(); archive.copyObject(hash, output);
        download(downloads, output.toByteArray());
      }
      var snapshot = store.prepare(archive); business[0] = snapshot.manifest.business.sha256;
      byte[] sentinel = {9, 8, 7, 6}; File replacement = temporary.newFile();
      Files.write(replacement.toPath(), sentinel);
      var executor = Executors.newSingleThreadExecutor();
      try {
        var cleanup = executor.submit(() -> downloads.committed(store, snapshot));
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        try { replaceWhileHeld(replacement, downloads.partial(business[0])); }
        catch (IOException denied) { Assume.assumeNoException(denied); }
        finally { replaced.countDown(); }
        assertEquals(snapshot.manifest.objects.size() - 1, (int) cleanup.get(5, TimeUnit.SECONDS));
        assertArrayEquals(sentinel, Files.readAllBytes(downloads.partial(business[0]).toPath()));
        assertPrivateCopy(metadata, business[0], sentinel);
        store.verifySnapshotObjects(snapshot);
      } finally { replaced.countDown(); executor.shutdownNow(); }
    }
  }

  private static void assertPrivateCopy(File root, String hash, byte[] expected) throws Exception {
    try (var paths = Files.walk(root.toPath())) {
      var copy = paths.filter(path -> path.getFileName().toString().equals(hash + ".part"))
          .findFirst().orElseThrow();
      assertArrayEquals(expected, Files.readAllBytes(copy));
    }
  }

  private static void replaceWhileHeld(File replacement, File target) throws Exception {
    try {
      Files.move(replacement.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (AccessDeniedException windowsReplaceDenied) {
      // Windows拒绝覆盖持锁句柄，但允许重命名该目录项；两步重绑仍重现同一旧句柄/新路径竞态。
      Path displaced = target.toPath().resolveSibling("displaced-" + UUID.randomUUID());
      Files.move(target.toPath(), displaced, StandardCopyOption.ATOMIC_MOVE);
      Files.move(replacement.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
    }
  }

  @Test public void unsupportedAtomicClaimPreservesCrossMountExternalParts() throws Exception {
    File external = temporary.newFolder(), metadata = temporary.newFolder();
    var downloads = new ObjectDownloader(external, new DownloadBudget(temporary.newFolder()), metadata,
        (source, target) -> { throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "EXDEV"); });
    String hash = download(downloads, new byte[] {1, 2, 3});
    assertEquals(0, downloads.collect(Set.of(), 0).removed());
    assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(downloads.partial(hash).toPath()));
    assertEquals(2, Objects.requireNonNull(metadata.list()).length);
  }

  @Test public void legacyCommittedCleanupAlsoPreservesUnsupportedAtomicMove() throws Exception {
    var downloads = new ObjectDownloader(temporary.newFolder(), new DownloadBudget(temporary.newFolder()), null,
        (source, target) -> { throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "EXDEV"); });
    var store = new ContentStore(temporary.newFolder());
    try (var archive = archive()) {
      for (String hash : archive.included) {
        var output = new ByteArrayOutputStream(); archive.copyObject(hash, output);
        download(downloads, output.toByteArray());
      }
      var snapshot = store.prepare(archive);
      assertEquals(0, downloads.committed(store, snapshot));
      for (String hash : snapshot.manifest.objects.keySet()) assertTrue(downloads.partial(hash).isFile());
      store.verifySnapshotObjects(snapshot);
    }
  }

  @Test public void unknownClaimCannotOverwriteReusedOriginalPathOnRestore() throws Exception {
    File external = temporary.newFolder(), metadata = temporary.newFolder();
    byte[] claimedUnknown = {9, 8, 7, 6}, reusedUnknown = {6, 5, 4};
    var downloads = new ObjectDownloader(external, new DownloadBudget(temporary.newFolder()), metadata,
        (source, target) -> {
          Path displaced = source.resolveSibling("displaced-" + UUID.randomUUID());
          Files.move(source, displaced, StandardCopyOption.ATOMIC_MOVE);
          Files.write(source, claimedUnknown, StandardOpenOption.CREATE_NEW);
          Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
          Files.write(source, reusedUnknown, StandardOpenOption.CREATE_NEW);
        });
    String hash = download(downloads, new byte[] {1, 2, 3});
    assertEquals(0, downloads.collect(Set.of(), 0).removed());
    assertArrayEquals(reusedUnknown, Files.readAllBytes(downloads.partial(hash).toPath()));
    assertPrivateCopy(metadata, hash, claimedUnknown);
  }

  @Test public void internalDirectorySwitchUsesNewCatalogueAndLeavesOldScopeIntact() throws Exception {
    File external = temporary.newFolder(), internal = temporary.newFolder();
    File oldRecords = temporary.newFolder(), privateRecords = temporary.newFolder();
    var budget = new DownloadBudget(temporary.newFolder());
    var old = new ObjectDownloader(external, budget, oldRecords);
    String oldHash = download(old, new byte[] {1, 2, 3});
    byte[] oldCatalogue = Files.readAllBytes(new File(oldRecords, "downloads.bin").toPath());
    var wrongScope = new ObjectDownloader(internal, budget, oldRecords);
    String newHash = download(wrongScope, new byte[] {4, 5, 6});
    assertEquals(0, wrongScope.collect(Set.of(), 0).removed());
    assertTrue(wrongScope.partial(newHash).isFile());
    var freshScope = new ObjectDownloader(internal, budget, privateRecords);
    download(freshScope, new byte[] {4, 5, 6});
    assertEquals(1, freshScope.collect(Set.of(), 0).removed());
    assertFalse(freshScope.partial(newHash).exists()); assertTrue(old.partial(oldHash).exists());
    assertArrayEquals(oldCatalogue, Files.readAllBytes(new File(oldRecords, "downloads.bin").toPath()));
  }
}
