package app.luoxianlv.hot;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;

/** 只记录已经完整缓存的待重启快照；此记录不是许可，离线读取不能执行新代码。 */
public final class PendingRestart {
  private final File directory, file;

  public PendingRestart(File directory) throws Exception {
    StrictJson.require(
        !Files.isSymbolicLink(directory.toPath())
            && (directory.isDirectory() || directory.mkdirs()),
        "无法创建待重启记录目录");
    this.directory = directory;
    file = new File(directory, "pending.bin");
  }

  public synchronized String current() throws Exception {
    if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return "";
    byte[] raw = ContentStore.readBounded(file);
    StrictJson.require(raw.length >= 44 && raw.length <= 256, "待重启记录大小无效");
    byte[] body = Arrays.copyOf(raw, raw.length - 32);
    StrictJson.require(
        MessageDigest.isEqual(
            MessageDigest.getInstance("SHA-256").digest(body),
            Arrays.copyOfRange(raw, raw.length - 32, raw.length)),
        "待重启记录损坏，不能将其当作授权");
    try (var input = new DataInputStream(new ByteArrayInputStream(body))) {
      StrictJson.require(input.readInt() == 0x4c584850 && input.readInt() == 1, "待重启记录格式无效");
      String id = input.readUTF();
      StrictJson.require(HotManifest.validHash(id) && input.available() == 0, "待重启快照身份无效");
      return id;
    }
  }

  public synchronized void record(ContentStore store, ContentStore.Snapshot snapshot)
      throws Exception {
    // 只保存内部对象已完整校验的身份，不依赖下载暂存或外部路径继续存在。
    var stored = store.snapshot(snapshot.manifest.snapshotId);
    store.verifySnapshotObjects(stored);
    try (var channel = lockChannel();
        FileLock lock = channel.tryLock()) {
      StrictJson.require(lock != null, "另一任务正在记录待重启组合");
      String existing = current();
      if (existing.equals(stored.manifest.snapshotId)) return;
      var buffer = new ByteArrayOutputStream();
      try (var output = new DataOutputStream(buffer)) {
        output.writeInt(0x4c584850);
        output.writeInt(1);
        output.writeUTF(stored.manifest.snapshotId);
      }
      byte[] body = buffer.toByteArray();
      buffer.write(MessageDigest.getInstance("SHA-256").digest(body));
      File temporary = File.createTempFile("pending-", ".part", directory);
      try {
        ContentStore.writeSynced(temporary.toPath(), buffer.toByteArray());
        ContentStore.replaceSynced(temporary, file);
      } finally {
        Files.deleteIfExists(temporary.toPath());
      }
    }
  }

  /** 较旧启动任务不能删除后来记录的新组合。 */
  public synchronized boolean clear(String expected) throws Exception {
    StrictJson.require(HotManifest.validHash(expected), "待清除的快照身份无效");
    try (var channel = lockChannel();
        FileLock lock = channel.tryLock()) {
      StrictJson.require(lock != null, "另一任务正在清理待重启记录");
      if (!current().equals(expected)) return false;
      Files.delete(file.toPath());
      ContentStore.syncDirectory(directory);
      return true;
    }
  }

  private FileChannel lockChannel() throws Exception {
    return FileChannel.open(
        new File(directory, "pending.lock").toPath(),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE);
  }
}
