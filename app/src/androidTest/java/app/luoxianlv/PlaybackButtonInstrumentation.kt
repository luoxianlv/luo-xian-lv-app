package app.luoxianlv

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import app.luoxianlv.business.playback.PlaybackSession
import app.luoxianlv.service.bindPlaybackButton
import app.luoxianlv.ui.floating.FloatingPanel
import app.luoxianlv.ui.floating.FloatingProgressView
import app.luoxianlv.ui.floating.PlayerUi
import app.luoxianlv.ui.floating.PlayerUi.dp

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
                // 模拟手指尚未抬起时，手势取消回调已触发状态重绘。
                active = false
                touch(MotionEvent.ACTION_UP)
                check(!active && toggles == 0) { "Lifting pause finger restarted playback" }
                button.performClick() // 无障碍和键盘激活仍然可用。
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
                canStart = true // 同一次按下期间，中断保护窗口已经过期。
                touch(MotionEvent.ACTION_UP)
                check(!active && toggles == 2) {
                    "Blocked DOWN became play when lifted after the window"
                }
                button.performClick()
                check(active && toggles == 3)
                verifyProgressView()
                verifyProgressLayout()
            }
            result.putString(
                "stream",
                "暂停竞态、取消、无障碍点击，以及悬浮进度条热区、预览、单次定位和布局检查通过。\n",
            )
            finish(-1, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            finish(0, result)
        }
    }

    private fun verifyProgressView() {
        val seeks = mutableListOf<Long>()
        val previews = mutableListOf<Long>()
        var duration = 1000L
        var ended = 0
        lateinit var progress: FloatingProgressView
        progress =
            FloatingProgressView(
                targetContext,
                PlayerUi.palette(targetContext),
                duration = { duration },
                onSeek = {
                    seeks += it
                    // 播放定位会同步刷新多次，目标不能被中间旧状态覆盖。
                    progress.update(100, duration)
                    progress.update(it, duration)
                },
                onSeekFinished = { ended++ },
                onPreview = { previews += it },
            )
        val height = targetContext.dp(40)
        progress.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        progress.layout(0, 0, 400, height)
        progress.update(500, 1000)
        val start = progress.paddingLeft.toFloat()
        val end = (progress.width - progress.paddingRight).toFloat()
        fun touch(action: Int, x: Float, y: Float = height / 2f) {
            val now = SystemClock.uptimeMillis()
            MotionEvent.obtain(now, now, action, x, y, 0).let {
                progress.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        // 在细轨道上方也能点中；播放刷新不能覆盖手指的预览位置。
        touch(MotionEvent.ACTION_DOWN, start, targetContext.dp(3).toFloat())
        check(progress.progress == 0 && previews.last() == 0L && seeks.isEmpty())
        progress.update(100, 1000)
        check(progress.progress == 0) { "播放刷新覆盖了手指预览" }
        touch(MotionEvent.ACTION_MOVE, (start + end) / 2f)
        check(progress.progress == 500 && previews.last() == 500L && seeks.isEmpty()) {
            "拖动预览未跟手，或移动时提前提交了定位"
        }
        touch(MotionEvent.ACTION_UP, end)
        check(seeks == listOf(1000L) && ended == 1) { "松手没有只提交一次终点定位" }

        // 点击轨道起点即可定位，无须命中滑块；取消不提交，后续刷新仍正常。
        touch(MotionEvent.ACTION_DOWN, start)
        touch(MotionEvent.ACTION_UP, start)
        check(seeks == listOf(1000L, 0L))
        touch(MotionEvent.ACTION_DOWN, end)
        progress.update(250, 1000)
        touch(MotionEvent.ACTION_CANCEL, end)
        check(seeks.size == 2 && progress.progress == 250) { "取消手势提交了定位或没有恢复" }
        progress.update(100, 1000)
        check(progress.progress == 100) { "取消后进度停止刷新" }
        touch(MotionEvent.ACTION_DOWN, (start + end) / 2f)
        touch(MotionEvent.ACTION_UP, (start + end) / 2f)
        check(seeks.last() == 500L && seeks.size == 3) { "取消后不能再次拖动" }

        val arguments =
            Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 750f)
            }
        check(
            progress.performAccessibilityAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                arguments,
            )
        )
        check(seeks.last() == 750L && seeks.size == 4) { "无障碍进度调节未提交" }
        val count = seeks.size
        listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL).forEach { finish ->
            duration = 1000
            progress.update(500, duration)
            touch(MotionEvent.ACTION_DOWN, end)
            duration = 0
            progress.update(0, duration)
            touch(finish, end)
            check(!progress.isEnabled && seeks.size == count) { "拖动期间曲目失效仍然提交定位" }
        }
        touch(MotionEvent.ACTION_DOWN, end)
        touch(MotionEvent.ACTION_UP, end)
        check(!progress.isEnabled && seeks.size == count) { "未就绪的曲目仍然触发定位" }
    }

    private fun verifyProgressLayout() {
        val context = ContextThemeWrapper(targetContext, R.style.AppTheme)
        listOf(false, true).forEach { speedVisible ->
            var picked = 0
            var speedToggled = 0
            val panel =
                FloatingPanel(
                    context,
                    PlayerUi.palette(context),
                    PlaybackSession(),
                    speedVisible,
                    onSelectSong = { picked++ },
                    onToggleSpeed = { speedToggled++ },
                    onCollapse = {},
                    onAttachDrag = {},
                )
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(context.dp(236), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            val strip =
                (0 until panel.childCount)
                    .map(panel::getChildAt)
                    .filterIsInstance<FloatingProgressView>()
                    .single()
            check(strip.height >= context.dp(40)) { "歌曲进度热区没有扩大" }
            check(strip.top >= panel.getChildAt(0).bottom) { "进度条覆盖了播放或曲名区" }
            if (speedVisible) check(strip.top >= panel.getChildAt(1).bottom) { "进度条覆盖了倍速设置" }
            val controls = panel.getChildAt(0) as android.view.ViewGroup
            controls.getChildAt(2).performClick()
            controls.getChildAt(3).performClick()
            check(picked == 1 && speedToggled == 1) { "原控制按钮不能使用" }
            if (!speedVisible) {
                panel.refresh()
                strip.update(500, 1000)
                val image = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
                panel.draw(Canvas(image))
                java.io
                    .File(targetContext.filesDir, "floating-progress-preview.png")
                    .outputStream()
                    .use {
                        image.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                image.recycle()
            }
        }
    }
}
