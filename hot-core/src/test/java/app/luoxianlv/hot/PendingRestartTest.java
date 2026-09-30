package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class PendingRestartTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @After
  public void writable() throws Exception {
    try (var paths = Files.walk(temporary.getRoot().toPath())) {
      paths.forEach(path -> path.toFile().setWritable(true, true));
    }
  }

  @Test
  public void onlyCompleteCachedSnapshotSurvivesRestartAndStaleClearCannotRemoveNewTarget()
      throws Exception {
    var vectors = new ContentStoreTest();
    vectors.directory = temporary;
    var store = new ContentStore(temporary.newFolder());
    File root = temporary.newFolder();
    var pending = new PendingRestart(root);
    assertEquals("", pending.current());
    try (var base = vectors.open("base.lxhp");
        var delta = vectors.open("delta.lxhp")) {
      var initial = store.prepare(base);
      var next = store.prepare(delta);
      pending.record(store, initial);
      var restarted = new PendingRestart(root);
      assertEquals(initial.manifest.snapshotId, restarted.current());
      restarted.record(store, next);
      assertFalse(pending.clear(initial.manifest.snapshotId));
      assertEquals(next.manifest.snapshotId, pending.current());
      File object = store.objectFile(initial.manifest.business.sha256);
      assertTrue(object.setWritable(true, true));
      Files.write(object.toPath(), new byte[] {1});
      assertThrows(Exception.class, () -> pending.record(store, initial));
      assertEquals(next.manifest.snapshotId, pending.current());
      assertTrue(pending.clear(next.manifest.snapshotId));
      assertEquals("", new PendingRestart(root).current());
    }
  }

  @Test
  public void corruptedPointerCannotBeReadAsFreshPermissionOrSilentlyReset() throws Exception {
    File root = temporary.newFolder();
    var pending = new PendingRestart(root);
    Files.write(new File(root, "pending.bin").toPath(), new byte[] {1, 2});
    assertThrows(IllegalArgumentException.class, pending::current);
    assertThrows(IllegalArgumentException.class, () -> pending.clear("a".repeat(64)));
    assertArrayEquals(
        new byte[] {1, 2}, Files.readAllBytes(new File(root, "pending.bin").toPath()));
  }
}
