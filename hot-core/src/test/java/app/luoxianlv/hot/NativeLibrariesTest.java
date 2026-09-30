package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.zip.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public final class NativeLibrariesTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private File archive(Map<String, byte[]> entries) throws Exception {
    File file = temporary.newFile();
    try (var zip = new ZipOutputStream(new FileOutputStream(file))) {
      for (var entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    return file;
  }

  @After
  public void writable() throws Exception {
    try (var files = Files.walk(temporary.getRoot().toPath())) {
      files.forEach(path -> path.toFile().setWritable(true, true));
    }
  }

  @Test
  public void onlyFirstSupportedAbiIsBudgetedAndMaterialized() throws Exception {
    byte[] selected = new byte[] {3, 4, 5};
    File apk = archive(Map.of("lib/x86_64/libcore.so", selected,
        "lib/arm64-v8a/libcore.so", new byte[1 << 20]));
    File target = new File(temporary.getRoot(), "native");
    var plan = NativeLibraries.inspect(apk, target, new String[] {"x86_64", "arm64-v8a"});
    assertEquals(selected.length, plan.bytes);
    assertEquals(2, plan.paths);
    assertEquals(target.getAbsolutePath(), plan.materialize());
    assertArrayEquals(selected, Files.readAllBytes(new File(target, "libcore.so").toPath()));
    assertFalse(new File(target, "libcore.so").canWrite());
  }

  @Test
  public void verifiedReadOnlyCacheIsReusedWithoutReplacingItsFile() throws Exception {
    byte[] selected = new byte[1 << 20];
    File apk = archive(Map.of("lib/x86_64/libcore.so", selected));
    File target = temporary.newFolder();
    var library = new File(target, "libcore.so");
    Files.write(library.toPath(), selected);
    Files.setLastModifiedTime(library.toPath(), FileTime.fromMillis(1234567890000L));
    assertTrue(library.setReadOnly());
    var before = Files.readAttributes(library.toPath(), java.nio.file.attribute.BasicFileAttributes.class);
    var plan = NativeLibraries.inspect(apk, target, new String[] {"x86_64"});
    assertEquals(0, plan.bytes);
    assertEquals(0, plan.paths);
    new PreparationSpace(path -> new PreparationSpace.Volume("same", 1, 4096))
        .admit(List.of(new PreparationSpace.Demand(target, plan.bytes, plan.paths)));
    plan.materialize();
    var after = Files.readAttributes(library.toPath(), java.nio.file.attribute.BasicFileAttributes.class);
    assertEquals(before.lastModifiedTime(), after.lastModifiedTime());
    assertEquals(before.fileKey(), after.fileKey());
    assertArrayEquals(selected, Files.readAllBytes(library.toPath()));
  }

  @Test
  public void sameSizeCorruptCacheNeedsNewSpaceAndIsAtomicallyRepaired() throws Exception {
    byte[] expected = {3, 4, 5}, corrupt = {9, 4, 5};
    File apk = archive(Map.of("lib/x86_64/libcore.so", expected));
    File target = temporary.newFolder();
    File library = new File(target, "libcore.so");
    Files.write(library.toPath(), corrupt);
    assertTrue(library.setReadOnly());
    var plan = NativeLibraries.inspect(apk, target, new String[] {"x86_64"});
    assertEquals(expected.length, plan.bytes);
    assertEquals(1, plan.paths);
    assertThrows(PreparationSpace.Deferred.class,
        () -> new PreparationSpace(path -> new PreparationSpace.Volume("same", 16L << 20, 4096))
            .admit(List.of(new PreparationSpace.Demand(target, plan.bytes, plan.paths))));
    assertArrayEquals(corrupt, Files.readAllBytes(library.toPath()));
    plan.materialize();
    assertArrayEquals(expected, Files.readAllBytes(library.toPath()));
  }

  @Test
  public void cachedLibraryChangedAfterAdmissionIsNeverLoadedOrSilentlyRewritten() throws Exception {
    byte[] expected = {3, 4, 5};
    File apk = archive(Map.of("lib/x86_64/libcore.so", expected));
    File target = temporary.newFolder();
    File library = new File(target, "libcore.so");
    Files.write(library.toPath(), expected);
    assertTrue(library.setReadOnly());
    var plan = NativeLibraries.inspect(apk, target, new String[] {"x86_64"});
    assertTrue(library.setWritable(true, true));
    Files.write(library.toPath(), new byte[] {9, 4, 5});
    assertTrue(library.setReadOnly());
    assertThrows(IllegalArgumentException.class, plan::materialize);
    assertArrayEquals(new byte[] {9, 4, 5}, Files.readAllBytes(library.toPath()));
  }
}
