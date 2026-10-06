package app.luoxianlv.host.input;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.RemoteInput;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Display;
import android.view.MotionEvent;
import app.luoxianlv.hot.contract.PlaybackBridge;
import app.luoxianlv.hot.contract.SharedInput;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** 真实宿主输入桥验收；真实触点测试只允许显式指定本机测试触屏和独立接收页面。 */
public final class InputModeInstrumentation extends Instrumentation {
  private String fixtureCommand = "/data/local/tmp/luoxianlv-input-fixture/touch-fixture";
  private static final String RECEIVER = "app.luoxianlv.audit.touchprobe";
  private static final String LOG = "files/touch-events.jsonl";
  private static final String READY = "files/touch-ready.json";
  private final ArrayList<SharedInput.Session> sessions = new ArrayList<>();
  private String mergedMode;
  private String expectedTouchProtocol;
  private int wirelessConnectPort;
  private int expectedHelperUid = 2000;
  private String wirelessCode, wirelessPairPort;
  private boolean fixture;
  private boolean floatingWithoutAccessibility;
  private boolean frozenConnection;
  private boolean frozenManager;
  private boolean notificationPair, pairedSaved, previousPaired, previousPairedPresent;
  private boolean wirelessOffline, networkChanged, startupAuto;
  private boolean previousWifi;
  private int previousWirelessDebug;
  private SharedInput.Bridge bridge;
  private UiAutomation automation;
  private boolean fingerDown;
  private String probeRun;

