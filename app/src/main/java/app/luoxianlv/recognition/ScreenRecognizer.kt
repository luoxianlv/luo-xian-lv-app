package app.luoxianlv.recognition

import android.graphics.Bitmap
import app.luoxianlv.library.PlayMode
import app.luoxianlv.recognition.GlyphDetection.coherent
import app.luoxianlv.recognition.GlyphDetection.glyphs
import app.luoxianlv.recognition.GlyphDetection.modeLabels
import app.luoxianlv.recognition.KeyboardReference.MODE_GAPS
import app.luoxianlv.recognition.KeyboardReference.MODE_SEMI_OFFSET
import app.luoxianlv.recognition.KeyboardReference.MODE_Y_OFFSET
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 从截图定位八个音符键及半音、升调、自然音、降调四个按钮，并读取音区状态。白色字形提出网格，圆框修正中心和缩放；稀疏文字须同时得到两行圆框支持才补键。[analyze] 仅处理灰度数组，便于
 * JVM 测试；[fromBitmap] 负责 Android 图像转换。
 */
object ScreenRecognizer {
    data class Result(
        val layout: KeyLayout,
        /** 从画面读取当前音区；无法确定时为 null。 */
        val mode: PlayMode?,
        /** 从截图读取半音状态；无法确定时为 null。 */
        val halfTone: Boolean?,
        /** 有可见字形直接支持的坐标数量。 */
        val observedNotes: Int,
        val observedModes: Int,
        val noteBorders: Int,
        val modeBorders: Int,
        /** 本帧已重新验证全部圆框，仅省略重复的字形搜索。 */
        val reusedGeometry: Boolean = false,
        internal val analysisWidth: Int = 0,
        internal val analysisHeight: Int = 0,
        internal val noteRadius: Float = 0f,
        internal val modeRadius: Float = 0f,
    )

    internal const val TARGET_WIDTH = 1024
    private const val PIXEL_ROWS = 64

    /** 音区按钮从左至右依次为半音、升调、自然音、降调。 */
    private val MODE_ORDER =
        listOf(PlayMode.SEMITONE, PlayMode.RAISE, PlayMode.NATURAL, PlayMode.LOWER)

