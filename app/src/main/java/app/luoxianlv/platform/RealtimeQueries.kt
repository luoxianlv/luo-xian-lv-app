package app.luoxianlv.platform

import app.luoxianlv.hot.contract.SharedRealtime
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** 查询优先复用宿主连接；没有能力、断线或繁忙时由调用者继续 HTTP。 */
internal object RealtimeQueries {
    private val paths =
        setOf(
            "/api/auth/profile",
            "/api/me",
            "/api/client/library",
            "/api/midi-core-version",
            "/api/scores/latest",
            "/api/scores/featured",
            "/api/scores/popular",
            "/api/scores/search",
            "/api/notifications",
        )

    suspend fun get(origin: String, url: String, token: String?, limit: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            if (!BusinessHttp.sameOrigin(origin, url)) return@withContext null
            val uri = URI(url)
            if (
                uri.rawPath !in paths &&
                    !uri.rawPath.matches(Regex("/api/scores/[A-Za-z0-9._-]{1,256}/interactions"))
            )
                return@withContext null
            val bridge = SharedRealtime.current() ?: return@withContext null
            if (
                !bridge.state().getBoolean("queriesReady") ||
                    !BusinessHttp.sameOrigin(
                        origin,
                        bridge.state().getString("apiOrigin").orEmpty(),
                    )
            )
                return@withContext null
            if (uri.rawPath == "/api/midi-core-version") {
                val catalog = runCatching {
                    JSONObject(bridge.state().getString("catalog").orEmpty())
                }
                    .getOrNull()
                val version = catalog?.optString("midiCoreVersion").orEmpty()
                if (version.length in 1..128)
                    return@withContext JSONObject()
                        .put("midiCoreVersion", version)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
            }
            val request =
                JSONObject()
                    .put("method", "GET")
                    .put("path", uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: ""))
                    .put("authorization", token.orEmpty())
            val reply =
                withTimeoutOrNull(16_000) {
                    suspendCancellableCoroutine<Pair<Boolean, String>> { continuation ->
                        val owned = AtomicReference<AutoCloseable?>()
                        continuation.invokeOnCancellation { owned.getAndSet(null)?.close() }
                        val operation =
                            bridge.send(
                                "query:${UUID.randomUUID()}",
                                "query",
                                "request",
                                request.toString(),
                            ) { success, body ->
                                if (continuation.isActive) continuation.resume(success to body)
                            }
                        owned.set(operation)
                        if (!continuation.isActive) owned.getAndSet(null)?.close()
                    }
                } ?: return@withContext null
            val envelope = JSONObject(reply.second)
            val status = envelope.optInt("status", 503)
            if (!reply.first || status !in 200..299) {
                if (status in listOf(401, 403, 404, 422) && !(status == 404 && !reply.first))
                    throw HttpFailure(status, "查询失败，请检查登录或访问权限")
                return@withContext null
            }
            envelope.getString("body").toByteArray(Charsets.UTF_8).also {
                require(it.size <= limit) { "查询结果过大" }
            }
        }
}
