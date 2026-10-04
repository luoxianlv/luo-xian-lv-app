package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class InstalledObjectCopyTest {
  @Rule public TemporaryFolder files = new TemporaryFolder();

  @After
  public void cleanup() throws Exception {
    Thread.interrupted();
    try (var paths = Files.walk(files.getRoot().toPath())) {
      paths.forEach(path -> path.toFile().setWritable(true, true));
    }
  }

  @Test
  public void cancelWhileCheckingValidInstalledCacheDoesNotDeleteOrRewriteIt() throws Exception {
    byte[] original = {8, 7, 6, 5};
    var directory = files.newFolder();
    var target = directory.toPath().resolve("runtime.apk");
    Files.write(target, original);
    target.toFile().setReadOnly();
    var opened = new AtomicInteger();
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          InterruptedIOException.class,
          () ->
              BundledBaseline.copyArtifact(
                  directory,
                  "runtime",
                  HotSignatures.hash(original),
                  original.length,
                  () -> {
                    opened.incrementAndGet();
                    return new ByteArrayInputStream(original);
                  }));
    } finally {
      Thread.interrupted();
    }
    assertArrayEquals(original, Files.readAllBytes(target));
    assertEquals(0, opened.get());
  }

  @Test
  public void failedNewSourceKeepsPreviousCorruptBytes() throws Exception {
    var directory = files.newFolder();
    var target = directory.toPath().resolve("runtime.apk");
    byte[] corrupt = {4, 3, 2, 1};
    Files.write(target, corrupt);
    target.toFile().setReadOnly();
    byte[] expected = {1, 2, 3, 4};
    assertThrows(
        IOException.class,
        () ->
            BundledBaseline.copyArtifact(
                directory,
                "runtime",
                HotSignatures.hash(expected),
                expected.length,
                () -> {
                  throw new IOException("测试读取故障");
                }));
    assertArrayEquals(corrupt, Files.readAllBytes(target));
  }

  @Test
  public void matchingArtifactIsPreparedWithoutCreatingPairedModule() throws Exception {
    var directory = files.newFolder();
    byte[] expected = {1, 2, 3, 4};
    var result =
        BundledBaseline.copyArtifact(
            directory,
            "runtime",
            HotSignatures.hash(expected),
            expected.length,
            () -> new ByteArrayInputStream(expected));
    ContentStore.verifyFile(result, HotSignatures.hash(expected), expected.length);
    assertFalse(directory.toPath().resolve("business.apk").toFile().exists());
  }
}
