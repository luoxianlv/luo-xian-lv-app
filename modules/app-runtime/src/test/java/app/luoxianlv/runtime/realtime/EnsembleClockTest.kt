package app.luoxianlv.runtime.realtime

import org.junit.Assert.*
import org.junit.Test

class EnsembleClockTest {
    private fun add(
        clock: EnsembleClock,
        sent: Long,
        delay: Long = 10_000,
        offset: Long = 1_000_000,
    ) =
        clock.sample(
            "a",
            sent,
            sent + delay + 1000,
            sent + delay / 2 + offset,
            sent + delay / 2 + offset + 1000,
            true,
        )

    @Test
    fun requiresThreeSamplesAndExcludesServerWork() {
        val clock = EnsembleClock()
        add(clock, 0)
        add(clock, 20_000)
        assertNull(clock.estimate(40_000))
        add(clock, 40_000)
        assertEquals(1_000_000L, clock.estimate(60_000)!!.offsetUs)
        assertEquals(6000L, clock.estimate(60_000)!!.uncertaintyUs)
    }

    @Test
    fun slowSamplesDoNotMoveEstimateAndOldSamplesExpire() {
        val clock = EnsembleClock()
        repeat(3) { add(clock, it * 20_000L) }
        add(clock, 100_000, 500_000, 1_100_000)
        assertEquals(1_000_000L, clock.estimate(610_000)!!.offsetUs)
        assertNull(clock.estimate(31_000_000))
    }

    @Test
    fun serverRestartOrUnhealthyClockRequiresFreshCalibration() {
        val clock = EnsembleClock()
        repeat(3) { add(clock, it * 20_000L) }
        clock.sample("b", 60_000, 70_000, 1_065_000, 1_065_000, true)
        assertNull(clock.estimate(80_000))
        assertFalse(clock.sample("b", 60_000, 70_000, 1_065_000, 1_065_000, false))
        assertNull(clock.estimate(80_000))
    }

    @Test
    fun rejectsImpossibleTimestampAndExcessiveUncertainty() {
        val clock = EnsembleClock()
        assertFalse(clock.sample("a", 5, 4, 5, 6, true))
        assertFalse(clock.sample("a", 5, 15, 100, 120, true))
        assertFalse(add(clock, 0, 1_100_000))
        repeat(3) { add(clock, it * 400_000L, 300_000) }
        assertNull(clock.estimate(1_200_000))
    }
}
