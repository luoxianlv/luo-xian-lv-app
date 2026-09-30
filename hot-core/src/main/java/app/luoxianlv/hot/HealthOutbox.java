package app.luoxianlv.hot;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;

/** 先持久化再上传；精确确认已发送批次，断网、进程退出和迟到响应不能丢事件。 */
public final class HealthOutbox {
  private static final int MAGIC = 0x4c58484f, MAX_EVENTS = 1024, MAX_ATTEMPTS = 4096;
  private final File directory, file;

  private static final class State {
    final Map<String, Long> sequences = new LinkedHashMap<>();
    final List<HealthEvent> events = new ArrayList<>();
  }

  public HealthOutbox(File directory) throws Exception {
    StrictJson.require(
        !Files.isSymbolicLink(directory.toPath())
            && (directory.isDirectory() || directory.mkdirs()),
        "无法创建内部健康队列");
    this.directory = directory;
    file = new File(directory, "outbox.bin");
  }

  public synchronized HealthEvent append(String attemptId, String kind, String code)
      throws Exception {
    try (var channel = lockChannel();
        FileLock lock = channel.tryLock()) {
      StrictJson.require(lock != null, "另一个任务正在更新健康队列");
      State state = read();
      StrictJson.require(
          state.events.size() < MAX_EVENTS
              && (state.sequences.containsKey(attemptId) || state.sequences.size() < MAX_ATTEMPTS),
          "健康队列已满，保留未确认记录");
      HealthEvent event =
          new HealthEvent(attemptId, state.sequences.getOrDefault(attemptId, 0L) + 1, kind, code);
      state.sequences.put(attemptId, event.sequence);
      state.events.add(event);
      save(state);
      return event;
    }
  }

  public synchronized List<HealthEvent> batch(int limit) throws Exception {
    StrictJson.require(limit > 0 && limit <= 100, "健康批次大小无效");
    var events = read().events;
    return List.copyOf(events.subList(0, Math.min(limit, events.size())));
  }

  /** 重复确认同一批次不会移除之后的事件；并发追加不改变已发送前缀。 */
  public synchronized boolean acknowledge(List<HealthEvent> sent) throws Exception {
    StrictJson.require(!sent.isEmpty() && sent.size() <= 100, "健康确认批次无效");
    try (var channel = lockChannel();
        FileLock lock = channel.tryLock()) {
      StrictJson.require(lock != null, "另一个任务正在确认健康队列");
      State state = read();
      if (sent.size() > state.events.size()) return false;
      for (int i = 0; i < sent.size(); i++)
        if (!same(sent.get(i), state.events.get(i))) return false;
      state.events.subList(0, sent.size()).clear();
      save(state);
      return true;
    }
  }

  private boolean same(HealthEvent left, HealthEvent right) {
    return left.attemptId.equals(right.attemptId)
        && left.sequence == right.sequence
        && left.kind.equals(right.kind)
        && left.code.equals(right.code);
  }

  private FileChannel lockChannel() throws Exception {
    return FileChannel.open(
        new File(directory, "outbox.lock").toPath(),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE);
  }

  private State read() throws Exception {
    State state = new State();
    if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return state;
    byte[] raw = ContentStore.readBounded(file);
    StrictJson.require(raw.length >= 48, "健康队列不完整");
    byte[] body = Arrays.copyOf(raw, raw.length - 32);
    StrictJson.require(
        MessageDigest.isEqual(
            MessageDigest.getInstance("SHA-256").digest(body),
            Arrays.copyOfRange(raw, raw.length - 32, raw.length)),
        "健康队列损坏，不能自动清空");
    try (var input = new DataInputStream(new ByteArrayInputStream(body))) {
      StrictJson.require(input.readInt() == MAGIC && input.readInt() == 1, "健康队列格式无效");
      int count = input.readInt();
      StrictJson.require(count >= 0 && count <= MAX_ATTEMPTS, "健康尝试数量超限");
      for (int i = 0; i < count; i++) {
        String id = input.readUTF();
        long sequence = input.readLong();
        new HealthEvent(id, sequence, "prepared", "recorded");
        StrictJson.require(state.sequences.put(id, sequence) == null, "健康尝试重复");
      }
      count = input.readInt();
      StrictJson.require(count >= 0 && count <= MAX_EVENTS, "健康事件数量超限");
      Map<String, Long> prior = new HashMap<>();
      for (int i = 0; i < count; i++) {
        var event =
            new HealthEvent(input.readUTF(), input.readLong(), input.readUTF(), input.readUTF());
        Long maximum = state.sequences.get(event.attemptId);
        StrictJson.require(
            maximum != null
                && event.sequence <= maximum
                && event.sequence > prior.getOrDefault(event.attemptId, 0L),
            "健康序号不一致");
        prior.put(event.attemptId, event.sequence);
        state.events.add(event);
      }
      StrictJson.require(input.available() == 0, "健康队列含尾随记录");
    }
    return state;
  }

  private void save(State state) throws Exception {
    var buffer = new ByteArrayOutputStream();
    try (var output = new DataOutputStream(buffer)) {
      output.writeInt(MAGIC);
      output.writeInt(1);
      output.writeInt(state.sequences.size());
      for (var entry : state.sequences.entrySet()) {
        output.writeUTF(entry.getKey());
        output.writeLong(entry.getValue());
      }
      output.writeInt(state.events.size());
      for (var event : state.events) {
        output.writeUTF(event.attemptId);
        output.writeLong(event.sequence);
        output.writeUTF(event.kind);
        output.writeUTF(event.code);
      }
    }
    byte[] body = buffer.toByteArray();
    buffer.write(MessageDigest.getInstance("SHA-256").digest(body));
    StrictJson.require(buffer.size() <= StrictJson.MAX_BYTES, "健康队列存储超限");
    File temporary = File.createTempFile("outbox-", ".part", directory);
    try {
      ContentStore.writeSynced(temporary.toPath(), buffer.toByteArray());
      ContentStore.replaceSynced(temporary, file);
    } finally {
      Files.deleteIfExists(temporary.toPath());
    }
  }
}
