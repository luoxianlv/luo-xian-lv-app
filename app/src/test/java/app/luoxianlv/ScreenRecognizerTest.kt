package app.luoxianlv
import app.luoxianlv.core.score.PlayMode
import app.luoxianlv.profile.ScreenRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScreenRecognizerTest {
    /** Reads a `.gray` fixture: 8-byte little-endian width/height header, then
     * one byte per pixel at 1024-wide downscale (pre-generated from the PNG
     * samples, since unit tests have no android Bitmap/ImageIO available). */
    private fun analyze(resource: String): ScreenRecognizer.Result {
        val bytes = javaClass.getResourceAsStream(resource)!!.readBytes()
        val header = ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
        val width = header.int
        val height = header.int
        val luma = FloatArray(width * height) { bytes[8 + it].toInt().and(0xff).toFloat() }
        val started = System.nanoTime()
        val result = ScreenRecognizer.analyze(luma, width, height)
        println("$resource recognized in ${(System.nanoTime() - started) / 1_000_000} ms")
        assertNotNull("$resource should be recognized", result)
        return result!!
    }

    private fun checkLayout(
        resource: String,
        noteYRange: ClosedFloatingPointRange<Float>,
        modeYRange: ClosedFloatingPointRange<Float>,
    ) {
        val result = analyze(resource)
        val layout = result.layout
        assertEquals(8, layout.noteX.size)
        // Note keys: strictly increasing, evenly spaced, inside the screen.
        val spacings = layout.noteX.toList().zipWithNext { a, b -> b - a }
        val mean = spacings.average()
        spacings.forEach { assertTrue("even spacing in $resource: $spacings", kotlin.math.abs(it - mean) < 0.08f * mean) }
        assertTrue(layout.noteX.first() > 0f && layout.noteX.last() < 1f)
        assertTrue("noteY ${layout.noteY} in $noteYRange", layout.noteY in noteYRange)
        // Mode buttons: left-to-right 半音 < 升调 < 自然音 < 降调, above the notes.
        val xs = listOf(PlayMode.SEMITONE, PlayMode.RAISE, PlayMode.NATURAL, PlayMode.LOWER).map { layout.modes.getValue(it) }
        xs.zipWithNext { a, b -> assertTrue("mode order in $resource", a[0] < b[0]) }
        xs.forEach {
            assertTrue("modeY ${it[1]} in $modeYRange", it[1] in modeYRange)
            assertTrue(it[1] < layout.noteY)
        }
    }

    private fun checkState(resource: String) {
        val result = analyze(resource)
        // All three samples show 自然音 active and 半音 off.
        assertEquals(PlayMode.NATURAL, result.mode)
        assertEquals(false, result.halfTone)
    }

    @Test fun blackBackground() {
        checkLayout("/keyboard-sample.gray", 0.60f..0.75f, 0.15f..0.35f)
        checkState("/keyboard-sample.gray")
    }

    @Test fun gameScene1920() {
        checkLayout("/keyboard-game-1920.gray", 0.55f..0.66f, 0.35f..0.50f)
        checkState("/keyboard-game-1920.gray")
    }

    @Test fun gameScene1280() {
        checkLayout("/keyboard-game-1280.gray", 0.55f..0.66f, 0.35f..0.50f)
        checkState("/keyboard-game-1280.gray")
    }
}
