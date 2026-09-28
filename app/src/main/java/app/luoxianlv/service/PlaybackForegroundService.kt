package app.luoxianlv.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.luoxianlv.MainActivity
import app.luoxianlv.R
import app.luoxianlv.data.SongRepository
import app.luoxianlv.debug.AppLog

class PlaybackForegroundService : Service() {
    private var foreground = false
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    private fun promoteToForeground() {
        if (Build.VERSION.SDK_INT >= 26)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(CHANNEL, "落弦律播放控制", NotificationManager.IMPORTANCE_LOW)
                        .apply { setShowBadge(false) }
                )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, PlaybackForegroundService::class.java).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        startForeground(
            ID,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle("落弦律")
                .setContentText("悬浮窗播放控制运行中")
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(0, "停止并关闭悬浮窗", stop)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 同一实例可能在停止完成前再次收到启动；每条命令都必须先兑现前台服务承诺。
        promoteToForeground()
        foreground = true
        lastStartId = startId
        startPending = false
        AppLog.i("播放服务", "已进入前台：启动序号=$startId，停止请求=$stopRequested")
        val repository = SongRepository(this)
        if (intent?.action == STOP) {
            stopRequested = true
            repository.floatingEnabled = false
            MusicAccessibilityService.instance?.apply {
                stop()
                showFloating(false)
            }
        }
        if (
            stopRequested ||
                !repository.floatingEnabled ||
                !MusicAccessibilityService.isEnabled(this)
        ) {
            stopIfLatest()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopIfLatest() {
        // 旧命令不能撤掉较新启动请求所需的通知。
        if (lastStartId != 0 && stopSelfResult(lastStartId)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            AppLog.i("播放服务", "已停止：启动序号=$lastStartId")
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        foreground = false
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "playback_controls"
        private const val ID = 1201
        private const val STOP = "app.luoxianlv.STOP_FLOATING_PLAYER"
        private var instance: PlaybackForegroundService? = null
        private var startPending = false
        private var stopRequested = false

        /** 与无障碍和 Activity 生命周期一样，仅在主线程调用。 */
        fun start(context: Context) {
            stopRequested = false
            if (startPending || instance?.foreground == true) return
            startPending = true
            try {
                context.startForegroundService(
                    Intent(context, PlaybackForegroundService::class.java)
                )
            } catch (error: RuntimeException) {
                startPending = false
                AppLog.w("播放服务", "系统拒绝启动播放前台服务", error)
            }
        }

        fun stop() {
            stopRequested = true
            // 不用 stopService 抢在 onStartCommand 前销毁服务，先登记前台再兑现关闭。
            if (!startPending) instance?.stopIfLatest()
        }
    }
}
