package app.luoxianlv.input;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** 仅在 shell 预检的名称匹配失败时读取系统映射，限制时间和体积，不保存完整系统转储。 */
final class SystemInputDeviceMap {
  static String read() throws Exception {
    Process process = new ProcessBuilder("/system/bin/dumpsys", "-t", "1", "input")
        .redirectErrorStream(true).start();
    FutureTask<String> reader = new FutureTask<>(() -> {
      try (var stream = process.getInputStream(); var output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[4096];
        for (int size; (size = stream.read(buffer)) >= 0;) {
          if (output.size() + size > 524288) throw new IllegalStateException("系统输入映射超出读取上限");
          output.write(buffer, 0, size);
        }
        return output.toString(StandardCharsets.UTF_8.name());
      }
    });
    Thread thread = new Thread(reader, "touch-device-map");
    thread.setDaemon(true);
    thread.start();
    try {
      String value = reader.get(1500, TimeUnit.MILLISECONDS);
      if (!process.waitFor(100, TimeUnit.MILLISECONDS) || process.exitValue() != 0)
        throw new IllegalStateException("系统输入映射未完成");
      return value;
    } finally {
      if (process.isAlive()) process.destroyForcibly();
      reader.cancel(true);
    }
  }
}
