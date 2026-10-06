package app.luoxianlv.input;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Objects;

/** 只在 shell 助手运行的触点合流内核；预检不接管设备。 */
public final class TouchEngine implements AutoCloseable {
    public interface Listener {
        boolean frame(int[] ids, float[] xy, boolean cancel);
        void noteFinished(long token, boolean success, String message);
    }

    private static boolean loaded;

    private final Listener listener;
    private volatile long handle;

    public TouchEngine(Listener listener) {
        ensureLoaded();
        this.listener = Objects.requireNonNull(listener);
    }

    /** 无线 ADB 的 app_process 没有宿主原生库搜索路径，仅接受安装信息中的实际目录。 */
    public static synchronized void initialize(String nativeLibraryDir) {
        if (nativeLibraryDir == null || nativeLibraryDir.trim().isEmpty())
            throw new IllegalArgumentException("缺少宿主原生库目录");
        try {
            String requested = new File(nativeLibraryDir).getCanonicalPath();
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object manager = thread.getDeclaredMethod("getPackageManager").invoke(null);
            Class<?> packages = Class.forName("android.content.pm.IPackageManager");
            Method applicationInfo;
            boolean longFlags;
            try {
                applicationInfo = packages.getMethod("getApplicationInfo", String.class, long.class, int.class);
                longFlags = true;
            } catch (NoSuchMethodException ignored) {
                applicationInfo = packages.getMethod("getApplicationInfo", String.class, int.class, int.class);
                longFlags = false;
            }
            ApplicationInfo owner = null;
            for (String packageName : new String[] {"app.luoxianlv", "app.luoxianlv.debug"}) {
                // APK 安装目录跨用户共享；查询未安装标记兼容仅在工作资料中安装的宿主。
                Object flags;
                if (longFlags) flags = Long.valueOf(8192);
                else flags = Integer.valueOf(8192);
                ApplicationInfo candidate = (ApplicationInfo) applicationInfo.invoke(manager, packageName, flags, 0);
                if (candidate != null && candidate.nativeLibraryDir != null &&
                        requested.equals(new File(candidate.nativeLibraryDir).getCanonicalPath())) {
                    owner = candidate;
                    break;
                }
            }
            if (owner == null) throw new SecurityException("原生库目录不属于已安装的落弦律宿主");
            if (loaded) return;
            File library = new File(requested, "libluoxianlv_input.so");
            if (library.isFile()) {
                if (!library.getCanonicalPath().equals(requested + "/libluoxianlv_input.so"))
                    throw new SecurityException("原生库路径异常");
                System.load(library.getPath());
            } else {
                String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
                if (abis.length == 0 || owner.sourceDir == null) throw new IllegalStateException("宿主缺少匹配的原生库");
                System.load(owner.sourceDir + "!/lib/" + abis[0] + "/libluoxianlv_input.so");
            }
            loaded = true;
        } catch (IOException | ReflectiveOperationException e) {
            throw new IllegalStateException("无法核对宿主原生库目录", e);
        }
    }

