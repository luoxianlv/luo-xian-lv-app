package app.luoxianlv.hot;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** 使用 Android 实际 HTTP 栈和外部私有暂存目录，验证续传后完整字节与预算记录。 */
final class NativeTransferChecks {
  static void run(HotCoreInstrumentation runner, File internalRoot) throws Exception {
    File parent = runner.getContext().getExternalFilesDir("hot/downloads");
    if (parent == null) throw new AssertionError("设备未提供应用外部私有目录");
    File directory = new File(parent, "check-" + UUID.randomUUID());
    if (!directory.getCanonicalPath().startsWith(parent.getCanonicalPath() + File.separator))
      throw new AssertionError("测试下载目录越界");
    DownloadBudget budget = new DownloadBudget(new File(internalRoot, "transfer-budget"));
    ObjectDownloader downloader = new ObjectDownloader(directory, budget);
    byte[] data = new byte[200000];
    new Random(9).nextBytes(data);
    String hash = HotSignatures.hash(data), content = HotSignatures.hash(new byte[] {3});
    File partial = downloader.partial(hash);
    int start = 70000;
    try {
      Files.write(partial.toPath(), Arrays.copyOf(data, start));
      AtomicReference<String> range = new AtomicReference<>();
      try (LoopbackHttp server =
          new LoopbackHttp(
              request -> {
                range.set(request.headers.get("range"));
                return LoopbackHttp.reply(
                    206,
                    "Content-Range: bytes " + start + "-199999/200000\r\n",
                    Arrays.copyOfRange(data, start, data.length));
              })) {
        URI origin = server.origin();
        HttpObjectSource source =
            new HttpObjectSource(
                origin.resolve("/object"), data.length, origin, "test-installation", true);
        File result =
            downloader.download(
                content,
                hash,
                data.length,
                source,
                () -> true,
                () -> false,
                () -> data.length - partial.length());
        if (!Arrays.equals(data, Files.readAllBytes(result.toPath()))
            || !"bytes=70000-".equals(range.get()))
          throw new AssertionError("Android 实际续传内容或请求范围不正确");
        if (budget.used(content) != data.length - start) throw new AssertionError("设备下载预算未持久化");
      }
    } finally {
      Files.deleteIfExists(partial.toPath());
      Files.deleteIfExists(directory.toPath());
    }
  }
}
