package app.luoxianlv.profile

import app.luoxianlv.profile.GlyphDetection.glyphs
import app.luoxianlv.profile.GlyphDetection.modeLabels
import app.luoxianlv.profile.KeyboardReference.MODE_GAPS
import app.luoxianlv.profile.KeyboardReference.MODE_SEMI_OFFSET
import app.luoxianlv.profile.KeyboardReference.MODE_Y_OFFSET
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Rank distinct eight-key grids, rejecting smaller adjacent sharp signs. Three-glyph candidates are
 * only proposals; both border rows must validate them in resolveLayout before they can be used for
 * playback.
 */
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
        // Match a grid rather than requiring adjacent components in the
        // component list: sharp signs and scenery may sit between digits.
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
                    // Missing edge digits make the grid's index ambiguous. Validate
                    // the origin against the separate mode row (or nearby sharps).
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
                    // The eight sharp signs form an exceptionally regular row,
                    // but are much smaller than the digits next to them. Reject
                    // a row when most candidates have a taller glyph just right
                    // and below them (the actual note digit).
                    val accidentals = observed.count { candidate ->
                        glyphs.any { digit ->
                            digit.h >= candidate.h * 1.4f &&
                                digit.cx - candidate.cx in candidate.h * 0.5f..candidate.h * 2.5f &&
                                digit.cy - candidate.cy in 0f..candidate.h.toFloat()
                        }
                    }
                    if (accidentals >= observed.size * .75f) continue
                    // A row of HUD text can also be evenly spaced. Piano keys
                    // must span a substantial width with gaps larger than digits.
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
