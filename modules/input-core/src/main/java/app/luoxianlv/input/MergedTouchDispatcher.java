package app.luoxianlv.input;

import android.graphics.Point;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeMap;
import java.util.Arrays;

/** 将合流后的触点快照转为完整的 Android 多点事件序列。仅由内核工作线程调用。 */
public final class MergedTouchDispatcher {
    private static final String TAG = "触控共存";
    private final int deviceId, width, height, rotation;
    private final Object inputManager;
    private final Method inject;
    private final Display display;
    private final String descriptor;
    private final TreeMap<Integer, float[]> active = new TreeMap<>();
    private final PointerIds pointerIds = new PointerIds();
    private final Point size = new Point();
    private long downTime;

    public MergedTouchDispatcher(int deviceId, int width, int height, int rotation) {
        if (deviceId < 0 || width < 2 || height < 2 || rotation < 0 || rotation > 3)
            throw new IllegalArgumentException("输入设备或屏幕参数无效");
        this.deviceId = deviceId;
        this.width = width;
        this.height = height;
        this.rotation = rotation;
        InputDevice device = InputDevice.getDevice(deviceId);
        if (device == null || !device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) ||
                !TouchEngine.targetsMainDisplay(device))
            throw new IllegalStateException("目标触屏已断开");
        descriptor = device.getDescriptor();
        try {
            Class<?> managerClass;
            try { managerClass = Class.forName("android.hardware.input.InputManagerGlobal"); }
            catch (ClassNotFoundException ignored) { managerClass = Class.forName("android.hardware.input.InputManager"); }
            Method instance = managerClass.getDeclaredMethod("getInstance");
            instance.setAccessible(true);
            inputManager = instance.invoke(null);
            inject = managerClass.getMethod("injectInputEvent", InputEvent.class, int.class);
            Class<?> displays = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object global = displays.getDeclaredMethod("getInstance").invoke(null);
            display = (Display) displays.getMethod("getRealDisplay", int.class).invoke(global, Display.DEFAULT_DISPLAY);
            if (display == null) throw new IllegalStateException("无法确认主屏幕");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("系统不支持触控注入", e);
        }
        if (!geometryMatches()) throw new IllegalStateException("屏幕方向或尺寸已变化，请重新准备触控");
    }

    public synchronized boolean frame(int[] ids, float[] xy, boolean cancel) {
        if (cancel) return cancelAll();
        if (!geometryMatches() || !valid(ids, xy)) { cancelAll(); return false; }
        TreeMap<Integer, float[]> desired = new TreeMap<>();
        for (int i = 0; i < ids.length; ++i) desired.put(ids[i], new float[] {xy[i * 2], xy[i * 2 + 1]});
        for (Map.Entry<Integer, float[]> item : desired.entrySet())
            if (active.containsKey(item.getKey())) active.put(item.getKey(), item.getValue());

        Integer[] previous = active.keySet().toArray(new Integer[0]);
        for (int id : previous) {
            if (desired.containsKey(id)) continue;
            int index = active.headMap(id).size();
            int action = active.size() == 1 ? MotionEvent.ACTION_UP :
                    MotionEvent.ACTION_POINTER_UP | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            if (!send(action)) { cancelAll(); return false; }
            active.remove(id);
            pointerIds.release(id);
        }
        for (Map.Entry<Integer, float[]> item : desired.entrySet()) {
            int id = item.getKey();
            if (active.containsKey(id)) continue;
            boolean first = active.isEmpty();
            pointerIds.allocate(id);
            active.put(id, item.getValue());
            if (first) downTime = SystemClock.uptimeMillis();
            int action = first ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_POINTER_DOWN |
                    (active.headMap(id).size() << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            if (!send(action)) { cancelAll(); return false; }
        }
        if (!active.isEmpty() && !send(MotionEvent.ACTION_MOVE)) { cancelAll(); return false; }
        return true;
    }

    public synchronized void reset() { cancelAll(); }

    @SuppressWarnings("deprecation")
    private boolean geometryMatches() {
        InputDevice device = InputDevice.getDevice(deviceId);
        if (device == null || !descriptor.equals(device.getDescriptor()) ||
                !TouchEngine.targetsMainDisplay(device) || !display.isValid() ||
                display.getRotation() != rotation) return false;
        display.getRealSize(size);
        return size.x == width && size.y == height;
    }

    private boolean valid(int[] ids, float[] xy) {
        if (ids == null || xy == null || ids.length > 16 || xy.length != ids.length * 2) return false;
        long mask = 0;
        for (int i = 0; i < ids.length; ++i) {
            int id = ids[i];
            float x = xy[i * 2], y = xy[i * 2 + 1];
            if (id < 0 || id >= 32 || (mask & (1L << id)) != 0 || !Float.isFinite(x) || !Float.isFinite(y) ||
                    x < 0 || y < 0 || x > width - 1 || y > height - 1) return false;
            mask |= 1L << id;
        }
        return true;
    }

    private boolean cancelAll() {
        boolean accepted = active.isEmpty() || send(MotionEvent.ACTION_CANCEL);
        active.clear();
        pointerIds.clear();
        downTime = 0;
        return accepted;
    }

    private boolean send(int action) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[active.size()];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[active.size()];
        int index = 0;
        for (Map.Entry<Integer, float[]> item : active.entrySet()) {
            MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
            property.id = pointerIds.get(item.getKey());
            property.toolType = MotionEvent.TOOL_TYPE_FINGER;
            properties[index] = property;
            MotionEvent.PointerCoords coordinate = new MotionEvent.PointerCoords();
            coordinate.x = item.getValue()[0];
            coordinate.y = item.getValue()[1];
            coordinate.pressure = 1;
            coordinate.size = 0.05f;
            coordinates[index++] = coordinate;
        }
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, active.size(),
                properties, coordinates, 0, 0, 1, 1, deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            // WAIT_FOR_RESULT 返回输入系统的接受结果；独立看门狗处理 Binder 长时间停顿。
            return Boolean.TRUE.equals(inject.invoke(inputManager, event, 1));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "系统未接受合流触摸事件：" + e.getClass().getSimpleName());
            return false;
        } finally { event.recycle(); }
    }

    /** 内核槽位只标识合流来源；Android 指针按首次按下分配低编号，并保持至抬起。 */
    static final class PointerIds {
        private final int[] mapped = new int[32];
        private int used;

        PointerIds() { clear(); }

        int allocate(int logicalId) {
            checkLogical(logicalId);
            if (mapped[logicalId] >= 0) return mapped[logicalId];
            for (int id = 0; id < 16; ++id) {
                if ((used & (1 << id)) == 0) {
                    used |= 1 << id;
                    return mapped[logicalId] = id;
                }
            }
            throw new IllegalStateException("同时触点超过系统容量");
        }

        int get(int logicalId) {
            checkLogical(logicalId);
            if (mapped[logicalId] < 0) throw new IllegalStateException("触点尚未分配指针编号");
            return mapped[logicalId];
        }

        void release(int logicalId) {
            checkLogical(logicalId);
            int id = mapped[logicalId];
            if (id >= 0) used &= ~(1 << id);
            mapped[logicalId] = -1;
        }

        void clear() { Arrays.fill(mapped, -1); used = 0; }

        private static void checkLogical(int logicalId) {
            if (logicalId < 0 || logicalId >= 32) throw new IllegalArgumentException("合流触点编号越界");
        }
    }
}
