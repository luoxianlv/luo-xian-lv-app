package app.luoxianlv

import app.luoxianlv.service.PlaybackCoordinates
import app.luoxianlv.core.score.PlayMode
import app.luoxianlv.data.KeyLayout
import org.junit.Assert.*
import org.junit.Test

class PlaybackCoordinatesTest {
    private fun layout(xs: FloatArray) = KeyLayout(xs, .6f,
        PlayMode.values().associateWith { floatArrayOf(.5f, .4f) })

    @Test fun huaweiScreenshotCoordinatesRemainInTheCapturedPixelSpace() {
        // Reported display is 2400x1128, but accessibility captured 2400x1176.
        val frame = PlaybackCoordinates.Frame(2400,1176)
        val (x,y) = frame.point(1200f/2400f,1000f/1176f)
        assertEquals(1200f,x,.001f)
        assertEquals(1000f,y,.001f)
        assertEquals(588f,frame.point(.5f,.5f).second,.001f)
        val edge = frame.point(.99999f,.99999f)
        assertTrue(edge.first <= 2399 && edge.second <= 1175)
    }

    @Test fun rejectsCollapsedOrInvalidCoordinates() {
        assertTrue(PlaybackCoordinates.validLayout(layout(FloatArray(8) { .18f + it * .09f })))
        assertFalse(PlaybackCoordinates.validLayout(layout(FloatArray(8) { .01f })))
        assertFalse(PlaybackCoordinates.validPoint(Float.NaN, .5f))
        assertFalse(PlaybackCoordinates.validPoint(-.5f, 2f))
        assertFalse(PlaybackCoordinates.validPoint(0f, 1f))
        assertFalse(PlaybackCoordinates.validLayout(layout(floatArrayOf(.2f))))
    }
}
