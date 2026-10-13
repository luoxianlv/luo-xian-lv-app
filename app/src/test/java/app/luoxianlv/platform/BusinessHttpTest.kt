package app.luoxianlv.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BusinessHttpTest {
    @Test
    fun truncatedContentLengthIsRejected() = runBlocking {
        transport(MockEngine { respond("short", headers = headersOf("Content-Length", "6")) })
            .use { http ->
                assertTrue(
                    runCatching { http.bytes("https://api.example/partial") }.exceptionOrNull()
                        is IOException
                )
            }
    }

    @Test
    fun binaryResponsePreservesBoundariesAndExactLimit() = runBlocking {
        val bytes = ByteArray(123_692) { (it * 71 + it / 257).toByte() }
        transport(
                MockEngine {
                    respond(bytes, headers = headersOf("Content-Length", bytes.size.toString()))
                }
            )
            .use { http ->
                assertArrayEquals(
                    bytes,
                    http.bytes("https://api.example/binary", limit = bytes.size),
                )
                assertTrue(
                    runCatching { http.bytes("https://api.example/binary", limit = bytes.size - 1) }
                        .isFailure
                )
            }
    }

    @Test
    fun versionHeadersDoNotLeakToOtherOrigins() = runBlocking {
        val engine = MockEngine { request ->
            assertNull(request.headers["X-App-Version-Code"])
            assertNull(request.headers["Authorization"])
            respond("ok")
        }
        transport(engine).use { http ->
            for (url in
                listOf(
                    "https://oss-eo.luoxianlv.cn/a?auth_key=fixture",
                    "https://oss-luoxianlv.admilk.cn/a?signature=fixture",
                    "https://api.example:8443/a",
                    "https://api.example.attacker.example/a",
                )) http.bytes(url, "private")
        }
    }

    private fun transport(engine: MockEngine) =
        BusinessHttp(
            HttpClients(
                HttpClient(engine) {
                    followRedirects = false
                    install(HttpTimeout)
                }
            ),
            "https://api.example",
            "Luoxianlv/test",
            mapOf("X-App-Version-Code" to "24"),
        )

    @Test
    fun redirectDoesNotForwardCredentialsToCdn() = runBlocking {
        val requests = mutableListOf<Pair<String, String?>>()
        val engine = MockEngine { request ->
            requests += request.url.host to request.headers["Authorization"]
            if (request.url.host == "api.example") {
                assertEquals("24", request.headers["X-App-Version-Code"])
                respond("", HttpStatusCode.Found, headersOf("Location", "https://cdn.example/a"))
            } else {
                assertNull(request.headers["X-App-Version-Code"])
                respond("score")
            }
        }
        transport(engine).use { http ->
            assertEquals(
                "score",
                http.bytes("https://api.example:443/score", "private").decodeToString(),
            )
        }
        assertEquals(listOf("api.example" to "Bearer private", "cdn.example" to null), requests)
    }

    @Test
    fun writeRedirectIsNotReplayed() = runBlocking {
        var count = 0
        val engine = MockEngine {
            count++
            respond(
                "",
                HttpStatusCode.TemporaryRedirect,
                headersOf("Location", "https://cdn.example"),
            )
        }
        transport(engine).use { http ->
            val error = runCatching {
                http.bytes("https://api.example/create", body = "{}")
            }
                .exceptionOrNull()
            assertTrue(error is IllegalStateException)
        }
        assertEquals(1, count)
    }

    @Test
    fun oversizedAndInsecureResponsesAreRejected() = runBlocking {
        var count = 0
        val engine = MockEngine {
            count++
            respond("x".repeat(8193))
        }
        transport(engine).use { http ->
            assertTrue(
                runCatching { http.bytes("https://api.example", limit = 8192) }.exceptionOrNull()
                    is IllegalArgumentException
            )
            assertTrue(
                runCatching { http.bytes("http://api.example") }.exceptionOrNull()
                    is IllegalArgumentException
            )
        }
        assertEquals(1, count)
    }

    @Test
    fun cancellationReachesThePendingRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val engine = MockEngine {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        transport(engine).use { http ->
            val pending = async { http.bytes("https://api.example/slow") }
            started.await()
            pending.cancelAndJoin()
            assertTrue(stopped.isCompleted)
        }
    }

    @Test
    fun redirectLoopsHaveAFiniteBudget() = runBlocking {
        var count = 0
        transport(
                MockEngine {
                    count++
                    respond("", HttpStatusCode.Found, headersOf("Location", "/loop"))
                }
            )
            .use { http ->
                assertTrue(runCatching { http.bytes("https://api.example/loop") }.isFailure)
            }
        assertEquals(6, count)
    }
}
