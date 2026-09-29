package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.time.Instant;
import org.junit.Test;

public final class ActivationPermitTest {
  byte[] read(String name) throws Exception {
    try (InputStream input = getClass().getResourceAsStream("/permit-v1/" + name)) {
      assertNotNull(input);
      return HotPackage.read(input, StrictJson.MAX_BYTES);
    }
  }

  HotTrust trust() throws Exception {
    HotSignatures.PublicKey root =
        new HotSignatures.PublicKey(StrictJson.object(read("root.public.json")));
    return new HotTrust(root, read("trust.json"), read("trust.sig.json"));
  }

  ActivationPermit.Request request() throws Exception {
    StrictJson.Obj value = StrictJson.object(read("permit.json"));
    return new ActivationPermit.Request(
        value.string("installationId"),
        value.string("nonce"),
        value.string("hostIdentity"),
        1,
        value.number("channelRevision"),
        1000,
        new HotManifest(read("manifest.json")));
  }

  Instant serverTime() throws Exception {
    // 公开向量的签发时间被冻结在本地联调当时，测试不依赖运行测试那天的系统日期。
    return Instant.ofEpochSecond(StrictJson.object(read("permit.json")).number("issuedAt") + 1);
  }

  ActivationPermit permit() throws Exception {
    return new ActivationPermit(
        read("permit.json"), read("permit.sig.json"), trust(), request(), 1, 1, serverTime(), 1100);
  }

  @Test
  public void realRustPermitVerifiesAndCanOnlyBeConsumedOnce() throws Exception {
    ActivationPermit permit = permit();
    assertEquals(request().manifest.snapshotId, permit.snapshotId);
    permit.consume(1, 1200);
    assertThrows(IllegalArgumentException.class, () -> permit.consume(1, 1300));
  }

  @Test
  public void newerDecisionExpiryAndOtherNonceAreRejected() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> permit().consume(2, 1200));
    assertThrows(IllegalArgumentException.class, () -> permit().consume(1, 602000));
    ActivationPermit.Request original = request();
    ActivationPermit.Request wrong =
        new ActivationPermit.Request(
            original.installationId,
            HotSignatures.hash(new byte[] {1}),
            original.hostIdentity,
            1,
            1,
            1000,
            original.manifest);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActivationPermit(
                read("permit.json"),
                read("permit.sig.json"),
                trust(),
                wrong,
                1,
                1,
                serverTime(),
                1100));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActivationPermit(
                read("permit.json"),
                read("permit.sig.json"),
                trust(),
                original,
                1,
                2,
                serverTime(),
                1100));
  }

  @Test
  public void contentSignatureCannotBeUsedAsActivationPermit() throws Exception {
    byte[] signature = read("trust.sig.json");
    assertThrows(
        Exception.class,
        () ->
            new ActivationPermit(
                read("permit.json"), signature, trust(), request(), 1, 1, serverTime(), 1100));
  }
}
