package app.luoxianlv.hot;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** Android 文件系统上的原子迁移与去重验证；重新打开磁盘实例不等同于真实进程死亡。 */
final class HealthHistoryChecks {
  static void run(File root) throws Exception {
    File queueRoot = new File(root, "queue"), journalRoot = new File(root, "journal");
    var journal = new ActivationJournal(journalRoot);
    String previous = confirm(journal, "previous", 1);
    for (var outcome : journal.state().outcomes) journal.outcomeQueued(outcome);
    String stable = confirm(journal, "stable", 2);
    var queue = new HealthOutbox(queueRoot);
    var buffer = new ByteArrayOutputStream();
    var ids = new ArrayList<String>(List.of(previous, stable));
    for (int i = 0; i < 100; i++) ids.add(UUID.randomUUID().toString());
    try (var output = new DataOutputStream(buffer)) {
      output.writeInt(0x4c58484f);
      output.writeInt(2);
      output.writeInt(ids.size());
      for (String id : ids) {
        output.writeUTF(id);
        output.writeLong(7);
      }
      output.writeInt(0);
      output.writeInt(ids.size());
      for (String id : ids)
        output.writeUTF(
            HotSignatures.hash(
                (id + "\0healthy\0foreground_observed").getBytes(StandardCharsets.UTF_8)));
    }
    byte[] body = buffer.toByteArray();
    buffer.write(MessageDigest.getInstance("SHA-256").digest(body));
    ContentStore.writeSynced(new File(queueRoot, "outbox.bin").toPath(), buffer.toByteArray());
    var compacted = queue.compact(() -> new ActivationJournal(journalRoot).state(), 0);
    check(compacted.removedAttempts() == 100 && compacted.removedReceipts() == 101, "旧格式历史清理不准确");
    // 稳定健康回执仍在激活日志中；上传已确认但日志未确认时不能重新入队。
    OutcomeRecovery.reconcile(new ActivationJournal(journalRoot), new HealthOutbox(queueRoot));
    check(queue.batch(100).isEmpty(), "旧健康回执重复入队");
    check(queue.append(previous, "crash", "stable_process_failed").sequence == 8, "回退尝试序号被重置");
    check(queue.append(stable, "anr", "stable_process_failed").sequence == 8, "稳定尝试序号被重置");
    var sent = queue.batch(100);
    queue.append(stable, "module_used", "main");
    check(
        queue.compact(() -> new ActivationJournal(journalRoot).state(), 0).removedAttempts() == 0,
        "删除了待上传尝试");
    check(queue.acknowledge(sent) && !queue.acknowledge(sent), "迟到确认删除了新事件");
    check(new HealthOutbox(queueRoot).batch(100).get(0).sequence == 9, "重新打开后丢失待上传事件或序号");
    try (var input =
        new DataInputStream(Files.newInputStream(new File(queueRoot, "outbox.bin").toPath()))) {
      check(input.readInt() == 0x4c58484f && input.readInt() == 3, "队列没有原子升级为新格式");
    }
  }

  private static String confirm(ActivationJournal journal, String target, int revision)
      throws Exception {
    String attempt =
        journal.begin(
            HotSignatures.hash(target.getBytes(StandardCharsets.UTF_8)), revision, 1, 123, 1000);
    journal.firstFrame(attempt);
    journal.healthy(attempt, 60000);
    return attempt;
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
