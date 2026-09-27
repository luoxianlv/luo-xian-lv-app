package app.luoxianlv.service

import android.view.MotionEvent
import android.view.View

/** Pause intent is latched at DOWN so cancelled note gestures cannot turn UP into play. */
internal fun bindPlaybackButton(
    view: View,
    isActive: () -> Boolean,
    pause: () -> Unit,
    toggle: () -> Unit,
    canStart: () -> Boolean = { true },
) {
    // A finger DOWN can cancel the injected note before UP arrives. Pause now,
    // and consume this same click instead of toggling the newly paused state.
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
