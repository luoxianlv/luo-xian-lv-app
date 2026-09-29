package app.luoxianlv.business.playback

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import app.luoxianlv.data.Song
import app.luoxianlv.hot.contract.PlaybackBridge
import app.luoxianlv.hot.contract.PlaybackPort
import app.luoxianlv.service.DisplayState

/** 页面侧适配器仅持有稳定宿主连接；返回的模型始终由本代业务代码构造。 */
class PlaybackConnection private constructor(private val port: PlaybackPort) {
    companion object {
        val instance
            get() = PlaybackBridge.current()?.let(::PlaybackConnection)

        fun isEnabled(context: Context) = PlaybackBridge.isEnabled(context)
    }

    data class Diagnostics(
        val serviceEnabled: Boolean,
        val playing: Boolean,
        val preparing: Boolean,
        val songTitle: String,
        val display: DisplayState?,
        val error: String?,
        val gestureFailure: String?,
        val playbackDisplay: DisplayState?,
        val lastCoordinates: String?,
    )

    private val state
        get() = port.query("state")

    val song
        get() = PlaybackWire.song(port.query("song"))

    val songId
        get() = state.getString("songId").orEmpty()

    val playing
        get() = state.getBoolean("playing")

    val preparing
        get() = state.getBoolean("preparing")

    val loadingSong
        get() = state.getBoolean("loadingSong")

    val waitingToPlay
        get() = state.getBoolean("waitingToPlay")

    val floatingVisible
        get() = state.getBoolean("floatingVisible")

    val error
        get() = state.getString("error")

    val speed
        get() = state.getFloat("speed", 1f)

    val positionMs
        get() = state.getLong("positionMs")

    val durationMs
        get() = state.getLong("durationMs")

    val modeLabel
        get() = state.getString("modeLabel").orEmpty()

    private fun send(action: String, arguments: Bundle = Bundle()) = port.command(action, arguments)

    fun select(song: Song) = send("select", PlaybackWire.song(song))

    fun play() = send("play")

    fun pause() = send("pause")

    fun stop() = send("stop")

    fun toggle() = send("toggle")

    fun reloadConfig() = send("reloadConfig")

    fun reloadExperimentalOptions() = send("reloadExperimentalOptions")

    fun refreshFloatingTheme() = send("refreshFloatingTheme")

    fun showFloating(enabled: Boolean) =
        send("showFloating", Bundle().apply { putBoolean("enabled", enabled) })

    fun seek(position: Long) = send("seek", Bundle().apply { putLong("position", position) })

    fun setSpeed(speed: Float) = send("setSpeed", Bundle().apply { putFloat("speed", speed) })

    fun screenBounds(): Rect =
        port.query("bounds").let { Rect(0, 0, it.getInt("width"), it.getInt("height")) }

    fun diagnostics(): Diagnostics = PlaybackWire.diagnostics(port.query("diagnostics"))
}
