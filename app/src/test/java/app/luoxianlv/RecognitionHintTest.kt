package app.luoxianlv

import app.luoxianlv.library.PlayMode
import app.luoxianlv.recognition.KeyLayout
import app.luoxianlv.recognition.ScreenRecognizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class RecognitionHintTest {
    private data class Fixture(val pixels: FloatArray, val w: Int, val h: Int)

    private fun fixture(): Fixture {
        val bytes = javaClass.getResourceAsStream("/keyboard-game-1280.gray")!!.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val w = header.int
        val h = header.int
        return Fixture(FloatArray(w * h) { bytes[it + 8].toInt().and(255).toFloat() }, w, h)
    }

    private fun assertCoordinates(
        expected: ScreenRecognizer.Result,
        actual: ScreenRecognizer.Result,
        w: Int,
        h: Int,
    ) {
        for (i in 0..7) assertEquals(expected.layout.noteX[i] * w, actual.layout.noteX[i] * w, 2f)
        assertEquals(expected.layout.noteY * h, actual.layout.noteY * h, 2f)
        for (mode in PlayMode.values()) {
            val a = expected.layout.modes.getValue(mode)
            val b = actual.layout.modes.getValue(mode)
            assertEquals(a[0] * w, b[0] * w, 2f)
            assertEquals(a[1] * h, b[1] * h, 2f)
        }
        assertEquals(expected.mode, actual.mode)
        assertEquals(expected.halfTone, actual.halfTone)
    }

    @Test
    fun currentFrameMustVerifyAllBordersAndReadCurrentPitch() {
        val f = fixture()
        val original = ScreenRecognizer.analyze(f.pixels, f.w, f.h)!!
        println("hint fixture borders=${original.noteBorders}/${original.modeBorders}")
        assertEquals(8, original.noteBorders)
        assertEquals(4, original.modeBorders)
        val checked = ScreenRecognizer.analyze(f.pixels, f.w, f.h, original)!!
        assertTrue(checked.reusedGeometry)
        assertCoordinates(original, checked, f.w, f.h)
        val changed = f.pixels.copyOf()
        for (mode in listOf(PlayMode.RAISE, PlayMode.NATURAL)) {
            val point = original.layout.modes.getValue(mode)
            val cx = point[0] * f.w
            val cy = point[1] * f.h
            val radius = original.modeRadius * .6f
            for (y in (cy - radius).toInt()..(cy + radius).toInt()) {
                for (x in (cx - radius).toInt()..(cx + radius).toInt()) {
                    if ((x - cx) * (x - cx) + (y - cy) * (y - cy) < radius * radius) {
                        changed[y * f.w + x] = if (mode == PlayMode.RAISE) 250f else 20f
                    }
                }
            }
        }
        val full = ScreenRecognizer.analyze(changed, f.w, f.h)!!
        val withHint = ScreenRecognizer.analyze(changed, f.w, f.h, original)!!
        assertEquals(PlayMode.RAISE, withHint.mode)
        assertCoordinates(full, withHint, f.w, f.h)
    }

    @Test
    fun changedScalePositionOrMissingKeyboardFallsBackToFullSearch() {
        val f = fixture()
        val original = ScreenRecognizer.analyze(f.pixels, f.w, f.h)!!
        for ((scale, dx, dy) in listOf(Triple(1f, 14f, 8f), Triple(.91f, 28f, 18f))) {
            val changed =
                FloatArray(f.pixels.size) { i ->
                    val x = ((i % f.w - dx) / scale).roundToInt()
                    val y = ((i / f.w - dy) / scale).roundToInt()
                    if (x in 0 until f.w && y in 0 until f.h) f.pixels[y * f.w + x] else 0f
                }
            val full = ScreenRecognizer.analyze(changed, f.w, f.h)!!
            val withHint = ScreenRecognizer.analyze(changed, f.w, f.h, original)!!
            assertFalse(withHint.reusedGeometry)
            assertCoordinates(full, withHint, f.w, f.h)
        }
        assertNull(ScreenRecognizer.analyze(FloatArray(f.pixels.size), f.w, f.h, original))
        val scenery = FloatArray(f.pixels.size)
        val firstX = original.layout.noteX[0] * f.w
        val firstY = original.layout.noteY * f.h
        for (y in 0 until f.h) for (x in 0 until f.w) {
            val distance =
                kotlin.math.sqrt((x - firstX) * (x - firstX) + (y - firstY) * (y - firstY))
            if (abs(distance - original.noteRadius) <= 2f) scenery[y * f.w + x] = 200f
        }
        assertNull("单个场景圆形不能复用整行", ScreenRecognizer.analyze(scenery, f.w, f.h, original))
        val occluded = f.pixels.copyOf()
        val cx = original.layout.noteX[3] * f.w
        val cy = original.layout.noteY * f.h
        for (y in 0 until f.h) for (x in 0 until f.w) {
            if (
                abs(x - cx) < original.noteRadius * 1.15f &&
                    abs(y - cy) < original.noteRadius * 1.15f
            )
                occluded[y * f.w + x] = 0f
        }
        val full = ScreenRecognizer.analyze(occluded, f.w, f.h)!!
        val withHint = ScreenRecognizer.analyze(occluded, f.w, f.h, original)!!
        assertFalse(withHint.reusedGeometry)
        assertCoordinates(full, withHint, f.w, f.h)
        assertNull(ScreenRecognizer.analyze(f.pixels, f.h, f.w, original))
    }

    @Test
    fun anUnverifiedLayoutCannotBypassCurrentImageEvidence() {
        val f = fixture()
        val original = ScreenRecognizer.analyze(f.pixels, f.w, f.h)!!
        val invalid =
            original.copy(
                layout = KeyLayout(FloatArray(8) { Float.NaN }, .5f, original.layout.modes)
            )
        val result = ScreenRecognizer.analyze(f.pixels, f.w, f.h, invalid)!!
        assertFalse(result.reusedGeometry)
        assertCoordinates(original, result, f.w, f.h)
    }
}
