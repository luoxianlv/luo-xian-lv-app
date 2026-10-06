package app.luoxianlv.input;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

/**
 * 通知流程移植自 Shizuku Manager v13.6.0 AdbPairingService.kt，Apache-2.0。
 * 来源：https://github.com/RikkaApps/Shizuku/blob/2650830c5b099ae0dd34fedf614d4f592ca05d65/manager/src/main/java/moe/shizuku/manager/adb/AdbPairingService.kt
 * 适配：Java、落弦律后端与图标；先登记前台通知，再发现服务，保留有限会话与停止校验。
 */
public final class WirelessPairingService extends Service {
  static final String CHANNEL = "input-wireless-pairing";
  static final int NOTIFICATION = 7212;
  static final String RESULT = "paring_code";
  static final String PAIR_PORT = "pairingPort";
  private static volatile WirelessPairingService current;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final long session = SystemClock.elapsedRealtimeNanos();
  private final long deadline = SystemClock.elapsedRealtime() + 120000;
  private boolean cancel;
  private boolean timedOut;
  private boolean preserveResult;

  static void start(Context context, String action, Bundle arguments) {
    Intent intent = new Intent(context, WirelessPairingService.class).setAction(action);
    if (arguments != null) intent.putExtra("arguments", arguments);
    context.startForegroundService(intent);
  }

  static void complete(Context context) { context.stopService(new Intent(context, WirelessPairingService.class)); }

  static void prepareChannel(NotificationManager manager) {
    NotificationChannel channel = new NotificationChannel(CHANNEL, "无线调试配对", NotificationManager.IMPORTANCE_HIGH);
    channel.setSound(null, null);
    channel.setShowBadge(false);
    if (Build.VERSION.SDK_INT >= 29) channel.setAllowBubbles(false);
    manager.createNotificationChannel(channel);
  }

  private static Notification.Builder notification(Context context, String title) {
    return new Notification.Builder(context, CHANNEL)
        .setSmallIcon(app.luoxianlv.input.R.drawable.ic_music_note)
        .setContentTitle(title).setOnlyAlertOnce(true);
  }

