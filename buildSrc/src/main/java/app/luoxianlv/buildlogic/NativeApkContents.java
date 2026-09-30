package app.luoxianlv.buildlogic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipFile;

/** 从实际 APK 读取 DEX 导出边界和资源表，不能用源码声明代替最终产物。 */
public final class NativeApkContents {
  public final Set<String> classes = new TreeSet<>(), exports = new TreeSet<>();
  public final Set<Integer> packageIds = new TreeSet<>();
  public final Set<String> nativeAbis = new TreeSet<>();
  public final Map<String, String> nativeLibraries = new TreeMap<>();
  public final Map<String, String> toolAnnotations = new TreeMap<>();
  public String runtimeAbi = "", resourceFingerprint, sha256;
  public long size;

  public static NativeApkContents read(Path apk) throws Exception {
    var result = new NativeApkContents();
    result.size = Files.size(apk);
    require(result.size > 0 && result.size <= 512L * 1024 * 1024, "APK 大小无效");
    result.sha256 = hash(apk);
    try (var zip = new ZipFile(apk.toFile())) {
      var names = new TreeSet<String>();
      var entries = zip.entries();
      long expanded = 0;
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        require(names.add(entry.getName()), "APK 条目重复");
        require(entry.getSize() >= 0, "APK 条目大小缺失");
        expanded += entry.getSize();
        require(expanded <= 1024L * 1024 * 1024, "APK 展开大小超限");
        if (entry.getName().matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) {
          try (var input = zip.getInputStream(entry)) {
            new Dex(read(input, 256 * 1024 * 1024), result).scan();
          }
        } else if (entry.getName().equals("resources.arsc")) {
          byte[] resources;
          try (var input = zip.getInputStream(entry)) {
            resources = read(input, 128 * 1024 * 1024);
          }
          result.resourceFingerprint = hash(resources);
          var data = ByteBuffer.wrap(resources).order(ByteOrder.LITTLE_ENDIAN);
          require(resources.length >= 12 && Short.toUnsignedInt(data.getShort(0)) == 2,
              "APK 资源表头无效");
          int end = data.getInt(4), at = Short.toUnsignedInt(data.getShort(2));
          require(end == resources.length && at >= 12, "APK 资源表长度无效");
          while (at < end) {
            require(at <= end - 8, "APK 资源块头截断");
            int header = Short.toUnsignedInt(data.getShort(at + 2)), length = data.getInt(at + 4);
            require(header >= 8 && length >= header && length <= end - at, "APK 资源块无效");
            if (Short.toUnsignedInt(data.getShort(at)) == 0x200) {
              require(header >= 12, "APK 资源包头截断");
              int id = data.getInt(at + 8);
              require(id > 0 && id <= 255 && result.packageIds.add(id), "APK 资源包编号无效或重复");
            }
            at += length;
          }
          require(!result.packageIds.isEmpty()
              && data.getInt(8) == result.packageIds.size(), "APK 资源包数量无效");
        } else if (entry.getName().equals("assets/runtime-abi.txt")) {
          try (var input = zip.getInputStream(entry)) {
            result.runtimeAbi = new String(read(input, 1024), StandardCharsets.UTF_8).trim();
          }
          require(result.runtimeAbi.matches("[a-z][a-z0-9._-]{0,95}"), "APK 运行时 ABI 无效");
        } else if (entry.getName().matches("lib/[a-z0-9_-]+/[^/]+\\.so")) {
          result.nativeAbis.add(entry.getName().split("/")[1]);
          try (var input = zip.getInputStream(entry)) {
            result.nativeLibraries.put(entry.getName(), hash(read(input, 64 * 1024 * 1024)));
          }
        }
      }
    }
    require(!result.classes.isEmpty() && result.resourceFingerprint != null,
        "原生 APK 缺少 DEX 或资源表");
    return result;
  }

  public Set<String> exports(String descriptorPrefix) {
    var selected = new TreeSet<String>();
    for (String line : exports)
      if (line.substring(2).startsWith(descriptorPrefix)) selected.add(line);
    return selected;
  }

  public static String fingerprint(Iterable<?> values) throws Exception {
    var lines = new StringBuilder();
    for (Object value : values) lines.append(value).append('\n');
    return hash(lines.toString().getBytes(StandardCharsets.UTF_8));
  }

  public static String hash(Path path) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(path)) {
      byte[] buffer = new byte[65536];
      int count;
      while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  public static String hash(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static byte[] read(InputStream input, int maximum) throws IOException {
    byte[] bytes = input.readNBytes(maximum + 1);
    require(bytes.length <= maximum, "APK 条目展开超限");
    return bytes;
  }

  private static void require(boolean valid, String message) throws IOException {
    if (!valid) throw new IOException(message);
  }

  /** 标准 DEX 035–040；不把尚未实现的 DEX 041 容器协议当作支持。 */
  private static final class Dex {
    final ByteBuffer data;
    final NativeApkContents result;
    String[] strings, types, protos;
    int fields, methods, fieldCount, methodCount, stringTable;

    Dex(byte[] bytes, NativeApkContents result) throws Exception {
      this.result = result;
      data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
      require(bytes.length >= 112, "DEX 头截断");
      String magic = new String(bytes, 0, 8, StandardCharsets.US_ASCII);
      require(magic.matches("dex\\n0(?:3[5-9]|40)\\x00")
          && u32(32) == bytes.length && u32(36) == 112 && u32(40) == 0x12345678,
          "DEX 版本或头无效");
    }

    int u16(int at) throws IOException { range(at, 2); return Short.toUnsignedInt(data.getShort(at)); }
    int u32(int at) throws IOException {
      range(at, 4);
      int value = data.getInt(at);
      require(value >= 0, "DEX 偏移或计数超限");
      return value;
    }
    void range(int at, int size) throws IOException {
      require(at >= 0 && size >= 0 && at <= data.capacity() - size, "DEX 数据截断");
    }
    int uleb(int[] at) throws IOException {
      long value = 0;
      for (int index = 0; index < 5; index++) {
        range(at[0], 1);
        int next = Byte.toUnsignedInt(data.get(at[0]++));
        value |= (long) (next & 127) << (index * 7);
        if ((next & 128) == 0) {
          require(value <= Integer.MAX_VALUE, "DEX 变长整数超限");
          return (int) value;
        }
      }
      throw new IOException("DEX 变长整数无效");
    }
    String item(String[] items, int at) throws IOException {
      require(at >= 0 && at < items.length, "DEX 索引无效");
      if (items == strings && items[at] == null) items[at] = string(at);
      return items[at];
    }
    List<String> typeList(int offset) throws IOException {
      var list = new ArrayList<String>();
      if (offset == 0) return list;
      int count = u32(offset);
      require(count <= 65535, "DEX 类型列表超限");
      range(offset + 4, count * 2);
      for (int index = 0; index < count; index++) list.add(item(types, u16(offset + 4 + index * 2)));
      return list;
    }
    int table(int countAt, int width) throws IOException {
      int count = u32(countAt), offset = u32(countAt + 4);
      require(count <= 1000000, "DEX 表数量超限");
      range(offset, Math.multiplyExact(count, width));
      return offset;
    }
    // 只解码类型、字段和方法名称；业务常量、注解元数据可能超过一百万字符，不能参与接口扫描。
    String string(int index) throws IOException {
        int[] cursor = {u32(stringTable + index * 4)};
        int chars = uleb(cursor);
        require(chars <= 65535, "DEX 接口名称超限");
        var value = new StringBuilder();
        for (int length = 0; length < chars; length++) {
          range(cursor[0], 1);
          int first = Byte.toUnsignedInt(data.get(cursor[0]++));
          require(first != 0, "DEX 字符串过早结束");
          if (first < 128) value.append((char) first);
          else if ((first & 224) == 192) {
            range(cursor[0], 1);
            int second = Byte.toUnsignedInt(data.get(cursor[0]++));
            require((second & 192) == 128, "DEX 字符编码无效");
            value.append((char) (((first & 31) << 6) | (second & 63)));
          } else {
            require((first & 240) == 224, "DEX 字符编码无效");
            range(cursor[0], 2);
            int second = Byte.toUnsignedInt(data.get(cursor[0]++)),
                third = Byte.toUnsignedInt(data.get(cursor[0]++));
            require((second & 192) == 128 && (third & 192) == 128, "DEX 字符编码无效");
            value.append((char) (((first & 15) << 12) | ((second & 63) << 6) | (third & 63)));
          }
        }
        range(cursor[0], 1);
        require(data.get(cursor[0]) == 0, "DEX 字符串缺少结束符");
        return value.toString();
    }
    void scan() throws Exception {
      stringTable = table(56, 4);
      strings = new String[u32(56)];
      int typeTable = table(64, 4);
      types = new String[u32(64)];
      for (int index = 0; index < types.length; index++) types[index] = item(strings, u32(typeTable + index * 4));
      int protoTable = table(72, 12);
      protos = new String[u32(72)];
      for (int index = 0; index < protos.length; index++) {
        int at = protoTable + index * 12;
        protos[index] = "(" + String.join("", typeList(u32(at + 8))) + ")" + item(types, u32(at + 4));
      }
      fields = table(80, 8); fieldCount = u32(80);
      methods = table(88, 8); methodCount = u32(88);
      int definitions = table(96, 32), count = u32(96);
      for (int index = 0; index < count; index++) {
        int at = definitions + index * 32;
        String owner = item(types, u32(at));
        require(owner.startsWith("L") && owner.endsWith(";") && result.classes.add(owner),
            "DEX 类重复或无效");
        int access = u32(at + 4);
        boolean visible = (access & 5) != 0;
        var interfaces = new TreeSet<>(typeList(u32(at + 12)));
        int parent = data.getInt(at + 8);
        if (visible) result.exports.add("C|" + owner + "|" + (access & 0x761f) + "|"
            + (parent == -1 ? "" : item(types, parent)) + "|" + String.join(",", interfaces));
        int offset = u32(at + 24);
        if (offset == 0) continue;
        int[] cursor = {offset};
        int staticFields = uleb(cursor), instanceFields = uleb(cursor), directMethods = uleb(cursor), virtualMethods = uleb(cursor);
        members(cursor, staticFields, true, visible, owner);
        members(cursor, instanceFields, true, visible, owner);
        members(cursor, directMethods, false, visible, owner);
        members(cursor, virtualMethods, false, visible, owner);
        String tool = toolDefinition(at, owner, access, parent, interfaces, offset);
        if (tool != null) result.toolAnnotations.put(owner, tool);
      }
    }

    // 只证明这一精确的 D8 合成注解，无字段、构造器或可执行方法；其它重复类仍失败。
    String toolDefinition(int definition, String owner, int access, int parent,
        Set<String> interfaces, int offset) throws Exception {
      if (!owner.equals("Lcom/android/tools/r8/annotations/LambdaMethod;") || access != 0x3601
          || parent == -1 || !item(types, parent).equals("Ljava/lang/Object;")
          || !interfaces.equals(Set.of("Ljava/lang/annotation/Annotation;")) || offset == 0)
        return null;
      int[] cursor = {offset};
      if (uleb(cursor) != 0 || uleb(cursor) != 0 || uleb(cursor) != 0 || uleb(cursor) != 3) return null;
      var lines = new TreeSet<String>();
      lines.add("C|" + owner + "|13825|Ljava/lang/Object;|Ljava/lang/annotation/Annotation;");
      var signatures = new TreeSet<String>();
      int member = 0;
      for (int index = 0; index < 3; index++) {
        member = Math.addExact(member, uleb(cursor));
        int flags = uleb(cursor), code = uleb(cursor);
        if (member >= methodCount || flags != 0x401 || code != 0) return null;
        int at = methods + member * 8;
        if (!item(types, u16(at)).equals(owner)) return null;
        String signature = item(strings, u32(at + 4)) + item(protos, u16(at + 2));
        signatures.add(signature);
        lines.add("M|" + owner + "|1025|" + signature);
      }
      if (!signatures.equals(Set.of("holder()Ljava/lang/String;", "method()Ljava/lang/String;",
          "proto()Ljava/lang/String;"))) return null;
      int annotations = u32(definition + 20);
      if (annotations != 0) {
        if (u32(annotations + 4) != 0 || u32(annotations + 8) != 0 || u32(annotations + 12) != 0)
          return null;
        int set = u32(annotations), count = set == 0 ? 0 : u32(set);
        require(count <= 1000, "工具注解数量超限");
        for (int index = 0; index < count; index++) {
          int at = u32(set + 4 + index * 4);
          range(at, 1);
          int visibility = Byte.toUnsignedInt(data.get(at));
          require(visibility <= 2, "工具注解可见性无效");
          cursor[0] = at + 1;
          String annotation = "A|" + visibility + "|" + annotation(cursor, 0);
          require(lines.add(annotation), "工具注解重复");
        }
      }
      return fingerprint(lines);
    }

    String annotation(int[] cursor, int depth) throws Exception {
      require(depth <= 32, "工具注解嵌套超限");
      String type = hex(item(types, uleb(cursor)));
      int count = uleb(cursor);
      require(count <= 1000, "工具注解元素超限");
      var values = new TreeMap<String, String>();
      for (int index = 0; index < count; index++) {
        String name = item(strings, uleb(cursor));
        require(values.putIfAbsent(name, value(cursor, depth + 1)) == null, "工具注解元素重复");
      }
      var parts = new ArrayList<String>();
      values.forEach((name, value) -> parts.add(hex(name) + "=" + value));
      return type + "(" + String.join(",", parts) + ")";
    }

    String value(int[] cursor, int depth) throws Exception {
      require(depth <= 32, "工具注解值嵌套超限");
      range(cursor[0], 1);
      int header = Byte.toUnsignedInt(data.get(cursor[0]++)), type = header & 31, argument = header >>> 5;
      if (type == 28) {
        require(argument == 0, "工具注解数组头无效");
        int count = uleb(cursor);
        require(count <= 1000, "工具注解数组超限");
        var parts = new ArrayList<String>();
        for (int index = 0; index < count; index++) parts.add(value(cursor, depth + 1));
        return "28:[" + String.join(",", parts) + "]";
      }
      if (type == 29) { require(argument == 0, "工具嵌套注解头无效"); return "29:" + annotation(cursor, depth + 1); }
      if (type == 30) { require(argument == 0, "工具空注解值无效"); return "30:null"; }
      if (type == 31) { require(argument <= 1, "工具布尔注解值无效"); return "31:" + argument; }
      int maximum = switch (type) {
        case 0 -> 1; case 2, 3 -> 2; case 4, 16, 21, 23, 24, 25, 26, 27 -> 4;
        case 6, 17 -> 8; default -> 0;
      };
      require(maximum != 0 && argument < maximum, "工具注解值类型或宽度不支持");
      int width = argument + 1;
      range(cursor[0], width);
      long bits = 0;
      for (int index = 0; index < width; index++) bits |= (long) Byte.toUnsignedInt(data.get(cursor[0]++)) << (8 * index);
      String content;
      switch (type) {
        case 0, 2, 4, 6 -> content = Long.toString((bits << (64 - width * 8)) >> (64 - width * 8));
        case 3 -> content = Long.toUnsignedString(bits);
        case 16 -> content = String.format(java.util.Locale.ROOT, "%08x", (bits << ((4 - width) * 8)) & 0xffffffffL);
        case 17 -> content = String.format(java.util.Locale.ROOT, "%016x", bits << ((8 - width) * 8));
        case 21 -> content = hex(item(protos, Math.toIntExact(bits)));
        case 23 -> content = hex(item(strings, Math.toIntExact(bits)));
        case 24 -> content = hex(item(types, Math.toIntExact(bits)));
        case 25, 27 -> {
          require(bits < fieldCount, "工具注解字段索引无效");
          int at = fields + (int) bits * 8;
          content = hex(item(types, u16(at)) + "|" + item(strings, u32(at + 4)) + ":" + item(types, u16(at + 2)));
        }
        case 26 -> {
          require(bits < methodCount, "工具注解方法索引无效");
          int at = methods + (int) bits * 8;
          content = hex(item(types, u16(at)) + "|" + item(strings, u32(at + 4)) + item(protos, u16(at + 2)));
        }
        default -> throw new java.io.IOException("工具注解值类型不支持");
      }
      return type + ":" + content;
    }
    String hex(String value) { return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8)); }
    void members(int[] cursor, int count, boolean field, boolean visible, String owner) throws Exception {
      int member = 0;
      require(count <= (field ? fieldCount : methodCount), "DEX 成员数量无效");
      for (int index = 0; index < count; index++) {
        member = Math.addExact(member, uleb(cursor));
        int access = uleb(cursor);
        if (!field) uleb(cursor);
        require(member < (field ? fieldCount : methodCount), "DEX 成员索引无效");
        int at = (field ? fields : methods) + member * 8;
        require(item(types, u16(at)).equals(owner), "DEX 成员归属无效");
        if (visible && (access & 5) != 0) {
          String signature = item(strings, u32(at + 4)) + (field ? ":" + item(types, u16(at + 2)) : item(protos, u16(at + 2)));
          result.exports.add((field ? "F|" : "M|") + owner + "|" + (access & 0x1ffff) + "|" + signature);
        }
      }
    }
  }
}
