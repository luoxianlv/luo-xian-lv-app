package app.luoxianlv.hot;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

/** 记录实际正在运行的签名组合；健康确认不清除它，清后台和无关进程退出不能据此隔离。 */
public final class ExecutionJournal {
  public static final class Run {
    public final String id, snapshot, attempt, fingerprint;
    public final int pid;
    public final long startedAt, crashedAt;

    private Run(
        String id,
        String snapshot,
        String attempt,
        String fingerprint,
        int pid,
        long startedAt,
        long crashedAt) {
      StrictJson.require(
          UUID.fromString(id).toString().equals(id)
              && HotManifest.validHash(snapshot)
              && HotManifest.validHash(fingerprint)
              && (attempt.isEmpty() || UUID.fromString(attempt).toString().equals(attempt))
              && pid > 0
              && startedAt > 0
              && (crashedAt == 0 || crashedAt >= startedAt),
          "运行组合记录无效");
      this.id = id;
      this.snapshot = snapshot;
      this.attempt = attempt;
      this.fingerprint = fingerprint;
      this.pid = pid;
      this.startedAt = startedAt;
      this.crashedAt = crashedAt;
    }

    public boolean belongsTo(String snapshot, String fingerprint) {
      return this.snapshot.equals(snapshot) && this.fingerprint.equals(fingerprint);
    }

    public boolean matches(ActivationJournal.ExitReason reason, int pid, long at) {
      return (reason == ActivationJournal.ExitReason.CRASH
              || reason == ActivationJournal.ExitReason.ANR)
          && this.pid == pid
          && at >= startedAt;
    }
  }

  private final File directory, file;

  public ExecutionJournal(File directory) throws Exception {
    StrictJson.require(
        !Files.isSymbolicLink(directory.toPath())
            && (directory.isDirectory() || directory.mkdirs()),
        "无法创建运行组合记录目录");
    this.directory = directory;
    file = new File(directory, "execution.bin");
  }

  public Run current() throws Exception {
    if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null;
    byte[] raw = ContentStore.readBounded(file);
    StrictJson.require(raw.length >= 44 && raw.length <= 1024, "运行记录大小无效");
    byte[] body = Arrays.copyOf(raw, raw.length - 32);
    StrictJson.require(
        MessageDigest.isEqual(
            MessageDigest.getInstance("SHA-256").digest(body),
            Arrays.copyOfRange(raw, raw.length - 32, raw.length)),
        "运行记录损坏，不能据此判定坏包");
    try (var input = new DataInputStream(new ByteArrayInputStream(body))) {
      StrictJson.require(input.readInt() == 0x4c584845 && input.readInt() == 1, "运行记录版本无效");
      var run =
          new Run(
              input.readUTF(),
              input.readUTF(),
              input.readUTF(),
              input.readUTF(),
              input.readInt(),
              input.readLong(),
              input.readLong());
      StrictJson.require(input.available() == 0, "运行记录含尾随内容");
      return run;
    }
  }

  public Run start(String snapshot, String attempt, String fingerprint, int pid, long now)
      throws Exception {
    var run = new Run(UUID.randomUUID().toString(), snapshot, attempt, fingerprint, pid, now, 0);
    try (var channel = lockChannel();
        FileLock lock = channel.tryLock()) {
      StrictJson.require(lock != null, "另一任务正在登记运行组合");
      save(run);
    }
    return run;
  }

  /** 异常处理器只标记本进程已登记的运行身份，不读取业务代码、不改激活选择或信任下限。 */
  public boolean crashed(Run expected, int pid, long now) throws Exception {
    StrictJson.require(expected != null && pid > 0, "崩溃运行身份无效");
    try (var channel = lockChannel();
        FileLock lock = channel.tryLock()) {
      StrictJson.require(lock != null, "运行记录正在更新，保留系统退出证据");
      var actual = current();
      if (actual == null
          || !actual.id.equals(expected.id)
          || actual.pid != pid
          || now < actual.startedAt) return false;
      save(
          new Run(
              actual.id,
              actual.snapshot,
              actual.attempt,
              actual.fingerprint,
              actual.pid,
              actual.startedAt,
              now));
      return true;
    }
  }

  public boolean clear(Run expected) throws Exception {
    if (expected == null) return false;
    try (var channel = lockChannel();
        FileLock lock = channel.tryLock()) {
      StrictJson.require(lock != null, "另一任务正在清理运行记录");
      var actual = current();
      if (actual == null || !actual.id.equals(expected.id)) return false;
      Files.delete(file.toPath());
      ContentStore.syncDirectory(directory);
      return true;
    }
  }

  private void save(Run run) throws Exception {
    var buffer = new ByteArrayOutputStream();
    try (var output = new DataOutputStream(buffer)) {
      output.writeInt(0x4c584845);
      output.writeInt(1);
      output.writeUTF(run.id);
      output.writeUTF(run.snapshot);
      output.writeUTF(run.attempt);
      output.writeUTF(run.fingerprint);
      output.writeInt(run.pid);
      output.writeLong(run.startedAt);
      output.writeLong(run.crashedAt);
    }
    byte[] body = buffer.toByteArray();
    buffer.write(MessageDigest.getInstance("SHA-256").digest(body));
    File temporary = File.createTempFile("execution-", ".part", directory);
    try {
      ContentStore.writeSynced(temporary.toPath(), buffer.toByteArray());
      ContentStore.replaceSynced(temporary, file);
    } finally {
      Files.deleteIfExists(temporary.toPath());
    }
  }

  private FileChannel lockChannel() throws Exception {
    return FileChannel.open(
        new File(directory, "execution.lock").toPath(),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE);
  }
}
