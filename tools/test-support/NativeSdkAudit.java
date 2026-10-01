package app.luoxianlv.tools;

import app.luoxianlv.buildlogic.NativeApkContents;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** 本地独立 javac 工具：直接读取 SDK classfile 与 APK DEX，不启动 Gradle。 */
public final class NativeSdkAudit {
  record Api(Set<String> classes, Set<String> exports) {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 4)
      throw new IllegalArgumentException("Usage: NativeSdkAudit host|runtime apk sdk [mapping], or business apk host-sdk-hash");
    boolean business = args[0].equals("business");
    require(business || args[0].equals("host") || args[0].equals("runtime"), "Unsupported APK role");
    require(business ? args.length == 3 : args.length >= 3, "Unexpected SDK arguments");
    var sdk = business ? new Api(Set.of(), Set.of()) : readSdk(Path.of(args[2]));
    var apk = NativeApkContents.read(Path.of(args[1]));
    verify(sdk, apk.classes, apk.exports);
    if (business || args[0].equals("host"))
      verifyContractMarker(Path.of(args[1]), business ? args[2] : NativeApkContents.hash(Path.of(args[2])));
    if (args.length == 4) verifyMapping(sdk.classes, Files.readAllLines(Path.of(args[3]), StandardCharsets.UTF_8));
    var exports = apk.exports(args[0].equals("host") ? "Lapp/luoxianlv/hot/contract/" : "L");
    System.out.println("{\"role\":\"" + args[0] + "\",\"sdkClasses\":" + sdk.classes.size()
        + ",\"sdkExports\":" + sdk.exports.size() + ",\"apkHash\":\"" + apk.sha256
        + "\",\"sdkHash\":\"" + (business ? "" : NativeApkContents.hash(Path.of(args[2])))
        + "\",\"classFingerprint\":\"" + NativeApkContents.fingerprint(apk.classes)
        + "\",\"exportFingerprint\":\"" + NativeApkContents.fingerprint(exports)
        + "\",\"classCount\":" + apk.classes.size()
        + ",\"resourcePackageIds\":" + apk.packageIds
        + ",\"nativeLibraryCount\":" + apk.nativeLibraries.size()
        + ",\"verification\":\"sdk-classfile-to-actual-dex\"}");
  }

  static Api readSdk(Path file) throws Exception {
    require(Files.size(file) > 0 && Files.size(file) <= 512L * 1024 * 1024, "SDK size invalid");
    var classes = new TreeSet<String>();
    var exports = new TreeSet<String>();
    try (var zip = new ZipFile(file.toFile())) {
      var names = new HashSet<String>();
      long expanded = 0;
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        require(names.add(entry.getName()), "Duplicate SDK ZIP entry");
        if (!entry.getName().endsWith(".class")) continue;
        require(entry.getSize() > 0 && entry.getSize() <= 8 * 1024 * 1024, "SDK class size invalid");
        expanded = Math.addExact(expanded, entry.getSize());
        require(expanded <= 512L * 1024 * 1024 && classes.size() < 100000, "SDK classes exceed limit");
        byte[] bytes;
        try (var stream = zip.getInputStream(entry)) { bytes = stream.readAllBytes(); }
        require(bytes.length == entry.getSize(), "Truncated SDK class");
        var api = readClass(bytes);
        String owner = api.classes.iterator().next();
        require(owner.equals("L" + entry.getName().substring(0, entry.getName().length() - 6) + ";"),
            "SDK class path differs from declared type");
        require(classes.add(owner), "Duplicate SDK type");
        exports.addAll(api.exports);
      }
    }
    require(!classes.isEmpty() && !exports.isEmpty(), "SDK has no exported API");
    return new Api(classes, exports);
  }

  static Api readClass(byte[] bytes) throws Exception {
    try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
      require(input.readInt() == 0xcafebabe, "Invalid classfile magic");
      input.readUnsignedShort(); // minor
      input.readUnsignedShort(); // major
      int count = input.readUnsignedShort();
      Object[] pool = new Object[count];
      for (int i = 1; i < count; i++) {
        switch (input.readUnsignedByte()) {
          case 1 -> pool[i] = input.readUTF(); // JVM modified UTF-8, including supplementary identifiers
          case 7 -> pool[i] = input.readUnsignedShort();
          case 3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
          case 5, 6 -> { input.skipNBytes(8); i++; }
          case 8, 16, 19, 20 -> input.skipNBytes(2);
          case 15 -> input.skipNBytes(3);
          default -> throw new IOException("Unsupported classfile constant tag");
        }
      }
      int flags = input.readUnsignedShort();
      String owner = type(pool, input.readUnsignedShort());
      int parentIndex = input.readUnsignedShort();
      String parent = parentIndex == 0 ? "" : type(pool, parentIndex);
      var interfaces = new TreeSet<String>();
      int interfaceCount = input.readUnsignedShort();
      for (int i = 0; i < interfaceCount; i++) interfaces.add(type(pool, input.readUnsignedShort()));
      var exports = new TreeSet<String>();
      boolean visible = (flags & 5) != 0;
      if (visible) exports.add("C|" + owner + "|" + flags + "|" + parent + "|" + String.join(",", interfaces));
      members(input, pool, "F", owner, visible, exports);
      members(input, pool, "M", owner, visible, exports);
      attributes(input);
      require(input.available() == 0, "Trailing classfile bytes");
      return new Api(Set.of(owner), exports);
    }
  }

  private static void members(DataInputStream input, Object[] pool, String kind, String owner,
      boolean visible, Set<String> exports) throws Exception {
    int count = input.readUnsignedShort();
    for (int i = 0; i < count; i++) {
      int flags = input.readUnsignedShort();
      String name = text(pool, input.readUnsignedShort()), descriptor = text(pool, input.readUnsignedShort());
      if (visible && (flags & 5) != 0)
        exports.add(kind + "|" + owner + "|" + flags + "|" + name + (kind.equals("F") ? ":" : "") + descriptor);
      attributes(input);
    }
  }

  private static void attributes(DataInputStream input) throws IOException {
    int count = input.readUnsignedShort();
    for (int i = 0; i < count; i++) {
      input.readUnsignedShort();
      input.skipNBytes(Integer.toUnsignedLong(input.readInt()));
    }
  }

  private static String text(Object[] pool, int index) throws IOException {
    require(index > 0 && index < pool.length && pool[index] instanceof String, "Invalid UTF-8 pool reference");
    return (String) pool[index];
  }

  private static String type(Object[] pool, int index) throws IOException {
    require(index > 0 && index < pool.length && pool[index] instanceof Integer, "Invalid class pool reference");
    return "L" + text(pool, (Integer) pool[index]) + ";";
  }

  static void verify(Api sdk, Set<String> dexClasses, Set<String> dexExports) throws Exception {
    for (String type : sdk.classes)
      require(dexClasses.contains(type), "SDK type missing/renamed in DEX: " + type);
    var actual = new java.util.HashMap<String, String>();
    for (String line : dexExports) {
      String[] parts = line.split("\\|", -1);
      String identity = identity(parts);
      require(actual.putIfAbsent(identity, line) == null, "Duplicate DEX API identity: " + identity);
    }
    for (String line : sdk.exports) {
      String[] expected = line.split("\\|", -1);
      String identity = identity(expected);
      String found = actual.get(identity);
      require(found != null, "SDK export missing/renamed in DEX: " + identity);
      String[] emitted = found.split("\\|", -1);
      int from = Integer.parseInt(expected[2]), to = Integer.parseInt(emitted[2]);
      // DEX constructor/synchronized/desugaring flags differ from JVM flags. Compare linkage-relevant bits.
      require((to & 1) != 0 || ((from & 1) == 0 && (to & 4) != 0), "SDK export visibility narrowed: " + identity);
      // 动态业务仍可能继承、override、写字段或实例化；优化器看不到这些后续调用。
      require((from & 0x10) != 0 || (to & 0x10) == 0, "SDK export became final: " + identity);
      if (!expected[0].equals("F"))
        require((from & 0x400) != 0 || (to & 0x400) == 0, "SDK export became abstract: " + identity);
      if (expected[0].equals("C")) {
        require((from & 0x6200) == (to & 0x6200), "SDK class kind changed: " + identity);
        require(expected[3].equals(emitted[3]) && expected[4].equals(emitted[4]),
            "SDK parent/interfaces changed: " + identity);
      } else {
        require((from & 8) == (to & 8), "SDK member static/instance shape changed: " + identity);
      }
    }
  }

  private static String identity(String[] parts) throws IOException {
    require(parts.length == (parts[0].equals("C") ? 5 : 4), "Invalid API record");
    require(parts[0].equals("C") || parts[0].equals("F") || parts[0].equals("M"), "Invalid API kind");
    return parts[0] + "|" + parts[1] + (parts[0].equals("C") ? "" : "|" + parts[3]);
  }

  static void verifyMapping(Set<String> sdkClasses, java.util.List<String> lines) throws Exception {
    var classLine = Pattern.compile("^(\\S+) -> (\\S+):$");
    for (String line : lines) {
      var match = classLine.matcher(line);
      if (!match.matches()) continue; // Inlined method mappings need not correspond to standalone methods.
      String owner = "L" + match.group(1).replace('.', '/') + ";";
      if (sdkClasses.contains(owner))
        require(match.group(1).equals(match.group(2)), "SDK class renamed in mapping: " + owner);
    }
  }

  static void verifyContractMarker(Path apk, String expected) throws Exception {
    require(expected.matches("[a-f0-9]{64}"), "Invalid expected host SDK hash");
    try (var zip = new ZipFile(apk.toFile())) {
      var entry = zip.getEntry("assets/hot/host-contract.sha256");
      require(entry != null && !entry.isDirectory() && entry.getSize() == 65, "Missing/invalid compiled host SDK marker");
      byte[] bytes;
      try (var input = zip.getInputStream(entry)) { bytes = input.readNBytes(66); }
      require(bytes.length == 65 && bytes[64] == '\n', "Compiled host SDK marker must be 64 hex + LF");
      String actual = new String(bytes, 0, 64, StandardCharsets.US_ASCII);
      require(actual.matches("[a-f0-9]{64}") && actual.equals(expected), "Compiled host SDK marker differs from actual SDK");
    }
  }

  private static void require(boolean condition, String message) throws IOException {
    if (!condition) throw new IOException(message);
  }
}
