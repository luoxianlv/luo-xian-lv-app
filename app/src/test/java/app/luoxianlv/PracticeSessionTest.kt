package app.luoxianlv

import app.luoxianlv.practice.PracticeGeometry
import app.luoxianlv.practice.PracticeSession
import org.junit.Assert.*
import org.junit.Test

class PracticeSessionTest {
    @Test
    fun everyModeAndHalfCombinationUsesTheGamePitch() {
        val intervals = listOf(0, 2, 4, 5, 7, 9, 11, 12)
        val pitches = mutableSetOf<Int>()
        for (mode in PracticeSession.Mode.entries) for (half in listOf(false, true)) for (key in
            0..7) {
            val pitch = PracticeSession.pitch(key, mode, half)
            assertEquals(60 + intervals[key] + mode.semitones + if (half) 1 else 0, pitch)
            pitches += pitch
        }
        assertEquals((48..85).toSet(), pitches)
        assertEquals("Harmonica_C4.wav", PracticeSession.sampleName(48))
        assertEquals("Harmonica_Cs7.wav", PracticeSession.sampleName(85))
    }

    @Test
    fun staleReleaseDoesNotStopNewNoteOrRestoreOldNote() {
        val session = PracticeSession()
        session.press(8, 0)
        session.press(12, 4)
        assertFalse(session.release(8))
        assertEquals(4, session.active!!.key)
        assertTrue(session.release(12))
        assertNull(session.active)
    }

    @Test
    fun modeAndHalfAreImmediateButDoNotRetriggerHeldNote() {
        val session = PracticeSession()
        val held = session.press(1, 0)
        session.select(PracticeSession.Mode.RAISE)
        session.toggleHalf()
        assertEquals(PracticeSession.Mode.RAISE, session.mode)
        assertTrue(session.half)
        assertEquals(held, session.active)
        assertEquals(73, session.press(2, 0).midi)
        session.select(PracticeSession.Mode.LOWER)
        assertEquals(49, session.press(3, 0).midi)
        session.cancel()
        assertNull(session.active)
    }

    @Test
    fun geometryKeepsCirclesAndHitTargetsAlignedAcrossAspectRatios() {
        for ((w, h) in listOf(2388f to 1080f, 1920f to 1080f, 2560f to 1600f, 1200f to 600f)) {
            val fit = PracticeGeometry.fit(w, h)
            (PracticeGeometry.notes + PracticeGeometry.modes).forEachIndexed { index, key ->
                assertEquals(
                    index,
                    fit.hit(fit.left + key.x * fit.scale, fit.top + key.y * fit.scale),
                )
                assertNull(
                    fit.hit(
                        fit.left + (key.x + key.radius + 5) * fit.scale,
                        fit.top + key.y * fit.scale,
                    )
                )
            }
            assertTrue(fit.top >= 0)
            assertTrue(fit.top + PracticeGeometry.HEIGHT * fit.scale < h)
        }
    }
}
