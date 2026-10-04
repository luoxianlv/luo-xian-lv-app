package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ResourceMountsTest {
  @Rule public TemporaryFolder directory = new TemporaryFolder();

  @After
  public void writableFiles() throws Exception {
    try (java.util.stream.Stream<java.nio.file.Path> paths =
        Files.walk(directory.getRoot().toPath())) {
      paths.forEach(path -> path.toFile().setWritable(true, true));
    }
  }

  byte[] zip(String... names) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
      for (String name : names) {
        zip.putNextEntry(new ZipEntry(name));
        zip.write("资源内容".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return buffer.toByteArray();
  }

  ContentStore.Snapshot snapshot(ContentStore store, byte[] resource) throws Exception {
    String hash = HotSignatures.hash(resource);
    Files.write(store.objectFile(hash).toPath(), resource);
    String code = HotSignatures.hash(new byte[] {1});
    String raw =
        "{\"schema\":1,\"kind\":\"hot\",\"applicationId\":\"app.luoxianlv.debug\",\"environment\":\"test\",\"label\":\"资源\",\"createdAt\":\"2026-09-29T00:00:00Z\",\"hostContract\":{\"min\":1,\"max\":1},\"runtimeAbi\":\"runtime-v1\",\"activation\":\"live\",\"stateSchema\":{\"current\":1,\"readable\":{\"min\":1,\"max\":1}},\"artifacts\":[{\"id\":\"runtime\",\"role\":\"runtime\",\"sha256\":\""
            + code
            + "\",\"size\":1,\"requires\":[]},{\"id\":\"business\",\"role\":\"business\",\"sha256\":\""
            + code
            + "\",\"size\":1,\"entryClass\":\"app.test.Entry\",\"requires\":[\"runtime\"]},{\"id\":\"theme\",\"role\":\"resources\",\"sha256\":\""
            + hash
            + "\",\"size\":"
            + resource.length
            + ",\"mount\":\"theme\",\"requires\":[]}]}";
    return new ContentStore.Snapshot(
        new HotManifest(raw.getBytes(StandardCharsets.UTF_8)), directory.newFolder());
  }

  @Test
  public void extractsVerifiedResourceAndRejectsChangesOrExtraFiles() throws Exception {
    ContentStore store = new ContentStore(directory.newFolder());
    ContentStore.Snapshot snapshot = snapshot(store, zip("images/星空.txt"));
    ResourceMounts mounts = new ResourceMounts(store);
    File result = mounts.prepare(snapshot, Collections.singleton("theme"));
    assertEquals(
        "资源内容",
        new String(
            Files.readAllBytes(new File(result, "theme/images/星空.txt").toPath()),
            StandardCharsets.UTF_8));
    mounts.prepare(snapshot, Collections.singleton("theme"));
    Files.write(new File(result, "theme/unlisted.txt").toPath(), new byte[] {1});
    assertThrows(
        IllegalArgumentException.class,
        () -> mounts.prepare(snapshot, Collections.singleton("theme")));
  }

  @Test
  public void rejectsTraversalAmbiguousNamesAndUnknownMountBeforeCommit() throws Exception {
    for (String[] names :
        new String[][] {
          {"../escape"},
          {"A/file", "a/other"},
          {"file", "file/child"},
          {"CON.txt"},
          {"value."},
          {"a\\b"}
        }) {
      ContentStore store = new ContentStore(directory.newFolder());
      ContentStore.Snapshot snapshot = snapshot(store, zip(names));
      assertThrows(
          Exception.class,
          () -> new ResourceMounts(store).prepare(snapshot, Collections.singleton("theme")));
      assertFalse(new File(snapshot.directory, "resources").exists());
    }
    ContentStore store = new ContentStore(directory.newFolder());
    ContentStore.Snapshot snapshot = snapshot(store, zip("safe.txt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceMounts(store).prepare(snapshot, Collections.emptySet()));
  }
}
