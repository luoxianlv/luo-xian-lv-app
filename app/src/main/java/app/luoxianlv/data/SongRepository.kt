package app.luoxianlv.data
import android.content.Context
import app.luoxianlv.core.harmonica.RustMidiCompiler
import app.luoxianlv.core.score.NoteEvent
import app.luoxianlv.core.score.ScoreParser
import app.luoxianlv.update.SyncedScore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Song(
    val id: String,
    val title: String,
    val score: String,
    val bpm: Int,
    val source: String,
    val builtIn: Boolean = false,
    val contentVersion: Long = 0L,
    val synced: Boolean = false,
) {
    val events: List<NoteEvent> by lazy { ScoreParser.parse(score) }
    val durationMs: Long get() = (events.sumOf { it.beats } * 60000 / bpm).toLong()
    val noteCount: Int get() = events.count { !it.rest }

    /**
     * 是否由 MIDI 编译而来。
     *
     * 同时决定列表图标与「MIDI / 简谱」筛选归类：
     * source 的取值（"MIDI · Rust 编译" / "MIDI · 引擎编译" / "简谱" / "平台下载" …）
     * 都由本文件写入，所以判断规则也放在这里，界面不再自己拼字符串。
     */
    val isMidi: Boolean get() = source.startsWith("MIDI")
}

data class SyncApplyResult(
    val applied: Int,
    val skipped: Int,
    val failed: Int,
)

class SongRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val prefs = Kv.of(context, "song_library")
    private val builtIns: List<Song> by lazy { loadBuiltIns() }

    init {
        // Older test builds persisted the temporary 2x playback experiment.
        // Keep MIDI timing at real speed unless a later settings screen opts in.
        if (!prefs.getBoolean("speed_migrated_v2", false)) {
            prefs
                .edit()
                .putFloat("speed", 1f)
                .putBoolean("speed_migrated_v2", true)
                .apply()
        }
    }

    private fun loadBuiltIns(): List<Song> {
        val fallback = SongLibrary.builtIns
        // 内置曲谱在构建时已用 Rust core 离线编译成简谱文本随包发布，
        // 运行时不再做 MIDI 编译，弱网或未登录也能正常播放。
        val rawBuiltIns =
            listOf(
                "rain-love" to "雨爱",
                "spring-shadow" to "春日影（Cover CRYCHIC）",
                "night-sky" to "夜空中最亮的星",
                "phantom-listening" to "幻听",
            ).mapNotNull { (id, title) ->
                runCatching {
                    val score = appContext.assets.open("builtin-scores/$id.txt").use { it.readBytes().toString(Charsets.UTF_8) }
                    require(ScoreParser.parse(score).isNotEmpty()) { "内置谱面为空" }
                    Song(id, title, score, ScoreParser.tempo(score, 120).coerceIn(1, 999), "MIDI · Rust 编译", true)
                }.getOrNull()
            }
        return rawBuiltIns + fallback
    }

    fun songs(): List<Song> {
        val saved = runCatching { JSONArray(prefs.getString("songs", "[]")) }.getOrDefault(JSONArray())
        val merged = linkedMapOf<String, Song>()
        builtIns.forEach { merged[it.id] = it }
        hotSongs().forEach { merged[it.id] = it }
        syncedSongs().forEach { merged[it.id] = it }
        val loaded =
            (0 until saved.length()).mapNotNull { index ->
                runCatching {
                    val s = saved.getJSONObject(index)
                    Song(
                        s.getString("id"),
                        s.getString("title"),
                        s.getString("score"),
                        s.optInt("bpm", 120).coerceIn(1, 999),
                        s.optString("source", "简谱"),
                    )
                }.getOrNull()
            }
        loaded.forEach { merged[it.id] = it }
        return merged.values.toList()
    }

    /** Merge records downloaded from GET /api/client/library.  Content is
     * versioned; an older response never replaces a newer local copy. */
    fun applySyncedScores(records: List<SyncedScore>): SyncApplyResult {
        val current = runCatching { JSONArray(prefs.getString("synced_songs", "[]")) }.getOrDefault(JSONArray())
        val byId = linkedMapOf<String, JSONObject>()
        (0 until current.length()).forEach { index ->
            current.optJSONObject(index)?.let { item ->
                item.optString("id").takeIf { it.isNotBlank() }?.let { byId[it] = item }
            }
        }
        var applied = 0
        var skipped = 0
        var failed = 0
        records.forEach { record ->
            val notation = record.notationText.trim()
            if (record.id.isBlank() || notation.isBlank() || record.title.isBlank()) {
                failed++
                return@forEach
            }
            val version = record.contentVersion.coerceAtLeast(1L)
            val previous = byId[record.id]
            val previousVersion = previous?.optLong("contentVersion", 0L) ?: 0L
            if (previous != null && version <= previousVersion) {
                skipped++
                return@forEach
            }
            val valid = runCatching { ScoreParser.parse(notation).isNotEmpty() }.getOrDefault(false)
            if (!valid) {
                failed++
                return@forEach
            }
            byId[record.id] =
                JSONObject()
                    .put("id", record.id)
                    .put("title", record.title)
                    .put("score", notation)
                    .put("bpm", record.bpm.coerceIn(1, 999))
                    .put("source", "平台同步")
                    .put("contentVersion", version)
            applied++
        }
        if (applied > 0) {
            val updated = JSONArray()
            byId.values.forEach(updated::put)
            check(prefs.edit().putString("synced_songs", updated.toString()).commit()) { "保存同步谱库失败" }
        }
        return SyncApplyResult(applied, skipped, failed)
    }

    fun applyHotUpdate(payload: JSONObject): Boolean {
        val version = payload.optInt("contentVersion", 0)
        val current = prefs.getInt("hot_content_version", 0)
        if (version <= current) return false
        val songs = payload.optJSONArray("songs") ?: JSONArray()
        prefs
            .edit()
            .putString("hot_songs", songs.toString())
            .putInt("hot_content_version", version)
            .apply()
        return true
    }

    private fun hotSongs(): List<Song> {
        val array = runCatching { JSONArray(prefs.getString("hot_songs", "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                Song(
                    item.getString("id"),
                    item.getString("title"),
                    item.getString("score"),
                    item.optInt("bpm", 120),
                    item.optString("source", "热更"),
                    true,
                )
            }.getOrNull()
        }
    }

    fun add(
        title: String,
        score: String,
        bpm: Int,
        source: String,
    ): Song {
        val song = Song(UUID.randomUUID().toString(), title, score, bpm, source)
        require(song.events.isNotEmpty()) { "乐谱没有音符" }
        write(songs().filterNot { it.builtIn } + song)
        selectedId = song.id
        return song
    }

    /** 导入 MIDI：在内存里发给服务端编译，拿到简谱后 MIDI 字节直接丢弃，
     * 不再写 filesDir/midi_sources 留作重编译缓存。 */
    fun addMidi(
        title: String,
        bytes: ByteArray,
        track: Int? = null,
        melody: Boolean = true,
    ): Song {
        val id = UUID.randomUUID().toString()
        val token = SessionStore(appContext).current()?.accessToken
        val rust = RustMidiCompiler.compile(token, bytes, track, melody)
        val score = rust.score
        val bpm = rust.bpm
        val song =
            Song(
                id,
                title,
                score,
                bpm,
                "MIDI · 引擎编译",
            )
        write(songs().filterNot { it.builtIn } + song)
        selectedId = song.id
        return song
    }

    fun remove(id: String) {
        val synced = runCatching { JSONArray(prefs.getString("synced_songs", "[]")) }.getOrDefault(JSONArray())
        val remainingSynced = JSONArray()
        (0 until synced.length()).forEach { index ->
            val item = synced.optJSONObject(index)
            if (item != null && item.optString("id") != id) remainingSynced.put(item)
        }
        prefs.edit().putString("synced_songs", remainingSynced.toString()).apply()
        write(songs().filterNot { it.builtIn || it.synced || it.id == id })
    }

    private fun write(songs: List<Song>) {
        val json = JSONArray()
        songs.filterNot { it.builtIn || it.synced }.forEach { s ->
            json.put(
                JSONObject()
                    .put(
                        "id",
                        s.id,
                    ).put(
                        "title",
                        s.title,
                    ).put(
                        "score",
                        s.score,
                    ).put(
                        "bpm",
                        s.bpm,
                    ).put(
                        "source",
                        s.source,
                    ),
            )
        }
        check(prefs.edit().putString("songs", json.toString()).commit()) { "保存歌单失败" }
    }

    private fun syncedSongs(): List<Song> {
        val array = runCatching { JSONArray(prefs.getString("synced_songs", "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                val score = item.getString("score")
                require(ScoreParser.parse(score).isNotEmpty())
                Song(
                    item.getString("id"),
                    item.optString("title", "未命名谱子"),
                    score,
                    item.optInt("bpm", 120).coerceIn(1, 999),
                    item.optString("source", "平台同步"),
                    contentVersion = item.optLong("contentVersion", 1L),
                    synced = true,
                )
            }.getOrNull()
        }
    }

    var selectedId: String
        get() = prefs.getString("selected", "rain") ?: "rain"
        set(value) {
            prefs.edit().putString("selected", value).apply()
        }
    var floatingEnabled: Boolean
        get() = prefs.getBoolean("floating", true)
        set(value) {
            prefs.edit().putBoolean("floating", value).apply()
        }
    var speed: Float
        get() = prefs.getFloat("speed", 1f)
        set(value) {
            prefs.edit().putFloat("speed", value.coerceIn(.5f, 2f)).apply()
        }

    fun selected(): Song = songs().firstOrNull { it.id == selectedId } ?: builtIns.first()
}

fun timeLabel(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
