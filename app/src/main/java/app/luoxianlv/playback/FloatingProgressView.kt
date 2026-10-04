package app.luoxianlv.playback

import android.content.Context
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.widget.SeekBar
import app.luoxianlv.playback.PlayerUi.dp
import kotlin.math.roundToInt

/** 拖动只预览，松手提交定位；沿用系统滑块的触摸、键盘和无障碍操作。 */
internal class FloatingProgressView(
    context: Context,
    palette: PlayerUiPalette,
    private val duration: () -> Long,
    private val onSeek: (Long) -> Unit,
    private val onSeekFinished: () -> Unit,
    private val onPreview: (Long) -> Unit = {},
) : SeekBar(context) {
    private var tracking = false
    private var cancelled = false
    private var latestPositionMs = 0L
    private var latestDurationMs = duration().coerceAtLeast(0)

    init {
        max = 1000
        keyProgressIncrement = 10
        minimumHeight = context.dp(TOUCH_HEIGHT_DP)
        setPadding(context.dp(12), 0, context.dp(12), 0)
        contentDescription = "歌曲播放进度"
        isFocusable = true
        isEnabled = duration() > 0
        background = null
        progressTintList = null
        progressBackgroundTintList = null
        thumbTintList = null
        splitTrack = false
        progressDrawable =
            LayerDrawable(
                    arrayOf(
                        PlayerUi.background(context, palette.line, 2),
                        ClipDrawable(
                            PlayerUi.background(context, PlayerUi.BLUE, 2),
                            Gravity.LEFT,
                            ClipDrawable.HORIZONTAL,
                        ),
                    )
                )
                .apply {
                    setId(0, android.R.id.background)
                    setId(1, android.R.id.progress)
                    repeat(2) { layer ->
                        setLayerHeight(layer, context.dp(4))
                        setLayerGravity(layer, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
                    }
                }
        thumb =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize(context.dp(14), context.dp(14))
                setColor(PlayerUi.BLUE)
                setStroke(context.dp(2), palette.panel)
            }
        thumbOffset = context.dp(7)
        setOnSeekBarChangeListener(
            object : OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val position = positionAt(progress)
                    onPreview(position)
                    // 键盘和无障碍调节没有触摸结束事件，直接提交一次。
                    if (!tracking && duration() > 0) {
                        onSeek(position)
                        onSeekFinished()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    tracking = true
                    cancelled = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onPreview(positionAt(progress))
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    tracking = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    val commit = !cancelled && duration() > 0
                    if (commit) onSeek(positionAt(progress))
                    onSeekFinished()
                    if (!commit) update(latestPositionMs, latestDurationMs)
                }
            }
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancelled = true
        return super.onTouchEvent(event)
    }

    private fun positionAt(progress: Int): Long {
        val durationMs = duration().coerceAtLeast(0)
        return (progress.toDouble() / max * durationMs).toLong().coerceIn(0, durationMs)
    }

    fun update(positionMs: Long, durationMs: Long) {
        latestPositionMs = positionMs
        latestDurationMs = durationMs
        if (tracking) return
        isEnabled = durationMs > 0
        progress =
            ((positionMs.coerceIn(0, durationMs.coerceAtLeast(0)).toDouble() /
                    durationMs.coerceAtLeast(1)) * max)
                .roundToInt()
    }

    companion object {
        const val TOUCH_HEIGHT_DP = 40
    }
}
