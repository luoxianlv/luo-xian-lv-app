package app.luoxianlv.recognition

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 负责阈值分割、连通域和字形分组，不依赖键盘布局。 */
internal object GlyphDetection {
    /** 提取 [mask] 中字形大小的连通域，并合并上下分离的同一字符，例如带点的 i。 */
    fun glyphs(
        mask: BooleanArray,
        w: Int,
        h: Int,
        left: Int = 0,
        right: Int = w,
        top: Int = 0,
        bottom: Int = h,
        stack: IntArray = IntArray((right - left) * (bottom - top)),
    ): List<Glyph> {
        val raw = mutableListOf<Glyph>()
        val neighbors = intArrayOf(-w - 1, -w, -w + 1, -1, 1, w - 1, w, w + 1)
        for (sy in top until bottom) for (sx in left until right) {
            val start = sy * w + sx
            if (!mask[start]) continue
            var pending = 0
            stack[pending++] = start
            mask[start] = false
            var x0 = Int.MAX_VALUE
            var x1 = Int.MIN_VALUE
            var y0 = Int.MAX_VALUE
            var y1 = Int.MIN_VALUE
            var count = 0
            while (pending > 0) {
                val p = stack[--pending]
                count++
                val y = p / w
                val x = p - y * w
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                // 区域内部的八个邻居均有效，直接用偏移；边界仍按原坐标裁剪。
                if (x > left && x < right - 1 && y > top && y < bottom - 1) {
                    for (offset in neighbors) {
                        val q = p + offset
                        if (mask[q]) {
                            mask[q] = false
                            stack[pending++] = q
                        }
                    }
                } else {
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = x + dx
                            val ny = y + dy
                            if (nx !in left until right || ny !in top until bottom) continue
                            val q = ny * w + nx
                            if (mask[q]) {
                                mask[q] = false
                                stack[pending++] = q
                            }
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
