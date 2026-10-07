package app.luoxianlv.input;

import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.SystemClock;
import java.lang.ref.Reference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;

/** Android 14 起按逻辑屏幕捕获；权限由 WindowManager 检查，迟到结果立即关闭。 */
final class DirectScreenshot {
  private static final long FAST_DEADLINE_MS = 700;
  private static Methods cached;
  private static boolean unavailable;

  static final class Image implements AutoCloseable {
    final HardwareBuffer buffer;
    final ColorSpace color;
    Image(HardwareBuffer buffer, ColorSpace color) { this.buffer = buffer; this.color = color; }
    @Override public void close() { buffer.close(); }
  }

  private record Methods(Object windowManager, Method capture, Constructor<?> listener,
                         Method hardware, Method color) {}

  private static synchronized Methods methods() throws ReflectiveOperationException {
    if (unavailable || Build.VERSION.SDK_INT < 34) return null;
    if (cached != null) return cached;
    ReflectiveOperationException missing = null;
    // Android 16 的后续系统版本将内部类型迁入 ScreenCaptureInternal，按能力解析。
    for (String owner : new String[] {"android.window.ScreenCapture", "android.window.ScreenCaptureInternal"}) try {
      Class<?> arguments = Class.forName(owner + "$CaptureArgs");
      Class<?> listener = Class.forName(owner + "$ScreenCaptureListener");
      Class<?> image = Class.forName(owner + "$ScreenshotHardwareBuffer");
      Object manager = Class.forName("android.view.WindowManagerGlobal")
          .getDeclaredMethod("getWindowManagerService").invoke(null);
      Constructor<?> constructor = listener.getDeclaredConstructor(
          Build.VERSION.SDK_INT >= 35 ? ObjIntConsumer.class : Consumer.class);
      constructor.setAccessible(true);
      Method call = Class.forName("android.view.IWindowManager")
          .getMethod("captureDisplay", int.class, arguments, listener);
      cached = new Methods(manager, call, constructor,
          image.getMethod("getHardwareBuffer"), image.getMethod("getColorSpace"));
      return cached;
    } catch (ReflectiveOperationException absent) { missing = absent; }
    unavailable = true;
    throw missing;
  }

  static Image capture(int displayId) {
    if (Build.VERSION.SDK_INT < 34) return null;
    try {
      Methods methods = methods();
      if (methods == null) return null;
      long began = SystemClock.elapsedRealtime();
      try (CaptureTicket<Image> ticket = new CaptureTicket<>()) {
        Consumer<Object> received = screenshot -> {
          HardwareBuffer buffer = null;
          try {
            if (screenshot != null) {
              buffer = (HardwareBuffer) methods.hardware.invoke(screenshot);
              if (buffer != null) {
                RawScreenshotHeader.dimensions(buffer.getWidth(), buffer.getHeight());
                ColorSpace color = (ColorSpace) methods.color.invoke(screenshot);
                ticket.offer(new Image(buffer, color == null ? ColorSpace.get(ColorSpace.Named.SRGB) : color));
                return;
              }
            }
          } catch (ReflectiveOperationException | RuntimeException | java.io.IOException failure) {
            if (buffer != null) buffer.close();
          }
          ticket.offer(null);
        };
        Object callback = Build.VERSION.SDK_INT >= 35
            ? (ObjIntConsumer<Object>) (image, status) -> {
              if (status == 0) received.accept(image);
              else {
                if (image != null) try {
                  HardwareBuffer buffer = (HardwareBuffer) methods.hardware.invoke(image);
                  if (buffer != null) buffer.close();
                } catch (ReflectiveOperationException | RuntimeException ignored) { }
                ticket.offer(null);
              }
            }
            : received;
        Object listener = methods.listener.newInstance(callback);
        // args=null 使用该显示的真实窗口边界，不截取受保护图层。
        try {
          methods.capture.invoke(methods.windowManager, displayId, null, listener);
          return ticket.await(Math.max(1, FAST_DEADLINE_MS - (SystemClock.elapsedRealtime() - began)));
        } finally {
          // 系统原生监听器只保留弱引用；等待结束前不能让 Java 回调被回收。
          Reference.reachabilityFence(callback);
          Reference.reachabilityFence(listener);
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return null;
    } catch (ReflectiveOperationException | RuntimeException unavailable) {
      Throwable detail = unavailable instanceof java.lang.reflect.InvocationTargetException
          ? ((java.lang.reflect.InvocationTargetException) unavailable).getTargetException() : unavailable;
      android.util.Log.w("截图", "系统直接捕获不可用：类型=" + detail.getClass().getSimpleName());
      return null;
    }
  }
  private DirectScreenshot() {}
}
