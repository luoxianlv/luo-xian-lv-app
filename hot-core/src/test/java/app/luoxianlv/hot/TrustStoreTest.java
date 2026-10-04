package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class TrustStoreTest {
  @Rule public TemporaryFolder directory = new TemporaryFolder();

  byte[] read(String name) throws Exception {
    try (InputStream input = getClass().getResourceAsStream("/protocol-v1/" + name)) {
      return HotPackage.read(input, StrictJson.MAX_BYTES);
    }
  }

  HotSignatures.PublicKey root() throws Exception {
    return new HotSignatures.PublicKey(StrictJson.object(read("root.public.json")));
  }

  @Test
  public void persistsExactAuthorizationAndNeverUsesExpiredForNewActivation() throws Exception {
    File trustDir = directory.newFolder();
    ActivationJournal journal = new ActivationJournal(directory.newFolder());
    TrustStore store = new TrustStore(trustDir, root());
    assertNull(store.current());
    store.accept(
        read("trust.json"), read("trust.sig.json"), journal, Instant.parse("2026-09-29T00:00:00Z"));
    assertEquals(1, journal.state().trustVersion);
    TrustStore.Record restored = new TrustStore(trustDir, root()).current();
    assertNotNull(restored);
    assertArrayEquals(read("trust.json"), restored.document());
    assertThrows(
        IllegalArgumentException.class,
        () -> restored.authority.current(1, Instant.parse("2031-01-01T00:00:00Z")));
    journal.observeVersions(0, 2);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.accept(
                read("trust.json"),
                read("trust.sig.json"),
                journal,
                Instant.parse("2026-09-29T00:00:00Z")));
    assertArrayEquals(read("trust.json"), store.current().document());
  }

  @Test
  public void corruptedStoredTrustFailsClosed() throws Exception {
    File trustDir = directory.newFolder();
    TrustStore store = new TrustStore(trustDir, root());
    ActivationJournal journal = new ActivationJournal(directory.newFolder());
    store.accept(
        read("trust.json"), read("trust.sig.json"), journal, Instant.parse("2026-09-29T00:00:00Z"));
    File file = new File(trustDir, "trust.bin");
    byte[] raw = Files.readAllBytes(file.toPath());
    raw[25] ^= 1;
    Files.write(file.toPath(), raw);
    assertThrows(Exception.class, store::current);
    assertEquals(1, journal.state().trustVersion);
  }

  @Test
  public void ordinaryUpdateTrustDoesNotCreateHotActivationState() throws Exception {
    TrustStore store = new TrustStore(directory.newFolder(), root());
    ActivationJournal journal = new ActivationJournal(directory.newFolder());
    Instant now = Instant.parse("2026-09-29T00:00:00Z");
    store.accept(read("trust.json"), read("trust.sig.json"), 1, now);
    assertEquals(0, journal.state().trustVersion);
    assertEquals(ActivationJournal.Phase.STABLE, journal.state().phase);
    assertThrows(
        IllegalArgumentException.class,
        () -> store.accept(read("trust.json"), read("trust.sig.json"), 2, now));
    assertArrayEquals(read("trust.json"), store.current().document());
  }
}
