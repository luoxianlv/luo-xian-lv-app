package app.luoxianlv.update;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.file.Files;

/** 专用事务不跟随链接，提交结果时同步父目录以支持进程退出恢复。 */
final class SafeFiles {
  private SafeFiles() {}
  static void rejectLink(File file) throws IOException {
    for (File path = file.getAbsoluteFile(); path != null; path = path.getParentFile()) {
      if (!Files.isSymbolicLink(path.toPath())) continue;
      // 系统提供的用户零目录别名可解析到 /data/data；应用无权更改此祖先。
      if (path.getAbsolutePath().equals("/data/user/0") && path.getCanonicalPath().equals("/data/data")) continue;
      throw new IOException("更新路径不能含符号链接");
    }
  }
  static void directory(File directory) throws IOException {
    rejectLink(directory);
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建更新目录");
    // Android 的 /data/user/0 与 /data/data 也可能是系统别名，不能用字符串相等判断链接。
    rejectLink(directory);
  }
  static void syncDirectory(File directory) throws IOException {
    FileDescriptor descriptor = null;
    try {
      descriptor = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0);
      if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) throw new IOException("更新父路径不是目录");
      Os.fsync(descriptor);
    } catch (ErrnoException failure) { throw new IOException("无法同步更新目录", failure); }
    finally { if (descriptor != null) try { Os.close(descriptor); } catch (ErrnoException ignored) { /* 已完成 fsync */ } }
  }
}
