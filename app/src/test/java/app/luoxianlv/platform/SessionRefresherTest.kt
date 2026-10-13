package app.luoxianlv.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class SessionRefresherTest {
    private class Store : SessionPersistence {
        var value = SessionSnapshot(1, AccountSession("old", "refresh", "name"))

        override fun snapshot() = value

        override fun replace(expected: SessionSnapshot, session: AccountSession?): Boolean {
            if (value != expected) return false
            value = SessionSnapshot(if (session == null) value.epoch + 1 else value.epoch, session)
            return true
        }
    }

    @Test
    fun unauthorizedWriteRefreshesCredentialButRequiresExplicitRetry() = runBlocking {
        val store = Store()
        var writes = 0
        var refreshes = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/api/auth/refresh") {
                refreshes++
                respond("""{"accessToken":"new","expiresIn":3600}""")
            } else {
                writes++
                if (request.headers["Authorization"] == "Bearer old")
                    respond("expired", HttpStatusCode.Unauthorized)
                else {
                    assertEquals("Bearer new", request.headers["Authorization"])
                    respond("saved")
                }
            }
        }
        BusinessHttp(
                HttpClients(
                    HttpClient(engine) {
                        install(HttpTimeout)
                        followRedirects = false
                    }
                ),
                "https://api.example",
                "test",
            )
            .use { transport ->
                val api =
                    BackendClient("https://api.example", store, { transport }, SessionRefresher())
                val failure = runCatching {
                    api.authenticatedBytes("https://api.example/submit", "{}")
                }
                    .exceptionOrNull()
                assertTrue(failure is HttpFailure)
                assertEquals(1, writes)
                assertEquals("new", store.snapshot().session!!.accessToken)
                assertEquals(
                    "saved",
                    api.authenticatedBytes("https://api.example/submit", "{}").decodeToString(),
                )
                assertEquals(2, writes)
                assertEquals(1, refreshes)
            }
    }

    @Test
    fun concurrentRefreshesUseOneRequest() = runBlocking {
        val store = Store()
        val before = store.snapshot()
        val refresh = SessionRefresher()
        val ready = CompletableDeferred<Unit>()
        var count = 0
        val requests =
            List(12) {
                async {
                    refresh.refresh("api", store, before, "old") {
                        count++
                        ready.await()
                        AccountSession("new", "refresh2", "name")
                    }
                }
            }
        yield()
        ready.complete(Unit)
        assertTrue(requests.awaitAll().all { it.accessToken == "new" })
        assertEquals(1, count)
    }

    @Test
    fun networkFailureKeepsSessionAndHasCooldown() = runBlocking {
        val store = Store()
        val before = store.snapshot()
        var now = 0L
        var count = 0
        val refresh = SessionRefresher { now }
        repeat(3) {
            assertTrue(
                runCatching {
                    refresh.refresh("api", store, before, "old") {
                        count++
                        throw IOException("offline")
                    }
                }
                    .exceptionOrNull() is IOException
            )
        }
        assertEquals(1, count)
        assertEquals(before, store.snapshot())
        now = 6_000_000_000L
        runCatching {
            refresh.refresh("api", store, before, "old") {
                count++
                throw IOException()
            }
        }
        assertEquals(2, count)
    }

    @Test
    fun lateRefreshCannotUndoLogoutOrAccountChange() = runBlocking {
        for (replacement in listOf(null, AccountSession("other", "", "other"))) {
            val store = Store()
            val before = store.snapshot()
            val ready = CompletableDeferred<Unit>()
            val pending = async {
                SessionRefresher().refresh("api", store, before, "old") {
                    ready.await()
                    AccountSession("new", "", "name")
                }
            }
            yield()
            val changed = SessionSnapshot(2, replacement)
            store.value = changed
            ready.complete(Unit)
            assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
            assertEquals(changed, store.snapshot())
        }
    }

    @Test
    fun rejectedRefreshClearsOnlyTheMatchingSession() = runBlocking {
        val store = Store()
        runCatching {
            SessionRefresher().refresh("api", store, store.snapshot(), "old") {
                throw HttpFailure(401, "expired")
            }
        }
        assertNull(store.snapshot().session)
        assertEquals(2L, store.snapshot().epoch)
    }
}
