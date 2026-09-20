package app.luoxianlv.core.harmonica
import android.util.Base64
import app.luoxianlv.BuildConfig
import app.luoxianlv.core.score.NoteEvent
import app.luoxianlv.core.score.PlayMode
import app.luoxianlv.core.score.ScoreParser
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class RustCompiledMidi(
    val score: String,
    val bpm: Int,
    val noteCount: Int,
)

/** 服务端 MIDI 编译：MIDI 字节在内存里直接发到 /api/compile-midi，
 * 拿到事件时间线后换算成 NoteEvent，本地不再保留 MIDI 数据。 */
object RustMidiCompiler {
    private val baseUrl = BuildConfig.UPDATE_BASE_URL.trimEnd('/')

    fun compile(
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
        val events = mutableListOf<NoteEvent>()
        var cursor = 0L
        for (i in 0 until notes.length()) {
            val note = notes.getJSONObject(i)
            val start = note.optLong("startUs")
            val end = note.optLong("releaseUs", note.optLong("endUs"))
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
        return RustCompiledMidi(ScoreParser.format(events, bpm), bpm, notes.length())
    }

    private fun postCompileJson(
        body: String,
        token: String?,
    ): String {
        val connection =
            try {
                (URL("$baseUrl/api/compile-midi").openConnection() as HttpURLConnection).apply {
                    app.luoxianlv.update.ClientVersion.attach(this)
                    connectTimeout = 8000
                    readTimeout = 15000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
                }
            } catch (e: IOException) {
                throw IOException("网络连接失败，请检查网络后重试", e)
            }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8).use { it?.readText().orEmpty() }
            if (status !in 200..299) {
                val message = runCatching { JSONObject(text).optString("message") }.getOrDefault("").ifBlank { "HTTP $status" }
                error(message)
            }
            return text
        } catch (e: IOException) {
            throw IOException("网络连接失败，请检查网络后重试", e)
        } finally {
            connection.disconnect()
        }
    }
}
