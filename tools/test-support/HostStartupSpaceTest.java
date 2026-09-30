package app.luoxianlv.host;

import static org.junit.Assert.*;

import app.luoxianlv.hot.*;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import sun.misc.Unsafe;

/** 仅独立 JVM runner 使用，不能编入 Android 测试；选择、验签、缓存和日志使用生产实现。 */
public final class HostStartupSpaceTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @After
  public void makeFixtureWritable() throws Exception {
    try (var paths = Files.walk(temporary.getRoot().toPath())) {
      paths.forEach(path -> path.toFile().setWritable(true, true));
    }
  }

  private byte[] vector(String name) throws Exception {
    try (var input = getClass().getResourceAsStream("/protocol-v1/" + name)) {
      assertNotNull("公开协议向量缺失", input);
      byte[] bytes = input.readNBytes(StrictJson.MAX_BYTES + 1);
      assertTrue("公开协议向量超限", bytes.length <= StrictJson.MAX_BYTES);
      return bytes;
    }
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field access = Unsafe.class.getDeclaredField("theUnsafe");
    access.setAccessible(true);
    return type.cast(((Unsafe) access.get(null)).allocateInstance(type));
  }

  private static void field(Object owner, String name, Object value) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private static void loaderMode(boolean deferred) throws Exception {
    // 测试替身只由独立 runner 生成，生产源码不添加测试开关。
    NativeLoader.class.getField("testOnlySpaceDeferred").setBoolean(null, deferred);
    NativeLoader.class.getField("testOnlyAttempts").set(null, new java.util.ArrayList<String>());
    NativeLoader.class.getField("testOnlyDiscardCalls").setInt(null, 0);
  }

  @SuppressWarnings("unchecked")
  private static List<String> attempts() throws Exception {
    return (List<String>) NativeLoader.class.getField("testOnlyAttempts").get(null);
  }

  private final class Fixture {
    final ContentStore store = new ContentStore(temporary.newFolder("content"));
    final File stateDirectory = temporary.newFolder("state");
    final ActivationJournal journal = new ActivationJournal(stateDirectory);
    final ContentQuarantine quarantine = new ContentQuarantine(temporary.newFolder("quarantine"));
    final HotSignatures.PublicKey root =
        new HotSignatures.PublicKey(StrictJson.object(vector("root.public.json")));
    final TrustStore trust = new TrustStore(temporary.newFolder("trust"), root);
    final ContentStore.Snapshot base, next;
    final HostStartup startup;

    Fixture() throws Exception {
      try (var pack = open("base.lxhp"); var delta = open("delta.lxhp")) {
        base = store.prepare(pack);
        next = store.prepare(delta);
        trust.accept(pack.trustBytes(), pack.trustSignature(), journal,
            Instant.parse("2026-09-29T00:00:00Z"));
      }
      confirm(base, 1);
      confirm(next, 2);
      var config = allocate(HostUpdateConfig.class);
      field(config, "environment", "test");
      field(config, "hostContract", 1L);
      startup = allocate(HostStartup.class);
      field(startup, "config", config);
      field(startup, "store", store);
      field(startup, "journal", journal);
      field(startup, "trust", trust);
      field(startup, "quarantine", quarantine);
      field(startup, "loader", new NativeLoader(null, store, quarantine, 1, Set.of()));
    }

    private HotPackage open(String name) throws Exception {
      File file = temporary.newFile();
      Files.write(file.toPath(), vector(name));
      return new HotPackage(file, new HotPackage.Policy(root, "app.luoxianlv.debug", "test", 1,
          1, Instant.parse("2026-09-29T00:00:00Z"), Set.of(), null, null));
    }

    private void confirm(ContentStore.Snapshot snapshot, long revision) throws Exception {
      String attempt = journal.begin(snapshot.manifest.snapshotId, revision, 1, 123, 1000);
      journal.firstFrame(attempt);
      journal.healthy(attempt, 60000);
    }
  }

  private static void sameState(ActivationJournal.State expected, ActivationJournal.State actual) {
    assertEquals(expected.stable, actual.stable);
    assertEquals(expected.active, actual.active);
    assertEquals(expected.previousStable, actual.previousStable);
    assertEquals(expected.attempt, actual.attempt);
    assertEquals(expected.stableAttempt, actual.stableAttempt);
    assertEquals(expected.previousStableAttempt, actual.previousStableAttempt);
    assertEquals(expected.revision, actual.revision);
    assertEquals(expected.trustVersion, actual.trustVersion);
    assertEquals(expected.phase, actual.phase);
    assertEquals(expected.quarantine, actual.quarantine);
  }

  @Test
  public void deferredUsesBaselineForThisLaunchWithoutChangingPersistedStableSelection()
      throws Exception {
    Fixture f = new Fixture();
    loaderMode(true);
    var before = f.journal.state();
    var file = new File(f.stateDirectory, "activation.bin").toPath();
    byte[] persisted = Files.readAllBytes(file);
    assertNull("null 是 Bootstrap 使用内置组合的选择信号", f.startup.prepareStable());
    assertNull("再次尝试仍应保留同一稳定版本", f.startup.prepareStable());
    assertEquals(List.of(f.next.manifest.snapshotId, f.next.manifest.snapshotId), attempts());
    assertEquals(0, NativeLoader.class.getField("testOnlyDiscardCalls").getInt(null));
    sameState(before, f.journal.state());
    sameState(before, new ActivationJournal(f.stateDirectory).state());
    assertArrayEquals("空间延期不应产生新的日志写入", persisted, Files.readAllBytes(file));
    f.trust.verifyStable(f.next, f.journal.state());
    f.store.verifySnapshotObjects(f.next);
  }

  @Test
  public void corruptStableObjectStillFallsBackToPreviousWholeSnapshot() throws Exception {
    Fixture f = new Fixture();
    loaderMode(false);
    File broken = f.store.objectFile(f.next.manifest.business.sha256);
    assertTrue(broken.setWritable(true, true));
    Files.write(broken.toPath(), new byte[] {1});
    var selected = f.startup.prepareStable();
    assertNotNull(selected);
    assertEquals(f.base.manifest.snapshotId, selected.manifest.snapshotId);
    assertEquals(List.of(f.next.manifest.snapshotId, f.base.manifest.snapshotId), attempts());
    assertEquals(1, NativeLoader.class.getField("testOnlyDiscardCalls").getInt(null));
    var persisted = new ActivationJournal(f.stateDirectory).state();
    assertEquals(f.base.manifest.snapshotId, persisted.stable);
    assertEquals(f.base.manifest.snapshotId, persisted.active);
    assertEquals("", persisted.previousStable);
    assertEquals(2, persisted.revision);
    assertEquals(1, persisted.trustVersion);
    assertTrue(persisted.quarantine.isEmpty());
    f.trust.verifyStable(f.base, persisted);
    f.store.verifySnapshotObjects(f.base);
  }
}
