package app.luoxianlv.playback

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class GestureWaitTicketTest {
    private class Clock(var time: Long = 1000) {
        fun ticket(id: Long = 1, duration: Long = 172) =
            GestureWaitTicket(id, time, duration) { time }
    }

    @Test
    fun missingCallbackWaitsForDurationPlusGraceThenExpiresOnce() {
        val clock = Clock()
        val ticket = clock.ticket()
        ticket.returned(true)
        assertEquals(2672L, ticket.deadline)
        clock.time = ticket.deadline - 1
        assertFalse(ticket.expired())
        assertFalse(ticket.expire())
        clock.time++
        assertTrue(ticket.expired())
        assertTrue(ticket.expire())
        assertFalse(ticket.expire())
        assertFalse(ticket.arrived(true))
        assertFalse(ticket.complete())
    }

    @Test
    fun lateCallbackCannotCompleteBeforeTimeoutMessageRuns() {
        val clock = Clock()
        val ticket = clock.ticket()
        ticket.returned(true)
        clock.time = ticket.deadline + 14000
        assertFalse(ticket.arrived(true))
        assertFalse(ticket.complete())
        assertTrue(ticket.expire())
        assertEquals(GestureWaitTicket.Stage.TIMED_OUT, ticket.stage)
    }

    @Test
    fun synchronousCallbackBeforeDispatchReturnKeepsItsPhase() {
        val clock = Clock()
        val ticket = clock.ticket()
        assertTrue(ticket.arrived(true))
        ticket.returned(true)
        assertEquals(GestureWaitTicket.Stage.HANDLING_CALLBACK, ticket.stage)
        assertEquals(true, ticket.callbackSuccess)
        assertTrue(ticket.complete())
        assertFalse(ticket.complete())
        ticket.returned(true)
        assertEquals(GestureWaitTicket.Stage.COMPLETED, ticket.stage)
    }

    @Test
    fun rejectedDispatchCanFinishItsFailureWithoutWaiting() {
        val clock = Clock()
        val ticket = clock.ticket()
        ticket.returned(false)
        assertEquals(false, ticket.accepted)
        assertTrue(ticket.arrived(false))
        assertEquals(false, ticket.callbackSuccess)
        assertTrue(ticket.complete())
        clock.time = ticket.deadline + 1
        assertFalse(ticket.expire())
    }

    @Test
    fun repeatedCallbacksHaveOnlyOneCompletionRight() {
        val clock = Clock()
        val ticket = clock.ticket()
        ticket.returned(true)
        assertTrue(ticket.arrived(true))
        assertFalse(ticket.arrived(false))
        assertEquals(true, ticket.callbackSuccess)
        assertTrue(ticket.complete())
        assertFalse(ticket.arrived(true))
    }

    @Test
    fun pauseCancellationRejectsLateCallbackAndTimeout() {
        val clock = Clock()
        val ticket = clock.ticket()
        ticket.returned(true)
        assertTrue(ticket.cancel())
        clock.time = ticket.deadline + 1
        assertFalse(ticket.arrived(true))
        assertFalse(ticket.complete())
        assertFalse(ticket.expire())
        assertFalse(ticket.expired())
        assertFalse(ticket.cancel())
    }

    @Test
    fun anOldCancelledRequestCannotAffectTheNextRequest() {
        val clock = Clock()
        val old = clock.ticket(1)
        old.returned(true)
        old.cancel()
        clock.time += 100
        val next = clock.ticket(2)
        next.returned(true)
        assertFalse(old.arrived(true))
        assertFalse(old.complete())
        assertEquals(GestureWaitTicket.Stage.WAITING_SYSTEM, next.stage)
        assertTrue(next.arrived(true))
        assertTrue(next.complete())
    }

    @Test
    fun cancellationDuringCallbackPreventsCommittingItsResult() {
        val clock = Clock()
        val ticket = clock.ticket()
        assertTrue(ticket.arrived(true))
        assertTrue(ticket.cancel())
        assertFalse(ticket.complete())
        assertEquals(GestureWaitTicket.Stage.CANCELLED, ticket.stage)
    }

    @Test
    fun callbackProcessingMustAlsoRespectTheDeadline() {
        val clock = Clock()
        val ticket = clock.ticket()
        assertTrue(ticket.arrived(true))
        clock.time = ticket.deadline
        assertEquals(GestureWaitTicket.Stage.HANDLING_CALLBACK, ticket.stage)
        assertFalse(ticket.complete())
        assertTrue(ticket.expire())
    }

    @Test
    fun successfulCompletionCannotBeCancelledOrTimedOutLater() {
        val clock = Clock()
        val ticket = clock.ticket()
        ticket.returned(true)
        clock.time = ticket.deadline - 1
        assertTrue(ticket.arrived(true))
        assertTrue(ticket.complete())
        clock.time = ticket.deadline + 1
        assertFalse(ticket.expired())
        assertFalse(ticket.expire())
        assertFalse(ticket.cancel())
    }

    @Test
    fun simultaneousTerminalClaimsCannotWinTwice() {
        val ticket = GestureWaitTicket(1, 1000, 172) { 1001 }
        assertTrue(ticket.arrived(true))
        val start = CountDownLatch(1)
        val completed = AtomicInteger()
        val cancelled = AtomicInteger()
        val a = Thread {
            start.await()
            if (ticket.complete()) completed.incrementAndGet()
        }
        val b = Thread {
            start.await()
            if (ticket.cancel()) cancelled.incrementAndGet()
        }
        a.start()
        b.start()
        start.countDown()
        a.join(2000)
        b.join(2000)
        assertFalse(a.isAlive || b.isAlive)
        assertEquals(1, completed.get() + cancelled.get())
        assertFalse(ticket.pending())
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDurationIsRejected() {
        GestureWaitTicket(1, 1000, 0) { 1000 }
    }

    @Test(expected = IllegalArgumentException::class)
    fun deadlineCannotWrapAroundTheClock() {
        GestureWaitTicket(1, Long.MAX_VALUE - 100, 1) { Long.MAX_VALUE - 100 }
    }
}
