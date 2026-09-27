package app.luoxianlv

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import app.luoxianlv.service.bindPlaybackButton
import app.luoxianlv.ui.floating.FloatingProgressView
import app.luoxianlv.ui.floating.PlayerUi

class PlaybackButtonInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            runOnMainSync {
                var active = true
                var pauses = 0
                var toggles = 0
                var canStart = true
                val button =
                    ImageView(targetContext).apply {
                        layout(0, 0, 100, 100)
                        contentDescription = "暂停"
                    }
                bindPlaybackButton(
                    button,
                    { active },
                    {
                        active = false
                        pauses++
                        button.contentDescription = "播放"
                    },
                    {
                        active = !active
                        toggles++
                    },
                    { canStart },
                )
                fun touch(action: Int) {
                    val now = SystemClock.uptimeMillis()
                    MotionEvent.obtain(now, now, action, 50f, 50f, 0).let {
                        button.dispatchTouchEvent(it)
                        it.recycle()
                    }
                }
                touch(MotionEvent.ACTION_DOWN)
                check(!active && pauses == 1) { "Pause was not immediate" }
                // Cancellation callback/state redraw happens before the finger is lifted.
                active = false
                touch(MotionEvent.ACTION_UP)
                check(!active && toggles == 0) { "Lifting pause finger restarted playback" }
                button.performClick() // Accessibility/keyboard activation is still supported.
                check(active && toggles == 1)
                touch(MotionEvent.ACTION_DOWN)
                touch(MotionEvent.ACTION_CANCEL)
                check(!active && toggles == 1)
                button.performClick()
                check(active && toggles == 2) { "Cancelled pause swallowed a later click" }
                active = false
                button.contentDescription = "暂停"
                touch(MotionEvent.ACTION_DOWN)
                touch(MotionEvent.ACTION_UP)
                check(!active && toggles == 2) {
                    "Visible pause action became play after note cancellation"
                }
                button.contentDescription = "播放"
                canStart = false
                touch(MotionEvent.ACTION_DOWN)
                canStart = true // Window expires while this same finger remains down.
                touch(MotionEvent.ACTION_UP)
                check(!active && toggles == 2) {
                    "Blocked DOWN became play when lifted after the window"
                }
                button.performClick()
                check(active && toggles == 3)
                verifyProgressView()
            }
            result.putString(
                "stream",
                "Pause DOWN/UP race, cancellation, stale pause icon accessibility click and floating progress seek passed.\n",
            )
            finish(-1, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            finish(0, result)
        }
    }

    private fun verifyProgressView() {
        val seeks = mutableListOf<Long>()
        var ended = 0
        val progress =
            FloatingProgressView(
                targetContext,
                PlayerUi.palette(targetContext),
                duration = { 1000L },
                onSeek = { seeks += it },
                onSeekFinished = { ended++ },
            )
        progress.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY),
        )
        progress.layout(0, 0, 400, 40)
        val fill = (progress.getChildAt(1) as ViewGroup).getChildAt(0)
        progress.update(500, 1000)
        val halfWidth = fill.layoutParams.width
        check(halfWidth > 0)
        fun touch(action: Int, x: Float) {
            val now = SystemClock.uptimeMillis()
            MotionEvent.obtain(now, now, action, x, 20f, 0).let {
                progress.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        touch(MotionEvent.ACTION_DOWN, 100f)
        progress.update(100, 1000)
        check(fill.layoutParams.width == halfWidth) {
            "Playback update overwrote a seek in progress"
        }
        touch(MotionEvent.ACTION_MOVE, 200f)
        touch(MotionEvent.ACTION_UP, 300f)
        check(seeks == listOf(500L, 750L) && ended == 1) {
            "Seek callbacks changed after extraction"
        }
        progress.update(100, 1000)
        check(fill.layoutParams.width < halfWidth)
    }
}
