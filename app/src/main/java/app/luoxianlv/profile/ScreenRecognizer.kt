package app.luoxianlv.profile
import android.graphics.Bitmap
import app.luoxianlv.core.score.PlayMode
import app.luoxianlv.data.KeyLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Locates the game's on-screen keyboard in a screenshot: the row of 8 note
 * discs plus the 4 pitch-mode buttons (半音/升调/自然音/降调) above them, and
 * reads which pitch state is currently active. White glyphs propose a grid;
 * circular borders refine its centers and scale. Sparse glyph hypotheses need
 * independent circle support in both rows before missing keys are inferred.
 *
 * The analysis core ([analyze]) is pure Kotlin on a grayscale buffer so JVM
 * unit tests can feed decoded images directly; [fromBitmap] is the thin
 * Android adapter.
 */
object ScreenRecognizer {
    data class Result(
        val layout: KeyLayout,
        /** Active pitch mode read from the screen, null when ambiguous. */
        val mode: PlayMode?,
        /** 半音 toggle state read from the screen, null when ambiguous. */
        val halfTone: Boolean?,
        /** Number of coordinates supported directly by visible image glyphs. */
        val observedNotes: Int,
        val observedModes: Int,
        val noteBorders: Int,
        val modeBorders: Int,
    )

    private const val TARGET_WIDTH = 1024

    /** Mode buttons left-to-right: 半音, 升调, 自然音, 降调. */
    private val MODE_ORDER = listOf(PlayMode.SEMITONE, PlayMode.RAISE, PlayMode.NATURAL, PlayMode.LOWER)

    // Mode row geometry relative to the note row, in units of note spacing,
    // measured on real captures. This initializes the search region; labels
    // and circular borders determine the final translation and scale.
    private const val MODE_SEMI_OFFSET = 1.96f
    private val MODE_GAPS = floatArrayOf(0f, 1.20f, 2.15f, 3.08f)
    private const val MODE_Y_OFFSET = -0.91f

    private class Glyph(
        var x0: Int,
        var x1: Int,
        var y0: Int,
        var y1: Int,
        val pixels: Int,
    ) {
        val cx get() = (x0 + x1) / 2f
        val cy get() = (y0 + y1) / 2f
        val w get() = x1 - x0
        val h get() = y1 - y0

        fun merge(other: Glyph) {
            x0 = min(x0, other.x0)
            x1 = max(x1, other.x1)
            y0 = min(y0, other.y0)
            y1 = max(y1, other.y1)
        }
    }

    private class Label(
        val cx: Float,
        val cy: Float,
        val h: Float,
    )

    fun fromBitmap(bitmap: Bitmap): Result? {
        val scaled =
            if (bitmap.width > TARGET_WIDTH) {
                val height = max(1, (bitmap.height * (TARGET_WIDTH.toFloat() / bitmap.width)).toInt())
                Bitmap.createScaledBitmap(bitmap, TARGET_WIDTH, height, true)
            } else {
                bitmap
            }
        val w = scaled.width
        val h = scaled.height
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()
        val luma =
            FloatArray(w * h) { i ->
                val c = px[i]
                0.299f * (c shr 16 and 0xff) + 0.587f * (c shr 8 and 0xff) + 0.114f * (c and 0xff)
            }
        return analyze(luma, w, h)
    }

