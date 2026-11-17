package app.luoxianlv.platform

import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readBuffer
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.readByteArray
import org.json.JSONObject

/** 响应只在请求作用域内消费；重定向逐跳检查，文件和账号请求共用取消语义。 */
internal class BusinessHttp(
    private val clients: HttpClients,
    private val origin: String,
    private val userAgent: String,
    private val versionHeaders: Map<String, String> = emptyMap(),
    private val allowLocalHttp: Boolean = false,
) : AutoCloseable {
    private sealed interface Hop<out T> {
        data class Redirect(val url: String) : Hop<Nothing>

        data class Value<T>(val value: T) : Hop<T>
    }

    suspend fun bytes(
        url: String,
        token: String? = null,
        body: String? = null,
        limit: Int = 8 * 1024 * 1024,
        readTimeoutMs: Long = 30_000,
    ): ByteArray =
        consume(url, token, body, readTimeoutMs = readTimeoutMs) { response ->
            response.requireSuccess()
            response.readLimited(limit)
        }

    suspend fun <T> consume(
        url: String,
        token: String? = null,
        body: String? = null,
        readTimeoutMs: Long = 30_000,
        streaming: Boolean = false,
        followRedirects: Boolean = true,
        validateRedirect: (String, String) -> String = { location, previous ->
            URI(previous).resolve(location).toString()
        },
        block: suspend (HttpResponse) -> T,
    ): T {
        var next = url
        repeat(6) { redirect ->
            currentCoroutineContext().ensureActive()
            val target = validate(next)
            val result =
                clients.use(target.host) { client ->
                    client
                        .prepareRequest(target.toString()) {
                            method = if (body == null) HttpMethod.Get else HttpMethod.Post
                            header("User-Agent", userAgent)
                            header("Accept-Encoding", "identity")
                            if (sameOrigin(origin, next)) {
                                versionHeaders.forEach { (key, value) -> header(key, value) }
                                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                            }
                            if (body != null) {
                                header("Content-Type", "application/json; charset=utf-8")
                                setBody(body)
                            }
                            timeout {
                                connectTimeoutMillis = 8_000
                                socketTimeoutMillis = readTimeoutMs
                                requestTimeoutMillis =
                                    if (streaming) HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                                    else readTimeoutMs + 8_000
                            }
                        }
                        .execute { response ->
                            if (response.status.value in REDIRECTS && followRedirects) {
                                // 写请求不自动重放，避免重复提交及请求体跨域泄漏。
                                check(body == null) { "服务器重定向了写请求，请重试" }
                                check(redirect < 5) { "下载地址重定向过多" }
                                val location = response.headers["Location"] ?: error("下载地址无效")
                                Hop.Redirect(validateRedirect(location, next))
                            } else {
                                Hop.Value(block(response))
                            }
                        }
                }
            when (result) {
                is Hop.Redirect -> next = result.url
                is Hop.Value -> return result.value
            }
        }
        error("下载地址重定向过多")
    }

    private fun validate(value: String): URI {
        val uri = URI(value)
        val local =
            uri.scheme == "http" &&
                allowLocalHttp &&
                uri.host in setOf("127.0.0.1", "localhost", "[::1]")
        require(
            (uri.scheme == "https" || local) &&
                uri.host != null &&
                uri.rawUserInfo == null &&
                uri.rawFragment == null
        ) {
            "网络地址无效"
        }
        return uri
    }

    override fun close() {
        clients.close()
    }

    companion object {
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        fun sameOrigin(first: String, second: String): Boolean {
            val a = URI(first)
            val b = URI(second)
            fun port(uri: URI) =
                if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
            return a.scheme.equals(b.scheme, true) &&
                a.host != null &&
                a.host.equals(b.host, true) &&
                port(a) == port(b)
        }
    }
}

internal class HttpFailure(val status: Int, message: String) : IOException(message)

internal suspend fun HttpResponse.requireSuccess() {
    if (status.value in 200..299) return
    val detail = runCatching {
        JSONObject(readLimited(8192).toString(Charsets.UTF_8)).optString("message").take(160)
    }
        .getOrDefault("")
    currentCoroutineContext().ensureActive()
    throw HttpFailure(status.value, detail.ifBlank { "请求失败（HTTP ${status.value}）" })
}

internal suspend fun HttpResponse.readLimited(limit: Int): ByteArray {
    require(limit >= 0)
    val input = bodyAsChannel()
    val bytes = input.readBuffer(limit.toLong() + 1).readByteArray()
    currentCoroutineContext().ensureActive()
    input.closedCause?.let { throw it }
    require(bytes.size <= limit) { "响应内容过大" }
    headers["Content-Length"]?.toLongOrNull()?.let { expected ->
        if (expected != bytes.size.toLong()) throw IOException("响应内容不完整")
    }
    return bytes
}
