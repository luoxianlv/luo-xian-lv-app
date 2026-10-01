package app.luoxianlv.buildlogic;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.zip.Adler32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

/** 精确工具注解允许相同完整定义；访问标志、元素或注解变化仍拒绝重复。 */
public final class ToolAnnotationTest {
  private static final String TYPE = "Lcom/android/tools/r8/annotations/LambdaMethod;";

  @Test public void exactAnnotationProofMatchesOnlyEqualDefinitions() throws Exception {
    Path base = fixture();
    var original = NativeApkContents.read(base);
    assertNotNull(original.toolAnnotations.get(TYPE));
    ExportNativeBuildReport.rejectOverlap(original, NativeApkContents.read(base));
    var metadata = NativeApkContents.read(change(base, 2));
    assertNotNull(metadata.toolAnnotations.get(TYPE));
    assertNotEquals(original.toolAnnotations.get(TYPE), metadata.toolAnnotations.get(TYPE));
    assertThrows(IllegalStateException.class, () -> ExportNativeBuildReport.rejectOverlap(original, metadata));
    for (int mutation : new int[] {0, 1}) {
      var changed = NativeApkContents.read(change(base, mutation));
      assertNull(changed.toolAnnotations.get(TYPE));
      assertThrows(IllegalStateException.class, () -> ExportNativeBuildReport.rejectOverlap(original, changed));
    }
    var ordinary = NativeApkContents.read(resource("host-base"));
    assertThrows(IllegalStateException.class, () -> ExportNativeBuildReport.rejectOverlap(ordinary, ordinary));
  }

  private static Path change(Path base, int mutation) throws Exception {
    Path output = Files.createTempFile("tool-annotation-mutated-", ".apk");
    try (var source = new ZipFile(base.toFile()); var zip = new ZipOutputStream(Files.newOutputStream(output))) {
      var entries = source.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        byte[] bytes;
        try (var input = source.getInputStream(entry)) { bytes = input.readAllBytes(); }
        if (entry.getName().equals("classes.dex")) mutate(bytes, mutation);
        zip.putNextEntry(new ZipEntry(entry.getName()));
        zip.write(bytes);
        zip.closeEntry();
      }
    }
    return output;
  }

  private static void mutate(byte[] bytes, int mutation) throws Exception {
    // 测试只改真实公开小型 DEX；借助同一边界读取器定位，避免猜测编译器偏移。
    Class<?> parser = Class.forName(NativeApkContents.class.getName() + "$Dex");
    var constructor = parser.getDeclaredConstructor(byte[].class, NativeApkContents.class);
    constructor.setAccessible(true);
    Object dex = constructor.newInstance(bytes, new NativeApkContents());
    Method scan = parser.getDeclaredMethod("scan"); scan.setAccessible(true); scan.invoke(dex);
    var typesField = parser.getDeclaredField("types"); typesField.setAccessible(true);
    String[] types = (String[]) typesField.get(dex);
    var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    int definitions = data.getInt(100), count = data.getInt(96), definition = -1;
    for (int index = 0; index < count; index++) {
      int at = definitions + index * 32;
      if (types[data.getInt(at)].equals(TYPE)) definition = at;
    }
    assertTrue(definition >= 0);
    if (mutation == 0) data.putInt(definition + 4, 0x2601);
    else if (mutation == 1) {
      int[] cursor = {data.getInt(definition + 24)};
      Method uleb = parser.getDeclaredMethod("uleb", int[].class); uleb.setAccessible(true);
      for (int index = 0; index < 5; index++) uleb.invoke(dex, (Object) cursor);
      // 第一个元素 public abstract 0x401 改为 private abstract 0x402，编码宽度不变。
      assertEquals(0x81, bytes[cursor[0]] & 255);
      bytes[cursor[0]] = (byte) 0x82;
    } else {
      int directory = data.getInt(definition + 20), set = data.getInt(directory), annotation = data.getInt(set + 4);
      bytes[annotation] = (byte) ((bytes[annotation] + 1) % 3);
    }
    byte[] signature = MessageDigest.getInstance("SHA-1").digest(java.util.Arrays.copyOfRange(bytes, 32, bytes.length));
    System.arraycopy(signature, 0, bytes, 12, signature.length);
    var checksum = new Adler32(); checksum.update(bytes, 12, bytes.length - 12);
    data.putInt(8, (int) checksum.getValue());
  }
  private static Path fixture() throws Exception { return resource("tool-annotation"); }
  private static Path resource(String name) throws Exception {
    Path file = Files.createTempFile("tool-annotation-", ".apk");
    try (var input = ToolAnnotationTest.class.getResourceAsStream("/native/" + name + ".apk")) {
      assertNotNull(input); Files.copy(input, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    return file;
  }
}
