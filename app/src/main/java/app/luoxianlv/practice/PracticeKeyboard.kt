package app.luoxianlv.practice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import app.luoxianlv.diagnostics.AppLog

/** 真实触摸与无障碍注入的 MotionEvent 共用绘制及命中几何。 */
class PracticeKeyboard(context: Context) : View(context) {
    val session = PracticeSession()
    var onNoteOn: (Int) -> Boolean = { false }
    var onNoteOff: () -> Unit = {}
    var onExit: () -> Unit = {}
    var onWallpaper: () -> Unit = {}
    var onWallpaperSound: () -> Unit = {}
    var wallpaperSoundEnabled = false
        set(value) {
            field = value
            invalidate()
        }

    var onReady: () -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var elapsed = 0f
    private var ready = false
    private var exitPointer: Int? = null
    private var wallpaperPointer: Int? = null
    private var soundPointer: Int? = null
    private val speakerPath = android.graphics.Path()
    var safeLeft = 0
        set(value) {
            field = value
            invalidate()
        }

    var safeTop = 0
        set(value) {
            field = value
            invalidate()
        }

    var safeRight = 0
        set(value) {
            field = value
            invalidate()
        }

    private val density
        get() = resources.displayMetrics.density

    private val exitBounds
        get() =
            RectF(
                safeLeft + 12 * density,
                safeTop + 12 * density,
                safeLeft + 66 * density,
                safeTop + 66 * density,
            )

    private val wallpaperBounds
        get() =
            RectF(
                safeLeft + 72 * density,
                safeTop + 12 * density,
                safeLeft + 126 * density,
                safeTop + 66 * density,
            )

    private val soundBounds
        get() =
            RectF(
                safeLeft + 132 * density,
                safeTop + 12 * density,
                safeLeft + 186 * density,
                safeTop + 66 * density,
            )

    init {
        isFocusable = true
        contentDescription = "口琴演奏区，八个音符、半音、升调、自然音、降调"
    }

