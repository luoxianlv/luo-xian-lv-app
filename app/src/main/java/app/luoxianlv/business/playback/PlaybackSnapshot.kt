package app.luoxianlv.business.playback

import android.os.Bundle
import app.luoxianlv.core.score.PlayMode
import app.luoxianlv.data.Song

/** 播放交接的基础值协议；默认偏好仍由会话在原来的时机读取。 */
internal class PlaybackSnapshot(private val state: Bundle?) {
    fun selectedSong(fallback: () -> Song): Song {
        if (state == null) return fallback()
        require(state.getInt("schema") == 1) { "播放状态版本不支持" }
        return PlaybackWire.song(requireNotNull(state.getBundle("song")) { "播放快照缺少曲目" })
    }

    fun speed(fallback: () -> Float): Float {
        val value = state?.getFloat("speed", 1f) ?: fallback()
        require(value.isFinite() && value in .5f..2f)
        return value
    }

    val mode
        get() = state?.getString("mode")?.let(PlayMode::valueOf) ?: PlayMode.NATURAL

    val position
        get() = state?.getLong("position") ?: 0L

    val halfToneOn
        get() = state?.getBoolean("half") ?: false

    fun fixedKeys(fallback: () -> Boolean) = state?.getBoolean("fixed") ?: fallback()

    val fixAttempted
        get() = state?.getBoolean("fixAttempted") ?: false

    fun floatingVisible(fallback: () -> Boolean) = state?.getBoolean("floating") ?: fallback()

    val floatingState
        get() = state?.getBundle("floatingState")

    val error
        get() = state?.getString("error")

    companion object {
        fun encode(
            song: Bundle,
            position: Long,
            speed: Float,
            mode: PlayMode,
            halfToneOn: Boolean,
            fixedKeys: Boolean,
            fixAttempted: Boolean,
            floatingVisible: Boolean,
            floatingState: Bundle,
            error: String?,
        ) =
            Bundle().apply {
                putInt("schema", 1)
                putBundle("song", song)
                putLong("position", position)
                putFloat("speed", speed)
                putString("mode", mode.name)
                putBoolean("half", halfToneOn)
                putBoolean("fixed", fixedKeys)
                putBoolean("fixAttempted", fixAttempted)
                putBoolean("floating", floatingVisible)
                putBundle("floatingState", floatingState)
                putString("error", error)
            }
    }
}
