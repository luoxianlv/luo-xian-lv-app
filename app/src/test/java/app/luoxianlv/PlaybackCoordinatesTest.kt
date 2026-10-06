package app.luoxianlv

import app.luoxianlv.library.PlayMode
import app.luoxianlv.playback.PlaybackCoordinates
import app.luoxianlv.recognition.KeyLayout
import org.junit.Assert.*
import org.junit.Test

class PlaybackCoordinatesTest {
    private fun layout(xs: FloatArray) =
        KeyLayout(
            xs,
            .6f,
            PlayMode.values().associateWith { floatArrayOf(.5f, .4f) },
        )

    @Test
    fun huaweiScreenshotCoordinatesRemainInTheCapturedPixelSpace() {
        // 系统显示为 2400×1128，但无障碍截图为 2400×1176。
        val frame = PlaybackCoordinates.Frame(2400, 1176)
        val (x, y) = frame.point(1200f / 2400f, 1000f / 1176f)
        assertEquals(1200f, x, .001f)
        assertEquals(1000f, y, .001f)
        assertEquals(588f, frame.point(.5f, .5f).second, .001f)
        val edge = frame.point(.99999f, .99999f)
        assertTrue(edge.first <= 2399 && edge.second <= 1175)
    }

    @Test
    fun rejectsCollapsedOrInvalidCoordinates() {
        assertTrue(PlaybackCoordinates.validLayout(layout(FloatArray(8) { .18f + it * .09f })))
        assertFalse(PlaybackCoordinates.validLayout(layout(FloatArray(8) { .01f })))
        assertFalse(PlaybackCoordinates.validPoint(Float.NaN, .5f))
        assertFalse(PlaybackCoordinates.validPoint(-.5f, 2f))
        assertFalse(PlaybackCoordinates.validPoint(0f, 1f))
        assertFalse(PlaybackCoordinates.validLayout(layout(floatArrayOf(.2f))))
    }

    @Test
    fun equalFramesKeepTheSamePixelForBothInputModes() {
        val frame = PlaybackCoordinates.Frame(2400, 1128)
        val point = frame.point(.5f, .75f)
        assertEquals(1200f, point.first, .001f)
        assertEquals(846f, point.second, .001f)
        assertTrue(PlaybackCoordinates.fitsDisplay(point.first, point.second, 2400, 1128))
    }

    @Test
    fun differingScreenshotHeightDoesNotRescaleAWorkingAccessibilityPoint() {
        val frame = PlaybackCoordinates.Frame(2400, 1176)
        val point = frame.point(1200f / 2400f, 1000f / 1176f)
        assertEquals(1000f, point.second, .001f)
        assertTrue(PlaybackCoordinates.fitsDisplay(point.first, point.second, 2400, 1128))
        assertFalse(PlaybackCoordinates.fitsDisplay(1200f, 1150f, 2400, 1128))
        assertFalse(PlaybackCoordinates.fitsDisplay(Float.NaN, 1000f, 2400, 1128))
    }

    @Test
    fun windowOriginAndSizeAreAppliedBeforeDisplayNormalization() {
        val result =
            checkNotNull(
                PlaybackCoordinates.windowLayout(
                    layout(FloatArray(8) { .18f + it * .09f }),
                    PlaybackCoordinates.Frame(1000, 500),
                    PlaybackCoordinates.Frame(2800, 1260),
                    300,
                    100,
                )
            )
        val point = PlaybackCoordinates.Frame(2800, 1260).point(result.noteX.first(), result.noteY)
        assertEquals(480f, point.first, .001f)
        assertEquals(400f, point.second, .001f)
        assertEquals(800f, result.modes.getValue(PlayMode.NATURAL)[0] * 2800, .001f)
    }

    @Test
    fun clippedWindowCannotSupplyOffscreenKeys() {
        assertNull(
            PlaybackCoordinates.windowLayout(
                layout(FloatArray(8) { .18f + it * .09f }),
                PlaybackCoordinates.Frame(1000, 500),
                PlaybackCoordinates.Frame(2800, 1260),
                -900,
                100,
            )
        )
    }
}
