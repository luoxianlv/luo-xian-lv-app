package app.luoxianlv.hot;

import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** 精确验证 Go 协议的域前缀与原始 JSON 字节，不重新序列化、不重复哈希。 */
public final class HotSignatures {
  public static final String MANIFEST = "LXHOT-MANIFEST-V1";
  public static final String TRANSPORT = "LXHOT-TRANSPORT-V1";
  public static final String TRUST = "LXHOT-TRUST-V1";
  public static final String ACTIVATION = "LXHOT-ACTIVATION-V1";
  public static final String ALGORITHM = "ecdsa-p256-sha256";

  private HotSignatures() {}

  public static final class PublicKey {
    public final String id;
    public final String purpose;
    final ECPublicKey key;

    public PublicKey(StrictJson.Obj value) throws Exception {
      value.only("schema", "algorithm", "keyId", "purpose", "spki");
      StrictJson.require(
          value.number("schema") == 1 && ALGORITHM.equals(value.string("algorithm")), "公钥格式不支持");
      id = value.string("keyId");
      purpose = value.string("purpose");
      StrictJson.require(
          purpose.equals("root") || purpose.equals("content") || purpose.equals("activation"),
          "公钥用途无效");
      byte[] der = Base64.getDecoder().decode(value.string("spki"));
      StrictJson.require(der.length <= 256 && hash(der).equals(id), "公钥身份不符");
      java.security.PublicKey parsed =
          KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
      StrictJson.require(parsed instanceof ECPublicKey, "只支持 EC 公钥");
      key = (ECPublicKey) parsed;
      AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
      parameters.init(new ECGenParameterSpec("secp256r1"));
      ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class);
      ECParameterSpec actual = key.getParams();
      StrictJson.require(
          actual.getOrder().equals(expected.getOrder())
              && actual.getCurve().equals(expected.getCurve())
              && actual.getGenerator().equals(expected.getGenerator())
              && actual.getCofactor() == expected.getCofactor(),
          "只支持 P-256 曲线");
    }
  }

  public static String hash(byte[] raw) throws Exception {
    return hex(MessageDigest.getInstance("SHA-256").digest(raw));
  }

  static String hex(byte[] data) {
    char[] digits = "0123456789abcdef".toCharArray();
    char[] value = new char[data.length * 2];
    for (int i = 0; i < data.length; i++) {
      value[i * 2] = digits[(data[i] & 255) >>> 4];
      value[i * 2 + 1] = digits[data[i] & 15];
    }
    return new String(value);
  }

  public static void verify(PublicKey key, String domain, byte[] raw, byte[] signature)
      throws Exception {
    String purpose =
        domain.equals(TRUST)
            ? "root"
            : domain.equals(ACTIVATION)
                ? "activation"
                : domain.equals(MANIFEST) || domain.equals(TRANSPORT) ? "content" : "";
    StrictJson.require(!purpose.isEmpty() && key.purpose.equals(purpose), "密钥用途不允许该签名");
    StrictJson.require(raw.length > 0 && raw.length <= StrictJson.MAX_BYTES, "签名内容长度无效");
    StrictJson.Obj envelope = StrictJson.object(signature).only("algorithm", "keyId", "signature");
    StrictJson.require(
        envelope.string("algorithm").equals(ALGORITHM) && envelope.string("keyId").equals(key.id),
        "签名密钥或算法不匹配");
    byte[] der = Base64.getDecoder().decode(envelope.string("signature"));
    validateDer(der);
    Signature verifier = Signature.getInstance("SHA256withECDSA");
    verifier.initVerify(key.key);
    verifier.update(domain.getBytes(StandardCharsets.US_ASCII));
    verifier.update((byte) 0);
    verifier.update(raw);
    StrictJson.require(verifier.verify(der), "签名验证失败");
  }

  private static void validateDer(byte[] bytes) {
    StrictJson.require(
        bytes.length >= 8
            && bytes.length <= 72
            && bytes[0] == 0x30
            && (bytes[1] & 255) == bytes.length - 2,
        "签名 DER 编码无效");
    int position = 2;
    for (int i = 0; i < 2; i++) {
      StrictJson.require(position + 2 <= bytes.length && bytes[position++] == 2, "签名 DER 整数缺失");
      int length = bytes[position++] & 255;
      StrictJson.require(
          length >= 1 && length <= 33 && position + length <= bytes.length && bytes[position] >= 0,
          "签名 DER 整数无效");
      StrictJson.require(
          length == 1 || bytes[position] != 0 || bytes[position + 1] < 0, "签名 DER 不是最短编码");
      position += length;
    }
    StrictJson.require(position == bytes.length, "签名 DER 含尾随内容");
  }
}
