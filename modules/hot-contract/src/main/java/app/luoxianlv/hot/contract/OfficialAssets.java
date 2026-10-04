package app.luoxianlv.hot.contract;

import android.content.Context;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** 未声明挂载才读取APK；已声明的缺失/损坏文件向上传播，不能混用两个版本。 */
public final class OfficialAssets {
  private OfficialAssets() {}
  public static OfficialResources source(Context context) {
    Object value = context.getSystemService(OfficialResources.SERVICE);
    return value instanceof OfficialResources ? (OfficialResources) value : null;
  }
  public static boolean mounted(Context context, String mount) {
    var source = source(context);
    return source != null && source.mounted(mount);
  }
  public static InputStream open(Context context, String mount, String relative, String apkAsset)
      throws IOException {
    var source = source(context);
    return source != null && source.mounted(mount)
        ? source.open(mount, relative) : context.getAssets().open(apkAsset);
  }
  public static byte[] read(Context context, String mount, String relative, String apkAsset, int maximum)
      throws IOException {
    if (maximum <= 0) throw new IllegalArgumentException("资源读取上限无效");
    try (var input = open(context, mount, relative, apkAsset)) {
      var output = new ByteArrayOutputStream();
      byte[] buffer = new byte[32768]; int count;
      while ((count = input.read(buffer)) != -1) {
        if (count == 0 || count > maximum - output.size()) throw new IOException("官方资源读取超限或停滞");
        output.write(buffer, 0, count);
      }
      return output.toByteArray();
    }
  }
  public static String text(Context context, String mount, String relative, String apkAsset, int maximum)
      throws IOException {
    byte[] raw = read(context, mount, relative, apkAsset, maximum);
    try {
      String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
      if (value.startsWith("\uFEFF")) throw new IOException("官方文本包含BOM");
      return value;
    } catch (CharacterCodingException malformed) { throw new IOException("官方文本不是UTF-8", malformed); }
  }
  public static String documentPath(Context context, String mount, String resourcesPath) {
    var source = source(context);
    return source != null && source.kind(mount).equals("config") ? "value" : resourcesPath;
  }
}
