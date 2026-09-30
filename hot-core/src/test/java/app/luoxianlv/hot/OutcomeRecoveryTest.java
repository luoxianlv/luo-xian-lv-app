package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class OutcomeRecoveryTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private String id(String value) throws Exception {
    return HotSignatures.hash(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private String confirm(ActivationJournal journal, String target, long revision) throws Exception {
    String attempt = journal.begin(target, revision, 1, 123, 1000);
    journal.firstFrame(attempt);
    journal.healthy(attempt, 60000);
    return attempt;
  }

  @Test
  public void diskHealthyThenProcessExitReplaysOriginalAttemptExactlyOnce() throws Exception {
    File root = temporary.newFolder(), queueRoot = temporary.newFolder();
    var journal = new ActivationJournal(root);
    String target = id("new-native-component");
    String attempt = confirm(journal, target, 1);
    assertEquals(target, new ActivationJournal(root).state().stable);
    var restarted = new ActivationJournal(root);
    var queue = new HealthOutbox(queueRoot);
    OutcomeRecovery.reconcile(restarted, queue);
    var events = queue.batch(100);
    assertEquals(1, events.size());
    assertEquals(attempt, events.get(0).attemptId);
    assertEquals("healthy", events.get(0).kind);
    assertTrue(new ActivationJournal(root).state().outcomes.isEmpty());
    assertTrue(queue.acknowledge(events));
    OutcomeRecovery.reconcile(new ActivationJournal(root), new HealthOutbox(queueRoot));
    assertTrue(queue.batch(100).isEmpty());
  }

  @Test
  public void processExitAfterQueueSaveBeforeJournalAcknowledgementDoesNotDuplicateAfterUpload()
      throws Exception {
    File root = temporary.newFolder(), queueRoot = temporary.newFolder();
    var journal = new ActivationJournal(root);
    confirm(journal, id("target"), 1);
    var receipt = journal.state().outcomes.get(0);
    var queue = new HealthOutbox(queueRoot);
    assertTrue(queue.appendOnce(receipt.attemptId, receipt.kind, receipt.code));
    assertTrue(queue.acknowledge(queue.batch(100)));
    // 网络已接收但日志尚未清回执，此时重启仍能依据持久去重身份完成交接。
    OutcomeRecovery.reconcile(new ActivationJournal(root), new HealthOutbox(queueRoot));
    assertTrue(queue.batch(100).isEmpty());
    assertTrue(new ActivationJournal(root).state().outcomes.isEmpty());
    assertEquals(2, queue.append(receipt.attemptId, "module_used", "main").sequence);
  }

  @Test
  public void damagedQueueKeepsReceiptAndPendingTrialNeverInventsHealthyEvent() throws Exception {
    File root = temporary.newFolder(), queueRoot = temporary.newFolder();
    var journal = new ActivationJournal(root);
    String target = id("target");
    String attempt = journal.begin(target, 1, 1, 123, 1000);
    journal.firstFrame(attempt);
    OutcomeRecovery.reconcile(journal, new HealthOutbox(queueRoot));
    assertTrue(new HealthOutbox(queueRoot).batch(100).isEmpty());
    journal.healthy(attempt, 60000);
    Files.write(new File(queueRoot, "outbox.bin").toPath(), new byte[] {1, 2});
    assertThrows(
        IllegalArgumentException.class,
        () -> OutcomeRecovery.reconcile(journal, new HealthOutbox(queueRoot)));
    assertEquals(1, new ActivationJournal(root).state().outcomes.size());
    assertEquals(target, journal.state().stable);
  }

  @Test
  public void rollbackBeforeReceiptHandoverReportsRecoveryInsteadOfHealthyAndRejectsLateAck()
      throws Exception {
    File root = temporary.newFolder(), queueRoot = temporary.newFolder();
    var journal = new ActivationJournal(root);
    String target = id("target");
    confirm(journal, target, 1);
    var old = journal.state().outcomes.get(0);
    journal.unavailableStable(target);
    assertFalse(journal.outcomeQueued(old));
    OutcomeRecovery.reconcile(journal, new HealthOutbox(queueRoot));
    var events = new HealthOutbox(queueRoot).batch(100);
    assertEquals(1, events.size());
    assertEquals("recovered", events.get(0).kind);
    assertEquals("", journal.state().stable);
  }

  @Test
  public void oldJournalAndOutboxFormatsUpgradeWithoutResettingFloorsOrSequence() throws Exception {
    File root = temporary.newFolder(), queueRoot = temporary.newFolder();
    var journal = new ActivationJournal(root);
    journal.observeVersions(7, 3);
    byte[] current = Files.readAllBytes(new File(root, "activation.bin").toPath());
    byte[] body = java.util.Arrays.copyOf(current, current.length - 36);
    java.nio.ByteBuffer.wrap(body).putInt(4, 2);
    var old = new java.io.ByteArrayOutputStream();
    old.write(body);
    old.write(MessageDigest.getInstance("SHA-256").digest(body));
    Files.write(new File(root, "activation.bin").toPath(), old.toByteArray());
    var upgraded = new ActivationJournal(root);
    upgraded.observeVersions(8, 4);
    assertEquals(8, new ActivationJournal(root).state().revision);
    assertEquals(4, new ActivationJournal(root).state().trustVersion);

    String attempt = UUID.randomUUID().toString();
    var queue = new HealthOutbox(queueRoot);
    queue.append(attempt, "activated", "whole_group_exposed");
    current = Files.readAllBytes(new File(queueRoot, "outbox.bin").toPath());
    body = java.util.Arrays.copyOf(current, current.length - 36);
    java.nio.ByteBuffer.wrap(body).putInt(4, 1);
    old.reset();
    old.write(body);
    old.write(MessageDigest.getInstance("SHA-256").digest(body));
    Files.write(new File(queueRoot, "outbox.bin").toPath(), old.toByteArray());
    queue = new HealthOutbox(queueRoot);
    assertTrue(queue.appendOnce(attempt, "healthy", "foreground_observed"));
    assertEquals(2, queue.batch(100).get(1).sequence);
    assertFalse(new HealthOutbox(queueRoot).appendOnce(attempt, "healthy", "foreground_observed"));
  }
}
