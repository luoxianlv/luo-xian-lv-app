package app.luoxianlv.ensemble

import app.luoxianlv.library.NoteEvent
import app.luoxianlv.library.PlayMode
import app.luoxianlv.library.Song
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal fun digest(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal data class EnsemblePart(val name: String, val hash: String, val events: String)

internal data class EnsembleScore(
    val hash: String,
    val title: String,
    val durationUs: Long,
    val parts: List<EnsemblePart>,
) {
    companion object {
        fun read(bytes: ByteArray, hash: String): EnsembleScore {
            require(bytes.size <= 8 * 1024 * 1024 && digest(bytes) == hash) { "合奏谱包校验失败" }
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            require(root.getInt("schemaVersion") == 1)
            val duration = root.getLong("durationUs").also { require(it in 1..7_200_000_000) }
            val tracks = root.getJSONArray("tracks")
            require(tracks.length() in 1..32)
            val parts =
                (0 until tracks.length()).map { index ->
                    val track = tracks.getJSONObject(index)
                    val events = track.getString("events")
                    val expected = track.getString("sha256")
                    require(digest(events.toByteArray(Charsets.UTF_8)) == expected) { "声部校验失败" }
                    events(events, duration)
                    EnsemblePart(track.getString("name"), expected, events)
                }
            return EnsembleScore(hash, root.getString("title"), duration, parts)
        }

        fun events(text: String, durationUs: Long): List<NoteEvent> {
            require(text.length <= 8 * 1024 * 1024 && durationUs in 1..7_200_000_000)
            val notes = JSONArray(text)
            require(notes.length() in 1..100_000)
            val result = ArrayList<NoteEvent>(notes.length() * 2 + 1)
            var cursor = 0L
            for (index in 0 until notes.length()) {
                val note = notes.getJSONObject(index)
                val start = note.getLong("startUs")
                val end = note.getLong("endUs")
                val key = note.getInt("key")
                val mode = note.getInt("mode")
                require(
                    start >= cursor &&
                        end > start &&
                        end <= durationUs &&
                        key in 0..7 &&
                        mode in -1..1
                )
                if (start > cursor) result += NoteEvent.rest((start - cursor) / 500_000.0)
                result +=
                    NoteEvent(
                        key,
                        when (mode) {
                            -1 -> PlayMode.LOWER
                            1 -> PlayMode.RAISE
                            else -> PlayMode.NATURAL
                        },
                        (end - start) / 500_000.0,
                        halfTone = note.getBoolean("halfTone"),
                    )
                cursor = end
            }
            if (cursor < durationUs) result += NoteEvent.rest((durationUs - cursor) / 500_000.0)
            return result
        }

        fun sameScore(song: Song): String {
            var position = 0L
            val notes = JSONArray()
            song.events.forEach { note ->
                val end = position + (note.beats * 60_000_000 / song.bpm).toLong().coerceAtLeast(1)
                if (!note.rest)
                    notes.put(
                        JSONObject()
                            .put("startUs", position)
                            .put("endUs", end)
                            .put("key", note.keyIndex)
                            .put(
                                "mode",
                                when (note.mode) {
                                    PlayMode.LOWER -> -1
                                    PlayMode.RAISE -> 1
                                    else -> 0
                                },
                            )
                            .put("halfTone", note.halfTone || note.mode == PlayMode.SEMITONE)
                    )
                position = end
            }
            return JSONObject()
                .put("title", song.title)
                .put("bpm", song.bpm)
                .put("notes", notes)
                .toString()
        }
    }
}
