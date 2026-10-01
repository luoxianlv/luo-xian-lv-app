package app.luoxianlv.hot;

import android.content.Context;
import android.os.storage.StorageManager;
import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** 仅使用本测试 APK 的目录，模拟余额不占据磁盘、不制造设备真实低空间。 */
final class PreparationSpaceChecks {
  static void run(Context context) throws Exception {
    File internal = context.getNoBackupFilesDir(),
        external = context.getExternalFilesDir("space-check");
    check(external != null && (external.isDirectory() || external.mkdirs()), "测试外部目录不可用");
    var manager = context.getSystemService(StorageManager.class);
    var budget = new PreparationSpace(context);
    var field = PreparationSpace.class.getDeclaredField("probe");
    field.setAccessible(true);
    var real = (PreparationSpace.Probe) field.get(budget);
    var first = real.inspect(internal);
    var second = real.inspect(external);
    boolean shared = manager.getUuidForPath(internal).equals(manager.getUuidForPath(external));
    check(shared == first.id().equals(second.id()), "系统卷与预算分组不一致");
    long internalDevice = android.system.Os.stat(internal.getPath()).st_dev;
    long externalDevice = android.system.Os.stat(external.getPath()).st_dev;
    File sentinel = new File(internal, "space-user-sentinel");
    byte[] bytes = new byte[] {9, 7, 5};
    Files.write(sentinel.toPath(), bytes);
    var constrained =
        new PreparationSpace(
            path -> {
              var volume = real.inspect(path);
              return new PreparationSpace.Volume(volume.id(), (16L << 20) + 100, 1);
            });
    boolean deferred = false;
    try {
      constrained.admit(
          List.of(
              new PreparationSpace.Demand(internal, 80),
              new PreparationSpace.Demand(external, 80)));
    } catch (PreparationSpace.Deferred expected) {
      deferred = true;
    }
    check(deferred == shared, "同一底层容量被重复使用，或独立卷被误拒绝");
    check(Arrays.equals(bytes, Files.readAllBytes(sentinel.toPath())), "预算改变了用户文件");
    var report =
        new org.json.JSONObject()
            .put("passed", true)
            .put("productionTouched", false)
            .put("sharedBackingVolume", shared)
            .put("mountDevicesDiffer", internalDevice != externalDevice)
            .put("sameVolumeBudgetCombined", deferred)
            .put("userFilePreserved", true)
            .put("internalBlockSize", first.blockSize())
            .put("externalBlockSize", second.blockSize());
    Files.write(
        new File(context.getFilesDir(), "native-space-report.json").toPath(),
        report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Files.delete(sentinel.toPath());
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
