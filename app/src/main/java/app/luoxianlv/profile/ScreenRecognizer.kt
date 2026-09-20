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
 * reads which pitch state is currently active. Detection anchors on the white
 * key digits — they keep strong local contrast against the translucent discs
 * no matter what the 3D scene behind them looks like — so it works on any
 * aspect ratio (tablets included) where stored proportional coordinates break.
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
    )

    private const val TARGET_WIDTH = 1024

    /** Mode buttons left-to-right: 半音, 升调, 自然音, 降调. */
    private val MODE_ORDER = listOf(PlayMode.SEMITONE, PlayMode.RAISE, PlayMode.NATURAL, PlayMode.LOWER)

    // Mode row geometry relative to the note row, in units of note spacing,
    // measured on real captures. Only limits the search region; the final
    // coordinates must come from observed mode text, never from this prior.
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
        var notes: List<Glyph>? = null
        for (threshold in listOf(45f, 30f, 20f, 10f)) {
            val strict = BooleanArray(luma.size) { luma[it] - local[it] > threshold && luma[it] > 120f }
            notes = findNoteRow(glyphs(strict, width, height), width)
            if (notes != null) break
        }
        if (notes == null) {
            for (threshold in listOf(200f, 175f, 150f)) {
                notes = findNoteRow(glyphs(BooleanArray(luma.size) { luma[it] > threshold }, width, height), width)
                if (notes != null) break
            }
        }
        notes = notes ?: return null
        val noteY = notes.map { it.cy }.average().toFloat()
        val noteH = notes.map { it.h }.average().toFloat()
        val noteXs = notes.map { it.cx }
        val spacings = FloatArray(7) { noteXs[it + 1] - noteXs[it] }
        val spacing = spacings.average().toFloat()
        // Sanity: the row must be level and evenly spaced.
        if (spacing <= 0f || spacings.any { abs(it - spacing) > 0.12f * spacing }) return null
        if (notes.any { abs(it.cy - noteY) > 0.45f * noteH }) return null

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
        val row = if (matched.all { it != null } && coherent(matched.filterNotNull(), spacing)) {
            matched.filterNotNull()
        } else locateModeText(luma, width, height, predictedX, predictedY, spacing, noteH, noteY)
            ?: return null
        val modeX = FloatArray(4) { row[it].cx }
        val modeY = row.map { it.cy }.average().toFloat()

        val r = 0.31f * spacing
        val contrast = FloatArray(4) { stateContrast(luma, width, height, modeX[it], modeY, r) }
        val best = (1..3).maxBy { contrast[it] }
        val second = (1..3).filter { it != best }.maxOf { contrast[it] }
        val mode = if (contrast[best] >= 20f && contrast[best] - second >= 15f) MODE_ORDER[best] else null
        val halfTone =
            when {
                contrast[0] >= 20f -> true
                contrast[0] <= 5f -> false
                else -> null
            }

        val modes = MODE_ORDER.mapIndexed { i, m -> m to floatArrayOf(modeX[i] / width, modeY / height) }.toMap()
        return Result(KeyLayout(FloatArray(8) { noteXs[it] / width }, noteY / height, modes), mode, halfTone)
    }

    /** Recover low-contrast labels without adopting coordinates from the prior.
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
        return null
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

    /** Match the eight-key grid, rejecting the adjacent smaller sharp signs.
     * At most one missing digit may be recovered from the observed grid. */
    private fun findNoteRow(glyphs: List<Glyph>, width: Int): List<Glyph>? {
        val digits = glyphs
        var best: List<Glyph>? = null
        var bestScore = Float.MAX_VALUE
        for (seed in digits) {
            val band = digits.filter { abs(it.cy - seed.cy) <= 0.4f * seed.h && it.h >= seed.h * 0.67f && it.h <= seed.h * 1.5f }
            if (band.size < 7) continue
            // Match a grid rather than requiring adjacent components in the
            // component list: sharp signs and scenery may sit between digits.
            for (second in band) {
                val step = second.cx - seed.cx
                if (step < width * .04f || step > width * .14f || step < seed.h * 1.8f) continue
                for (offset in 0..1) {
                val startX = seed.cx - offset * step
                if (startX <= 0 || startX + 7 * step >= width) continue
                val grid = (0..7).map { index ->
                    band.filter { abs(it.cx - (startX + step * index)) <= step * .10f }
                        .minByOrNull { abs(it.cx - (startX + step * index)) }
                }
                val observed = grid.filterNotNull()
                if (observed.size < 7) continue
                // Do not extend a seven-key row in the wrong direction. A
                // missing edge digit requires its adjacent sharp as evidence.
                if (listOf(0, 7).any { index -> grid[index] == null && glyphs.none { sharp ->
                    sharp.h < seed.h * .75f &&
                        startX + step * index - sharp.cx in seed.h * .25f..seed.h * 1.5f &&
                        seed.cy - sharp.cy in 0f..seed.h * .65f
                } }) continue
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
                val accidentals = window.count { candidate ->
                    glyphs.any { digit ->
                        digit.h >= candidate.h * 1.4f &&
                            digit.cx - candidate.cx in candidate.h * 0.5f..candidate.h * 2.5f &&
                            digit.cy - candidate.cy in 0f..candidate.h.toFloat()
                    }
                }
                if (accidentals >= 6) continue
                // A row of HUD text can also be evenly spaced. Piano keys
                // must span a substantial width with gaps larger than digits.
                if (meanSp < meanH * 1.8f || xs.last() - xs.first() < width * 0.30f) continue
                val cv = sqrt(xs.zipWithNext { a, b -> (b - a - meanSp) * (b - a - meanSp) }.average().toFloat()) / meanSp
                if (cv >= 0.12f) continue
                val score = cv + (maxY - minY) / meanH * 0.1f + (8 - observed.size) * .15f
                if (score < bestScore) {
                    bestScore = score
                    best = window
                }
                }
            }
        }
        return best
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

    /** A matched mode row is trusted fully only when its labels line up and
     * keep even spacing; otherwise suspicious labels fall back to the prior. */
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
