package app.luoxianlv.profile

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Local circle fitting on image gradients. Text supplies a search window,
 * never the final radius. Several angular sectors must support the same edge
 * polarity, so a wall edge crossing a button is not mistaken for its border. */
internal class ButtonBorderDetector(luma: FloatArray, private val width: Int, private val height: Int) {
    data class Circle(val x: Float, val y: Float, val radius: Float, val score: Float)

    private val gx = FloatArray(luma.size)
    private val gy = FloatArray(luma.size)

    init {
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val p = y * width + x
            gx[p] = (luma[p-width+1] + 2*luma[p+1] + luma[p+width+1] -
                luma[p-width-1] - 2*luma[p-1] - luma[p+width-1]) / 8f
            gy[p] = (luma[p+width-1] + 2*luma[p+width] + luma[p+width+1] -
                luma[p-width-1] - 2*luma[p-width] - luma[p-width+1]) / 8f
        }
    }

    fun locate(
        x: Float, y: Float, spacing: Float, searchX: Float, searchY: Float,
        minRadius: Float = .25f, maxRadius: Float = .41f,
    ): Circle? {
        val step = max(2, (spacing * .055f).roundToInt())
        val r0 = max(5, (spacing * minRadius).roundToInt())
        val r1 = max(r0, (spacing * maxRadius).roundToInt())
        var best: Circle? = null
        fun search(left: Int, right: Int, top: Int, bottom: Int, lo: Int, hi: Int, stride: Int, radiusStride: Int, relaxed: Boolean) {
            for (r in lo..hi step radiusStride) {
                val ring = offsets(r)
                for (cy in max(r+2, top)..min(height-r-3, bottom) step stride) {
                    for (cx in max(r+2, left)..min(width-r-3, right) step stride) {
                        val score = score(cx, cy, ring, relaxed)
                        if (score > (best?.score ?: 0f)) best = Circle(cx.toFloat(), cy.toFloat(), r.toFloat(), score)
                    }
                }
            }
        }
        search((x-searchX).roundToInt(), (x+searchX).roundToInt(), (y-searchY).roundToInt(),
            (y+searchY).roundToInt(), r0, r1, step, 2, relaxed = true)
        val coarse = best ?: return null
        best = null
        search(coarse.x.toInt()-step, coarse.x.toInt()+step, coarse.y.toInt()-step,
            coarse.y.toInt()+step, max(r0, coarse.radius.toInt()-2), min(r1, coarse.radius.toInt()+2), 1, 1, relaxed = false)
        return best?.takeIf { it.score >= 3f }
    }

    // Cache radius-dependent sampling offsets across all twelve buttons.
    private val rings = mutableMapOf<Int, IntArray>()
    private fun offsets(radius: Int): IntArray = rings.getOrPut(radius) {
        IntArray(SAMPLES * 3 * 2) { index ->
            val sample = index / 6
            val delta = index / 2 % 3 - 1
            val direction = if (index % 2 == 0) COS[sample] else SIN[sample]
            ((radius + delta) * direction).roundToInt()
        }
    }

    private fun score(cx: Int, cy: Int, ring: IntArray, relaxed: Boolean): Float {
        var positive = 0f
        var negative = 0f
        var positiveCount = 0
        var negativeCount = 0
        var positiveSectors = 0
        var negativeSectors = 0
        var pSector = 0
        var nSector = 0
        for (i in 0 until SAMPLES) {
            var p = 0f
            var n = 0f
            for (delta in 0..2) {
                val offset = i*6 + delta*2
                val index = (cy + ring[offset+1]) * width + cx + ring[offset]
                val radial = gx[index]*COS[i] + gy[index]*SIN[i]
                val tangent = -gx[index]*SIN[i] + gy[index]*COS[i]
                if (abs(tangent) > abs(radial) * .8f) continue
                p = max(p, radial)
                n = max(n, -radial)
            }
            if (p >= 2f) { positiveCount++; pSector++ }
            if (n >= 2f) { negativeCount++; nSector++ }
            positive += min(p, 16f)
            negative += min(n, 16f)
            if (i % 6 == 5) {
                if (pSector >= 3) positiveSectors++
                if (nSector >= 3) negativeSectors++
                pSector = 0
                nSector = 0
            }
        }
        val minCount = if (relaxed) 12 else 26
        val minSectors = if (relaxed) 3 else 6
        // A handful of very bright scenery edges must not outweigh a weak but
        // continuous ring. Reward angular coverage, not just edge magnitude.
        val p = if (positiveCount >= minCount && positiveSectors >= minSectors)
            positive / SAMPLES * positiveCount / SAMPLES * positiveCount / SAMPLES else 0f
        val n = if (negativeCount >= minCount && negativeSectors >= minSectors)
            negative / SAMPLES * negativeCount / SAMPLES * negativeCount / SAMPLES else 0f
        return max(p, n)
    }

    companion object {
        private const val SAMPLES = 48
        private val COS = FloatArray(SAMPLES) { cos(it * 2.0 * PI / SAMPLES).toFloat() }
        private val SIN = FloatArray(SAMPLES) { sin(it * 2.0 * PI / SAMPLES).toFloat() }
    }
}
