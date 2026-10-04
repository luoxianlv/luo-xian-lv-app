package app.luoxianlv

import app.luoxianlv.playback.PlaybackInterruptionGuard
import org.junit.Assert.*
import org.junit.Test

class PlaybackInterruptionGuardTest {
    @Test
    fun ignoresOnlyStartsInTheInterruptionWindow() {
        val guard = PlaybackInterruptionGuard()
        assertTrue(guard.canStart(0))
        guard.interrupted(1000)
        assertFalse(guard.canStart(1000))
        assertFalse(guard.canStart(1299))
        assertTrue(guard.canStart(1300))
        // 被忽略的点击不延长保护窗口；新的中断会重新计时。
        assertTrue(guard.canStart(1301))
        guard.interrupted(1400)
        assertFalse(guard.canStart(1500))
        assertTrue(guard.canStart(1700))
    }
}
