package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Collections;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ContentStoreTest {
  @Rule public TemporaryFolder directory = new TemporaryFolder();

  @After
  public void writableTestFiles() throws Exception {
    try (java.util.stream.Stream<java.nio.file.Path> paths =
        Files.walk(directory.getRoot().toPath())) {
      paths.forEach(path -> path.toFile().setWritable(true, true));
    }
  }

  byte[] data(String name) throws Exception {
    try (java.io.InputStream input = getClass().getResourceAsStream("/protocol-v1/" + name)) {
      return HotPackage.read(input, StrictJson.MAX_BYTES);
    }
  }

  HotPackage open(String name) throws Exception {
    File file = directory.newFile();
    Files.write(file.toPath(), data(name));
    HotSignatures.PublicKey root =
        new HotSignatures.PublicKey(StrictJson.object(data("root.public.json")));
    return new HotPackage(
        file,
        new HotPackage.Policy(
            root,
            "app.luoxianlv.debug",
            "test",
            1,
            1,
            Instant.parse("2026-09-29T00:00:00Z"),
            Collections.emptySet(),
            null,
            null));
  }

  @Test
  public void fullThenDeltaPreparesCompleteImmutableSnapshot() throws Exception {
    ContentStore store = new ContentStore(directory.newFolder("internal-hot"));
    try (HotPackage base = open("base.lxhp");
        HotPackage delta = open("delta.lxhp")) {
      ContentStore.Snapshot old = store.prepare(base);
      ContentStore.Snapshot next = store.prepare(delta);
      store.verifySnapshotObjects(next);
      store.verifySnapshotObjects(old);
      assertEquals(delta.manifest.snapshotId, next.manifest.snapshotId);
      assertEquals(base.manifest.runtime.sha256, next.manifest.runtime.sha256);
      assertTrue(old.directory.isDirectory());
      assertTrue(next.directory.isDirectory());
      assertFalse(
          "准备候选不能私自激活", new File(directory.getRoot(), "internal-hot/state/active").exists());
      assertEquals(next.directory, store.prepare(delta).directory);
    }
  }

  @Test
  public void missingBaselineOrCorruptObjectCannotBeCommitted() throws Exception {
    ContentStore store = new ContentStore(directory.newFolder("internal-hot"));
    try (HotPackage base = open("base.lxhp");
        HotPackage delta = open("delta.lxhp")) {
      assertThrows(Exception.class, () -> store.prepare(delta));
      ContentStore.Snapshot stable = store.prepare(base);
      File object = store.objectFile(base.manifest.runtime.sha256);
      assertTrue(object.setWritable(true, true));
      Files.write(object.toPath(), new byte[] {1, 2, 3});
      assertThrows(Exception.class, () -> store.prepare(delta));
      assertThrows(Exception.class, () -> store.verifySnapshotObjects(stable));
      assertThrows(Exception.class, () -> store.snapshot(delta.manifest.snapshotId));
      assertTrue("候选失败必须保留稳定快照元数据", stable.directory.isDirectory());
    }
  }
}
