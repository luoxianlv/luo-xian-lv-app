package app.luoxianlv.hot;

import android.content.Context;
import app.luoxianlv.update.SignedDelivery;
import app.luoxianlv.update.Verifier;
import java.io.File;
import java.time.Instant;
import java.util.Base64;

/** 普通更新仅复用 APK 固定根与授权下限，不创建热更候选或激活许可。 */
public final class ApkDeliveryVerifier implements Verifier {
  private final HostUpdateConfig config;
  private final TrustStore trust;
  private final File hotState;

  public ApkDeliveryVerifier(Context application) throws Exception {
    config = HostUpdateConfig.read(application);
    StrictJson.require(config != null, "当前安装包没有增量更新信任配置");
    trust =
        new TrustStore(
            new File(application.getNoBackupFilesDir(), "native-update/trust"), config.root);
    hotState = new File(application.getNoBackupFilesDir(), "native-update/state");
  }

  @Override
  public void verify(SignedDelivery delivery, byte[] rawManifest) throws SecurityException {
    try {
      Instant now = Instant.now();
      byte[] document = bytes(delivery.trust), rootSignature = bytes(delivery.trustSignature);
      HotTrust supplied = new HotTrust(config.root, document, rootSignature);
      StrictJson.require(
          supplied.applicationId.equals(config.applicationId)
              && supplied.environment.equals(config.environment),
          "更新授权不属于当前安装环境");
      supplied.verify(HotSignatures.APK_UPDATE, rawManifest, bytes(delivery.signature), now);
      StrictJson.Obj manifest = StrictJson.object(rawManifest);
      StrictJson.require(
          manifest.string("applicationId").equals(config.applicationId)
              && manifest.string("environment").equals(config.environment),
          "更新说明不属于当前安装环境");
      Instant issued = HotManifest.utc(manifest.string("issuedAt"));
      StrictJson.require(!issued.isAfter(now.plusSeconds(300)), "更新说明时间无效");
      // 仅读取日志下限；普通更新不推进热更修订、候选或激活状态。
      long floor = hotState.exists() ? new ActivationJournal(hotState).state().trustVersion : 0;
      TrustStore.Record accepted = trust.accept(document, rootSignature, Math.max(1, floor), now);
      accepted.authority.verify(
          HotSignatures.APK_UPDATE, rawManifest, bytes(delivery.signature), now);
    } catch (Exception failure) {
      throw new SecurityException("更新说明签名验证失败", failure);
    }
  }

  private static byte[] bytes(String value) {
    StrictJson.require(
        value != null && value.length() <= 4L * StrictJson.MAX_BYTES / 3 + 8, "更新授权过大");
    byte[] raw = Base64.getDecoder().decode(value);
    StrictJson.require(
        raw.length > 0
            && raw.length <= StrictJson.MAX_BYTES
            && Base64.getEncoder().encodeToString(raw).equals(value),
        "更新授权编码无效");
    return raw;
  }
}
