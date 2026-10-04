package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ActivationJournalTest {
  @Rule public TemporaryFolder directory = new TemporaryFolder();

  String id(String name) throws Exception {
    return HotSignatures.hash(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  public void crashBeforeOrAfterFirstFrameRecoversAndQuarantines() throws Exception {
    for (boolean firstFrame : new boolean[] {false, true}) {
      File root = directory.newFolder();
      ActivationJournal journal = new ActivationJournal(root);
      String stable = id("stable"), broken = id("broken");
      String first = journal.begin(stable, 1, 1, 100, 1000);
      journal.firstFrame(first);
      journal.healthy(first, 60000);
      String attempt = journal.begin(broken, 2, 1, 101, 2000);
      if (firstFrame) journal.firstFrame(attempt);
      ActivationJournal restarted = new ActivationJournal(root);
      ActivationJournal.State recovered =
          restarted.recover(ActivationJournal.ExitReason.CRASH, 101, 2100);
      assertEquals(stable, recovered.active);
      assertTrue(recovered.quarantine.contains(broken));
      assertEquals(2, recovered.revision);
      assertThrows(IllegalArgumentException.class, () -> restarted.begin(broken, 3, 1, 102, 3000));
      assertThrows(IllegalArgumentException.class, () -> restarted.firstFrame(attempt));
    }
  }

  @Test
  public void userExitAndUnrelatedCrashDoNotBlameCandidate() throws Exception {
    for (ActivationJournal.ExitReason reason :
        new ActivationJournal.ExitReason[] {
          ActivationJournal.ExitReason.USER,
          ActivationJournal.ExitReason.SYSTEM,
          ActivationJournal.ExitReason.UNKNOWN,
          ActivationJournal.ExitReason.CRASH
        }) {
      ActivationJournal journal = new ActivationJournal(directory.newFolder());
      String candidate = id("candidate");
      journal.begin(candidate, 8, 3, 101, 2000);
      ActivationJournal.State recovered = journal.recover(reason, 100, 1900);
      assertEquals("", recovered.active);
      assertTrue(recovered.quarantine.isEmpty());
      assertEquals(8, recovered.revision);
      assertEquals(3, recovered.trustVersion);
      assertThrows(IllegalArgumentException.class, () -> journal.observeVersions(7, 3));
      journal.begin(candidate, 9, 3, 102, 3000);
    }
  }

  @Test
  public void rollbackStillNeedsNewRevisionAndHealthyBackgroundTimeIsExcluded() throws Exception {
    ActivationJournal journal = new ActivationJournal(directory.newFolder());
    String first = journal.begin(id("first"), 10, 5, 100, 1000);
    journal.firstFrame(first);
    AtomicLong clock = new AtomicLong();
    HealthWindow window = new HealthWindow(clock::get);
    window.setActive(true);
    clock.set(20000);
    window.setActive(false);
    clock.set(500000);
    assertEquals(20000, window.observedMillis());
    assertThrows(
        IllegalArgumentException.class, () -> journal.healthy(first, window.observedMillis()));
    window.setActive(true);
    clock.set(540000);
    journal.healthy(first, window.observedMillis());
    String second = journal.begin(id("second"), 11, 5, 101, 2000);
    journal.firstFrame(second);
    journal.healthy(second, 60000);
    assertThrows(
        IllegalArgumentException.class, () -> journal.begin(id("first"), 10, 5, 102, 3000));
    assertNotNull(journal.begin(id("first"), 12, 5, 102, 3000));
  }

  @Test
  public void damagedJournalDoesNotSilentlyResetAntiRollbackFloors() throws Exception {
    File root = directory.newFolder();
    ActivationJournal journal = new ActivationJournal(root);
    journal.observeVersions(10, 5);
    File file = new File(root, "activation.bin");
    byte[] raw = Files.readAllBytes(file.toPath());
    raw[12] ^= 1;
    Files.write(file.toPath(), raw);
    assertThrows(IllegalArgumentException.class, () -> new ActivationJournal(root));
  }

  @Test
  public void newerDecisionInvalidatesPendingAttemptAndStaleWriterCannotOverwriteIt()
      throws Exception {
    File root = directory.newFolder();
    ActivationJournal first = new ActivationJournal(root);
    String attempt = first.begin(id("candidate"), 1, 1, 100, 1000);
    ActivationJournal stale = new ActivationJournal(root);
    first.observeVersions(2, 1);
    assertThrows(IllegalArgumentException.class, () -> first.firstFrame(attempt));
    assertThrows(IllegalArgumentException.class, () -> stale.firstFrame(attempt));
    ActivationJournal recovered = new ActivationJournal(root);
    assertEquals(2, recovered.state().revision);
    assertEquals(ActivationJournal.Phase.STABLE, recovered.state().phase);
  }
}
