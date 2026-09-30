package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class StableSnapshotTest {
  @Rule public TemporaryFolder directory = new TemporaryFolder();

  @After
  public void writableFiles() throws Exception {
    try (var paths = Files.walk(directory.getRoot().toPath())) {
      paths.forEach(path -> path.toFile().setWritable(true, true));
    }
  }

  final class Fixture {
    final ContentStoreTest vectors = new ContentStoreTest();
    final ContentStore store = new ContentStore(directory.newFolder());
    final File journalRoot = directory.newFolder();
    final ActivationJournal journal = new ActivationJournal(journalRoot);
    final File trustRoot = directory.newFolder();
    final HotSignatures.PublicKey root =
        new HotSignatures.PublicKey(StrictJson.object(vectors.data("root.public.json")));
    final TrustStore trust = new TrustStore(trustRoot, root);
    final ContentStore.Snapshot base, next;

    Fixture() throws Exception {
      vectors.directory = directory;
      try (var pack = vectors.open("base.lxhp");
          var delta = vectors.open("delta.lxhp")) {
        base = store.prepare(pack);
        next = store.prepare(delta);
        trust.accept(
            pack.trustBytes(),
            pack.trustSignature(),
            journal,
            Instant.parse("2026-09-29T00:00:00Z"));
      }
    }

    void confirm(ContentStore.Snapshot snapshot, long revision) throws Exception {
      String attempt = journal.begin(snapshot.manifest.snapshotId, revision, 1, 123, 1000);
      journal.firstFrame(attempt);
      journal.healthy(attempt, 60000);
    }
  }

  @Test
  public void onlyPersistedStableCanUseHistoricalSignatureWithoutFreshPermission()
      throws Exception {
    Fixture f = new Fixture();
    assertThrows(
        IllegalArgumentException.class, () -> f.trust.verifyStable(f.base, f.journal.state()));
    f.confirm(f.base, 1);
    var restarted = new ActivationJournal(f.journalRoot);
    var persistedTrust = new TrustStore(f.trustRoot, f.root);
    assertThrows(
        IllegalArgumentException.class,
        () -> persistedTrust.current().authority.current(1, Instant.parse("2031-01-01T00:00:00Z")));
    persistedTrust.verifyStable(f.base, restarted.state());
    assertThrows(
        IllegalArgumentException.class,
        () -> persistedTrust.verifyStable(f.next, restarted.state()));
    restarted.begin(f.next.manifest.snapshotId, 2, 1, 124, 2000);
    assertThrows(
        IllegalArgumentException.class,
        () -> persistedTrust.verifyStable(f.next, restarted.state()));
    assertThrows(
        IllegalArgumentException.class,
        () -> persistedTrust.verifyStable(f.base, restarted.state()));
  }

  @Test
  public void signatureDamageAndTrustFloorAreRejectedEvenForConfirmedVersion() throws Exception {
    Fixture f = new Fixture();
    f.confirm(f.base, 1);
    File signature = new File(f.base.directory, "manifest.sig.json");
    byte[] good = Files.readAllBytes(signature.toPath());
    byte[] changed = good.clone();
    for (int i = 0; i < changed.length; i++)
      if (changed[i] == 'A') {
        changed[i] = 'B';
        break;
      }
    if (java.util.Arrays.equals(good, changed)) changed[changed.length - 2] ^= 1;
    Files.write(signature.toPath(), changed);
    assertThrows(Exception.class, () -> f.trust.verifyStable(f.base, f.journal.state()));
    Files.write(signature.toPath(), good);
    f.trust.verifyStable(f.base, f.journal.state());
    f.journal.observeVersions(1, 2);
    assertThrows(
        IllegalArgumentException.class, () -> f.trust.verifyStable(f.base, f.journal.state()));
  }

  @Test
  public void damagedCacheFallsBackWithoutQuarantiningCodeOrResettingFloors() throws Exception {
    Fixture f = new Fixture();
    f.confirm(f.base, 1);
    f.confirm(f.next, 2);
    File object = f.store.objectFile(f.next.manifest.business.sha256);
    assertTrue(object.setWritable(true, true));
    Files.write(object.toPath(), new byte[] {1});
    assertThrows(Exception.class, () -> f.store.verifySnapshotObjects(f.next));
    f.journal.unavailableStable(f.next.manifest.snapshotId);
    var restarted = new ActivationJournal(f.journalRoot);
    assertEquals(f.base.manifest.snapshotId, restarted.state().stable);
    assertEquals(2, restarted.state().revision);
    assertEquals(1, restarted.state().trustVersion);
    assertTrue(restarted.state().quarantine.isEmpty());
    f.trust.verifyStable(f.base, restarted.state());
    f.store.verifySnapshotObjects(f.base);
    restarted.unavailableStable(f.base.manifest.snapshotId);
    assertEquals("", restarted.state().stable);
    assertEquals(2, restarted.state().revision);
    assertThrows(
        IllegalArgumentException.class,
        () -> restarted.unavailableStable(f.base.manifest.snapshotId));
  }
}
