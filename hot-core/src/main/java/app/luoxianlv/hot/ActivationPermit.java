package app.luoxianlv.hot;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一次进程内激活尝试的短期许可；发现新修订后，旧许可即使未到期也失效。 */
public final class ActivationPermit {
  public final String attemptId;
  public final String snapshotId;
  public final long revision;
  public final long trustVersion;
  public final boolean recovery;
  private final long deadlineElapsedMillis;
  private final AtomicBoolean consumed = new AtomicBoolean();

  public static final class Request {
    public final String installationId, nonce, hostIdentity;
    public final long hostContract, revision, startedElapsedMillis;
    public final HotManifest manifest;

    public Request(
        String installationId,
        String nonce,
        String hostIdentity,
        long hostContract,
        long revision,
        long startedElapsedMillis,
        HotManifest manifest) {
      UUID.fromString(installationId);
      StrictJson.require(
          HotManifest.validHash(nonce)
              && HotManifest.validHash(hostIdentity)
              && hostContract > 0
              && revision > 0
              && startedElapsedMillis >= 0,
          "激活请求身份无效");
      this.installationId = installationId;
      this.nonce = nonce;
      this.hostIdentity = hostIdentity;
      this.hostContract = hostContract;
      this.revision = revision;
      this.startedElapsedMillis = startedElapsedMillis;
      this.manifest = manifest;
    }
  }

  /** serverTime 必须来自已认证的 API 连接时间，不能直接拿许可自身时间替代。 */
  public ActivationPermit(
      byte[] raw,
      byte[] signature,
      HotTrust authority,
      Request request,
      long minimumTrustVersion,
      long minimumRevision,
      Instant serverTime,
      long nowElapsedMillis)
      throws Exception {
    authority.current(minimumTrustVersion, serverTime);
    authority.verify(HotSignatures.ACTIVATION, raw, signature, serverTime);
    trustVersion = authority.version;
    StrictJson.Obj value =
        StrictJson.object(raw)
            .only(
                "schema",
                "installationId",
                "nonce",
                "attemptId",
                "applicationId",
                "environment",
                "hostIdentity",
                "hostContract",
                "snapshotId",
                "runtimeAbi",
                "channelRevision",
                "issuedAt",
                "expiresAt",
                "recovery");
    attemptId = value.string("attemptId");
    UUID.fromString(attemptId);
    snapshotId = value.string("snapshotId");
    revision = value.number("channelRevision");
    recovery = value.bool("recovery");
    HotManifest manifest = request.manifest;
    StrictJson.require(
        value.number("schema") == 1
            && value.string("installationId").equals(request.installationId)
            && value.string("nonce").equals(request.nonce)
            && value.string("hostIdentity").equals(request.hostIdentity)
            && value.number("hostContract") == request.hostContract,
        "许可不属于当前安装或请求");
    StrictJson.require(
        value.string("applicationId").equals(manifest.applicationId)
            && value.string("environment").equals(manifest.environment)
            && authority.applicationId.equals(manifest.applicationId)
            && authority.environment.equals(manifest.environment)
            && snapshotId.equals(manifest.snapshotId)
            && value.string("runtimeAbi").equals(manifest.runtimeAbi),
        "许可目标与已验证候选不一致");
    StrictJson.require(revision == request.revision && revision >= minimumRevision, "许可的渠道修订已过时");
    long issued = value.number("issuedAt"),
        expires = value.number("expiresAt"),
        wall = serverTime.getEpochSecond();
    StrictJson.require(
        issued > 0
            && issued <= wall + 30
            && expires > issued
            && expires - issued <= 600
            && expires > wall,
        "激活许可未生效、过期或时长超限");
    StrictJson.require(
        nowElapsedMillis >= request.startedElapsedMillis
            && nowElapsedMillis - request.startedElapsedMillis < 600000,
        "请求等待过久，需要重新取得许可");
    long remaining = Math.min(expires - wall, expires - issued) * 1000;
    deadlineElapsedMillis =
        Math.min(
            Math.addExact(nowElapsedMillis, remaining),
            Math.addExact(request.startedElapsedMillis, (expires - issued) * 1000));
    StrictJson.require(deadlineElapsedMillis > nowElapsedMillis, "激活许可已经失效");
  }

  /** 由激活控制器在安全点事务内调用；消耗后不能用于第二次切换。 */
  public void consume(long latestRevision, long nowElapsedMillis) {
    requireCurrent(latestRevision, trustVersion, nowElapsedMillis);
    StrictJson.require(consumed.compareAndSet(false, true), "许可已经使用");
  }

  /** 消耗许可后到页面曝光前仍需检查；许可不会因已经消耗而豁免撤回和超时。 */
  public void requireCurrent(long latestRevision, long latestTrustVersion, long nowElapsedMillis) {
    StrictJson.require(
        revision >= latestRevision
            && trustVersion == latestTrustVersion
            && nowElapsedMillis >= 0
            && nowElapsedMillis < deadlineElapsedMillis,
        "许可已过期或已收到更新决定");
  }
}