    fun analyze(
        luma: FloatArray,
        width: Int,
        height: Int,
    ): Result? {
        require(width > 0 && height > 0 && luma.size.toLong() == width.toLong() * height)
        // Top-hat: pixels much brighter than their local surroundings. The key
        // digits pass this even when the scene (sky) is brighter than they are.
        val local = localMean(luma, width, height, 18)
        val borders by lazy { ButtonBorderDetector(luma, width, height) }
        val candidates = mutableListOf<List<Glyph>>()
        for (threshold in listOf(45f, 30f, 20f, 10f)) {
            val strict = BooleanArray(luma.size) { luma[it] - local[it] > threshold && luma[it] > 120f }
            val glyphs = glyphs(strict, width, height)
            candidates.add(glyphs)
            val notes = findNoteRows(glyphs, width).firstOrNull() ?: continue
            resolveLayout(luma, local, width, height, notes, borders)?.let { return it }
        }
        for (threshold in listOf(200f, 175f, 150f)) {
            val glyphs = glyphs(BooleanArray(luma.size) { luma[it] > threshold }, width, height)
            candidates.add(glyphs)
            val notes = findNoteRows(glyphs, width).firstOrNull() ?: continue
            resolveLayout(luma, local, width, height, notes, borders)?.let { return it }
        }
        // Sparse text is allowed when the actual circles validate both rows.
        // Keep several origins: with missing edge digits the first grid can be
        // offset by whole keys. Border support resolves that ambiguity.
        val sparse = candidates.flatMap { findNoteRows(it, width, minObserved = 3) }
            .distinctBy { Triple((it.first().cx/4).toInt(), (it.last().cx/4).toInt(), (it.first().cy/4).toInt()) }
            .sortedByDescending { row -> row.count { it.pixels > 0 } }.take(16)
        var best: Result? = null
        for (notes in sparse) {
            val result = resolveLayout(luma, local, width, height, notes, borders, sparse = true) ?: continue
            if (result.noteBorders == 8 && result.modeBorders == 4) return result
            if (result.noteBorders+result.modeBorders > (best?.let { it.noteBorders+it.modeBorders } ?: 0)) best = result
        }
        return best
    }

    private fun resolveLayout(
        luma: FloatArray, local: FloatArray, width: Int, height: Int,
        notes: List<Glyph>, borders: ButtonBorderDetector, sparse: Boolean = false,
    ): Result? {
        var noteY = notes.map { it.cy }.average().toFloat()
        val noteH = notes.map { it.h }.average().toFloat()
        var noteXs = notes.map { it.cx }.toFloatArray()
        val spacings = FloatArray(7) { noteXs[it + 1] - noteXs[it] }
        var spacing = spacings.average().toFloat()
        // Sanity: the row must be level and evenly spaced.
        if (spacing <= 0f || spacings.any { abs(it - spacing) > 0.12f * spacing }) return null
        if (notes.any { abs(it.cy - noteY) > 0.45f * noteH }) return null

        val noteCircles = noteXs.map { x -> borders.locate(x, noteY, spacing, .14f*spacing, .16f*spacing) }
        val noteFit = fitBorderRow(noteCircles, FloatArray(8) { it.toFloat() }, spacing)
        if (sparse && (noteFit == null || noteFit.count < 6)) return null
        if (noteFit != null) {
            noteXs = noteFit.xs
            noteY = noteFit.y
            spacing = (noteXs.last() - noteXs.first()) / 7f
        }
        if (noteXs.first() <= 0f || noteXs.last() >= width || noteY !in 0f..height.toFloat()) return null

        val predictedY = noteY + MODE_Y_OFFSET * spacing
        val predictedX = FloatArray(4) { noteXs[0] + (MODE_SEMI_OFFSET + MODE_GAPS[it]) * spacing }
        // Only search the mode row after the note row is known. Avoid flooding
        // connected components across unrelated HUD text and scenery.
        val loose = BooleanArray(luma.size)
        val modeTop = max(0, (predictedY - spacing).toInt())
        val modeBottom = min(height - 1, (predictedY + spacing).toInt())
        val modeLeft = max(0, (predictedX.first() - spacing).toInt())
        val modeRight = min(width - 1, (predictedX.last() + spacing).toInt())
        for (y in modeTop..modeBottom) for (x in modeLeft..modeRight) {
            val i = y * width + x
            loose[i] = luma[i] - local[i] > 22f && luma[i] > 85f
        }
        val labels = modeLabels(glyphs(loose, width, height), noteY, noteH, predictedY, spacing)
        // A horizontal match alone can pick scenery above/below the buttons.
        val matched = predictedX.map { px -> labels.filter {
            abs(it.cx - px) <= 0.3f * spacing && abs(it.cy - predictedY) <= 0.20f * spacing
        }.minByOrNull { abs(it.cx - px) + abs(it.cy - predictedY) } }
        val textRow = if (matched.all { it != null } && coherent(matched.filterNotNull(), spacing)) {
            matched.filterNotNull()
        } else locateModeText(luma, width, height, predictedX, predictedY, spacing, noteH, noteY)
        val modeCircles = MODE_ORDER.indices.map { i ->
            val label = textRow?.get(i)
            borders.locate(label?.cx ?: predictedX[i], label?.cy ?: predictedY, spacing,
                spacing * if (label != null && label.h > 0) .14f else .40f,
                spacing * if (label != null && label.h > 0) .14f else .48f,
                minRadius = .23f, maxRadius = .38f)
        }
        val modeFit = fitBorderRow(modeCircles, MODE_GAPS, spacing)
        if (textRow == null && (modeFit == null || modeFit.count < 3)) return null
        if (sparse && (modeFit == null || modeFit.count < 3)) return null
        val modeX = modeFit?.xs ?: FloatArray(4) { textRow!![it].cx }
        val modeY = modeFit?.y ?: textRow!!.map { it.cy }.average().toFloat()
        if (modeX.first() <= 0f || modeX.last() >= width || modeY <= 0f || modeY >= noteY) return null
        val directlySeen = BooleanArray(4) { (textRow?.get(it)?.h ?: 0f) > 0f || modeFit?.observed?.get(it) == true }

        val r = modeFit?.radius ?: (0.31f * spacing)
        val contrast = FloatArray(4) { stateContrast(luma, width, height, modeX[it], modeY, r) }
        val best = (1..3).maxBy { contrast[it] }
        val second = (1..3).filter { it != best }.maxOf { contrast[it] }
        val mode = if (directlySeen[best] && contrast[best] >= 20f && contrast[best] - second >= 15f) MODE_ORDER[best] else null
        val halfTone =
            when {
                !directlySeen[0] -> null
                contrast[0] >= 20f -> true
                contrast[0] <= 5f -> false
                else -> null
            }

        val modes = MODE_ORDER.mapIndexed { i, m -> m to floatArrayOf(modeX[i] / width, modeY / height) }.toMap()
        return Result(
            KeyLayout(FloatArray(8) { noteXs[it] / width }, noteY / height, modes),
            mode, halfTone, notes.count { it.pixels > 0 }, textRow?.count { it.h > 0f } ?: 0,
            noteFit?.count ?: 0, modeFit?.count ?: 0,
        )
    }

