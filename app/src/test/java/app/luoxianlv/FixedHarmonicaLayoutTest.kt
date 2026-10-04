package app.luoxianlv

import app.luoxianlv.library.PlayMode
import app.luoxianlv.playback.PlaybackCoordinates
import app.luoxianlv.practice.PracticeGeometry
import org.junit.Assert.*
import org.junit.Test

class FixedHarmonicaLayoutTest {
    @Test
    fun `不同屏幕比例的固定坐标均命中演练场全部琴键`() {
        for ((width, height) in
            listOf(2400 to 1080, 2400 to 1176, 1600 to 720, 2048 to 1536, 720 to 1600)) {
            val fit = PracticeGeometry.fit(width.toFloat(), height.toFloat())
            val layout = PracticeGeometry.keyLayout(width, height)
            assertTrue(PlaybackCoordinates.validLayout(layout))
            layout.noteX.forEachIndexed { index, x ->
                assertEquals(index, fit.hit(x * width, layout.noteY * height))
            }
            listOf(PlayMode.SEMITONE, PlayMode.RAISE, PlayMode.NATURAL, PlayMode.LOWER)
                .forEachIndexed { index, mode ->
                    val point = layout.modes.getValue(mode)
                    assertEquals(index + 8, fit.hit(point[0] * width, point[1] * height))
                }
        }
    }
}
