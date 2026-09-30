package app.luoxianlv.hot;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import java.io.FileNotFoundException;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** 只从安装包读取信任根与源站；未配置时保留内置业务，不接受外部文件改写信任。 */
public final class HostUpdateConfig {
  public final String applicationId, environment, fingerprint;
  public final long hostContract;
  public final URI origin;
  public final HotSignatures.PublicKey root;
  public final Set<String> mounts;

  private HostUpdateConfig(Context context, byte[] raw) throws Exception {
    var value =
        StrictJson.object(raw)
            .only(
                "schema",
                "applicationId",
                "environment",
                "hostContract",
                "fingerprint",
                "origin",
                "root",
                "mounts");
    applicationId = value.string("applicationId");
    environment = value.string("environment");
    fingerprint = value.string("fingerprint");
    hostContract = value.number("hostContract");
    origin = URI.create(value.string("origin"));
    root = new HotSignatures.PublicKey(value.object("root"));
    var supported = new HashSet<String>();
    for (String mount : value.strings("mounts"))
      StrictJson.require(HotManifest.validId(mount) && supported.add(mount), "宿主挂载声明重复或无效");
    mounts = Collections.unmodifiableSet(supported);
    boolean localDebug =
        (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
            && environment.equals("test")
            && "http".equals(origin.getScheme())
            && "127.0.0.1".equals(origin.getHost());
    StrictJson.require(
        value.number("schema") == 1
            && applicationId.equals(context.getPackageName())
            && HotManifest.validScope(applicationId, environment)
            && HotManifest.validHash(fingerprint)
            && hostContract > 0
            && hostContract <= Integer.MAX_VALUE
            && root.purpose.equals("root")
            && origin.getHost() != null
            && ("https".equals(origin.getScheme()) || localDebug)
            && origin.getRawUserInfo() == null
            && origin.getRawQuery() == null
            && origin.getRawFragment() == null
            && (origin.getPath().isEmpty() || origin.getPath().equals("/")),
        "安装包热更配置无效");
  }

  public static HostUpdateConfig read(Context application) throws Exception {
    Context installed = application.createPackageContext(application.getPackageName(), 0);
    try (var input = installed.getAssets().open("hot/config.json")) {
      return new HostUpdateConfig(application, HotPackage.read(input, 65536));
    } catch (FileNotFoundException absent) {
      return null;
    }
  }
}
