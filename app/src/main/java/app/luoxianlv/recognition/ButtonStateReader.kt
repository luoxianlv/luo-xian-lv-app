package app.luoxianlv.recognition

import kotlin.math.max
import kotlin.math.min

internal object ButtonStateReader {
    /** 选中的音区按钮是浅色实心圆，未选中时压暗背景；比较圆内中位亮度与周围背景，抵消天空、岩石等场景差异。 */
    fun contrast(
        luma: FloatArray,
        w: Int,
        h: Int,
        cx: Float,
        cy: Float,
        r: Float,
    ): Float {
        val rIn = r * 0.55f
        val rMid = r * 1.25f
        val rOut = r * 1.7f
        val inSquared = rIn * rIn
        val midSquared = rMid * rMid
        val outSquared = rOut * rOut
        val x0 = max(0, (cx - rOut).toInt())
        val x1 = min(w - 1, (cx + rOut).toInt() + 1)
        val y0 = max(0, (cy - rOut).toInt())
        val y1 = min(h - 1, (cy + rOut).toInt() + 1)
        val capacity = max(0, x1 - x0 + 1) * max(0, y1 - y0 + 1)
        val inner = FloatArray(capacity)
        val outer = FloatArray(capacity)
        var innerSize = 0
        var outerSize = 0
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = x - cx
                val dy = y - cy
                val d = dx * dx + dy * dy
                if (d <= inSquared) {
                    inner[innerSize++] = luma[y * w + x]
                } else if (d in midSquared..outSquared) {
                    outer[outerSize++] = luma[y * w + x]
                }
            }
        }
        if (innerSize == 0 || outerSize == 0) return 0f
        return median(inner, innerSize) - median(outer, outerSize)
    }

    private fun median(values: FloatArray, size: Int): Float {
        java.util.Arrays.sort(values, 0, size)
        return values[size / 2]
    }
}
