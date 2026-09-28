package app.luoxianlv.hot;

import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 先检查中央目录中 Java ZIP API 未暴露的链接与加密标志，再进行有界流式读取。 */
final class ZipContainer implements Closeable {
  final Map<String, Entry> entries;
  private final ZipFile zip;

  static final class Entry {
    final String name;
    final long size;
    final boolean directory;

    Entry(String name, long size, boolean directory) {
      this.name = name;
      this.size = size;
      this.directory = directory;
    }
  }

  ZipContainer(File file, int maxEntries, long maxExpanded, boolean allowDirectories)
      throws Exception {
    StrictJson.require(
        file.isFile() && file.length() <= HotManifest.MAX_EXPANDED + 16L * StrictJson.MAX_BYTES,
        "ZIP 文件大小无效");
    Map<String, Entry> result = new LinkedHashMap<>();
    try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
      long length = input.length();
      StrictJson.require(length >= 22, "ZIP 尾记录缺失");
      int tailSize = (int) Math.min(length, 65557);
      byte[] tail = new byte[tailSize];
      input.seek(length - tailSize);
      input.readFully(tail);
      int end = -1;
      for (int i = tail.length - 22; i >= 0; i--) {
        if (u32(tail, i) == 0x06054b50L && i + 22 + u16(tail, i + 20) == tail.length) {
          end = i;
          break;
        }
      }
      StrictJson.require(end >= 0, "ZIP 尾记录无效");
      int count = u16(tail, end + 10);
      long directorySize = u32(tail, end + 12), directoryOffset = u32(tail, end + 16);
      StrictJson.require(
          u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0 && u16(tail, end + 8) == count,
          "不支持分卷 ZIP");
      StrictJson.require(
          count > 0
              && count <= maxEntries
              && directoryOffset + directorySize == length - tailSize + end,
          "ZIP 条目数量或目录边界无效");
      input.seek(directoryOffset);
      long expanded = 0;
      for (int i = 0; i < count; i++) {
        byte[] header = new byte[46];
        input.readFully(header);
        StrictJson.require(u32(header, 0) == 0x02014b50L, "ZIP 中央目录损坏");
        int flags = u16(header, 8),
            method = u16(header, 10),
            names = u16(header, 28),
            extra = u16(header, 30),
            comments = u16(header, 32);
        long size = u32(header, 24),
            compressed = u32(header, 20),
            offset = u32(header, 42),
            attrs = u32(header, 38);
        int unixType = (int) ((attrs >>> 16) & 0170000);
        StrictJson.require(
            (flags & 65) == 0 && (method == ZipEntry.STORED || method == ZipEntry.DEFLATED),
            "ZIP 含加密或不支持的压缩方法");
        StrictJson.require(
            u16(header, 34) == 0
                && names > 0
                && names <= 1024
                && size <= maxExpanded
                && compressed <= file.length(),
            "ZIP 条目边界无效");
        StrictJson.require(
            unixType == 0 || unixType == 0100000 || (allowDirectories && unixType == 0040000),
            "ZIP 不能包含符号链接或特殊文件");
        byte[] nameRaw = new byte[names];
        input.readFully(nameRaw);
        String name =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(nameRaw))
                .toString();
        boolean directory = name.endsWith("/");
        StrictJson.require(allowDirectories || (!directory && (attrs & 16) == 0), "外层 ZIP 不能包含目录");
        StrictJson.require(!directory || size == 0, "ZIP 目录不能携带内容");
        StrictJson.require(result.put(name, new Entry(name, size, directory)) == null, "ZIP 路径重复");
        expanded += size;
        StrictJson.require(expanded <= maxExpanded, "ZIP 展开总量超限");
        long next = input.getFilePointer() + extra + comments;
        StrictJson.require(
            next <= directoryOffset + directorySize
                && offset + 30 + names + compressed <= directoryOffset,
            "ZIP 数据越界");
        input.seek(offset);
        byte[] local = new byte[30];
        input.readFully(local);
        StrictJson.require(
            u32(local, 0) == 0x04034b50L
                && u16(local, 6) == flags
                && u16(local, 8) == method
                && u16(local, 26) == names,
            "ZIP 本地头与中央目录不一致");
        byte[] localName = new byte[names];
        input.readFully(localName);
        StrictJson.require(
            java.util.Arrays.equals(localName, nameRaw)
                && offset + 30 + names + u16(local, 28) + compressed <= directoryOffset,
            "ZIP 本地名称或数据边界不一致");
        input.seek(next);
      }
      StrictJson.require(
          input.getFilePointer() == directoryOffset + directorySize, "ZIP 中央目录含未声明条目");
    }
    entries = Collections.unmodifiableMap(result);
    zip = new ZipFile(file, StandardCharsets.UTF_8);
    if (zip.size() != entries.size()) {
      zip.close();
      throw new IllegalArgumentException("ZIP 解析结果不一致");
    }
  }

  InputStream open(Entry entry) throws Exception {
    ZipEntry actual = zip.getEntry(entry.name);
    StrictJson.require(actual != null && actual.getSize() == entry.size, "ZIP 条目在读取前改变");
    return zip.getInputStream(actual);
  }

  @Override
  public void close() throws java.io.IOException {
    zip.close();
  }

  private static int u16(byte[] b, int p) {
    return (b[p] & 255) | ((b[p + 1] & 255) << 8);
  }

  private static long u32(byte[] b, int p) {
    return (long) u16(b, p) | ((long) u16(b, p + 2) << 16);
  }
}
