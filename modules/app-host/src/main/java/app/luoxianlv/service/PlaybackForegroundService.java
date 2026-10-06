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
import app.luoxianlv.host.Bootstrap;
import app.luoxianlv.host.R;
import app.luoxianlv.hot.NativePlaybackHost;
import app.luoxianlv.hot.ForegroundStopper;
import app.luoxianlv.hot.contract.NativePlaybackSession;
import app.luoxianlv.hot.contract.ForegroundPolicy;
import app.luoxianlv.hot.contract.HostDiagnostics;

/** 系统前台承诺由 Java 宿主兑现，通知创建不得等待业务加载或谱面准备。 */
public final class PlaybackForegroundService extends Service {
  public static final String CHANNEL = app.luoxianlv.hot.contract.PlaybackBridge.FOREGROUND_CHANNEL;
  private static final int ID = 1201;
  private static final String STOP = "app.luoxianlv.STOP_FLOATING_PLAYER";
  private static final String START = "app.luoxianlv.START_FLOATING_PLAYER";
  private static final String START_AT = "playbackStartAtNanos";
  private static PlaybackForegroundService instance;
  private static boolean startPending;
  private static boolean stopRequested;
  private static long lastStopAt;
  private boolean foreground;
  private int lastStartId;
  private ForegroundPolicy policy;
  private ForegroundStopper stopping;
  private PlaybackHost playback;
  private boolean closingPlayback;
  private AutoCloseable preparation;

