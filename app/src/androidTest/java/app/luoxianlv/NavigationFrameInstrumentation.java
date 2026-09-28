package app.luoxianlv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.FrameMetrics;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Window;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** 在真实导航上分别采集点击、拖动的窗口帧耗时；只依赖 Framework，可用于 R8 分发包。 */
public final class NavigationFrameInstrumentation extends Instrumentation {
    private final Map<String, List<long[]>> samples = new LinkedHashMap<>();
    private volatile String phase;
    private int lostCallbacks;
    private String label;
    private boolean stress;
    private boolean refresh;
    private Activity activity;
    private boolean listening;
    private final Window.OnFrameMetricsAvailableListener listener = (window, frame, dropped) -> {
        String current = phase;
        if (current == null || frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME) != 0) return;
        synchronized (samples) {
            lostCallbacks += dropped;
            samples.computeIfAbsent(current, key -> new ArrayList<>()).add(new long[] {
                frame.getMetric(FrameMetrics.TOTAL_DURATION),
                frame.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                frame.getMetric(FrameMetrics.DRAW_DURATION),
                android.os.Build.VERSION.SDK_INT >= 31 ? frame.getMetric(FrameMetrics.DEADLINE) : 16666667,
            });
        }
    };

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        label = arguments == null ? "navigation" : arguments.getString("label", "navigation");
        stress = arguments != null && "true".equals(arguments.getString("stress"));
        refresh = arguments != null && "true".equals(arguments.getString("refresh"));
        start();
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String label) {
        return find(node, label, false);
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String label, boolean clickable) {
        if (node == null) return null;
        if (node.isVisibleToUser() && (label.contentEquals(node.getText() == null ? "" : node.getText()) ||
            label.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()))) {
            AccessibilityNodeInfo candidate = node;
            while (clickable && candidate != null && !candidate.isClickable()) candidate = candidate.getParent();
            if (candidate != null) return candidate;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = find(node.getChild(i), label, clickable);
            if (found != null) return found;
        }
        return null;
    }

    private void awaitPage(String label) throws Exception {
        long until = SystemClock.uptimeMillis() + 10000;
        while (find(getUiAutomation().getRootInActiveWindow(), label) == null) {
            if (SystemClock.uptimeMillis() >= until) throw new AssertionError("页面未显示：" + label);
            Thread.sleep(100);
        }
    }

    private void click(String label) {
        AccessibilityNodeInfo node = find(getUiAutomation().getRootInActiveWindow(), label, true);
        if (node == null) throw new AssertionError("无法点击：" + label);
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        long down = SystemClock.uptimeMillis();
        for (int action : new int[] { MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP }) {
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.centerX(), bounds.centerY(), 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try { getUiAutomation().injectInputEvent(event, true); }
            finally { event.recycle(); }
        }
    }

    private void swipe(boolean forward) {
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        long down = SystemClock.uptimeMillis();
        for (int step = 0; step <= 20; step++) {
            long time = down + step * 16L;
            SystemClock.sleep(Math.max(0, time - SystemClock.uptimeMillis()));
            float fraction = step / 20f;
            float x = width * (forward ? .85f - .7f * fraction : .15f + .7f * fraction);
            int action = step == 0 ? MotionEvent.ACTION_DOWN : step == 20 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, height * .45f, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try {
                if (!getUiAutomation().injectInputEvent(event, true)) throw new AssertionError("拖动注入失败");
            } finally { event.recycle(); }
        }
    }

    private void transition(String category, String target, String expected, Boolean forward) throws Exception {
        phase = category;
        if (forward == null) click(target); else swipe(forward);
        Thread.sleep(1000);
        phase = null;
        awaitPage(expected);
    }

    private double percentile(List<long[]> frames, int field, double quantile) {
        ArrayList<Long> values = new ArrayList<>();
        for (long[] frame : frames) values.add(frame[field]);
        Collections.sort(values);
        return values.get(Math.min(values.size() - 1, (int) Math.ceil(values.size() * quantile) - 1)) / 1e6;
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        HandlerThread collector = new HandlerThread("navigation-frames");
        collector.start();
        boolean passed = false;
        android.content.SharedPreferences library = app.luoxianlv.data.Kv.INSTANCE.of(getTargetContext(), "song_library");
        String previousSongs = library.getString("songs", "[]");
        // 恢复上次测试进程被外部终止时遗留的专用测试曲目。
        try {
            org.json.JSONArray clean = new org.json.JSONArray(), saved = new org.json.JSONArray(previousSongs);
            for (int i = 0; i < saved.length(); i++) {
                JSONObject item = saved.getJSONObject(i);
                if (!item.optString("id").startsWith("navigation-stress-")) clean.put(item);
            }
            previousSongs = clean.toString();
        } catch (org.json.JSONException ignored) { /* 原数据损坏时仍原样恢复，不覆盖用户记录。 */ }
        String previousSelected = library.getString("selected", null);
        try {
            if (stress) {
                org.json.JSONArray songs = new org.json.JSONArray(previousSongs);
                String notation = "1:0.25 2:0.25 ".repeat(3000);
                for (int i = 0; i < 60; i++) songs.put(new JSONObject()
                    .put("id", "navigation-stress-" + i).put("title", "性能回归-" + i)
                    .put("score", notation).put("bpm", 120).put("source", "简谱"));
                library.edit().putString("songs", songs.toString()).commit();
            }
            // 精确测量单独在未裁剪测试包执行；导航回归使用与正式版相同的 R8 优化。
            String scoreReport = "解析和队列性能见独立测试";
            activity = startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(),
                "app.luoxianlv.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            awaitPage("演练场");
            Thread.sleep(3000);
            runOnMainSync(() -> activity.getWindow().addOnFrameMetricsAvailableListener(listener, new Handler(collector.getLooper())));
            listening = true;
            transition("首次点击", "曲库", "导入谱子", null);
            transition("首次点击", "我的", "演练场", null);
            for (int i = 0; i < 4; i++) {
                transition("点击我的曲库", "曲库", "导入谱子", null);
                transition("点击曲库发现", "发现", "搜索谱子", null);
                transition("点击发现设置", "设置", "网站账号", null);
                transition("点击发现设置", "发现", "搜索谱子", null);
                transition("点击曲库发现", "曲库", "导入谱子", null);
                transition("点击我的曲库", "我的", "演练场", null);
            }
            for (int i = 0; i < 4; i++) {
                transition("滑动我的曲库", null, "导入谱子", true);
                transition("滑动曲库发现", null, "搜索谱子", true);
                transition("滑动发现设置", null, "网站账号", true);
                transition("滑动发现设置", null, "搜索谱子", false);
                transition("滑动曲库发现", null, "导入谱子", false);
                transition("滑动我的曲库", null, "演练场", false);
            }
            // 设置直接返回每个导航；刷新事件模拟下载完成，必须保持可切页。
            for (int i = 0; i < 6; i++) {
                transition("设置返回曲库", "设置", "网站账号", null);
                // 内部事件接口仅在未裁剪包测试，R8 可将单例改成静态调用。
                if (refresh) runOnMainSync(() -> app.luoxianlv.ui.AppEvents.INSTANCE.notifyLibraryChanged());
                transition("设置返回曲库", "曲库", "导入谱子", null);
                transition("设置返回发现", "设置", "网站账号", null);
                transition("设置返回发现", "发现", "搜索谱子", null);
            }
            for (int i = 0; i < 3; i++) {
                transition("跨页点击", "设置", "网站账号", null);
                transition("跨页点击", "我的", "演练场", null);
            }
            runOnMainSync(() -> activity.getWindow().removeOnFrameMetricsAvailableListener(listener));
            listening = false;
            JSONObject report = new JSONObject().put("label", label).put("lostCallbacks", lostCallbacks)
                .put("score", scoreReport).put("stressSongs", stress ? 60 : 0).put("refresh", refresh);
            synchronized (samples) {
                for (Map.Entry<String, List<long[]>> entry : samples.entrySet()) {
                    List<long[]> frames = entry.getValue();
                    int missed = 0;
                    for (long[] frame : frames) if (frame[0] > frame[3]) missed++;
                    report.put(entry.getKey(), new JSONObject().put("frames", frames.size())
                        .put("overDeadline", missed).put("totalP50Ms", percentile(frames, 0, .5))
                        .put("totalP95Ms", percentile(frames, 0, .95))
                        .put("layoutP95Ms", percentile(frames, 1, .95))
                        .put("drawP95Ms", percentile(frames, 2, .95)));
                }
            }
            File output = new File(getTargetContext().getExternalFilesDir(null), "diagnostics/navigation-frames.json");
            output.getParentFile().mkdirs();
            Files.write(output.toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
            result.putString("stream", report.toString(2) + "\n");
            passed = true;
        } catch (Throwable error) {
            try {
                android.graphics.Bitmap shot = getUiAutomation().takeScreenshot();
                if (shot != null) {
                    File image = new File(getTargetContext().getExternalFilesDir(null), "diagnostics/navigation-failure.png");
                    image.getParentFile().mkdirs();
                    try (java.io.FileOutputStream stream = new java.io.FileOutputStream(image)) {
                        shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream);
                    } finally { shot.recycle(); }
                }
            } catch (Exception ignored) { /* 截图失败不覆盖原始导航错误。 */ }
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            result.putString("stream", trace.toString());
        } finally {
            phase = null;
            if (listening) runOnMainSync(() -> activity.getWindow().removeOnFrameMetricsAvailableListener(listener));
            collector.quitSafely();
            if (stress) {
                library.edit().putString("songs", previousSongs).commit();
                if (previousSelected == null) library.edit().remove("selected").commit();
                else library.edit().putString("selected", previousSelected).commit();
            }
        }
        finish(passed ? -1 : 0, result);
    }
}
