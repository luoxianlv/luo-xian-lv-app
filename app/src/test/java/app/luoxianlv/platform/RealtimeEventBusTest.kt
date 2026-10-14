package app.luoxianlv.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RealtimeEventBusTest {
    private class Subscription(
        val state: (RealtimeState) -> Unit,
        val message: (String, String, String) -> Unit,
    ) {
        var closed = false
    }

    private class Source {
        val subscriptions = mutableListOf<Subscription>()
        val current
            get() = subscriptions.last()

        val bus = RealtimeEventBus { state, message ->
            Subscription(state, message).also(subscriptions::add).let { source ->
                AutoCloseable { source.closed = true }
            }
        }
    }

    private fun state(account: String = "one", epoch: String = "1", reset: Long = 0) =
        RealtimeState(
            connection = RealtimeConnection.CONNECTED,
            identity = RealtimeIdentity(epoch, account, reset),
            queriesReady = true,
        )

    private fun frame(
        topic: String = "business",
        epoch: String = "1",
        data: JSONObject = JSONObject().put("kind", "library").put("revision", "1"),
    ) =
        JSONObject()
            .put("protocolVersion", 1)
            .put("connectionEpoch", epoch)
            .put("messageId", "notice")
            .put("topic", topic)
            .put("type", "changed")
            .put("data", data)
            .toString()

    @Test
    fun validatesFormatEpochUtf8AndKnownNotificationKinds() {
        val live = state()
        assertTrue(
            RealtimeEventFormat.decode("business", "changed", frame(), live)
                is RealtimeEvent.ResourceChanged
        )
        assertNull(RealtimeEventFormat.decode("business", "changed", frame(epoch = "old"), live))
        assertNull(RealtimeEventFormat.decode("business", "changed", frame(), state(account = "")))
        assertNull(RealtimeEventFormat.decode("query", "result", "{}", live))
        assertNull(RealtimeEventFormat.decode("ensemble", "changed", "{}", live))
        assertNull(
            RealtimeEventFormat.decode(
                "business",
                "changed",
                frame(data = JSONObject().put("kind", "unknown")),
                live,
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            RealtimeEventFormat.decode(
                "business",
                "changed",
                frame(data = JSONObject().put("text", "中".repeat(6000))),
                live,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RealtimeEventFormat.decode(
                "business",
                "changed",
                frame(data = JSONObject().put("kind", "library").put("revision", "-1")),
                live,
            )
        }
    }

    @Test
    fun suppressesDuplicatesAndKeepsRefreshVersionsForLateSubscribers() = runBlocking {
        val source = Source()
        source.bus.use { bus ->
            bus.resume()
            bus.resume()
            assertEquals(1, source.subscriptions.size)
            source.current.state(state())
            val initial = bus.invalidations.value.library
            source.current.message("business", "changed", frame())
            val updated = bus.invalidations.value.library
            assertTrue(updated > initial)
            source.current.message("business", "changed", frame())
            source.current.message("business", "changed", "broken")
            assertEquals(updated, bus.invalidations.first().library)
            source.current.state(state(account = "two", reset = 1))
            assertEquals("two", bus.invalidations.value.identity.accountId)
            assertTrue(bus.invalidations.value.profile > updated)
        }
    }

    @Test
    fun pausedClosedAndOldGenerationCallbacksCannotChangeNewBusinessState() {
        val source = Source()
        val bus = source.bus
        bus.resume()
        source.current.state(state())
        val old = source.current
        bus.pause()
        assertTrue(old.closed)
        old.state(state(account = "stale"))
        assertEquals("one", bus.state.value.identity.accountId)
        bus.resume()
        source.current.state(state(account = "two", epoch = "2"))
        old.message("business", "changed", frame())
        old.state(state(account = "stale"))
        assertEquals("two", bus.state.value.identity.accountId)
        bus.close()
        source.current.state(state(account = "stale"))
        assertEquals(RealtimeState(), bus.state.value)
        bus.resume()
        assertEquals(2, source.subscriptions.size)
    }

    @Test
    fun bufferedEventsFromPreviousAccountAreDiscardedBeforeConsumption() = runBlocking {
        val source = Source()
        source.bus.use { bus ->
            bus.resume()
            source.current.state(state())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val arrived = CompletableDeferred<Unit>()
            val seen = mutableListOf<RealtimeEvent.ResourceChanged>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    bus.events.collect { event ->
                        if (event is RealtimeEvent.ResourceChanged) {
                            seen.add(event)
                            if (event.revision == 1L) {
                                entered.complete(Unit)
                                release.await()
                            }
                            if (event.revision == 3L) arrived.complete(Unit)
                        }
                    }
                }
            try {
                source.current.message("business", "changed", frame())
                withTimeout(5000) { entered.await() }
                source.current.message(
                    "business",
                    "changed",
                    frame(data = JSONObject().put("kind", "library").put("revision", "2")),
                )
                source.current.state(state(account = "two", reset = 1))
                source.current.message(
                    "business",
                    "changed",
                    frame(data = JSONObject().put("kind", "library").put("revision", "3")),
                )
                release.complete(Unit)
                withTimeout(5000) { arrived.await() }
                assertEquals(listOf(1L, 3L), seen.map { it.revision })
                assertEquals("two", seen.last().identity.accountId)
            } finally {
                collector.cancelAndJoin()
            }
        }
    }

    @Test
    fun slowEventCollectorCannotBlockStateOrLoseDirtyResourceVersions() = runBlocking {
        val source = Source()
        source.bus.use { bus ->
            bus.resume()
            source.current.state(state())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    bus.events.collect {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            try {
                source.current.message("business", "changed", frame())
                withTimeout(5000) { entered.await() }
                withTimeout(5000) {
                    async {
                        repeat(500) { index ->
                            source.current.message(
                                "business",
                                "changed",
                                frame(
                                    data =
                                        JSONObject()
                                            .put("kind", "library")
                                            .put("revision", (index + 2).toString())
                                ),
                            )
                        }
                    }
                        .await()
                }
                assertTrue(bus.invalidations.value.library >= 502)
                val versions =
                    JSONObject()
                        .put("versionCode", 50)
                        .put("versionName", "测试")
                        .put("updateEnabled", true)
                source.current.message(
                    "catalog",
                    "changed",
                    frame("catalog", data = JSONObject().put("versions", versions)),
                )
                assertEquals(50L, bus.state.value.versions?.versionCode)
            } finally {
                collector.cancelAndJoin()
            }
        }
    }

    @Test
    fun readsServerBusinessNotificationFormat() {
        val raw =
            """{"protocolVersion":1,"connectionEpoch":"1","messageId":"business","topic":"business","type":"changed","data":{"kind":"notifications","revision":"42"}}"""
        val event =
            RealtimeEventFormat.decode("business", "changed", raw, state())
                as RealtimeEvent.ResourceChanged
        assertEquals(RealtimeResource.NOTIFICATIONS, event.resource)
        assertEquals(42L, event.revision)
    }
}
