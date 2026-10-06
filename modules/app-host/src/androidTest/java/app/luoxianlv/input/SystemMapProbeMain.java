package app.luoxianlv.input;

import android.graphics.Point;
import android.os.Build;
import android.os.Looper;
import android.os.Process;
import android.view.Display;
import android.view.InputDevice;
import java.util.ArrayList;

/** Shell 测试入口：核对真实系统映射和注入器准备，不注入、抓取或修改任何触点。 */
public final class SystemMapProbeMain {
  public static void main(String[] arguments) throws Exception {
    if (Process.myUid() != 2000 || arguments.length != 1)
      throw new IllegalStateException("仅允许普通 shell 指定测试触屏路径");
    if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
    Class.forName("android.app.ActivityThread").getDeclaredMethod("systemMain").invoke(null);
    ArrayList<InputDeviceMatcher.Device> devices = new ArrayList<>();
    for (int id : InputDevice.getDeviceIds()) {
      InputDevice device = InputDevice.getDevice(id);
      if (device == null || !device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) continue;
      // 模拟公开名称与内核名称不同，强制验证系统路径/descriptor 分支。
      devices.add(new InputDeviceMatcher.Device(id, "公开名称偏差测试", device.getVendorId(),
          device.getProductId(), device.getDescriptor(), TouchEngine.targetsMainDisplay(device)));
    }
    InputDeviceMatcher.Match match = InputDeviceMatcher.bySystemMap(arguments[0], SystemInputDeviceMap.read(), devices);
    if (!match.found()) throw new AssertionError(match.error());
    Class<?> displays = Class.forName("android.hardware.display.DisplayManagerGlobal");
    Object manager = displays.getDeclaredMethod("getInstance").invoke(null);
    Display display = (Display) displays.getMethod("getRealDisplay", int.class).invoke(manager, Display.DEFAULT_DISPLAY);
    Point size = new Point();
    display.getRealSize(size);
    MergedTouchDispatcher dispatcher = new MergedTouchDispatcher(match.id(), size.x, size.y, display.getRotation());
    System.out.println("系统触屏映射通过：API=" + Build.VERSION.SDK_INT + "，方法=" + match.method()
        + "，设备ID=" + match.id() + "，注入器准备通过，UID=" + Process.myUid() + "；未注入触摸。");
    System.exit(0);
  }
}
