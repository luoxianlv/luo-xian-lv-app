package app.luoxianlv.input;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
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

    private record Inventory(ArrayList<Bundle> candidates, Bundle diagnostics, int selected,
                             String error, String selectionReason, String identity) {}

    private static Inventory inventory(String[][] rows) {
        if (rows == null || rows.length < 1 || rows.length > 129 || rows[0] == null
                || rows[0].length != 11 || !"touch-candidates-v1".equals(rows[0][0]))
            throw new IllegalStateException("触屏预检没有返回有效信息");
        String[] metadata = rows[0];
        ArrayList<Bundle> candidates = new ArrayList<>();
        StringBuilder details = new StringBuilder();
        for (int i = 1; i < rows.length; ++i) {
            String[] row = rows[i];
            if (row == null || row.length != 34) throw new IllegalStateException("触屏候选信息不完整");
            Bundle candidate = new Bundle();
            candidate.putString("path", row[0]); candidate.putString("name", row[1]);
            candidate.putInt("physicalSlots", Integer.parseInt(row[2]));
            candidate.putInt("minX", Integer.parseInt(row[3])); candidate.putInt("maxX", Integer.parseInt(row[4]));
            candidate.putInt("minY", Integer.parseInt(row[5])); candidate.putInt("maxY", Integer.parseInt(row[6]));
            candidate.putInt("vendorId", Integer.parseInt(row[7])); candidate.putInt("productId", Integer.parseInt(row[8]));
            candidate.putInt("version", Integer.parseInt(row[9])); candidate.putInt("bus", Integer.parseInt(row[10]));
            candidate.putString("touchProtocol", row[12]);
            candidate.putBoolean("hardwareTrackingIds", "1".equals(row[13]));
            candidate.putString("identityFingerprint", row[14]); candidate.putString("rdev", row[15]);
            candidate.putString("propertyBits", row[16]); candidate.putString("keyBits", row[17]);
            candidate.putString("absBits", row[18]); candidate.putBoolean("touchSupported", "1".equals(row[19]));
            candidate.putString("sysfsTopology", row[20]); candidate.putString("sysfsHash", row[21]);
            candidate.putString("driver", row[22]); candidate.putString("physHash", row[23]); candidate.putString("uniqHash", row[24]);
            candidate.putInt("errno", Integer.parseInt(row[25])); candidate.putInt("topologyErrno", Integer.parseInt(row[26]));
            candidate.putInt("slotMin", Integer.parseInt(row[27])); candidate.putInt("slotMax", Integer.parseInt(row[28]));
            candidate.putInt("trackingMin", Integer.parseInt(row[29])); candidate.putInt("trackingMax", Integer.parseInt(row[30]));
            candidate.putString("duplicateOf", row[31]);
            candidate.putString("canonicalPath", row[31].isEmpty() ? row[0] : row[31]);
            candidate.putBoolean("capabilitiesKnown", "1".equals(row[32])); candidate.putBoolean("uncertain", "1".equals(row[33]));
            long properties = row[16].isEmpty() ? 0 : Long.parseUnsignedLong(row[16], 16);
            candidate.putBoolean("direct", (properties & 2) != 0);
            candidate.putBoolean("pointer", (properties & 1) != 0);
            candidate.putBoolean("semiMt", (properties & 8) != 0);
            boolean compatible = row[11].isEmpty() && !row[15].isEmpty() && candidate.getBoolean("capabilitiesKnown");
            candidate.putBoolean("compatible", compatible);
            candidate.putBoolean("eligible", compatible && row[31].isEmpty());
            String rejection = row[31].isEmpty() ? row[11] : "duplicate-rdev";
            candidate.putString("rejectReason", rejection);
            String summary = row[0] + " name=" + bounded(row[1], 160)
                    + " rdev=" + row[15] + " protocol=" + row[12] + " slots=" + row[2]
                    + " properties=" + row[16] + " topology=" + row[20] + " driver=" + row[22]
                    + " reason=" + (rejection.isEmpty() ? "compatible" : rejection) + " errno=" + row[25];
            candidate.putString("capabilitySummary", summary);
            if (details.length() != 0) details.append('\n');
            details.append(summary);
            candidates.add(candidate);
        }
        // 路由可能只登记了另一个同 rdev 别名；去重不应丢失可核对的精确路径。
        for (Bundle canonical : candidates) {
            if (!canonical.getBoolean("eligible")) continue;
            String path = canonical.getString("path");
            ArrayList<String> aliases = new ArrayList<>();
            for (Bundle value : candidates)
                if (value.getBoolean("compatible") && path.equals(value.getString("canonicalPath")))
                    aliases.add(value.getString("path"));
            canonical.putStringArrayList("aliases", aliases);
        }
        Bundle diagnostics = new Bundle();
        diagnostics.putParcelableArrayList("deviceCandidates", candidates);
        diagnostics.putInt("candidateCount", Integer.parseInt(metadata[7]));
        diagnostics.putInt("candidateNodeCount", Integer.parseInt(metadata[3]));
        diagnostics.putInt("candidateUnknownCount", Integer.parseInt(metadata[8]));
        diagnostics.putInt("candidateDuplicateCount", Integer.parseInt(metadata[9]));
        diagnostics.putBoolean("candidateScanTruncated", "1".equals(metadata[2]));
        diagnostics.putString("candidateScanError", metadata[1]);
        diagnostics.putString("nativeSelectionDecision", metadata[5]);
        diagnostics.putString("deviceScanIdentity", metadata[10]);
        diagnostics.putString("details", details.toString());
        return new Inventory(candidates, diagnostics, Integer.parseInt(metadata[4]), metadata[1], metadata[6], metadata[10]);
    }

    private static String bounded(String value, int maximum) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), maximum));
    }

    private static Bundle candidate(Inventory inventory, String path) {
        for (Bundle value : inventory.candidates)
            if (path.equals(value.getString("path")) && value.getBoolean("compatible")) return value;
        return null;
    }

    private static Bundle failedProbe(String stage, String message, Throwable error, Bundle diagnostics) {
        Bundle result = new Bundle(diagnostics);
        result.putAll(failure(stage, message, error));
        return result;
    }

    public static Bundle probe(String preferredDevice) {
        String stage = "native-load";
        Bundle diagnostics = new Bundle();
        try {
            ensureLoaded();
            stage = "device-probe";
            Inventory initial = inventory(nativeProbe(""));
            diagnostics.putAll(initial.diagnostics);
            if (!initial.error.isEmpty()) return failedProbe(stage, initial.error, null, diagnostics);
            if (diagnostics.getBoolean("candidateScanTruncated"))
                return failedProbe(stage, "触屏设备列表超过安全范围，不能确认输入来源", null, diagnostics);
            stage = "device-routing";
            Bundle selection = TouchDeviceRouting.resolve(initial.candidates);
            if (selection == null) return failedProbe(stage, "系统输入路由没有返回有效信息", null, diagnostics);
            String selectedPath = selection.getString("selectedPath", "");
            diagnostics.putString("deviceSelectionMethod", bounded(selection.getString("selectionMethod", ""), 80));
            diagnostics.putString("deviceSelectionReason", bounded(selection.getString("selectionReason", ""), 2000));
            diagnostics.putString("routingDiagnostics", bounded(selection.getString("routingDiagnostics", ""), 32768));
            if (selectedPath.isEmpty()) {
                String reason = selection.getString("selectionReason", "无法确认当前主屏触屏，请重新连接");
                return failedProbe(stage, reason, null, diagnostics);
            }
            // 首选只能缩小已经核对的系统路由，不能覆盖真实歧义或用名称猜设备。
            if (preferredDevice != null && !preferredDevice.isEmpty() && !preferredDevice.equals(selectedPath))
                return failedProbe(stage, "首选触屏与已确认的主屏输入来源不一致", null, diagnostics);
            Bundle before = candidate(initial, selectedPath);
            if (before == null) return failedProbe(stage, "系统路由指向的触屏不在兼容候选中", null, diagnostics);
            stage = "device-revalidate";
            Inventory current = inventory(nativeProbe(selectedPath));
            if (!current.error.isEmpty()) return failedProbe(stage, current.error, null, diagnostics);
            Bundle after = current.selected < 0 || current.selected >= current.candidates.size()
                    ? null : current.candidates.get(current.selected);
            if (after == null || !after.getBoolean("compatible") || !selectedPath.equals(after.getString("path"))
                    || !initial.identity.equals(current.identity)
                    || !before.getString("identityFingerprint", "").equals(after.getString("identityFingerprint", "")))
                return failedProbe(stage, "输入设备身份或能力发生变化，请重新预检", null, diagnostics);
            Bundle result = new Bundle(diagnostics);
            result.putAll(after);
            result.putBoolean("eligible", true);
            result.putString("rejectReason", "");
            result.putString("deviceIdentity", after.getString("identityFingerprint"));
            result.putInt("maxAutomaticPointers", Math.min(10, 32 - result.getInt("physicalSlots")));
            result.putInt("maxPointers", 16);
            // 合流输出是独立的虚拟触摸流；与 scrcpy 一样使用设备 0，不伪装成物理 InputDevice。
            result.putInt("deviceId", MergedTouchDispatcher.DEVICE_ID);
            result.putString("deviceMatchMethod", "virtual-injection");
            result.putBoolean("supported", true);
            result.putString("message", "触屏预检通过");
            result.putString("diagnosticStage", "ready");
            return result;
        } catch (RuntimeException e) {
            return failedProbe(stage, "触屏能力检查失败", e, diagnostics);
        } catch (LinkageError e) {
            return failedProbe(stage, stage.equals("native-load") ? "触控原生库加载失败" : "触屏接口不兼容", e, diagnostics);
        }
    }

    public synchronized Bundle prepare(String preferredDevice, int width, int height, int rotation) {
        close();
        if (width < 2 || height < 2 || width > 32768 || height > 32768 || rotation < 0 || rotation > 3)
            return failure("geometry-validation", "屏幕尺寸或方向无效", null);
        Bundle result = probe(preferredDevice);
        if (!result.getBoolean("supported")) return result;
        try {
            handle = nativePrepare(listener, result.getString("path"), result.getString("deviceIdentity"),
                    result.getString("deviceScanIdentity"), width, height, rotation);
            if (handle == 0) return failedProbe("native-prepare", "触控引擎没有成功准备", null, result);
            result.putInt("width", width);
            result.putInt("height", height);
            result.putInt("rotation", rotation);
            return result;
        } catch (RuntimeException e) {
            return failedProbe("native-prepare", "触控引擎准备失败：" + concise(e), e, result);
        } catch (LinkageError e) {
            return failedProbe("native-prepare", "触控原生接口不可用", e, result);
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
        if (error != null) android.util.Log.w("触控共存", "触屏预检异常：阶段=" + stage, error);
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

    private static native String[][] nativeProbe(String preferred);
    private static native long nativePrepare(Listener listener, String path, String identity,
            String scanIdentity, int width, int height, int rotation);
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
