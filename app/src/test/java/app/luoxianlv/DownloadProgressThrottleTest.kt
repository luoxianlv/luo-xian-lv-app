package app.luoxianlv

import app.luoxianlv.update.DownloadProgressThrottle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressThrottleTest {
    @Test
    fun fastDownloadPublishesAtMostOncePerHundredMilliseconds() {
        val throttle = DownloadProgressThrottle()
        val emissions = (0L until 1_000_000_000L step 1_000_000L).filter(throttle::shouldPublish)
        assertEquals(10, emissions.size)
        assertTrue(emissions.zipWithNext().all { (a, b) -> b - a >= 100_000_000L })
    }

    @Test
    fun firstProgressIsImmediateAndSourceRetryHasItsOwnClock() {
        val firstSource = DownloadProgressThrottle()
        assertTrue(firstSource.shouldPublish(30L))
        assertFalse(firstSource.shouldPublish(31L))
        assertTrue(DownloadProgressThrottle().shouldPublish(31L))
    }

    @Test
    fun longIdleAndMonotonicClockWrapStillPublishTheNextProgress() {
        val throttle = DownloadProgressThrottle()
        assertTrue(throttle.shouldPublish(Long.MAX_VALUE - 50_000_000L))
        assertFalse(throttle.shouldPublish(Long.MIN_VALUE + 10_000_000L))
        assertTrue(throttle.shouldPublish(Long.MIN_VALUE + 100_000_000L))
        assertTrue(throttle.shouldPublish(Long.MIN_VALUE + 5_000_000_000L))
    }
}
