package app.luoxianlv.hot;

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** 安装配置的纯 Java 公共规则，构建预检与设备运行时共同使用原源。 */
public final class HostConfigRules {
  public static final int MAX_BYTES = 65536;

  private HostConfigRules() {}

  public static final class Validated {
    public final String applicationId, environment, fingerprint;
    public final long hostContract;
    public final URI origin;
    public final HotSignatures.PublicKey root;
    public final Set<String> mounts;
    public final boolean automatic, testHealthReports;

    private Validated(String installedApplicationId, boolean debuggable, byte[] raw)
        throws Exception {
      StrictJson.require(raw.length > 0 && raw.length <= MAX_BYTES, "热更公钥配置大小无效");
      var value =
          StrictJson.object(raw)
              .only(
                  "schema", "applicationId", "environment", "hostContract", "fingerprint",
                  "origin", "root", "mounts", "automatic", "testHealthReports");
      applicationId = value.string("applicationId");
      environment = value.string("environment");
      fingerprint = value.string("fingerprint");
      hostContract = value.number("hostContract");
      try {
        origin = URI.create(value.string("origin"));
      } catch (IllegalArgumentException invalid) {
        // URI 异常可能携带完整输入（含用户名/密码），不能让它进入构建或设备日志。
        throw new IllegalArgumentException("热更源站 URI 格式无效");
      }
      try {
        root = new HotSignatures.PublicKey(value.object("root"));
      } catch (Exception invalid) {
        throw new IllegalArgumentException("热更根公钥格式、身份或曲线无效");
      }
      automatic = !value.has("automatic") || value.bool("automatic");
      testHealthReports = value.has("testHealthReports") && value.bool("testHealthReports");
      var supported = new HashSet<String>();
      for (String mount : value.strings("mounts"))
        StrictJson.require(HotManifest.validId(mount) && supported.add(mount), "宿主挂载声明重复或无效");
      mounts = Collections.unmodifiableSet(supported);
      boolean localDebug = debuggable && environment.equals("test")
          && "http".equals(origin.getScheme()) && "127.0.0.1".equals(origin.getHost());
      StrictJson.require(
          value.number("schema") == 1
              && applicationId.equals(installedApplicationId)
              && HotManifest.validScope(applicationId, environment)
              && HotManifest.validHash(fingerprint)
              && hostContract > 0 && hostContract <= Integer.MAX_VALUE
              && root.purpose.equals("root")
              && (!testHealthReports || (debuggable && environment.equals("test")))
              && origin.getHost() != null
              && ("https".equals(origin.getScheme()) || localDebug)
              && origin.getRawUserInfo() == null && origin.getRawQuery() == null
              && origin.getRawFragment() == null
              && (origin.getPath().isEmpty() || origin.getPath().equals("/")),
          "安装包热更配置与实际变体身份或源站安全规则不符");
    }
  }

  public static Validated validate(byte[] raw, String applicationId, boolean debuggable)
      throws Exception {
    return new Validated(applicationId, debuggable, raw);
  }
}
