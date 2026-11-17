package app.luoxianlv.runtime.realtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NotificationMailboxTest {
    @Test
    fun stalledConsumerDoesNotBlockProducerAndOnlyKeepsLatestPerKey() {
        val worker = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val values = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val mailbox = NotificationMailbox(worker, 2)
        try {
            assertTrue(
                mailbox.offer("busy") {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            )
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repeat(10_000) { number ->
                assertTrue(
                    mailbox.offer("library") {
                        values.add(number)
                        delivered.countDown()
                    }
                )
            }
            assertTrue(mailbox.offer("profile") { throw IllegalStateException("bad consumer") })
            assertFalse(mailbox.offer("overflow") { fail("capacity ignored") })
            assertTrue(values.isEmpty())
            release.countDown()
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(9999), values)
            val survived = CountDownLatch(1)
            mailbox.offer("state") { survived.countDown() }
            assertTrue(survived.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            mailbox.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun closeReleasesQueuedCallbacksAndShutdownDoesNotPropagateToProducer() {
        val tasks = java.util.ArrayDeque<Runnable>()
        val mailbox = NotificationMailbox({ tasks.addLast(it) })
        mailbox.offer("state") { fail("closed callback delivered") }
        mailbox.close()
        tasks.removeFirst().run()
        assertFalse(mailbox.offer("state") {})
        val worker = Executors.newSingleThreadExecutor()
        worker.shutdownNow()
        assertFalse(NotificationMailbox(worker).offer("state") { fail("stopped worker delivered") })
    }
}
