package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class PreparationSpaceTest {
  private static final long RESERVE = 16L << 20;
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void sameVolumeCombinesInternalAndExternalInsteadOfSpendingSpaceTwice() throws Exception {
    File internal = temporary.newFolder(), external = temporary.newFolder();
    var budget =
        new PreparationSpace(path -> new PreparationSpace.Volume("same", RESERVE + 100, 1));
    assertThrows(
        PreparationSpace.Deferred.class,
        () ->
            budget.admit(
                List.of(
                    new PreparationSpace.Demand(internal, 80),
                    new PreparationSpace.Demand(external, 80))));
    budget.admit(
        List.of(
            new PreparationSpace.Demand(internal, 50), new PreparationSpace.Demand(external, 50)));
  }

  @Test
  public void differentVolumesHaveSeparateReservesAndAllocationUnits() throws Exception {
    File internal = temporary.newFolder(), external = temporary.newFolder();
    var budget =
        new PreparationSpace(
            path -> new PreparationSpace.Volume(path.getPath(), RESERVE + 65536, 65536));
    budget.admit(
        List.of(
            new PreparationSpace.Demand(internal, 1), new PreparationSpace.Demand(external, 1)));
    assertThrows(
        PreparationSpace.Deferred.class,
        () -> budget.admit(List.of(new PreparationSpace.Demand(internal, 1, 1))));
  }

  @Test
  public void inconsistentFreeMeasurementsUseLowestAndInvalidMeasurementsAbort() throws Exception {
    var count = new java.util.concurrent.atomic.AtomicInteger();
    var budget =
        new PreparationSpace(
            path ->
                new PreparationSpace.Volume(
                    "same", RESERVE + (count.getAndIncrement() == 0 ? 100 : 50), 1));
    File root = temporary.newFolder();
    assertThrows(
        PreparationSpace.Deferred.class,
        () ->
            budget.admit(
                List.of(
                    new PreparationSpace.Demand(root, 40), new PreparationSpace.Demand(root, 40))));
    assertThrows(
        Exception.class,
        () ->
            new PreparationSpace(path -> new PreparationSpace.Volume("same", 100, 0))
                .admit(List.of(new PreparationSpace.Demand(root, 1))));
    assertThrows(
        Exception.class,
        () ->
            new PreparationSpace(path -> new PreparationSpace.Volume("same", Long.MAX_VALUE, 4096))
                .admit(List.of(new PreparationSpace.Demand(root, Long.MAX_VALUE))));
  }

  private byte[] zip(String name, int size) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry(name));
      zip.write(new byte[size]);
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }

  private HotManifest manifest(byte[] runtime, byte[] business, byte[] resource) throws Exception {
    String extra =
        resource == null
            ? ""
            : ",{\"id\":\"theme\",\"role\":\"resources\",\"sha256\":\""
                + HotSignatures.hash(resource)
                + "\",\"size\":"
                + resource.length
                + ",\"mount\":\"theme\",\"requires\":[]}";
    String raw =
        "{\"schema\":1,\"kind\":\"hot\",\"applicationId\":\"app.luoxianlv.debug\",\"environment\":\"test\",\"label\":\"空间\",\"createdAt\":\"2026-09-29T00:00:00Z\",\"hostContract\":{\"min\":1,\"max\":1},\"runtimeAbi\":\"runtime-v1\",\"activation\":\"live\",\"stateSchema\":{\"current\":1,\"readable\":{\"min\":1,\"max\":1}},\"artifacts\":[{\"id\":\"runtime\",\"role\":\"runtime\",\"sha256\":\""
            + HotSignatures.hash(runtime)
            + "\",\"size\":"
            + runtime.length
            + ",\"requires\":[]},{\"id\":\"business\",\"role\":\"business\",\"sha256\":\""
            + HotSignatures.hash(business)
            + "\",\"size\":"
            + business.length
            + ",\"entryClass\":\"app.test.Entry\",\"requires\":[\"runtime\"]}"
            + extra
            + "]}";
    return new HotManifest(raw.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void commitIncludesExpandedResourceAndCopiesAndNeverDeletesFallback() throws Exception {
    File root = temporary.newFolder(), downloadRoot = temporary.newFolder();
    var store = new ContentStore(root);
    byte[] runtime = zip("assets/runtime-abi.txt", 1), business = zip("classes.dex", 1024);
    byte[] resource = zip("images/星空.bin", 1 << 20);
    var manifest = manifest(runtime, business, resource);
    Map<String, File> downloaded = new HashMap<>();
    for (byte[] bytes : List.of(runtime, business, resource)) {
      File path = new File(downloadRoot, HotSignatures.hash(bytes));
      Files.write(path.toPath(), bytes);
      downloaded.put(path.getName(), path);
    }
    Path sentinel = new File(root, "stable-user-sentinel").toPath();
    Files.write(sentinel, new byte[] {9});
    var budget =
        new PreparationSpace(path -> new PreparationSpace.Volume("same", RESERVE + (1 << 20), 1));
    assertThrows(
        PreparationSpace.Deferred.class,
        () -> budget.beforeCommit(store, manifest, downloaded, manifest.runtime.sha256));
    assertArrayEquals(new byte[] {9}, Files.readAllBytes(sentinel));
    assertFalse(store.objectFile(manifest.business.sha256).exists());
    assertFalse(new File(root, "snapshots/" + manifest.snapshotId).exists());
    new PreparationSpace(path -> new PreparationSpace.Volume("same", RESERVE + (2 << 20), 1))
        .beforeCommit(store, manifest, downloaded, manifest.runtime.sha256);
  }

  @Test
  public void preflightCountsRemainingDownloadAndInternalCopyOnSameVolume() throws Exception {
    File root = temporary.newFolder();
    var store = new ContentStore(root);
    byte[] runtime = zip("assets/runtime-abi.txt", 1), business = zip("classes.dex", 200);
    var manifest = manifest(runtime, business, null);
    Files.write(store.objectFile(manifest.runtime.sha256).toPath(), runtime);
    var downloads =
        new ObjectDownloader(temporary.newFolder(), new DownloadBudget(temporary.newFolder()));
    Files.write(
        downloads.partial(manifest.business.sha256).toPath(),
        Arrays.copyOf(business, business.length / 2));
    long amount = business.length * 2L + business.length - business.length / 2;
    var budget =
        new PreparationSpace(path -> new PreparationSpace.Volume("same", RESERVE + amount - 1, 1));
    assertThrows(
        PreparationSpace.Deferred.class,
        () -> budget.beforeDownload(store, downloads, manifest, manifest.runtime.sha256));
    new PreparationSpace(path -> new PreparationSpace.Volume("same", RESERVE + amount, 1))
        .beforeDownload(store, downloads, manifest, manifest.runtime.sha256);
  }

  @Test
  public void newRuntimeIncludesNativeExpansionInsteadOfOnlyCompressedApk() throws Exception {
    var store = new ContentStore(temporary.newFolder());
    byte[] runtime = zip("lib/x86_64/libcore.so", 1 << 20), business = zip("classes.dex", 200);
    var manifest = manifest(runtime, business, null);
    Map<String, File> downloaded = new HashMap<>();
    for (byte[] bytes : List.of(runtime, business)) {
      File file = temporary.newFile();
      Files.write(file.toPath(), bytes);
      downloaded.put(HotSignatures.hash(bytes), file);
    }
    assertThrows(
        PreparationSpace.Deferred.class,
        () ->
            new PreparationSpace(
                    path -> new PreparationSpace.Volume("same", RESERVE + (1 << 20), 4096))
                .beforeCommit(store, manifest, downloaded, ""));
    new PreparationSpace(path -> new PreparationSpace.Volume("same", RESERVE + (2 << 20), 4096))
        .beforeCommit(store, manifest, downloaded, "");
    assertFalse(store.objectFile(manifest.runtime.sha256).exists());
  }

  @Test
  public void missingResourceArchiveUsesUpperBoundBeforeAnyConnection() throws Exception {
    var store = new ContentStore(temporary.newFolder());
    byte[] runtime = zip("assets/runtime-abi.txt", 1), business = zip("classes.dex", 200);
    byte[] resource = zip("image.bin", 100);
    var manifest = manifest(runtime, business, resource);
    Files.write(store.objectFile(manifest.runtime.sha256).toPath(), runtime);
    var downloads =
        new ObjectDownloader(temporary.newFolder(), new DownloadBudget(temporary.newFolder()));
    var budget =
        new PreparationSpace(path -> new PreparationSpace.Volume("same", RESERVE + (2 << 20), 1));
    assertThrows(
        PreparationSpace.Deferred.class,
        () -> budget.beforeDownload(store, downloads, manifest, manifest.runtime.sha256));
    assertFalse(downloads.partial(HotSignatures.hash(resource)).exists());
  }

  @Test
  public void completedPartialArchiveUsesVerifiedExpansionAndCanResumeWithLessThanUpperBound()
      throws Exception {
    var store = new ContentStore(temporary.newFolder());
    byte[] runtime = zip("assets/runtime-abi.txt", 1), business = zip("classes.dex", 200);
    byte[] resource = zip("images/星空.bin", 1 << 20);
    var manifest = manifest(runtime, business, resource);
    Files.write(store.objectFile(manifest.runtime.sha256).toPath(), runtime);
    Files.write(store.objectFile(manifest.business.sha256).toPath(), business);
    var downloads = new ObjectDownloader(temporary.newFolder(), new DownloadBudget(temporary.newFolder()));
    var partial = downloads.partial(HotSignatures.hash(resource));
    Files.write(partial.toPath(), resource);
    var budget = new PreparationSpace(path -> new PreparationSpace.Volume("same", RESERVE + (2 << 20), 4096));
    budget.beforeDownload(store, downloads, manifest, manifest.runtime.sha256);
    budget.beforeCommit(store, manifest, Map.of(HotSignatures.hash(resource), partial), manifest.runtime.sha256);
    assertArrayEquals(resource, Files.readAllBytes(partial.toPath()));
    assertFalse(store.objectFile(HotSignatures.hash(resource)).exists());
  }

  @Test
  public void completedPartialWithWrongHashIsRejectedBeforeItCanBecomeBudgetEvidence()
      throws Exception {
    var store = new ContentStore(temporary.newFolder());
    byte[] runtime = zip("assets/runtime-abi.txt", 1), business = zip("classes.dex", 200);
    byte[] resource = zip("images/星空.bin", 100);
    var manifest = manifest(runtime, business, resource);
    Files.write(store.objectFile(manifest.runtime.sha256).toPath(), runtime);
    Files.write(store.objectFile(manifest.business.sha256).toPath(), business);
    var downloads = new ObjectDownloader(temporary.newFolder(), new DownloadBudget(temporary.newFolder()));
    resource[0] ^= 1;
    var partial = downloads.partial(manifest.artifacts.get(2).sha256);
    Files.write(partial.toPath(), resource);
    assertThrows(IllegalArgumentException.class,
        () -> new PreparationSpace(path -> new PreparationSpace.Volume("same", Long.MAX_VALUE, 4096))
            .beforeDownload(store, downloads, manifest, manifest.runtime.sha256));
    assertArrayEquals(resource, Files.readAllBytes(partial.toPath()));
  }

  @Test
  public void coldCachedNativeAndApkCopiesNeedNoNewCapacity() throws Exception {
    var store = new ContentStore(temporary.newFolder());
    byte[] runtime = zip("lib/x86_64/libcore.so", 1 << 20), business = zip("classes.dex", 200);
    var manifest = manifest(runtime, business, null);
    Files.write(store.objectFile(manifest.runtime.sha256).toPath(), runtime);
    Files.write(store.objectFile(manifest.business.sha256).toPath(), business);
    File snapshot = new File(store.rootDirectory(), "snapshots/" + manifest.snapshotId);
    assertTrue(snapshot.mkdirs());
    Files.write(new File(snapshot, "business.apk").toPath(), business);
    File runtimeDirectory = store.runtimeDirectory(manifest.runtime.sha256);
    Files.write(new File(runtimeDirectory, "runtime.apk").toPath(), runtime);
    File nativeDirectory = new File(runtimeDirectory, "native");
    assertTrue(nativeDirectory.mkdir());
    File library = new File(nativeDirectory, "libcore.so");
    Files.write(library.toPath(), new byte[1 << 20]);
    assertTrue(library.setReadOnly());
    new PreparationSpace(path -> new PreparationSpace.Volume("same", RESERVE + 4096, 4096), "x86_64")
        .beforeLoad(store, new ContentStore.Snapshot(manifest, snapshot), "");
    assertTrue(library.setWritable(true, true));
  }

  @Test
  public void resourceWriteBudgetExcludesAlreadyStoredCodeSizes()
      throws Exception {
    var store = new ContentStore(temporary.newFolder());
    byte[] runtime = new byte[4 << 20], business = new byte[4 << 20];
    business[0] = 1;
    byte[] resource = zip("images/星空.bin", 1 << 20);
    var manifest = manifest(runtime, business, resource);
    Files.write(store.objectFile(manifest.artifacts.get(2).sha256).toPath(), resource);
    File snapshot = new File(store.rootDirectory(), "snapshots/" + manifest.snapshotId);
    assertTrue(snapshot.mkdirs());
    var budget = new PreparationSpace(path -> new PreparationSpace.Volume("same", RESERVE + (2 << 20), 4096));
    File mounted = new ResourceMounts(store, budget)
        .prepare(new ContentStore.Snapshot(manifest, snapshot), Set.of("theme"));
    File expanded = new File(mounted, "theme/images/星空.bin");
    assertEquals(1 << 20, expanded.length());
    assertTrue(expanded.setWritable(true, true));
  }
}
