package app.luoxianlv.profile

import app.luoxianlv.profile.GlyphDetection.coherent
import app.luoxianlv.profile.GlyphDetection.glyphs
import app.luoxianlv.profile.GlyphDetection.localMean
import app.luoxianlv.profile.GlyphDetection.modeLabels
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 恢复低对比标签并补全缺失项；连通域分组前先排除场景长边缘。 */
internal fun locateModeText(
    luma: FloatArray,
    w: Int,
    h: Int,
    xs: FloatArray,
    y: Float,
    spacing: Float,
    noteH: Float,
    noteY: Float,
): List<Label>? {
    val top = max(0, (y - .45f * spacing).toInt())
    val bottom = min(h - 1, (y + .45f * spacing).toInt())
    val fineMean = localMean(luma, w, h, 3)
    val evidence = mutableListOf<Label>()
    for (threshold in listOf(200f, 175f, 145f, 115f, 12f, 20f, 30f)) {
        val mask = BooleanArray(luma.size)
        for (yy in top..bottom) {
            for (xx in
                max(0, (xs.first() - .4f * spacing).toInt())..min(
                        w - 1,
                        (xs.last() + .4f * spacing).toInt(),
                    )) {
                val i = yy * w + xx
                mask[i] =
                    if (threshold < 100f) luma[i] > 100f && luma[i] - fineMean[i] > threshold
                    else luma[i] > threshold
            }
            var start = 0
            while (start < w) {
                if (!mask[yy * w + start]) {
                    start++
                    continue
                }
                var end = start + 1
                while (end < w && mask[yy * w + end]) end++
                if (end - start > noteH * 2.5f) {
                    for (xx in start until end) mask[yy * w + xx] = false
                }
                start = end
            }
        }
        val candidates = modeLabels(glyphs(mask, w, h), noteY, noteH, y, spacing)
        evidence.addAll(candidates)
        for (seed in evidence) {
            val row = xs.map { x ->
                evidence
                    .filter {
                        abs(it.cx - x) < .32f * spacing && abs(it.cy - seed.cy) < .4f * noteH
                    }
                    .minByOrNull { abs(it.cx - x) }
            }
            if (row.all { it != null } && coherent(row.filterNotNull(), spacing))
                return row.filterNotNull()
        }
    }
    return completeModeRow(evidence, xs, y, spacing, noteH)
}

/** 至少用两个不同且对齐的标签拟合平移，禁止由单个亮点推算整行；推算标签的 h=0，遮挡时状态仍为未知。 */
internal fun completeModeRow(
    evidence: List<Label>,
    xs: FloatArray,
    y: Float,
    spacing: Float,
    noteH: Float,
    minAnchors: Int = 2,
): List<Label>? {
    var best: List<Label>? = null
    var bestCount = 1
    var bestError = Float.MAX_VALUE
    for (seed in evidence) {
        if (abs(seed.cy - y) > .20f * spacing) continue
        val matches = xs.map { x ->
            evidence
                .filter {
                    abs(it.cx - x) <= .20f * spacing && abs(it.cy - seed.cy) <= .35f * noteH
                }
                .minByOrNull { abs(it.cx - x) }
        }
        val indices = matches.indices.filter { matches[it] != null }
        if (indices.size < minAnchors) continue
        val dx = indices.map { matches[it]!!.cx - xs[it] }.average().toFloat()
        val cy = indices.map { matches[it]!!.cy }.average().toFloat()
        val error = indices.maxOf { abs(matches[it]!!.cx - xs[it] - dx) }
        if (error > .08f * spacing) continue
        if (indices.size > bestCount || indices.size == bestCount && error < bestError) {
            bestCount = indices.size
            bestError = error
            best = xs.indices.map { matches[it] ?: Label(xs[it] + dx, cy, 0f) }
        }
    }
    return best
}
