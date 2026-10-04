package app.luoxianlv.playback

import android.os.Bundle
import app.luoxianlv.library.Song

/** 明确的基础值协议，不序列化业务类，也不在页面线程解析谱面。 */
internal object PlaybackWire {
    fun song(song: Song) =
        Bundle().apply {
            putInt("schema", 1)
            putString("id", song.id)
            putString("title", song.title)
            putString("score", song.score)
            putInt("bpm", song.bpm)
            putString("source", song.source)
            putBoolean("builtIn", song.builtIn)
            putLong("contentVersion", song.contentVersion)
            putBoolean("synced", song.synced)
            putString("remoteId", song.remoteId)
            putString("coreVersion", song.coreVersion)
            putBoolean("needsFix", song.needsFix)
        }

    fun song(data: Bundle): Song {
        require(data.getInt("schema", 1) == 1) { "不支持的曲目消息版本" }
        return Song(
            id = data.getString("id").orEmpty(),
            title = data.getString("title").orEmpty(),
            score = data.getString("score").orEmpty(),
            bpm = data.getInt("bpm", 120),
            source = data.getString("source").orEmpty(),
            builtIn = data.getBoolean("builtIn"),
            contentVersion = data.getLong("contentVersion"),
            synced = data.getBoolean("synced"),
            remoteId = data.getString("remoteId").orEmpty(),
            coreVersion = data.getString("coreVersion").orEmpty(),
            needsFix = data.getBoolean("needsFix"),
        )
    }

    private fun display(value: DisplayState?) = value?.let {
        Bundle().apply {
            putInt("width", it.width)
            putInt("height", it.height)
            putInt("rotation", it.rotation)
        }
    }

    private fun display(data: Bundle?) = data?.let {
        DisplayState(it.getInt("width"), it.getInt("height"), it.getInt("rotation"))
    }

    fun diagnostics(value: PlaybackConnection.Diagnostics) =
        Bundle().apply {
            putBoolean("serviceEnabled", value.serviceEnabled)
            putBoolean("playing", value.playing)
            putBoolean("preparing", value.preparing)
            putString("songTitle", value.songTitle)
            putBundle("display", display(value.display))
            putBundle("playbackDisplay", display(value.playbackDisplay))
            putString("error", value.error)
            putString("gestureFailure", value.gestureFailure)
            putString("lastCoordinates", value.lastCoordinates)
        }

    fun diagnostics(data: Bundle) =
        PlaybackConnection.Diagnostics(
            serviceEnabled = data.getBoolean("serviceEnabled"),
            playing = data.getBoolean("playing"),
            preparing = data.getBoolean("preparing"),
            songTitle = data.getString("songTitle").orEmpty(),
            display = display(data.getBundle("display")),
            error = data.getString("error"),
            gestureFailure = data.getString("gestureFailure"),
            playbackDisplay = display(data.getBundle("playbackDisplay")),
            lastCoordinates = data.getString("lastCoordinates"),
        )
}
