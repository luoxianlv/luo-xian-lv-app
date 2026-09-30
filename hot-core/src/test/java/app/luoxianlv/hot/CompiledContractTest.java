package app.luoxianlv.hot;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CompiledContractTest {
  private static final String HASH = "a".repeat(64);
  @Rule public final TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void matchingCompiledSdkAccepted() throws Exception {
    CompiledContract.require(HASH, archive(HASH + "\n"));
  }

  @Test
  public void mismatchOrMissingRejected() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> CompiledContract.require(HASH, archive("b".repeat(64) + "\n")));
    assertThrows(
        IllegalArgumentException.class, () -> CompiledContract.require(HASH, archive(null)));
  }

  @Test
  public void invalidIdentityRejected() throws Exception {
    for (String invalid :
        new String[] {"a".repeat(63), HASH, "A".repeat(64) + "\n", HASH + "\r", "你".repeat(64)})
      assertThrows(Exception.class, () -> CompiledContract.require(HASH, archive(invalid)));
    assertThrows(IllegalArgumentException.class, () -> CompiledContract.require("", archive(HASH)));
  }

  @Test
  public void missingMarkerAllowedOnlyInIndependentDebugCoreTest() {
    assertTrue(CompiledContract.legacyTest("app.luoxianlv.hot.test", 2));
    assertFalse(CompiledContract.legacyTest("app.luoxianlv.hot.test", 0));
    assertFalse(CompiledContract.legacyTest("app.luoxianlv", 2));
    assertFalse(CompiledContract.legacyTest("app.luoxianlv.debug", 2));
  }

  private File archive(String marker) throws Exception {
    File file = temporary.newFile();
    try (var output = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
      output.putNextEntry(new ZipEntry("assets/sample"));
      output.write(1);
      output.closeEntry();
      if (marker != null) {
        output.putNextEntry(new ZipEntry("assets/hot/host-contract.sha256"));
        output.write(marker.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
      }
    }
    return file;
  }
}