  @Override public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    mergedMode = arguments == null ? null : arguments.getString("mergedMode");
    expectedTouchProtocol = arguments == null ? null : arguments.getString("expectedTouchProtocol");
    if (arguments != null) expectedHelperUid = Integer.parseInt(arguments.getString("expectedHelperUid", "2000"));
    wirelessConnectPort = arguments == null ? 0 : Integer.parseInt(arguments.getString("wirelessConnectPort", "0"));
    wirelessCode = arguments == null ? null : arguments.getString("wirelessCode");
    wirelessPairPort = arguments == null ? null : arguments.getString("wirelessPairPort");
    fixture = arguments != null && "true".equals(arguments.getString("fixture"));
    floatingWithoutAccessibility = arguments != null && "true".equals(arguments.getString("floatingWithoutAccessibility"));
    frozenConnection = arguments != null && "true".equals(arguments.getString("frozenConnection"));
    frozenManager = arguments != null && "true".equals(arguments.getString("frozenManager"));
    notificationPair = arguments != null && "true".equals(arguments.getString("notificationPair"));
    wirelessOffline = arguments != null && "true".equals(arguments.getString("wirelessOffline"));
    startupAuto = arguments != null && "true".equals(arguments.getString("startupAuto"));
    if (arguments != null && arguments.containsKey("fixtureCommand"))
      fixtureCommand = arguments.getString("fixtureCommand");
    start();
  }

  @Override public void onStart() {
    Bundle result = new Bundle();
    String previousMode = null;
    boolean success = false;
    try {
      require(getTargetContext().getPackageName().endsWith(".debug"), "仅允许运行 Debug 宿主");
      await("宿主未安装输入桥", () -> SharedInput.current() != null, 10000);
      bridge = SharedInput.current();
      await("输入状态尚未初始化", () -> bridge.state().getBoolean("initialized"), 10000);
      previousMode = bridge.state().getString("mode", SharedInput.ACCESSIBILITY);
      if (startupAuto) {
        require(SharedInput.WIRELESS.equals(previousMode) && SharedInput.WIRELESS.equals(mergedMode),
            "启动恢复检查需要预先选择无线模式");
        await("启动后没有恢复已配对的连接", () -> bridge.state().getBoolean("paired")
            && bridge.state().getBoolean("connected") && bridge.state().getBoolean("touchReady"), 30000);
      }
      var playback = PlaybackBridge.current();
      require(playback == null || !playback.query("state").getBoolean("playing"),
          "请先暂停已有播放，测试不会抢占正在演奏的会话");
      if (notificationPair) {
        require(SharedInput.WIRELESS.equals(mergedMode) && fixture, "通知配对仅用于明确启用的无线 fixture 测试");
        var prefs = getTargetContext().getSharedPreferences("input-wireless", Context.MODE_PRIVATE);
        previousPairedPresent = prefs.contains("paired");
        previousPaired = prefs.getBoolean("paired", false);
        pairedSaved = true;
        require(prefs.edit().putBoolean("paired", false).commit(), "无法暂停测试前的配对状态标记");
      }
      checkControl();
      if (mergedMode != null) {
        require(fixture, "真实合流必须明确传 fixture=true，拒绝对普通页面注入");
        require(fixtureCommand != null && fixtureCommand.matches("/data/local/tmp/[a-zA-Z0-9_/-]+"),
            "测试设备命令路径无效");
        require(SharedInput.SHIZUKU.equals(mergedMode) || SharedInput.WIRELESS.equals(mergedMode),
            "mergedMode 必须为 shizuku 或 wireless");
        automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        checkMerged();
        if (frozenManager) ShizukuManagerFrozenChecks.run(this, automation, bridge);
        if (frozenConnection) ShizukuFrozenChecks.run(this, automation, bridge);
        checkScreenshot();
        app.luoxianlv.host.FixedInputChecks.run(this);
        if (floatingWithoutAccessibility) FloatingWithoutAccessibilityChecks.run(this, automation);
        result.putString("stream", "输入模式验收通过：协议=" + bridge.state().getString("touchProtocol")
            + "，真实截图、固定琴键演奏、长按合流、音符间隙保留手指、抬手等待与取消、协议帧处理、租约释放及旧 owner 隔离。\n");
        if (floatingWithoutAccessibility)
          result.putString("stream", result.getString("stream") + "通过：关闭无障碍，真实输入连接下通过业务启动悬浮窗，展开、选歌、收起与关闭；未点击播放。\n");
      } else {
        result.putString("stream", "输入模式状态验收通过；本次未指定 fixture，未执行真实触点合流测试。\n");
      }
      success = true;
    } catch (Throwable failure) {
      result.putString("stream", "输入模式验收失败：" + failure + "\n");
      result.putString("error", android.util.Log.getStackTraceString(failure));
      if (bridge != null) result.putString("state", bridge.state().toString());
    } finally {
      try {
        if (networkChanged) {
          shell("svc wifi " + (previousWifi ? "enable" : "disable"));
          if (previousWifi) await("测试后 Wi-Fi 尚未恢复", () -> {
            var manager = getTargetContext().getSystemService(android.net.ConnectivityManager.class);
            for (var network : manager.getAllNetworks()) {
              var caps = manager.getNetworkCapabilities(network);
              if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return true;
            }
            return false;
          }, 15000);
          shell("settings put global adb_wifi_enabled " + previousWirelessDebug);
        }
        if (fingerDown) physical("up");
        for (var session : sessions) session.close();
        if (bridge != null) {
          bridge.release();
          await("清理未归还触屏", () -> sessions.stream().allMatch(SharedInput.Session::idle), 10000);
          if (previousMode != null) {
            String restore = previousMode;
            bridge.select(restore);
            await("未恢复原输入模式", () -> restore.equals(bridge.state().getString("mode")), 10000);
          }
        }
      } catch (Throwable cleanup) {
        success = false;
        result.putString("cleanup", cleanup.toString());
      }
      if (pairedSaved) {
        var editor = getTargetContext().getSharedPreferences("input-wireless", Context.MODE_PRIVATE).edit();
        if (previousPairedPresent) editor.putBoolean("paired", previousPaired);
        else editor.remove("paired");
        if (!editor.commit()) {
          success = false;
          result.putString("pairedCleanup", "无法恢复测试前的配对标记");
        }
      }
    }
    finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }

  private void checkScreenshot() throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<app.luoxianlv.hot.contract.AccessibilityBinding.Frame> result = new java.util.concurrent.atomic.AtomicReference<>();
    AtomicBoolean onMain = new AtomicBoolean();
    runOnMainSync(() -> bridge.screenshot(0, new app.luoxianlv.hot.contract.AccessibilityBinding.ScreenshotCallback() {
      @Override public void success(app.luoxianlv.hot.contract.AccessibilityBinding.Frame frame) {
        onMain.set(Looper.myLooper() == Looper.getMainLooper()); result.set(frame); done.countDown();
      }
      @Override public void failure(int code) { done.countDown(); }
    }));
    require(done.await(10, TimeUnit.SECONDS), "输入服务截图超时");
    var frame = result.get();
    require(frame != null && onMain.get(), "没有实际截图或回调未回到主线程");
    try {
      require(frame.width > 100 && frame.height > 100 && frame.buffer == null, "截图没有返回有效软件图像");
      // 真实业务识别器消费软件帧，验证不依赖无障碍 HardwareBuffer。
      var loader = app.luoxianlv.host.Bootstrap.source().prepared.classLoader();
      Class<?> analyzer = Class.forName("app.luoxianlv.recognition.ScreenshotAnalyzer", true, loader);
      var recognize = java.util.Arrays.stream(analyzer.getDeclaredMethods()).filter(m -> m.getName().equals("recognize")).findFirst().orElseThrow();
      recognize.setAccessible(true);
      recognize.invoke(analyzer.getField("INSTANCE").get(null), frame, null);
      require(frame.takeBitmap() == null, "识别器没有消费截图所有权");
    } finally { frame.close(); }
  }

  private void checkControl() throws Exception {
    bridge.select(SharedInput.ACCESSIBILITY);
    await("未切换到无障碍模式", () -> SharedInput.ACCESSIBILITY.equals(bridge.state().getString("mode")), 5000);
    require(!bridge.state().getBoolean("active"), "无障碍默认模式仍捕获物理触屏");
    Bundle copied = bridge.state();
    copied.putString("mode", "edited-copy");
    require(SharedInput.ACCESSIBILITY.equals(bridge.state().getString("mode")), "状态快照可反向修改宿主");
    var first = lease();
    var second = lease();
    require(first.idle() && second.idle(), "创建会话提前抢占输入");
    AtomicInteger updates = new AtomicInteger();
    AtomicBoolean mainThread = new AtomicBoolean(true);
    try (var observed = bridge.observe(() -> {
      updates.incrementAndGet();
      if (Looper.myLooper() != Looper.getMainLooper()) mainThread.set(false);
    })) {
      for (String mode : new String[] {SharedInput.SHIZUKU, SharedInput.WIRELESS, SharedInput.ACCESSIBILITY}) {
        bridge.select(mode);
        await("输入模式状态未刷新：" + mode, () -> mode.equals(bridge.state().getString("mode")), 5000);
        require(first.idle() && second.idle(), "模式选择使空会话抢占触屏");
      }
      await("未收到输入状态观测", () -> updates.get() > 0, 5000);
      require(mainThread.get(), "状态观察回调没有回到主线程");
    }
    first.close(); second.close();
    await("空会话关闭未完成", () -> first.idle() && second.idle(), 5000);
  }

  @SuppressWarnings("deprecation")
  private void checkMerged() throws Exception {
    bridge.select(mergedMode);
    await("未采用指定合流模式", () -> mergedMode.equals(bridge.state().getString("mode")), 5000);
    if (wirelessCode != null || wirelessPairPort != null) {
      require(SharedInput.WIRELESS.equals(mergedMode), "配对参数仅用于无线 ADB 模式");
      require(wirelessCode != null && wirelessCode.matches("[0-9]{6}"), "请输入有效的六位配对码");
      require(wirelessPairPort != null && wirelessPairPort.matches("[0-9]{1,5}"), "配对端口无效");
      int port = Integer.parseInt(wirelessPairPort);
      require(port > 0 && port <= 65535, "配对端口超出范围");
      if (notificationPair) pairThroughNotification(wirelessCode, port);
      else {
        Bundle pair = new Bundle();
        pair.putString("code", wirelessCode);
        pair.putInt("port", port);
        bridge.command("pair", pair);
      }
      wirelessCode = null;
    } else {
      require(wirelessConnectPort >= 0 && wirelessConnectPort <= 65535, "连接端口超出范围");
      Bundle connect = new Bundle();
      connect.putInt("port", wirelessConnectPort);
      bridge.command("connect", connect);
    }
    await("合流未就绪，请预先授权/配对并启动测试触屏：" + bridge.state().getString("message"),
        () -> bridge.state().getBoolean("connected") && bridge.state().getBoolean("touchReady"), 30000);
    if (notificationPair) require(bridge.state().getBoolean("paired"), "通知未完成真实配对，拒绝把旧连接算作成功");
    require(expectedHelperUid == 2000 || (expectedHelperUid == 0 && SharedInput.SHIZUKU.equals(mergedMode)),
        "仅允许官方 Shizuku 身份；无线调试必须为普通 shell");
    require(bridge.state().getInt("uid", -1) == expectedHelperUid, "合流助手与指定服务身份不符");
    require("Luoxianlv Audit Direct Touch".equals(bridge.state().getString("name")),
        "拒绝操作非测试触屏设备");
    if (expectedTouchProtocol != null)
      require(expectedTouchProtocol.equals(bridge.state().getString("touchProtocol")),
          "未采用指定测试触屏协议，实际=" + bridge.state().getString("touchProtocol"));
    var playback = PlaybackBridge.current();
    require(playback == null || !playback.query("state").getBoolean("floatingVisible"),
        "请先隐藏悬浮窗，避免测试触点被其他窗口接收");
    require(bridge.state().getInt("deviceId", -1) == 0, "合流输出必须使用虚拟触摸设备");
    require("virtual-injection".equals(bridge.state().getString("deviceMatchMethod")), "合流仍依赖物理设备名匹配");
    require(shell("pm path " + RECEIVER).trim().startsWith("package:"), "独立触点接收 APK 尚未安装");
    probeRun = "run_" + android.os.Process.myPid() + "_" + SystemClock.elapsedRealtime();
    require(shell("am start -W -n " + RECEIVER + "/.MainActivity --ez clear_logs true --es probe_run " + probeRun).contains("Starting"),
        "独立触点接收页面启动失败");
    Display display = getTargetContext().getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
    Point size = new Point(); display.getRealSize(size);
    int rotation = display.getRotation();
    await("独立接收页未取得焦点或真实显示尺寸不稳定", () -> receiverReady(size, rotation), 10000);
    var first = lease(); var second = lease();
    Outcome initial = new Outcome();
    AutoCloseable oldToken = first.press(new float[] {size.x * .75f, size.y * .65f},
        6000, size.x, size.y, rotation, initial);
    await("首音没有实际接管触屏", () -> bridge.state().getBoolean("active"), 5000);
    awaitFrames("独立接收页未收到首音，拒绝仅凭active状态继续", frames -> frames.stream().anyMatch(frame -> {
      // InputDispatcher 会将注入设备编号改为虚拟编号 -1，旧系统也可能保留输入参数 0。
      try { return isTouch(frame) && (frame.getInt("deviceId") == -1 || frame.getInt("deviceId") == 0)
          && frame.getInt("actionMasked") == MotionEvent.ACTION_DOWN; }
      catch (Exception malformed) { return false; }
    }), 5000);
    physicalPoint("down", .25f, .65f, rotation); fingerDown = true;
    physicalPoint("move", .29f, .7f, rotation);
    awaitFrames("物理触点尚未实际与首音合流", frames -> hasCombined(frames, size.x, 0), 5000);
    if (wirelessOffline) {
      require(SharedInput.WIRELESS.equals(mergedMode), "离线检查仅用于内置无线调试");
      previousWifi = getTargetContext().getSystemService(android.net.wifi.WifiManager.class).isWifiEnabled();
      previousWirelessDebug = android.provider.Settings.Global.getInt(
          getTargetContext().getContentResolver(), "adb_wifi_enabled", 0);
      require(previousWifi && previousWirelessDebug == 1, "离线检查需要先完成无线调试连接");
      networkChanged = true;
      shell("settings put global adb_wifi_enabled 0");
      shell("svc wifi disable");
      SystemClock.sleep(5500);
      require(bridge.state().getBoolean("connected") && bridge.state().getBoolean("touchReady")
          && bridge.state().getBoolean("active"), "Wi-Fi 或无线调试关闭打断了已启动的触控会话");
    }
    Outcome rejected = new Outcome();
    second.press(new float[] {size.x * .8f, size.y * .7f}, 100, size.x, size.y, rotation, rejected);
    require(!rejected.await("候选 owner 没有收到拒绝"), "新 owner 抢走活动触屏");
    require(!first.idle() && bridge.state().getBoolean("active"), "候选 owner 的失败终止了原会话");
    require(initial.await("首音未完成"), "首音失败：" + initial.message);
    require(!first.idle(), "音符结束错误归还了持续读取的触屏");
    long gap = SystemClock.uptimeMillis();
    physicalPoint("move", .3f, .72f, rotation);
    awaitFrames("音符间隙未收到真实手指移动", frames -> frames.stream().anyMatch(frame -> {
      try {
        if (!isTouch(frame)) return false;
        JSONArray points = frame.getJSONArray("pointers");
        return frame.getLong("eventTimeMs") >= gap && points.length() == 1
            && points.getJSONObject(0).getDouble("x") < size.x * .5
            && frame.getInt("actionMasked") == MotionEvent.ACTION_MOVE;
      } catch (Exception malformed) { return false; }
    }), 5000);
    Outcome following = new Outcome();
    first.press(new float[] {size.x * .75f, size.y * .65f}, 2000, size.x, size.y, rotation, following);
    awaitFrames("后续音符尚未送达真实接收页", frames -> hasCombined(frames, size.x, gap), 5000);
    oldToken.close(); // 旧按键句柄不能取消后续音符。
    physicalPoint("move", .32f, .74f, rotation);
    require(following.await("后续音符未完成"), "旧 token 取消了后续音符：" + following.message);
    verifyPhysicalFrames(readFrames(), gap, size.x);
    first.release();
    await("首个 owner 尚未真实归还触屏", first::idle, 5000);
    require(!bridge.state().getBoolean("active"), "释放确认后助手仍接管触屏");
    physical("up"); fingerDown = false;
    SystemClock.sleep(100);
    Outcome nextOwner = new Outcome();
    second.press(new float[] {size.x * .7f, size.y * .6f}, 600, size.x, size.y, rotation, nextOwner);
    await("第二 owner 没有取得捕获", () -> bridge.state().getBoolean("active"), 5000);
    first.close();
    require(nextOwner.await("新 owner 音符未完成"), "旧 owner 关闭取消了新 owner");
    second.close();
    await("新 owner 关闭没有实际归还触屏", second::idle, 5000);
    Outcome cancellation = new Outcome();
    var third = lease();
    third.press(new float[] {size.x * .7f, size.y * .6f}, 3000, size.x, size.y, rotation, cancellation);
    await("取消测试未启动捕获", () -> bridge.state().getBoolean("active"), 5000);
    third.close();
    require(!cancellation.await("关闭后任务回调丢失"), "关闭会话后旧按键仍成功完成");
    await("关闭后 capture 尚未释放", third::idle, 5000);
    SystemClock.sleep(200);
    require(cancellation.calls.get() == 1, "任务完成回调重复");
    checkActivationWait(size, rotation);
    if ("type-a".equals(bridge.state().getString("touchProtocol"))) checkTypeAPackets(size, rotation);
    else checkStaleContact(size, rotation);
  }

  /** Type A 包顺序可以改变；仍要保持长按手指及自动音符的实际身份。 */
  private void checkTypeAPackets(Point size, int rotation) throws Exception {
    require(rotation == 0, "多包测试使用明确的竖屏自然坐标");
    var session = lease();
    try {
      long start = SystemClock.uptimeMillis();
      Outcome note = new Outcome();
      session.press(new float[] {size.x * .8f, size.y * .7f}, 8000,
          size.x, size.y, rotation, note);
      await("Type A 多点测试没有接管", () -> bridge.state().getBoolean("active"), 5000);
      physicalPoint("down", .25f, .65f, rotation); fingerDown = true;
      awaitFrames("Type A 首个真实手指没有合流", frames -> hasCombined(frames, size.x, start), 5000);
      long twoAt = SystemClock.uptimeMillis();
      physical("two 8192 21299 13107 18022");
      awaitFrames("Type A 两个物理手指没有与音符合流", frames -> frames.stream().anyMatch(frame -> {
        try { return isTouch(frame) && frame.getLong("eventTimeMs") >= twoAt && frame.getJSONArray("pointers").length() == 3; }
        catch (Exception malformed) { return false; }
      }), 5000);
      ArrayList<JSONObject> before = readFrames();
      JSONObject observed = null;
      for (JSONObject frame : before) if (isTouch(frame) && frame.getLong("eventTimeMs") >= twoAt
          && frame.getJSONArray("pointers").length() == 3) observed = frame;
      require(observed != null, "缺少实际三指帧");
      int heldId = -1;
      for (int i = 0; i < 3; ++i) {
        JSONObject point = observed.getJSONArray("pointers").getJSONObject(i);
        if (point.getDouble("x") < size.x * .3) heldId = point.getInt("id");
      }
      require(heldId >= 0, "未识别固定长按手指");
      final int retained = heldId;
      long swappedAt = SystemClock.uptimeMillis();
      physical("swap");
      awaitFrames("Type A 乱序帧没有送达", frames -> frames.stream().anyMatch(frame -> {
        try {
          if (!isTouch(frame) || frame.getLong("eventTimeMs") < swappedAt || frame.getJSONArray("pointers").length() != 3) return false;
          JSONArray points = frame.getJSONArray("pointers");
          for (int i = 0; i < 3; ++i) if (points.getJSONObject(i).getDouble("x") < size.x * .3)
            return points.getJSONObject(i).getInt("id") == retained;
          return false;
        } catch (Exception malformed) { return false; }
      }), 5000);
      for (JSONObject frame : readFrames()) if (isTouch(frame) && frame.getLong("eventTimeMs") >= twoAt)
        require(frame.getInt("actionMasked") != MotionEvent.ACTION_CANCEL, "Type A 乱序包取消了持续手势");
      physical("up"); fingerDown = false;
      require(note.completed.getCount() == 1, "物理抬手结束了自动音符");
      session.release();
      await("Type A 多点测试没有归还触屏", session::idle, 5000);
    } finally {
      if (fingerDown) { physical("up"); fingerDown = false; }
      session.close();
    }
  }

  /** 追踪编号残留不等于手指按下；仅接触开关变化也必须实时加入和移除真实触点。 */
  private void checkStaleContact(Point size, int rotation) throws Exception {
    bridge.release();
    await("残留槽测试前旧租约尚未归还触屏", () ->
        sessions.stream().allMatch(SharedInput.Session::idle) && !bridge.state().getBoolean("active"), 5000);
    var session = lease();
    try {
      physicalPoint("stale", .25f, .65f, rotation);
      fingerDown = true; // 即使没有接触，也要在失败清理时删除 fixture 的残留编号。
      long firstAt = SystemClock.uptimeMillis();
      Outcome automatic = new Outcome();
      session.press(new float[] {size.x * .75f, size.y * .65f}, 8000,
          size.x, size.y, rotation, automatic);
      await("非接触残留槽仍阻止首次接管", () ->
          bridge.state().getBoolean("active") && staleContactIgnored(bridge.state()), 5000);
      awaitFrames("忽略残留槽后自动音符没有真正送达", frames ->
          hasOnlyAutomatic(frames, size.x, firstAt), 5000);
      require(automatic.completed.getCount() == 1, "接触切换前自动音符提前结束");

      long contactOnAt = SystemClock.uptimeMillis();
      physical("contact_on");
      awaitFrames("同一追踪编号恢复接触后未形成双触点", frames ->
          hasCombined(frames, size.x, contactOnAt), 5000);
      require(automatic.completed.getCount() == 1 && bridge.state().getBoolean("active"),
          "真实接触加入时打断了自动音符");

      long contactOffAt = SystemClock.uptimeMillis();
      physical("contact_off");
      awaitFrames("接触结束后真实触点未移除或自动音符没有继续", frames ->
          hasPointerUp(frames, contactOffAt) && hasOnlyAutomatic(frames, size.x, contactOffAt), 5000);
      require(automatic.completed.getCount() == 1 && bridge.state().getBoolean("active"),
          "真实接触结束时打断了自动音符");
      require(automatic.await("接触切换后的自动音符未完成"),
          "接触开关变化使自动音符失败：" + automatic.message);
      verifyStaleContactFrames(readFrames(), size.x, firstAt, contactOnAt, contactOffAt);
      require(automatic.calls.get() == 1, "残留槽测试发生重复完成回调");

      session.release();
      await("残留槽测试的租约没有真实归还触屏", session::idle, 5000);
      require(!bridge.state().getBoolean("active"), "归还后仍捕获测试触屏");
      // 不发送 up，保持同一 trackingId 和 BTN_TOUCH=0，验证释放后重新接管。
      long nextAt = SystemClock.uptimeMillis();
      Outcome replay = new Outcome();
      session.press(new float[] {size.x * .75f, size.y * .65f}, 1000,
          size.x, size.y, rotation, replay);
      await("归还后残留槽再次阻止接管", () ->
          bridge.state().getBoolean("active") && staleContactIgnored(bridge.state()), 5000);
      awaitFrames("重新接管后自动音符没有真正送达", frames ->
          hasOnlyAutomatic(frames, size.x, nextAt), 5000);
      require(replay.await("残留槽下的第二次播放没有完成"),
          "释放后残留槽使第二次播放失败：" + replay.message);
      require(replay.calls.get() == 1, "残留槽重播发生重复完成回调");
    } finally {
      try {
        physical("up");
        fingerDown = false;
      } finally {
        session.close();
        await("残留槽测试结束后没有归还触屏", session::idle, 5000);
      }
    }
  }

  private static boolean staleContactIgnored(Bundle state) {
    return state.getBoolean("touchReady") && state.getBoolean("supported")
        && state.getInt("trackedSlots", 0) > 0 && state.getBoolean("touchSupported")
        && !state.getBoolean("touchPressed") && state.getBoolean("staleContactIgnored");
  }

  private void verifyStaleContactFrames(ArrayList<JSONObject> frames, int width,
      long startedAt, long contactOnAt, long contactOffAt) throws Exception {
    boolean combined = false, automaticContinued = false;
    int automaticId = -1;
    for (JSONObject frame : frames) {
      if (!isTouch(frame)) continue;
      long at = frame.getLong("eventTimeMs");
      if (at < startedAt) continue;
      require(frame.getInt("actionMasked") != MotionEvent.ACTION_CANCEL,
          "接触切换期间出现 ACTION_CANCEL");
      JSONArray points = frame.getJSONArray("pointers");
      boolean left = false, right = false;
      for (int i = 0; i < points.length(); i++) {
        JSONObject point = points.getJSONObject(i);
        if (point.getDouble("x") < width * .5) left = true;
        else {
          right = true;
          if (automaticId < 0) automaticId = point.getInt("id");
          require(automaticId == point.getInt("id"), "接触切换时自动触点被重新编号");
        }
      }
      if (at < contactOnAt) require(!left, "未接触的残留槽被注入为真实手指");
      combined |= at >= contactOnAt && at <= contactOffAt && left && right;
      automaticContinued |= at >= contactOffAt && points.length() == 1 && right
          && frame.getInt("actionMasked") == MotionEvent.ACTION_MOVE;
    }
    require(automaticId == 0, "忽略非接触残留槽后首个自动触点没有使用标准编号0");
    require(combined && automaticContinued, "接触开关切换没有形成真实双触点及持续自动音符");
  }

  private static boolean hasOnlyAutomatic(ArrayList<JSONObject> frames, int width, long afterMs) {
    for (JSONObject frame : frames) {
      try {
        if (!isTouch(frame)) continue;
        JSONArray points = frame.getJSONArray("pointers");
        int action = frame.getInt("actionMasked");
        if (frame.getLong("eventTimeMs") >= afterMs && points.length() == 1
            && points.getJSONObject(0).getDouble("x") > width * .5
            && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)) return true;
      } catch (Exception malformed) { }
    }
    return false;
  }

  private static boolean hasPointerUp(ArrayList<JSONObject> frames, long afterMs) {
    for (JSONObject frame : frames) {
      try {
        if (!isTouch(frame)) continue;
        if (frame.getLong("eventTimeMs") >= afterMs
            && frame.getInt("actionMasked") == MotionEvent.ACTION_POINTER_UP) return true;
      } catch (Exception malformed) { }
    }
    return false;
  }

  /** 点击播放的手指尚未抬起不能永久锁死就绪状态，取消等待也不能稍后抓取触屏。 */
  private void checkActivationWait(Point size, int rotation) throws Exception {
    var session = lease();
    physicalPoint("down", .25f, .65f, rotation); fingerDown = true;
    Outcome held = new Outcome();
    session.press(new float[] {size.x * .75f, size.y * .65f}, 1000,
        size.x, size.y, rotation, held);
    require(!held.await("持续按住手指时激活等待没有结束"), "手指未抬起时错误抓取了触屏");
    Bundle blocked = bridge.state();
    require(blocked.getBoolean("supported") && blocked.getBoolean("touchReady"),
        "手指暂时按下被误判为设备不支持，后续播放会永久失效");
    require(!blocked.getBoolean("active") && !blocked.getBoolean("waitingForFingers"),
        "激活超时后仍捕获触屏或残留等待");
    require(blocked.getLong("activationWaitMs") >= 500, "激活没有等待完整的抬手窗口");
    physical("up"); fingerDown = false;
    Outcome recovered = new Outcome();
    session.press(new float[] {size.x * .75f, size.y * .65f}, 150,
        size.x, size.y, rotation, recovered);
    require(recovered.await("抬手后下一次播放没有完成"), "暂时按下导致后续播放仍然失败");
    session.release();
    await("恢复测试没有归还触屏", session::idle, 5000);

    physicalPoint("down", .25f, .65f, rotation); fingerDown = true;
    Outcome cancelled = new Outcome();
    AutoCloseable handle = session.press(new float[] {size.x * .75f, size.y * .65f}, 1000,
        size.x, size.y, rotation, cancelled);
    await("取消测试没有进入可取消的抬手等待", () -> bridge.state().getBoolean("waitingForFingers"), 3000);
    handle.close();
    require(!cancelled.await("取消抬手等待没有回调"), "取消后本次按键仍成功完成");
    physical("up"); fingerDown = false;
    SystemClock.sleep(600);
    require(!bridge.state().getBoolean("active") && !bridge.state().getBoolean("waitingForFingers"),
        "取消的旧定时任务在抬手后重新接管了触屏");
    require(cancelled.calls.get() == 1, "取消激活等待发生重复回调");
    session.close();
    await("抬手等待测试没有释放会话", session::idle, 5000);
  }

  private void verifyPhysicalFrames(ArrayList<JSONObject> frames, long gap, int width) throws Exception {
    boolean combined = false, keptFingerInGap = false, combinedAfterGap = false;
    boolean firstDown = false;
    int physicalPointer = -1;
    long physicalDownTime = -1;
    for (JSONObject frame : frames) {
      if (!isTouch(frame)) continue;
      require(frame.getInt("actionMasked") != MotionEvent.ACTION_CANCEL,
          "持续演奏时出现 ACTION_CANCEL");
      JSONArray points = frame.getJSONArray("pointers");
      if (!firstDown && frame.getInt("actionMasked") == MotionEvent.ACTION_DOWN) {
        require(points.length() == 1 && points.getJSONObject(0).getInt("id") == 0,
            "首个自动触点没有使用标准单指编号0");
        firstDown = true;
      }
      boolean left = false, right = false;
      for (int i = 0; i < points.length(); i++) {
        JSONObject point = points.getJSONObject(i);
        int id = point.getInt("id");
        require(id >= 0 && id < 16, "输出触点编号超出兼容范围");
        float x = (float) point.getDouble("x");
        if (x < width * .5f) {
          if (physicalPointer < 0) physicalPointer = id;
          require(physicalPointer == id, "真实手指在自动按键结束后被重新编号");
          long downTime = frame.getLong("downTimeMs");
          if (physicalDownTime < 0) physicalDownTime = downTime;
          require(downTime > 0 && downTime == physicalDownTime,
              "真实手指的downTime在音符间隙或后续音符中发生变化");
          require(frame.getLong("eventTimeMs") >= downTime, "触摸事件时间早于按下时间");
        }
        left |= x < width * .5f; right |= x > width * .5f;
      }
      combined |= points.length() >= 2 && left && right;
      combinedAfterGap |= frame.getLong("eventTimeMs") >= gap && points.length() >= 2 && left && right;
      keptFingerInGap |= frame.getLong("eventTimeMs") >= gap && points.length() == 1 && left
          && frame.getInt("actionMasked") == MotionEvent.ACTION_MOVE;
    }
    require(firstDown, "未收到首个自动按键的ACTION_DOWN");
    require(combined, "接收页面未收到手指与自动按键同帧事件");
    require(keptFingerInGap, "音符间隙没有保留真实手指移动");
    require(combinedAfterGap, "后续音符没有与原来的真实手指持续合流");
  }

  private void pairThroughNotification(String code, int port) throws Exception {
    bridge.command("disconnect", new Bundle());
    await("旧无线连接尚未退出", () -> !bridge.state().getBoolean("connected"), 10000);
    require(!getTargetContext().getSharedPreferences("input-wireless", Context.MODE_PRIVATE)
        .getBoolean("paired", false), "已有配对标记会掩盖通知入口失败");
    bridge.command("discover", new Bundle());
    await("未发现本次系统配对端口", () -> bridge.state().getInt("pairingPort") == port, 15000);
    final Notification.Action[] reply = new Notification.Action[1];
    NotificationManager manager = getTargetContext().getSystemService(NotificationManager.class);
    await("配对通知未出现或缺少 RemoteInput，请检查通知权限", () -> {
      for (var active : manager.getActiveNotifications()) {
        if (active.getId() != 7212 || !getTargetContext().getPackageName().equals(active.getPackageName())) continue;
        Notification.Action[] actions = active.getNotification().actions;
        if (actions == null) continue;
        for (Notification.Action action : actions) {
          RemoteInput[] inputs = action.getRemoteInputs();
          if (inputs == null || inputs.length != 1 || !inputs[0].getAllowFreeFormInput()
              || action.actionIntent == null) continue;
          if (!getTargetContext().getPackageName().equals(action.actionIntent.getCreatorPackage())) continue;
          reply[0] = action;
          return true;
        }
      }
      return false;
    }, 15000);
    RemoteInput[] fields = reply[0].getRemoteInputs();
    Bundle text = new Bundle();
    text.putCharSequence(fields[0].getResultKey(), code);
    Intent delivery = new Intent();
    RemoteInput.addResultsToIntent(fields, delivery, text);
    if (android.os.Build.VERSION.SDK_INT >= 28)
      RemoteInput.setResultsSource(delivery, RemoteInput.SOURCE_FREE_FORM_INPUT);
    reply[0].actionIntent.send(getTargetContext(), 0, delivery);
    await("通知广播未触发真实配对", () -> bridge.state().getBoolean("pairing")
        || bridge.state().getBoolean("paired"), 10000);
  }

  private ArrayList<JSONObject> readFrames() throws Exception {
    ArrayList<JSONObject> records = new ArrayList<>();
    String logged = shell("run-as " + RECEIVER + " cat " + LOG + " 2>&1");
    require(!logged.contains("Permission denied") && !logged.contains("No such file"),
        "触点接收日志无法读取：" + logged.trim());
    for (String line : logged.split("\n")) {
      if (line.trim().isEmpty()) continue;
      // flush 与 cat 可能相遇在最后一行中途；只消费已完整写入的本次运行记录。
      JSONObject frame;
      try { frame = new JSONObject(line); }
      catch (org.json.JSONException partial) { continue; }
      if (probeRun.equals(frame.optString("runId"))) records.add(frame);
    }
    require(!records.isEmpty(), "触点接收日志为空");
    return records;
  }

  private boolean receiverReady(Point size, int rotation) {
    try {
      JSONObject ready = new JSONObject(shell("run-as " + RECEIVER + " cat " + READY + " 2>/dev/null"));
      return probeRun.equals(ready.optString("runId")) && ready.optBoolean("focused")
          && ready.optInt("width") == size.x && ready.optInt("height") == size.y
          && ready.optInt("rotation", -1) == rotation
          && ready.optInt("viewWidth") > 0 && ready.optInt("viewHeight") > 0;
    } catch (Exception unavailable) { return false; }
  }

  private void awaitFrames(String message, java.util.function.Predicate<ArrayList<JSONObject>> check, long timeoutMs) {
    await(message, () -> {
      try { return check.test(readFrames()); }
      catch (Exception pending) { return false; }
    }, timeoutMs);
  }

  private static boolean hasCombined(ArrayList<JSONObject> frames, int width, long afterMs) {
    for (JSONObject frame : frames) {
      try {
        if (!isTouch(frame)) continue;
        if (frame.getLong("eventTimeMs") < afterMs) continue;
        boolean left = false, right = false;
        JSONArray points = frame.getJSONArray("pointers");
        for (int i = 0; i < points.length(); ++i) {
          double x = points.getJSONObject(i).getDouble("x");
          left |= x < width * .5; right |= x > width * .5;
        }
        if (points.length() >= 2 && left && right) return true;
      } catch (Exception malformed) { }
    }
    return false;
  }

  private static boolean isTouch(JSONObject frame) {
    // 悬停退出属于 onGenericMotionEvent，不能充当实际触摸或鬼点证据。
    return "onTouchEvent".equals(frame.optString("callback"));
  }

  private SharedInput.Session lease() {
    SharedInput.Session session = bridge.openSession(); sessions.add(session); return session;
  }

  private void physical(String command) throws Exception {
    require(automation != null, "未准备测试输入设备");
    require(shell(fixtureCommand + " send " + command).trim().startsWith("OK"), "测试触屏命令失败");
  }

  private void physicalPoint(String action, float x, float y, int rotation) throws Exception {
    float u = x, v = y;
    switch (rotation) {
      case 1 -> { u = 1 - y; v = x; }
      case 2 -> { u = 1 - x; v = 1 - y; }
      case 3 -> { u = y; v = 1 - x; }
      default -> { }
    }
    physical(action + " " + Math.round(u * 32767) + " " + Math.round(v * 32767));
  }

  private String shell(String command) throws Exception {
    try (var input = new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command));
        var output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096]; int count;
      while ((count = input.read(buffer)) >= 0) {
        if (count == 0) continue;
        require(output.size() + count <= 1024 * 1024, "测试输出过大");
        output.write(buffer, 0, count);
      }
      return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private static void await(String message, BooleanSupplier check, long timeoutMs) {
    long deadline = SystemClock.elapsedRealtime() + timeoutMs;
    while (!check.getAsBoolean()) {
      require(SystemClock.elapsedRealtime() < deadline, message);
      SystemClock.sleep(20);
    }
  }

  private static void require(boolean valid, String message) {
    if (!valid) throw new AssertionError(message);
  }

  private static final class Outcome implements SharedInput.Completion {
    final CountDownLatch completed = new CountDownLatch(1);
    final AtomicInteger calls = new AtomicInteger();
    volatile boolean success;
    volatile String message;
    volatile boolean mainThread;
    @Override public void complete(boolean success, String message) {
      mainThread = Looper.myLooper() == Looper.getMainLooper();
      this.success = success; this.message = message;
      calls.incrementAndGet(); completed.countDown();
    }
    boolean await(String failure) throws InterruptedException {
      require(completed.await(10, TimeUnit.SECONDS), failure);
      require(mainThread, "按键完成回调没有回到主线程");
      return success;
    }
  }
}
