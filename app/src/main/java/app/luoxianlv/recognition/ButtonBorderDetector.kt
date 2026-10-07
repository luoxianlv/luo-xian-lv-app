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

    private class Candidate {
        var x = 0f
        var y = 0f
        var radius = 0f
        var score = 0f
    }

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
            val topLeft = luma[p - width - 1]
            val topCenter = luma[p - width]
            val topRight = luma[p - width + 1]
            val centerLeft = luma[p - 1]
            val centerRight = luma[p + 1]
            val bottomLeft = luma[p + width - 1]
            val bottomCenter = luma[p + width]
            val bottomRight = luma[p + width + 1]
            gx[p] =
                (topRight + 2 * centerRight + bottomRight - topLeft - 2 * centerLeft - bottomLeft) /
                    8f
            gy[p] =
                (bottomLeft + 2 * bottomCenter + bottomRight - topLeft - 2 * topCenter - topRight) /
                    8f
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
        // 搜索时只更新同一候选，最终通过后才构造圆框，避免每次改进都创建对象。
        val best = Candidate()
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
            projection: Projection? = null,
        ) {
            for (r in lo..hi step radiusStride) {
                val ring = projection?.rings?.get(r - lo) ?: offsets(r)
                for (cy in max(r + 2, top)..min(height - r - 3, bottom) step stride) {
                    for (cx in max(r + 2, left)..min(width - r - 3, right) step stride) {
                        val score =
                            if (projection == null) {
                                score(cy * width + cx, ring, relaxed, best.score) { index, i ->
                                    val dx = gx[index]
                                    val dy = gy[index]
                                    val radial = dx * COS[i] + dy * SIN[i]
                                    val tangent = -dx * SIN[i] + dy * COS[i]
                                    if (abs(tangent) > abs(radial) * .8f) 0f else radial
                                }
                            } else {
                                score(cy * projection.stride + cx, ring, relaxed, best.score) {
                                    index,
                                    _ ->
                                    projection.values[index]
                                }
                            }
                        if (score > best.score) {
                            best.x = cx.toFloat()
                            best.y = cy.toFloat()
                            best.radius = r.toFloat()
                            best.score = score
                        }
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
        if (best.score == 0f) return null
        val coarseX = best.x.toInt()
        val coarseY = best.y.toInt()
        val coarseRadius = best.radius.toInt()
        val fineLo = max(r0, coarseRadius - 2)
        val fineHi = min(r1, coarseRadius + 2)
        val projection = project(coarseX, coarseY, step, fineLo, fineHi)
        best.score = 0f
        search(
            coarseX - step,
            coarseX + step,
            coarseY - step,
            coarseY + step,
            fineLo,
            fineHi,
            1,
            1,
            relaxed = false,
            projection = projection,
        )
        return if (best.score >= 3f) Circle(best.x, best.y, best.radius, best.score) else null
    }

    // 十二个按钮共用按半径缓存的采样偏移。
    private val rings = mutableMapOf<Int, IntArray>()

    private fun offsets(radius: Int): IntArray =
        rings.getOrPut(radius) {
            val offsets = IntArray(SAMPLES * 3)
            for (sample in 0 until SAMPLES) {
                val sin = SIN[sample]
                val cos = COS[sample]
                for (delta in 0..2) {
                    offsets[sample * 3 + delta] =
                        ((radius + delta - 1) * sin).roundToInt() * width +
                            ((radius + delta - 1) * cos).roundToInt()
                }
            }
            offsets
        }

    private class Projection(val stride: Int, val values: FloatArray, val rings: Array<IntArray>)

    private var projectionValues = FloatArray(0)
    private val projectionRings by lazy { Array(5) { IntArray(SAMPLES * 3) } }

    /** 精搜相邻圆重复读取同一方向梯度；只缓存各角度附近的小块，评分顺序和采样位置不变。 */
    private fun project(cx: Int, cy: Int, step: Int, lo: Int, hi: Int): Projection {
        val stride = 2 * step + hi - lo + 3
        val area = stride * stride
        // 十二个圆框串行使用同一工作区，生命周期仅限当前帧。
        if (projectionValues.size < SAMPLES * area) projectionValues = FloatArray(SAMPLES * area)
        val values = projectionValues
        val rings = projectionRings
        for (i in 0 until SAMPLES) {
            val cos = COS[i]
            val sin = SIN[i]
            val left = cx - step + min(((lo - 1) * cos).roundToInt(), ((hi + 1) * cos).roundToInt())
            val top = cy - step + min(((lo - 1) * sin).roundToInt(), ((hi + 1) * sin).roundToInt())
            val origin = i * area - top * stride - left
            for (y in max(1, top) until min(height - 1, top + stride)) {
                var source = y * width + max(1, left)
                var target = origin + y * stride + max(1, left)
                for (x in max(1, left) until min(width - 1, left + stride)) {
                    val dx = gx[source]
                    val dy = gy[source++]
                    val radial = dx * cos + dy * sin
                    val tangent = -dx * sin + dy * cos
                    values[target++] = if (abs(tangent) > abs(radial) * .8f) 0f else radial
                }
            }
            for (radius in lo..hi) for (delta in 0..2) {
                rings[radius - lo][i * 3 + delta] =
                    origin +
                        ((radius + delta - 1) * sin).roundToInt() * stride +
                        ((radius + delta - 1) * cos).roundToInt()
            }
        }
        return Projection(stride, values, rings)
    }

    private inline fun score(
        center: Int,
        ring: IntArray,
        relaxed: Boolean,
        minimumScore: Float,
        sample: (Int, Int) -> Float,
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
        var sectorEnd = 5
        for (i in 0 until SAMPLES) {
            var p = 0f
            var n = 0f
            for (delta in 0..2) {
                val index = center + ring[i * 3 + delta]
                val radial = sample(index, i)
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
            if (i == sectorEnd) {
                sectorEnd += 6
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
