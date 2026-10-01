package app.luoxianlv.hot;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import java.io.FileNotFoundException;
import java.net.URI;
import java.util.Set;

/** 只从安装包读取信任根与源站；未配置时保留内置业务，不接受外部文件改写信任。 */
public final class HostUpdateConfig {
  public final String applicationId, environment, fingerprint;
  public final long hostContract;
  public final URI origin;
  public final HotSignatures.PublicKey root;
  public final Set<String> mounts;
  public final boolean automatic, testHealthReports;

  private HostUpdateConfig(Context context, byte[] raw) throws Exception {
    var value = HostConfigRules.validate(raw, context.getPackageName(),
        (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
    applicationId = value.applicationId;
    environment = value.environment;
    fingerprint = value.fingerprint;
    hostContract = value.hostContract;
    origin = value.origin;
    root = value.root;
    mounts = value.mounts;
    automatic = value.automatic;
    testHealthReports = value.testHealthReports;
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
