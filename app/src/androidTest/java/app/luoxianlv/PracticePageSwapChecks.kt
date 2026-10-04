package app.luoxianlv

import android.app.Instrumentation
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import app.luoxianlv.hot.MainSwapFixture
import app.luoxianlv.hot.PageSwapHost
import app.luoxianlv.library.PlayMode
import app.luoxianlv.practice.*
import app.luoxianlv.practice.PracticePage
import app.luoxianlv.ui.practice.PracticeActivity
import java.util.concurrent.atomic.AtomicReference

/** 使用包内新业务实例检查演练场迁移；不冒充下载新 Dex 或真实 60 秒观察。 */
internal fun Instrumentation.checkPracticePageSwap(activity: PracticeActivity): PracticeKeyboard {
    fun main(action: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        runOnMainSync {
            try {
                action()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        failure.get()?.let { throw AssertionError("演练场替换失败", it) }
    }
    fun await(label: String, test: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 30000
        while (!test()) {
            check(SystemClock.uptimeMillis() < end) { label }
            SystemClock.sleep(50)
        }
    }
    fun <T : View> find(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is ViewGroup)
            return (0 until view.childCount).firstNotNullOfOrNull {
                find(view.getChildAt(it), type)
            }
        return null
    }
    val fixture =
        MainSwapFixture(context, targetContext, PracticePage::class.java, activity.resources)
    lateinit var original: PracticeKeyboard
    lateinit var previous: LifecycleOwner
    lateinit var current: PracticeKeyboard
    val window = activity.window
    main {
        original = checkNotNull(find(window.decorView, PracticeKeyboard::class.java))
        previous = checkNotNull(original.findViewTreeLifecycleOwner())
        original.session.select(PracticeSession.Mode.RAISE)
        if (!original.session.half) original.session.toggleHalf()
        original.session.press(55, 2)
        val pages = checkNotNull(find(window.decorView, PageSwapHost::class.java))
        pages.bindController(fixture.controller)
        check(pages.offer(fixture.prepared, fixture.ticket))
    }
    SystemClock.sleep(350)
    main {
        check(find(window.decorView, PracticeKeyboard::class.java) === original) { "保持音符时开始替换" }
        check(PracticePlaybackGate.ready && original.session.active != null)
        original.session.cancel()
    }
    await("演练场未在空闲后原位替换") {
        var done = false
        main {
            find(window.decorView, PracticeKeyboard::class.java)?.let {
                if (it !== original && !original.isAttachedToWindow && PracticePlaybackGate.ready) {
                    current = it
                    done = true
                }
            }
        }
        done
    }
    main {
        check(activity.window === window && activity.hasWindowFocus())
        check(current.session.mode == PracticeSession.Mode.RAISE && current.session.half)
        check(current.session.active == null) { "新版错误恢复了持续按下状态" }
        check(previous.lifecycle.currentState == Lifecycle.State.CREATED)
    }
    fixture.elapsed.addAndGet(60000)
    await("旧演奏页未释放") {
        var retired = false
        main { retired = previous.lifecycle.currentState == Lifecycle.State.DESTROYED }
        retired && fixture.stable()
    }
    main {
        check(PracticePlaybackGate.active && PracticePlaybackGate.ready) { "旧页销毁关闭了新版演奏" }
        check(PracticePlaybackGate.pitchState() == (PlayMode.RAISE to true))
    }
    fixture.cleanSuccessfulRun()
    return current
}
