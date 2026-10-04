package app.luoxianlv.hot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 字节补丁只是目标对象的传输方式，不能改变签名快照或激活范围。 */
public final class HotByteDeltaPlan {
  public static final String ALGORITHM = "hdiff-w26-zstd-v1";
  public final Map<String, List<Delta>> targets;

  public static final class Delta {
    public final String baseHash, targetHash, patchHash;
    public final long baseSize, targetSize, patchSize;

    Delta(StrictJson.Obj value) {
      value.only("baseSha256", "baseSize", "targetSha256", "targetSize", "patch");
      baseHash = value.string("baseSha256");
      targetHash = value.string("targetSha256");
      baseSize = value.number("baseSize");
      targetSize = value.number("targetSize");
      var patch = value.object("patch").only("algorithm", "sha256", "size", "object");
      patchHash = patch.string("sha256");
      patchSize = patch.number("size");
      StrictJson.require(
          HotManifest.validHash(baseHash)
              && HotManifest.validHash(targetHash)
              && HotManifest.validHash(patchHash)
              && !baseHash.equals(targetHash),
          "字节补丁身份无效");
      StrictJson.require(
          baseSize > 0
              && baseSize <= HotManifest.MAX_EXPANDED
              && targetSize > 0
              && targetSize <= HotManifest.MAX_EXPANDED
              && patchSize > 0
              && patchSize <= HotManifest.MAX_EXPANDED,
          "字节补丁大小无效");
      StrictJson.require(
          ALGORITHM.equals(patch.string("algorithm"))
              && patch.string("object").equals("hot/v2/objects/" + patchHash),
          "字节补丁算法或对象不支持");
      StrictJson.require(
          app.luoxianlv.update.UpdateManifest.worthwhile(targetSize, patchSize), "字节补丁没有达到节省门槛");
    }
  }

  public HotByteDeltaPlan(
      StrictJson.Obj envelope,
      SignedSnapshot candidate,
      HotApiClient api,
      TrustStore trust,
      ActivationJournal journal,
      HotSignatures.PublicKey root,
      Instant now)
      throws Exception {
    envelope.only("schema", "manifest", "signature", "trust", "trustSignature");
    StrictJson.require(envelope.number("schema") == 1, "字节补丁封装不支持");
    byte[] raw = decode(envelope.string("manifest"));
    byte[] document = decode(envelope.string("trust")),
        rootSignature = decode(envelope.string("trustSignature"));
    HotTrust authority = new HotTrust(root, document, rootSignature);
    StrictJson.require(
        authority.applicationId.equals(api.installation.applicationId)
            && authority.environment.equals(api.installation.environment),
        "字节补丁授权环境不符");
    authority.current(Math.max(1, journal.state().trustVersion), now);
    authority.verify(HotSignatures.TRANSPORT, raw, decode(envelope.string("signature")), now);
    var value =
        StrictJson.object(raw)
            .only(
                "schema",
                "type",
                "snapshotId",
                "applicationId",
                "environment",
                "targetVersionCode",
                "hostFingerprint",
                "deltas");
    StrictJson.require(
        value.number("schema") == 1
            && value.string("type").equals("hot-object-deltas")
            && value.string("snapshotId").equals(candidate.manifest.snapshotId)
            && value.string("applicationId").equals(api.installation.applicationId)
            && value.string("environment").equals(api.installation.environment)
            && value.number("targetVersionCode") == api.appVersionCode
            && api.appVersionCode > 0
            && candidate.manifest.targetVersionCode == api.appVersionCode
            && value.string("hostFingerprint").equals(api.hostIdentity),
        "字节补丁不属于此快照和实际宿主");
    var entries = value.objects("deltas");
    StrictJson.require(!entries.isEmpty() && entries.size() <= 1024, "字节补丁数量超限");
    var parsed = new LinkedHashMap<String, List<Delta>>();
    var seen = new HashSet<String>();
    var patchSizes = new LinkedHashMap<String, Long>();
    for (var entry : entries) {
      Delta delta = new Delta(entry);
      StrictJson.require(
          Long.valueOf(delta.targetSize).equals(candidate.manifest.objects.get(delta.targetHash)),
          "字节补丁目标不在签名快照内");
      StrictJson.require(seen.add(delta.baseHash + ":" + delta.targetHash), "字节补丁基线目标重复");
      Long oldSize = patchSizes.put(delta.patchHash, delta.patchSize);
      StrictJson.require(oldSize == null || oldSize == delta.patchSize, "字节补丁大小冲突");
      parsed.computeIfAbsent(delta.targetHash, ignored -> new ArrayList<>()).add(delta);
    }
    // 原始目标及范围认证成功后才推进公共信任下限，防止错误环境污染授权。
    var accepted = trust.accept(document, rootSignature, journal, now);
    accepted.authority.verify(
        HotSignatures.TRANSPORT, raw, decode(envelope.string("signature")), now);
    parsed.replaceAll((hash, values) -> java.util.Collections.unmodifiableList(values));
    targets = java.util.Collections.unmodifiableMap(parsed);
  }

  static byte[] decode(String value) {
    StrictJson.require(value.length() <= 4L * StrictJson.MAX_BYTES / 3 + 8, "字节补丁说明过大");
    byte[] raw = Base64.getDecoder().decode(value);
    StrictJson.require(
        raw.length > 0
            && raw.length <= StrictJson.MAX_BYTES
            && Base64.getEncoder().encodeToString(raw).equals(value),
        "字节补丁编码无效");
    return raw;
  }
}