  /** 来源由整组事务切换；常驻服务不能一直保留旧业务策略的加载器。 */
  public static void businessChanged() {
    if (instance != null) instance.policy = null;
  }

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
    if (stopping != null) stopping.cancel();
    // 停止命令、重启的绑定实例也先登记，不能只在 onCreate 中调用。
    promoteToForeground();
    foreground = true;
    lastStartId = startId;
    startPending = false;
    if (intent != null && START.equals(intent.getAction())) {
      // 请求时间来自同一启动时钟；迟到的旧启动不能覆盖用户随后发出的关闭。
      stopRequested = intent.getLongExtra(START_AT, 0) <= lastStopAt;
    }
    if (intent != null && STOP.equals(intent.getAction())) {
      stopRequested = true;
      lastStopAt = android.os.SystemClock.elapsedRealtimeNanos();
    }
    closePreparation();
    Preparation waiting = new Preparation(intent, startId);
    preparation = waiting;
    waiting.awaitReady();
    return START_STICKY;
  }

  /** 每条启动命令独立等待；同步 ready 回调也不能覆盖延后重试的取消句柄。 */
  private final class Preparation implements AutoCloseable {
    private final Intent intent;
    private final int startId;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable retry = this::evaluate;
    private AutoCloseable readiness;
    private boolean closed;

    Preparation(Intent intent, int startId) {
      this.intent = intent;
      this.startId = startId;
    }

    private boolean current() {
      return !closed && instance == PlaybackForegroundService.this && lastStartId == startId;
    }

    void awaitReady() {
      if (!current()) return;
      AutoCloseable registered = Bootstrap.ready(this::evaluate);
      if (closed) {
        try {
          registered.close();
        } catch (Exception failure) {
          HostDiagnostics.log(Log.WARN, "播放服务", "取消播放准备监听失败", failure);
        }
      } else readiness = registered;
    }

    private void evaluate() {
      if (!current()) return;
      try {
        boolean created =
            Bootstrap.initialCreation(
                () -> {
                  if (!current()) return;
                  if (policy == null) {
                    var selected = Bootstrap.source();
                    policy =
                        selected.factory.foreground(
                            selected.prepared.context(PlaybackForegroundService.this));
                  }
                  diagnostic("已进入前台：启动序号=" + startId + "，停止请求=" + stopRequested);
                  if (intent != null && STOP.equals(intent.getAction())) {
                    stopRequested = true;
                    policy.stopPlayback();
                  }
                   if (stopRequested || !policy.shouldRun()) stopIfLatest();
                   else ensurePlayback();
                });
        if (!created && current()) main.postDelayed(retry, 16);
        else close();
      } catch (Throwable failure) {
        HostDiagnostics.log(Log.ERROR, "播放服务", "播放业务不可用，停止本次前台服务", failure);
        try {
          Bootstrap.componentFailed(failure);
        } catch (Throwable stopping) {
          failure.addSuppressed(stopping);
        }
        stopIfLatest();
        close();
      }
    }

    @Override
    public void close() {
      if (closed) return;
      closed = true;
      main.removeCallbacks(retry);
      if (readiness != null) {
        try {
          readiness.close();
        } catch (Exception failure) {
          HostDiagnostics.log(Log.WARN, "播放服务", "取消播放准备监听失败", failure);
        }
        readiness = null;
      }
    }
  }

  private void closePreparation() {
    if (preparation == null) return;
    try {
      preparation.close();
    } catch (Exception failure) {
      HostDiagnostics.log(Log.WARN, "播放服务", "取消播放准备监听失败", failure);
    }
    preparation = null;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  private void stopIfLatest() {
    if (stopping == null) {
      stopping = new ForegroundStopper(this, startId -> {
        if (instance != this || lastStartId != startId) return;
        closePreparation();
        closePlayback();
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        diagnostic("已停止：启动序号=" + startId);
      });
    }
    stopping.stop(lastStartId);
  }

  private void ensurePlayback() {
    if (playback == null) {
      playback = new PlaybackHost();
      playback.open();
    } else {
      var port = app.luoxianlv.hot.contract.PlaybackBridge.current();
      if (port != null) {
        android.os.Bundle arguments = new android.os.Bundle();
        arguments.putBoolean("enabled", true);
        port.command("showFloating", arguments);
      }
    }
  }

  private void checkPlaybackDemand() {
    if (playback != null && !closingPlayback && policy != null && (stopRequested || !policy.shouldRun())) stop();
  }

  private void closePlayback() {
    PlaybackHost previous = playback;
    playback = null;
    if (previous == null) return;
    closingPlayback = true;
    try { previous.close(); } finally { closingPlayback = false; }
  }

  private void diagnostic(String message) {
    HostDiagnostics.log(Log.INFO, "播放服务", message, null);
  }

  @Override
  public void onDestroy() {
    if (stopping != null) stopping.cancel();
    closePreparation();
    if (instance == this) instance = null;
    closePlayback();
    foreground = false;
    policy = null;
    super.onDestroy();
  }

  /** 与无障碍和 Activity 生命周期一样，仅在主线程调用。 */
  public static void start(Context context) {
    stopRequested = false;
    boolean restart = instance != null && instance.stopping != null && instance.stopping.cancel();
    if (startPending || (instance != null && instance.foreground && !restart)) return;
    startPending = true;
    try {
      context.startForegroundService(new Intent(context, PlaybackForegroundService.class)
          .setAction(START).putExtra(START_AT, android.os.SystemClock.elapsedRealtimeNanos()));
    } catch (RuntimeException failure) {
      startPending = false;
      HostDiagnostics.log(Log.WARN, "播放服务", "系统拒绝启动播放前台服务", failure);
    }
  }

  public static void stop() {
    stopRequested = true;
    lastStopAt = android.os.SystemClock.elapsedRealtimeNanos();
    // 不用 stopService 抢在 onStartCommand 前销毁，先登记前台再兑现关闭。
    if (!startPending && instance != null) instance.stopIfLatest();
  }

  /** 业务来源和热更租约集中在普通服务；系统无障碍连接不持有业务实例。 */
  private final class PlaybackHost extends NativePlaybackHost {
    private Bootstrap.Source source;

    PlaybackHost() { super(PlaybackForegroundService.this); }

    @Override protected AutoCloseable whenPlaybackReady(Runnable ready) { return Bootstrap.ready(ready); }
    @Override protected boolean initialCreation(Runnable create) { return Bootstrap.initialCreation(create); }
    @Override protected NativePlaybackSession createPlaybackSession() {
      source = Bootstrap.source();
      return source.factory.playback();
    }
    @Override protected Context playbackContext() {
      Context value = source.prepared.context(PlaybackForegroundService.this);
      source = null;
      return value;
    }
    @Override protected void playbackOpened() {
      Bootstrap.playbackOpened(this);
      checkPlaybackDemand();
    }
    @Override protected void playbackClosed() { Bootstrap.playbackClosed(this); }
    @Override protected void playbackFailed(Throwable failure) { Bootstrap.componentFailed(failure); }
    @Override protected void playbackUsageChanged() {
      Bootstrap.usageChanged();
      checkPlaybackDemand();
    }
    @Override protected void foregroundRequested(boolean enabled) {
      if (enabled) start(PlaybackForegroundService.this);
      else if (!closingPlayback) stop();
    }
  }
}
