package app.luoxianlv.hot;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public final class ResourceScopeTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
  private static String palette(String color) {
    String block = "{\"primary\":\"" + color + "\",\"onPrimary\":\"#FFFFFFFF\",\"secondary\":\"#FF000000\",\"background\":\"#FF000000\",\"surface\":\"#FF000000\",\"onBackground\":\"#FFFFFFFF\",\"onSurface\":\"#FFFFFFFF\"}";
    return "{\"schema\":1,\"light\":" + block + ",\"dark\":" + block + "}";
  }
  private final class Fixture implements AutoCloseable {
    final ContentStore store;
    final ContentStore.Snapshot snapshot;
    final File root;
    final ResourceScope scope;
    final AutoCloseable owner;
    Fixture(Map<String, Map<String, byte[]>> mounts, Set<String> config) throws Exception {
      store = new ContentStore(temporary.newFolder());
      String code = HotSignatures.hash(new byte[] {1});
      StringBuilder artifacts = new StringBuilder("{\"id\":\"runtime\",\"role\":\"runtime\",\"sha256\":\"" + code + "\",\"size\":1,\"requires\":[]},{\"id\":\"business\",\"role\":\"business\",\"sha256\":\"" + code + "\",\"size\":1,\"requires\":[\"runtime\"],\"entryClass\":\"test.Entry\"}");
      for (String mount : mounts.keySet()) {
        String hash = HotSignatures.hash(bytes(mount + Arrays.toString(mounts.get(mount).values().iterator().next())));
        artifacts.append(",{\"id\":\"").append(mount).append("\",\"role\":\"").append(config.contains(mount) ? "config" : "resources")
            .append("\",\"sha256\":\"").append(hash).append("\",\"size\":1,\"requires\":[],\"mount\":\"").append(mount).append("\"}");
      }
      byte[] raw = bytes("{\"schema\":1,\"kind\":\"hot\",\"applicationId\":\"app.luoxianlv.debug\",\"environment\":\"test\",\"label\":\"resource-test\",\"createdAt\":\"2026-10-01T00:00:00Z\",\"hostContract\":{\"min\":1,\"max\":1},\"runtimeAbi\":\"test-v1\",\"activation\":\"live\",\"stateSchema\":{\"current\":1,\"readable\":{\"min\":1,\"max\":1}},\"artifacts\":[" + artifacts + "]}");
      var manifest = new HotManifest(raw);
      File directory = new File(store.rootDirectory(), "snapshots/" + manifest.snapshotId);
      assertTrue(directory.mkdirs()); Files.write(new File(directory, "manifest.json").toPath(), raw);
      root = new File(directory, "resources"); assertTrue(root.mkdir());
      for (var mount : mounts.entrySet()) for (var entry : mount.getValue().entrySet()) {
        File target = new File(root, mount.getKey() + "/" + entry.getKey());
        assertTrue(target.getParentFile().isDirectory() || target.getParentFile().mkdirs());
        Files.write(target.toPath(), entry.getValue()); assertTrue(target.setReadOnly());
      }
      snapshot = store.snapshot(manifest.snapshotId); owner = store.pin(snapshot);
      scope = new ResourceScope(store, snapshot, root, manifest.snapshotId);
    }
    @Override public void close() throws Exception { scope.retire(); owner.close(); }
  }
  @After public void writable() throws Exception {
    try (var paths = Files.walk(temporary.getRoot().toPath())) { paths.forEach(path -> path.toFile().setWritable(true, true)); }
  }
  @Test public void streamRetainsSnapshotAfterOwnerRetiresUntilExplicitClose() throws Exception {
    Fixture f = new Fixture(Map.of("theme", Map.of("palette.json", bytes(palette("#FF123456")))), Set.of());
    var input = f.scope.open("theme", "palette.json"); f.close();
    assertTrue(ContentLeases.snapshots(f.store.rootDirectory()).contains(f.snapshot.manifest.snapshotId));
    assertThrows(IOException.class, () -> f.scope.open("theme", "palette.json"));
    assertThrows(IllegalStateException.class, () -> f.scope.mounted("theme"));
    assertArrayEquals(bytes(palette("#FF123456")), input.readAllBytes()); input.close(); input.close();
    assertEquals(0, f.scope.openStreams()); assertFalse(ContentLeases.snapshots(f.store.rootDirectory()).contains(f.snapshot.manifest.snapshotId));
  }
  @Test public void twoSnapshotsNeverReadEachOthersMountedPalette() throws Exception {
    try (var old = new Fixture(Map.of("theme", Map.of("palette.json", bytes(palette("#FF123456")))), Set.of());
        var next = new Fixture(Map.of("theme", Map.of("palette.json", bytes(palette("#FF654321")))), Set.of())) {
      old.scope.validate(); next.scope.validate(); assertNotEquals(old.scope.identity(), next.scope.identity());
      try (var a = old.scope.open("theme", "palette.json"); var b = next.scope.open("theme", "palette.json")) {
        assertNotEquals(new String(a.readAllBytes(), StandardCharsets.UTF_8), new String(b.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
  }
  @Test public void declaredMissingWrongColorDuplicateKeyAndUnknownFieldAreRejected() throws Exception {
    for (String value : List.of("{}", palette("red"), palette("#FF123456").replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
        palette("#FF123456").replace("\"schema\":1", "\"schema\":1,\"unknown\":true"))) {
      try (var f = new Fixture(Map.of("theme", Map.of("palette.json", bytes(value))), Set.of())) {
        assertThrows(Exception.class, f.scope::validate);
      }
    }
    try (var f = new Fixture(Map.of("theme", Map.of("other", bytes("x"))), Set.of())) { assertThrows(Exception.class, f.scope::validate); }
  }
  @Test public void configValueIsStrictAndUsedAsDocument() throws Exception {
    String value = "{\"schema\":1,\"harmonicaGainPermille\":500,\"shaderStrengthPermille\":700}";
    try (var f = new Fixture(Map.of("config", Map.of("value", bytes(value))), Set.of("config"))) {
      f.scope.validate(); assertEquals("config", f.scope.kind("config"));
    }
    for (String bad : List.of(value.replace("500", "1001"), value.replace("500", "0.5"), "{}"))
      try (var f = new Fixture(Map.of("config", Map.of("value", bytes(bad))), Set.of("config"))) { assertThrows(Exception.class, f.scope::validate); }
  }
  @Test public void pcmSizeAndLoopRangeAreCheckedForEntireDeclaredSet() throws Exception {
    var data = new HashMap<String, byte[]>(); var index = new StringBuilder();
    for (int midi = 48; midi <= 85; midi++) { index.append(midi).append("\t16\t0\t16\t2\n"); data.put(midi + ".pcm", new byte[32]); }
    data.put("index.tsv", bytes(index.toString()));
    try (var f = new Fixture(Map.of("harmonica", data), Set.of())) { f.scope.validate(); }
    data.put("60.pcm", new byte[30]);
    try (var f = new Fixture(Map.of("harmonica", data), Set.of())) { assertThrows(Exception.class, f.scope::validate); }
    data.put("60.pcm", new byte[32]); data.put("index.tsv", bytes(index.toString().replace("\t16\t0\t16\t2", "\t16\t15\t16\t2")));
    try (var f = new Fixture(Map.of("harmonica", data), Set.of())) { assertThrows(Exception.class, f.scope::validate); }
  }
  @Test public void engineRequiresAllCriticalPathsAndUtf8ShaderSources() throws Exception {
    var engine = new HashMap<String, byte[]>(); for (String name : ResourceScope.ENGINE) engine.put(name, bytes("official-source"));
    try (var f = new Fixture(Map.of("wallpaperengine", engine), Set.of())) { f.scope.validate(); }
    engine.remove("audio.mjs");
    try (var f = new Fixture(Map.of("wallpaperengine", engine), Set.of())) { assertThrows(Exception.class, f.scope::validate); }
    try (var f = new Fixture(Map.of("shaders", Map.of("white-sphere.agsl", new byte[] {(byte) 0xff}, "gravity-lens.agsl", bytes("shader"))), Set.of())) { assertThrows(Exception.class, f.scope::validate); }
  }
  @Test public void missingTraversalWritableAndUnknownMountCannotBeOpened() throws Exception {
    try (var f = new Fixture(Map.of("theme", Map.of("palette.json", bytes(palette("#FF123456")))), Set.of())) {
      assertThrows(IOException.class, () -> f.scope.open("theme", "../palette.json"));
      assertThrows(IOException.class, () -> f.scope.open("theme", "missing"));
      assertThrows(IOException.class, () -> f.scope.open("unknown", "palette.json"));
      assertTrue(new File(f.root, "theme/palette.json").setWritable(true, true));
      assertThrows(IOException.class, () -> f.scope.open("theme", "palette.json"));
      assertEquals(0, f.scope.openStreams());
    }
  }
  @Test public void streamLimitIsBoundedAndClosingReturnsCapacity() throws Exception {
    try (var f = new Fixture(Map.of("theme", Map.of("palette.json", bytes(palette("#FF123456")))), Set.of())) {
      var inputs = new ArrayList<InputStream>();
      try {
        for (int i = 0; i < 128; i++) inputs.add(f.scope.open("theme", "palette.json"));
        assertThrows(IOException.class, () -> f.scope.open("theme", "palette.json"));
        inputs.remove(0).close(); inputs.add(f.scope.open("theme", "palette.json"));
      } finally { for (var input : inputs) input.close(); }
      assertEquals(0, f.scope.openStreams());
    }
  }
  @Test public void androidShaderCapabilityRejectsLowApiWithoutCallingItCorruptContent() throws Exception {
    for (int sdk : List.of(26, 29, 32)) {
      var error = assertThrows(NativeLoader.ResourceUnsupported.class, () -> ResourceScope.requireShaderApi(true, sdk));
      assertEquals("shaders", error.mount); assertEquals(33, error.requiredApi); assertEquals(sdk, error.actualApi);
    }
  }
  @Test public void api33AndOfflineJvmCanProceedToTheirOwnValidation() throws Exception {
    ResourceScope.requireShaderApi(true, 33); ResourceScope.requireShaderApi(true, 37);
    ResourceScope.requireShaderApi(false, 0);
  }
}
