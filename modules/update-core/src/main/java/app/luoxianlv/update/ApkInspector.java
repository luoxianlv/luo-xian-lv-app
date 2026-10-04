package app.luoxianlv.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 只从实际安装宿主获取基线，业务和热更版本不会影响 APK 身份。 */
public final class ApkInspector {
  private final Context context;

  public ApkInspector(Context context) {
    this.context = context.getApplicationContext();
  }

  public Installed installed() throws IOException {
    try {
      PackageInfo info =
          context.getPackageManager().getPackageInfo(context.getPackageName(), signatureFlags());
      if (!hasCertificate(info))
        info =
            context
                .getPackageManager()
                .getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
      if (info.applicationInfo == null || info.applicationInfo.sourceDir == null)
        throw new IOException("无法读取宿主安装包");
      File source = new File(info.applicationInfo.sourceDir).getCanonicalFile();
      boolean universal =
          info.applicationInfo.splitSourceDirs == null
              || info.applicationInfo.splitSourceDirs.length == 0;
      return new Installed(source, code(info), certificate(info), universal);
    } catch (PackageManager.NameNotFoundException failure) {
      throw new IOException("无法读取宿主安装信息", failure);
    }
  }

  public void verifyTarget(
      File file, UpdateManifest manifest, Installed installed, Cancellation cancellation)
      throws IOException {
    ArtifactVerifier.verify(file, manifest.target.size, manifest.target.sha256, cancellation);
    PackageInfo info =
        context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), signatureFlags());
    if (info != null && !hasCertificate(info))
      info =
          context
              .getPackageManager()
              .getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNATURES);
    if (info == null
        || !context.getPackageName().equals(info.packageName)
        || !manifest.applicationId.equals(info.packageName)
        || code(info) != manifest.target.versionCode
        || !manifest.target.versionName.equals(info.versionName))
      throw new IOException("目标 APK 应用或版本不匹配");
    String signer = certificate(info);
    if (!signer.equals(manifest.target.certificateSha256)
        || !signer.equals(installed.certificateSha256)) throw new IOException("目标 APK 安装证书不匹配");
    if (info.splitNames != null && info.splitNames.length != 0) throw new IOException("不支持拆分安装包");
    cancellation.check();
  }

  public void verifyIdentity(UpdateManifest manifest, Installed installed) {
    if (!context.getPackageName().equals(manifest.applicationId)
        || manifest.target.versionCode <= installed.versionCode
        || !installed.certificateSha256.equals(manifest.target.certificateSha256))
      throw new SecurityException("更新说明与实际宿主身份不匹配");
  }

  public void ensureUnchanged(Installed baseline) throws IOException {
    Installed current = installed();
    if (current.versionCode != baseline.versionCode
        || !current.source.equals(baseline.source)
        || !current.certificateSha256.equals(baseline.certificateSha256)
        || current.universal != baseline.universal
        || current.size != baseline.size
        || current.modified != baseline.modified) throw new SecurityException("宿主安装已变化，请重新检查更新");
  }

  private static int signatureFlags() {
    return Build.VERSION.SDK_INT >= 28
        ? PackageManager.GET_SIGNING_CERTIFICATES
        : PackageManager.GET_SIGNATURES;
  }

  private static long code(PackageInfo info) {
    return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
  }

  private static String certificate(PackageInfo info) throws IOException {
    Signature[] signatures = signatures(info);
    if (signatures == null || signatures.length != 1) throw new IOException("安装包必须使用唯一证书");
    try {
      return ArtifactVerifier.hex(
          MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static Signature[] signatures(PackageInfo info) {
    Signature[] current =
        Build.VERSION.SDK_INT >= 28 && info.signingInfo != null
            ? info.signingInfo.getApkContentsSigners()
            : null;
    return current != null && current.length > 0 ? current : info.signatures;
  }

  private static boolean hasCertificate(PackageInfo info) {
    Signature[] values = signatures(info);
    return values != null && values.length != 0;
  }

  public static final class Installed {
    public final File source;
    public final long versionCode;
    public final String certificateSha256;
    public final boolean universal;
    public final long size, modified;

    Installed(File source, long versionCode, String certificateSha256, boolean universal) {
      this.source = source;
      this.versionCode = versionCode;
      this.certificateSha256 = certificateSha256;
      this.universal = universal;
      size = source.length();
      modified = source.lastModified();
    }
  }
}
