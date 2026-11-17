package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** 反射调用业务 Debug UI 探针；测试 APK 不复制 Kotlin/Compose，也不替换真实多人会话。 */
public final class EnsembleUiInstrumentation extends Instrumentation {
  private Bundle options;

  @Override public void onCreate(Bundle arguments) {
    options = arguments == null ? new Bundle() : arguments;
    super.onCreate(arguments);
    start();
  }

  @Override public void onStart() {
    Bundle report = new Bundle();
    boolean passed = false;
    View[] mounted = {null};
    Method close = null;
    try {
      Activity activity = startActivityBounded(
          new Intent().setClassName(getTargetContext(), "app.luoxianlv.MainActivity")
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      long until = SystemClock.elapsedRealtime() + 30_000;
      while (true) {
        try { if (Bootstrap.source().prepared != null) break; }
        catch (IllegalStateException waiting) { }
        if (SystemClock.elapsedRealtime() > until) throw new AssertionError("业务加载超时");
        SystemClock.sleep(20);
      }
      Context[] context = {null};
      onMain(() -> context[0] = Bootstrap.source().prepared.context(activity));
      Class<?> probe = Class.forName("app.luoxianlv.ensemble.EnsembleUiProbe", true,
          Bootstrap.source().prepared.classLoader());
      Method render = probe.getMethod("render", Context.class, Activity.class, String.class, boolean.class);
      Method verify = probe.getMethod("verify", View.class);
      Method ready = probe.getMethod("ready", View.class);
      Method verifyInput = probe.getMethod("verifyInput", View.class);
      Method advance = probe.getMethod("advance", View.class);
      Method finish = probe.getMethod("finish", View.class);
      close = probe.getMethod("close", View.class);
      Method cleanup = close;
      String requested = options.getString("scenario", "all");
      String requestedTheme = options.getString("theme", "both");
      boolean screenshots = "true".equals(options.getString("screenshots", "false"));
      long holdMs = Long.parseLong(options.getString("holdMs", "0"));
      if (holdMs < 0 || holdMs > 60_000) throw new AssertionError("holdMs 必须在 0..60000");
      if (!requestedTheme.equals("light") && !requestedTheme.equals("dark") && !requestedTheme.equals("both"))
        throw new AssertionError("theme 仅支持 light/dark/both");
      String[] scenarios = requested.equals("all")
          ? new String[] {"signedOut", "unsupported", "connecting", "unauthorized", "serviceUnavailable", "lobby", "owner", "member", "disconnected", "preparing", "paused", "proposed", "playing", "finished", "finishedReady", "rejoin", "emptyRoom", "scheduledStart", "pendingRejoin", "scorePicker"}
          : new String[] {requested};
      JSONArray results = new JSONArray();
      File output = new File(getTargetContext().getFilesDir(), "ensemble-ui-probe");
      if (!output.isDirectory() && !output.mkdirs()) throw new AssertionError("无法创建界面验证输出目录");
      for (String scenario : scenarios) {
        for (boolean dark : new boolean[] {false, true}) {
          String theme = dark ? "dark" : "light";
          if (!requestedTheme.equals("both") && !requestedTheme.equals(theme)) continue;
          try {
            Bundle progress = new Bundle();
            progress.putString("stream", "检查：" + scenario + " / " + theme + "\n");
            sendStatus(0, progress);
            onMain(() -> mounted[0] = (View) render.invoke(null, context[0], activity, scenario, dark));
            waitForUiFrames(350);
            long renderDeadline = SystemClock.elapsedRealtime() + 10_000;
            while (true) {
              AtomicBoolean rendered = new AtomicBoolean();
              onMain(() -> rendered.set((Boolean) ready.invoke(null, mounted[0])));
              if (rendered.get()) break;
              if (SystemClock.elapsedRealtime() > renderDeadline) throw new AssertionError("界面数据加载超时");
              SystemClock.sleep(100);
            }
            onMain(() -> verify.invoke(null, mounted[0]));
            waitForUiFrames(100);
            onMain(() -> verifyInput.invoke(null, mounted[0]));
            if (screenshots) screenshot(new File(output, scenario + "-" + theme + ".png"));
            int frames = 0;
            while (true) {
              AtomicBoolean more = new AtomicBoolean();
              onMain(() -> more.set((Boolean) advance.invoke(null, mounted[0])));
              if (!more.get()) break;
              if (++frames > 12) throw new AssertionError("界面滚动没有到达底部");
              waitForUiFrames(180);
              onMain(() -> verifyInput.invoke(null, mounted[0]));
              if (screenshots) screenshot(new File(output, scenario + "-" + theme + "-" + frames + ".png"));
            }
            AtomicReference<String> result = new AtomicReference<>();
            onMain(() -> result.set((String) finish.invoke(null, mounted[0])));
            JSONObject snapshot = new JSONObject(result.get());
            results.put(snapshot);
            Files.write(new File(output, scenario + "-" + theme + ".json").toPath(),
                snapshot.toString(2).getBytes(StandardCharsets.UTF_8));
            Bundle status = new Bundle();
            status.putString("stream", "通过：" + scenario + " / " + theme + "\n");
            sendStatus(0, status);
            if (holdMs > 0) SystemClock.sleep(holdMs);
          } finally {
            if (mounted[0] != null) onMain(() -> cleanup.invoke(null, mounted[0]));
            mounted[0] = null;
          }
        }
      }
      JSONObject summary = new JSONObject().put("passed", true).put("snapshots", results);
      Files.write(new File(output, "report.json").toPath(), summary.toString(2).getBytes(StandardCharsets.UTF_8));
      report.putString("stream", "通过：" + results.length() + " 个明暗主题多人合奏界面快照与注入动作。\n");
      passed = true;
    } catch (Throwable failure) {
      report.putString("stream", android.util.Log.getStackTraceString(failure));
    } finally {
      if (mounted[0] != null && close != null) {
        Method cleanup = close;
        try { onMain(() -> cleanup.invoke(null, mounted[0])); } catch (Throwable ignored) { }
      }
    }
    finish(passed ? -1 : 0, report);
  }

  private interface MainAction { void run() throws Exception; }

  /** 系统的同步启动本身也会等待首帧，不能让它绕过测试的等待截止时间。 */
  private Activity startActivityBounded(Intent intent) {
    Bundle status = new Bundle();
    status.putString("stream", "正在启动多人界面验证\n");
    sendStatus(0, status);
    FutureTask<Activity> startup = new FutureTask<>(() -> startActivitySync(intent));
    Thread worker = new Thread(startup, "ensemble-ui-start");
    worker.setDaemon(true);
    worker.start();
    try {
      return startup.get(30, TimeUnit.SECONDS);
    } catch (TimeoutException timeout) {
      startup.cancel(true);
      throw new AssertionError("多人界面启动超时（30 秒）", timeout);
    } catch (InterruptedException interrupted) {
      startup.cancel(true);
      Thread.currentThread().interrupt();
      throw new AssertionError("多人界面启动等待被中断", interrupted);
    } catch (ExecutionException failure) {
      throw new AssertionError("多人界面启动失败", failure.getCause());
    }
  }

  /** 连续动画不要求主队列空闲；只给组合/布局留出短帧，并使用有截止时间的主线程屏障。 */
  private void waitForUiFrames(long frameMs) {
    onMain(() -> {});
    SystemClock.sleep(frameMs);
    onMain(() -> {});
  }

  private void screenshot(File output) throws Exception {
    Bitmap bitmap = getUiAutomation().takeScreenshot();
    if (bitmap == null) throw new AssertionError("屏幕截图失败");
    try (FileOutputStream stream = new FileOutputStream(output)) {
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new AssertionError("保存截图失败");
    } finally { bitmap.recycle(); }
  }

  private void onMain(MainAction action) {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch completed = new CountDownLatch(1);
    Handler main = new Handler(Looper.getMainLooper());
    Runnable task = () -> {
      try { action.run(); } catch (Throwable error) { failure.set(error); }
      finally { completed.countDown(); }
    };
    if (!main.post(task)) throw new AssertionError("多人界面主线程已停止");
    try {
      if (!completed.await(5, TimeUnit.SECONDS)) {
        main.removeCallbacks(task);
        throw new AssertionError("多人界面主线程检查超时（5 秒）");
      }
    } catch (InterruptedException interrupted) {
      main.removeCallbacks(task);
      Thread.currentThread().interrupt();
      throw new AssertionError("多人界面主线程等待被中断", interrupted);
    }
    if (failure.get() != null) throw new AssertionError("多人界面主线程检查失败", failure.get());
  }
}
