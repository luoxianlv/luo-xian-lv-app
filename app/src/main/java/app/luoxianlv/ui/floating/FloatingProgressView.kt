package app.luoxianlv.ui.floating

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.luoxianlv.ui.floating.PlayerUi.dp

/** Progress painting and seek input, independent of overlay window management. */
internal class FloatingProgressView(
    context: Context,
    palette: PlayerUiPalette,
    duration: () -> Long,
    onSeek: (Long) -> Unit,
    onSeekFinished: () -> Unit,
) : FrameLayout(context) {
    private var seeking = false
    private val fill =
        View(context).apply {
            background = PlayerUi.background(context, PlayerUi.BLUE, 2, false)
        }
    private val fillHolder =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            clipChildren = false
            setPadding(context.dp(12), 0, context.dp(12), 0)
            addView(fill, LinearLayout.LayoutParams(0, context.dp(3)))
        }

    init {
        val rail =
            View(context).apply {
                background = PlayerUi.background(context, palette.line, 2, false)
            }
        val track =
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(12), 0, context.dp(12), 0)
                addView(rail, LinearLayout.LayoutParams(-1, context.dp(3)))
            }
        addView(track, LayoutParams(-1, -1))
        addView(fillHolder, LayoutParams(-1, -1))
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    seeking = true
                    true
                }
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP -> {
                    val fraction = (event.x / view.width).coerceIn(0f, 1f)
                    onSeek((fraction * duration()).toLong())
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        seeking = false
                        onSeekFinished()
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun update(positionMs: Long, durationMs: Long) {
        if (seeking) return
        val fraction = (positionMs.toFloat() / durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
        val width = ((fillHolder.width - context.dp(24)).coerceAtLeast(0) * fraction).toInt()
        if (fill.layoutParams.width != width) {
            fill.layoutParams = fill.layoutParams.apply { this.width = width }
        }
    }
}
