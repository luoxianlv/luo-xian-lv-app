package app.luoxianlv.library

import android.content.Context
import app.luoxianlv.platform.SessionStore
import app.luoxianlv.platform.SyncedScore
import app.luoxianlv.shared.Kv
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class SongRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = Kv.of(context, "song_library")
    private val builtIns: List<Song> by lazy { loadBuiltIns() }

    init {
        // 清除旧测试版遗留的两倍速实验配置；没有新的用户设置时按 MIDI 原速播放。
        if (!prefs.getBoolean("speed_migrated_v2", false)) {
            prefs.edit().putFloat("speed", 1f).putBoolean("speed_migrated_v2", true).apply()
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
                )
                .mapNotNull { (id, title) ->
                    runCatching {
                        val score =
                            appContext.assets.open("builtin-scores/$id.txt").use {
                                it.readBytes().toString(Charsets.UTF_8)
                            }
                        Song(
                                id,
                                title,
                                score,
                                ScoreParser.tempo(score, 120).coerceIn(1, 999),
                                "MIDI · Rust 编译",
                                true,
                            )
                            .also { require(it.events.isNotEmpty()) { "内置谱面为空" } }
                    }
                        .getOrNull()
                }
        return rawBuiltIns + fallback
    }

    fun songs(): List<Song> {
        val saved = runCatching {
            JSONArray(prefs.getString("songs", "[]"))
        }
            .getOrDefault(JSONArray())
        val merged = linkedMapOf<String, Song>()
        // 用户删掉的内置谱面只是记进隐藏清单：内容随包发布，删不掉文件。
        val hidden = hiddenBuiltIns()
        builtIns.filterNot { it.id in hidden }.forEach { merged[it.id] = it }
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
                        remoteId = s.optString("remoteId", ""),
                        contentVersion = s.optLong("contentVersion", 0L),
                        coreVersion = s.optString("coreVersion", ""),
                        needsFix = s.optBoolean("needsFix", false),
                    )
                }
                    .getOrNull()
            }
        loaded.forEach { merged[it.id] = it }
        return merged.values.toList()
    }

    /** 合并 GET /api/client/library 的版本化内容；旧响应不得覆盖较新的本地副本。 */
    fun applySyncedScores(records: List<SyncedScore>): SyncApplyResult {
        val current = runCatching {
            JSONArray(prefs.getString("synced_songs", "[]"))
        }
            .getOrDefault(JSONArray())
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
            check(prefs.edit().putString("synced_songs", updated.toString()).commit()) {
                "保存同步谱库失败"
            }
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
        val array = runCatching {
            JSONArray(prefs.getString("hot_songs", "[]"))
        }
            .getOrDefault(JSONArray())
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
            }
                .getOrNull()
        }
    }

    fun add(
        title: String,
        score: String,
        bpm: Int,
        source: String,
        remoteId: String = "",
        coreVersion: String = "",
    ): Song {
        val song =
            Song(
                UUID.randomUUID().toString(),
                title,
                score,
                bpm,
                source,
                remoteId = remoteId,
                coreVersion = coreVersion,
            )
        require(song.events.isNotEmpty()) { "乐谱没有音符" }
        write(songs().filterNot { it.builtIn } + song)
        selectedId = song.id
        return song
    }

    /** 导入 MIDI：在内存里发给服务端编译，拿到简谱后 MIDI 字节直接丢弃， 不再写 filesDir/midi_sources 留作重编译缓存。 */
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
        // 内置谱面不在 prefs 的歌单里，写回歌单对它无效，必须单独记一条删除意图，
        // 否则 songs() 下次合并又会把它带回来。
        if (builtIns.any { it.id == id }) setHiddenBuiltIns(hiddenBuiltIns() + id)
        val synced = runCatching {
            JSONArray(prefs.getString("synced_songs", "[]"))
        }
            .getOrDefault(JSONArray())
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
        songs
            .filterNot { it.builtIn || it.synced }
            .forEach { s ->
                json.put(
                    JSONObject()
                        .put(
                            "id",
                            s.id,
                        )
                        .put(
                            "title",
                            s.title,
                        )
                        .put(
                            "score",
                            s.score,
                        )
                        .put(
                            "bpm",
                            s.bpm,
                        )
                        .put(
                            "source",
                            s.source,
                        )
                        .apply {
                            // 老记录没有这些字段：只写非默认值，保持 JSON 精简且向后兼容。
                            if (s.remoteId.isNotBlank()) put("remoteId", s.remoteId)
                            if (s.contentVersion > 0) put("contentVersion", s.contentVersion)
                            if (s.coreVersion.isNotBlank()) put("coreVersion", s.coreVersion)
                            if (s.needsFix) put("needsFix", true)
                        }
                )
            }
        check(prefs.edit().putString("songs", json.toString()).commit()) { "保存歌单失败" }
    }

    /** 用新版编译结果覆盖本地缓存（批量重编 / 手动修复共用）。 只动「本地歌单」里的记录；内置、热更、平台同步的谱面不走这条路。 返回是否找到了对应记录。 */
    fun updateCompiled(
        id: String,
        score: String,
        bpm: Int,
        coreVersion: String,
    ): Boolean =
        mutateSong(id) { item ->
            item.put("score", score)
            item.put("bpm", bpm.coerceIn(1, 999))
            if (coreVersion.isNotBlank()) item.put("coreVersion", coreVersion)
            item.put("needsFix", false)
        }

    /** 标记 / 清除「需要修复」；批量重编连续失败时置 true，修复成功后由 [updateCompiled] 清除。 */
    fun markNeedsFix(
        id: String,
        needsFix: Boolean,
    ): Boolean = mutateSong(id) { item -> item.put("needsFix", needsFix) }

    /** 按标题给缺 remoteId 的老记录补填平台 id（匹配不到的跳过，播放兜底已覆盖）。返回补填条数。 */
    fun backfillRemoteIds(candidates: Map<String, String>): Int {
        val array = runCatching {
            JSONArray(prefs.getString("songs", "[]"))
        }
            .getOrDefault(JSONArray())
        var filled = 0
        (0 until array.length()).forEach { index ->
            val item = array.optJSONObject(index) ?: return@forEach
            if (item.optString("remoteId").isNotBlank()) return@forEach
            val remoteId = candidates[item.optString("title").trim()] ?: return@forEach
            item.put("remoteId", remoteId)
            filled++
        }
        if (filled > 0) {
            check(prefs.edit().putString("songs", array.toString()).commit()) { "保存歌单失败" }
        }
        return filled
    }

    /** 定位并改写「本地歌单」里的一条记录，整条数组原子写回。 */
    private fun mutateSong(
        id: String,
        mutate: (JSONObject) -> Unit,
    ): Boolean {
        val array = runCatching {
            JSONArray(prefs.getString("songs", "[]"))
        }
            .getOrDefault(JSONArray())
        var found = false
        (0 until array.length()).forEach { index ->
            val item = array.optJSONObject(index)
            if (item != null && item.optString("id") == id) {
                mutate(item)
                found = true
            }
        }
        if (found) {
            check(prefs.edit().putString("songs", array.toString()).commit()) { "保存歌单失败" }
        }
        return found
    }

    private fun syncedSongs(): List<Song> {
        val array = runCatching {
            JSONArray(prefs.getString("synced_songs", "[]"))
        }
            .getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                val score = item.getString("score")
                // 写入同步库时已完整校验；读取列表不再重复解析所有谱面。
                require(score.isNotBlank())
                Song(
                    item.getString("id"),
                    item.optString("title", "未命名谱子"),
                    score,
                    item.optInt("bpm", 120).coerceIn(1, 999),
                    item.optString("source", "平台同步"),
                    contentVersion = item.optLong("contentVersion", 1L),
                    synced = true,
                )
            }
                .getOrNull()
        }
    }

    /** 被用户删除的内置谱面 id。内置内容随包发布，删除只能表达为「隐藏」。 */
    private fun hiddenBuiltIns(): Set<String> =
        parseHiddenBuiltIns(prefs.getString("hidden_builtins", "[]"))

    private fun setHiddenBuiltIns(ids: Set<String>) {
        prefs.edit().putString("hidden_builtins", hiddenBuiltInsJson(ids)).apply()
    }

    /** 已删除的内置谱面数量：界面据此决定是否显示「恢复内置示例」。 */
    fun hiddenBuiltInCount(): Int = builtIns.count { it.id in hiddenBuiltIns() }

    /** 恢复全部被删除的内置谱面。 */
    fun restoreBuiltIns() {
        prefs.edit().putString("hidden_builtins", "[]").apply()
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

    fun selected(): Song {
        val visible = songs()
        // 兜底时优先取仍然可见的曲目：内置谱面可能已被用户删除，
        // 直接回退 builtIns.first() 会把已删除的谱面重新载入服务。
        return visible.firstOrNull { it.id == selectedId }
            ?: visible.firstOrNull()
            ?: builtIns.first()
    }
}
