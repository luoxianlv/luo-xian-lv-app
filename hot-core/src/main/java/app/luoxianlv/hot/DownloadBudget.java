package app.luoxianlv.hot;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;

/** 按真实内容累计计费网络流量；读取前预留，进程中断和改发布名不能重置预算。 */
public final class DownloadBudget {
  public static final long LIMIT = 20L << 20;
  public static final int RESERVATION = 1 << 20;
  private static final int MAGIC = 0x4c584842;
  private final File directory;

  public static final class Deferred extends Exception {
    Deferred() {
      super("该候选需要等待非计费网络，当前版本继续使用");
    }
  }

  public DownloadBudget(File internalDirectory) throws Exception {
    StrictJson.require(
        !Files.isSymbolicLink(internalDirectory.toPath())
            && (internalDirectory.isDirectory() || internalDirectory.mkdirs()),
        "无法创建下载预算目录");
    directory = internalDirectory;
  }

  /** 先按整组缺失对象判断，不能逐对象拆开绕过 20 MiB。未知大小以负数传入。 */
  public synchronized void admit(String contentId, long missingBytes, boolean metered)
      throws Exception {
    File file = file(contentId);
    if (!metered) return;
    if (missingBytes < 0 || missingBytes > LIMIT - used(file)) throw new Deferred();
  }

  /** 每次最多预留 1 MiB；未读完即中断的预留不退还，以避免反复中断绕过限制。 */
  public synchronized void reserve(String contentId, int maximumBytes) throws Exception {
    StrictJson.require(maximumBytes > 0 && maximumBytes <= RESERVATION, "下载预算预留大小无效");
    File file = file(contentId);
    try (FileChannel channel =
            FileChannel.open(
                new File(directory, "traffic.lock").toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock()) {
      StrictJson.require(lock != null, "另一个控制器正在记录流量");
      long previous = used(file);
      if (maximumBytes > LIMIT - previous) throw new Deferred();
      byte[] body =
          ByteBuffer.allocate(16).putInt(MAGIC).putInt(1).putLong(previous + maximumBytes).array();
      byte[] bytes =
          ByteBuffer.allocate(48)
              .put(body)
              .put(MessageDigest.getInstance("SHA-256").digest(body))
              .array();
      File temporary = File.createTempFile("traffic-", ".part", directory);
      try {
        ContentStore.writeSynced(temporary.toPath(), bytes);
        ContentStore.replaceSynced(temporary, file);
      } finally {
        Files.deleteIfExists(temporary.toPath());
      }
    }
  }

  public synchronized long used(String contentId) throws Exception {
    return used(file(contentId));
  }

  private File file(String contentId) {
    StrictJson.require(HotManifest.validHash(contentId), "流量记录内容身份无效");
    return new File(directory, contentId + ".bin");
  }

  private long used(File file) throws Exception {
    if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return 0;
    StrictJson.require(
        file.isFile() && file.length() == 48 && !Files.isSymbolicLink(file.toPath()),
        "流量记录损坏，不能重置预算");
    byte[] bytes = Files.readAllBytes(file.toPath());
    StrictJson.require(
        bytes.length == 48
            && MessageDigest.isEqual(
                MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes, 16)),
                Arrays.copyOfRange(bytes, 16, 48)),
        "流量记录校验失败，不能重置预算");
    ByteBuffer input = ByteBuffer.wrap(bytes);
    StrictJson.require(input.getInt() == MAGIC && input.getInt() == 1, "下载预算格式无效");
    long value = input.getLong();
    StrictJson.require(value >= 0 && value <= LIMIT, "下载预算数值无效");
    return value;
  }
}
