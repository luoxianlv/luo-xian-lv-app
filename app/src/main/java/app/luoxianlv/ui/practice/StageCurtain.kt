package app.luoxianlv.ui.practice

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View

/** 首页和演练场共用的光幕；所属窗口结束时取消阶段动画。 */
class StageCurtain(context: Context, dark: Boolean) : View(context) {
    var backgroundReady = false
        set(value) {
            field = value
            invalidate()
        }

    var origin: RectF? = null
    var expansion = 1f
        set(value) {
            field = value
            openingProgress = .4f * value
            invalidate()
        }

    var openingProgress = .4f
        private set

    private val labelPaint =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) 0xffd7e2f0.toInt() else 0xff29445a.toInt()
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 13 * resources.displayMetrics.density
        }
    private val labelMetrics =
        android.graphics.Paint.FontMetrics().also { labelPaint.getFontMetrics(it) }
    private val renderer = StageLightRenderer(dark)
    private val exitRenderer = StageExitRenderer(dark)
    private var exitProgress: Float? = null
    private val rect = RectF()
    private val clip = Path()
    private val corners = FloatArray(8)
    private var animator: ValueAnimator? = null
    private var ambient = true

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        renderer.resize(w, h)
        exitRenderer.resize(w, h)
    }

    fun setAmbientActive(active: Boolean) {
        ambient = active
        if (active) invalidate()
    }

    fun reveal(onProgress: (Float) -> Unit, onKeys: () -> Unit, onFinished: () -> Unit) {
        var keysStarted = false
        transition(
            1f,
            2800,
            { p ->
                onProgress(p)
                if (p >= .88f && !keysStarted) {
                    keysStarted = true
                    onKeys()
                }
            },
        ) {
            visibility = GONE
            onFinished()
        }
    }

    fun prepareHomeReturn() {
        animator?.cancel()
        exitProgress = .54f
        visibility = VISIBLE
        invalidate()
    }

    fun gatherExit(onProgress: (Float) -> Unit, onFinished: () -> Unit) {
        animateExit(0f, .54f, 620, onProgress, onFinished)
    }

    fun returnToEntry(onFinished: () -> Unit) {
        animateExit(.54f, 1f, 720, {}, onFinished)
    }

    private fun animateExit(
        from: Float,
        to: Float,
        durationMs: Long,
        update: (Float) -> Unit,
        end: () -> Unit,
    ) {
        animator?.cancel()
        visibility = VISIBLE
        ambient = false
        animator =
            ValueAnimator.ofFloat(from, to).apply {
                duration = if (ValueAnimator.areAnimatorsEnabled()) durationMs else 0
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener {
                    exitProgress = it.animatedValue as Float
                    update(exitProgress!!)
                    invalidate()
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        private var cancelled = false

                        override fun onAnimationCancel(animation: Animator) {
                            cancelled = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            if (!cancelled) end()
                        }
                    }
                )
                start()
            }
    }

    private fun transition(
        target: Float,
        durationMs: Long,
        update: (Float) -> Unit,
        end: () -> Unit,
    ) {
        animator?.cancel()
        ambient = true
        if (!ValueAnimator.areAnimatorsEnabled()) {
            openingProgress = target
            update(target)
            invalidate()
            end()
            return
        }
        animator =
            ValueAnimator.ofFloat(openingProgress, target).apply {
                duration = durationMs
                interpolator =
                    android.animation.TimeInterpolator { t ->
                        if (t < .5f) 4 * t * t * t
                        else {
                            val x = -2 * t + 2
                            1 - x * x * x / 2
                        }
                    }
                addUpdateListener {
                    openingProgress = it.animatedValue as Float
                    update(openingProgress)
                    invalidate()
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        private var cancelled = false

                        override fun onAnimationCancel(animation: Animator) {
                            cancelled = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            if (!cancelled) end()
                        }
                    }
                )
                start()
            }
    }

    fun close() {
        ambient = false
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        exitProgress?.let {
            exitRenderer.draw(canvas, width.toFloat(), height.toFloat(), it, origin)
            return
        }
        val from = origin
        fun mix(a: Float, b: Float) = a + (b - a) * expansion
        rect.set(
            mix(from?.left ?: 0f, 0f),
            mix(from?.top ?: 0f, 0f),
            mix(from?.right ?: width.toFloat(), width.toFloat()),
            mix(from?.bottom ?: height.toFloat(), height.toFloat()),
        )
        val radius = 24 * resources.displayMetrics.density * (1 - expansion)
        val saved = canvas.save()
        if (expansion < 1f) {
            clip.rewind()
            // 首页入口贴齐卡片右边缘。
            corners[0] = radius
            corners[1] = radius
            corners[6] = radius
            corners[7] = radius
            clip.addRoundRect(rect, corners, Path.Direction.CW)
            canvas.clipPath(clip)
        }
        renderer.draw(
            canvas,
            width.toFloat(),
            height.toFloat(),
            openingProgress,
            (SystemClock.uptimeMillis() % 120000) / 1000f,
            backgroundReady,
        )
        if (from != null && openingProgress < .13f) {
            labelPaint.alpha =
                (184 * (1 - StageLightRenderer.smooth(0f, .12f, openingProgress))).toInt()
            canvas.drawText(
                "演练场",
                from.centerX(),
                from.centerY() - (labelMetrics.ascent + labelMetrics.descent) / 2,
                labelPaint,
            )
        }
        canvas.restoreToCount(saved)
        if (
            ambient &&
                openingProgress >= .06f &&
                openingProgress <= .63f &&
                isShown &&
                windowVisibility == VISIBLE &&
                ValueAnimator.areAnimatorsEnabled()
        )
            postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }
}
