package app.luoxianlv.input;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import org.json.JSONObject;

/** 本机无线调试身份仅存于不参与备份的私有目录；读取损坏时不悄悄更换身份。 */
final class WirelessIdentity {
  final PrivateKey privateKey;
  final X509Certificate certificate;

  private WirelessIdentity(PrivateKey key, X509Certificate cert) {
    privateKey = key;
    certificate = cert;
  }

  static WirelessIdentity load(Context context) throws Exception {
    File directory = new File(context.getNoBackupFilesDir(), "input-wireless");
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法保存无线调试身份");
    AtomicFile storage = new AtomicFile(new File(directory, "identity.json"));
    if (storage.getBaseFile().exists()) {
      byte[] bytes = storage.readFully();
      if (bytes.length > 32768) throw new IOException("无线调试身份文件异常");
      JSONObject object = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
      PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(
          Base64.decode(object.getString("privateKey"), Base64.NO_WRAP)));
      X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
          .generateCertificate(new ByteArrayInputStream(Base64.decode(object.getString("certificate"), Base64.NO_WRAP)));
      verifyPair(key, cert);
      return new WirelessIdentity(key, cert);
    }
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048, new SecureRandom());
    KeyPair pair = generator.generateKeyPair();
    X509Certificate cert = selfSigned(pair);
    JSONObject object = new JSONObject();
    object.put("privateKey", Base64.encodeToString(pair.getPrivate().getEncoded(), Base64.NO_WRAP));
    object.put("certificate", Base64.encodeToString(cert.getEncoded(), Base64.NO_WRAP));
    FileOutputStream output = null;
    try {
      output = storage.startWrite();
      output.write(object.toString().getBytes(StandardCharsets.UTF_8));
      storage.finishWrite(output);
    } catch (IOException failure) {
      if (output != null) storage.failWrite(output);
      throw failure;
    }
    return new WirelessIdentity(pair.getPrivate(), cert);
  }

  private static void verifyPair(PrivateKey key, X509Certificate cert) throws Exception {
    byte[] challenge = new byte[32];
    new SecureRandom().nextBytes(challenge);
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(key);
    signature.update(challenge);
    byte[] signed = signature.sign();
    signature.initVerify(cert.getPublicKey());
    signature.update(challenge);
    if (!signature.verify(signed)) throw new IOException("无线调试身份不匹配，请重新配对");
  }

  /** 仅编码标准 X.509 结构；RSA 签名与证书解析均使用系统 JCA。 */
  static X509Certificate selfSigned(KeyPair pair) throws Exception {
    byte[] algorithm = der(0x30, hex("06092a864886f70d01010b0500"));
    byte[] name = der(0x30, der(0x31, der(0x30, hex("0603550403"),
        der(0x0c, "Luoxianlv Wireless".getBytes(StandardCharsets.UTF_8)))));
    byte[] validity = der(0x30, der(0x17, "200101000000Z".getBytes(StandardCharsets.US_ASCII)),
        der(0x17, "491231235959Z".getBytes(StandardCharsets.US_ASCII)));
    byte[] serial = new BigInteger(128, new SecureRandom()).add(BigInteger.ONE).toByteArray();
    byte[] tbs = der(0x30, der(0xa0, der(0x02, new byte[]{2})), der(0x02, serial), algorithm,
        name, validity, name, pair.getPublic().getEncoded());
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(pair.getPrivate());
    signature.update(tbs);
    byte[] encoded = der(0x30, tbs, algorithm, der(0x03, new byte[]{0}, signature.sign()));
    X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
        .generateCertificate(new ByteArrayInputStream(encoded));
    cert.verify(pair.getPublic());
    return cert;
  }

  private static byte[] der(int tag, byte[]... parts) {
    int length = 0;
    for (byte[] part : parts) length += part.length;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.write(tag);
    if (length < 128) output.write(length);
    else {
      int count = length > 65535 ? 3 : length > 255 ? 2 : 1;
      output.write(0x80 | count);
      for (int shift = (count - 1) * 8; shift >= 0; shift -= 8) output.write(length >> shift & 255);
    }
    for (byte[] part : parts) output.write(part, 0, part.length);
    return output.toByteArray();
  }

  private static byte[] hex(String source) {
    byte[] value = new byte[source.length() / 2];
    for (int i = 0; i < value.length; i++) value[i] = (byte) Integer.parseInt(source.substring(i * 2, i * 2 + 2), 16);
    return value;
  }
}
