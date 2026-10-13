package app.luoxianlv.runtime.realtime

import org.junit.Assert.*
import org.junit.Test

class EnsembleTimelineTest {
    private fun clock() =
        EnsembleClock().apply {
            repeat(3) { i ->
                val sent = i * 20_000L
                sample("clock", sent, sent + 10_000, sent + 1_005_000, sent + 1_005_000, true)
            }
        }

    private fun plan(id: String = "start", revision: Long = 1) =
        EnsembleTimeline.Plan(id, "run", revision, "clock", 2_000_000, 2_100_000)

    @Test
    fun mapsAbsoluteTimeAndExecutesOnce() {
        val timeline = EnsembleTimeline(clock())
        timeline.prepare("run")
        val scheduled = timeline.accept(plan(), 60_000)!!
        assertEquals(1_000_000L, scheduled.localDeadlineUs)
        assertEquals(scheduled, timeline.accept(plan(), 70_000))
        assertFalse(timeline.claim(scheduled, 999_999))
        assertTrue(timeline.claim(scheduled, 1_000_000))
        assertNull(timeline.accept(plan(), 1_000_001))
        assertFalse(timeline.claim(scheduled, 1_000_001))
    }

    @Test
    fun localStopAndFreshPreparationRejectOldCallbacks() {
        val timeline = EnsembleTimeline(clock())
        timeline.prepare("run")
        val scheduled = timeline.accept(plan(), 60_000)!!
        timeline.stop()
        assertNull(timeline.accept(plan("retry", 2), 70_000))
        assertFalse(timeline.claim(scheduled, 1_000_000))
        timeline.prepare("new-run")
        assertFalse(timeline.claim(scheduled, 1_000_000))
        assertNull(timeline.accept(plan(), 70_000))
    }

    @Test
    fun staleRevisionsLateWakeupsAndClockRestartNeverCatchUp() {
        val clock = clock()
        val timeline = EnsembleTimeline(clock)
        timeline.prepare("run")
        val scheduled = timeline.accept(plan(revision = 2), 60_000)!!
        assertNull(timeline.accept(plan("old", 1), 70_000))
        assertNull(timeline.accept(plan().copy(executeAtUs = 3_000_000), 70_000))
        assertFalse(timeline.claim(scheduled, 1_060_000))
        timeline.prepare("run")
        val next = timeline.accept(plan(), 60_000)!!
        clock.reset("new-clock")
        assertFalse(timeline.claim(next, 1_000_000))
    }

    @Test
    fun invalidExpiryOrAlreadyLatePlanIsRejected() {
        val timeline = EnsembleTimeline(clock())
        timeline.prepare("run")
        assertNull(timeline.accept(plan().copy(expiresAtUs = 1_999_999), 60_000))
        assertNull(timeline.accept(plan(), 1_000_001))
        assertNull(timeline.accept(plan().copy(executeAtUs = Long.MAX_VALUE), 60_000))
    }
}
