package app.luoxianlv.input;

import android.graphics.Bitmap;
import android.graphics.ParcelableColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.SystemClock;
import app.luoxianlv.hot.contract.AccessibilityBinding;
import java.io.IOException;

/** 仅在自有输入 Binder 传输；硬件缓冲不编码，原始流逐块解码，源端在序列化后关闭。 */
public final class ScreenshotPacket implements Parcelable, AutoCloseable {
  private DirectScreenshot.Image image;
  private ParcelFileDescriptor descriptor;
  private final long captureMs;
  private final boolean png;

  private ScreenshotPacket(DirectScreenshot.Image image, ParcelFileDescriptor descriptor, long captureMs) {
    this(image, descriptor, captureMs, false);
  }

  private ScreenshotPacket(DirectScreenshot.Image image, ParcelFileDescriptor descriptor, long captureMs, boolean png) {
    this.image = image; this.descriptor = descriptor; this.captureMs = captureMs;
    this.png = png;
  }

  static ScreenshotPacket capture(int displayId, boolean rawOnly) throws IOException {
    if (!InputIdentity.privileged(android.os.Process.myUid()) || displayId != 0)
      throw new IOException("截图服务身份或屏幕无效");
    long began = SystemClock.elapsedRealtime();
    // 旧系统沿用已验证的 PNG 协议；16 字节 raw 头仅用于 Android 11 起。
    if (android.os.Build.VERSION.SDK_INT < 30) return png(ShellScreenshot.capture(displayId));
    DirectScreenshot.Image image = rawOnly ? null : DirectScreenshot.capture(displayId);
    if (image != null) return new ScreenshotPacket(image, null, SystemClock.elapsedRealtime() - began);
    return new ScreenshotPacket(null, ShellScreenshot.raw(displayId), -1);
  }

  static ScreenshotPacket png(ParcelFileDescriptor descriptor) { return new ScreenshotPacket(null, descriptor, -1, true); }

  String kind() { return image != null ? "hardware" : png ? "png-fallback" : "raw"; }
  long captureMs() { return captureMs; }

  @Override public int describeContents() { return CONTENTS_FILE_DESCRIPTOR; }

  @Override public void writeToParcel(Parcel target, int flags) {
    target.writeInt(image != null ? 1 : png ? 2 : 0);
    target.writeLong(captureMs);
    // AIDL 返回值的 RETURN_VALUE 必须传到底层 PFD，可靠管道才会静默转交所有权。
    if (image == null) target.writeTypedObject(descriptor, flags);
    else {
      if (android.os.Build.VERSION.SDK_INT < 31) throw new IllegalStateException("当前系统不支持硬件截图传输");
      target.writeTypedObject(image.buffer, flags);
      target.writeTypedObject(new ParcelableColorSpace(image.color), flags);
    }
  }

  public static final Creator<ScreenshotPacket> CREATOR = new Creator<>() {
    @Override public ScreenshotPacket createFromParcel(Parcel source) {
      int kind = source.readInt();
      if (kind < 0 || kind > 2) throw new IllegalArgumentException("截图传输类型无效");
      long captureMs = source.readLong();
      if (kind != 1) return new ScreenshotPacket(null, source.readTypedObject(ParcelFileDescriptor.CREATOR), captureMs, kind == 2);
      if (android.os.Build.VERSION.SDK_INT < 31) throw new IllegalArgumentException("当前系统不支持硬件截图传输");
      HardwareBuffer buffer = source.readTypedObject(HardwareBuffer.CREATOR);
      try {
        ParcelableColorSpace color = source.readTypedObject(ParcelableColorSpace.CREATOR);
        if (buffer == null) throw new IllegalArgumentException("截图没有返回硬件缓冲");
        return new ScreenshotPacket(new DirectScreenshot.Image(buffer, color == null
            ? android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB) : color.getColorSpace()), null, captureMs);
      } catch (RuntimeException failure) {
        if (buffer != null) buffer.close();
        throw failure;
      }
    }
    @Override public ScreenshotPacket[] newArray(int count) { return new ScreenshotPacket[count]; }
  };

  static AccessibilityBinding.Frame decode(ScreenshotPacket packet) throws IOException {
    return decode(packet, descriptor -> {});
  }

  static AccessibilityBinding.Frame decode(ScreenshotPacket packet,
      java.util.function.Consumer<ParcelFileDescriptor> opened) throws IOException {
    if (packet == null) throw new IOException("截图没有返回数据");
    if (packet.image != null) {
      HardwareBuffer buffer = packet.image.buffer;
      try {
        RawScreenshotHeader.dimensions(buffer.getWidth(), buffer.getHeight());
        AccessibilityBinding.Frame result = new AccessibilityBinding.Frame(buffer, packet.image.color);
        packet.image = null;
        return result;
      } catch (IOException | RuntimeException failure) {
        buffer.close(); packet.image = null; throw failure;
      }
    }
    ParcelFileDescriptor descriptor = packet.descriptor;
    if (descriptor == null) throw new IOException("截图没有返回像素流");
    opened.accept(descriptor);
    Bitmap bitmap = null;
    try (var input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
      if (packet.png) {
        var options = new android.graphics.BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        bitmap = android.graphics.BitmapFactory.decodeStream(input, null, options);
        if (bitmap == null) throw new IOException("兼容截图图像无效");
        RawScreenshotHeader.dimensions(bitmap.getWidth(), bitmap.getHeight());
        AccessibilityBinding.Frame frame = new AccessibilityBinding.Frame(bitmap);
        bitmap = null;
        return frame;
      }
      RawScreenshotHeader header = RawScreenshotHeader.read(input);
      bitmap = Bitmap.createBitmap(header.width, header.height, Bitmap.Config.ARGB_8888);
      int rows = Math.min(32, header.height);
      byte[] bytes = new byte[header.width * rows * header.bytesPerPixel];
      int[] pixels = new int[header.width * rows];
      for (int y = 0; y < header.height; y += rows) {
        int actual = Math.min(rows, header.height - y), count = header.width * actual;
        RawScreenshotHeader.readFully(input, bytes, count * header.bytesPerPixel);
        header.decode(bytes, pixels, count);
        bitmap.setPixels(pixels, 0, header.width, 0, y, header.width, actual);
      }
      if (input.read() != -1) throw new IOException("截图包含多余像素");
      descriptor.checkError();
      AccessibilityBinding.Frame frame = new AccessibilityBinding.Frame(bitmap);
      bitmap = null;
      return frame;
    } finally {
      if (bitmap != null) bitmap.recycle();
      packet.descriptor = null;
    }
  }

  static void discard(ScreenshotPacket packet) { if (packet != null) packet.close(); }

  @Override public void close() {
    if (image != null) { image.close(); image = null; }
    if (descriptor != null) {
      try { descriptor.close(); } catch (IOException ignored) { }
      descriptor = null;
    }
  }
}
