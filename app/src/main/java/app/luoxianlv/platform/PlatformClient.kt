package app.luoxianlv.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import app.luoxianlv.BuildConfig
import app.luoxianlv.core.harmonica.RustCompiledMidi
import app.luoxianlv.core.harmonica.RustMidiCompiler
import app.luoxianlv.data.SessionStore
import app.luoxianlv.data.SongRepository
import app.luoxianlv.data.SyncApplyResult
import java.net.URL
import org.json.JSONObject

class PlatformClient(private val context: Context) {
    private val baseUrl = BuildConfig.UPDATE_BASE_URL.trimEnd('/')
    private val sessionStore = SessionStore(context)
    private val http = BackendClient(baseUrl, sessionStore)
    private val responses = PlatformResponseParser(baseUrl)
    private val oauth = ShushuLogin(context, baseUrl, http)
    private val licensedMidi = LicensedMidiDecoder { http.requestBytes(it, null) }

    private fun compileToken(explicit: String? = null): String? =
        explicit?.takeIf { it.isNotBlank() } ?: sessionStore.current()?.accessToken

    fun startShushuLogin(activity: Activity) = oauth.startShushuLogin(activity)

    fun finishShushuLogin(intent: Intent, onResult: (Result<LoginResult>) -> Unit) =
        oauth.finishShushuLogin(intent, onResult)

    fun checkHot(onResult: (Result<HotUpdate>) -> Unit) {
        background(onResult) {
            val json = JSONObject(http.get("$baseUrl/api/hot/current"))
            HotUpdate(json.optInt("contentVersion", 0), json)
        }
    }

    fun login(
        account: String,
        password: String,
        onResult: (Result<LoginResult>) -> Unit,
    ) {
        background(onResult) {
            val root =
                JSONObject(
                    http.postJson(
                        "$baseUrl/api/auth/login",
                        JSONObject().put("account", account).put("password", password).toString(),
                    )
                )
            LoginResult(parseAccountSession(root))
        }
    }

    fun profile(
        accessToken: String,
        onResult: (Result<JSONObject>) -> Unit,
    ) {
        background(onResult) {
            JSONObject(http.requestText("$baseUrl/api/auth/profile", accessToken))
        }
    }

    fun fetchLibrary(
        deviceId: String,
        accessToken: String,
        onResult: (Result<List<SyncedScore>>) -> Unit,
    ) {
        Thread {
            onResult(
                runCatching {
                    require(deviceId.isNotBlank()) { "设备标识不能为空" }
                    require(accessToken.isNotBlank()) { "登录凭证不能为空" }
                    val records = mutableListOf<SyncedScore>()
                    var cursor: String? = null
                    for (pageIndex in 0 until MAX_LIBRARY_PAGES) {
                        val device = java.net.URLEncoder.encode(deviceId, "UTF-8")
                        val cursorQuery =
                            cursor?.let { "&cursor=${java.net.URLEncoder.encode(it, "UTF-8")}" }
                                ?: ""
                        val payload =
                            http
                                .requestText(
                                    "$baseUrl/api/client/library?device_id=$device$cursorQuery",
                                    accessToken,
                                )
                                .trim()
                        val (items, nextCursor) = responses.parseLibraryPage(payload)
                        (0 until items.length())
                            .mapNotNull { index ->
                                responses.parseSyncedScore(items.optJSONObject(index))?.let { score
                                    ->
                                    if (
                                        score.notationText.isNotBlank() || score.contentUrl != null
                                    ) {
                                        enrichContent(score, accessToken)
                                    } else {
                                        score
                                    }
                                }
                            }
                            .also(records::addAll)
                        if (nextCursor == null || nextCursor == cursor) break
                        cursor = nextCursor
                    }
                    records
                }
            )
        }
            .start()
    }

    /** 拉取平台曲目并原子合并较新版本；结果回调在工作线程执行。 */
    fun fetchAndApplyLibrary(
        repository: SongRepository,
        deviceId: String,
        accessToken: String,
        onResult: (Result<SyncApplyResult>) -> Unit,
    ) {
        fetchLibrary(deviceId, accessToken) { result ->
            onResult(result.mapCatching(repository::applySyncedScores))
        }
    }

