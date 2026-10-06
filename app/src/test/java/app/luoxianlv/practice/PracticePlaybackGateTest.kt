package app.luoxianlv.practice

import app.luoxianlv.library.PlayMode
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

    @Test
    fun backgroundAndLostFocusNeverExposeAPracticeRoute() {
        val owner = Any()
        var resumed = true
        var focused = true
        try {
            PracticePlaybackGate.enter(owner, { resumed && focused })
            PracticePlaybackGate.bindSession(owner, PracticeSession())
            PracticePlaybackGate.setReady(owner, true)
            val token = PracticePlaybackGate.readyToken
            assertTrue(token != 0L)
            focused = false // 系统焦点先于 onPause 变化，也必须即时退出。
            assertFalse(PracticePlaybackGate.active)
            assertFalse(PracticePlaybackGate.ready)
            assertEquals(0L, PracticePlaybackGate.readyToken)
            assertNull(PracticePlaybackGate.pitchState())
            assertTrue(PracticePlaybackGate.owns(owner)) // 后台 Activity 与模式保留。
            focused = true
            resumed = false
            assertFalse(PracticePlaybackGate.active)
            resumed = true
            assertEquals(token, PracticePlaybackGate.readyToken)
            assertEquals(PlayMode.NATURAL to false, PracticePlaybackGate.pitchState())
        } finally {
            PracticePlaybackGate.leave(owner)
        }
    }

    @Test
    fun oldWindowCannotPauseOrClearTheNewPracticeOwner() {
        val old = Any()
        val next = Any()
        try {
            PracticePlaybackGate.enter(old, { true })
            PracticePlaybackGate.setReady(old, true)
            val retired = PracticePlaybackGate.token(old)
            PracticePlaybackGate.enter(next, { true })
            PracticePlaybackGate.bindSession(next, PracticeSession())
            PracticePlaybackGate.setReady(next, true)
            val current = PracticePlaybackGate.readyToken
            assertTrue(current != 0L && current != retired)
            assertFalse(PracticePlaybackGate.matchesPlayback(retired, 0L))
            assertFalse(PracticePlaybackGate.matchesPlayback(retired, current))
            assertTrue(PracticePlaybackGate.matchesPlayback(current, current))
            assertFalse(PracticePlaybackGate.matchesPlayback(0L, 0L))
            assertEquals(0L, PracticePlaybackGate.token(old))
            PracticePlaybackGate.setReady(old, false)
            PracticePlaybackGate.leave(old)
            assertEquals(current, PracticePlaybackGate.readyToken)
        } finally {
            PracticePlaybackGate.leave(next)
        }
    }
}
