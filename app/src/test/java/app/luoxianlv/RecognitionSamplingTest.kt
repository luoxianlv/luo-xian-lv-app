package app.luoxianlv

import app.luoxianlv.recognition.GlyphDetection
import app.luoxianlv.recognition.LuminanceIntegral
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Test

class RecognitionSamplingTest {
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
