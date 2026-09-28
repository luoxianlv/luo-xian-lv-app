package app.luoxianlv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebView;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 验证实际 R8 分发包的外部行为，不依赖已裁剪的应用内部类。 */
public final class CompactRenderInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private AccessibilityNodeInfo entrance(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if ("演练场".contentEquals(node.getText() == null ? "" : node.getText()))
            return node.isClickable() ? node : node.getParent();
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = entrance(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }
    private WebView browser(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                WebView found = browser(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
    private boolean wallpaperState(Activity activity, boolean paused) throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        runOnMainSync(() -> {
            WebView web = browser(activity.getWindow().getDecorView());
            if (web == null) { result.set("static"); done.countDown(); return; }
            web.evaluateJavascript("window.wallpaperState === 'ready' && window.wallpaperPaused?.() === " + paused,
                value -> { result.set(value); done.countDown(); });
        });
        return done.await(2, TimeUnit.SECONDS) &&
            ("true".equals(result.get()) || "static".equals(result.get()));
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String pkg = getTargetContext().getPackageName();
            Activity home = startActivitySync(new Intent().setClassName(pkg, "app.luoxianlv.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            AccessibilityNodeInfo entry = null;
            long until = SystemClock.uptimeMillis() + 30000;
            while (entry == null && SystemClock.uptimeMillis() < until) {
                entry = entrance(getUiAutomation().getRootInActiveWindow());
                Thread.sleep(100);
            }
            require(entry != null, "Home entrance missing or privacy dialog blocked internal build");
            Thread.sleep(1000);
            until = SystemClock.uptimeMillis() + 75000;
            while (!wallpaperState(home, true) && SystemClock.uptimeMillis() < until) Thread.sleep(200);
            require(wallpaperState(home, true), "Home preload not suspended");
            ActivityMonitor monitor = addMonitor("app.luoxianlv.ui.practice.PracticeActivity", null, false);
            require(entry.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Entrance click failed");
            Activity stage = waitForMonitorWithTimeout(monitor, 15000);
            require(stage != null, "Stage did not open");
            until = SystemClock.uptimeMillis() + 75000;
            while (!wallpaperState(stage, false) && SystemClock.uptimeMillis() < until) Thread.sleep(200);
            require(wallpaperState(stage, false), "Stage renderer not resumed");
            Thread.sleep(5000);
            runOnMainSync(() -> require(stage.getWindow().getDecorView().getWidth() >
                stage.getWindow().getDecorView().getHeight(), "Stage not landscape"));
            runOnMainSync(() -> stage.startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            Thread.sleep(1000);
            require(wallpaperState(stage, true), "Background renderer still active");
            runOnMainSync(() -> getTargetContext().startActivity(new Intent()
                .setClassName(pkg, "app.luoxianlv.ui.practice.PracticeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
            Thread.sleep(1500);
            require(!stage.isDestroyed() && wallpaperState(stage, false), "Stage did not survive resume");
            getUiAutomation().performGlobalAction(1);
            Thread.sleep(3000);
            runOnMainSync(() -> require(home.getWindow().getDecorView().getHeight() >
                home.getWindow().getDecorView().getWidth(), "Home did not return to portrait"));
            removeMonitor(monitor);
            result.putString("stream", "Compact distribution: home, preload pause, landscape entry, background pause, resume and portrait exit passed.\n");
            finish(-1, result);
        } catch (Throwable error) {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            result.putString("stream", trace.toString());
            finish(0, result);
        }
    }
}
