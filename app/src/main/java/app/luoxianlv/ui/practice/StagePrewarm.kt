package app.luoxianlv.ui.practice

import android.content.Context
import android.graphics.RectF
import android.os.Looper
import android.os.MessageQueue
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.luoxianlv.business.ui.findActivity
import app.luoxianlv.wallpaper.render.PreparedWallpaper

/** 首次绘制后等主队列空闲再预热；交互和切页先让路，缓存不随 Tab 切换重建。 */
@Composable
fun StagePrewarmEffect(enabled: Boolean = true, moving: Boolean = false): Modifier {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context.findActivity() ?: return Modifier
    val controller =
        remember(context, lifecycleOwner, activity) {
            val lease = Any()
            val queue = PrewarmFrameQueue(activity.window.decorView)
            StagePrewarmController(
                schedule =
                    IdlePrewarmSchedule(
                        enqueue = queue::enqueue,
                        cancel = queue::cancel,
                        pause = { PreparedWallpaper.pause(lease) },
                        prepare = {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                PreparedWallpaper.prepare(activity, context, lease)
                            }
                        },
                    ),
                claim = { PreparedWallpaper.claim(lease) },
                clear = { PreparedWallpaper.clear(lease) },
            )
        }
    SideEffect { controller.update(enabled, moving) }
    DisposableEffect(controller, lifecycleOwner) {
        val observer =
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                    controller.resume()
                }

                override fun onPause(owner: androidx.lifecycle.LifecycleOwner) {
                    controller.pause()
                }

                override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                    controller.close()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.close()
        }
    }
    // Initial 阶段只观察按下状态；不消费事件，也不影响按钮、列表和 Pager 的手势。
    return Modifier.pointerInput(controller) {
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    controller.touching(event.changes.any { it.pressed })
                }
            }
        } finally {
            controller.touching(false)
        }
    }
}

private class StagePrewarmController(
    private val schedule: IdlePrewarmSchedule,
    private val claim: () -> Unit,
    private val clear: () -> Unit,
) {
    private var resumed = false
    private var enabled = false
    private var moving = false
    private var touching = false
    private var closed = false

    fun update(enabled: Boolean, moving: Boolean) {
        this.enabled = enabled
        this.moving = moving
        refresh()
    }

    fun touching(pressed: Boolean) {
        touching = pressed
        refresh()
    }

    fun resume() {
        if (closed) return
        claim()
        resumed = true
        refresh()
    }

    fun pause() {
        resumed = false
        refresh()
    }

    private fun refresh() = schedule.update(resumed && enabled && !moving && !touching)

    fun close() {
        if (closed) return
        closed = true
        schedule.close()
        clear()
    }
}

/** 绘制监听不能在回调中移除；先 post 到绘制结束，再等待主线程 MessageQueue 空闲。 */
private class PrewarmFrameQueue(private val decor: View) {
    private val queue = Looper.myQueue()
    private var observer: ViewTreeObserver? = null
    private var draw: ViewTreeObserver.OnDrawListener? = null
    private var afterDraw: Runnable? = null
    private var idle: MessageQueue.IdleHandler? = null

    fun enqueue(prepare: Runnable) {
        cancel()
        val next = decor.viewTreeObserver
        val after = Runnable {
            removeDrawListener()
            afterDraw = null
            val handler = MessageQueue.IdleHandler {
                idle = null
                prepare.run()
                false
            }
            idle = handler
            queue.addIdleHandler(handler)
        }
        var posted = false
        val listener = ViewTreeObserver.OnDrawListener {
            if (!posted) {
                posted = true
                decor.post(after)
            }
        }
        observer = next
        draw = listener
        afterDraw = after
        next.addOnDrawListener(listener)
        decor.invalidate()
    }

    fun cancel() {
        afterDraw?.let(decor::removeCallbacks)
        afterDraw = null
        idle?.let(queue::removeIdleHandler)
        idle = null
        removeDrawListener()
    }

    private fun removeDrawListener() {
        val listener = draw
        if (listener != null && observer?.isAlive == true) observer?.removeOnDrawListener(listener)
        draw = null
        observer = null
    }
}

/** 带开幕动画进入演练场，幕布从 [origin]（入口按钮的窗口坐标）展开；拿不到 Activity 时退化为直接跳转。 */
fun openPracticeStage(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    origin: RectF,
    dark: Boolean,
    onPractice: (Boolean) -> Unit,
) {
    val activity = context.findActivity()
    if (activity == null) onPractice(dark)
    else StageEntry.open(activity, context, lifecycleOwner, origin, dark) { onPractice(dark) }
}
