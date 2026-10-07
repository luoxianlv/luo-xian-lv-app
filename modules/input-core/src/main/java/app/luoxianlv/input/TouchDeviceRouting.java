package app.luoxianlv.input;

import android.os.Bundle;
import android.view.InputDevice;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** shell 内解析当前系统路由；完整 dumpsys、descriptor 和设备地址不会交给宿主或日志。 */
final class TouchDeviceRouting {
  private static final java.util.concurrent.ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
    Thread thread = new Thread(task, "input-route-deadline"); thread.setDaemon(true); return thread;
  });
  private static final Method associatedDisplay = optional("getAssociatedDisplayId");
  private static final Method generation = optional("getGeneration");
  private static TouchRouteModel.Hint hint;
  private static String cachedKey;
  private static long cachedAt;
  private static Map<String, TouchRouteModel.Raw> cachedRaw = Map.of();
  private static Map<Integer, TouchRouteModel.Reader> cachedReaders = Map.of();

  /** Android 8.0 没有此公开接口，仍须通过后续 EventHub/Reader 的启用状态核对。 */
  private static boolean enabled(InputDevice device) {
    return android.os.Build.VERSION.SDK_INT < 27 || device.isEnabled();
  }

  static synchronized boolean hint(int deviceId, int displayId, String descriptor) {
    InputDevice device = InputDevice.getDevice(deviceId);
    if (deviceId <= 0 || displayId != 0 || device == null || !enabled(device)
        || !device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) || !device.getDescriptor().equals(descriptor)) return false;
    hint = new TouchRouteModel.Hint(deviceId, descriptor, displayId, number(generation, device));
    return true;
  }

  static synchronized Bundle resolve(ArrayList<Bundle> inventory) {
    ArrayList<TouchRouteModel.Candidate> candidates = new ArrayList<>();
    StringBuilder identities = new StringBuilder();
    for (Bundle node : inventory) {
      identities.append(node.getString("path", "")).append(':').append(node.getString("identityFingerprint", ""))
          .append(':').append(node.getInt("errno")).append(';');
      if (!node.getBoolean("eligible") && !node.getBoolean("uncertain")) continue;
      candidates.add(new TouchRouteModel.Candidate(node.getString("path", ""), node.getString("name", ""),
          node.getInt("bus", -1), node.getInt("vendorId", -1), node.getInt("productId", -1), node.getInt("version", -1), node.getBoolean("eligible"), node.getBoolean("direct")));
    }
    String error = "";
    List<TouchRouteModel.Device> devices = List.of();
    Map<String, TouchRouteModel.Raw> raw = Map.of();
    Map<Integer, TouchRouteModel.Reader> readers = Map.of();
    try {
      devices = devices();
      String key = identities + devices.toString();
      if (devices.stream().anyMatch(device -> device.generation() < 0) || !key.equals(cachedKey)
          || android.os.SystemClock.elapsedRealtime() - cachedAt > 5000) {
        String dump = dump();
        Map<String, TouchRouteModel.Raw> found = TouchRouteModel.parse(dump, android.os.Build.VERSION.SDK_INT == 26);
        Map<Integer, TouchRouteModel.Reader> associations = TouchRouteModel.readers(dump, android.os.Build.VERSION.SDK_INT < 34);
        cachedRaw = found; cachedReaders = associations; cachedKey = key; cachedAt = android.os.SystemClock.elapsedRealtime();
      }
      raw = cachedRaw; readers = cachedReaders;
      // 采集过程中新增、停用、旋转或重新配置设备时，丢弃本次系统关联。
      if (!devices.equals(devices())) {
        cachedKey = null;
        Bundle changed = new Bundle();
        changed.putString("selectedPath", ""); changed.putString("selectionMethod", "state-changed");
        changed.putString("selectionReason", "系统输入状态刚发生变化，请重新连接");
        changed.putString("routingDiagnostics", "采集前后的逻辑设备身份或配置不一致，未接管触屏");
        return changed;
      }
    } catch (Exception failure) {
      cachedKey = null;
      error = "系统路由证据不可用（" + failure.getClass().getSimpleName() + "）"
          + (failure instanceof IOException ? "：" + failure.getMessage() : "");
    }
    if (hint != null && devices.stream().noneMatch(device -> device.id() == hint.id()
        && device.generation() == hint.generation() && device.descriptor().equals(hint.descriptor()))) hint = null;
    // 同一 rdev 的别名不增加设备数量，优先使用系统实际登记的精确路径。
    for (int i = 0; i < candidates.size(); i++) {
      TouchRouteModel.Candidate candidate = candidates.get(i);
      if (raw.containsKey(candidate.path())) continue;
      for (Bundle node : inventory) if (candidate.path().equals(node.getString("path"))) {
        ArrayList<String> aliases = node.getStringArrayList("aliases");
        if (aliases == null) continue;
        String matched = null;
        for (String path : aliases) if (raw.containsKey(path)) {
          if (matched != null) { matched = null; break; }
          matched = path;
        }
        if (matched != null) candidates.set(i, new TouchRouteModel.Candidate(matched, candidate.name(), candidate.bus(),
            candidate.vendor(), candidate.product(), candidate.version(), candidate.capable(), candidate.direct()));
      }
    }
    TouchRouteModel.Decision decision = TouchRouteModel.choose(candidates, raw, readers, devices, hint, 0);
    if (decision.method().equals("stale-route")) cachedKey = null;
    Bundle result = new Bundle();
    result.putString("selectedPath", decision.path());
    result.putString("selectionMethod", decision.method());
    result.putString("selectionReason", decision.reason());
    StringBuilder diagnostics = new StringBuilder(error);
    candidates.stream().sorted(Comparator.comparing(TouchRouteModel.Candidate::path)).forEach(candidate -> {
      if (diagnostics.length() > 0) diagnostics.append("；");
      diagnostics.append(candidate.path()).append('：').append(decision.evidence().get(candidate.path()));
    });
    result.putString("routingDiagnostics", diagnostics.toString());
    return result;
  }

  private static List<TouchRouteModel.Device> devices() throws IOException {
    int[] ids = InputDevice.getDeviceIds();
    if (ids.length > 256) throw new IOException("系统逻辑输入设备超过上限");
    ArrayList<TouchRouteModel.Device> values = new ArrayList<>();
    for (int id : ids) {
      if (id <= 0) continue;
      InputDevice device = InputDevice.getDevice(id);
      if (device == null) continue;
      values.add(new TouchRouteModel.Device(id, number(generation, device), device.getName(), device.getDescriptor(),
          device.getVendorId(), device.getProductId(), enabled(device), device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN),
          number(associatedDisplay, device)));
    }
    values.sort(Comparator.comparingInt(TouchRouteModel.Device::id));
    return values;
  }

  private static Method optional(String name) {
    try { return InputDevice.class.getMethod(name); }
    catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
  }
  private static int number(Method method, Object object) {
    if (method == null) return -1;
    try { return (Integer) method.invoke(object); }
    catch (ReflectiveOperationException | RuntimeException unavailable) { return -1; }
  }

  private static String dump() throws IOException, ReflectiveOperationException {
    // 与 dumpsys 使用同一 Binder dump 接口，省去启动进程，并保持原系统 DUMP 权限检查。
    android.os.IBinder manager = (android.os.IBinder) Class.forName("android.os.ServiceManager")
        .getMethod("getService",String.class).invoke(null,"input");
    if (manager == null) throw new IOException("系统输入服务不可用");
    var pipe = android.os.ParcelFileDescriptor.createPipe();
    var timeout = deadlines.schedule(() -> {
      try { pipe[0].close(); } catch (IOException ignored) { }
      try { pipe[1].close(); } catch (IOException ignored) { }
    },1500,TimeUnit.MILLISECONDS);
    try (var input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);
         var output = new ByteArrayOutputStream()) {
      // oneway 交付后系统持有写端副本，本地写端关闭后 EOF 才能表示系统已结束输出。
      try { manager.dumpAsync(pipe[1].getFileDescriptor(),new String[0]); }
      catch (android.os.RemoteException failed) { throw new IOException("系统输入服务未交付摘要",failed); }
      finally { pipe[1].close(); }
      byte[] bytes = new byte[8192];
      for (int count; (count = input.read(bytes)) != -1;) {
        if (output.size() + count > 2 * 1024 * 1024) throw new IOException("系统输入摘要超过上限");
        output.write(bytes, 0, count);
      }
      return output.toString(StandardCharsets.UTF_8.name());
    } finally { timeout.cancel(false); try { pipe[1].close(); } catch (IOException ignored) { } }
  }
  private TouchDeviceRouting() { }
}
