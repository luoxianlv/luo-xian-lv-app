package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ProtocolTest {
  @Rule public TemporaryFolder directory = new TemporaryFolder();

  byte[] resource(String name) throws Exception {
    try (InputStream input = getClass().getResourceAsStream("/protocol-v1/" + name)) {
      assertNotNull("缺少公开向量 " + name, input);
      return HotPackage.read(input, StrictJson.MAX_BYTES);
    }
  }

  File fixture(String name) throws Exception {
    File file = directory.newFile(name);
    Files.write(file.toPath(), resource(name));
    return file;
  }

  HotPackage.Policy policy() throws Exception {
    HotSignatures.PublicKey root =
        new HotSignatures.PublicKey(StrictJson.object(resource("root.public.json")));
    return new HotPackage.Policy(
        root,
        "app.luoxianlv.debug",
        "test",
        1,
        1,
        Instant.parse("2026-09-29T00:00:00Z"),
        Collections.emptySet(),
        null,
        null);
  }

  @Test
  public void goFullAndDeltaHaveSameTargetIdentity() throws Exception {
    StrictJson.Obj expected = StrictJson.object(resource("expected.json"));
    try (HotPackage base = new HotPackage(fixture("base.lxhp"), policy());
        HotPackage target = new HotPackage(fixture("target.lxhp"), policy());
        HotPackage delta = new HotPackage(fixture("delta.lxhp"), policy())) {
      assertEquals(expected.string("baseSnapshotId"), base.manifest.snapshotId);
      assertEquals(expected.string("targetSnapshotId"), target.manifest.snapshotId);
      assertEquals(target.manifest.snapshotId, delta.manifest.snapshotId);
      assertArrayEquals(target.manifestSignature(), delta.manifestSignature());
      assertEquals(base.manifest.snapshotId, delta.baseSnapshotId);
      assertEquals(2, target.included.size());
      assertEquals(1, delta.included.size());
      ByteArrayOutputStream a = new ByteArrayOutputStream(), b = new ByteArrayOutputStream();
      target.copyObject(target.manifest.business.sha256, a);
      delta.copyObject(delta.manifest.business.sha256, b);
      assertArrayEquals(a.toByteArray(), b.toByteArray());
    }
  }

  @Test
  public void exactInstallationVersionIsIndependentOfHostContract() throws Exception {
    try (HotPackage pack = new HotPackage(fixture("target.lxhp"), policy())) {
      String raw = new String(pack.manifestBytes(), StandardCharsets.UTF_8);
      HotManifest legacy = new HotManifest(raw.getBytes(StandardCharsets.UTF_8));
      legacy.requireVersion(17);
      HotManifest targeted = new HotManifest(raw.replaceFirst("\\{", "{\"targetVersionCode\":17,").getBytes(StandardCharsets.UTF_8));
      targeted.requireVersion(17);
      assertThrows(IllegalArgumentException.class, () -> targeted.requireVersion(18));
      assertThrows(IllegalArgumentException.class, () -> targeted.requireVersion(0));
      assertThrows(IllegalArgumentException.class, () -> new HotManifest(raw.replaceFirst("\\{", "{\"targetVersionCode\":-1,").getBytes(StandardCharsets.UTF_8)));
    }
  }

  @Test
  public void strictJsonRejectsCrossLanguageAmbiguities() {
    String[] bad = {
      "{\"x\":1,\"x\":2}",
      "{\"x\":1,\"\\u0078\":2}",
      "{\"x\":1.0}",
      "{\"x\":1e0}",
      "{\"x\":01}",
      "{\"x\":null}",
      "{\"x\":\"\\ud800\"}",
      "{\"x\":\"\\udc00\"}",
      "{\"x\":\"\\ud800\\u0041\"}",
      "{}{}",
      "\ufeff{}"
    };
    for (String raw : bad)
      assertThrows(
          raw,
          IllegalArgumentException.class,
          () -> StrictJson.object(raw.getBytes(StandardCharsets.UTF_8)));
    assertEquals(
        "🎵",
        StrictJson.object("{\"x\":\"\\ud83c\\udfb5\"}".getBytes(StandardCharsets.UTF_8))
            .string("x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> StrictJson.object(new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xff, '"', '}'}));
    assertThrows(
        IllegalArgumentException.class,
        () -> StrictJson.object("{\"Schema\":1}".getBytes(StandardCharsets.UTF_8)).only("schema"));
  }

  @Test
  public void tamperAndForeignScopeAreRejected() throws Exception {
    File original = fixture("target.lxhp");
    File changed = directory.newFile("changed.lxhp");
    try (ZipFile source = new ZipFile(original);
        ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(changed.toPath()))) {
      java.util.Enumeration<? extends ZipEntry> entries = source.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        out.putNextEntry(new ZipEntry(entry.getName()));
        try (InputStream input = source.getInputStream(entry)) {
          out.write(HotPackage.read(input, StrictJson.MAX_BYTES));
        }
        if (entry.getName().equals("manifest.json")) out.write(' ');
        out.closeEntry();
      }
    }
    assertThrows(
        Exception.class,
        () -> {
          try (HotPackage ignored = new HotPackage(changed, policy())) {}
        });
    HotPackage.Policy originalPolicy = policy();
    HotPackage.Policy production =
        new HotPackage.Policy(
            originalPolicy.root,
            "app.luoxianlv.debug",
            "production",
            1,
            1,
            originalPolicy.now,
            Collections.emptySet(),
            null,
            null);
    assertThrows(
        Exception.class,
        () -> {
          try (HotPackage ignored = new HotPackage(original, production)) {}
        });
    HotPackage.Policy floor =
        new HotPackage.Policy(
            originalPolicy.root,
            "app.luoxianlv.debug",
            "test",
            1,
            2,
            originalPolicy.now,
            Collections.emptySet(),
            null,
            null);
    assertThrows(
        Exception.class,
        () -> {
          try (HotPackage ignored = new HotPackage(original, floor)) {}
        });
  }

  @Test
  public void rootCannotBeReplacedByItsOwnPackage() throws Exception {
    HotSignatures.PublicKey root = policy().root;
    byte[] raw = resource("trust.json"), sig = resource("trust.sig.json");
    HotSignatures.verify(root, HotSignatures.TRUST, raw, sig);
    assertThrows(
        Exception.class, () -> HotSignatures.verify(root, HotSignatures.MANIFEST, raw, sig));
    byte[] altered = java.util.Arrays.copyOf(raw, raw.length + 1);
    altered[raw.length] = ' ';
    assertThrows(
        Exception.class, () -> HotSignatures.verify(root, HotSignatures.TRUST, altered, sig));
    HotTrust trust = new HotTrust(root, raw, sig);
    assertThrows(
        IllegalArgumentException.class,
        () -> trust.current(1, Instant.parse("2031-01-01T00:00:00Z")));
  }
}
