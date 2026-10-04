package app.luoxianlv.platform

import app.luoxianlv.update.ClientVersion
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** 负责 HTTP 传输和会话刷新；回调仍在当前工作线程执行。 */
internal class BackendClient(private val baseUrl: String, private val sessionStore: SessionStore) {
    fun get(url: String): String = requestText(url, null)

    fun requestText(
        url: String,
        accessToken: String?,
    ): String = requestBytes(url, accessToken).toString(Charsets.UTF_8)

    fun requestBytes(
        url: String,
        accessToken: String?,
        retryAuth: Boolean = true,
    ): ByteArray {
        val current = sessionStore.current()
        val token =
            if (
                !accessToken.isNullOrBlank() &&
                    current?.accessToken == accessToken &&
                    current.expiresAt > 0L &&
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

    private fun refreshSession(previous: String): AccountSession? = runCatching {
        val root = JSONObject(postJsonWithAuth("$baseUrl/api/auth/refresh", previous))
        parseAccountSession(root).also { sessionStore.save(it) }
    }
        .getOrElse {
            sessionStore.clear()
            null
        }

    fun postJsonWithAuth(
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

    fun postJson(
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
        if (status !in 200..299)
            error(JSONObject(text).optString("message").ifBlank { "HTTP $status" })
        return text
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
}

internal fun <T> background(
    callback: (Result<T>) -> Unit,
    block: () -> T,
) {
    if (!app.luoxianlv.app.BusinessJobs.thread("平台请求") { callback(runCatching(block)) })
        callback(Result.failure(IllegalStateException("本代工作已停用，请重新操作")))
}