    private data class BorderRow(val xs: FloatArray, val y: Float, val radius: Float, val observed: BooleanArray) {
        val count get() = observed.count { it }
    }

    /** Robust affine row fit: the image determines offset AND scale. Reject
     * isolated scene circles by their row height, radius and grid residual. */
    private fun fitBorderRow(circles: List<ButtonBorderDetector.Circle?>, units: FloatArray, spacing: Float): BorderRow? {
        var best: BorderRow? = null
        var bestError = Float.MAX_VALUE
        for (a in circles.indices) for (b in a+1 until circles.size) {
            val first = circles[a] ?: continue
            val second = circles[b] ?: continue
            val scale = (second.x-first.x) / (units[b]-units[a])
            if (scale !in .80f*spacing..1.20f*spacing || abs(first.y-second.y) > .12f*spacing) continue
            val origin = first.x-units[a]*scale
            val cy = (first.y+second.y)/2f
            val radius = (first.radius+second.radius)/2f
            val kept = circles.indices.filter { i -> circles[i]?.let {
                abs(it.x-origin-units[i]*scale) <= .10f*spacing && abs(it.y-cy) <= .10f*spacing &&
                    abs(it.radius-radius) <= .18f*radius
            } == true }
            if (kept.size < 3) continue
            val meanUnit = kept.map { units[it] }.average().toFloat()
            val meanX = kept.map { circles[it]!!.x }.average().toFloat()
            val fittedScale = kept.sumOf { ((units[it]-meanUnit)*(circles[it]!!.x-meanX)).toDouble() }.toFloat() /
                kept.sumOf { ((units[it]-meanUnit)*(units[it]-meanUnit)).toDouble() }.toFloat()
            val xs = FloatArray(units.size) { meanX+(units[it]-meanUnit)*fittedScale }
            val error = kept.sumOf { abs(circles[it]!!.x-xs[it]).toDouble() }.toFloat()
            if (kept.size > (best?.count ?: 0) || kept.size == best?.count && error < bestError) {
                best = BorderRow(xs, kept.map { circles[it]!!.y }.average().toFloat(),
                    kept.map { circles[it]!!.radius }.average().toFloat(), BooleanArray(units.size) { it in kept })
                bestError = error
            }
        }
        return best
    }

