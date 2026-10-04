package app.luoxianlv.playback

import android.content.Context
import app.luoxianlv.hot.contract.ForegroundPolicy
import app.luoxianlv.library.SongRepository

/** 播放开关属于业务；前台登记时限与系统通知由稳定宿主负责。 */
class PlaybackServicePolicy(private val context: Context) : ForegroundPolicy {
    override fun shouldRun() =
        SongRepository(context).floatingEnabled && PlaybackConnection.isEnabled(context)

    override fun stopPlayback() {
        SongRepository(context).floatingEnabled = false
        PlaybackConnection.instance?.apply {
            stop()
            showFloating(false)
        }
    }
}
