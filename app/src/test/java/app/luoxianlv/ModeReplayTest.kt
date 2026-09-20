package app.luoxianlv

import app.luoxianlv.profile.ScreenRecognizer
import app.luoxianlv.core.score.PlayMode
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.awt.RenderingHints

/** Private captures are supplied locally, never bundled in the APK or Git. */
class ModeReplayTest {
    @Test fun verifiesFaultCaptureCoordinatesAndPitchState() {
        val directory = System.getenv("LX_MODE_REPLAY")
        assumeTrue(directory != null)
        val files = File(directory!!).listFiles()!!.filter { it.extension == "jpg" }
        assertEquals(9, files.size)
        for (file in files.sortedBy { it.name }) {
            val source = ImageIO.read(file)
            val w = 1024
            val h = (source.height * (1024f / source.width)).toInt()
            val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            scaled.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(source, 0, 0, w, h, null)
                dispose()
            }
            val luma = FloatArray(w*h) { i ->
                val c = scaled.getRGB(i%w, i/w)
                .299f*(c shr 16 and 255)+.587f*(c shr 8 and 255)+.114f*(c and 255)
            }
            val started = System.nanoTime()
            val result = ScreenRecognizer.analyze(luma,w,h)
            println("${file.name}: ${(System.nanoTime()-started)/1_000_000}ms")
            if (file.name.contains("153355") || file.name.contains("153356")) {
                assertNull("Covered keys must not be guessed", result)
                continue
            }
            assertNotNull(file.name, result)
            val layout = result!!.layout
            val expectedX = mapOf(PlayMode.SEMITONE to 851f, PlayMode.RAISE to 1113f,
                PlayMode.NATURAL to 1313f, PlayMode.LOWER to 1509f)
            for ((mode, x) in expectedX) {
                val point = layout.modes.getValue(mode)
                assertEquals("${file.name}: $mode x", x, point[0]*2362, 26f)
                assertEquals("${file.name}: $mode y", 459f, point[1]*1080, 12f)
            }
            assertEquals(433f,layout.noteX.first()*2362,12f)
            assertEquals(1926f,layout.noteX.last()*2362,12f)
            assertEquals(654f,layout.noteY*1080,10f)
            assertEquals(PlayMode.NATURAL,result.mode)
            val sharp = listOf("153450", "153520", "153531", "153549").any { file.name.contains(it) }
            assertEquals("${file.name}: semitone state",sharp,result.halfTone)
        }
    }
}
