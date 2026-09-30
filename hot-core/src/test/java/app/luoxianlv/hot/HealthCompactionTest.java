package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class HealthCompactionTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private String confirm(ActivationJournal journal, String target, int revision) throws Exception {
    String attempt =
        journal.begin(
            HotSignatures.hash(target.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            revision,
            1,
            123,
            1000);
    journal.firstFrame(attempt);
    journal.healthy(attempt, 60000);
    return attempt;
  }

  /** 构造有签名摘要的磁盘历史，避免数千次 fsync 掩盖清理与迁移行为。 */
  private void history(File root, List<String> attempts, int format, boolean receipts)
      throws Exception {
    var buffer = new ByteArrayOutputStream();
    try (var output = new DataOutputStream(buffer)) {
      output.writeInt(0x4c58484f);
      output.writeInt(format);
      output.writeInt(attempts.size());
      for (String id : attempts) {
        output.writeUTF(id);
        output.writeLong(7);
      }
      output.writeInt(0);
      if (format >= 2) {
        output.writeInt(receipts ? attempts.size() : 0);
        if (receipts)
          for (String id : attempts) {
            output.writeUTF(
                HotSignatures.hash(
                    (id + "\0healthy\0foreground_observed")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (format >= 3) output.writeUTF(id);
          }
      }
    }
    byte[] body = buffer.toByteArray();
    buffer.write(MessageDigest.getInstance("SHA-256").digest(body));
    Files.write(new File(root, "outbox.bin").toPath(), buffer.toByteArray());
  }

  @Test
  public void fullHistoricalCapacityCanContinueAndStableFallbackSequencesSurvive()
      throws Exception {
    File root = temporary.newFolder();
    var journal = new ActivationJournal(temporary.newFolder());
    String previous = confirm(journal, "previous", 1);
    for (var outcome : journal.state().outcomes) journal.outcomeQueued(outcome);
    String stable = confirm(journal, "stable", 2);
    for (var outcome : journal.state().outcomes) journal.outcomeQueued(outcome);
    List<String> attempts = new ArrayList<>(List.of(previous, stable));
    while (attempts.size() < 4096) attempts.add(UUID.randomUUID().toString());
    history(root, attempts, 3, true);
    var queue = new HealthOutbox(root);
    var removed = queue.compact(journal::state, 64);
    assertEquals(4030, removed.removedAttempts());
    assertEquals(4030, removed.removedReceipts());
    assertEquals(8, queue.append(previous, "crash", "stable_process_failed").sequence);
    assertEquals(8, queue.append(stable, "anr", "stable_process_failed").sequence);
    String next = UUID.randomUUID().toString();
    assertEquals(1, queue.append(next, "prepared", "whole_group_prepared").sequence);
    assertFalse(queue.appendOnce(stable, "healthy", "foreground_observed"));
    var reopened = new HealthOutbox(root);
    assertEquals(3, reopened.batch(100).size());
    assertEquals(2, journal.state().revision);
    assertEquals(previous, journal.state().previousStableAttempt);
  }

  @Test
  public void pendingEventsAndLateAcknowledgementsRemainExact() throws Exception {
    File root = temporary.newFolder();
    var queue = new HealthOutbox(root);
    var journal = new ActivationJournal(temporary.newFolder());
    String attempt = UUID.randomUUID().toString();
    queue.append(attempt, "prepared", "whole_group_prepared");
    var sent = queue.batch(100);
    queue.append(attempt, "activated", "whole_group_exposed");
    assertEquals(0, queue.compact(journal::state, 0).removedAttempts());
    assertTrue(queue.acknowledge(sent));
    assertFalse(queue.acknowledge(sent));
    assertEquals(2, queue.batch(100).get(0).sequence);
    assertEquals(3, queue.append(attempt, "module_used", "main").sequence);
    assertEquals(0, new HealthOutbox(root).compact(journal::state, 0).removedAttempts());
  }

  @Test
  public void uploadBeforeJournalAckThenCompactionStillDeduplicatesAfterRestart() throws Exception {
    File root = temporary.newFolder(), journalRoot = temporary.newFolder();
    var journal = new ActivationJournal(journalRoot);
    String attempt = confirm(journal, "target", 1);
    var outcome = journal.state().outcomes.get(0);
    var queue = new HealthOutbox(root);
    assertTrue(queue.appendOnce(attempt, outcome.kind, outcome.code));
    assertTrue(queue.acknowledge(queue.batch(100)));
    assertEquals(0, queue.compact(journal::state, 0).removedReceipts());
    OutcomeRecovery.reconcile(new ActivationJournal(journalRoot), new HealthOutbox(root));
    assertTrue(queue.batch(100).isEmpty());
    assertTrue(new ActivationJournal(journalRoot).state().outcomes.isEmpty());
    assertEquals(2, queue.append(attempt, "crash", "stable_process_failed").sequence);
  }

  @Test
  public void legacyUnknownReceiptsPreservePendingReplayAndSequence() throws Exception {
    File root = temporary.newFolder(), journalRoot = temporary.newFolder();
    var journal = new ActivationJournal(journalRoot);
    String stable = confirm(journal, "target", 1), old = UUID.randomUUID().toString();
    history(root, List.of(stable, old), 2, true);
    var queue = new HealthOutbox(root);
    var result = queue.compact(journal::state, 0);
    assertEquals(1, result.removedAttempts());
    assertEquals(1, result.removedReceipts());
    OutcomeRecovery.reconcile(new ActivationJournal(journalRoot), new HealthOutbox(root));
    assertTrue(queue.batch(100).isEmpty());
    assertFalse(queue.appendOnce(stable, "healthy", "foreground_observed"));
    assertEquals(8, queue.append(stable, "crash", "stable_process_failed").sequence);
  }

  @Test
  public void legacyAssociationIsPersistedEvenWithoutAnyRemoval() throws Exception {
    File root = temporary.newFolder(), journalRoot = temporary.newFolder();
    var journal = new ActivationJournal(journalRoot);
    String stable = confirm(journal, "target", 1);
    history(root, List.of(stable), 2, true);
    var queue = new HealthOutbox(root);
    assertEquals(0, queue.compact(journal::state, 64).removedReceipts());
    try (var input = new DataInputStream(new FileInputStream(new File(root, "outbox.bin")))) {
      assertEquals(0x4c58484f, input.readInt());
      assertEquals(3, input.readInt());
    }
    var outcome = journal.state().outcomes.get(0);
    journal.outcomeQueued(outcome);
    assertEquals(0, new HealthOutbox(root).compact(journal::state, 0).removedReceipts());
    assertFalse(queue.appendOnce(stable, "healthy", "foreground_observed"));
  }

  @Test
  public void protectionReadFailureCorruptionAndLockContentionNeverEraseHistory() throws Exception {
    File root = temporary.newFolder();
    var journal = new ActivationJournal(temporary.newFolder());
    history(root, List.of(UUID.randomUUID().toString()), 3, true);
    Path file = new File(root, "outbox.bin").toPath();
    byte[] raw = Files.readAllBytes(file);
    var queue = new HealthOutbox(root);
    assertThrows(
        IOException.class,
        () ->
            queue.compact(
                () -> {
                  throw new IOException("模拟状态读取失败");
                },
                0));
    assertArrayEquals(raw, Files.readAllBytes(file));
    try (var channel =
            FileChannel.open(
                new File(root, "outbox.lock").toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        var lock = channel.lock()) {
      assertThrows(Exception.class, () -> queue.compact(journal::state, 0));
    }
    assertArrayEquals(raw, Files.readAllBytes(file));
    raw[20] ^= 1;
    Files.write(file, raw);
    assertThrows(Exception.class, () -> queue.compact(journal::state, 0));
    assertArrayEquals(raw, Files.readAllBytes(file));
  }
}
