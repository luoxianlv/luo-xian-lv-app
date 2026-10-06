package app.luoxianlv

import app.luoxianlv.library.PlayMode
import app.luoxianlv.recognition.ButtonBorderDetector
import app.luoxianlv.recognition.ScreenRecognizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class ButtonBorderRecognitionTest {
    private class Fixture(val pixels: FloatArray, val w: Int, val h: Int)

    private fun reference(resource: String = "/keyboard-reference.gray"): Fixture {
        val bytes = javaClass.getResourceAsStream(resource)!!.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val w = header.int
        val h = header.int
        return Fixture(FloatArray(w * h) { bytes[it + 8].toInt().and(255).toFloat() }, w, h)
    }

    private fun eraseText(f: Fixture, x: Float, y: Float, halfW: Float, halfH: Float) {
        // 保留按钮填充与圆框，仅擦除前景标签。
        val fill = f.pixels[(y - halfH - 2).roundToInt() * f.w + x.roundToInt()]
        for (yy in (y - halfH).roundToInt()..(y + halfH).roundToInt()) {
            for (xx in (x - halfW).roundToInt()..(x + halfW).roundToInt()) f.pixels[yy * f.w + xx] =
                fill
        }
    }

    @Test
    fun referenceBordersAreMeasured() {
        val f = reference()
        val result = ScreenRecognizer.analyze(f.pixels, f.w, f.h)!!
        println("Reference circles: ${result.noteBorders}/${result.modeBorders}")
        assertTrue(result.noteBorders >= 7)
        assertTrue(result.modeBorders >= 3)
    }

    @Test
    fun disjointGradientSearchesDoNotLeaveAnUncomputedGap() {
        val f = reference()
        val result = ScreenRecognizer.analyze(f.pixels, f.w, f.h)!!
        val spacing = (result.layout.noteX[1] - result.layout.noteX[0]) * f.w
        val cached = ButtonBorderDetector(f.pixels, f.w, f.h)
        for (i in listOf(7, 0, 4, 2, 6, 1, 5, 3)) {
            val x = result.layout.noteX[i] * f.w
            val y = result.layout.noteY * f.h
            val expected =
                ButtonBorderDetector(f.pixels, f.w, f.h)
                    .locate(x, y, spacing, spacing * .14f, spacing * .16f)
            assertEquals(expected, cached.locate(x, y, spacing, spacing * .14f, spacing * .16f))
        }
    }

    @Test
    fun threeDigitsPlusBordersRecoverTheWholeKeyboard() {
        for (resource in
            listOf(
                "/keyboard-reference.gray",
                "/keyboard-game-1920.gray",
                "/keyboard-game-1280.gray",
            )) {
            for (kept in listOf(setOf(0, 3, 7), setOf(1, 4, 6))) {
                val f = reference(resource)
                val original = ScreenRecognizer.analyze(f.pixels, f.w, f.h)!!
                val step = (original.layout.noteX[1] - original.layout.noteX[0]) * f.w
                for (i in 0..7) if (i !in kept) {
                    eraseText(
                        f,
                        original.layout.noteX[i] * f.w,
                        original.layout.noteY * f.h,
                        step * .22f,
                        step * .23f,
                    )
                }
                val result = ScreenRecognizer.analyze(f.pixels, f.w, f.h)
                assertNotNull("$resource visible digits $kept", result)
                assertTrue("Must really use sparse text", result!!.observedNotes <= 3)
                assertTrue("Missing digits require circle support", result.noteBorders >= 6)
                for (i in 0..7) assertEquals(
                    original.layout.noteX[i] * f.w,
                    result.layout.noteX[i] * f.w,
                    4f,
                )
                assertEquals(original.layout.noteY * f.h, result.layout.noteY * f.h, 3f)
            }
        }
    }

    @Test
    fun circlesRecoverModePositionsWhenAllLabelsDisappear() {
        val f = reference()
        val original = ScreenRecognizer.analyze(f.pixels, f.w, f.h)!!
        val step = (original.layout.noteX[1] - original.layout.noteX[0]) * f.w
        for (point in original.layout.modes.values) eraseText(
            f,
            point[0] * f.w,
            point[1] * f.h,
            step * .24f,
            step * .12f,
        )
        val result = ScreenRecognizer.analyze(f.pixels, f.w, f.h)
        assertNotNull(result)
        assertEquals(0, result!!.observedModes)
        assertTrue(result.modeBorders >= 3)
        for (mode in PlayMode.values()) {
            val expected = original.layout.modes.getValue(mode)
            val actual = result.layout.modes.getValue(mode)
            assertEquals(expected[0] * f.w, actual[0] * f.w, 4f)
            assertEquals(expected[1] * f.h, actual[1] * f.h, 3f)
        }
    }

    @Test
    fun modeRowScaleAndTranslationComeFromBorders() {
        for (scale in listOf(.85f, 1.13f)) {
            val f = reference()
            val original = ScreenRecognizer.analyze(f.pixels, f.w, f.h)!!
            val source = f.pixels.copyOf()
            val pivotX = f.w * .5f
            val pivotY = original.layout.modes.getValue(PlayMode.NATURAL)[1] * f.h
            val dx = 9f
            val dy = 18f
            val bottom = (original.layout.noteY * f.h - 55).toInt()
            for (y in 0 until bottom) for (x in 0 until f.w) {
                val sx = ((x - pivotX - dx) / scale + pivotX).roundToInt()
                val sy = ((y - pivotY - dy) / scale + pivotY).roundToInt()
                f.pixels[y * f.w + x] =
                    if (sx in 0 until f.w && sy in 0 until bottom) source[sy * f.w + sx] else 0f
            }
            val result = ScreenRecognizer.analyze(f.pixels, f.w, f.h)
            assertNotNull("Mode scale $scale", result)
            assertTrue(result!!.modeBorders >= 3)
            for (mode in PlayMode.values()) {
                val expected = original.layout.modes.getValue(mode)
                val actual = result.layout.modes.getValue(mode)
                assertEquals(
                    "$scale $mode X",
                    (expected[0] * f.w - pivotX) * scale + pivotX + dx,
                    actual[0] * f.w,
                    5f,
                )
                assertEquals(
                    "$scale $mode Y",
                    (expected[1] * f.h - pivotY) * scale + pivotY + dy,
                    actual[1] * f.h,
                    4f,
                )
            }
        }
    }

    @Test
    fun straightSceneryEdgesAreNotButtonBorders() {
        val w = 240
        val h = 180
        val pixels = FloatArray(w * h) { i -> if (i % w > 115 || i / w > 92) 210f else 20f }
        val detector = ButtonBorderDetector(pixels, w, h)
        assertNull(detector.locate(120f, 90f, 90f, 15f, 15f))
    }

    @Test
    fun keyboardScaleAndScreenMarginsDoNotChangeButtonIndices() {
        val source = reference()
        val original = ScreenRecognizer.analyze(source.pixels, source.w, source.h)!!
        val w = 1024
        val h = 600
        val scale = .76f
        val dx = 110f
        val dy = 190f
        val pixels =
            FloatArray(w * h) { i ->
                val x = ((i % w - dx) / scale).roundToInt()
                val y = ((i / w - dy) / scale).roundToInt()
                if (x in 0 until source.w && y in 0 until source.h) source.pixels[y * source.w + x]
                else 0f
            }
        val result = ScreenRecognizer.analyze(pixels, w, h)
        assertNotNull(result)
        for (i in 0..7) assertEquals(
            original.layout.noteX[i] * source.w * scale + dx,
            result!!.layout.noteX[i] * w,
            4f,
        )
        assertEquals(original.layout.noteY * source.h * scale + dy, result!!.layout.noteY * h, 3f)
        for (mode in PlayMode.values()) {
            val point = result.layout.modes.getValue(mode)
            val expected = original.layout.modes.getValue(mode)
            assertEquals(expected[0] * source.w * scale + dx, point[0] * w, 4f)
            assertEquals(expected[1] * source.h * scale + dy, point[1] * h, 4f)
        }
    }
}
