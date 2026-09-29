package app.luoxianlv.hot;

import java.io.EOFException;
import java.io.File;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** 下载暂存只提供字节，不加载代码；复制进内部对象库时仍须重新验哈希。 */
public final class ObjectDownloader {
  public interface Source {
    Response open(long offset) throws Exception;
  }

  public static final class Response implements AutoCloseable {
    public final long offset, total, length;
    public final InputStream body;
    private final AutoCloseable connection;

    public Response(
        long offset, long total, long length, InputStream body, AutoCloseable connection) {
      this.offset = offset;
      this.total = total;
      this.length = length;
      this.body = body;
      this.connection = connection;
    }

    @Override
    public void close() throws Exception {
      try {
        body.close();
      } finally {
        connection.close();
      }
    }
  }

  private final File directory;
  private final DownloadBudget budget;

  public ObjectDownloader(File downloadDirectory, DownloadBudget budget) throws Exception {
    StrictJson.require(
        !Files.isSymbolicLink(downloadDirectory.toPath())
            && (downloadDirectory.isDirectory() || downloadDirectory.mkdirs()),
        "无法创建下载暂存目录");
    directory = downloadDirectory.getCanonicalFile();
    this.budget = budget;
  }

  public File partial(String hash) {
    StrictJson.require(HotManifest.validHash(hash), "下载对象身份无效");
    return new File(directory, hash + ".part");
  }

  /** remainingCandidate 返回整组缺失字节；切网和每次预算预留前重新计算，不能只计当前对象。 */
  public File download(
      String contentId,
      String hash,
      long size,
      Source source,
      BooleanSupplier metered,
      BooleanSupplier cancelled,
      LongSupplier remainingCandidate)
      throws Exception {
    StrictJson.require(
        HotManifest.validHash(contentId) && size > 0 && size <= HotManifest.MAX_EXPANDED,
        "下载目标大小或身份无效");
    File target = partial(hash);
    StrictJson.require(!Files.isSymbolicLink(target.toPath()), "下载暂存不能是链接");
    try (FileChannel file =
            FileChannel.open(
                target.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        FileLock lock = file.tryLock()) {
      StrictJson.require(lock != null, "该对象已有下载任务");
      cancelled(cancelled);
      long offset = file.size();
      StrictJson.require(offset <= size, "下载暂存超过声明大小");
      if (offset == size) {
        verify(file, hash, size, cancelled);
        return target;
      }
      cancelled(cancelled);
      long remaining = remainingCandidate.getAsLong();
      StrictJson.require(remaining < 0 || remaining >= size - offset, "整组下载大小低于当前对象");
      budget.admit(contentId, remaining, metered.getAsBoolean());
      StrictJson.require(
          directory.getUsableSpace() >= size - offset + 16L * StrictJson.MAX_BYTES,
          "下载空间不足，保留当前版本");
      try (Response response = source.open(offset)) {
        StrictJson.require(
            response.offset == offset && response.total == size && response.length == size - offset,
            "续传响应范围改变，需要重新检查完整下载预算");
        StrictJson.require(file.size() == offset, "下载暂存在连接期间被修改");
        file.position(offset);
        byte[] bytes = new byte[32768];
        int allowance = 0;
        while (offset < size) {
          cancelled(cancelled);
          int count = (int) Math.min(bytes.length, size - offset);
          if (metered.getAsBoolean()) {
            if (allowance == 0) {
              remaining = remainingCandidate.getAsLong();
              StrictJson.require(remaining < 0 || remaining >= size - offset, "整组下载大小改变");
              budget.admit(contentId, remaining, true);
              allowance = (int) Math.min(DownloadBudget.RESERVATION, size - offset);
              budget.reserve(contentId, allowance);
            }
            count = Math.min(count, allowance);
          }
          int n = response.body.read(bytes, 0, count);
          if (n < 0) throw new EOFException("下载连接提前结束，保留已收到的暂存");
          if (n == 0) throw new InterruptedIOException("下载连接没有继续提供数据");
          ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, n);
          while (buffer.hasRemaining()) file.write(buffer);
          offset += n;
          // 已预留部分在非计费网络接续时也按已消耗处理，切回移动网络不会重复使用同一额度。
          allowance = Math.max(0, allowance - n);
        }
      } finally {
        file.force(true);
      }
      verify(file, hash, size, cancelled);
      return target;
    }
  }

  private static void cancelled(BooleanSupplier cancelled) throws InterruptedIOException {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("下载已让位于播放或当前更新任务已取消");
  }

  /** 复用持锁句柄，避免换句柄竞态，也兼容 Windows 的强制文件锁语义。 */
  private static void verify(
      FileChannel file, String expected, long size, BooleanSupplier cancelled) throws Exception {
    StrictJson.require(file.size() == size, "下载对象大小改变");
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
    ByteBuffer buffer = ByteBuffer.allocate(32768);
    file.position(0);
    long count = 0;
    int n;
    while ((n = file.read(buffer)) != -1) {
      cancelled(cancelled);
      count += n;
      StrictJson.require(n > 0 && count <= size, "下载对象读取异常或超限");
      buffer.flip();
      digest.update(buffer);
      buffer.clear();
    }
    StrictJson.require(
        count == size && HotSignatures.hex(digest.digest()).equals(expected), "下载对象内容校验失败");
  }
}
