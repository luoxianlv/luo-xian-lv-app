package app.luoxianlv.hot;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;

/** 保守匹配真实编译 SDK；只有独立 Debug 核心测试 APK 可使用无标记的旧实验。 */
final class CompiledContract {
  private static final String PATH = "hot/host-contract.sha256";

  private CompiledContract() {}

  static void require(Context application, File business) throws Exception {
    Context installed = application.createPackageContext(application.getPackageName(), 0);
    String expected;
    try (var input = installed.getAssets().open(PATH)) {
      expected = read(HotPackage.read(input, 65));
    } catch (FileNotFoundException legacyExperiment) {
      StrictJson.require(
          legacyTest(application.getPackageName(), application.getApplicationInfo().flags),
          "安装宿主缺少实际编译 SDK 身份，不能加载原生热更业务");
      return;
    }
    require(expected, business);
  }

  static boolean legacyTest(String packageName, int flags) {
    return packageName.equals("app.luoxianlv.hot.test")
        && (flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
  }

  static void require(String expected, File business) throws Exception {
    StrictJson.require(HotManifest.validHash(expected), "宿主编译契约身份无效");
    try (var zip = new ZipContainer(business, 50_000, Long.MAX_VALUE, true)) {
      var entry = zip.entries.get("assets/" + PATH);
      StrictJson.require(entry != null && !entry.directory, "业务包缺少实际编译的宿主 SDK 身份");
      try (var input = zip.open(entry)) {
        String required = read(HotPackage.read(input, 65));
        StrictJson.require(expected.equals(required), "业务编译 SDK 与安装宿主不匹配，须使用兼容 SDK 或升级安装包");
      }
    }
  }

  private static String read(byte[] raw) {
    StrictJson.require(raw.length == 65 && raw[64] == '\n', "编译契约标记格式无效");
    String hash = new String(raw, 0, 64, StandardCharsets.US_ASCII);
    StrictJson.require(HotManifest.validHash(hash), "编译契约标记哈希无效");
    return hash;
  }
}
