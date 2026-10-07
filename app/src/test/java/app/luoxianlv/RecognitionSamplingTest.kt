package app.luoxianlv

import app.luoxianlv.recognition.GlyphDetection
import app.luoxianlv.recognition.LuminanceIntegral
import app.luoxianlv.recognition.ScreenRecognizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RecognitionSamplingTest {
    @Test
    fun connectivityClipsAllRegionEdgesWithoutChangingOutsidePixels() {
        val w = 160
        val h = 90
        val left = 60
        val right = 110
        val top = 15
        val bottom = 55
        val mask = BooleanArray(w * h)
        // 一个连通框跨越四个裁剪边界，区域外的白色不能被纳入或消费。
        for (y in top - 2..bottom + 1) for (x in left - 2..right + 1) {
            mask[y * w + x] = x < left + 4 || x >= right - 4 || y < top + 4 || y >= bottom - 4
        }
        val clipped = mask.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            if (x !in left until right || y !in top until bottom) clipped[y * w + x] = false
        }
        val expected = GlyphDetection.glyphs(clipped, w, h)
        val regionalMask = mask.copyOf()
        val actual = GlyphDetection.glyphs(regionalMask, w, h, left, right, top, bottom)
        assertEquals(1, expected.size)
        assertEquals(
            expected.map { listOf(it.x0, it.x1, it.y0, it.y1, it.pixels) },
            actual.map { listOf(it.x0, it.x1, it.y0, it.y1, it.pixels) },
        )
        for (y in 0 until h) for (x in 0 until w) {
            if (x !in left until right || y !in top until bottom)
                assertEquals(mask[y * w + x], regionalMask[y * w + x])
        }
    }

    @Test
    fun scratchBuffersNeverModifyCallerPixels() {
        val bytes =
            javaClass.getResourceAsStream("/keyboard-game-1280.gray")!!.use { it.readBytes() }
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val w = header.int
        val h = header.int
        val original = FloatArray(w * h) { bytes[it + 8].toInt().and(255).toFloat() }
        val pixels = original.copyOf()
        val previous = requireNotNull(ScreenRecognizer.analyze(pixels, w, h))
        repeat(2) { requireNotNull(ScreenRecognizer.analyze(pixels, w, h, previous)) }
        assertArrayEquals(original, pixels, 0f)
    }

    @Test
    fun argbStripsKeepChannelsOffsetsAndPartialTail() {
        val pixels =
            intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0x110000ff, 0xffffffff.toInt())
        val luma = FloatArray(7) { -1f }
        ScreenRecognizer.copyLuminance(pixels, luma, 2, 3)
        assertEquals(-1f, luma[0], 0f)
        assertEquals(-1f, luma[1], 0f)
        assertEquals(76.245f, luma[2], .00002f)
        assertEquals(149.685f, luma[3], .00002f)
        assertEquals(29.07f, luma[4], .00002f)
        assertEquals(-1f, luma[5], 0f)
        assertEquals(-1f, luma[6], 0f)
        pixels[0] = 0x00ffffff
        ScreenRecognizer.copyLuminance(pixels, luma, 5, 1)
        assertEquals(255f, luma[5], .00002f)
        assertEquals(-1f, luma[6], 0f)
    }

    @Test
    fun integralMeansKeepBorderClippingAndFractionalValues() {
        val w = 19
        val h = 13
        val pixels = FloatArray(w * h) { (it * 71 % 997) / 4f }
        val integral = LuminanceIntegral(pixels, w, h)
        for (radius in listOf(0, 3, 18)) {
            val full = integral.mean(radius)
            val region = integral.mean(radius, 5, 17, 2, 10)
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0.0
                var count = 0
                for (yy in max(0, y - radius)..min(h - 1, y + radius)) {
                    for (xx in max(0, x - radius)..min(w - 1, x + radius)) {
                        sum += pixels[yy * w + xx]
                        count++
                    }
                }
                assertEquals((sum / count).toFloat(), full[y * w + x], 0f)
                assertEquals(
                    if (x in 5 until 17 && y in 2 until 10) full[y * w + x] else 0f,
                    region[y * w + x],
                    0f,
                )
            }
        }
    }

    @Test
    fun integralMeansKeepExactValuesForSmallAndLargeWindows() {
        for ((w, h) in listOf(1 to 1, 2 to 5, 7 to 2, 29 to 23)) {
            val pixels = FloatArray(w * h) { (it * 71 % 997) / 4f }
            val integral = LuminanceIntegral(pixels, w, h)
            for (radius in listOf(0, 1, 3, 18)) {
                val mean = integral.mean(radius)
                for (y in 0 until h) for (x in 0 until w) {
                    var sum = 0.0
                    var count = 0
                    for (yy in max(0, y - radius)..min(h - 1, y + radius)) {
                        for (xx in max(0, x - radius)..min(w - 1, x + radius)) {
                            sum += pixels[yy * w + xx]
                            count++
                        }
                    }
                    assertEquals((sum / count).toFloat(), mean[y * w + x], 0f)
                }
            }
        }
    }

    @Test
    fun regionConnectivityKeepsGlobalCoordinatesAndDiagonalNeighbors() {
        val w = 160
        val h = 90
        val mask = BooleanArray(w * h)
        // 分离的小点与竖笔画共享字符，左下角为区域外干扰。
        for (y in 22..42) mask[y * w + 75] = true
        for (x in 75..86) mask[42 * w + x] = true
        mask[21 * w + 74] = true
        for (y in 24..31) for (x in 75..83) mask[y * w + x] = true
        for (y in 65..85) mask[y * w + 8] = true
        for (x in 8..17) mask[85 * w + x] = true
        val full = GlyphDetection.glyphs(mask.copyOf(), w, h).filter { it.x0 > 50 }
        val stack = IntArray(w * h)
        repeat(3) {
            val region = GlyphDetection.glyphs(mask.copyOf(), w, h, 60, 110, 15, 55, stack)
            assertEquals(full.size, region.size)
            for (i in full.indices) {
                assertEquals(full[i].x0, region[i].x0)
                assertEquals(full[i].x1, region[i].x1)
                assertEquals(full[i].y0, region[i].y0)
                assertEquals(full[i].y1, region[i].y1)
                assertEquals(full[i].pixels, region[i].pixels)
            }
        }
    }
}