    private static synchronized void ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("luoxianlv_input");
            loaded = true;
        }
    }

    public static Bundle probe(String preferredDevice) {
        String stage = "native-load";
        try {
            ensureLoaded();
            stage = "device-probe";
            String[] info = nativeProbe(preferredDevice == null ? "" : preferredDevice);
            if (info == null || info.length != 14) return failure(stage, "触屏预检没有返回有效信息", null);
            if (!info[11].isEmpty()) return failure(stage, info[11], null);
            stage = "device-capabilities";
            Bundle result = new Bundle();
            result.putString("path", info[0]);
            result.putString("name", info[1]);
            result.putInt("physicalSlots", Integer.parseInt(info[2]));
            result.putInt("minX", Integer.parseInt(info[3]));
            result.putInt("maxX", Integer.parseInt(info[4]));
            result.putInt("minY", Integer.parseInt(info[5]));
            result.putInt("maxY", Integer.parseInt(info[6]));
            result.putInt("vendorId", Integer.parseInt(info[7]));
            result.putInt("productId", Integer.parseInt(info[8]));
            result.putInt("version", Integer.parseInt(info[9]));
            result.putInt("bus", Integer.parseInt(info[10]));
            result.putInt("maxAutomaticPointers", Math.min(10, 32 - result.getInt("physicalSlots")));
            result.putInt("maxPointers", 16);
            // 合流输出是独立的虚拟触摸流；与 scrcpy 一样使用设备 0，不伪装成物理 InputDevice。
            result.putInt("deviceId", MergedTouchDispatcher.DEVICE_ID);
            result.putString("deviceMatchMethod", "virtual-injection");
            result.putString("touchProtocol", info[12]);
            result.putBoolean("hardwareTrackingIds", "1".equals(info[13]));
            result.putBoolean("supported", true);
            result.putString("message", "触屏预检通过");
            result.putString("diagnosticStage", "ready");
            return result;
        } catch (RuntimeException e) {
            return failure(stage, "触屏能力检查失败", e);
        } catch (LinkageError e) {
            return failure(stage, "触控原生库加载失败", e);
        }
    }

    public synchronized Bundle prepare(String preferredDevice, int width, int height, int rotation) {
        close();
        if (width < 2 || height < 2 || width > 32768 || height > 32768 || rotation < 0 || rotation > 3)
            return failure("geometry-validation", "屏幕尺寸或方向无效", null);
        Bundle result = probe(preferredDevice);
        if (!result.getBoolean("supported")) return result;
        try {
            handle = nativePrepare(listener, result.getString("path"), result.getString("name"),
                    result.getInt("vendorId"), result.getInt("productId"), width, height, rotation);
            if (handle == 0) return failure("native-prepare", "触控引擎没有成功准备", null);
            result.putInt("width", width);
            result.putInt("height", height);
            result.putInt("rotation", rotation);
            return result;
        } catch (RuntimeException e) {
            return failure("native-prepare", "触控引擎准备失败：" + concise(e), e);
        } catch (LinkageError e) {
            return failure("native-prepare", "触控原生接口不可用", e);
        }
    }

    public boolean activate() { return nativeActivate(handle); }
    public String lastFailure() { return nativeFailure(handle); }
    /** 手指暂时按下只需等待；设备和权限错误不能当成可重试的抬手状态。 */
    public boolean activationWaiting() { return nativeActivationWaiting(handle); }

    /** 返回最后一次接管观察的安全摘要，不读取坐标或设备地址。 */
    public Bundle contactState() {
        String[] value = nativeContactState(handle);
        Bundle result = new Bundle();
        if (value == null || value.length != 6) return result;
        result.putInt("trackedSlots", Integer.parseInt(value[0]));
        result.putBoolean("touchSupported", "1".equals(value[1]));
        result.putBoolean("touchPressed", "1".equals(value[2]));
        result.putInt("touchState", Integer.parseInt(value[2]));
        result.putInt("contactReadErrno", Integer.parseInt(value[3]));
        result.putBoolean("staleContactIgnored", "1".equals(value[4]));
        result.putString("contactDecision", value[5]);
        return result;
    }

    public boolean begin(float[] xy, int durationMs, long token) {
        if (xy == null || xy.length == 0 || (xy.length & 1) != 0 || xy.length > 20 ||
                durationMs < 1 || durationMs > 120000 || token == 0) return false;
        return nativeBegin(handle, xy, durationMs, token);
    }

    public void heartbeat() { nativeHeartbeat(handle); }
    public void cancel() { nativeCommand(handle, 1); }
    public void yield() { nativeCommand(handle, 2); }
    public boolean isActive() { return nativeIsActive(handle); }

    @Override public synchronized void close() {
        long previous = handle;
        handle = 0;
        if (previous != 0) nativeClose(previous);
    }

    private static Bundle failure(String stage, String message, Throwable error) {
        Bundle result = new Bundle();
        result.putBoolean("supported", false);
        String type = safeErrorType(error);
        if (error instanceof LinkageError) message += "：" + linkageReason(error);
        result.putString("message", type.isEmpty() ? message : message + "（" + type + "）");
        result.putString("diagnosticStage", stage);
        result.putString("diagnosticType", type);
        result.putString("diagnosticMessage", message);
        return result;
    }

    static String safeErrorType(Throwable error) {
        if (error == null) return "";
        Throwable current = error;
        for (int i = 0; i < 6 && current.getCause() != null && current.getCause() != current; ++i)
            current = current.getCause();
        return current.getClass().getSimpleName();
    }

    private static String linkageReason(Throwable error) {
        String message = error.getMessage();
        if (message == null) return "原生库或接口无法使用";
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("wrong elf") || lower.contains("e_machine") || lower.contains("is for em_") ||
                lower.contains("32-bit instead") || lower.contains("64-bit instead")) return "原生库架构与助手不匹配";
        if (lower.contains("namespace") || lower.contains("permission denied") || lower.contains("not permitted"))
            return "系统限制原生库访问";
        if (lower.contains("not found") || lower.contains("couldn't find")) return "原生库或依赖未找到";
        if (lower.contains("no implementation") || lower.contains("jni_onload") || lower.contains("jni version"))
            return "原生接口版本不匹配";
        return "原生库或接口无法使用";
    }

    private static String concise(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    private static native String[] nativeProbe(String preferred);
    private static native long nativePrepare(Listener listener, String path, String name,
            int vendorId, int productId, int width, int height, int rotation);
    private static native boolean nativeActivate(long handle);
    private static native String nativeFailure(long handle);
    private static native boolean nativeActivationWaiting(long handle);
    private static native String[] nativeContactState(long handle);
    private static native boolean nativeBegin(long handle, float[] xy, int durationMs, long token);
    private static native void nativeHeartbeat(long handle);
    private static native void nativeCommand(long handle, int command);
    private static native boolean nativeIsActive(long handle);
    private static native void nativeClose(long handle);
}
