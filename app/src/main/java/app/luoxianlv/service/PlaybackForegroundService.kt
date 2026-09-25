package app.luoxianlv.service

import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.luoxianlv.R
import app.luoxianlv.MainActivity
import app.luoxianlv.data.SongRepository

class PlaybackForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        promoteToForeground()
    }

    private fun promoteToForeground() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "落弦律播放控制", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, PlaybackForegroundService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(ID, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_music_note).setContentTitle("落弦律")
            .setContentText("悬浮窗播放控制运行中").setOngoing(true)
            .setContentIntent(open).addAction(0, "停止并关闭悬浮窗", stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setPriority(NotificationCompat.PRIORITY_LOW).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repository = SongRepository(this)
        if (intent?.action == STOP) {
            repository.floatingEnabled = false
            MusicAccessibilityService.instance?.apply { stop(); showFloating(false) }
        }
        if (!repository.floatingEnabled || !MusicAccessibilityService.isEnabled(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val CHANNEL = "playback_controls"
        private const val ID = 1201
        private const val STOP = "app.luoxianlv.STOP_FLOATING_PLAYER"
    }
}
