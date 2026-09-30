package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 测试私钥只在内存生成，不读取或保存真实发布凭据。 */
public final class StableRevocationTest {
  @Rule public TemporaryFolder directory = new TemporaryFolder();

  private KeyPair pair() throws Exception {
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  private String id(KeyPair pair) throws Exception {
    return HotSignatures.hash(pair.getPublic().getEncoded());
  }

  private String publicKey(KeyPair pair, String purpose) throws Exception {
    return "{\"schema\":1,\"algorithm\":\"ecdsa-p256-sha256\",\"keyId\":\""
        + id(pair)
        + "\",\"purpose\":\""
        + purpose
        + "\",\"spki\":\""
        + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())
        + "\"}";
  }

  private byte[] sign(KeyPair pair, String domain, byte[] raw) throws Exception {
    Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(pair.getPrivate());
    signer.update(domain.getBytes(StandardCharsets.US_ASCII));
    signer.update((byte) 0);
    signer.update(raw);
    return ("{\"algorithm\":\"ecdsa-p256-sha256\",\"keyId\":\""
            + id(pair)
            + "\",\"signature\":\""
            + Base64.getEncoder().encodeToString(signer.sign())
            + "\"}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private byte[] authority(KeyPair content, long version, boolean revoked) throws Exception {
    return ("{\"schema\":1,\"version\":"
            + version
            + ",\"applicationId\":\"app.luoxianlv.debug\",\"environment\":\"test\","
            + "\"notBefore\":\"2026-01-01T00:00:00Z\",\"notAfter\":\"2027-01-01T00:00:00Z\","
            + "\"keys\":[{\"key\":"
            + publicKey(content, "content")
            + ",\"notBefore\":\"2026-01-01T00:00:00Z\",\"notAfter\":\"2027-01-01T00:00:00Z\"}],"
            + "\"revokedKeyIds\":["
            + (revoked ? "\"" + id(content) + "\"" : "")
            + "]}")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  public void newerRevocationStopsStableOfflineCodeDespiteValidHistoricalSignature()
      throws Exception {
    KeyPair root = pair(), content = pair();
    var key =
        new HotSignatures.PublicKey(
            StrictJson.object(publicKey(root, "root").getBytes(StandardCharsets.UTF_8)));
    var trust = new TrustStore(directory.newFolder(), key);
    var journal = new ActivationJournal(directory.newFolder());
    byte[] accepted = authority(content, 1, false);
    byte[] signature = sign(root, HotSignatures.TRUST, accepted);
    trust.accept(accepted, signature, journal, Instant.parse("2026-09-29T00:00:00Z"));
    byte[] raw = null;
    // 使用完整协议清单，且所有元数据由本测试独立签名。
    try (var zip =
        new java.util.zip.ZipInputStream(
            new java.io.ByteArrayInputStream(new ContentStoreTest().data("base.lxhp")))) {
      java.util.zip.ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null)
        if (entry.getName().equals("manifest.json")) {
          raw = zip.readAllBytes();
          break;
        }
    }
    assertNotNull("测试包缺少完整清单", raw);
    var manifest = new HotManifest(raw);
    File metadata = directory.newFolder();
    Files.write(new File(metadata, "manifest.json").toPath(), raw);
    Files.write(
        new File(metadata, "manifest.sig.json").toPath(),
        sign(content, HotSignatures.MANIFEST, raw));
    Files.write(new File(metadata, "trust.json").toPath(), accepted);
    Files.write(new File(metadata, "trust.sig.json").toPath(), signature);
    var snapshot = new ContentStore.Snapshot(manifest, metadata);
    String attempt = journal.begin(manifest.snapshotId, 1, 1, 123, 1000);
    journal.firstFrame(attempt);
    journal.healthy(attempt, 60000);
    trust.verifyStable(snapshot, journal.state());
    byte[] revoked = authority(content, 2, true);
    trust.accept(
        revoked,
        sign(root, HotSignatures.TRUST, revoked),
        journal,
        Instant.parse("2026-09-30T00:00:00Z"));
    assertThrows(
        IllegalArgumentException.class, () -> trust.verifyStable(snapshot, journal.state()));
    assertEquals(manifest.snapshotId, journal.state().stable);
    assertEquals(2, journal.state().trustVersion);
  }
}