  private static Notification.Action stopAction(Context context) {
    PendingIntent stop = PendingIntent.getService(context, NOTIFICATION + 1,
        new Intent(context, WirelessPairingService.class).setAction("stop"),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Action.Builder(null, "停止搜索", stop).build();
  }

  /** 与上游一致：端口随通知 PendingIntent 保存，冷进程回复不依赖先前的 NSD 对象。 */
  static boolean showInput(Context context, int port, String token) {
    if (port <= 0 || port > 65535) return false;
    Intent reply = new Intent(context, WirelessPairingService.class).setAction("reply")
        .putExtra(PAIR_PORT, port).putExtra("notificationToken", token);
    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
    PendingIntent pending = PendingIntent.getForegroundService(context, NOTIFICATION, reply, flags);
    RemoteInput field = new RemoteInput.Builder(RESULT).setLabel("配对码").build();
    Notification.Action action = new Notification.Action.Builder(null, "输入配对码", pending)
        .addRemoteInput(field).setAllowGeneratedReplies(false).build();
    return post(context, notification(context, "已找到配对服务").addAction(action).build());
  }

  static void working(Context context) {
    post(context, notification(context, "正在进行配对").build());
  }

  static void result(Context context, long expectedSession, boolean success, String text) {
    WirelessPairingService service = current;
    if (service == null || service.session != expectedSession) return;
    Runnable finish = () -> {
      if (current != service || service.cancel || service.session != expectedSession) return;
      service.preserveResult = true;
      service.stopForeground(STOP_FOREGROUND_REMOVE);
      post(context, notification(context, success ? "配对成功" : "配对失败")
          .setContentText(text).build());
      service.stopSelf();
    };
    service.handler.post(finish);
  }

  static void connected(Context context) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null) return;
    boolean exists = false;
    for (android.service.notification.StatusBarNotification value : manager.getActiveNotifications())
      exists |= value.getId() == NOTIFICATION;
    if (!exists) return;
    post(context, notification(context, "无线调试已连接")
        .setContentText("可以返回落弦律开始演奏").build());
  }

  private static boolean post(Context context, Notification value) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null) return false;
    prepareChannel(manager);
    try { manager.notify(NOTIFICATION, value); return true; }
    catch (SecurityException denied) { return false; }
  }

  @Override public void onCreate() { super.onCreate(); current = this; }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) { stopSelf(); return START_NOT_STICKY; }
    NotificationManager manager = getSystemService(NotificationManager.class);
    prepareChannel(manager);
    boolean connecting = "connect".equals(intent.getAction()) || "authorize".equals(intent.getAction());
    Notification notification = notification(this, "reply".equals(intent.getAction())
        ? "正在进行配对" : connecting ? "正在连接无线调试" : "正在搜索配对服务")
        .addAction(stopAction(this)).build();
    if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    else startForeground(NOTIFICATION, notification);
    if ("stop".equals(intent.getAction())) { cancel = true; stopSelf(startId); return START_NOT_STICKY; }
    if (SystemClock.elapsedRealtime() >= deadline) { cancel = true; timedOut = true; stopSelf(startId); return START_NOT_STICKY; }
    cancel = false;
    WirelessAdbBackend backend = WirelessAdbBackend.current();
    InputController controller = InputController.current();
    String selected = getSharedPreferences("input-mode", MODE_PRIVATE).getString("mode", "accessibility");
    if (backend == null || controller == null || !"wireless".equals(selected)) { stopSelf(); return START_NOT_STICKY; }
    Bundle arguments = intent.getBundleExtra("arguments");
    arguments = arguments == null ? new Bundle() : new Bundle(arguments);
    // 已停止的用户请求不能靠迟到的服务启动重新连接或突然打开系统设置。
    if (arguments.containsKey("foregroundCommandRevision")
        && !backend.foregroundCommandCurrent(arguments.getLong("foregroundCommandRevision"))) {
      stopSelf(startId);
      return START_NOT_STICKY;
    }
    if ("reply".equals(intent.getAction())) {
      if (!backend.notificationMatches(intent.getStringExtra("notificationToken"))) { stopSelf(); return START_NOT_STICKY; }
      Bundle result = RemoteInput.getResultsFromIntent(intent);
      CharSequence code = result == null ? null : result.getCharSequence(RESULT);
      if (code == null) { stopSelf(); return START_NOT_STICKY; }
      arguments.putString("code", code.toString().trim());
      int port = intent.getIntExtra(PAIR_PORT, -1);
      if (port <= 0 || port > 65535) { stopSelf(); return START_NOT_STICKY; }
      arguments.putInt("port", port);
    }
    arguments.putLong("foregroundSession", session);
    String action = intent.getAction();
    if ("startPairing".equals(action)) arguments.putBoolean("openSettingsAfterStart", true);
    controller.resumeWirelessFromUser();
    backend.command("pair".equals(action) || "reply".equals(action) ? "foregroundPair"
        : "connect".equals(action) || "authorize".equals(action) ? "foregroundConnect" : "foregroundDiscover", arguments);
    handler.removeCallbacksAndMessages(null);
    handler.postDelayed(() -> { cancel = true; timedOut = true; stopSelf(); }, deadline - SystemClock.elapsedRealtime());
    return START_NOT_STICKY;
  }

  @Override public void onTimeout(int startId, int type) { cancel = true; timedOut = true; stopSelf(); }
  @Override public void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    WirelessAdbBackend backend = WirelessAdbBackend.current();
    if (backend != null) {
      Bundle ended = new Bundle();
      ended.putLong("foregroundSession", session);
      ended.putBoolean("cancel", cancel);
      ended.putBoolean("timedOut", timedOut);
      ended.putBoolean("preserveNotification", preserveResult);
      backend.command("foregroundEnded", ended);
    }
    stopForeground(preserveResult ? STOP_FOREGROUND_DETACH : STOP_FOREGROUND_REMOVE);
    if (current == this) current = null;
    super.onDestroy();
  }
  @Override public IBinder onBind(Intent intent) { return null; }
}
