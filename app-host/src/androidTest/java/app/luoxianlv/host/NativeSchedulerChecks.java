package app.luoxianlv.host;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import app.luoxianlv.hot.contract.PracticeBridge;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** 仅 Debug 本机：观察普通入口的真实调度，不注入时钟/状态或调用更新入口。 */
final class NativeSchedulerChecks {
  private NativeSchedulerChecks() {}

  static JSONObject run(Instrumentation runner, Activity home) throws Exception {
    return run(runner, home, false);
  }

  /** true 仅额外打开实际演练场观察准备优先，不发送播放、手势或音频命令。 */
  static JSONObject run(Instrumentation runner, Activity home, boolean practice) throws Exception {
    Context target = guard(runner, home);
    Path report = new File(target.getFilesDir(), "native-host-scheduler-report.json").toPath();
    Files.deleteIfExists(report);
    Observer observer = new Observer(runner, home);
    Activity stage = null;
    JSONObject result = null;
    Throwable failure = null;
    try {
      status(runner, "等待完整、真实的正常调度周期；不缩短期限");
      Frame initial = waitFrame(observer, 90000, frame -> quiet(frame) && frame.pulse,
          "普通入口未进入可观察的空闲调度状态");
      require(!PracticeBridge.active(), "已有演练场不能被调度测试接管");
      Cycle normal = nextSuccessfulCycle(observer, initial, 90000);
      require(normal.finishHigh - normal.finishLow <= 500, "真实完成时刻的观察窗口过宽");
      long intervalLow = normal.after.due - normal.finishHigh;
      long intervalHigh = normal.after.due - normal.finishLow;
      require(intervalHigh >= 48000 && intervalLow <= 72000
          && intervalLow >= 47500 && intervalHigh <= 72500, "成功检查没有真实60秒±20%抖动期限");
      require(normal.after.pulse, "前台阳性对照无法读取真实 pulse，不能证明后台已撤销它");
      JSONObject normalReport = new JSONObject().put("before", initial.json()).put("completed", normal.after.json())
          .put("finishObservedAfterMs", normal.finishLow).put("finishObservedByMs", normal.finishHigh)
          .put("nextDueMinusFinishLowMs", intervalLow).put("nextDueMinusFinishHighMs", intervalHigh)
          .put("expectedLowMs", 48000).put("expectedHighMs", 72000).put("clockInjected", false);

      status(runner, "发送真实 HOME，后台等至少75秒并越过真实 due");
      require(runner.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
          .performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME),
          "系统 HOME 动作未接受");
      Frame background = waitFrame(observer, 10000,
          frame -> !frame.active && !frame.usable && !frame.pulse && !frame.inFlight && !frame.busy,
          "HOME 后仍有调度使用或 pulse");
      require(!background.playback && !PracticeBridge.active(), "后台仍有真实播放，不适用无使用停轮询检查");
      long backgroundDeadline = Math.max(normal.after.due + 3000, background.at + 75000);
      Frame pastDue = background;
      while (SystemClock.elapsedRealtime() < backgroundDeadline) {
        pastDue = observer.read();
        require(!pastDue.active && !pastDue.usable && !pastDue.pulse
            && !pastDue.inFlight && !pastDue.busy, "后台无使用期间重新启动了调度");
        require(pastDue.lastStart == normal.after.lastStart, "后台超过期限前已发起新的检查");
        require(!home.isDestroyed(), "原首页在后台被系统销毁，不能宣称同窗口恢复");
        SystemClock.sleep(100);
      }
      pastDue = observer.read();
      require(pastDue.at > normal.after.due && pastDue.lastStart == normal.after.lastStart,
          "没有等待超过真实期限或后台仍发起检查");
      JSONObject backgroundReport = new JSONObject().put("entered", background.json()).put("pastDue", pastDue.json())
          .put("minimumWaitMs", 75000).put("actualWaitMs", pastDue.at - background.at)
          .put("lastStartUnchanged", true).put("pulseAbsent", true);

      status(runner, "恢复原 Main 窗口，观察真实合并检查");
      restoreHome(runner, target, home);
      Cycle resumed = nextSuccessfulCycle(observer, pastDue, 30000);
      long coalescingUntil = SystemClock.elapsedRealtime() + 5000;
      Frame merged = resumed.after;
      while (SystemClock.elapsedRealtime() < coalescingUntil) {
        merged = observer.read();
        require(merged.lastStart == resumed.after.lastStart && !merged.inFlight && !merged.busy,
            "返回前台产生了重复检查或并行任务");
        SystemClock.sleep(100);
      }
      JSONObject resumedReport = new JSONObject().put("completed", resumed.after.json()).put("afterMergeWindow", merged.json())
          .put("mergeObservedMs", merged.at - resumed.after.at).put("singleCheck", true);

      JSONObject practiceReport = new JSONObject().put("requested", practice).put("verified", false);
      if (practice) {
        status(runner, "打开真实演练场，只观察准备优先");
        stage = runner.startActivitySync(new Intent().setClassName(target, "app.luoxianlv.ui.practice.PracticeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        require(stage != null, "实际演练场没有启动");
        Activity playing = stage;
        observer.window = playing;
        Frame opening = waitFrame(observer, 15000, frame -> {
          dismissFullscreenHint(runner);
          return PracticeBridge.active() && !PracticeBridge.ready() && frame.priorityWork;
        },
            "没有观察到真实演练场准备时的更新让路");
        Frame ready = waitFrame(observer, 90000, frame -> {
          dismissFullscreenHint(runner);
          return PracticeBridge.ready() && frame.windowFocus && !frame.priorityWork;
        },
            "演练场首帧就绪后调度优先状态未恢复");
        practiceReport.put("verified", true).put("opening", opening.json()).put("ready", ready.json())
            .put("bridgeStateInjected", false).put("downloadCancellationVerified", false)
            .put("gesturesTested", false).put("audioTested", false);
      }
      result = new JSONObject().put("passed", true).put("productionTouched", false)
          .put("pid", android.os.Process.myPid()).put("sourceIdentity", observer.source.prepared.identity())
          .put("normal", normalReport).put("background", backgroundReport).put("resumed", resumedReport)
          .put("practiceOpening", practiceReport).put("homeRestored", false).put("stageClosed", stage == null)
          .put("updateStateInjected", false).put("clockInjected", false).put("explicitCheckCalled", false)
          .put("explicitActivationCalled", false).put("healthInjected", false);
    } catch (Throwable invalid) {
      failure = invalid;
    } finally {
      try {
        if (stage != null) {
          Activity closing = stage;
          onMain(runner, () -> { if (!closing.isDestroyed()) closing.finish(); });
          observer.window = home;
          waitFrame(observer, 15000, frame -> closing.isDestroyed() && !PracticeBridge.active(),
              "测试演练场没有关闭并释放入口");
          if (result != null) result.put("stageClosed", true);
        }
      } catch (Throwable closing) {
        failure = append(failure, closing);
      }
      try {
        restoreHome(runner, target, home);
        if (result != null) result.put("homeRestored", true);
      } catch (Throwable restoring) {
        failure = append(failure, restoring);
      }
    }
    if (failure != null) throw new AssertionError("真实宿主调度验收未完成", failure);
    require(result != null && result.getBoolean("homeRestored") && result.getBoolean("stageClosed"),
        "未恢复首页或未关闭演练场不能生成成功回执");
    write(report, result);
    return result;
  }

  /** 由 root 在外部ADB断网后单独调用；不操作系统网络，真实等待越过due。 */
  static JSONObject offline(Instrumentation runner, Activity home) throws Exception {
    Context target = guard(runner, home);
    Path report = new File(target.getFilesDir(), "native-host-scheduler-offline-report.json").toPath();
    Files.deleteIfExists(report);
    Observer observer = new Observer(runner, home);
    Frame disconnected = waitFrame(observer, 20000, frame ->
        frame.active && frame.windowFocus && !frame.online && !frame.inFlight && !frame.busy
            && frame.failures == 0 && !frame.priorityWork,
        "尚未观察到实际前台网络断开回调");
    require(disconnected.due <= disconnected.at + 72500,
        "离线检查必须从正常空闲周期开始，不能缩短或冒充已存在的长退避");
    long deadline = Math.max(disconnected.due + 3000, disconnected.at + 75000);
    Frame pastDue = disconnected;
    while (SystemClock.elapsedRealtime() < deadline) {
      pastDue = observer.read();
      require(pastDue.active && pastDue.windowFocus && !pastDue.online && !pastDue.inFlight && !pastDue.busy
          && pastDue.lastStart == disconnected.lastStart, "外部断网观察期间仍有新请求或前台状态改变");
      SystemClock.sleep(100);
    }
    pastDue = observer.read();
    require(pastDue.at > disconnected.due && pastDue.lastStart == disconnected.lastStart,
        "离线没有越过实际期限或仍发起检查");
    JSONObject result = new JSONObject().put("passed", true).put("productionTouched", false)
        .put("disconnected", disconnected.json()).put("pastDue", pastDue.json())
        .put("systemNetworkChangedByHelper", false).put("lastStartUnchanged", true)
        .put("updateStateInjected", false).put("clockInjected", false).put("healthInjected", false);
    write(report, result);
    return result;
  }

  private static final class Observer {
    final Instrumentation runner;
    final Bootstrap.Source source;
    final Object updates, schedule;
    final Handler main;
    final Runnable pulse;
    volatile Activity window;
    Observer(Instrumentation runner, Activity window) throws Exception {
      this.runner = runner;
      this.window = window;
      source = Bootstrap.source();
      updates = field(Bootstrap.class, null, "updates");
      require(updates != null, "普通入口没有配置实际 HostUpdates");
      schedule = field(updates.getClass(), updates, "schedule");
      main = (Handler) field(updates.getClass(), updates, "main");
      pulse = (Runnable) field(updates.getClass(), updates, "pulse");
    }
    Frame read() throws Exception {
      AtomicReference<Frame> value = new AtomicReference<>();
      onMain(runner, () -> {
        require(Bootstrap.source() == source, "观察期间业务来源改变");
        Frame frame = new Frame();
        synchronized (schedule) {
          frame.at = SystemClock.elapsedRealtime();
          frame.lastStart = (Long) field(schedule.getClass(), schedule, "lastStart");
          frame.due = (Long) field(schedule.getClass(), schedule, "due");
          frame.failures = (Integer) field(schedule.getClass(), schedule, "failures");
          frame.inFlight = (Boolean) field(schedule.getClass(), schedule, "inFlight");
          frame.requested = (Boolean) field(schedule.getClass(), schedule, "requested");
          frame.usable = (Boolean) field(schedule.getClass(), schedule, "usable");
        }
        frame.active = (Boolean) field(updates.getClass(), updates, "active");
        frame.windowFocus = window != null && !window.isDestroyed() && window.hasWindowFocus();
        frame.playback = Bootstrap.playbackInUse();
        frame.online = (Boolean) field(updates.getClass(), updates, "online");
        frame.priorityWork = (Boolean) field(updates.getClass(), updates, "priorityWork");
        frame.busy = (Boolean) field(updates.getClass(), updates, "busy");
        frame.pending = field(updates.getClass(), updates, "pending") != null;
        frame.observing = field(updates.getClass(), updates, "group") != null
            || field(updates.getClass(), updates, "coldTicket") != null;
        require(!(Boolean) field(updates.getClass(), updates, "blocked"), "普通宿主更新已被安全门禁停止");
        frame.pulse = pulsePending(main, pulse);
        value.set(frame);
      });
      return value.get();
    }
  }

  private static final class Frame {
    long at, lastStart, due;
    int failures;
    boolean inFlight, requested, usable, active, windowFocus, playback, online, priorityWork, busy, pending, observing, pulse;
    JSONObject json() throws Exception {
      return new JSONObject().put("elapsedMs", at).put("lastStartMs", lastStart).put("nextDueMs", due)
          .put("failures", failures).put("inFlight", inFlight).put("requested", requested).put("usable", usable)
          .put("active", active).put("windowFocus", windowFocus).put("playbackInUse", playback)
          .put("online", online).put("priorityWork", priorityWork).put("busy", busy)
          .put("pending", pending).put("observing", observing).put("pulseScheduled", pulse);
    }
  }
  private static final class Cycle {
    final Frame after;
    final long finishLow, finishHigh;
    Cycle(Frame after, long finishLow, long finishHigh) { this.after = after; this.finishLow = finishLow; this.finishHigh = finishHigh; }
  }
  private interface Check { boolean get(Frame frame) throws Exception; }
  private interface MainAction { void run() throws Exception; }

  private static Cycle nextSuccessfulCycle(Observer observer, Frame previous, long timeout) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + timeout;
    long previousStart = previous.lastStart;
    while (SystemClock.elapsedRealtime() < deadline) {
      Frame current = observer.read();
      require(current.active && current.online && !current.priorityWork && !current.pending && !current.observing,
          "观察普通检查时发生断网、准备或候选观察，不能冒充正常轮询");
      if (current.lastStart != previousStart && !current.inFlight && !current.busy
          && current.failures == 0 && current.due > current.at) {
        return new Cycle(current, previous.at, current.at);
      }
      require(current.failures == 0, "普通检查出现真实失败或退避，不能生成成功间隔证据");
      previous = current;
      SystemClock.sleep(20);
    }
    throw new AssertionError("没有观察到普通入口自然完成下一次检查");
  }

