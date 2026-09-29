package app.luoxianlv.ui.practice

import app.luoxianlv.core.score.PlayMode
import org.junit.Assert.*
import org.junit.Test

class PracticePlaybackGateTest {
    @Test
    fun retiredPageCannotDisableCurrentPlayback() {
        val old = Any()
        val current = Any()
        val oldKeys = PracticeSession().apply { select(PracticeSession.Mode.LOWER) }
        val newKeys =
            PracticeSession().apply {
                select(PracticeSession.Mode.RAISE)
                toggleHalf()
            }
        try {
            PracticePlaybackGate.enter(old)
            PracticePlaybackGate.bindSession(old, oldKeys)
            PracticePlaybackGate.setReady(old, true)
            PracticePlaybackGate.enter(current)
            PracticePlaybackGate.bindSession(current, newKeys)
            PracticePlaybackGate.setReady(current, true)
            PracticePlaybackGate.bindSession(old, oldKeys)
            PracticePlaybackGate.setReady(old, false)
            PracticePlaybackGate.invalidateSession(oldKeys)
            PracticePlaybackGate.leave(old)
            assertTrue(PracticePlaybackGate.active)
            assertTrue(PracticePlaybackGate.ready)
            assertEquals(PlayMode.RAISE to true, PracticePlaybackGate.pitchState())
            PracticePlaybackGate.invalidateSession(newKeys)
            assertFalse(PracticePlaybackGate.ready)
            assertNull(PracticePlaybackGate.pitchState())
        } finally {
            PracticePlaybackGate.leave(current)
        }
    }

    @Test
    fun reenterSameOwnerPreservesItsSessionAndExitClearsIt() {
        val owner = Any()
        try {
            PracticePlaybackGate.enter(owner)
            PracticePlaybackGate.bindSession(owner, PracticeSession())
            PracticePlaybackGate.setReady(owner, true)
            PracticePlaybackGate.enter(owner)
            assertEquals(PlayMode.NATURAL to false, PracticePlaybackGate.pitchState())
        } finally {
            PracticePlaybackGate.leave(owner)
        }
        assertFalse(PracticePlaybackGate.active)
        assertFalse(PracticePlaybackGate.ready)
    }
}
