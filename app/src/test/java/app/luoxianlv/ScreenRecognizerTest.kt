package app.luoxianlv

import app.luoxianlv.core.score.PlayMode
import app.luoxianlv.profile.ScreenRecognizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRecognizerTest {
    @Test
    fun diagnosticArchiveCaptures() {
        val path = System.getenv("LX_ARCHIVE_FIXTURES") ?: return
        val files =
            java.io.File(path).listFiles { f -> f.extension == "gray" }!!.sortedBy { it.name }
        for (file in files) {
            val bytes = file.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val w = header.int
            val h = header.int
            val result =
                ScreenRecognizer.analyze(
                    FloatArray(w * h) { bytes[8 + it].toInt().and(255).toFloat() },
                    w,
                    h,
                )
            println(
                "ARCHIVE ${file.name}: ${result?.layout?.noteX?.joinToString()} y=${result?.layout?.noteY} mode=${result?.mode} borders=${result?.noteBorders}/${result?.modeBorders}"
            )
            val unavailable = h > w || listOf("100449", "100450", "100457").any { it in file.name }
            if (unavailable) {
                org.junit.Assert.assertNull(file.name, result)
            } else {
                assertNotNull(file.name, result)
                assertEquals(file.name, 0.605f, result!!.layout.noteY, 0.005f)
                val first =
                    when {
                        "2712" in file.name -> 0.190f
                        "2362" in file.name -> 0.184f
                        else -> 0.186f
                    }
                for (i in 0..7) assertEquals(
                    file.name,
                    first + i * (1f - 2f * first) / 7,
                    result.layout.noteX[i],
                    0.003f,
                )
                val expectedMode =
                    if (listOf("150819", "150835", "150841").any { it in file.name }) PlayMode.RAISE
                    else PlayMode.NATURAL
                assertEquals(file.name, expectedMode, result.mode)
                assertEquals(
                    file.name,
                    0.425f,
                    result.layout.modes.getValue(expectedMode)[1],
                    0.012f,
                )
            }
        }
    }

    @Test
    fun threeMissingDigitsAreRecoveredFromGridAndModeAnchors() {
        val bytes = javaClass.getResourceAsStream("/keyboard-sample.gray")!!.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val w = header.int
        val h = header.int
        val pixels = FloatArray(w * h) { bytes[8 + it].toInt().and(255).toFloat() }
        val original = ScreenRecognizer.analyze(pixels, w, h)!!
        val spacing = (original.layout.noteX[1] - original.layout.noteX[0]) * w
        for (missing in
            listOf(
                listOf(2, 3, 4),
                listOf(1, 4, 6),
                listOf(0, 1, 2),
                listOf(5, 6, 7),
                listOf(0, 3, 7),
            )) {
            val masked = pixels.copyOf()
            for (index in missing) {
                val x = (original.layout.noteX[index] * w).toInt()
                val y = (original.layout.noteY * h).toInt()
                val r = (spacing * 0.35f).toInt()
                for (yy in y - r..y + r) for (xx in x - r..x + r) masked[yy * w + xx] = 0f
            }
            val result = ScreenRecognizer.analyze(masked, w, h)
            assertNotNull("Missing $missing", result)
            assertEquals(5, result!!.observedNotes)
            for (i in 0..7) assertEquals(original.layout.noteX[i], result.layout.noteX[i], 0.005f)
            // 字形中心与圆盘中心略有偏差，允许三个分析像素误差。
            assertEquals(original.layout.noteY * h, result.layout.noteY * h, 3f)
        }
    }

    @Test
    fun partialModeLabelsFollowObservedRowAndKeepOccludedStateUnknown() {
        val bytes = javaClass.getResourceAsStream("/keyboard-sample.gray")!!.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val w = header.int
        val h = header.int
        val pixels = FloatArray(w * h) { bytes[8 + it].toInt().and(255).toFloat() }
        val original = ScreenRecognizer.analyze(pixels, w, h)!!
        val spacing = (original.layout.noteX[1] - original.layout.noteX[0]) * w
        val y = (original.layout.modes.getValue(PlayMode.NATURAL)[1] * h).toInt()
        val r = (spacing * 0.42f).toInt()
        val shift = (spacing * 0.12f).toInt()
        val masked = pixels.copyOf()
        for (yy in y - r..y + r + shift) for (xx in 0 until w) masked[yy * w + xx] = 0f
        for (yy in y - r..y + r) for (xx in 0 until w) masked[(yy + shift) * w + xx] =
            pixels[yy * w + xx]
        for (mode in listOf(PlayMode.SEMITONE, PlayMode.LOWER)) {
            val x = (original.layout.modes.getValue(mode)[0] * w).toInt()
            for (yy in y - r..y + r + shift) for (xx in x - r..x + r) masked[yy * w + xx] = 0f
        }
        val result = ScreenRecognizer.analyze(masked, w, h)
        assertNotNull(result)
        assertEquals(2, result!!.observedModes)
        for (mode in PlayMode.values()) {
            assertEquals(
                original.layout.modes.getValue(mode)[0],
                result.layout.modes.getValue(mode)[0],
                0.01f,
            )
            assertEquals(y + shift.toFloat(), result.layout.modes.getValue(mode)[1] * h, 4f)
        }
        org.junit.Assert.assertNull(result.halfTone)
        val single = masked.copyOf()
        val x = (original.layout.modes.getValue(PlayMode.RAISE)[0] * w).toInt()
        for (yy in y - r..y + r + shift) for (xx in x - r..x + r) single[yy * w + xx] = 0f
        org.junit.Assert.assertNull(
            "One mode anchor is insufficient",
            ScreenRecognizer.analyze(single, w, h),
        )
    }

    @Test
    fun modeRowMovesIndependentlyAndMissingLabelsAreRejected() {
        val bytes = javaClass.getResourceAsStream("/keyboard-sample.gray")!!.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val w = header.int
        val h = header.int
        val pixels = FloatArray(w * h) { bytes[8 + it].toInt().and(255).toFloat() }
        val original = ScreenRecognizer.analyze(pixels, w, h)!!
        val y = (original.layout.modes.getValue(PlayMode.NATURAL)[1] * h).toInt()
        val spacing = (original.layout.noteX[1] - original.layout.noteX[0]) * w
        val radius = (spacing * 0.42f).toInt()
        val shift = (spacing * 0.12f).toInt()
        val moved = pixels.copyOf()
        for (yy in y - radius..y + radius + shift) for (xx in 0 until w) moved[yy * w + xx] = 0f
        for (yy in y - radius..y + radius) for (xx in 0 until w) moved[(yy + shift) * w + xx] =
            pixels[yy * w + xx]
        val detected = ScreenRecognizer.analyze(moved, w, h)
        assertNotNull("Observed mode row must follow the image", detected)
        assertEquals(original.layout.noteY, detected!!.layout.noteY, 0.005f)
        for (mode in PlayMode.values()) {
            assertEquals(
                original.layout.modes.getValue(mode)[1] * h + shift,
                detected.layout.modes.getValue(mode)[1] * h,
                4f,
            )
        }
        val removed = pixels.copyOf()
        for (yy in 0 until (original.layout.noteY * h - spacing * 0.45f).toInt()) {
            for (xx in 0 until w) removed[yy * w + xx] = 0f
        }
        org.junit.Assert.assertNull(
            "No mode coordinates without image evidence",
            ScreenRecognizer.analyze(removed, w, h),
        )
    }

    @Test
    fun blankAndCompactHudTextAreRejected() {
        val width = 1024
        val height = 600
        val pixels = FloatArray(width * height)
        org.junit.Assert.assertNull(ScreenRecognizer.analyze(pixels, width, height))
        // 用短 HUD 标签模拟八个等距且接近数字大小的笔画。
        for (i in 0..7) {
            for (y in 400..420) for (x in 100 + i * 15..105 + i * 15) {
                if (x == 100 + i * 15 || y == 400 || y == 410) pixels[y * width + x] = 240f
            }
        }
        org.junit.Assert.assertNull(ScreenRecognizer.analyze(pixels, width, height))
    }

    /** 私人复现截图不进入 Git；持续集成使用公开测试样本。 */
    @Test
    fun privateReproductionCaptures() {
        val path = System.getenv("LX_DIAGNOSTIC_FIXTURES")
        org.junit.Assume.assumeTrue(path != null)
        val files = java.io.File(path!!).listFiles { f -> f.extension == "gray" }!!
        assertTrue(files.isNotEmpty())
        for (file in files) {
            val bytes = file.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val w = header.int
            val h = header.int
            val pixels = FloatArray(w * h) { bytes[8 + it].toInt().and(255).toFloat() }
            val start = System.nanoTime()
            val result = ScreenRecognizer.analyze(pixels, w, h)
            assertNotNull(file.name, result)
            assertTrue(result!!.layout.noteY in 0.58f..0.62f)
            assertTrue(result.layout.noteX.first() in 0.16f..0.20f)
            assertTrue(result.layout.noteX.last() in 0.78f..0.83f)
            assertEquals(PlayMode.NATURAL, result.mode)
            println("private capture recognized in ${(System.nanoTime() - start) / 1_000_000} ms")
        }
    }

    /** 读取 .gray 样本：前 8 字节为小端宽高，之后每像素一字节灰度，预先缩放至宽 1024；JVM 测试不依赖 Android Bitmap 或 ImageIO。 */
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
        // 音符键必须在屏幕内，横坐标严格递增且等距。
        val spacings = layout.noteX.toList().zipWithNext { a, b -> b - a }
        val mean = spacings.average()
        spacings.forEach {
            assertTrue(
                "even spacing in $resource: $spacings",
                kotlin.math.abs(it - mean) < 0.08f * mean,
            )
        }
        assertTrue(layout.noteX.first() > 0f && layout.noteX.last() < 1f)
        assertTrue("noteY ${layout.noteY} in $noteYRange", layout.noteY in noteYRange)
        // Mode buttons: left-to-right 半音 < 升调 < 自然音 < 降调, above the notes.
        val xs =
            listOf(PlayMode.SEMITONE, PlayMode.RAISE, PlayMode.NATURAL, PlayMode.LOWER).map {
                layout.modes.getValue(it)
            }
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

    @Test
    fun blackBackground() {
        checkLayout("/keyboard-sample.gray", 0.60f..0.75f, 0.15f..0.35f)
        checkState("/keyboard-sample.gray")
    }

    @Test
    fun userProvidedKeyboardReference() {
        checkLayout("/keyboard-reference.gray", 0.67f..0.71f, 0.22f..0.27f)
        checkState("/keyboard-reference.gray")
    }

    @Test
    fun gameScene1920() {
        checkLayout("/keyboard-game-1920.gray", 0.55f..0.66f, 0.35f..0.50f)
        checkState("/keyboard-game-1920.gray")
    }

    @Test
    fun gameScene1280() {
        checkLayout("/keyboard-game-1280.gray", 0.55f..0.66f, 0.35f..0.50f)
        checkState("/keyboard-game-1280.gray")
    }
}
