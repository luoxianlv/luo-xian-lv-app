package app.luoxianlv.recognition

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 负责阈值分割、连通域和字形分组，不依赖键盘布局。 */
internal object GlyphDetection {
    /** 利用积分图计算邻域均值。 */
    fun localMean(
        luma: FloatArray,
        w: Int,
        h: Int,
        r: Int,
    ): FloatArray {
        val integral = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            val src = y * w
            val dst = (y + 1) * (w + 1)
            for (x in 0 until w) {
                row += luma[src + x]
                integral[dst + x + 1] = integral[dst - (w + 1) + x + 1] + row
            }
        }
        return FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val x0 = max(0, x - r)
            val x1 = min(w, x + r + 1)
            val y0 = max(0, y - r)
            val y1 = min(h, y + r + 1)
            val sum =
                integral[y1 * (w + 1) + x1] -
                    integral[y0 * (w + 1) + x1] -
                    integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
            (sum / ((x1 - x0) * (y1 - y0))).toFloat()
        }
    }

    /** 提取 [mask] 中字形大小的连通域，并合并上下分离的同一字符，例如带点的 i。 */
    fun glyphs(
        mask: BooleanArray,
        w: Int,
        h: Int,
    ): List<Glyph> {
        val stack = IntArray(mask.size)
        val raw = mutableListOf<Glyph>()
        for (start in mask.indices) {
            if (!mask[start]) continue
            var top = 0
            stack[top++] = start
            mask[start] = false
            var x0 = Int.MAX_VALUE
            var x1 = Int.MIN_VALUE
            var y0 = Int.MAX_VALUE
            var y1 = Int.MIN_VALUE
            var count = 0
            while (top > 0) {
                val p = stack[--top]
                count++
                val x = p % w
                val y = p / w
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until w || ny !in 0 until h) continue
                        val q = ny * w + nx
                        if (mask[q]) {
                            mask[q] = false
                            stack[top++] = q
                        }
                    }
                }
            }
            val bw = x1 - x0 + 1
            val bh = y1 - y0 + 1
            if (bh !in 8..70 || bw !in 3..60) continue
            val extent = count.toFloat() / (bw * bh)
            if (extent < 0.15f || extent > 0.95f) continue
            raw.add(Glyph(x0, x1 + 1, y0, y1 + 1, count))
        }
        raw.sortBy { it.x0 }
        val merged = mutableListOf<Glyph>()
        for (g in raw) {
            val host = merged.firstOrNull { m ->
                min(g.x1, m.x1) - max(g.x0, m.x0) > 0 &&
                    g.y0 - m.y1 in 0..(0.7f * max(g.h, m.h)).toInt()
            }
            if (host == null) merged.add(g) else host.merge(g)
        }
        return merged
    }

    /** 将每个音区标签的 2～3 个字形合并。 */
    fun modeLabels(
        glyphs: List<Glyph>,
        noteY: Float,
        noteH: Float,
        predictedY: Float,
        spacing: Float,
    ): List<Label> {
        val candidates =
            glyphs
                .filter {
                    it.cy < noteY - 1.2f * noteH &&
                        abs(it.cy - predictedY) < 0.7f * spacing &&
                        it.h <= 1.2f * noteH &&
                        it.h >= 6 &&
                        it.w >= .4f * it.h &&
                        it.w <= 1.8f * it.h
                }
                .sortedBy { it.cx }
        val groups = mutableListOf<MutableList<Glyph>>()
        for (g in candidates) {
            val host = groups.firstOrNull { grp ->
                val gy = grp.map { it.cy }.average().toFloat()
                val gh = grp.map { it.h }.average().toFloat()
                val right = grp.maxOf { it.cx + it.w / 2f }
                abs(g.cy - gy) <= 0.4f * gh && g.cx - g.w / 2f - right <= .7f * gh
            }
            if (host == null) groups.add(mutableListOf(g)) else host.add(g)
        }
        return groups.mapNotNull { grp ->
            if (grp.size !in 1..3) return@mapNotNull null
            val median = grp.map { it.h }.sorted()[grp.size / 2]
            val kept = grp.filter { it.h <= 1.8f * median }
            val left = kept.minOf { it.cx - it.w / 2f }
            val right = kept.maxOf { it.cx + it.w / 2f }
            Label(
                (left + right) / 2f,
                kept.map { it.cy }.average().toFloat(),
                kept.map { it.h }.average().toFloat(),
            )
        }
    }

    /** 完整音区行的标签须对齐且大小接近。 */
    fun coherent(
        labels: List<Label>,
        spacing: Float,
    ): Boolean {
        val meanH = labels.map { it.h }.average().toFloat()
        if (labels.maxOf { it.h } > meanH * 1.6f || labels.minOf { it.h } < meanH / 1.6f)
            return false
        if (labels.maxOf { it.cy } - labels.minOf { it.cy } > 0.8f * meanH) return false
        val xs = labels.map { it.cx }
        val meanSp = (xs.last() - xs.first()) / 3f
        val cv =
            sqrt(
                xs.zipWithNext { a, b -> (b - a - meanSp) * (b - a - meanSp) }.average().toFloat()
            ) / meanSp
        return cv < 0.25f
    }
}
