package app.luoxianlv.platform

import app.luoxianlv.app.BusinessJobs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** 业务 API 与账号刷新；下载授权只发送到同源 API。 */
internal class BackendClient(
    private val baseUrl: String,
    private val sessionStore: SessionPersistence,
    private val transport: () -> BusinessHttp = BusinessNetwork::current,
    private val refresher: SessionRefresher = sharedRefresher,
) {
    suspend fun authorizedToken(forceRefresh: Boolean = false): String {
        val snapshot = sessionStore.snapshot()
        val session = snapshot.session ?: throw HttpFailure(401, "请先登录")
        return if (forceRefresh || session.expiresAt in 1..(System.currentTimeMillis() + 300_000))
            refresh(snapshot).accessToken
        else session.accessToken
    }

    suspend fun authenticatedBytes(
        url: String,
        body: String? = null,
        limit: Int = 8 * 1024 * 1024,
    ): ByteArray {
        require(BusinessHttp.sameOrigin(baseUrl, url))
        val snapshot = sessionStore.snapshot()
        val epoch = snapshot.epoch
        val token = authorizedToken()
        return try {
                queryBytes(url, token, body, limit)
            } catch (failure: HttpFailure) {
                // GET 可在刷新后重放；写请求由业务明确重试，避免重复编译或覆盖房间。
                if (failure.status != 401) throw failure
                val refreshed = refresh(snapshot, token)
                if (body != null) throw HttpFailure(401, "登录已刷新，请重新操作")
                queryBytes(url, refreshed.accessToken, null, limit)
            }
            .also {
                if (sessionStore.snapshot().epoch != epoch) throw CancellationException("登录账号已改变")
            }
    }

    suspend fun get(url: String): String = requestText(url, null)

    suspend fun requestText(url: String, accessToken: String?): String =
        requestBytes(url, accessToken).toString(Charsets.UTF_8)

    suspend fun requestBytes(url: String, accessToken: String?): ByteArray {
        val snapshot = sessionStore.snapshot()
        val authenticated = !accessToken.isNullOrBlank() && BusinessHttp.sameOrigin(baseUrl, url)
        if (authenticated && !sessionStore.accepts(snapshot, accessToken))
            throw HttpFailure(401, "登录状态已改变，请重新操作")
        var token = if (authenticated) snapshot.session?.accessToken else null
        if (
            authenticated &&
                snapshot.session!!.expiresAt in 1..(System.currentTimeMillis() + 300_000)
        )
            token = refresh(snapshot).accessToken
        return try {
                queryBytes(url, token)
            } catch (failure: HttpFailure) {
                if (failure.status != 401 || !authenticated) throw failure
                val refreshed = refresh(snapshot, token)
                queryBytes(url, refreshed.accessToken)
            }
            .also {
                if (authenticated && sessionStore.snapshot().epoch != snapshot.epoch)
                    throw CancellationException("登录账号已改变")
            }
    }

    private suspend fun queryBytes(
        url: String,
        token: String?,
        body: String? = null,
        limit: Int = 8 * 1024 * 1024,
    ): ByteArray {
        if (body == null)
            RealtimeQueries.get(baseUrl, url, token, limit)?.let {
                return it
            }
        return transport().bytes(url, token, body, limit = limit, readTimeoutMs = 30_000)
    }

    private suspend fun refresh(
        expected: SessionSnapshot,
        previous: String? = expected.session?.accessToken,
    ): AccountSession =
        refresher.refresh(baseUrl, sessionStore, expected, previous) { token ->
            parseAccountSession(JSONObject(postJsonWithAuth("$baseUrl/api/auth/refresh", token)))
        }

    suspend fun postJsonWithAuth(url: String, token: String): String =
        transport().bytes(url, token, "{}", limit = 256 * 1024).toString(Charsets.UTF_8)

    suspend fun postJson(url: String, body: String): String =
        transport().bytes(url, body = body, limit = 256 * 1024).toString(Charsets.UTF_8)

    private companion object {
        val sharedRefresher = SessionRefresher()
    }
}

/** 同账号刷新串行合并；短时失败也合并，避免断网时每个等待请求再刷新一遍。 */
internal class SessionRefresher(private val now: () -> Long = System::nanoTime) {
    private val mutex = Mutex()

    private data class Failure(
        val origin: String,
        val snapshot: SessionSnapshot,
        val until: Long,
        val error: Exception,
    )

    private var failure: Failure? = null

    suspend fun refresh(
        origin: String,
        store: SessionPersistence,
        expected: SessionSnapshot,
        previous: String?,
        request: suspend (String) -> AccountSession,
    ): AccountSession = mutex.withLock {
        val current = store.snapshot()
        if (current.epoch != expected.epoch || current.session == null)
            throw CancellationException("登录账号已改变")
        if (current.session.accessToken != previous) return@withLock current.session
        failure
            ?.takeIf { it.origin == origin && it.snapshot == current && now() < it.until }
            ?.let { throw it.error }
        try {
            val next = request(current.session.accessToken)
            currentCoroutineContext().ensureActive()
            if (!store.replace(current, next)) throw CancellationException("登录账号已改变")
            failure = null
            next
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is HttpFailure && error.status == 401) store.replace(current, null)
            failure = Failure(origin, current, now() + 5_000_000_000, error)
            throw error
        }
    }
}

internal fun <T> background(callback: (Result<T>) -> Unit, block: suspend () -> T) {
    if (
        !BusinessJobs.launch {
            val result =
                try {
                    Result.success(block())
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Result.failure(error)
                }
            currentCoroutineContext().ensureActive()
            callback(result)
        }
    )
        callback(Result.failure(IllegalStateException("本代工作已停用，请重新操作")))
}
