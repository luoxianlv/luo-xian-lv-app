package app.luoxianlv.update

import android.content.Context
import app.luoxianlv.core.harmonica.RustCompiledMidi
import app.luoxianlv.data.Kv
import app.luoxianlv.data.Song
import app.luoxianlv.data.SongRepository
import app.luoxianlv.debug.AppLog
import app.luoxianlv.platform.PlatformClient
import app.luoxianlv.ui.AppEvents
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 回前台时按 [CHECK_INTERVAL_MS] 节流检查 /api/midi-core-version，并从 /api/scores/latest 回填缺失
 * remoteId。核心版本变化后回源重编 MIDI；每首最多尝试 [MAX_ATTEMPTS] 次，429 或网络错误退避，最终失败标记 needsFix。全部在后台执行，不使用常驻任务。
 */
object MidiCoreFixer {
    private const val TAG = "曲目修复"
    private const val STORE = "midi_core_fix"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_KNOWN_VERSION = "known_version"
    private const val CHECK_INTERVAL_MS = 5 * 60_000L
    private const val MAX_ATTEMPTS = 3
    private const val AWAIT_TIMEOUT_S = 40L

    /** 429 额外退避：限流时多等一会，不要把整批任务卡死。 */
    private const val RATE_LIMIT_BACKOFF_MS = 10_000L

    private val running = AtomicBoolean(false)

    /** 进入主界面 / 回前台时调用；本身轻量（一次 prefs 读取），可随意重复调用。 */
    fun kick(context: Context) {
        val app = context.applicationContext
        val prefs = Kv.of(app, STORE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        if (!running.compareAndSet(false, true)) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        Thread {
            try {
                runCheck(app)
            } catch (t: Throwable) {
                AppLog.w(TAG, "编译版本检查失败", t)
            } finally {
                running.set(false)
            }
        }
            .apply { isDaemon = true }
            .start()
    }

    /** 修复一首歌；[onDone] 在后台线程通知，界面更新由调用方切回主线程。 */
    fun fixSong(
        context: Context,
        song: Song,
        onDone: (Boolean) -> Unit,
    ) {
        val app = context.applicationContext
        Thread {
            val ok = runCatching {
                if (song.remoteId.isBlank()) return@runCatching false
                val version =
                    fetchVersionBlocking(app)
                        ?: Kv.of(app, STORE).getString(KEY_KNOWN_VERSION, "").orEmpty()
                recompileWithRetry(app, song, version)
            }
                .getOrDefault(false)
            onDone(ok)
        }
            .apply { isDaemon = true }
            .start()
    }

    private fun runCheck(app: Context) {
        val prefs = Kv.of(app, STORE)
        val remoteVersion = fetchVersionBlocking(app) ?: return
        // 无论版本是否变化都顺手回填一次 remoteId：新上架的歌随时可能匹配上。
        backfillRemoteIds(app)
        val known = prefs.getString(KEY_KNOWN_VERSION, "").orEmpty()
        if (remoteVersion == known) return

        val repository = SongRepository(app)
        // 只重编 MIDI 来源、带 remoteId、且不是用当前版本核心编译的歌。
        val targets =
            repository.songs().filter {
                it.isMidi && it.remoteId.isNotBlank() && it.coreVersion != remoteVersion
            }
        if (targets.isEmpty()) {
            prefs.edit().putString(KEY_KNOWN_VERSION, remoteVersion).apply()
            return
        }
        AppLog.i(TAG, "编译核心 $known -> $remoteVersion，批量重编 ${targets.size} 首")
        targets.forEach { song ->
            val ok = recompileWithRetry(app, song, remoteVersion)
            if (!ok) repository.markNeedsFix(song.id, true)
        }
        prefs.edit().putString(KEY_KNOWN_VERSION, remoteVersion).apply()
        AppEvents.notifyLibraryChanged()
    }

    /** 对一首歌执行「下载 → 重新编译 → 覆盖本地缓存」，失败退避重试至多 [MAX_ATTEMPTS] 次。 */
    private fun recompileWithRetry(
        app: Context,
        song: Song,
        version: String,
    ): Boolean {
        val repository = SongRepository(app)
        repeat(MAX_ATTEMPTS) { attempt ->
            val result = downloadCompiledBlocking(app, song.remoteId)
            result.onSuccess { compiled ->
                // 简谱文本回源没有重新编译（midiCoreVersion 为空），保留原版本号避免误标。
                val newVersion = compiled.midiCoreVersion.ifBlank { song.coreVersion }
                repository.updateCompiled(song.id, compiled.score, compiled.bpm, newVersion)
                AppLog.i(TAG, "重编完成《${song.title}》")
                return true
            }
            val message = result.exceptionOrNull()?.message.orEmpty()
            val rateLimited = message.startsWith("HTTP 429")
            AppLog.w(TAG, "重编失败《${song.title}》第 ${attempt + 1} 次：$message")
            if (attempt < MAX_ATTEMPTS - 1) {
                val backoff =
                    (attempt + 1) * 2_000L + if (rateLimited) RATE_LIMIT_BACKOFF_MS else 0L
                Thread.sleep(backoff)
            }
        }
        return false
    }

    /** 从 /api/scores/latest 拉曲库，按标题给本地老记录补填 remoteId。 */
    private fun backfillRemoteIds(app: Context) {
        runCatching {
            val candidates = linkedMapOf<String, String>()
            var page = 1
            // 只翻有限页：标题匹配只需要曲库覆盖面，不必拉完全站。
            while (page in 1..5) {
                val latch = CountDownLatch(1)
                var out: Result<PlatformClient.ScorePage>? = null
                PlatformClient(app).fetchLatestScores(page = page) { result ->
                    out = result
                    latch.countDown()
                }
                latch.await(AWAIT_TIMEOUT_S, TimeUnit.SECONDS)
                val scored = out?.getOrNull() ?: break
                scored.items.forEach { candidates.putIfAbsent(it.title.trim(), it.id) }
                page = scored.nextPage ?: break
            }
            if (candidates.isEmpty()) return
            val filled = SongRepository(app).backfillRemoteIds(candidates)
            if (filled > 0) {
                AppLog.i(TAG, "已回填 $filled 首曲目的 remoteId")
                AppEvents.notifyLibraryChanged()
            }
        }
            .onFailure { AppLog.w(TAG, "remoteId 回填失败", it) }
    }

    private fun fetchVersionBlocking(app: Context): String? {
        val latch = CountDownLatch(1)
        var out: Result<String>? = null
        PlatformClient(app).fetchMidiCoreVersion { result ->
            out = result
            latch.countDown()
        }
        latch.await(AWAIT_TIMEOUT_S, TimeUnit.SECONDS)
        return out?.getOrNull()
    }

    private fun downloadCompiledBlocking(
        app: Context,
        remoteId: String,
    ): Result<RustCompiledMidi> {
        val latch = CountDownLatch(1)
        var out: Result<RustCompiledMidi> = Result.failure(java.io.IOException("修复请求超时"))
        PlatformClient(app).downloadPublicScoreCompiled(remoteId) { result ->
            out = result
            latch.countDown()
        }
        latch.await(AWAIT_TIMEOUT_S, TimeUnit.SECONDS)
        return out
    }
}
