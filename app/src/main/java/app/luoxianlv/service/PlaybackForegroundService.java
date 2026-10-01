package app.luoxianlv.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import app.luoxianlv.MainActivity;
import app.luoxianlv.R;
import app.luoxianlv.business.playback.PlaybackServicePolicy;
import app.luoxianlv.hot.contract.ForegroundPolicy;
import app.luoxianlv.hot.contract.HostDiagnostics;

/** 系统前台承诺由 Java 宿主兑现，通知创建不得等待业务加载或谱面准备。 */
public final class PlaybackForegroundService extends Service {
  public static final String CHANNEL = app.luoxianlv.hot.contract.PlaybackBridge.FOREGROUND_CHANNEL;
  private static final int ID = 1201;
  private static final String STOP = "app.luoxianlv.STOP_FLOATING_PLAYER";
  private static PlaybackForegroundService instance;
  private static boolean startPending;
  private static boolean stopRequested;
  private boolean foreground;
  private int lastStartId;
  private ForegroundPolicy policy;

  @Override
  public void onCreate() {
    super.onCreate();
    instance = this;
  }

  private void promoteToForeground() {
    NotificationChannel channel =
        new NotificationChannel(CHANNEL, "落弦律播放控制", NotificationManager.IMPORTANCE_LOW);
    channel.setShowBadge(false);
    getSystemService(NotificationManager.class).createNotificationChannel(channel);
    int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
    PendingIntent open =
        PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
    PendingIntent stop =
        PendingIntent.getService(
            this, 1, new Intent(this, PlaybackForegroundService.class).setAction(STOP), flags);
    startForeground(
        ID,
        new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("落弦律")
            .setContentText("悬浮窗播放控制运行中")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(0, "停止并关闭悬浮窗", stop).build())
            .setCategory(Notification.CATEGORY_SERVICE)
            .build());
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    // 停止命令、重启的绑定实例也先登记，不能只在 onCreate 中调用。
    promoteToForeground();
    foreground = true;
    lastStartId = startId;
    startPending = false;
    try {
      if (policy == null) policy = new PlaybackServicePolicy(this);
      diagnostic("已进入前台：启动序号=" + startId + "，停止请求=" + stopRequested);
      if (intent != null && STOP.equals(intent.getAction())) {
        stopRequested = true;
        policy.stopPlayback();
      }
      if (stopRequested || !policy.shouldRun()) {
        stopIfLatest();
        return START_NOT_STICKY;
      }
      return START_STICKY;
    } catch (Throwable failure) {
      HostDiagnostics.log(Log.ERROR, "播放服务", "播放业务不可用，停止本次前台服务", failure);
      stopIfLatest();
      return START_NOT_STICKY;
    }
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  private void stopIfLatest() {
    // 旧命令不能撤掉较新启动请求所需的通知。
    if (lastStartId != 0 && stopSelfResult(lastStartId)) {
      stopForeground(STOP_FOREGROUND_REMOVE);
      foreground = false;
      diagnostic("已停止：启动序号=" + lastStartId);
    }
  }

  private void diagnostic(String message) {
    HostDiagnostics.log(Log.INFO, "播放服务", message, null);
  }

  @Override
  public void onDestroy() {
    if (instance == this) instance = null;
    foreground = false;
    policy = null;
    super.onDestroy();
  }

  /** 与无障碍和 Activity 生命周期一样，仅在主线程调用。 */
  public static void start(Context context) {
    stopRequested = false;
    if (startPending || (instance != null && instance.foreground)) return;
    startPending = true;
    try {
      context.startForegroundService(new Intent(context, PlaybackForegroundService.class));
    } catch (RuntimeException failure) {
      startPending = false;
      HostDiagnostics.log(Log.WARN, "播放服务", "系统拒绝启动播放前台服务", failure);
    }
  }

  public static void stop() {
    stopRequested = true;
    // 不用 stopService 抢在 onStartCommand 前销毁，先登记前台再兑现关闭。
    if (!startPending && instance != null) instance.stopIfLatest();
  }
}
