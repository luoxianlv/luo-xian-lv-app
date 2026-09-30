package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipInputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ContentQuarantineTest {
  @Rule public TemporaryFolder directory = new TemporaryFolder();

  HotManifest manifest(String archive) throws Exception {
    try (InputStream input = getClass().getResourceAsStream("/protocol-v1/" + archive);
        ZipInputStream zip = new ZipInputStream(input)) {
      java.util.zip.ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null)
        if (entry.getName().equals("manifest.json"))
          return new HotManifest(HotPackage.read(zip, StrictJson.MAX_BYTES));
    }
    throw new AssertionError("缺少向量清单");
  }

  @Test
  public void stableProcessFailureAfterHealthyUploadKeepsOriginalAttemptAndFallback()
      throws Exception {
    HotManifest base = manifest("base.lxhp"), target = manifest("target.lxhp");
    for (var reason :
        new ActivationJournal.ExitReason[] {
          ActivationJournal.ExitReason.CRASH, ActivationJournal.ExitReason.ANR
        }) {
      File root = directory.newFolder();
      var journal = new ActivationJournal(root);
      var quarantine = new ContentQuarantine(directory.newFolder());
      var queue = new HealthOutbox(directory.newFolder());
      String first = journal.begin(base.snapshotId, 10, 4, 100, 1000);
      journal.firstFrame(first);
      journal.healthy(first, 60000);
      String second = journal.begin(target.snapshotId, 11, 4, 101, 2000);
      journal.firstFrame(second);
      journal.healthy(second, 60000);
      OutcomeRecovery.reconcile(journal, queue);
      assertTrue(queue.acknowledge(queue.batch(100)));
      assertTrue(journal.state().outcomes.isEmpty());
      journal = new ActivationJournal(root);
      assertEquals(second, journal.state().stableAttempt);
      assertEquals(first, journal.state().previousStableAttempt);
      journal.stableProcessFailed(target, quarantine, 1, reason);
      assertEquals(base.snapshotId, journal.state().stable);
      assertEquals(first, journal.state().stableAttempt);
      assertEquals(11, journal.state().revision);
      assertEquals(4, journal.state().trustVersion);
      OutcomeRecovery.reconcile(journal, queue);
      var events = queue.batch(100);
      assertEquals(1, events.size());
      assertEquals(second, events.get(0).attemptId);
      assertEquals(
          reason == ActivationJournal.ExitReason.CRASH ? "crash" : "anr", events.get(0).kind);
      assertEquals(2, events.get(0).sequence);
      assertThrows(IllegalArgumentException.class, () -> quarantine.requireAllowed(target, 1));
      quarantine.requireAllowed(base, 1);
    }
  }

  @Test
  public void renamingCannotBypassContentIsolationAndLateFailureKeepsFallback() throws Exception {
    HotManifest base = manifest("base.lxhp"), target = manifest("target.lxhp");
    File stateDir = directory.newFolder();
    ActivationJournal journal = new ActivationJournal(stateDir);
    ContentQuarantine quarantine = new ContentQuarantine(directory.newFolder());
    String first = journal.begin(base.snapshotId, 1, 1, 100, 1000);
    journal.firstFrame(first);
    journal.healthy(first, 60000);
    String second = journal.begin(target.snapshotId, 2, 1, 100, 2000);
    journal.firstFrame(second);
    journal.healthy(second, 60000);
    assertEquals(base.snapshotId, new ActivationJournal(stateDir).state().previousStable);
    journal.stableContentFailed(target, quarantine, 1);
    assertEquals(base.snapshotId, journal.state().active);
    assertTrue(journal.state().quarantine.contains(target.snapshotId));
    quarantine.requireAllowed(base, 1);
    byte[] raw;
    try (InputStream input = getClass().getResourceAsStream("/protocol-v1/target.lxhp");
        ZipInputStream zip = new ZipInputStream(input)) {
      java.util.zip.ZipEntry entry;
      raw = null;
      while ((entry = zip.getNextEntry()) != null)
        if (entry.getName().equals("manifest.json")) {
          raw = HotPackage.read(zip, StrictJson.MAX_BYTES);
          break;
        }
    }
    HotManifest renamed =
        new HotManifest(
            new String(raw, StandardCharsets.UTF_8)
                .replace("公开验签向量-target", "换名不能绕过隔离")
                .getBytes(StandardCharsets.UTF_8));
    assertNotEquals(target.snapshotId, renamed.snapshotId);
    assertEquals(target.contentId, renamed.contentId);
    assertThrows(IllegalArgumentException.class, () -> quarantine.requireAllowed(renamed, 1));
    quarantine.requireAllowed(renamed, 2);
  }
}
