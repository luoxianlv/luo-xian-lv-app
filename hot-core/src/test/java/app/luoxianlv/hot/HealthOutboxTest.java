package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class HealthOutboxTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void restartRetryAndLateAcknowledgementCannotLoseNewEventsOrReuseSequence()
      throws Exception {
    File root = temporary.newFolder();
    String attempt = UUID.randomUUID().toString();
    var first = new HealthOutbox(root);
    first.append(attempt, "prepared", "whole_group_prepared");
    var sent = first.batch(100);
    var restarted = new HealthOutbox(root);
    assertEquals(1, restarted.batch(100).get(0).sequence);
    assertEquals(2, restarted.append(attempt, "activated", "whole_group_exposed").sequence);
    assertTrue(first.acknowledge(sent));
    assertFalse(restarted.acknowledge(sent));
    var remaining = first.batch(100);
    assertEquals(1, remaining.size());
    assertEquals(2, remaining.get(0).sequence);
    assertTrue(restarted.acknowledge(remaining));
    assertTrue(new HealthOutbox(root).batch(100).isEmpty());
    assertEquals(3, first.append(attempt, "healthy", "foreground_observed").sequence);
  }

  @Test
  public void incorrectBatchAndCorruptStorageNeverSilentlyClearQueue() throws Exception {
    File root = temporary.newFolder();
    var queue = new HealthOutbox(root);
    String attempt = UUID.randomUUID().toString();
    queue.append(attempt, "activated", "whole_group_exposed");
    assertFalse(
        queue.acknowledge(
            java.util.List.of(new HealthEvent(attempt, 1, "healthy", "foreground_observed"))));
    assertEquals(1, queue.batch(100).size());
    File data = new File(root, "outbox.bin");
    byte[] raw = Files.readAllBytes(data.toPath());
    raw[16] ^= 1;
    Files.write(data.toPath(), raw);
    assertThrows(IllegalArgumentException.class, () -> new HealthOutbox(root).batch(100));
    assertThrows(
        IllegalArgumentException.class,
        () -> queue.append(attempt, "healthy", "foreground_observed"));
    assertArrayEquals(raw, Files.readAllBytes(data.toPath()));
  }
}
