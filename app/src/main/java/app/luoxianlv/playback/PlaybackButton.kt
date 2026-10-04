package app.luoxianlv.playback

import android.view.MotionEvent
import android.view.View

/** 在 DOWN 时锁定暂停意图，避免音符手势被取消后 UP 反而触发播放。 */
internal fun bindPlaybackButton(
    view: View,
    isActive: () -> Boolean,
    pause: () -> Unit,
    toggle: () -> Unit,
    canStart: () -> Boolean = { true },
) {
    // 手指 DOWN 可能先取消注入手势；立即暂停并消费本次点击，避免再次切换为播放。
    var suppressClick = false
    view.setOnTouchListener { target, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pauseRequested = isActive() || view.contentDescription == "暂停"
                suppressClick = pauseRequested || !canStart()
                if (pauseRequested) pause()
                suppressClick
            }
            MotionEvent.ACTION_UP ->
                if (suppressClick) {
                    target.performClick()
                    suppressClick = false
                    true
                } else false
            MotionEvent.ACTION_CANCEL -> {
                val consumed = suppressClick
                suppressClick = false
                consumed
            }
            else -> suppressClick
        }
    }
    view.setOnClickListener { if (!suppressClick) toggle() }
}
