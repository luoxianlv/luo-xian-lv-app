package app.luoxianlv

import android.app.Instrumentation
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import app.luoxianlv.business.MainPage
import app.luoxianlv.hot.MainSwapFixture
import app.luoxianlv.hot.PageSwapHost
import java.util.concurrent.atomic.AtomicReference

/** 重建实际主业务页面，不下载新 Dex；用于发现只测简单 View 无法覆盖的 Compose 集成问题。 */
internal fun Instrumentation.checkMainPageSwap(activity: MainActivity) {
    fun main(action: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        runOnMainSync {
            try {
                action()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        failure.get()?.let { throw AssertionError("主页面替换失败", it) }
    }
    fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { message }
            SystemClock.sleep(50)
        }
    }
    fun host(view: View): PageSwapHost? =
        when (view) {
            is PageSwapHost -> view
            is ViewGroup ->
                (0 until view.childCount).firstNotNullOfOrNull { host(view.getChildAt(it)) }
            else -> null
        }
    val fixture = MainSwapFixture(context, targetContext, MainPage::class.java, activity.resources)
    lateinit var original: androidx.compose.ui.platform.ComposeView
    lateinit var oldPage: MainPage
    val window = activity.window
    main {
        original = activity.businessComposeView()
        oldPage = activity.businessModels() as MainPage
        val pages = checkNotNull(host(window.decorView))
        pages.bindController(fixture.controller)
        check(pages.offer(fixture.prepared, fixture.ticket))
    }
    await("同窗口未切换到新的主业务页面") {
        var swapped = false
        main {
            val current = activity.businessComposeView()
            swapped = current !== original && !original.isAttachedToWindow
        }
        swapped
    }
    main {
        check(activity.window === window && !activity.isFinishing && activity.hasWindowFocus())
        check(activity.businessModels() !== oldPage)
        check(oldPage.lifecycle.currentState == Lifecycle.State.CREATED) { "试运行期间没有保留旧业务页" }
    }
    // 使用测试时钟缩短本地回归；真实 60 秒观察另由 NativeOnlineChecks 验证。
    fixture.elapsed.addAndGet(60000)
    await("旧主页面未在稳定后释放") {
        var released = false
        main { released = oldPage.lifecycle.currentState == Lifecycle.State.DESTROYED }
        released && fixture.stable()
    }
    fixture.cleanSuccessfulRun()
}