    fun open(durationMs: Long = 1070) {
        animator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            elapsed = 1070f
            ready = true
            invalidate()
            onReady()
            return
        }
        animator =
            ValueAnimator.ofFloat(0f, 1070f).apply {
                duration = durationMs
                interpolator = LinearInterpolator()
                addUpdateListener {
                    elapsed = it.animatedValue as Float
                    if (elapsed >= 1070 && !ready) {
                        ready = true
                        onReady()
                    }
                    invalidate()
                }
                start()
            }
    }

    fun silence() {
        session.cancel()
        onNoteOff()
        invalidate()
    }

    fun close() {
        ready = false
        animator?.cancel()
        animator = null
        silence()
    }

    private fun progress(start: Float, duration: Float) =
        ((elapsed - start) / duration).coerceIn(0f, 1f)

    private fun circle(
        canvas: Canvas,
        x: Float,
        y: Float,
        radius: Float,
        color: Int,
        alpha: Float = 1f,
        stroke: Float = 0f,
    ) {
        paint.color = color
        paint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        paint.style = if (stroke > 0) Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = stroke
        canvas.drawCircle(x, y, radius, paint)
    }

    private fun text(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        alpha: Float = 1f,
        bold: Boolean = false,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(238, 238, 229)
        paint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        paint.typeface =
            if (bold) Typeface.create("sans-serif", Typeface.BOLD)
            else Typeface.create("sans-serif", Typeface.NORMAL)
        val bounds = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        // 按可见字形居中，不按字体行框居中；汉字与箭头的边界不同。
        canvas.drawText(
            text,
            x - bounds.exactCenterX(),
            y - bounds.exactCenterY(),
            paint.apply { textAlign = Paint.Align.LEFT },
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val darkness = progress(0f, 220f)
        canvas.drawColor(Color.argb((255 - 125 * darkness).toInt(), 6, 8, 7))
        val fit = PracticeGeometry.fit(width.toFloat(), height.toFloat())
        // 即使壁纸很亮，也在游戏控件后保留稳定的暗底。
        paint.style = Paint.Style.FILL
        paint.alpha = (255 * darkness).toInt()
        paint.shader =
            android.graphics.LinearGradient(
                0f,
                fit.top - 90 * fit.scale,
                0f,
                fit.top + 602 * fit.scale,
                intArrayOf(
                    Color.TRANSPARENT,
                    0xB4000000.toInt(),
                    0xB4000000.toInt(),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, .2f, .85f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        canvas.save()
        canvas.translate(fit.left, fit.top)
        canvas.scale(fit.scale, fit.scale)
        val chrome = progress(140f, 620f)
        paint.color = Color.rgb(72, 76, 68)
        paint.alpha = (160 * chrome).toInt()
        paint.strokeWidth = 1.5f
        canvas.drawLine(5f, 7f, 957f, 7f, paint)
        canvas.drawLine(1013f, 7f, 1970f, 7f, paint)
        canvas.drawLine(5f, 477f, 1970f, 477f, paint)
        canvas.drawLine(740f, 84f, 740f, 173f, paint)
        circle(canvas, 974f, 8f, 9f, Color.GRAY, chrome * .65f, 1.5f)
        circle(canvas, 996f, 8f, 9f, Color.GRAY, chrome * .65f, 1.5f)
        (PracticeGeometry.notes + PracticeGeometry.modes).forEachIndexed { index, key ->
            val note = index < 8
            val modeIndex = index - 8
            val delay = if (note) 220f + index * 27 else 130f + modeIndex * 37
            val ring = progress(delay, 440f)
            val label = progress(delay + 270f, 240f)
            val selected =
                if (note) session.active?.key == index
                else
                    when (modeIndex) {
                        0 -> session.half
                        1 -> session.mode == PracticeSession.Mode.RAISE
                        2 -> session.mode == PracticeSession.Mode.NATURAL
                        else -> session.mode == PracticeSession.Mode.LOWER
                    }
            // 入口椭圆逐渐展开，最终对齐实际可演奏的圆形按键。
            canvas.save()
            if (note && !ready) {
                val settle = progress(0f, 720f)
                val eased = 1f - (1f - settle) * (1f - settle) * (1f - settle)
                val startX = 985f + (key.x - 985f) * .58f
                val startY = 245f
                canvas.translate((startX - key.x) * (1f - eased), (startY - key.y) * (1f - eased))
                canvas.scale(.68f + .32f * eased, .42f + .58f * eased, key.x, key.y)
            }
            circle(canvas, key.x, key.y, key.radius, Color.rgb(6, 8, 7), ring * .66f)
            if (selected) {
                val alpha = if (ready) 1f else progress(770f, 300f)
                circle(canvas, key.x, key.y, key.radius, Color.rgb(85, 88, 82), alpha * .8f)
                circle(canvas, key.x, key.y, key.radius, Color.rgb(229, 232, 223), alpha, 3f)
            } else circle(canvas, key.x, key.y, key.radius, Color.rgb(56, 59, 54), ring, 2.5f)
            val trace = progress(delay, 500f)
            if (trace > 0 && trace < 1) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.6f
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = Color.rgb(217, 230, 237)
                paint.alpha = ((1 - trace) * 230).toInt()
                canvas.drawArc(
                    key.x - key.radius,
                    key.y - key.radius,
                    key.x + key.radius,
                    key.y + key.radius,
                    -90f,
                    trace * 360,
                    false,
                    paint,
                )
            }
            if (note) {
                text(
                    canvas,
                    if (index == 7) "1" else (index + 1).toString(),
                    key.x,
                    key.y,
                    80f,
                    label,
                    true,
                )
                // 游戏截图中的升号约为数字高度一半，位于左上方。
                if (session.half) text(canvas, "#", key.x - 39f, key.y - 22f, 40f, label, true)
                val register = session.mode.semitones / 12 + if (index == 7) 1 else 0
                repeat(kotlin.math.abs(register)) { dot ->
                    circle(
                        canvas,
                        key.x,
                        key.y + if (register > 0) -53f - dot * 12 else 58f + dot * 12,
                        5.2f,
                        Color.rgb(238, 238, 229),
                        label,
                    )
                }
            } else
                text(canvas, listOf("半音", "升调", "自然音", "降调")[modeIndex], key.x, key.y, 31f, label)
            canvas.restore()
        }
        canvas.restore()
        val exit = exitBounds
        circle(canvas, exit.centerX(), exit.centerY(), 18 * density, Color.DKGRAY, .6f, density)
        paint.color = Color.LTGRAY
        paint.alpha = 220
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * density
        paint.strokeCap = Paint.Cap.ROUND
        val cx = exit.centerX()
        val cy = exit.centerY()
        canvas.drawLine(cx + 3 * density, cy - 7 * density, cx - 3 * density, cy, paint)
        canvas.drawLine(cx - 3 * density, cy, cx + 3 * density, cy + 7 * density, paint)
        val wallpaper = wallpaperBounds
        circle(
            canvas,
            wallpaper.centerX(),
            wallpaper.centerY(),
            18 * density,
            Color.DKGRAY,
            .6f,
            density,
        )
        paint.color = Color.LTGRAY
        paint.alpha = 220
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        val wx = wallpaper.centerX()
        val wy = wallpaper.centerY()
        canvas.drawRoundRect(
            wx - 9 * density,
            wy - 7 * density,
            wx + 9 * density,
            wy + 7 * density,
            2 * density,
            2 * density,
            paint,
        )
        canvas.drawLine(wx - 7 * density, wy + 5 * density, wx - 2 * density, wy, paint)
        canvas.drawLine(wx - 2 * density, wy, wx + 2 * density, wy + 4 * density, paint)
        canvas.drawLine(wx + 2 * density, wy + 4 * density, wx + 5 * density, wy + density, paint)
        val sound = soundBounds
        circle(canvas, sound.centerX(), sound.centerY(), 18 * density, Color.DKGRAY, .6f, density)
        canvas.save()
        canvas.translate(sound.centerX(), sound.centerY())
        canvas.scale(density, density)
        paint.color = Color.LTGRAY
        paint.alpha = if (wallpaperSoundEnabled) 255 else 180
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        speakerPath.reset()
        speakerPath.moveTo(-9f, -3f)
        speakerPath.lineTo(-5f, -3f)
        speakerPath.lineTo(0f, -7f)
        speakerPath.lineTo(0f, 7f)
        speakerPath.lineTo(-5f, 3f)
        speakerPath.lineTo(-9f, 3f)
        speakerPath.close()
        canvas.drawPath(speakerPath, paint)
        if (wallpaperSoundEnabled) {
            canvas.drawArc(-2f, -6f, 9f, 6f, -60f, 120f, false, paint)
            canvas.drawArc(-4f, -9f, 14f, 9f, -55f, 110f, false, paint)
        } else {
            canvas.drawLine(4f, -3f, 10f, 3f, paint)
            canvas.drawLine(4f, 3f, 10f, -3f, paint)
        }
        canvas.restore()
        if (width > 520 * density)
            text(
                canvas,
                if (ready) "落弦律 · 演练场" else "舞台开场中",
                width / 2f,
                28 * density,
                11 * density,
                .5f,
            )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val id = event.getPointerId(index)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(index)
                val y = event.getY(index)
                if (exitBounds.contains(x, y)) {
                    exitPointer = id
                    return true
                }
                if (wallpaperBounds.contains(x, y)) {
                    wallpaperPointer = id
                    return true
                }
                if (soundBounds.contains(x, y)) {
                    soundPointer = id
                    return true
                }
                if (!ready) return true
                val hit =
                    PracticeGeometry.fit(width.toFloat(), height.toFloat()).hit(x, y) ?: return true
                if (hit < 8) {
                    val note = session.press(id, hit)
                    if (!onNoteOn(note.midi)) {
                        silence()
                    }
                    AppLog.log("演练场按下：按键=$hit midi=${note.midi} 触点=$id")
                } else {
                    // DOWN 立即切换，不等待动画、防抖或 UP。
                    when (hit) {
                        8 -> session.toggleHalf()
                        9 -> session.select(PracticeSession.Mode.RAISE)
                        10 -> session.select(PracticeSession.Mode.NATURAL)
                        11 -> session.select(PracticeSession.Mode.LOWER)
                    }
                    AppLog.log("演练场切换：音区=${session.mode} 半音=${session.half}")
                }
                invalidate()
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                if (session.release(id)) {
                    onNoteOff()
                    invalidate()
                    AppLog.log("演练场松开：触点=$id")
                }
                if (wallpaperPointer == id) {
                    wallpaperPointer = null
                    if (wallpaperBounds.contains(event.getX(index), event.getY(index))) {
                        performClick()
                        onWallpaper()
                    }
                }
                if (soundPointer == id) {
                    soundPointer = null
                    if (soundBounds.contains(event.getX(index), event.getY(index))) {
                        performClick()
                        onWallpaperSound()
                    }
                }
                if (exitPointer == id) {
                    exitPointer = null
                    if (exitBounds.contains(event.getX(index), event.getY(index))) {
                        performClick()
                        onExit()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                exitPointer = null
                wallpaperPointer = null
                soundPointer = null
                silence()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && (w <= h || h < 200 * density)) {
            close()
            PracticePlaybackGate.invalidateSession(session)
            post { onExit() }
        }
    }

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }
}