    /** Recover low-contrast labels, then complete a partially observed row.
     * Long scenery edges are excluded before connected-component grouping. */
    private fun locateModeText(
        luma: FloatArray, w: Int, h: Int, xs: FloatArray, y: Float,
        spacing: Float, noteH: Float, noteY: Float,
    ): List<Label>? {
        val top = max(0, (y - .45f * spacing).toInt())
        val bottom = min(h - 1, (y + .45f * spacing).toInt())
        val fineMean = localMean(luma, w, h, 3)
        val evidence = mutableListOf<Label>()
        for (threshold in listOf(200f, 175f, 145f, 115f, 12f, 20f, 30f)) {
            val mask = BooleanArray(luma.size)
            for (yy in top..bottom) {
                for (xx in max(0, (xs.first() - .4f * spacing).toInt())..min(w - 1, (xs.last() + .4f * spacing).toInt())) {
                    val i = yy * w + xx
                    mask[i] = if (threshold < 100f) luma[i] > 100f && luma[i] - fineMean[i] > threshold else luma[i] > threshold
                }
                var start = 0
                while (start < w) {
                    if (!mask[yy * w + start]) { start++; continue }
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
                val row = xs.map { x -> evidence.filter {
                    abs(it.cx - x) < .32f * spacing && abs(it.cy - seed.cy) < .4f * noteH
                }.minByOrNull { abs(it.cx - x) } }
                if (row.all { it != null } && coherent(row.filterNotNull(), spacing)) return row.filterNotNull()
            }
        }
        return completeModeRow(evidence, xs, y, spacing, noteH)
    }

    /** Fit translation from at least two distinct, aligned labels. A single
     * bright scene feature is never enough to invent a row of mode buttons.
     * h=0 marks inferred labels so their occluded state remains unknown. */
    private fun completeModeRow(
        evidence: List<Label>, xs: FloatArray, y: Float, spacing: Float, noteH: Float,
        minAnchors: Int = 2,
    ): List<Label>? {
        var best: List<Label>? = null
        var bestCount = 1
        var bestError = Float.MAX_VALUE
        for (seed in evidence) {
            if (abs(seed.cy - y) > .20f * spacing) continue
            val matches = xs.map { x -> evidence.filter {
                abs(it.cx - x) <= .20f * spacing && abs(it.cy - seed.cy) <= .35f * noteH
            }.minByOrNull { abs(it.cx - x) } }
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

    /** Box mean of radius [r] around every pixel via a summed-area table. */
    private fun localMean(
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
            val sum = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
            (sum / ((x1 - x0) * (y1 - y0))).toFloat()
        }
    }

    /** Connected components of [mask], filtered to glyph-sized blobs, with
     * vertically stacked parts (the dotted "i") merged back together. */
    private fun glyphs(
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
            val host =
                merged.firstOrNull { m ->
                    min(g.x1, m.x1) - max(g.x0, m.x0) > 0 && g.y0 - m.y1 in 0..(0.7f * max(g.h, m.h)).toInt()
                }
            if (host == null) merged.add(g) else host.merge(g)
        }
        return merged
    }

    /** Rank distinct eight-key grids, rejecting smaller adjacent sharp signs.
     * Three-glyph candidates are only proposals; both border rows must validate
     * them in resolveLayout before they can be used for playback. */
    private fun findNoteRows(glyphs: List<Glyph>, width: Int, minObserved: Int = 5): List<List<Glyph>> {
        val digits = glyphs
        val candidates = mutableMapOf<Triple<Int, Int, Int>, Pair<Float, List<Glyph>>>()
        for (seed in digits) {
            val band = digits.filter { abs(it.cy - seed.cy) <= 0.4f * seed.h && it.h >= seed.h * 0.67f && it.h <= seed.h * 1.5f }
            if (band.size < minObserved) continue
            // Match a grid rather than requiring adjacent components in the
            // component list: sharp signs and scenery may sit between digits.
            for (second in band) {
                for (gap in 1..(9-minObserved)) {
                    val step = (second.cx - seed.cx) / gap
                    if (step < width * .04f || step > width * .14f || step < seed.h * 1.8f) continue
                    for (offset in 0..(8-minObserved)) {
                        val startX = seed.cx - offset * step
                        if (startX <= 0 || startX + 7 * step >= width) continue
                        val grid = (0..7).map { index ->
                            band.filter { abs(it.cx - (startX + step * index)) <= step * .10f }
                                .minByOrNull { abs(it.cx - (startX + step * index)) }
                        }
                        val observed = grid.filterNotNull()
                        if (observed.size < minObserved) continue
                        // Missing edge digits make the grid's index ambiguous. Validate
                        // the origin against the separate mode row (or nearby sharps).
                        val ambiguousEdge = listOf(0, 7).any { index -> grid[index] == null && glyphs.none { sharp ->
                            sharp.h < seed.h * .75f &&
                                startX + step * index - sharp.cx in seed.h * .25f..seed.h * 1.5f &&
                                seed.cy - sharp.cy in 0f..seed.h * .65f
                        } }
                        if (minObserved >= 5 && (ambiguousEdge || observed.size < 7 && (grid.first() == null || grid.last() == null))) {
                            val expectedY = seed.cy + MODE_Y_OFFSET * step
                            val expectedX = FloatArray(4) { startX + (MODE_SEMI_OFFSET + MODE_GAPS[it]) * step }
                            val labels = modeLabels(glyphs, seed.cy, seed.h.toFloat(), expectedY, step)
                            if (completeModeRow(labels, expectedX, expectedY, step, seed.h.toFloat(), minAnchors = 3) == null) continue
                        }
                        val window = grid.mapIndexed { index, glyph -> glyph ?: run {
                            val cx = (startX + step * index).toInt()
                            val cy = observed.map { it.cy }.average().toInt()
                            Glyph(cx - seed.w / 2, cx + seed.w / 2, cy - seed.h / 2, cy + seed.h / 2, 0)
                        } }
                        val meanH = window.map { it.h }.average().toFloat()
                        if (window.maxOf { it.h } > meanH * 1.5f || window.minOf { it.h } < meanH / 1.5f) continue
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
                        val cv = sqrt(xs.zipWithNext { a, b -> (b - a - meanSp) * (b - a - meanSp) }.average().toFloat()) / meanSp
                        if (cv >= 0.12f) continue
                        val score = cv + (maxY - minY) / meanH * 0.1f + (8 - observed.size) * .15f
                        val key = Triple((xs.first()/4).toInt(), (xs.last()/4).toInt(), (seed.cy/4).toInt())
                        if (score < (candidates[key]?.first ?: Float.MAX_VALUE)) {
                            candidates[key] = score to window
                        }
                    }
                }
            }
        }
        return candidates.values.sortedBy { it.first }.take(12).map { it.second }
    }

    /** Group small glyphs near the predicted mode row into text labels
     * (半音/升调/自然音/降调 are 2–3 characters each). */
    private fun modeLabels(
        glyphs: List<Glyph>,
        noteY: Float,
        noteH: Float,
        predictedY: Float,
        spacing: Float,
    ): List<Label> {
        val candidates =
            glyphs
                .filter {
                    it.cy < noteY - 1.2f * noteH && abs(it.cy - predictedY) < 0.7f * spacing && it.h <= 1.2f * noteH &&
                        it.h >= 6 && it.w >= .4f * it.h && it.w <= 1.8f * it.h
                }.sortedBy { it.cx }
        val groups = mutableListOf<MutableList<Glyph>>()
        for (g in candidates) {
            val host =
                groups.firstOrNull { grp ->
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
            Label((left + right) / 2f, kept.map { it.cy }.average().toFloat(), kept.map { it.h }.average().toFloat())
        }
    }

    /** A fully observed mode row must have aligned, similarly sized labels. */
    private fun coherent(
        labels: List<Label>,
        spacing: Float,
    ): Boolean {
        val meanH = labels.map { it.h }.average().toFloat()
        if (labels.maxOf { it.h } > meanH * 1.6f || labels.minOf { it.h } < meanH / 1.6f) return false
        if (labels.maxOf { it.cy } - labels.minOf { it.cy } > 0.8f * meanH) return false
        val xs = labels.map { it.cx }
        val meanSp = (xs.last() - xs.first()) / 3f
        val cv = sqrt(xs.zipWithNext { a, b -> (b - a - meanSp) * (b - a - meanSp) }.average().toFloat()) / meanSp
        return cv < 0.25f
    }

    /** Active pitch buttons show a light filled disc; inactive ones darken the
     * scene behind them. Comparing the interior median against the surrounding
     * scene cancels out arbitrary backgrounds (bright sky, dark rocks). */
    private fun stateContrast(
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
