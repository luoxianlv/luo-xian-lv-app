package app.luoxianlv.recognition

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 在局部梯度上拟合圆；文字只限定搜索区域。多个角区须具有一致的边缘极性，避免把穿过按钮的墙边当作圆框。 */
internal class ButtonBorderDetector(
    private val luma: FloatArray,
    private val width: Int,
    private val height: Int,
) {
    data class Circle(val x: Float, val y: Float, val radius: Float, val score: Float)

    private val gx = FloatArray(luma.size)
    private val gy = FloatArray(luma.size)
    private val gradientLeft = IntArray(height) { width }
    private val gradientRight = IntArray(height)

    /** 只计算圆框搜索会读取的区域；相邻按钮重叠的梯度在同次识别中共用。 */
    private fun prepareGradients(left: Int, right: Int, top: Int, bottom: Int) {
        val x0 = max(1, left)
        val x1 = min(width - 1, right)
        if (x0 >= x1) return
        for (y in max(1, top) until min(height - 1, bottom)) {
            if (gradientLeft[y] == width) {
                gradientRow(y, x0, x1)
            } else {
                gradientRow(y, x0, gradientLeft[y])
                gradientRow(y, gradientRight[y], x1)
            }
            gradientLeft[y] = min(gradientLeft[y], x0)
            gradientRight[y] = max(gradientRight[y], x1)
        }
    }

    private fun gradientRow(y: Int, left: Int, right: Int) {
        for (x in left until right) {
            val p = y * width + x
            gx[p] =
                (luma[p - width + 1] + 2 * luma[p + 1] + luma[p + width + 1] -
                    luma[p - width - 1] -
                    2 * luma[p - 1] -
                    luma[p + width - 1]) / 8f
            gy[p] =
                (luma[p + width - 1] + 2 * luma[p + width] + luma[p + width + 1] -
                    luma[p - width - 1] -
                    2 * luma[p - width] -
                    luma[p - width + 1]) / 8f
        }
    }

    fun locate(
        x: Float,
        y: Float,
        spacing: Float,
        searchX: Float,
        searchY: Float,
        minRadius: Float = .25f,
        maxRadius: Float = .41f,
    ): Circle? {
        val step = max(2, (spacing * .055f).roundToInt())
        val r0 = max(5, (spacing * minRadius).roundToInt())
        val r1 = max(r0, (spacing * maxRadius).roundToInt())
        // 精搜会越过粗搜中心一步，采样还包含半径两侧的一个像素。
        val margin = r1 + step + 3
        prepareGradients(
            (x - searchX).roundToInt() - margin,
            (x + searchX).roundToInt() + margin,
            (y - searchY).roundToInt() - margin,
            (y + searchY).roundToInt() + margin,
        )
        var best: Circle? = null
        fun search(
            left: Int,
            right: Int,
            top: Int,
            bottom: Int,
            lo: Int,
            hi: Int,
            stride: Int,
            radiusStride: Int,
            relaxed: Boolean,
        ) {
            for (r in lo..hi step radiusStride) {
                val ring = offsets(r)
                for (cy in max(r + 2, top)..min(height - r - 3, bottom) step stride) {
                    for (cx in max(r + 2, left)..min(width - r - 3, right) step stride) {
                        val score = score(cx, cy, ring, relaxed, best?.score ?: 0f)
                        if (score > (best?.score ?: 0f))
                            best = Circle(cx.toFloat(), cy.toFloat(), r.toFloat(), score)
                    }
                }
            }
        }
        search(
            (x - searchX).roundToInt(),
            (x + searchX).roundToInt(),
            (y - searchY).roundToInt(),
            (y + searchY).roundToInt(),
            r0,
            r1,
            step,
            2,
            relaxed = true,
        )
        val coarse = best ?: return null
        best = null
        search(
            coarse.x.toInt() - step,
            coarse.x.toInt() + step,
            coarse.y.toInt() - step,
            coarse.y.toInt() + step,
            max(r0, coarse.radius.toInt() - 2),
            min(r1, coarse.radius.toInt() + 2),
            1,
            1,
            relaxed = false,
        )
        return best?.takeIf { it.score >= 3f }
    }

    // 十二个按钮共用按半径缓存的采样偏移。
    private val rings = mutableMapOf<Int, IntArray>()

    private fun offsets(radius: Int): IntArray =
        rings.getOrPut(radius) {
            IntArray(SAMPLES * 3) { index ->
                val sample = index / 3
                val delta = index % 3 - 1
                ((radius + delta) * SIN[sample]).roundToInt() * width +
                    ((radius + delta) * COS[sample]).roundToInt()
            }
        }

    private fun score(
        cx: Int,
        cy: Int,
        ring: IntArray,
        relaxed: Boolean,
        minimumScore: Float,
    ): Float {
        var positive = 0f
        var negative = 0f
        var positiveCount = 0
        var negativeCount = 0
        var positiveSectors = 0
        var negativeSectors = 0
        var pSector = 0
        var nSector = 0
        val minCount = if (relaxed) 12 else 26
        val minSectors = if (relaxed) 3 else 6
        val center = cy * width + cx
        for (i in 0 until SAMPLES) {
            var p = 0f
            var n = 0f
            val cos = COS[i]
            val sin = SIN[i]
            for (delta in 0..2) {
                val index = center + ring[i * 3 + delta]
                val dx = gx[index]
                val dy = gy[index]
                val radial = dx * cos + dy * sin
                val tangent = -dx * sin + dy * cos
                if (abs(tangent) > abs(radial) * .8f) continue
                p = max(p, radial)
                n = max(n, -radial)
            }
            if (p >= 2f) {
                positiveCount++
                pSector++
            }
            if (n >= 2f) {
                negativeCount++
                nSector++
            }
            positive += min(p, 16f)
            negative += min(n, 16f)
            if (i % 6 == 5) {
                if (pSector >= 3) positiveSectors++
                if (nSector >= 3) negativeSectors++
                pSector = 0
                nSector = 0
                // 剩余角区即使全部命中也无法通过或胜过当前最优时，提前结束。
                val remaining = SAMPLES - i - 1
                val sectors = remaining / 6
                val pBound =
                    if (
                        positiveCount + remaining >= minCount &&
                            positiveSectors + sectors >= minSectors
                    ) {
                        val coverage = (positiveCount + remaining).toFloat() / SAMPLES
                        (positive + remaining * 16f) / SAMPLES * coverage * coverage
                    } else 0f
                val nBound =
                    if (
                        negativeCount + remaining >= minCount &&
                            negativeSectors + sectors >= minSectors
                    ) {
                        val coverage = (negativeCount + remaining).toFloat() / SAMPLES
                        (negative + remaining * 16f) / SAMPLES * coverage * coverage
                    } else 0f
                val bound = max(pBound, nBound)
                if (bound == 0f || bound + .0001f <= minimumScore) return 0f
            }
        }
        // 按角度覆盖度评分，避免少数高亮场景边缘压过较暗但连续的按钮圆环。
        val p =
            if (positiveCount >= minCount && positiveSectors >= minSectors)
                positive / SAMPLES * positiveCount / SAMPLES * positiveCount / SAMPLES
            else 0f
        val n =
            if (negativeCount >= minCount && negativeSectors >= minSectors)
                negative / SAMPLES * negativeCount / SAMPLES * negativeCount / SAMPLES
            else 0f
        return max(p, n)
    }

    companion object {
        private const val SAMPLES = 48
        private val COS = FloatArray(SAMPLES) { cos(it * 2.0 * PI / SAMPLES).toFloat() }
        private val SIN = FloatArray(SAMPLES) { sin(it * 2.0 * PI / SAMPLES).toFloat() }
    }
}
