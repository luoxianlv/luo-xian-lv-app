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
        val x0 = IntArray(right - left) { max(0, left + it - radius) }
        val x1 = IntArray(right - left) { min(w, left + it + radius + 1) }
        val widths = IntArray(right - left) { x1[it] - x0[it] }
        for (y in top until bottom) {
            val y0 = max(0, y - radius)
            val y1 = min(h, y + radius + 1)
            val upper = y0 * stride
            val lower = y1 * stride
            val rows = y1 - y0
            val dst = y * w + left
            for (i in x0.indices) {
                val sum =
                    integral[lower + x1[i]] - integral[upper + x1[i]] - integral[lower + x0[i]] +
                        integral[upper + x0[i]]
                result[dst + i] = (sum / (widths[i] * rows)).toFloat()
            }
        }
        return result
    }
}
