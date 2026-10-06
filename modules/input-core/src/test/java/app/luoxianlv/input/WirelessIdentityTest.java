package app.luoxianlv.input;

import static org.junit.Assert.*;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import org.junit.Test;

public class WirelessIdentityTest {
  @Test public void certificateMatchesRsaIdentity() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair identity = generator.generateKeyPair();
    X509Certificate certificate = WirelessIdentity.selfSigned(identity);
    certificate.verify(identity.getPublic());
    assertArrayEquals(identity.getPublic().getEncoded(), certificate.getPublicKey().getEncoded());
    assertEquals("SHA256withRSA", certificate.getSigAlgName());
    assertTrue(certificate.getSubjectX500Principal().getName().contains("Luoxianlv"));
    assertTrue(certificate.getSerialNumber().signum() > 0);
  }

  @Test public void shellPathsAreQuotedAsSingleArguments() {
    assertEquals("'/data/app/normal/base.apk'", WirelessAdbBackend.quote("/data/app/normal/base.apk"));
    assertEquals("'a'\\''b'", WirelessAdbBackend.quote("a'b"));
    assertEquals("'$(id); anything'", WirelessAdbBackend.quote("$(id); anything"));
  }
}
