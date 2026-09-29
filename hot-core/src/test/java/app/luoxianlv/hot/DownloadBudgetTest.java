package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class DownloadBudgetTest {
  @Rule public TemporaryFolder directory = new TemporaryFolder();

  @Test
  public void wholeCandidateAndRetriesShareOneDurableBudget() throws Exception {
    File root = directory.newFolder();
    String id = HotSignatures.hash(new byte[] {1});
    DownloadBudget budget = new DownloadBudget(root);
    assertThrows(DownloadBudget.Deferred.class, () -> budget.admit(id, 21L << 20, true));
    assertThrows(DownloadBudget.Deferred.class, () -> budget.admit(id, -1, true));
    budget.admit(id, 20L << 20, true);
    for (int i = 0; i < 10; i++) budget.reserve(id, 1 << 20);
    DownloadBudget restarted = new DownloadBudget(root);
    assertEquals(10L << 20, restarted.used(id));
    assertThrows(DownloadBudget.Deferred.class, () -> restarted.admit(id, 11L << 20, true));
    restarted.admit(id, 10L << 20, true);
    restarted.admit(id, 512L << 20, false);
    for (int i = 0; i < 10; i++) restarted.reserve(id, 1 << 20);
    assertThrows(DownloadBudget.Deferred.class, () -> restarted.reserve(id, 1));
    assertEquals(20L << 20, budget.used(id));
  }

  @Test
  public void corruptLedgerCannotResetSpentTraffic() throws Exception {
    File root = directory.newFolder();
    String id = HotSignatures.hash(new byte[] {2});
    DownloadBudget budget = new DownloadBudget(root);
    budget.reserve(id, 100);
    File file = new File(root, id + ".bin");
    byte[] raw = Files.readAllBytes(file.toPath());
    raw[8] ^= 1;
    Files.write(file.toPath(), raw);
    assertThrows(IllegalArgumentException.class, () -> budget.admit(id, 1, true));
    assertThrows(IllegalArgumentException.class, () -> budget.reserve(id, 1));
    budget.admit(id, 10, false);
  }
}