    fun fromBitmap(bitmap: Bitmap, previous: Result? = null): Result? {
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
        val rows = min(PIXEL_ROWS, h)
        val px = IntArray(w * rows)
        val luma = FloatArray(w * h)
        // 按条带转换，避免同时持有完整 ARGB 和灰度副本；缩放与像素顺序保持一致。
        try {
            var top = 0
            while (top < h) {
                val count = min(rows, h - top)
                scaled.getPixels(px, 0, w, 0, top, w, count)
                copyLuminance(px, luma, top * w, count * w)
                top += count
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        return analyze(luma, w, h, previous)
    }

    /** 最后一条带可能不足缓冲区大小，只转换实际像素，不读取上次剩余内容。 */
    internal fun copyLuminance(pixels: IntArray, luma: FloatArray, offset: Int, count: Int) {
        for (i in 0 until count) {
            val c = pixels[i]
            luma[offset + i] =
                0.299f * (c shr 16 and 0xff) + 0.587f * (c shr 8 and 0xff) + 0.114f * (c and 0xff)
        }
    }

    fun analyze(
        luma: FloatArray,
        width: Int,
        height: Int,
        previous: Result? = null,
    ): Result? {
        require(width > 0 && height > 0 && luma.size.toLong() == width.toLong() * height)
        // 同帧验证失败后仍可复用梯度与圆环缓存，不重复分配两张全图梯度缓冲区。
        val borders by lazy { ButtonBorderDetector(luma, width, height) }
        validatePrevious(luma, width, height, previous) { borders }
            ?.let {
                return it
            }
        // 顶帽阈值提取明显亮于邻域的像素，即使天空比数字更亮也能保留字形。
        val integral = LuminanceIntegral(luma, width, height)
        val local = integral.mean(18)
        val stack = IntArray(luma.size)
        val mask = BooleanArray(luma.size)
        val candidates = mutableListOf<List<Glyph>>()
        for (threshold in listOf(45f, 30f, 20f, 10f)) {
            for (i in luma.indices) mask[i] = luma[i] - local[i] > threshold && luma[i] > 120f
            val glyphs = glyphs(mask, width, height, stack = stack)
            candidates.add(glyphs)
            val notes = findNoteRows(glyphs, width).firstOrNull() ?: continue
            resolveLayout(luma, local, integral, stack, mask, width, height, notes, borders)?.let {
                return it
            }
        }
        for (threshold in listOf(200f, 175f, 150f)) {
            for (i in luma.indices) mask[i] = luma[i] > threshold
            val glyphs = glyphs(mask, width, height, stack = stack)
            candidates.add(glyphs)
            val notes = findNoteRows(glyphs, width).firstOrNull() ?: continue
            resolveLayout(luma, local, integral, stack, mask, width, height, notes, borders)?.let {
                return it
            }
        }
        // 允许稀疏文字，但须由两行圆框验证；保留多个起点，用圆框支持消除边缘缺字导致的整键偏移。
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
                resolveLayout(
                    luma,
                    local,
                    integral,
                    stack,
                    mask,
                    width,
                    height,
                    notes,
                    borders,
                    sparse = true,
                ) ?: continue
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
        integral: LuminanceIntegral,
        stack: IntArray,
        mask: BooleanArray,
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
        // 校验音符行水平且等距。
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
        // 确定音符行后再搜索音区行，避免将无关 HUD 和场景大量纳入连通域。
        // 全图连通域已清空 mask；此区域在每次候选中重新填充并消费，避免全图重复分配。
        val modeTop = max(0, (predictedY - spacing).toInt())
        val modeBottom = min(height - 1, (predictedY + spacing).toInt())
        val modeLeft = max(0, (predictedX.first() - spacing).toInt())
        val modeRight = min(width - 1, (predictedX.last() + spacing).toInt())
        for (y in modeTop..modeBottom) for (x in modeLeft..modeRight) {
            val i = y * width + x
            mask[i] = luma[i] - local[i] > 22f && luma[i] > 85f
        }
        val labels =
            modeLabels(
                glyphs(
                    mask,
                    width,
                    height,
                    modeLeft,
                    modeRight + 1,
                    modeTop,
                    modeBottom + 1,
                    stack,
                ),
                noteY,
                noteH,
                predictedY,
                spacing,
            )
        // 仅横向匹配可能误选按钮上下的场景特征。
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
                locateModeText(
                    luma,
                    width,
                    height,
                    predictedX,
                    predictedY,
                    spacing,
                    noteH,
                    noteY,
                    integral,
                    stack,
                )
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

        return resultFromRows(
            luma,
            width,
            height,
            noteXs,
            noteY,
            noteFit?.radius ?: 0f,
            modeX,
            modeY,
            modeFit?.radius ?: (0.31f * spacing),
            directlySeen,
            notes.count { it.pixels > 0 },
            textRow?.count { it.h > 0f } ?: 0,
            noteFit?.count ?: 0,
            modeFit?.count ?: 0,
        )
    }

    /** 旧结果仅限定搜索范围；位置、比例和十二个圆框须得到当前画面重新确认。 */
    private fun validatePrevious(
        luma: FloatArray,
        width: Int,
        height: Int,
        previous: Result?,
        borderDetector: () -> ButtonBorderDetector,
    ): Result? {
        if (
            previous == null ||
                previous.analysisWidth != width ||
                previous.analysisHeight != height ||
                previous.noteBorders != 8 ||
                previous.modeBorders != 4 ||
                previous.noteRadius <= 0f ||
                previous.modeRadius <= 0f
        )
            return null
        val xs = previous.layout.noteX
        if (
            xs.size != 8 ||
                xs.any { !it.isFinite() || it !in 0f..1f } ||
                !previous.layout.noteY.isFinite()
        )
            return null
        val noteXs = FloatArray(8) { xs[it] * width }
        val noteY = previous.layout.noteY * height
        val spacing = (noteXs.last() - noteXs.first()) / 7f
        if (spacing <= 0f) return null
        val modeXs = FloatArray(4)
        var modeY = 0f
        for (i in MODE_ORDER.indices) {
            val point = previous.layout.modes[MODE_ORDER[i]] ?: return null
            if (point.size != 2 || point.any { !it.isFinite() || it !in 0f..1f }) return null
            modeXs[i] = point[0] * width
            if (i == 0) modeY = point[1] * height
            else if (abs(point[1] * height - modeY) > .01f) return null
        }
        if (modeY <= 0f || modeY >= noteY) return null
        val borders = borderDetector()
        fun verifyRow(points: FloatArray, y: Float, radius: Float, units: FloatArray): BorderRow? {
            val circles = ArrayList<ButtonBorderDetector.Circle>(points.size)
            for (x in points) {
                val circle =
                    borders.locate(
                        x,
                        y,
                        spacing,
                        1f,
                        1f,
                        minRadius = max(.1f, (radius - 2f) / spacing),
                        maxRadius = (radius + 2f) / spacing,
                    ) ?: return null
                // 任一圆框移动超过两个像素即可回到完整搜索，不必继续验证剩余旧位置。
                if (
                    abs(circle.x - x) > 2f ||
                        abs(circle.y - y) > 2f ||
                        abs(circle.radius - radius) > 2f
                )
                    return null
                circles.add(circle)
            }
            return fitBorderRow(circles, units, spacing)?.takeIf { it.count == points.size }
        }
        val notes =
            verifyRow(noteXs, noteY, previous.noteRadius, FloatArray(8) { it.toFloat() })
                ?: return null
        val modes = verifyRow(modeXs, modeY, previous.modeRadius, MODE_GAPS) ?: return null
        if (abs((notes.xs.last() - notes.xs.first()) / 7f - spacing) > 1f) return null
        return resultFromRows(
            luma,
            width,
            height,
            notes.xs,
            notes.y,
            notes.radius,
            modes.xs,
            modes.y,
            modes.radius,
            BooleanArray(4) { true },
            0,
            0,
            8,
            4,
            reusedGeometry = true,
        )
    }

    private fun resultFromRows(
        luma: FloatArray,
        width: Int,
        height: Int,
        noteXs: FloatArray,
        noteY: Float,
        noteRadius: Float,
        modeX: FloatArray,
        modeY: Float,
        modeRadius: Float,
        directlySeen: BooleanArray,
        observedNotes: Int,
        observedModes: Int,
        noteBorders: Int,
        modeBorders: Int,
        reusedGeometry: Boolean = false,
    ): Result {
        val contrast =
            FloatArray(4) {
                ButtonStateReader.contrast(luma, width, height, modeX[it], modeY, modeRadius)
            }
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
            observedNotes,
            observedModes,
            noteBorders,
            modeBorders,
            reusedGeometry,
            width,
            height,
            noteRadius,
            modeRadius,
        )
    }
}