    /** 服务端 MIDI 编译核心版本：变化意味着本地缓存的编译结果需要批量重编。 */
    fun fetchMidiCoreVersion(onResult: (Result<String>) -> Unit) {
        background(onResult) {
            JSONObject(http.get("$baseUrl/api/midi-core-version"))
                .optString("midiCoreVersion")
                .ifBlank { error("服务端未返回编译核心版本") }
        }
    }

    /** 读取公开曲目，无需单点登录。 */
    fun fetchPublicScores(
        query: String = "",
        accessToken: String? = null,
        onResult: (Result<List<PlatformScore>>) -> Unit,
    ) {
        background(onResult) {
            val path =
                if (query.isBlank()) {
                    "/api/scores/featured"
                } else {
                    "/api/scores/search?q=${java.net.URLEncoder.encode(
                        query.trim(),
                        "UTF-8",
                    )}"
                }
            responses.parsePlatformScores(
                JSONObject(http.requestText("$baseUrl$path", accessToken))
            )
        }
    }

    data class ScorePage(val items: List<PlatformScore>, val nextPage: Int?, val total: Int)

    /** 新版服务端按页返回；兼容尚未升级的服务端返回完整列表。 */
    fun fetchLatestScores(
        accessToken: String? = null,
        page: Int = 1,
        onResult: (Result<ScorePage>) -> Unit,
    ) {
        background(onResult) {
            val root =
                JSONObject(
                    http.requestText(
                        "$baseUrl/api/scores/latest?page=$page&pageSize=30",
                        accessToken,
                    )
                )
            val items = responses.parsePlatformScores(root)
            ScorePage(
                items,
                root.optInt("nextPage", 0).takeIf { it > page },
                root.optInt("total", items.size),
            )
        }
    }

    /** 下载公开曲目的通用简谱文本。 */
    fun downloadPublicScore(
        id: String,
        onResult: (Result<String>) -> Unit,
    ) = downloadPublicScoreCompiled(id) { result -> onResult(result.map { it.score }) }

    /**
     * 与 [downloadPublicScore] 相同，但保留完整编译结果（含 bpm 与编译核心版本）， 供下载入库与批量重编使用。简谱文本没有 MIDI
     * 编译过程，midiCoreVersion 为空串。
     */
    fun downloadPublicScoreCompiled(
        id: String,
        onResult: (Result<RustCompiledMidi>) -> Unit,
    ) {
        background(onResult) {
            val bytes =
                http.requestBytes(
                    "$baseUrl/api/scores/${java.net.URLEncoder.encode(id, "UTF-8")}/download",
                    null,
                )
            if (bytes.looksLikeLicense()) {
                val root = JSONObject(bytes.toString(Charsets.UTF_8))
                val license = root.optJSONObject("data") ?: root
                val midi = licensedMidi.decode(license)
                // 解密得到的 MIDI 只在内存里编译，不落到磁盘。
                RustMidiCompiler.compile(compileToken(), midi, melody = true)
            } else if (bytes.startsWithMidi()) {
                RustMidiCompiler.compile(compileToken(), bytes, melody = true)
            } else {
                val text =
                    bytes.toString(Charsets.UTF_8).removePrefix("﻿").trim().also {
                        require(it.any(Char::isDigit)) { "谱面内容为空" }
                    }
                RustCompiledMidi(text, app.luoxianlv.core.score.ScoreParser.tempo(text, 120), 0)
            }
        }
    }

    private fun enrichContent(
        score: SyncedScore,
        accessToken: String,
    ): SyncedScore {
        if (score.notationText.isNotBlank() || score.contentUrl == null) return score
        return runCatching {
                val contentOrigin = URL(score.contentUrl)
                val platformOrigin = URL(baseUrl)
                val sameOrigin =
                    contentOrigin.protocol == platformOrigin.protocol &&
                        contentOrigin.host == platformOrigin.host &&
                        contentOrigin.port == platformOrigin.port
                val bytes =
                    http.requestBytes(score.contentUrl, if (sameOrigin) accessToken else null)
                if (
                    bytes.size >= 4 &&
                        bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x4d, 0x54, 0x68, 0x64))
                ) {
                    val rust =
                        RustMidiCompiler.compile(compileToken(accessToken), bytes, melody = true)
                    score.copy(notationText = rust.score, bpm = rust.bpm)
                } else {
                    val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
                    require(text.any { it.isDigit() }) { "谱面内容为空" }
                    score.copy(notationText = text)
                }
            }
            .getOrDefault(score)
    }

    private companion object {
        const val MAX_LIBRARY_PAGES = 100
    }
}
