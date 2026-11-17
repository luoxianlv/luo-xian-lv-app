package app.luoxianlv.library

import android.util.Base64
import app.luoxianlv.BuildConfig
import java.io.IOException
import org.json.JSONObject

/** 前导静音超过 5 秒判定为旧版编译核心的缺陷产物（不会剪掉 MIDI 开头空白）， 编译时直接平移到 0，避免播放开头长时间没有点击。 */
const val MAX_LEAD_IN_US = 5_000_000L

data class RustCompiledMidi(
    val score: String,
    val bpm: Int,
    val noteCount: Int,
    /** 服务端编译核心版本（compile-midi 响应顶层字段）；非 MIDI 内容为空串。 */
    val midiCoreVersion: String = "",
)

/** 服务端 MIDI 编译：MIDI 字节在内存里直接发到 /api/compile-midi， 拿到事件时间线后换算成 NoteEvent，本地不再保留 MIDI 数据。 */
object RustMidiCompiler {
    private val baseUrl = BuildConfig.UPDATE_BASE_URL.trimEnd('/')

    suspend fun compile(
        token: String?,
        bytes: ByteArray,
        track: Int? = null,
        melody: Boolean = true,
    ): RustCompiledMidi {
        val request =
            JSONObject()
                .put("midi", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .put("melody", melody)
                .apply {
                    if (track != null) {
                        put("tracks", org.json.JSONArray().put(track))
                    } else {
                        put("tracks", org.json.JSONArray())
                    }
                }
        val root = JSONObject(postCompileJson(request.toString(), token))
        val bpm = root.optInt("bpm", 120).coerceIn(20, 400)
        val notes = root.optJSONArray("notes") ?: error("服务端没有返回音符")
        // 旧版编译核心不剪 MIDI 开头空白：首音符 onset 超过阈值时整段平移到 0。
        val leadIn = notes.getJSONObject(0).optLong("startUs")
        val shift = if (leadIn > MAX_LEAD_IN_US) leadIn else 0L
        val events = mutableListOf<NoteEvent>()
        var cursor = 0L
        for (i in 0 until notes.length()) {
            val note = notes.getJSONObject(i)
            val start = note.optLong("startUs") - shift
            val end = note.optLong("releaseUs", note.optLong("endUs")) - shift
            if (start > cursor) events += NoteEvent.rest((start - cursor) * bpm / 60_000_000.0)
            val mode =
                when (note.optString("mode")) {
                    "升调" -> PlayMode.RAISE
                    "降调" -> PlayMode.LOWER
                    else -> PlayMode.NATURAL
                }
            events +=
                NoteEvent(
                    note.optInt("key", 0),
                    mode,
                    ((end - start).coerceAtLeast(1L)) * bpm / 60_000_000.0,
                    halfTone = note.optBoolean("halfTone", false),
                )
            cursor = end
        }
        require(events.isNotEmpty()) { "服务端没有可播放音符" }
        return RustCompiledMidi(
            ScoreParser.format(events, bpm),
            bpm,
            notes.length(),
            root.optString("midiCoreVersion"),
        )
    }

    private suspend fun postCompileJson(body: String, token: String?): String {
        return try {
            app.luoxianlv.platform.BusinessNetwork.current()
                .bytes("$baseUrl/api/compile-midi", token, body, readTimeoutMs = 15_000)
                .toString(Charsets.UTF_8)
        } catch (e: app.luoxianlv.platform.HttpFailure) {
            throw e
        } catch (e: IOException) {
            throw IOException("网络连接失败，请检查网络后重试", e)
        }
    }
}
