package app.luoxianlv.update
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import app.luoxianlv.BuildConfig
import app.luoxianlv.core.harmonica.RustMidiCompiler
import app.luoxianlv.data.AccountSession
import app.luoxianlv.data.Kv
import app.luoxianlv.data.SessionStore
import app.luoxianlv.data.SongRepository
import app.luoxianlv.data.SyncApplyResult
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class HotUpdate(
    val contentVersion: Int,
    val payload: JSONObject,
)

/** A score returned by the platform library endpoint.  The optional fields
 * keep the client compatible with both the current demo API and the signed
 * content records used by production (`contentUrl`, `updatedAt`). */
data class SyncedScore(
    val id: String,
    val title: String,
    val contentVersion: Long,
    val notationText: String,
    val bpm: Int,
    val keySignature: String?,
    val contentUrl: String? = null,
    val updatedAt: String? = null,
)

data class PlatformScore(
    val id: String,
    val title: String,
    val author: String,
    val bpm: Int,
    val keySignature: String?,
    val notationText: String,
)

data class LoginResult(
    val session: AccountSession,
)

class UpdateManager(
    private val context: Context,
) {
    private val baseUrl = BuildConfig.UPDATE_BASE_URL.trimEnd('/')
    private val sessionStore = SessionStore(context)

    private fun compileToken(explicit: String? = null): String? =
        explicit?.takeIf { it.isNotBlank() } ?: sessionStore.current()?.accessToken

    fun startShushuLogin(activity: Activity) {
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.encodeToString(random, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val stateBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val state = Base64.encodeToString(stateBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val challenge =
            Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
        Kv
            .of(context, "oauth")
            .edit()
            .putString("state", state)
            .putString("verifier", verifier)
            .apply()
        val url =
            Uri
                .parse("https://shushu.fan/oauth/authorize")
                .buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", "shu_8d4eddbcb1e96ff01a8b52fb")
                .appendQueryParameter("redirect_uri", "https://luoxianlv.com/login/callback")
                .appendQueryParameter("scope", "openid profile")
                .appendQueryParameter("state", "app_$state")
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build()
        activity.startActivity(Intent(Intent.ACTION_VIEW, url))
    }

    fun finishShushuLogin(
        intent: Intent,
        onResult: (Result<LoginResult>) -> Unit,
    ) {
        val data = intent.data ?: return
        val code = data.getQueryParameter("code") ?: return
        val returnedState = data.getQueryParameter("state") ?: return
        val prefs = Kv.of(context, "oauth")
        if (returnedState != "app_${prefs.getString("state", null)}") return
        val verifier = prefs.getString("verifier", null) ?: return
        prefs.edit().clear().apply()
        background(onResult) {
            val root =
                JSONObject(
                    postJson(
                        "$baseUrl/api/auth/oauth/exchange",
                        JSONObject()
                            .put(
                                "code",
                                code,
                            ).put(
                                "state",
                                returnedState,
                            ).put("redirect_uri", "https://luoxianlv.com/login/callback")
                            .put("code_verifier", verifier)
                            .toString(),
                    ),
                )
            val access = root.optString("accessToken").ifBlank { root.optString("access_token") }
            require(access.isNotBlank()) { root.optString("message", "鼠鼠登录失败") }
            LoginResult(
                AccountSession(
                    access,
                    root.optString("refreshToken"),
                    root.optJSONObject("user")?.optString("nickname").orEmpty(),
                    System.currentTimeMillis() +
                        (root.optLong("expiresIn", 0L).takeIf { it > 0 } ?: root.optLong("expires_in", 30L * 24 * 3600)) * 1000L,
                ),
            )
        }
    }

    fun checkHot(onResult: (Result<HotUpdate>) -> Unit) {
        background(onResult) {
            val json = JSONObject(get("$baseUrl/api/hot/current"))
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
                JSONObject(postJson("$baseUrl/api/auth/login", JSONObject().put("account", account).put("password", password).toString()))
            LoginResult(parseAccountSession(root))
        }
    }

    fun register(
        username: String,
        email: String,
        password: String,
        code: String,
        nickname: String,
        onResult: (Result<LoginResult>) -> Unit,
    ) {
        background(onResult) {
            val body =
                JSONObject()
                    .put(
                        "username",
                        username,
                    ).put("email", email)
                    .put("password", password)
                    .put("code", code)
                    .put("nickname", nickname)
            val root = JSONObject(postJson("$baseUrl/api/auth/register", body.toString()))
            LoginResult(parseAccountSession(root))
        }
    }

    fun profile(
        accessToken: String,
        onResult: (Result<JSONObject>) -> Unit,
    ) {
        background(onResult) { JSONObject(requestText("$baseUrl/api/auth/profile", accessToken)) }
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
                        val cursorQuery = cursor?.let { "&cursor=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: ""
                        val payload = requestText("$baseUrl/api/client/library?device_id=$device$cursorQuery", accessToken).trim()
                        val (items, nextCursor) = parseLibraryPage(payload)
                        (0 until items.length())
                            .mapNotNull { index ->
                                parseSyncedScore(items.optJSONObject(index))?.let { score ->
                                    if (score.notationText.isNotBlank() ||
                                        score.contentUrl != null
                                    ) {
                                        enrichContent(score, accessToken)
                                    } else {
                                        score
                                    }
                                }
                            }.also(records::addAll)
                        if (nextCursor == null || nextCursor == cursor) break
                        cursor = nextCursor
                    }
                    records
                },
            )
        }.start()
    }

    /** Fetch platform records and atomically merge newer versions into the
     * local song library.  Callers receive the result on the worker thread. */
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

    /** Read public scores from the local platform without requiring SSO. */
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
            parsePlatformScores(JSONObject(requestText("$baseUrl$path", accessToken)))
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
            val root = JSONObject(requestText("$baseUrl/api/scores/latest?page=$page&pageSize=30", accessToken))
            val items = parsePlatformScores(root)
            ScorePage(items, root.optInt("nextPage", 0).takeIf { it > page }, root.optInt("total", items.size))
        }
    }

    private fun parsePlatformScores(root: JSONObject): List<PlatformScore> {
        val items = root.optJSONArray("items") ?: org.json.JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            if (id.isBlank()) return@mapNotNull null
            PlatformScore(
                id,
                item.optString(
                    "title",
                    "未命名谱子",
                ),
                item.optString("author", "落弦律用户"),
                item.optInt("bpm", 120).coerceIn(1, 999),
                item.optString("keySignature").ifBlank {
                    null
                },
                item.optString("notationText"),
            )
        }
    }

    /** Download the portable notation text for a public platform score. */
    fun downloadPublicScore(
        id: String,
        onResult: (Result<String>) -> Unit,
    ) {
        background(onResult) {
            val bytes = requestBytes("$baseUrl/api/scores/${java.net.URLEncoder.encode(id, "UTF-8")}/download", null)
            if (bytes.looksLikeLicense()) {
                val root = JSONObject(bytes.toString(Charsets.UTF_8))
                val license = root.optJSONObject("data") ?: root
                val midi = decryptLicensedMidi(license)
                // 解密得到的 MIDI 只在内存里编译，不落到磁盘。
                RustMidiCompiler.compile(compileToken(), midi, melody = true).score
            } else if (bytes.startsWithMidi()) {
                RustMidiCompiler.compile(compileToken(), bytes, melody = true).score
            } else {
                bytes
                    .toString(Charsets.UTF_8)
                    .removePrefix("\uFEFF")
                    .trim()
                    .also { require(it.any(Char::isDigit)) { "谱面内容为空" } }
            }
        }
    }

    /** 服务端编译自带 HTTP 超时，失败会抛出带说明的异常交给调用方展示。 */
    private fun resolve(value: String): String =
        if (value.startsWith("http://") ||
            value.startsWith("https://")
        ) {
            value
        } else {
            "$baseUrl/${value.trimStart('/')}"
        }

    private fun parseSyncedScore(item: JSONObject?): SyncedScore? {
        if (item == null) return null
        val id = firstString(item, "scoreId", "score_id", "id") ?: return null
        val title = firstString(item, "title", "name") ?: "未命名谱子"
        val notation = firstString(item, "notationText", "notation", "score", "content") ?: ""
        val version = firstLong(item, "contentVersion", "content_version", "version")
        val key = firstString(item, "keySignature", "key_signature")
        val contentUrl = firstString(item, "contentUrl", "content_url", "url")?.let(::resolve)
        return SyncedScore(
            id = id,
            title = title,
            contentVersion = version,
            notationText = notation,
            bpm = item.optInt("bpm", 120).coerceIn(1, 999),
            keySignature = key,
            contentUrl = contentUrl,
            updatedAt = firstString(item, "updatedAt", "updated_at"),
        )
    }

    private fun parseLibraryPage(payload: String): Pair<org.json.JSONArray, String?> {
        if (payload.startsWith("[")) return org.json.JSONArray(payload) to null
        val root = JSONObject(payload)
        val items = root.optJSONArray("items") ?: root.optJSONArray("scores") ?: org.json.JSONArray()
        val next = firstString(root, "nextCursor", "next_cursor", "cursor")
        return items to next
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
                    contentOrigin.host == platformOrigin.host && contentOrigin.port == platformOrigin.port
            val bytes = requestBytes(score.contentUrl, if (sameOrigin) accessToken else null)
            if (bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x4d, 0x54, 0x68, 0x64))) {
                val rust =
                    RustMidiCompiler.compile(compileToken(accessToken), bytes, melody = true)
                score.copy(notationText = rust.score, bpm = rust.bpm)
            } else {
                val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
                require(text.any { it.isDigit() }) { "谱面内容为空" }
                score.copy(notationText = text)
            }
        }.getOrDefault(score)
    }

    private fun firstString(
        item: JSONObject,
        vararg names: String,
    ): String? =
        names
            .asSequence()
            .mapNotNull { name -> item.optString(name, "").trim().ifBlank { null } }
            .firstOrNull()

    private fun firstLong(
        item: JSONObject,
        vararg names: String,
    ): Long =
        names
            .asSequence()
            .mapNotNull { name ->
                val raw = item.opt(name)
                when (raw) {
                    is Number -> raw.toLong()
                    is String -> raw.trim().toLongOrNull()
                    else -> null
                }
            }.firstOrNull { it > 0 } ?: 0L

    private fun get(url: String): String = requestText(url, null)

    private fun requestText(
        url: String,
        accessToken: String?,
    ): String = requestBytes(url, accessToken).toString(Charsets.UTF_8)

    private fun requestBytes(
        url: String,
        accessToken: String?,
        retryAuth: Boolean = true,
    ): ByteArray {
        val current = sessionStore.current()
        val token =
            if (!accessToken.isNullOrBlank() && current?.accessToken == accessToken && current.expiresAt > 0L &&
                current.expiresAt - System.currentTimeMillis() < 5 * 60 * 1000L
            ) {
                refreshSession(accessToken)?.accessToken ?: accessToken
            } else {
                accessToken
            }
        val connection = open(url)
        if (!token.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
        connection.connect()
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        if (status == 401 && retryAuth && !token.isNullOrBlank()) {
            connection.disconnect()
            val refreshed = refreshSession(token)
            if (refreshed != null) return requestBytes(url, refreshed.accessToken, false)
        }
        if (status !in 200..299) {
            val detail = bytes.toString(Charsets.UTF_8).take(160).trim()
            error(if (detail.isBlank()) "HTTP $status" else "HTTP $status: $detail")
        }
        return bytes
    }

    private fun refreshSession(previous: String): AccountSession? =
        runCatching {
            val root = JSONObject(postJsonWithAuth("$baseUrl/api/auth/refresh", previous))
            parseAccountSession(root).also { sessionStore.save(it) }
        }.getOrElse {
            sessionStore.clear()
            null
        }

    private fun postJsonWithAuth(
        url: String,
        token: String,
    ): String {
        val connection =
            open(url).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
            }
        connection.outputStream.use { it.write("{}".toByteArray()) }
        return readResponse(connection)
    }

    private fun postJson(
        url: String,
        body: String,
    ): String {
        val connection =
            open(url).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return readResponse(connection)
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8).use { it?.readText().orEmpty() }
        if (status !in 200..299) error(JSONObject(text).optString("message").ifBlank { "HTTP $status" })
        return text
    }

    private fun <T> background(
        callback: (Result<T>) -> Unit,
        block: () -> T,
    ) = Thread { callback(runCatching(block)) }.start()

    private fun ByteArray.startsWithMidi() = size >= 4 && copyOfRange(0, 4).contentEquals(byteArrayOf(0x4d, 0x54, 0x68, 0x64))

    private fun ByteArray.looksLikeLicense() = toString(Charsets.UTF_8).trimStart().startsWith('{')

    private fun decryptLicensedMidi(license: JSONObject): ByteArray {
        require(license.optString("alg") == "aes-256-gcm") { "许可证算法不支持" }
        val session = license.getJSONObject("session")
        require(session.optString("alg") == "aes-256-gcm-session") { "许可证会话算法不支持" }
        val decode = { key: String -> Base64.decode(key, Base64.DEFAULT) }
        val sessionKey = decode(session.getString("key"))
        val sessionNonce = decode(session.getString("nonce"))
        val wrappedDek = decode(session.getString("wrappedDek"))
        val songNonce = decode(license.getString("nonce"))
        val songAad = decode(license.getString("aad"))
        val sessionAad =
            "${license.getString(
                "songId",
            )}\u0000${license.getInt("version")}\u0000${session.getInt("expiresIn")}".toByteArray()
        val dek = aesGcm(sessionKey, sessionNonce, sessionAad, wrappedDek)
        val encrypted = requestBytes(license.getJSONObject("download").getString("url"), null)
        val midi = aesGcm(dek, songNonce, songAad, encrypted)
        require(midi.startsWithMidi()) { "许可证解密结果不是 MIDI" }
        return midi
    }

    private fun aesGcm(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        ClientVersion.attach(connection)
        connection.connectTimeout = 8000
        connection.readTimeout = if (connection.url.path == "/api/scores/search") 30_000 else 8000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        return connection
    }

    private companion object {
        const val MAX_LIBRARY_PAGES = 100
    }
}
