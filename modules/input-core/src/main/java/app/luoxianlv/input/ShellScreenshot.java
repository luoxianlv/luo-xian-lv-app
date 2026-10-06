package app.luoxianlv.input;

import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 使用 Android 自带截图命令，图像通过匿名管道交付，不写入共享目录。 */
final class ShellScreenshot {
  private static final int MAX_BYTES = 32 * 1024 * 1024;
  private static final java.util.concurrent.ScheduledExecutorService deadlines =
      Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "screenshot-deadline"); thread.setDaemon(true); return thread;
      });

  static ParcelFileDescriptor capture(int displayId) throws IOException {
    if (!InputIdentity.privileged(android.os.Process.myUid()) || displayId != 0)
      throw new IOException("截图服务身份或屏幕无效");
    Process process = new ProcessBuilder("/system/bin/screencap", "-p").start();
    var timeout = deadlines.schedule(() -> { process.destroyForcibly(); }, 5, TimeUnit.SECONDS);
    byte[] png;
    try (var input = process.getInputStream(); var output = new ByteArrayOutputStream()) {
      byte[] chunk = new byte[8192];
      for (int size; (size = input.read(chunk)) != -1;) {
        if (output.size() + size > MAX_BYTES) throw new IOException("截图超过大小上限");
        output.write(chunk, 0, size);
      }
      if (!process.waitFor(300, TimeUnit.MILLISECONDS) || process.exitValue() != 0)
        throw new IOException("系统截图未成功完成");
      png = output.toByteArray();
      if (png.length < 45 || png[0] != (byte) 137 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G'
          || png[png.length - 8] != 'I' || png[png.length - 7] != 'E'
          || png[png.length - 6] != 'N' || png[png.length - 5] != 'D')
        throw new IOException("系统截图格式不完整");
      var header = java.nio.ByteBuffer.wrap(png).order(java.nio.ByteOrder.BIG_ENDIAN);
      int width = header.getInt(16), height = header.getInt(20);
      if (width < 2 || height < 2 || width > 8192 || height > 8192 || (long) width * height > 20_000_000)
        throw new IOException("截图尺寸超出上限");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt(); throw new IOException("截图已取消", interrupted);
    } finally {
      timeout.cancel(false);
      process.destroyForcibly();
    }
    var pipe = ParcelFileDescriptor.createPipe();
    Thread writer = new Thread(() -> {
      try (var output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) { output.write(png); }
      catch (IOException cancelled) { /* 客户端取消或服务结束时正常关闭管道。 */ }
    }, "screenshot-transfer");
    writer.setDaemon(true);
    try { writer.start(); }
    catch (RuntimeException failure) { pipe[0].close(); pipe[1].close(); throw failure; }
    return pipe[0];
  }
  private ShellScreenshot() {}
}
