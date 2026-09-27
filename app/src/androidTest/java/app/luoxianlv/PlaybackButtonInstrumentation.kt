package app.luoxianlv

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.ImageView
import app.luoxianlv.service.bindPlaybackButton

class PlaybackButtonInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            runOnMainSync {
                var active = true
                var pauses = 0
                var toggles = 0
                var canStart = true
                val button = ImageView(targetContext).apply { layout(0,0,100,100); contentDescription = "暂停" }
                bindPlaybackButton(button,{ active },{ active = false; pauses++; button.contentDescription = "播放" },{ active = !active; toggles++ },{ canStart })
                fun touch(action: Int) {
                    val now = SystemClock.uptimeMillis()
                    MotionEvent.obtain(now,now,action,50f,50f,0).let { button.dispatchTouchEvent(it); it.recycle() }
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
                active = false; button.contentDescription = "暂停"
                touch(MotionEvent.ACTION_DOWN); touch(MotionEvent.ACTION_UP)
                check(!active && toggles == 2) { "Visible pause action became play after note cancellation" }
                button.contentDescription = "播放"
                canStart = false
                touch(MotionEvent.ACTION_DOWN)
                canStart = true // Window expires while this same finger remains down.
                touch(MotionEvent.ACTION_UP)
                check(!active && toggles == 2) { "Blocked DOWN became play when lifted after the window" }
                button.performClick()
                check(active && toggles == 3)
            }
            result.putString("stream","Pause DOWN/UP race, cancellation, stale pause icon and accessibility click passed.\n")
            finish(-1,result)
        } catch (error: Throwable) {
            result.putString("stream",error.stackTraceToString()); finish(0,result)
        }
    }
}
