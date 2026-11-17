package app.luoxianlv.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class HttpClientsTest {
    @Test
    fun limitsConcurrentDomainsAndDoesNotEvictActiveRequest() = runBlocking {
        val created = mutableListOf<Pair<String, HttpClient>>()
        val clients =
            HttpClients(
                create = { host ->
                    HttpClient(MockEngine { respond("ok") }).also { created += host to it }
                }
            )
        val release = CompletableDeferred<Unit>()
        var entered = 0
        val active =
            (0..7).map { index ->
                async {
                    clients.use("host$index") { client ->
                        entered++
                        release.await()
                        assertTrue(client.coroutineContext.isActive)
                    }
                }
            }
        withTimeout(2000) { while (entered != 8) yield() }
        var ninth = false
        val waiting = async { clients.use("host8") { ninth = true } }
        yield()
        assertFalse(ninth)
        assertEquals(8, created.size)
        release.complete(Unit)
        active.awaitAll()
        waiting.await()
        assertEquals(9, created.size)
        clients.close()
        assertTrue(created.all { !it.second.coroutineContext.isActive })
    }

    @Test
    fun sameDomainReusesClientAndRetirementRejectsNewWork() = runBlocking {
        var created = 0
        val clients =
            HttpClients(create = { HttpClient(MockEngine { respond("ok") }).also { created++ } })
        clients.use("API.example") { first -> clients.use("api.example") { assertSame(first, it) } }
        assertEquals(1, created)
        clients.close()
        try {
            clients.use("another.example") { fail("已退役客户端接受新请求") }
            fail()
        } catch (_: CancellationException) {}
        assertEquals(1, created)
    }
}
