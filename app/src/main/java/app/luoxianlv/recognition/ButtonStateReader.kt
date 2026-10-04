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
        val inner = mutableListOf<Float>()
        val outer = mutableListOf<Float>()
        val x0 = max(0, (cx - rOut).toInt())
        val x1 = min(w - 1, (cx + rOut).toInt() + 1)
        val y0 = max(0, (cy - rOut).toInt())
        val y1 = min(h - 1, (cy + rOut).toInt() + 1)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = x - cx
                val dy = y - cy
                val d = dx * dx + dy * dy
                if (d <= inSquared) {
                    inner.add(luma[y * w + x])
                } else if (d in midSquared..outSquared) {
                    outer.add(luma[y * w + x])
                }
            }
        }
        if (inner.isEmpty() || outer.isEmpty()) return 0f
        return median(inner) - median(outer)
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
