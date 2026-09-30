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
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
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
  private final DownloadRecords records;
  private final AtomicLong received = new AtomicLong();
  interface AtomicClaim { void move(Path source, Path destination) throws Exception; }
  private final AtomicClaim claim;

  public ObjectDownloader(File downloadDirectory, DownloadBudget budget) throws Exception {
    this(downloadDirectory, budget, null);
  }

  /** 归属资料必须由宿主传入内部目录；旧构造保持续传，仅禁用目录级回收。 */
  public ObjectDownloader(File downloadDirectory, DownloadBudget budget, File internalRecords)
      throws Exception {
    this(downloadDirectory, budget, internalRecords,
        (source, destination) -> Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE));
  }

  ObjectDownloader(File downloadDirectory, DownloadBudget budget, File internalRecords,
      AtomicClaim claim) throws Exception {
    StrictJson.require(
        !Files.isSymbolicLink(downloadDirectory.toPath())
            && (downloadDirectory.isDirectory() || downloadDirectory.mkdirs()),
        "无法创建下载暂存目录");
    directory = downloadDirectory.getCanonicalFile();
    this.budget = budget;
    this.claim = java.util.Objects.requireNonNull(claim);
    records = internalRecords == null ? null : new DownloadRecords(internalRecords, directory);
  }

  public File partial(String hash) {
    StrictJson.require(HotManifest.validHash(hash), "下载对象身份无效");
    return new File(directory, hash + ".part");
  }

  File directory() {
    return directory;
  }

  /** 实际读取的网络字节，含重试；仅用有界计数，不保留逐对象资料。 */
  public long receivedBytes() {
    return received.get();
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
    boolean existed = Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS);
    try (FileChannel file =
            FileChannel.open(
                target.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        FileLock lock = file.tryLock()) {
      StrictJson.require(lock != null, "该对象已有下载任务");
      var prior = records == null ? null : records.find(hash);
      boolean owned = !existed || (prior != null && matches(file, prior));
      boolean verified = false;
      try {
        cancelled(cancelled);
        long offset = file.size();
        StrictJson.require(offset <= size, "下载暂存超过声明大小");
        if (offset == size) {
          verify(file, hash, size, cancelled);
          verified = true;
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
            received.addAndGet(n);
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
        verified = true;
        return target;
      } finally {
        if (records != null && (owned || verified)) {
          try {
            long length = file.size();
            String digest = verified ? hash : fingerprint(file, length, () -> false);
            if (length <= size && (length < size || digest.equals(hash)))
              records.remember(new DownloadRecords.Entry(hash, size, length, digest, System.currentTimeMillis()));
          } catch (Exception unavailable) { /* 不让清理资料覆盖原始下载结果。 */ }
        }
      }
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
    StrictJson.require(fingerprint(file, size, cancelled).equals(expected), "下载对象内容校验失败");
  }

  private static String fingerprint(FileChannel file, long size, BooleanSupplier cancelled)
      throws Exception {
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
    StrictJson.require(count == size, "下载对象内容大小改变");
    return HotSignatures.hex(digest.digest());
  }

  public record CollectionResult(long beforeBytes, long afterBytes, int removed, boolean overBudget) {}

  /** 只回收内部记录仍与外部字节指纹一致的断点；当前候选、活跃锁、未知文件都保留。 */
  public CollectionResult collect(Set<String> protectedHashes, long softBytes) {
    if (records == null) return new CollectionResult(0, 0, 0, false);
    StrictJson.require(softBytes >= 0, "下载缓存预算无效");
    for (String hash : protectedHashes) StrictJson.require(HotManifest.validHash(hash), "保护下载身份无效");
    try (var channel = records.lockChannel(); var lock = channel.tryLock()) {
      if (lock == null) return new CollectionResult(0, 0, 0, false);
      var owned = records.read();
      var ordered = new ArrayList<>(owned.values());
      ordered.sort(Comparator.comparingLong(DownloadRecords.Entry::used).thenComparing(DownloadRecords.Entry::hash));
      long before = 0;
      int present = 0, removed = 0;
      for (var entry : ordered) {
        File target = partial(entry.hash());
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
          before = Math.addExact(before, target.length()); present++;
        } else owned.remove(entry.hash());
      }
      long after = before;
      for (var entry : ordered) {
        if (after <= softBytes && present <= 256) break;
        if (protectedHashes.contains(entry.hash())) continue;
        File target = partial(entry.hash());
        if (!target.isFile() || Files.isSymbolicLink(target.toPath())) continue;
        try (var data = FileChannel.open(target.toPath(), StandardOpenOption.READ,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            var active = data.tryLock()) {
          if (active == null || !matches(data, entry)) continue;
          if (removeClaimed(target, records.directory(), entry.fingerprint(), entry.length(), active)) {
            after -= entry.length(); present--; removed++; owned.remove(entry.hash());
          }
        } catch (Exception busyOrUnknown) { /* 换文件、锁冲突或异常内容均留给之后人工/安全重试。 */ }
      }
      records.save(owned);
      return new CollectionResult(before, after, removed, after > softBytes || present > 256);
    } catch (Exception unavailable) { return new CollectionResult(0, 0, 0, false); }
  }

  /** 内部对象重新验完后才清相应完整暂存；失败与异常外部内容不影响候选准备。 */
  public int committed(ContentStore store, ContentStore.Snapshot snapshot) throws Exception {
    store.verifySnapshotObjects(snapshot);
    int removed = 0;
    for (var entry : snapshot.manifest.objects.entrySet()) {
      File target = partial(entry.getKey());
      if (!target.isFile() || Files.isSymbolicLink(target.toPath())) continue;
      try (var data = FileChannel.open(target.toPath(), StandardOpenOption.READ,
              StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
          var active = data.tryLock()) {
        if (active == null || data.size() != entry.getValue()) continue;
        verify(data, entry.getKey(), entry.getValue(), () -> false);
        File privateRoot = records == null ? store.rootDirectory() : records.directory();
        if (removeClaimed(target, privateRoot, entry.getKey(), entry.getValue(), active)) removed++;
      } catch (Exception busyOrUnknown) { /* 准备成功但无法清理时保留外部资料，不能撤销稳定结果。 */ }
    }
    return removed;
  }

  private static boolean matches(FileChannel file, DownloadRecords.Entry entry) {
    try {
      return file.size() == entry.length()
          && fingerprint(file, entry.length(), () -> false).equals(entry.fingerprint());
    } catch (Exception unreadable) { return false; }
  }

  /** 名称可被外部替换：原子认领后只对内部私有条目重验/删除，绝不 unlink 原路径。 */
  private boolean removeClaimed(File target, File privateRoot, String hash, long size,
      FileLock original) {
    Path isolation = null, claimed = null;
    try {
      if (Files.isSymbolicLink(privateRoot.toPath()) || !privateRoot.isDirectory()) return false;
      isolation = Files.createTempDirectory(privateRoot.toPath(), "download-retired-");
      claimed = isolation.resolve(target.getName());
      // 不降级为 copy/delete；Android FUSE -> 内部目录 EXDEV 时原断点原封保留。
      claim.move(target.toPath(), claimed);
      original.release();
      if (!Files.isSymbolicLink(claimed) && Files.isRegularFile(claimed, LinkOption.NOFOLLOW_LINKS)) {
        try (var file = FileChannel.open(claimed, StandardOpenOption.READ,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            var lock = file.tryLock()) {
          if (lock != null && file.size() == size && fingerprint(file, size, () -> false).equals(hash)) {
            Files.delete(claimed);
            return true;
          }
        }
      }
    } catch (Exception unsupportedBusyOrUnknown) { /* 不安全或不可用的认领原语不能换成路径删除。 */ }
    finally {
      if (claimed != null && Files.exists(claimed, LinkOption.NOFOLLOW_LINKS)) {
        // 创建硬链接不会覆盖现有名称；无法安全还原时保留私有隔离项供人工处理。
        try {
          if (Files.isRegularFile(claimed, LinkOption.NOFOLLOW_LINKS)) {
            Files.createLink(target.toPath(), claimed);
          } else if (Files.isSymbolicLink(claimed)) {
            Files.createSymbolicLink(target.toPath(), Files.readSymbolicLink(claimed));
          }
        } catch (Exception unavailable) { /* 原名称已复用或不支持还原：隔离项保留，不覆盖。 */ }
      }
      if (isolation != null) {
        try { Files.delete(isolation); } catch (Exception retained) { /* 未知项仍在隔离目录。 */ }
      }
    }
    return false;
  }
}
