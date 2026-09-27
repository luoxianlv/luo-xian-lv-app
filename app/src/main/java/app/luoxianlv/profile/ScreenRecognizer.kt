package app.luoxianlv.profile

import android.graphics.Bitmap
import app.luoxianlv.core.score.PlayMode
import app.luoxianlv.data.KeyLayout
import app.luoxianlv.profile.GlyphDetection.coherent
import app.luoxianlv.profile.GlyphDetection.glyphs
import app.luoxianlv.profile.GlyphDetection.localMean
import app.luoxianlv.profile.GlyphDetection.modeLabels
import app.luoxianlv.profile.KeyboardReference.MODE_GAPS
import app.luoxianlv.profile.KeyboardReference.MODE_SEMI_OFFSET
import app.luoxianlv.profile.KeyboardReference.MODE_Y_OFFSET
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Locates the game's on-screen keyboard in a screenshot: the row of 8 note discs plus the 4
 * pitch-mode buttons (半音/升调/自然音/降调) above them, and reads which pitch state is currently active.
 * White glyphs propose a grid; circular borders refine its centers and scale. Sparse glyph
 * hypotheses need independent circle support in both rows before missing keys are inferred.
 *
 * The analysis core ([analyze]) is pure Kotlin on a grayscale buffer so JVM unit tests can feed
 * decoded images directly; [fromBitmap] is the thin Android adapter.
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
    private val MODE_ORDER =
        listOf(PlayMode.SEMITONE, PlayMode.RAISE, PlayMode.NATURAL, PlayMode.LOWER)

    fun fromBitmap(bitmap: Bitmap): Result? {
        val scaled =
            if (bitmap.width > TARGET_WIDTH) {
                val height =
                    max(1, (bitmap.height * (TARGET_WIDTH.toFloat() / bitmap.width)).toInt())
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
            val strict =
                BooleanArray(luma.size) { luma[it] - local[it] > threshold && luma[it] > 120f }
            val glyphs = glyphs(strict, width, height)
            candidates.add(glyphs)
            val notes = findNoteRows(glyphs, width).firstOrNull() ?: continue
            resolveLayout(luma, local, width, height, notes, borders)?.let {
                return it
            }
        }
        for (threshold in listOf(200f, 175f, 150f)) {
            val glyphs = glyphs(BooleanArray(luma.size) { luma[it] > threshold }, width, height)
            candidates.add(glyphs)
            val notes = findNoteRows(glyphs, width).firstOrNull() ?: continue
            resolveLayout(luma, local, width, height, notes, borders)?.let {
                return it
            }
        }
        // Sparse text is allowed when the actual circles validate both rows.
        // Keep several origins: with missing edge digits the first grid can be
        // offset by whole keys. Border support resolves that ambiguity.
        val sparse =
            candidates
                .flatMap { findNoteRows(it, width, minObserved = 3) }
                .distinctBy {
                    Triple(
                        (it.first().cx / 4).toInt(),
                        (it.last().cx / 4).toInt(),
                        (it.first().cy / 4).toInt(),
                    )
                }
                .sortedByDescending { row -> row.count { it.pixels > 0 } }
                .take(16)
        var best: Result? = null
        for (notes in sparse) {
            val result =
                resolveLayout(luma, local, width, height, notes, borders, sparse = true) ?: continue
            if (result.noteBorders == 8 && result.modeBorders == 4) return result
            if (
                result.noteBorders + result.modeBorders >
                    (best?.let { it.noteBorders + it.modeBorders } ?: 0)
            )
                best = result
        }
        return best
    }

    private fun resolveLayout(
        luma: FloatArray,
        local: FloatArray,
        width: Int,
        height: Int,
        notes: List<Glyph>,
        borders: ButtonBorderDetector,
        sparse: Boolean = false,
    ): Result? {
        var noteY = notes.map { it.cy }.average().toFloat()
        val noteH = notes.map { it.h }.average().toFloat()
        var noteXs = notes.map { it.cx }.toFloatArray()
        val spacings = FloatArray(7) { noteXs[it + 1] - noteXs[it] }
        var spacing = spacings.average().toFloat()
        // Sanity: the row must be level and evenly spaced.
        if (spacing <= 0f || spacings.any { abs(it - spacing) > 0.12f * spacing }) return null
        if (notes.any { abs(it.cy - noteY) > 0.45f * noteH }) return null

        val noteCircles = noteXs.map { x ->
            borders.locate(x, noteY, spacing, .14f * spacing, .16f * spacing)
        }
        val noteFit = fitBorderRow(noteCircles, FloatArray(8) { it.toFloat() }, spacing)
        if (sparse && (noteFit == null || noteFit.count < 6)) return null
        if (noteFit != null) {
            noteXs = noteFit.xs
            noteY = noteFit.y
            spacing = (noteXs.last() - noteXs.first()) / 7f
        }
        if (noteXs.first() <= 0f || noteXs.last() >= width || noteY !in 0f..height.toFloat())
            return null

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
        val matched = predictedX.map { px ->
            labels
                .filter {
                    abs(it.cx - px) <= 0.3f * spacing && abs(it.cy - predictedY) <= 0.20f * spacing
                }
                .minByOrNull { abs(it.cx - px) + abs(it.cy - predictedY) }
        }
        val textRow =
            if (matched.all { it != null } && coherent(matched.filterNotNull(), spacing)) {
                matched.filterNotNull()
            } else
                locateModeText(luma, width, height, predictedX, predictedY, spacing, noteH, noteY)
        val modeCircles =
            MODE_ORDER.indices.map { i ->
                val label = textRow?.get(i)
                borders.locate(
                    label?.cx ?: predictedX[i],
                    label?.cy ?: predictedY,
                    spacing,
                    spacing * if (label != null && label.h > 0) .14f else .40f,
                    spacing * if (label != null && label.h > 0) .14f else .48f,
                    minRadius = .23f,
                    maxRadius = .38f,
                )
            }
        val modeFit = fitBorderRow(modeCircles, MODE_GAPS, spacing)
        if (textRow == null && (modeFit == null || modeFit.count < 3)) return null
        if (sparse && (modeFit == null || modeFit.count < 3)) return null
        val modeX = modeFit?.xs ?: FloatArray(4) { textRow!![it].cx }
        val modeY = modeFit?.y ?: textRow!!.map { it.cy }.average().toFloat()
        if (modeX.first() <= 0f || modeX.last() >= width || modeY <= 0f || modeY >= noteY)
            return null
        val directlySeen =
            BooleanArray(4) {
                (textRow?.get(it)?.h ?: 0f) > 0f || modeFit?.observed?.get(it) == true
            }

        val r = modeFit?.radius ?: (0.31f * spacing)
        val contrast =
            FloatArray(4) { ButtonStateReader.contrast(luma, width, height, modeX[it], modeY, r) }
        val best = (1..3).maxBy { contrast[it] }
        val second = (1..3).filter { it != best }.maxOf { contrast[it] }
        val mode =
            if (directlySeen[best] && contrast[best] >= 20f && contrast[best] - second >= 15f)
                MODE_ORDER[best]
            else null
        val halfTone =
            when {
                !directlySeen[0] -> null
                contrast[0] >= 20f -> true
                contrast[0] <= 5f -> false
                else -> null
            }

        val modes =
            MODE_ORDER.mapIndexed { i, m -> m to floatArrayOf(modeX[i] / width, modeY / height) }
                .toMap()
        return Result(
            KeyLayout(FloatArray(8) { noteXs[it] / width }, noteY / height, modes),
            mode,
            halfTone,
            notes.count { it.pixels > 0 },
            textRow?.count { it.h > 0f } ?: 0,
            noteFit?.count ?: 0,
            modeFit?.count ?: 0,
        )
    }
}
