package app.luoxianlv.recognition

import app.luoxianlv.recognition.GlyphDetection.glyphs
import app.luoxianlv.recognition.GlyphDetection.modeLabels
import app.luoxianlv.recognition.KeyboardReference.MODE_GAPS
import app.luoxianlv.recognition.KeyboardReference.MODE_SEMI_OFFSET
import app.luoxianlv.recognition.KeyboardReference.MODE_Y_OFFSET
import kotlin.math.abs
import kotlin.math.sqrt

/** 对八键网格排序并排除较小的相邻升号；仅有三个字形时只是候选，须经 resolveLayout 的两行圆框验证后才能用于播放。 */
internal fun findNoteRows(
    glyphs: List<Glyph>,
    width: Int,
    minObserved: Int = 5,
): List<List<Glyph>> {
    val digits = glyphs
    val candidates = mutableMapOf<Triple<Int, Int, Int>, Pair<Float, List<Glyph>>>()
    for (seed in digits) {
        val band = digits.filter {
            abs(it.cy - seed.cy) <= 0.4f * seed.h && it.h >= seed.h * 0.67f && it.h <= seed.h * 1.5f
        }
        if (band.size < minObserved) continue
        // 按网格匹配，不要求连通域相邻，因为升号或场景可能夹在数字之间。
        for (second in band) {
            for (gap in 1..(9 - minObserved)) {
                val step = (second.cx - seed.cx) / gap
                if (step < width * .04f || step > width * .14f || step < seed.h * 1.8f) continue
                for (offset in 0..(8 - minObserved)) {
                    val startX = seed.cx - offset * step
                    if (startX <= 0 || startX + 7 * step >= width) continue
                    val grid =
                        (0..7).map { index ->
                            band
                                .filter { abs(it.cx - (startX + step * index)) <= step * .10f }
                                .minByOrNull { abs(it.cx - (startX + step * index)) }
                        }
                    val observed = grid.filterNotNull()
                    if (observed.size < minObserved) continue
                    // 边缘数字缺失会造成整键偏移；用独立音区行或邻近升号验证网格起点。
                    val ambiguousEdge =
                        listOf(0, 7).any { index ->
                            grid[index] == null &&
                                glyphs.none { sharp ->
                                    sharp.h < seed.h * .75f &&
                                        startX + step * index - sharp.cx in
                                            seed.h * .25f..seed.h * 1.5f &&
                                        seed.cy - sharp.cy in 0f..seed.h * .65f
                                }
                        }
                    if (
                        minObserved >= 5 &&
                            (ambiguousEdge ||
                                observed.size < 7 && (grid.first() == null || grid.last() == null))
                    ) {
                        val expectedY = seed.cy + MODE_Y_OFFSET * step
                        val expectedX =
                            FloatArray(4) { startX + (MODE_SEMI_OFFSET + MODE_GAPS[it]) * step }
                        val labels = modeLabels(glyphs, seed.cy, seed.h.toFloat(), expectedY, step)
                        if (
                            completeModeRow(
                                labels,
                                expectedX,
                                expectedY,
                                step,
                                seed.h.toFloat(),
                                minAnchors = 3,
                            ) == null
                        )
                            continue
                    }
                    val window = grid.mapIndexed { index, glyph ->
                        glyph
                            ?: run {
                                val cx = (startX + step * index).toInt()
                                val cy = observed.map { it.cy }.average().toInt()
                                Glyph(
                                    cx - seed.w / 2,
                                    cx + seed.w / 2,
                                    cy - seed.h / 2,
                                    cy + seed.h / 2,
                                    0,
                                )
                            }
                    }
                    val meanH = window.map { it.h }.average().toFloat()
                    if (
                        window.maxOf { it.h } > meanH * 1.5f || window.minOf { it.h } < meanH / 1.5f
                    )
                        continue
                    val minY = window.minOf { it.cy }
                    val maxY = window.maxOf { it.cy }
                    if (maxY - minY > 0.45f * meanH) continue
                    val xs = window.map { it.cx }
                    val meanSp = (xs.last() - xs.first()) / 7f
                    // 升号同样组成规则八键行，但比数字小；若多数候选右下方存在更高的字形，则拒绝该升号行。
                    val accidentals = observed.count { candidate ->
                        glyphs.any { digit ->
                            digit.h >= candidate.h * 1.4f &&
                                digit.cx - candidate.cx in candidate.h * 0.5f..candidate.h * 2.5f &&
                                digit.cy - candidate.cy in 0f..candidate.h.toFloat()
                        }
                    }
                    if (accidentals >= observed.size * .75f) continue
                    // HUD 文字也可能等距；琴键行须覆盖足够宽度，且间距大于字形宽度。
                    if (meanSp < meanH * 1.8f || xs.last() - xs.first() < width * 0.30f) continue
                    val cv =
                        sqrt(
                            xs.zipWithNext { a, b -> (b - a - meanSp) * (b - a - meanSp) }
                                .average()
                                .toFloat()
                        ) / meanSp
                    if (cv >= 0.12f) continue
                    val score = cv + (maxY - minY) / meanH * 0.1f + (8 - observed.size) * .15f
                    val key =
                        Triple(
                            (xs.first() / 4).toInt(),
                            (xs.last() / 4).toInt(),
                            (seed.cy / 4).toInt(),
                        )
                    if (score < (candidates[key]?.first ?: Float.MAX_VALUE)) {
                        candidates[key] = score to window
                    }
                }
            }
        }
    }
    return candidates.values.sortedBy { it.first }.take(12).map { it.second }
}
