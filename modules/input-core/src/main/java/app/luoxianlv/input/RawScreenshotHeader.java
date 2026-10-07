package app.luoxianlv.input;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** AOSP Android 11–16 的紧密像素流：四个 uint32 头，后续不含行填充。 */
final class RawScreenshotHeader {
  static final int MAX_PIXELS = 20_000_000;
  final int width, height, format, color, bytesPerPixel;

  static final class Unsupported extends IOException {
    Unsupported(int format, int color) { super("系统原始截图需要兼容解码：像素格式=" + format + "，颜色配置=" + color); }
  }

  RawScreenshotHeader(int width, int height, int format, int color) throws IOException {
    dimensions(width, height);
    this.width = width; this.height = height; this.format = format; this.color = color;
    // P3 或未知空间沿用 PNG 的颜色配置，不将原始值猜成 sRGB。
    if (color != 1) throw new Unsupported(format, color);
    bytesPerPixel = switch (format) { case 1, 2, 5 -> 4; case 3 -> 3; case 4 -> 2; default -> throw new Unsupported(format, color); };
  }

  static RawScreenshotHeader read(InputStream input) throws IOException {
    byte[] header = new byte[16];
    readFully(input, header, header.length);
    ByteBuffer fields = ByteBuffer.wrap(header).order(ByteOrder.nativeOrder());
    return new RawScreenshotHeader(fields.getInt(), fields.getInt(), fields.getInt(), fields.getInt());
  }

  static void dimensions(int width, int height) throws IOException {
    if (width < 2 || height < 2 || width > 8192 || height > 8192 || (long) width * height > MAX_PIXELS)
      throw new IOException("截图尺寸超出上限");
  }

  static void readFully(InputStream input, byte[] bytes, int length) throws IOException {
    int offset = 0;
    while (offset < length) {
      int count = input.read(bytes, offset, length - offset);
      if (count < 0) throw new IOException("系统截图像素流不完整");
      if (count == 0) throw new IOException("系统截图流没有进展");
      offset += count;
    }
  }

  void decode(byte[] bytes, int[] pixels, int count) {
    int offset = 0;
    for (int i = 0; i < count; i++, offset += bytesPerPixel) {
      int r, g, b, a = 255;
      if (format == 4) {
        int value = (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
        int r5 = value >> 11, g6 = (value >> 5) & 63, b5 = value & 31;
        r = (r5 << 3) | (r5 >> 2); g = (g6 << 2) | (g6 >> 4); b = (b5 << 3) | (b5 >> 2);
      } else {
        r = bytes[offset + (format == 5 ? 2 : 0)] & 255;
        g = bytes[offset + 1] & 255;
        b = bytes[offset + (format == 5 ? 0 : 2)] & 255;
        if (format == 1 || format == 5) a = bytes[offset + 3] & 255;
      }
      // screencap 输出预乘 RGB，setPixels 接受非预乘颜色，避免透明区域二次变暗。
      if (a == 0) r = g = b = 0;
      else if (a < 255) {
        r = Math.min(255, (r * 255 + a / 2) / a);
        g = Math.min(255, (g * 255 + a / 2) / a);
        b = Math.min(255, (b * 255 + a / 2) / a);
      }
      pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
    }
  }
}
