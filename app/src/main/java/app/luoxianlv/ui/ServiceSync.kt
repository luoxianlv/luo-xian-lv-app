package app.luoxianlv.ui

import android.os.Handler
import android.os.Looper
import app.luoxianlv.business.playback.PlaybackConnection
import app.luoxianlv.data.Song
import app.luoxianlv.debug.AppLog

/** 曲库选择、导入和下载统一通过基础值协议同步曲目；未连接时跳过，谱面解析仍在后台。 */
fun syncSelectionToService(song: Song) {
    val select = Runnable {
        runCatching { PlaybackConnection.instance?.select(song) }
            .onFailure { AppLog.w("悬浮窗同步", "曲目已保存，但悬浮窗同步失败", it) }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) select.run()
    else Handler(Looper.getMainLooper()).post(select)
}
