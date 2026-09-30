package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public final class ContentCollectorTest {
  @Rule public TemporaryFolder directory = new TemporaryFolder();

  private HotPackage open(String name) throws Exception {
    File file = directory.newFile();
    try (var input = getClass().getResourceAsStream("/protocol-v1/" + name)) {
      Files.copy(input, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    byte[] key;
    try (var input = getClass().getResourceAsStream("/protocol-v1/root.public.json")) {
      key = HotPackage.read(input, StrictJson.MAX_BYTES);
    }
    return new HotPackage(
        file,
        new HotPackage.Policy(
            new HotSignatures.PublicKey(StrictJson.object(key)),
            "app.luoxianlv.debug",
            "test",
            1,
            1,
            java.time.Instant.parse("2026-09-29T00:00:00Z"),
            Collections.emptySet(),
            null,
            null));
  }

  @After
  public void writable() throws Exception {
    try (var paths = Files.walk(directory.getRoot().toPath())) {
      paths.forEach(path -> path.toFile().setWritable(true, true));
    }
  }

  @Test
  public void protectsSnapshotAndSharedObjectsWhileDeletingUnreferencedContent() throws Exception {
    ContentStore store = new ContentStore(directory.newFolder("store"));
    try (var base = open("base.lxhp");
        var delta = open("delta.lxhp")) {
      var old = store.prepare(base);
      var next = store.prepare(delta);
      Path orphan = store.objectFile("e".repeat(64)).toPath();
      Files.write(orphan, new byte[100]);
      File baseline = directory.newFolder("native-baseline");
      Path sentinel = baseline.toPath().resolve("runtime.apk");
      Files.write(sentinel, new byte[] {5});
      var collector = new ContentCollector(store);
      var result = collector.collect(() -> Set.of(next.manifest.snapshotId), 0, Long.MAX_VALUE);
      assertEquals(1, result.removedSnapshots());
      assertTrue(result.beforeBytes() > result.afterBytes());
      assertFalse(Files.exists(orphan));
      assertFalse(old.directory.exists());
      store.verifySnapshotObjects(next);
      assertTrue(Files.exists(sentinel));
      assertEquals(
          0,
          collector
              .collect(() -> Set.of(next.manifest.snapshotId), 0, Long.MAX_VALUE)
              .removedSnapshots());
    }
  }

  @Test
  public void liveLeaseProtectsSnapshotUntilRetirementAndRuntimeUntilProcessEnd() throws Exception {
    ContentStore store = new ContentStore(directory.newFolder("store"));
    try (var base = open("base.lxhp")) {
      var snapshot = store.prepare(base);
      var collector = new ContentCollector(store);
      AutoCloseable module = store.pin(snapshot);
      AutoCloseable runtime = store.pinRuntime(snapshot.manifest.runtime.sha256);
      File runtimeDirectory = store.runtimeDirectory(snapshot.manifest.runtime.sha256);
      Files.write(runtimeDirectory.toPath().resolve("runtime.apk"), new byte[120]);
      try {
        assertTrue(collector.collect(Set::of, 0, 0).overBudget());
        assertTrue(snapshot.directory.exists());
        store.verifySnapshotObjects(snapshot);
        module.close();
        module.close();
        collector.collect(Set::of, 0, 0);
        assertFalse(snapshot.directory.exists());
        assertTrue(runtimeDirectory.exists());
        assertTrue(store.objectFile(snapshot.manifest.runtime.sha256).exists());
        runtime.close();
        runtime.close();
        var finalResult = collector.collect(Set::of, 0, 0);
        assertEquals(0, finalResult.afterBytes());
        assertFalse(runtimeDirectory.exists());
      } finally {
        module.close();
        runtime.close();
      }
    }
  }

  @Test
  public void corruptProtectedMetadataAbortsBeforeAnyDeletion() throws Exception {
    ContentStore store = new ContentStore(directory.newFolder("store"));
    try (var base = open("base.lxhp");
        var delta = open("delta.lxhp")) {
      var old = store.prepare(base);
      var next = store.prepare(delta);
      Files.write(next.directory.toPath().resolve("manifest.json"), new byte[] {0});
      assertThrows(
          Exception.class,
          () -> new ContentCollector(store).collect(() -> Set.of(next.manifest.snapshotId), 0, 0));
      assertTrue(old.directory.exists());
      store.verifySnapshotObjects(old);
    }
  }

  @Test
  public void sameSizeProtectedObjectCorruptionAbortsBeforeSweep() throws Exception {
    ContentStore store = new ContentStore(directory.newFolder("store"));
    try (var base = open("base.lxhp")) {
      var snapshot = store.prepare(base);
      File object = store.objectFile(snapshot.manifest.business.sha256);
      byte[] corrupt = Files.readAllBytes(object.toPath());
      corrupt[0] ^= 1;
      assertTrue(object.setWritable(true, true));
      Files.write(object.toPath(), corrupt);
      Path orphan = store.objectFile("f".repeat(64)).toPath();
      Files.write(orphan, new byte[] {1});
      assertThrows(
          Exception.class,
          () ->
              new ContentCollector(store)
                  .collect(() -> Set.of(snapshot.manifest.snapshotId), 0, 0));
      assertTrue(Files.exists(orphan));
      assertTrue(snapshot.directory.exists());
    }
  }

  @Test
  public void softBudgetDropsExtraHistoryButNeverProtectedRecoveryVersion() throws Exception {
    ContentStore store = new ContentStore(directory.newFolder("store"));
    try (var base = open("base.lxhp");
        var delta = open("delta.lxhp")) {
      var old = store.prepare(base);
      var next = store.prepare(delta);
      Files.write(next.directory.toPath().resolve("business.apk"), new byte[1000]);
      var collector = new ContentCollector(store);
      assertEquals(
          0,
          collector
              .collect(() -> Set.of(old.manifest.snapshotId), 2, Long.MAX_VALUE)
              .removedSnapshots());
      var result = collector.collect(() -> Set.of(old.manifest.snapshotId), 2, 0);
      assertTrue(result.overBudget());
      assertEquals(1, result.removedSnapshots());
      assertFalse(next.directory.exists());
      store.verifySnapshotObjects(old);
    }
  }

  @Test
  public void prepareLockDefersCleanupAndUnknownFilesRemainUntouched() throws Exception {
    ContentStore store = new ContentStore(directory.newFolder("store"));
    Path unknown = store.rootDirectory().toPath().resolve("objects/note.txt");
    Files.write(unknown, new byte[] {7});
    try (var channel =
            FileChannel.open(
                store.rootDirectory().toPath().resolve("prepare.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        var lock = channel.lock()) {
      assertThrows(Exception.class, () -> new ContentCollector(store).collect(Set::of, 0, 0));
    }
    assertTrue(new ContentCollector(store).collect(Set::of, 0, 0).overBudget());
    assertArrayEquals(new byte[] {7}, Files.readAllBytes(unknown));
  }

  @Test
  public void symbolicLinkCannotDeleteOutsideOrPartiallySweep() throws Exception {
    ContentStore store = new ContentStore(directory.newFolder("store"));
    Path outside = directory.newFile("outside.txt").toPath();
    Files.write(outside, new byte[] {9});
    Path link = store.objectFile("e".repeat(64)).toPath();
    try {
      Files.createSymbolicLink(link, outside);
    } catch (UnsupportedOperationException | java.io.IOException denied) {
      Assume.assumeNoException("平台不允许创建符号链接", denied);
    }
    Path orphan = store.objectFile("f".repeat(64)).toPath();
    Files.write(orphan, new byte[] {1});
    assertThrows(Exception.class, () -> new ContentCollector(store).collect(Set::of, 0, 0));
    assertTrue(Files.exists(orphan));
    assertArrayEquals(new byte[] {9}, Files.readAllBytes(outside));
    Files.delete(link);
  }
}