  private static Frame waitFrame(Observer observer, long timeout, Check check, String message) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + timeout;
    while (SystemClock.elapsedRealtime() < deadline) {
      Frame value = observer.read();
      if (check.get(value)) return value;
      SystemClock.sleep(50);
    }
    throw new AssertionError(message);
  }
  private static boolean quiet(Frame frame) {
    return frame.active && frame.online && !frame.priorityWork && !frame.inFlight && !frame.busy
        && !frame.pending && !frame.observing && frame.failures == 0;
  }

  private static boolean pulsePending(Handler handler, Runnable pulse) throws Exception {
    try { return (Boolean) Handler.class.getMethod("hasCallbacks", Runnable.class).invoke(handler, pulse); }
    catch (NoSuchMethodException api26) {
      // API26使用公开dump，且调用方要求前台阳性对照；不读系统私有队列字段或保存原始队列文本。
      AtomicBoolean found = new AtomicBoolean();
      String callback = "callback=" + pulse.getClass().getName();
      handler.dump(line -> {
        int at = line.indexOf(callback);
        int end = at + callback.length();
        if (at >= 0 && (end == line.length() || line.charAt(end) == ' ' || line.charAt(end) == '}')) found.set(true);
      }, "");
      return found.get();
    }
  }
  private static Object field(Class<?> type, Object owner, String name) throws Exception {
    Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner);
  }
  private static void onMain(Instrumentation runner, MainAction action) throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    runner.runOnMainSync(() -> { try { action.run(); } catch (Throwable invalid) { failure.set(invalid); } });
    if (failure.get() != null) throw new AssertionError("宿主调度主线程观察失败", failure.get());
  }
  private static void restoreHome(Instrumentation runner, Context target, Activity home) throws Exception {
    onMain(runner, () -> {
      require(!home.isDestroyed() && !home.isFinishing(), "原首页已结束，不能冒充恢复原窗口");
      if (home.hasWindowFocus() && Bootstrap.foregroundInUse()) return;
      target.startActivity(new Intent().setClassName(target, "app.luoxianlv.MainActivity")
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    });
    long deadline = SystemClock.elapsedRealtime() + 15000;
    AtomicBoolean restored = new AtomicBoolean();
    while (SystemClock.elapsedRealtime() < deadline) {
      onMain(runner, () -> restored.set(home.hasWindowFocus() && Bootstrap.foregroundInUse()));
      if (restored.get()) return;
      SystemClock.sleep(50);
    }
    throw new AssertionError("原首页没有恢复焦点和前台生命周期");
  }
  private static Context guard(Instrumentation runner, Activity home) throws Exception {
    Context target = runner.getTargetContext();
    require(home != null && target.getPackageName().equals("app.luoxianlv.debug")
        && (target.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
        && Looper.myLooper() != Looper.getMainLooper(), "调度仪器仅允许本机Debug工作线程");
    var startup = Bootstrap.startupState();
    require(startup != null && startup.config.automatic && startup.config.environment.equals("test")
        && startup.config.origin.toString().equals("http://127.0.0.1:18472"), "调度仪器仅允许显式本机test自动更新");
    return target;
  }
  @SuppressWarnings("deprecation") // API26仍需要回收节点；新SDK为兼容空操作。
  private static void dismissFullscreenHint(Instrumentation runner) {
    var root = runner.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        .getRootInActiveWindow();
    if (root == null) return;
    var titles = root.findAccessibilityNodeInfosByText("Viewing full screen");
    var buttons = new ArrayList<>(root.findAccessibilityNodeInfosByText("Got it"));
    buttons.addAll(root.findAccessibilityNodeInfosByText("GOT IT"));
    try {
      for (AccessibilityNodeInfo title : titles) {
        String owner = String.valueOf(title.getPackageName());
        if (!owner.equals("android") && !owner.equals("com.android.systemui")) continue;
        if (!"Viewing full screen".equalsIgnoreCase(String.valueOf(title.getText()))) continue;
        for (AccessibilityNodeInfo button : buttons) {
          if (owner.equals(String.valueOf(button.getPackageName()))
              && "Got it".equalsIgnoreCase(String.valueOf(button.getText()))) {
            require(button.performAction(AccessibilityNodeInfo.ACTION_CLICK), "系统首次全屏提示无法关闭");
            return;
          }
        }
      }
    } finally {
      for (AccessibilityNodeInfo title : titles) title.recycle();
      for (AccessibilityNodeInfo button : buttons) button.recycle();
      root.recycle();
    }
  }
  private static void status(Instrumentation runner, String message) {
    Bundle status = new Bundle(); status.putString("stream", "调度观察：" + message + "\n"); runner.sendStatus(Activity.RESULT_OK, status);
  }
  private static Throwable append(Throwable original, Throwable added) {
    if (original == null) return added; original.addSuppressed(added); return original;
  }
  private static void write(Path report, JSONObject value) throws Exception {
    Path temporary = Files.createTempFile(report.getParent(), "native-scheduler-report-", ".tmp");
    try {
      Files.write(temporary, value.toString(2).getBytes(StandardCharsets.UTF_8));
      Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING);
    } finally { Files.deleteIfExists(temporary); }
  }
  private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
