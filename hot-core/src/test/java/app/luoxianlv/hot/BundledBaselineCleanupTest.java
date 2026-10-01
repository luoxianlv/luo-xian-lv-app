package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public final class BundledBaselineCleanupTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private static final String APP = "app.luoxianlv.debug";
  private File root;
  private record Cache(File directory, byte[] index, String runtime) {}

  @Before public void root() throws Exception { root = temporary.newFolder(); }
  @After public void writable() throws Exception {
    try (var paths = Files.walk(temporary.getRoot().toPath())) {
      paths.forEach(path -> path.toFile().setWritable(true, true));
    }
  }

  private Cache cache(int identity, boolean tracked, boolean withNative) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("classes.dex")); zip.write(new byte[] {(byte) identity}); zip.closeEntry();
      if (withNative) {
        zip.putNextEntry(new ZipEntry("lib/" + NativeLibraries.systemAbis()[0] + "/libcore.so"));
        zip.write(new byte[] {(byte) identity, 2, 3}); zip.closeEntry();
      }
    }
    byte[] runtime = bytes.toByteArray(), business = {(byte) identity, 9, 8};
    String runtimeHash = HotSignatures.hash(runtime);
    byte[] index = ("{\"schema\":1,\"runtimeAbi\":\"test-runtime-1\",\"entryClass\":\"test.Entry\","
        + "\"runtime\":{\"sha256\":\"" + runtimeHash + "\",\"size\":" + runtime.length + "},"
        + "\"business\":{\"sha256\":\"" + HotSignatures.hash(business) + "\",\"size\":" + business.length + "}}")
        .getBytes(StandardCharsets.UTF_8);
    File directory = new File(root, HotSignatures.hash(index)); assertTrue(directory.mkdir());
    File runtimeFile = new File(directory, "runtime.apk"), businessFile = new File(directory, "business.apk");
    Files.write(runtimeFile.toPath(), runtime); Files.write(businessFile.toPath(), business);
    assertTrue(runtimeFile.setReadOnly()); assertTrue(businessFile.setReadOnly());
    if (withNative) NativeLibraries.inspect(runtimeFile, new File(directory, "native"), NativeLibraries.systemAbis()).materialize();
    if (tracked) BundledBaseline.remember(directory, index, APP);
    return new Cache(directory, index, runtimeHash);
  }

  private int collect(Cache current, Set<String> protectedPaths, String resident) {
    return BundledBaseline.collectOld(root, APP, current.directory.getName(), protectedPaths, resident);
  }

  @Test public void completeRecordedOldBaselineIsRemovedAndCurrentIsPreserved() throws Exception {
    Cache old = cache(1, true, true), current = cache(2, true, false);
    assertEquals(1, collect(current, Set.of(), ""));
    assertFalse(old.directory.exists()); assertTrue(current.directory.isDirectory());
  }

  @Test public void requestedAndResidentRecoveryBaselinesRemainAvailable() throws Exception {
    Cache requested = cache(1, true, false), resident = cache(2, true, false), current = cache(3, true, false);
    assertEquals(0, collect(current, Set.of(requested.directory.getCanonicalPath()), resident.runtime));
    assertTrue(requested.directory.exists()); assertTrue(resident.directory.exists());
  }

  @Test public void legacyAndForeignApplicationCachesAreNotClaimed() throws Exception {
    Cache legacy = cache(1, false, false), foreign = cache(2, false, false), current = cache(3, true, false);
    BundledBaseline.remember(foreign.directory, foreign.index, "other.application");
    assertEquals(0, collect(current, Set.of(), ""));
    assertTrue(legacy.directory.exists()); assertTrue(foreign.directory.exists());
  }

  @Test public void modifiedApkAndUnknownNativeOrTopLevelFilesArePreserved() throws Exception {
    Cache corrupt = cache(1, true, false), extra = cache(2, true, false), nativeExtra = cache(3, true, true);
    Cache current = cache(4, true, false);
    File business = new File(corrupt.directory, "business.apk"); assertTrue(business.setWritable(true, true));
    Files.write(business.toPath(), new byte[] {9, 9, 8}); assertTrue(business.setReadOnly());
    Files.writeString(new File(extra.directory, "user-file").toPath(), "keep");
    Files.writeString(new File(nativeExtra.directory, "native/unknown.part").toPath(), "keep");
    assertEquals(0, collect(current, Set.of(), ""));
    assertTrue(corrupt.directory.exists()); assertTrue(extra.directory.exists()); assertTrue(nativeExtra.directory.exists());
  }

  @Test public void changedIndexAndMalformedOwnershipCannotAuthorizeDeletion() throws Exception {
    Cache index = cache(1, true, false), owner = cache(2, true, false), current = cache(3, true, false);
    Files.writeString(new File(index.directory, "index.json").toPath(), "{}");
    File record = new File(owner.directory, "owner.json"); Files.writeString(record.toPath(), "{}");
    BundledBaseline.remember(owner.directory, owner.index, APP);
    assertEquals("{}", Files.readString(record.toPath()));
    assertEquals(0, collect(current, Set.of(), ""));
    assertTrue(index.directory.exists()); assertTrue(owner.directory.exists());
  }

  @Test public void linkedChildIsPreservedWithItsTarget() throws Exception {
    Cache old = cache(1, true, false), current = cache(2, true, false);
    File target = temporary.newFile(); Files.writeString(target.toPath(), "user");
    File business = new File(old.directory, "business.apk"); assertTrue(business.setWritable(true, true)); Files.delete(business.toPath());
    try { Files.createSymbolicLink(business.toPath(), target.toPath()); }
    catch (IOException | UnsupportedOperationException denied) { Assume.assumeNoException(denied); }
    assertEquals(0, collect(current, Set.of(), "")); assertTrue(old.directory.exists());
    assertEquals("user", Files.readString(target.toPath()));
  }
}
