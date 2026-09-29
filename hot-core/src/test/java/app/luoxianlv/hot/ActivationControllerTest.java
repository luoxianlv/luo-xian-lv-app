package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ActivationControllerTest {
  @Rule public TemporaryFolder directory = new TemporaryFolder();

  final class Fixture {
    final ActivationPermitTest vectors = new ActivationPermitTest();
    final AtomicLong clock = new AtomicLong(1200);
    final ActivationJournal journal = new ActivationJournal(directory.newFolder());
    final ContentQuarantine quarantine = new ContentQuarantine(directory.newFolder());
    final TrustStore trust;
    final ContentStore.Snapshot snapshot;
    final ActivationController controller;

    Fixture() throws Exception {
      HotSignatures.PublicKey root =
          new HotSignatures.PublicKey(StrictJson.object(vectors.read("root.public.json")));
      trust = new TrustStore(directory.newFolder(), root);
      trust.accept(
          vectors.read("trust.json"),
          vectors.read("trust.sig.json"),
          journal,
          vectors.serverTime());
      File metadata = directory.newFolder();
      Files.write(new File(metadata, "manifest.json").toPath(), vectors.read("manifest.json"));
      Files.write(
          new File(metadata, "manifest.sig.json").toPath(), vectors.read("manifest.sig.json"));
      snapshot = new ContentStore.Snapshot(vectors.request().manifest, metadata);
      controller = new ActivationController(journal, trust, quarantine, 1, clock::get);
    }

    ActivationController.Ticket begin() throws Exception {
      return controller.begin(snapshot, vectors.permit(), vectors.serverTime(), 123);
    }
  }

  @Test
  public void serverAttemptSurvivesDiskAndOnlyActualExposedTimeCounts() throws Exception {
    Fixture f = new Fixture();
    ActivationController.Ticket ticket = f.begin();
    assertEquals(f.vectors.permit().attemptId, f.journal.state().attempt);
    assertFalse(f.controller.healthy(ticket));
    assertThrows(IllegalArgumentException.class, f::begin);
    AtomicInteger swaps = new AtomicInteger();
    assertThrows(
        IllegalArgumentException.class, () -> f.controller.expose(ticket, swaps::incrementAndGet));
    f.controller.firstFrame(ticket);
    assertTrue(f.controller.expose(ticket, swaps::incrementAndGet));
    assertEquals(1, swaps.get());
    assertThrows(
        IllegalArgumentException.class, () -> f.controller.expose(ticket, swaps::incrementAndGet));
    f.controller.setActive(ticket, true);
    f.clock.addAndGet(20000);
    f.controller.setActive(ticket, false);
    f.clock.addAndGet(180000);
    assertFalse(f.controller.healthy(ticket));
    f.controller.setActive(ticket, true);
    f.clock.addAndGet(40000);
    assertTrue(f.controller.healthy(ticket));
    assertEquals(f.snapshot.manifest.snapshotId, f.journal.state().stable);
    assertFalse(f.controller.valid(ticket));
  }

  @Test
  public void newerDecisionBeforeOrAfterFirstFrameNeverExposesOldCandidate() throws Exception {
    for (boolean submitted : new boolean[] {false, true}) {
      Fixture f = new Fixture();
      ActivationController.Ticket ticket = f.begin();
      if (submitted) f.controller.firstFrame(ticket);
      f.controller.observe(2, null, null, f.vectors.serverTime());
      assertFalse(f.controller.valid(ticket));
      assertEquals("", f.journal.state().active);
      assertEquals(2, f.journal.state().revision);
      assertTrue(f.journal.state().quarantine.isEmpty());
      assertThrows(
          IllegalArgumentException.class, () -> f.controller.expose(ticket, () -> fail("旧候选不能曝光")));
      assertThrows(IllegalArgumentException.class, () -> f.controller.firstFrame(ticket));
    }
  }

  @Test
  public void permissionExpiryDuringPreparationLeavesOldUiAndDoesNotQuarantine() throws Exception {
    Fixture f = new Fixture();
    ActivationController.Ticket ticket = f.begin();
    f.controller.firstFrame(ticket);
    f.clock.set(602000);
    assertThrows(
        IllegalArgumentException.class, () -> f.controller.expose(ticket, () -> fail("过期候选不能曝光")));
    f.controller.abort(ticket, false);
    f.quarantine.requireAllowed(f.snapshot.manifest, 1);
    assertEquals("", f.journal.state().active);
  }

  @Test
  public void confirmedFailureIsolatesContentAndOldCallbacksCannotReviveIt() throws Exception {
    Fixture f = new Fixture();
    ActivationController.Ticket ticket = f.begin();
    f.controller.abort(ticket, true);
    assertTrue(f.journal.state().quarantine.contains(f.snapshot.manifest.snapshotId));
    assertThrows(
        IllegalArgumentException.class, () -> f.quarantine.requireAllowed(f.snapshot.manifest, 1));
    assertThrows(IllegalArgumentException.class, f::begin);
    assertThrows(IllegalArgumentException.class, () -> f.controller.firstFrame(ticket));
  }

  @Test
  public void preparedFilesAreReauthenticatedBeforePermissionIsConsumed() throws Exception {
    Fixture f = new Fixture();
    ActivationPermit permit = f.vectors.permit();
    File metadata = new File(f.snapshot.directory, "manifest.json");
    Files.write(metadata.toPath(), new byte[] {123, 125});
    assertThrows(
        IllegalArgumentException.class,
        () -> f.controller.begin(f.snapshot, permit, f.vectors.serverTime(), 123));
    assertEquals(ActivationJournal.Phase.STABLE, f.journal.state().phase);
    Files.write(metadata.toPath(), f.vectors.read("manifest.json"));
    assertNotNull(f.controller.begin(f.snapshot, permit, f.vectors.serverTime(), 123));
  }

  @Test
  public void failureRacingWithHealthyAcknowledgementStillRestoresFallback() throws Exception {
    Fixture f = new Fixture();
    ActivationController.Ticket ticket = f.begin();
    f.controller.firstFrame(ticket);
    assertTrue(f.controller.expose(ticket, () -> {}));
    f.controller.setActive(ticket, true);
    f.clock.addAndGet(60000);
    assertTrue(f.controller.healthy(ticket));
    f.controller.abort(ticket, true);
    assertEquals("", f.journal.state().stable);
    assertTrue(f.journal.state().quarantine.contains(f.snapshot.manifest.snapshotId));
  }
}
