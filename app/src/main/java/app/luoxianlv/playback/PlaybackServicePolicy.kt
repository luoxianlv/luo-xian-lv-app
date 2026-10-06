package app.luoxianlv.playback

import android.content.Context
import app.luoxianlv.hot.contract.ForegroundPolicy
import app.luoxianlv.hot.contract.SharedInput
import app.luoxianlv.library.SongRepository

/** 播放开关属于业务；前台登记时限与系统通知由稳定宿主负责。 */
class PlaybackServicePolicy(context: Context) : ForegroundPolicy {
    private val repository = SongRepository(context)

    override fun shouldRun() =
        repository.floatingEnabled &&
            runCatching {
                    SharedInput.current()?.state()?.getBoolean("overlayGranted", false) == true
                }
                .getOrDefault(false)

    override fun stopPlayback() {
        repository.floatingEnabled = false
        PlaybackConnection.instance?.apply {
            stop()
            showFloating(false)
        }
    }
}
