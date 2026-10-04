package app.luoxianlv.hot;

import java.util.Arrays;

/** 离线 ZIP 和在线对象下载共用的签名清单；外层传输格式不能放宽内容验证。 */
public final class SignedSnapshot {
  public final HotManifest manifest;
  public final HotTrust trust;
  private final byte[] raw, signature, authority, authoritySignature;

  public SignedSnapshot(
      byte[] raw,
      byte[] signature,
      byte[] authority,
      byte[] authoritySignature,
      HotPackage.Policy policy)
      throws Exception {
    StrictJson.require((authority == null) == (authoritySignature == null), "根授权与签名必须成对提供");
    HotTrust selected =
        authority == null ? null : new HotTrust(policy.root, authority, authoritySignature);
    if (policy.trust != null) {
      HotTrust external = new HotTrust(policy.root, policy.trust, policy.signature);
      if (selected != null && external.version == selected.version)
        StrictJson.require(Arrays.equals(authority, policy.trust), "同版本授权内容冲突");
      if (selected == null || external.version >= selected.version) {
        selected = external;
        authority = policy.trust;
        authoritySignature = policy.signature;
      }
    }
    StrictJson.require(selected != null, "缺少根授权");
    selected.current(policy.minimumTrustVersion, policy.now);
    selected.verify(HotSignatures.MANIFEST, raw, signature, policy.now);
    manifest = new HotManifest(raw);
    StrictJson.require(
        manifest.applicationId.equals(selected.applicationId)
            && manifest.environment.equals(selected.environment),
        "清单范围超出签名授权");
    manifest.compatible(
        policy.applicationId, policy.environment, policy.hostContract, policy.mounts);
    trust = selected;
    this.raw = raw.clone();
    this.signature = signature.clone();
    this.authority = authority.clone();
    this.authoritySignature = authoritySignature.clone();
  }

  public byte[] manifestBytes() {
    return raw.clone();
  }

  public byte[] manifestSignature() {
    return signature.clone();
  }

  public byte[] trustBytes() {
    return authority.clone();
  }

  public byte[] trustSignature() {
    return authoritySignature.clone();
  }
}
