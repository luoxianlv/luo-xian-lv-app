package app.luoxianlv.hot;

import android.content.Context;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** 仅从已安装 APK 的 assets 读取恢复组合；不接受外部路径或网络清单。 */
public final class BundledBaseline {
  final File directory, runtime, business;
  final String runtimeHash, businessHash, abi, entry;
  private final long runtimeSize, businessSize;

  private BundledBaseline(
      File directory,
      File runtime,
      File business,
      String runtimeHash,
      String businessHash,
      String abi,
      String entry,
      long runtimeSize,
      long businessSize) {
    this.directory = directory;
    this.runtime = runtime;
    this.business = business;
    this.runtimeHash = runtimeHash;
    this.businessHash = businessHash;
    this.abi = abi;
    this.entry = entry;
    this.runtimeSize = runtimeSize;
    this.businessSize = businessSize;
  }

  void verify() throws Exception {
    ContentStore.verifyFile(runtime, runtimeHash, runtimeSize);
    ContentStore.verifyFile(business, businessHash, businessSize);
  }

  public static BundledBaseline prepare(Context context) throws Exception {
    StrictJson.require(Looper.myLooper() != Looper.getMainLooper(), "内置恢复组合必须在后台准备");
    // 使用真实应用 Context，不能从业务模块覆盖的 AssetManager 读取信任根。
    Context installed = context.createPackageContext(context.getPackageName(), 0);
    byte[] raw;
    try (InputStream input = installed.getAssets().open("baseline/index.json")) {
      raw = HotPackage.read(input, 8192);
    }
    StrictJson.Obj index =
        StrictJson.object(raw).only("schema", "runtimeAbi", "entryClass", "runtime", "business");
    StrictJson.require(index.number("schema") == 1, "内置恢复组合版本不支持");
    String abi = index.string("runtimeAbi"), entry = index.string("entryClass");
    StrictJson.require(
        HotManifest.validId(abi) && entry.matches("[A-Za-z_$][A-Za-z0-9_.$]+"), "内置恢复入口无效");
    File directory =
        new File(context.getNoBackupFilesDir(), "native-baseline/" + HotSignatures.hash(raw));
    StrictJson.require(directory.isDirectory() || directory.mkdirs(), "无法创建内置恢复目录");
    StrictJson.Obj runtime = index.object("runtime").only("sha256", "size");
    StrictJson.Obj business = index.object("business").only("sha256", "size");
    File runtimeApk = copy(installed, directory, "runtime", runtime);
    File businessApk = copy(installed, directory, "business", business);
    return new BundledBaseline(
        directory,
        runtimeApk,
        businessApk,
        runtime.string("sha256"),
        business.string("sha256"),
        abi,
        entry,
        runtime.number("size"),
        business.number("size"));
  }

  private static File copy(Context installed, File directory, String name, StrictJson.Obj metadata)
      throws Exception {
    String hash = metadata.string("sha256");
    long size = metadata.number("size");
    StrictJson.require(
        HotManifest.validHash(hash) && size > 0 && size <= HotManifest.MAX_EXPANDED, "内置模块声明无效");
    File destination = new File(directory, name + ".apk");
    if (destination.isFile()) {
      try {
        ContentStore.verifyFile(destination, hash, size);
        return destination;
      } catch (Exception corrupted) {
        destination.setWritable(true, true);
        Files.delete(destination.toPath());
      }
    }
    StrictJson.require(directory.getUsableSpace() >= size + (16L << 20), "空间不足，无法准备内置恢复模块");
    File temporary = File.createTempFile(name, ".part", directory);
    try {
      try (InputStream input = installed.getAssets().open("baseline/" + name + ".apk");
          FileOutputStream out = new FileOutputStream(temporary)) {
        StrictJson.require(temporary.setReadOnly(), "无法将内置模块设为只读");
        ContentStore.copyVerified(input, out, hash, size);
        out.getFD().sync();
      }
      ContentStore.moveAtomic(
          temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
      ContentStore.syncDirectory(directory);
      return destination;
    } finally {
      if (temporary.exists()) {
        temporary.setWritable(true, true);
        Files.deleteIfExists(temporary.toPath());
      }
    }
  }
}
