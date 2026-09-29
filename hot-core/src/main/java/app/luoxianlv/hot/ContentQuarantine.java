package app.luoxianlv.hot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Set;

/** 内容隔离不受 label/日期变更影响；按宿主契约分组，不能靠换链接或重签绕过。 */
public final class ContentQuarantine {
  private final File directory;

  public ContentQuarantine(File internalDirectory) throws Exception {
    directory = internalDirectory.getCanonicalFile();
    StrictJson.require(directory.isDirectory() || directory.mkdirs(), "无法创建内容隔离目录");
  }

  public void requireAllowed(HotManifest manifest, long hostContract) throws Exception {
    StrictJson.require(!read(hostContract).contains(manifest.contentId), "该内容已隔离，修改版本名不能重试");
  }

  public void isolate(HotManifest manifest, long hostContract) throws Exception {
    try (FileChannel channel =
            FileChannel.open(
                new File(directory, "quarantine.lock").toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock()) {
      StrictJson.require(lock != null, "其他任务正在更新内容隔离记录");
      Set<String> contents = read(hostContract);
      if (!contents.add(manifest.contentId)) return;
      StrictJson.require(contents.size() <= 512, "内容隔离记录已满，暂停后续激活");
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(buffer)) {
        out.writeInt(0x4c584851);
        out.writeInt(1);
        out.writeInt(contents.size());
        for (String id : contents) out.writeUTF(id);
      }
      byte[] body = buffer.toByteArray();
      buffer.write(MessageDigest.getInstance("SHA-256").digest(body));
      File temp = File.createTempFile("quarantine-", ".part", directory);
      try {
        ContentStore.writeSynced(temp.toPath(), buffer.toByteArray());
        Files.move(
            temp.toPath(),
            file(hostContract).toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
        ContentStore.syncDirectory(directory);
      } finally {
        Files.deleteIfExists(temp.toPath());
      }
    }
  }

  private File file(long host) {
    StrictJson.require(host > 0 && host <= Integer.MAX_VALUE, "宿主契约无效");
    return new File(directory, "host-" + host + ".bin");
  }

  private Set<String> read(long host) throws Exception {
    File file = file(host);
    Set<String> result = new LinkedHashSet<>();
    if (!file.exists()) return result;
    StrictJson.require(file.length() <= 65536 && !Files.isSymbolicLink(file.toPath()), "内容隔离记录损坏");
    byte[] raw;
    try (java.io.InputStream input = Files.newInputStream(file.toPath())) {
      raw = HotPackage.read(input, 65536);
    }
    StrictJson.require(raw.length >= 44, "内容隔离记录不完整");
    byte[] body = java.util.Arrays.copyOf(raw, raw.length - 32);
    StrictJson.require(
        MessageDigest.isEqual(
            MessageDigest.getInstance("SHA-256").digest(body),
            java.util.Arrays.copyOfRange(raw, raw.length - 32, raw.length)),
        "内容隔离记录损坏，不能自动清空");
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
      StrictJson.require(input.readInt() == 0x4c584851 && input.readInt() == 1, "内容隔离版本无效");
      int size = input.readInt();
      StrictJson.require(size >= 0 && size <= 512, "内容隔离数量无效");
      for (int i = 0; i < size; i++) {
        String id = input.readUTF();
        StrictJson.require(HotManifest.validHash(id) && result.add(id), "隔离身份无效");
      }
      StrictJson.require(input.available() == 0, "内容隔离含尾随记录");
    }
    return result;
  }
}
