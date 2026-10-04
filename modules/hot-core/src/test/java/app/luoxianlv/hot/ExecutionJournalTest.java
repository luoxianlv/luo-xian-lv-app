package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ExecutionJournalTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private String id(String name) throws Exception {
    return HotSignatures.hash(name.getBytes());
  }

  @Test
  public void stableRunPersistsAndOnlyMatchingCrashOrAnrCanBlameIt() throws Exception {
    var directory = temporary.newFolder();
    var journal = new ExecutionJournal(directory);
    var run = journal.start(id("stable"), UUID.randomUUID().toString(), id("host"), 123, 1000);
    var restored = new ExecutionJournal(directory).current();
    assertEquals(run.id, restored.id);
    assertEquals(run.attempt, restored.attempt);
    assertTrue(restored.belongsTo(id("stable"), id("host")));
    assertFalse(restored.belongsTo(id("stable"), id("new-apk")));
    assertTrue(restored.matches(ActivationJournal.ExitReason.CRASH, 123, 1100));
    assertTrue(restored.matches(ActivationJournal.ExitReason.ANR, 123, 1100));
    assertFalse(restored.matches(ActivationJournal.ExitReason.CRASH, 124, 1100));
    assertFalse(restored.matches(ActivationJournal.ExitReason.CRASH, 123, 900));
    for (var reason :
        new ActivationJournal.ExitReason[] {
          ActivationJournal.ExitReason.USER,
          ActivationJournal.ExitReason.SYSTEM,
          ActivationJournal.ExitReason.UNKNOWN
        }) assertFalse(restored.matches(reason, 123, 1100));
    assertTrue(journal.crashed(run, 123, 1100));
    assertEquals(1100, new ExecutionJournal(directory).current().crashedAt);
  }

  @Test
  public void lateWriterCannotMarkOrDeleteAnotherRunAndTamperingIsRejected() throws Exception {
    var directory = temporary.newFolder();
    var journal = new ExecutionJournal(directory);
    var old = journal.start(id("first"), "", id("host"), 123, 1000);
    var next = journal.start(id("second"), "", id("host"), 123, 2000);
    assertFalse(journal.crashed(old, 123, 2100));
    assertFalse(journal.clear(old));
    assertFalse(journal.crashed(next, 124, 2100));
    assertFalse(journal.crashed(next, 123, 1900));
    assertEquals(0, journal.current().crashedAt);
    var file = new java.io.File(directory, "execution.bin");
    byte[] raw = Files.readAllBytes(file.toPath());
    raw[12] ^= 1;
    Files.write(file.toPath(), raw);
    assertThrows(IllegalArgumentException.class, journal::current);
  }
}
