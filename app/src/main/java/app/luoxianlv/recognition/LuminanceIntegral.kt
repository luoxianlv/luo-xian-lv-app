package app.luoxianlv.recognition

import kotlin.math.max
import kotlin.math.min

/** 同一截图共用积分图；局部恢复标签时只输出需要的邻域均值。 */
internal class LuminanceIntegral(luma: FloatArray, private val w: Int, private val h: Int) {
    private val stride = w + 1
    private val integral = DoubleArray(stride * (h + 1))

    init {
        for (y in 0 until h) {
            var row = 0.0
            val src = y * w
            val dst = (y + 1) * stride
            for (x in 0 until w) {
                row += luma[src + x]
                integral[dst + x + 1] = integral[dst - stride + x + 1] + row
            }
        }
    }

    fun mean(
        radius: Int,
        left: Int = 0,
        right: Int = w,
        top: Int = 0,
        bottom: Int = h,
    ): FloatArray {
        val result = FloatArray(w * h)
        val middleLeft = max(left, min(right, radius))
        val middleRight = max(middleLeft, min(right, w - radius))
        val windowWidth = radius * 2 + 1
        for (y in top until bottom) {
            val y0 = max(0, y - radius)
            val y1 = min(h, y + radius + 1)
            val upper = y0 * stride
            val lower = y1 * stride
            val rows = y1 - y0
            val dst = y * w
            for (x in left until middleLeft) {
                result[dst + x] = clippedMean(x, radius, upper, lower, rows)
            }
            // 内部窗口宽度固定，沿积分图递增读取；除法与加减顺序保持原样。
            val denominator = (windowWidth * rows).toDouble()
            var upperLeft = upper + middleLeft - radius
            var upperRight = upper + middleLeft + radius + 1
            var lowerLeft = lower + middleLeft - radius
            var lowerRight = lower + middleLeft + radius + 1
            for (x in middleLeft until middleRight) {
                val sum =
                    integral[lowerRight++] - integral[upperRight++] - integral[lowerLeft++] +
                        integral[upperLeft++]
                result[dst + x] = (sum / denominator).toFloat()
            }
            for (x in middleRight until right) {
                result[dst + x] = clippedMean(x, radius, upper, lower, rows)
            }
        }
        return result
    }

    private fun clippedMean(x: Int, radius: Int, upper: Int, lower: Int, rows: Int): Float {
        val x0 = max(0, x - radius)
        val x1 = min(w, x + radius + 1)
        val sum =
            integral[lower + x1] - integral[upper + x1] - integral[lower + x0] +
                integral[upper + x0]
        return (sum / ((x1 - x0) * rows)).toFloat()
    }
}
