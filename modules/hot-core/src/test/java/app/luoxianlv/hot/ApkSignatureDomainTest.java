package app.luoxianlv.hot;

import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.Test;

/** 普通更新、热更和激活许可不能相互借用签名，字节改动也必须被拒绝。 */
public final class ApkSignatureDomainTest {
  @Test
  public void signedApkCannotBecomeHotMetadataOrActivation() throws Exception {
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    var pair = generator.generateKeyPair();
    String id = HotSignatures.hash(pair.getPublic().getEncoded());
    var key =
        new HotSignatures.PublicKey(
            StrictJson.object(
                JsonWire.encode(
                    JsonWire.fields(
                        "schema",
                        1,
                        "algorithm",
                        HotSignatures.ALGORITHM,
                        "keyId",
                        id,
                        "purpose",
                        "content",
                        "spki",
                        Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())))));
    byte[] raw = "{\"type\":\"apk-update\"}".getBytes(StandardCharsets.UTF_8);
    var signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(pair.getPrivate());
    signer.update(HotSignatures.APK_UPDATE.getBytes(StandardCharsets.US_ASCII));
    signer.update((byte) 0);
    signer.update(raw);
    byte[] envelope =
        JsonWire.encode(
            JsonWire.fields(
                "algorithm",
                HotSignatures.ALGORITHM,
                "keyId",
                id,
                "signature",
                Base64.getEncoder().encodeToString(signer.sign())));
    HotSignatures.verify(key, HotSignatures.APK_UPDATE, raw, envelope);
    for (String domain :
        new String[] {
          HotSignatures.MANIFEST,
          HotSignatures.TRANSPORT,
          HotSignatures.ACTIVATION,
          HotSignatures.TRUST
        }) {
      assertThrows(Exception.class, () -> HotSignatures.verify(key, domain, raw, envelope));
    }
    raw[raw.length - 1] ^= 1;
    assertThrows(
        Exception.class, () -> HotSignatures.verify(key, HotSignatures.APK_UPDATE, raw, envelope));
  }
}
