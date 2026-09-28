package app.luoxianlv.hot;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 根授权与内容键/许可键分离；最高已见版本由内部持久化存储负责保存。 */
public final class HotTrust {
  public final long version;
  public final String applicationId, environment;
  public final Instant notBefore, notAfter;
  private final Map<String, Grant> keys;
  private final Set<String> revoked;

  private static final class Grant {
    final HotSignatures.PublicKey key;
    final Instant start, end;

    Grant(StrictJson.Obj value) throws Exception {
      value.only("key", "notBefore", "notAfter");
      key = new HotSignatures.PublicKey(value.object("key"));
      StrictJson.require(!key.purpose.equals("root"), "根不能通过授权列表自我替换");
      start = HotManifest.utc(value.string("notBefore"));
      end = HotManifest.utc(value.string("notAfter"));
      StrictJson.require(start.isBefore(end), "密钥有效期无效");
    }
  }

  public HotTrust(HotSignatures.PublicKey root, byte[] raw, byte[] signature) throws Exception {
    HotSignatures.verify(root, HotSignatures.TRUST, raw, signature);
    StrictJson.Obj value =
        StrictJson.object(raw)
            .only(
                "schema",
                "version",
                "applicationId",
                "environment",
                "notBefore",
                "notAfter",
                "keys",
                "revokedKeyIds");
    version = value.number("version");
    applicationId = value.string("applicationId");
    environment = value.string("environment");
    StrictJson.require(
        value.number("schema") == 1
            && version >= 1
            && version <= 9007199254740991L
            && HotManifest.validScope(applicationId, environment),
        "信任列表范围或版本无效");
    notBefore = HotManifest.utc(value.string("notBefore"));
    notAfter = HotManifest.utc(value.string("notAfter"));
    StrictJson.require(notBefore.isBefore(notAfter), "信任列表有效期无效");
    List<StrictJson.Obj> entries = value.objects("keys");
    StrictJson.require(!entries.isEmpty() && entries.size() <= 64, "授权密钥数量无效");
    Map<String, Grant> grants = new HashMap<>();
    for (StrictJson.Obj entry : entries) {
      Grant grant = new Grant(entry);
      StrictJson.require(
          !grant.start.isBefore(notBefore) && !grant.end.isAfter(notAfter), "密钥有效期超出根授权");
      StrictJson.require(grants.put(grant.key.id, grant) == null, "密钥身份重复");
    }
    List<String> revokedIds = value.strings("revokedKeyIds");
    StrictJson.require(revokedIds.size() <= 1024, "撤销列表过大");
    Set<String> revokedSet = new HashSet<>();
    for (String id : revokedIds)
      StrictJson.require(HotManifest.validHash(id) && revokedSet.add(id), "撤销密钥身份无效或重复");
    keys = Collections.unmodifiableMap(grants);
    revoked = Collections.unmodifiableSet(revokedSet);
  }

  public void current(long minimumVersion, Instant now) {
    StrictJson.require(version >= minimumVersion, "拒绝回退信任版本");
    StrictJson.require(!now.isBefore(notBefore) && now.isBefore(notAfter), "信任列表未生效或已过期");
  }

  public void verify(String domain, byte[] raw, byte[] signature, Instant now) throws Exception {
    String keyId = StrictJson.object(signature).string("keyId");
    StrictJson.require(!revoked.contains(keyId), "签名密钥已撤销");
    Grant grant = keys.get(keyId);
    StrictJson.require(grant != null, "签名密钥未授权");
    StrictJson.require(!now.isBefore(grant.start) && now.isBefore(grant.end), "签名密钥未生效或已过期");
    HotSignatures.verify(grant.key, domain, raw, signature);
  }
}
