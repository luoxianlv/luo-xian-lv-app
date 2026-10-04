package app.luoxianlv

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import app.luoxianlv.playback.FloatingControls
import app.luoxianlv.playback.PlaybackConnection
import app.luoxianlv.playback.PlayerUi.dp
import java.util.concurrent.atomic.AtomicReference

/** 验证真实系统窗口的通知去重、转屏复用与选歌内容保留；只供测试设备运行。 */
class FloatingDisplayInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
                getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                    .executeShellCommand(command)
            )
            .bufferedReader()
            .use { it.readText().trim() }

    private fun value(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    private fun main(action: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        runOnMainSync {
            try {
                action()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        failure.get()?.let { throw AssertionError("浮窗显示回归失败", it) }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (true) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            check(SystemClock.uptimeMillis() < deadline) { "等待超时：$label" }
            SystemClock.sleep(40)
        }
    }

    private fun search(view: View): EditText? =
        if (view is EditText) view
        else if (view is ViewGroup)
            (0 until view.childCount).firstNotNullOfOrNull { search(view.getChildAt(it)) }
        else null

    private fun described(view: View, text: String): View? =
        if (view.contentDescription?.toString() == text) view
        else if (view is ViewGroup)
            (0 until view.childCount).firstNotNullOfOrNull { described(view.getChildAt(it), text) }
        else null

    override fun onStart() {
        val result = Bundle()
        val priorServices = shell("settings get secure enabled_accessibility_services")
        val priorEnabled = shell("settings get secure accessibility_enabled")
        var activity: Activity? = null
        var controls: FloatingControls? = null
        var previousVisible = false
        var previousState: Bundle? = null
        var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        var success = false
        try {
            val component =
                "${targetContext.packageName}/app.luoxianlv.service.MusicAccessibilityService"
            val enabled =
                priorServices
                    .takeUnless { it == "null" || it.isBlank() }
                    .orEmpty()
                    .split(':')
                    .filter { it.isNotEmpty() }
                    .toMutableSet()
            enabled.add(component)
            shell("settings put secure enabled_accessibility_services ${enabled.joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            activity =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            val host = checkNotNull(activity)
            previousOrientation = host.requestedOrientation
            main { host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await("无障碍连接") { PlaybackConnection.instance != null }
            val connection = checkNotNull(PlaybackConnection.instance)
            val session = currentPlaybackSession()
            val floating = value(session, "floating") as FloatingControls
            controls = floating
            previousVisible = connection.floatingVisible
            main {
                previousState = floating.snapshot()
                connection.showFloating(false)
                connection.showFloating(true)
            }
            await("竖屏气泡挂载") {
                value(floating, "root") != null &&
                    connection.screenBounds().height() > connection.screenBounds().width() &&
                    !floating.interacting
            }
            val listener = value(session, "displayListener") as DisplayManager.DisplayListener
            var bubble: View? = null
            var anchorX = 0
            var anchorY = 0
            main {
                val view = value(floating, "root") as View
                val layout = value(floating, "params") as WindowManager.LayoutParams
                val bounds = connection.screenBounds()
                val startX = layout.x + view.measuredWidth / 2f
                val startY = layout.y + view.measuredHeight / 2f
                // 靠近右下方但不贴边；展开的大面板需要临时钳制位置。
                anchorX = bounds.width() - view.measuredWidth - targetContext.dp(30)
                anchorY = bounds.height() - view.measuredHeight - targetContext.dp(30)
                val touchX = startX + anchorX - layout.x
                val touchY = startY + anchorY - layout.y
                val down = SystemClock.uptimeMillis()
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)
                    .forEachIndexed { index, action ->
                        val event =
                            MotionEvent.obtain(
                                down,
                                down + index * 20L,
                                action,
                                if (index == 0) startX else touchX,
                                if (index == 0) startY else touchY,
                                0,
                            )
                        view.dispatchTouchEvent(event)
                        event.recycle()
                    }
            }
            await("气泡拖动完成") { !floating.interacting }
            main {
                bubble = value(floating, "root") as View
                check(floating.snapshot().getString("dock") == "NONE")
                val revision = floating.revision
                val sessionRevision = value(session, "changeRevision")
                repeat(200) { listener.onDisplayChanged(Display.DEFAULT_DISPLAY) }
                check(value(floating, "root") === bubble)
                check(floating.revision == revision) { "同几何显示通知触发浮窗变更" }
                check(value(session, "changeRevision") == sessionRevision) { "同几何通知打断热更安全点" }
                check(value(floating, "pendingGeometry") == null)
                check(!floating.interacting)
                bubble!!.performClick()
            }
            await("展开面板") { value(floating, "root") !== bubble }
            var panel: View? = null
            main {
                panel = value(floating, "root") as View
            }
            repeat(3) {
                main {
                    checkNotNull(described(panel!!, "展开倍速设置")).performClick()
                    check(value(floating, "root") === panel) { "展开倍速重建了滑块" }
                    checkNotNull(described(panel!!, "收起倍速设置")).performClick()
                    check(value(floating, "root") === panel) { "收起倍速重建了滑块" }
                    checkNotNull(described(panel!!, "收起")).performClick()
                    val layout = value(floating, "params") as WindowManager.LayoutParams
                    check(layout.x == anchorX && layout.y == anchorY) {
                        "展开或倍速切换改变了气泡锚点：${layout.x},${layout.y}，预期 $anchorX,$anchorY"
                    }
                }
                SystemClock.sleep(80)
                main { (value(floating, "root") as View).performClick() }
                await("再次展开保留面板") { value(floating, "root") === panel }
            }
            main {
                checkNotNull(described(panel!!, "展开倍速设置")).performClick()
                host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            await("横屏布局完成") {
                connection.screenBounds().width() > connection.screenBounds().height() &&
                    value(floating, "pendingGeometry") == null
            }
            main {
                check(value(floating, "root") === panel) { "转屏重建了面板" }
                check(floating.snapshot().getBoolean("expanded"))
                check(floating.snapshot().getBoolean("speedControls")) { "转屏丢失倍速面板" }
                floating.javaClass
                    .getDeclaredMethod("showPlaylist", List::class.java)
                    .apply { isAccessible = true }
                    .invoke(floating, listOf(session.song))
            }
            val playlist = value(floating, "playlistWindow")!!
            val card = value(playlist, "view") as View
            val input = checkNotNull(search(card))
            main {
                input.setText("保留搜索")
                input.requestFocus()
                host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            await("选歌窗转回竖屏") {
                connection.screenBounds().height() > connection.screenBounds().width() &&
                    value(floating, "pendingGeometry") == null
            }
            main {
                check(value(playlist, "view") === card) { "转屏重建了选歌窗口" }
                check(input.text.toString() == "保留搜索" && input.hasFocus())
                floating.javaClass
                    .getDeclaredMethod("dismissPlaylist")
                    .apply { isAccessible = true }
                    .invoke(floating)
                host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            await("真实几何变化已排队") { value(floating, "pendingGeometry") != null }
            main { connection.showFloating(false) }
            SystemClock.sleep(350)
            main {
                check(value(floating, "root") == null && value(floating, "pendingGeometry") == null)
                check(
                    value(floating, "cachedPanel") == null &&
                        value(floating, "panelPrewarm") == null
                ) {
                    "关闭悬浮窗未释放面板或预备任务"
                }
                check(!floating.isVisible && !floating.interacting) { "隐藏后仍有待处理窗口" }
            }
            result.putString(
                "stream",
                "通过：200 次非几何通知零重建；三轮展开收起与倍速切换复用面板并保留气泡锚点；横竖屏保留面板与选歌搜索；隐藏取消布局并释放缓存。\n",
            )
            success = true
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
        } finally {
            main {
                PlaybackConnection.instance?.showFloating(false)
                previousState?.let { controls?.restore(it) }
                PlaybackConnection.instance?.showFloating(previousVisible)
                activity?.requestedOrientation = previousOrientation
                activity?.finish()
            }
            if (priorServices == "null")
                shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $priorServices")
            if (priorEnabled == "null") shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $priorEnabled")
        }
        finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
