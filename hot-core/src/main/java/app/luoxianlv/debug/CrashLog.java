package app.luoxianlv.debug;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import app.luoxianlv.hot.contract.AppDirectories;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** 崩溃记录在业务加载前安装；同步落盘后交还系统，不能依赖异步日志队列。 */
public final class CrashLog {
  private CrashLog() {}

  public static synchronized void install(Context context) {
    Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    if (previous instanceof FatalErrorHandler) return;
    File directory = new File(AppDirectories.visibleRoot(context), "logs");
    String version = "未知";
    try {
      android.content.pm.PackageInfo info =
          context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
      version = info.versionName + "（" + info.versionCode + "）";
    } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
    }
    String header =
        "包名="
            + context.getPackageName()
            + "；版本="
            + version
            + "；设备="
            + Build.MANUFACTURER
            + " "
            + Build.MODEL
            + "；Android="
            + Build.VERSION.RELEASE
            + "（API "
            + Build.VERSION.SDK_INT
            + "）";
    Thread.setDefaultUncaughtExceptionHandler(
        new FatalErrorHandler(
            (thread, error) -> write(directory, header, thread, error),
            previous != null
                ? previous
                : (thread, error) -> {
                  Process.killProcess(Process.myPid());
                  System.exit(10);
                }));
  }

  public static void write(File directory, String header, Thread thread, Throwable error)
      throws IOException {
    directory.mkdirs();
    // 独立文件避免等待故障线程持有的日志锁，保留最近两次并沿用导出器前缀。
    File current = new File(directory, "play-debug-crash.log");
    File previous = new File(directory, "play-debug-crash.log.1");
    if (current.exists()) {
      previous.delete();
      current.renameTo(previous);
    }
    try (FileOutputStream stream = new FileOutputStream(current)) {
      PrintWriter writer = new PrintWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
      writer.println("时间=" + new Date() + "；线程=" + thread.getName());
      writer.println(header);
      writer.println("未捕获异常（保留原始堆栈与原因）：");
      error.printStackTrace(writer);
      writer.flush();
      if (writer.checkError()) throw new IOException("崩溃日志写入失败");
      stream.getFD().sync();
    }
  }
}
