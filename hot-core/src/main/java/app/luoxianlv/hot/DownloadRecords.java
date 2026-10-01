package app.luoxianlv.hot;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 外部断点的归属与字节指纹仅存于应用内部；损坏记录不重置，也不据此删除外部文件。 */
final class DownloadRecords {
  private static final int MAGIC = 0x4c584450, MAX_RECORDS = 8192, MAX_BYTES = 4 << 20;
  record Entry(String hash, long size, long length, String fingerprint, long used) {}
  private final File root, file;
  private final String downloads;

  DownloadRecords(File internalRoot, File directory) throws Exception {
    StrictJson.require(!Files.isSymbolicLink(internalRoot.toPath())
        && (internalRoot.isDirectory() || internalRoot.mkdirs()), "无法创建内部下载归属记录");
    root = internalRoot.getCanonicalFile();
    file = new File(root, "downloads.bin");
    downloads = directory.getCanonicalPath();
  }

  FileChannel lockChannel() throws Exception {
    return FileChannel.open(new File(root, "downloads.lock").toPath(),
        StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
  }

  File directory() { return root; }

  Map<String, Entry> read() throws Exception {
    Map<String, Entry> records = new LinkedHashMap<>();
    if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return records;
    StrictJson.require(!Files.isSymbolicLink(file.toPath()) && file.isFile(), "下载记录类型异常");
    byte[] raw;
    try (var input = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
      raw = HotPackage.read(input, MAX_BYTES);
    }
    StrictJson.require(raw.length >= 48, "下载归属记录不完整");
    byte[] body = Arrays.copyOf(raw, raw.length - 32);
    StrictJson.require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body),
        Arrays.copyOfRange(raw, raw.length - 32, raw.length)), "下载归属记录损坏");
    try (var input = new DataInputStream(new ByteArrayInputStream(body))) {
      StrictJson.require(input.readInt() == MAGIC && input.readInt() == 1
          && input.readUTF().equals(downloads), "下载归属记录范围异常");
      int count = input.readInt();
      StrictJson.require(count >= 0 && count <= MAX_RECORDS, "下载归属记录过多");
      for (int i = 0; i < count; i++) {
        Entry entry = new Entry(input.readUTF(), input.readLong(), input.readLong(), input.readUTF(), input.readLong());
        validate(entry);
        StrictJson.require(records.put(entry.hash, entry) == null, "下载归属重复");
      }
      StrictJson.require(input.available() == 0, "下载归属含尾随内容");
    }
    return records;
  }

  void save(Map<String, Entry> records) throws Exception {
    StrictJson.require(records.size() <= MAX_RECORDS, "下载归属记录过多");
    var bytes = new ByteArrayOutputStream();
    try (var output = new DataOutputStream(bytes)) {
      output.writeInt(MAGIC); output.writeInt(1); output.writeUTF(downloads); output.writeInt(records.size());
      for (Entry entry : records.values()) {
        validate(entry);
        output.writeUTF(entry.hash); output.writeLong(entry.size); output.writeLong(entry.length);
        output.writeUTF(entry.fingerprint); output.writeLong(entry.used);
      }
    }
    byte[] body = bytes.toByteArray();
    bytes.write(MessageDigest.getInstance("SHA-256").digest(body));
    StrictJson.require(bytes.size() <= MAX_BYTES, "下载归属存储超限");
    File temporary = File.createTempFile("downloads-", ".part", root);
    try {
      ContentStore.writeSynced(temporary.toPath(), bytes.toByteArray());
      ContentStore.replaceSynced(temporary, file);
    } finally { Files.deleteIfExists(temporary.toPath()); }
  }

  void remember(Entry entry) {
    try (var channel = lockChannel(); var lock = channel.tryLock()) {
      if (lock == null) return;
      var records = read();
      if (!records.containsKey(entry.hash) && records.size() >= MAX_RECORDS) return;
      records.put(entry.hash, entry);
      save(records);
    } catch (Exception unavailable) { /* 清理资料写入失败不改变已下载字节或稳定选择。 */ }
  }

  Entry find(String hash) {
    try { return read().get(hash); } catch (Exception unavailable) { return null; }
  }

  private static void validate(Entry entry) {
    StrictJson.require(HotManifest.validHash(entry.hash) && HotManifest.validHash(entry.fingerprint)
        && entry.size > 0 && entry.size <= HotManifest.MAX_EXPANDED
        && entry.length >= 0 && entry.length <= entry.size && entry.used > 0, "下载归属记录无效");
  }
}
