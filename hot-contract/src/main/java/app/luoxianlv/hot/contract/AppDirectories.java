package app.luoxianlv.hot.contract;

import android.content.Context;
import java.io.File;

/** 可查看的业务文件共用目录；热更可执行文件、密钥和凭据仍留在内部专用存储。 */
public final class AppDirectories {
  private static File visibleRoot;

  private AppDirectories() {}

  public static synchronized File visibleRoot(Context context) {
    if (visibleRoot == null) {
      File external = null;
      try {
        external = context.getExternalFilesDir(null);
      } catch (RuntimeException ignored) {
        // 外部卷暂不可用时沿用内部回退，进程内不能在两个目录间来回切换。
      }
      visibleRoot =
          external != null && (external.isDirectory() || external.mkdirs()) && external.canWrite()
              ? external
              : context.getFilesDir();
    }
    return visibleRoot;
  }
}
