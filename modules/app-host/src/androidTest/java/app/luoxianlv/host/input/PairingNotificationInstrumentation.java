package app.luoxianlv.host.input;

import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import app.luoxianlv.hot.contract.PlaybackBridge;
import app.luoxianlv.hot.contract.SharedInput;
import java.util.function.BooleanSupplier;

/** 检查真实配对通知；有临时配对码时，将原 PendingIntent 交给独立测试进程。 */
public final class PairingNotificationInstrumentation extends Instrumentation {
  private static final int NOTIFICATION = 7212;
  private static final String CHANNEL = "input-wireless-pairing";
  private static final String SNAPSHOT = "pairing-notification-test";
  private String code;
  private long delayMs;
  private boolean preparePairing, restorePairing, keepPairing;
  private boolean strictChannelDefaults;

  @Override public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    code = arguments == null ? null : arguments.getString("code");
    preparePairing = arguments != null && "true".equals(arguments.getString("preparePairing"));
    restorePairing = arguments != null && "true".equals(arguments.getString("restorePairing"));
    keepPairing = arguments != null && "true".equals(arguments.getString("keepPairing"));
    strictChannelDefaults = arguments != null && "true".equals(arguments.getString("strictChannelDefaults"));
    String delay = arguments == null ? null : arguments.getString("delayMs");
    try { delayMs = delay == null ? 15000 : Long.parseLong(delay); }
    catch (NumberFormatException ignored) { delayMs = -1; }
    start();
  }

  @Override public void onStart() {
    Bundle result = new Bundle();
    try {
      check(getTargetContext().getPackageName().endsWith(".debug"), "仅允许运行 Debug 宿主");
      check(delayMs >= 1000 && delayMs <= 60000, "延迟需在一至六十秒之间");
      check(code == null || validCode(code), "临时配对码必须为六位数字");
      check(!preparePairing || !restorePairing, "准备和恢复不能同时执行");
      check(!keepPairing || preparePairing, "保留配对标记仅用于准备测试");
      check((!preparePairing && !restorePairing) || code == null, "准备或恢复不接受临时配对码");
      if (restorePairing) {
        restorePairedMark();
        result.putString("stream", "已恢复测试前 paired 标记；未改身份、通知令牌或连接意愿。\n");
        finish(0, result);
        return;
      }
      if (preparePairing) preparePairing();
      NotificationManager manager = getTargetContext().getSystemService(NotificationManager.class);
      check(manager != null && manager.areNotificationsEnabled(), "宿主通知未开启");
      if (preparePairing) await("真实配对前台通知没有启动", () -> activeNotification(manager) != null);
      NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
      check(channel != null, "配对通知通道不存在");
      boolean hasSound = channel.getSound() != null;
      boolean showBadge = channel.canShowBadge();
      boolean canBubble = Build.VERSION.SDK_INT >= 29 && channel.canBubble();
      boolean defaultsMatch = channel.getImportance() == NotificationManager.IMPORTANCE_HIGH
          && !hasSound && !showBadge && !canBubble;
      result.putInt("channelImportance", channel.getImportance());
      result.putBoolean("channelHasSound", hasSound);
      result.putBoolean("channelShowBadge", showBadge);
      result.putBoolean("channelCanBubble", canBubble);
      result.putBoolean("channelDefaultsMatch", defaultsMatch);
      result.putBoolean("strictChannelDefaults", strictChannelDefaults);
      check(channel.getImportance() != NotificationManager.IMPORTANCE_NONE, "配对通知通道已被用户关闭");
      // 既有通道允许保留用户偏好；只在显式要求时检验新安装的默认配置。
      if (strictChannelDefaults) {
        check(channel.getImportance() == NotificationManager.IMPORTANCE_HIGH, "配对通道不是高重要性");
        check(!hasSound && !showBadge, "配对通道声音或角标配置不符");
        check(!canBubble, "配对通道不应允许气泡");
      }

      Notification notification = activeNotification(manager);
      check(notification != null, "没有正在搜索或等待输入的真实配对通知");
      check(CHANNEL.equals(notification.getChannelId()), "真实通知使用了错误通道");
      CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
      boolean found = "已找到配对服务".contentEquals(title == null ? "" : title);
      boolean searching = "正在搜索配对服务".contentEquals(title == null ? "" : title);
      check(found || searching, "当前通知不处于搜索或等待输入状态");
      check(notification.actions != null && notification.actions.length == 1, "配对通知应只有一个操作");
      Notification.Action action = notification.actions[0];
      PendingIntent pending = action.actionIntent;
      check(pending != null
          && getTargetContext().getPackageName().equals(pending.getCreatorPackage())
          && pending.getCreatorUid() == Process.myUid(), "通知操作不是宿主签发的真实操作");
      if (searching) {
        check("停止搜索".contentEquals(action.title), "搜索通知没有官方停止操作");
        check(pending.isService(), "停止搜索操作不是服务入口");
        check(action.getRemoteInputs() == null || action.getRemoteInputs().length == 0,
            "搜索通知不应提前接受配对码");
        check(code == null, "尚未发现配对服务，未提交临时配对码");
        result.putString("stream", "真实搜索通知：通道、标题和停止操作通过；未尝试配对。\n");
      } else {
        RemoteInput[] fields = action.getRemoteInputs();
        check(fields != null && fields.length == 1, "等待输入通知没有唯一的 RemoteInput");
        check("paring_code".equals(fields[0].getResultKey()), "RemoteInput 未使用上游配对键");
        check("配对码".contentEquals(fields[0].getLabel()) && fields[0].getAllowFreeFormInput(),
            "配对码输入框配置不符");
        check("输入配对码".contentEquals(action.title) && !action.getAllowGeneratedReplies(),
            "配对码操作文案或自动回复配置不符");
        check(pending.isForegroundService(), "配对回复未使用前台服务 PendingIntent");
        check(Build.VERSION.SDK_INT < 31 || !pending.isImmutable(), "配对回复 PendingIntent 不可填入 RemoteInput");
        if (code == null) {
          result.putString("stream", "真实等待输入通知：通道、上游输入键及前台服务操作通过；未尝试配对。\n");
        } else {
          String testPackage = getContext().getPackageName();
          check(!testPackage.equals(getTargetContext().getPackageName()), "测试包不能与宿主同包");
          Intent relay = new Intent().setComponent(new ComponentName(testPackage,
              PairingNotificationRelayActivity.class.getName()))
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
              .putExtra(PairingNotificationRelayActivity.EXTRA_PENDING, pending)
              .putExtra(PairingNotificationRelayActivity.EXTRA_FIELDS, fields)
              .putExtra(PairingNotificationRelayActivity.EXTRA_CODE, code)
              .putExtra(PairingNotificationRelayActivity.EXTRA_DELAY, delayMs)
              .putExtra(PairingNotificationRelayActivity.EXTRA_SOURCE_PACKAGE, getTargetContext().getPackageName())
              .putExtra(PairingNotificationRelayActivity.EXTRA_SOURCE_PID, Process.myPid());
          getContext().startActivity(relay);
          result.putInt("sourcePid", Process.myPid());
          result.putString("relayPackage", testPackage);
          result.putLong("delayMs", delayMs);
          result.putString("stream", "真实通知操作已交给独立测试 Activity。请读取测试包内部结果确认已接收，再终止宿主 PID；不要 force-stop。\n");
        }
      }
      finish(0, result);
    } catch (Throwable failure) {
      // Intent 与异常消息可能携带临时输入；只输出失败类型及本测试自己的断言。
      String reason = failure instanceof AssertionError ? failure.getMessage() : failure.getClass().getSimpleName();
      result.putString("stream", "配对通知验证失败：" + reason + "\n");
      finish(1, result);
    } finally {
      code = null;
    }
  }

  private Notification activeNotification(NotificationManager manager) {
    for (StatusBarNotification active : manager.getActiveNotifications())
      if (active.getId() == NOTIFICATION
          && getTargetContext().getPackageName().equals(active.getPackageName()))
        return active.getNotification();
    return null;
  }

  private void preparePairing() {
    check(Build.VERSION.SDK_INT >= 30, "无线配对需要 Android 11 或更新版本");
    await("输入桥尚未初始化", () -> SharedInput.current() != null
        && SharedInput.current().state().getBoolean("initialized"));
    SharedInput.Bridge bridge = SharedInput.current();
    var playback = PlaybackBridge.current();
    check(playback == null || !playback.query("state").getBoolean("playing"), "请先暂停演奏再准备配对测试");
    check(!bridge.state().getBoolean("active"), "触屏仍在使用，未开始配对测试");
    SharedPreferences wireless = getTargetContext().getSharedPreferences("input-wireless", Context.MODE_PRIVATE);
    SharedPreferences snapshot = getTargetContext().getSharedPreferences(SNAPSHOT, Context.MODE_PRIVATE);
    if (!snapshot.getBoolean("saved", false))
      check(snapshot.edit().putBoolean("pairedPresent", wireless.contains("paired"))
          .putBoolean("pairedValue", wireless.getBoolean("paired", false))
          .putBoolean("saved", true).commit(), "无法保存测试前配对标记");

    NotificationManager manager = getTargetContext().getSystemService(NotificationManager.class);
    check(manager != null, "无法读取宿主通知");
    bridge.command("disconnect", new Bundle());
    await("旧输入连接或配对通知尚未退出", () -> !bridge.state().getBoolean("connected")
        && !bridge.state().getBoolean("active") && activeNotification(manager) == null);
    if (!keepPairing)
      check(wireless.edit().putBoolean("paired", false).commit(), "无法清除临时配对标记");
    bridge.select(SharedInput.WIRELESS);
    await("尚未选中无线模式", () -> SharedInput.WIRELESS.equals(bridge.state().getString("mode")));
    getTargetContext().startActivity(new Intent()
        .setClassName(getTargetContext().getPackageName(), "app.luoxianlv.MainActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    waitForIdleSync();
    bridge.command("startPairing", new Bundle());
  }

  private void restorePairedMark() {
    SharedPreferences snapshot = getTargetContext().getSharedPreferences(SNAPSHOT, Context.MODE_PRIVATE);
    check(snapshot.getBoolean("saved", false), "没有待恢复的测试配对标记");
    SharedPreferences.Editor original = getTargetContext().getSharedPreferences("input-wireless", Context.MODE_PRIVATE).edit();
    if (snapshot.getBoolean("pairedPresent", false))
      original.putBoolean("paired", snapshot.getBoolean("pairedValue", false));
    else original.remove("paired");
    check(original.commit(), "无法恢复测试前配对标记");
    check(snapshot.edit().remove("saved").remove("pairedPresent").remove("pairedValue").commit(),
        "无法清理配对测试标记");
  }

  private static void await(String message, BooleanSupplier condition) {
    long deadline = SystemClock.elapsedRealtime() + 10000;
    while (!condition.getAsBoolean()) {
      check(SystemClock.elapsedRealtime() < deadline, message);
      SystemClock.sleep(50);
    }
  }

  static boolean validCode(String value) {
    if (value == null || value.length() != 6) return false;
    for (int i = 0; i < value.length(); i++)
      if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
    return true;
  }

  private static void check(boolean valid, String message) {
    if (!valid) throw new AssertionError(message);
  }
}
