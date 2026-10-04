package app.luoxianlv

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import app.luoxianlv.discover.DiscoverViewModel
import app.luoxianlv.discover.RemoteUiState
import app.luoxianlv.platform.PlatformScore
import app.luoxianlv.playback.FloatingControls
import app.luoxianlv.playback.PlaybackConnection
import kotlinx.coroutines.flow.MutableStateFlow

/** 在真正的自由窗口与系统悬浮窗口中验证触摸、缩放和停靠。 */
class InteractionInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private val automation
        get() = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand(command)
            )
            .bufferedReader()
            .use { it.readText().trim() }

    private fun await(label: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 30000
        while (!predicate()) {
            check(SystemClock.uptimeMillis() < end) { label }
            SystemClock.sleep(100)
        }
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun find(
        text: String,
        node: AccessibilityNodeInfo? = automation.apply { clearCache() }.rootInActiveWindow,
    ): AccessibilityNodeInfo? {
        node ?: return null
        if (
            node.isVisibleToUser &&
                (node.text?.toString()?.startsWith(text) == true ||
                    node.contentDescription?.toString() == text)
        )
            return node
        return (0 until node.childCount).firstNotNullOfOrNull { find(text, node.getChild(it)) }
    }

    private fun click(text: String) {
        var node = checkNotNull(find(text)) { "找不到 $text" }
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "无法点击 $text" }
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)

    private fun swipe(x: Float, start: Float, end: Float) {
        val down = SystemClock.uptimeMillis()
        for (i in 0..18) {
            SystemClock.sleep(16)
            val action =
                if (i == 0) MotionEvent.ACTION_DOWN
                else if (i == 18) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
            val event =
                MotionEvent.obtain(
                    down,
                    SystemClock.uptimeMillis(),
                    action,
                    x,
                    start + (end - start) * i / 18f,
                    0,
                )
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                check(automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        SystemClock.sleep(600)
    }

    override fun onStart() {
        val result = Bundle()
        var success = false
        var activity: MainActivity? = null
        val priorMode = shell("wm get-display-windowing-mode")
        val priorServices = shell("settings get secure enabled_accessibility_services")
        val priorEnabled = shell("settings get secure accessibility_enabled")
        val floatingPrefs = app.luoxianlv.shared.Kv.of(targetContext, "floating_position")
        val oldX = floatingPrefs.getInt("x", 28)
        val oldY = floatingPrefs.getInt("y", 245)
        val oldDock = floatingPrefs.getString("dock", null)
        var previousVisible = false
        try {
            shell("wm set-display-windowing-mode 5")
            activity =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    android.app.ActivityOptions.makeBasic()
                        .setLaunchBounds(Rect(60, 180, 640, 990))
                        .toBundle(),
                )
                    as MainActivity
            val main = activity
            await("没有进入自由窗口") { main.isInMultiWindowMode }
            shell("am task resize ${main.taskId} 60 180 640 990")
            await("首页未显示") { find("演练场") != null }
            click("发现")
            await("发现页未显示") { find("搜索谱子") != null }
            lateinit var vm: DiscoverViewModel
            runOnMainSync {
                vm = ViewModelProvider(main.businessModels())[DiscoverViewModel::class.java]
            }
            await("发现数据加载未结束") { !vm.state.value.loading && !vm.state.value.loadingMore }
            @Suppress("UNCHECKED_CAST")
            val state = field(vm, "_state") as MutableStateFlow<RemoteUiState>
            runOnMainSync {
                state.value =
                    RemoteUiState(
                        status = "测试曲目",
                        scores =
                            (0 until 100).map {
                                PlatformScore(
                                    "test-$it",
                                    "滚动测试 $it 很长的曲名验证窄窗口显示",
                                    "测试作者",
                                    120,
                                    null,
                                    "",
                                )
                            },
                        visibleCount = 100,
                    )
            }
            await("首个曲目未显示") { find("滚动测试 0") != null }
            await("小窗尚未获得触摸焦点") { main.hasWindowFocus() }
            SystemClock.sleep(600)
            val search = bounds(checkNotNull(find("搜索谱子")))
            val first = bounds(checkNotNull(find("滚动测试 0")))
            // 从原先固定的搜索区起手；旧布局不会带动列表。
            swipe(search.centerX().toFloat(), search.bottom.toFloat(), search.top - 90f)
            val after = find("滚动测试 0")?.let(::bounds)
            check(after == null || after.top < first.top - 40) { "从页头拖动没有带动列表：$first → $after" }
            val window = Rect()
            runOnMainSync { main.window.decorView.getWindowVisibleDisplayFrame(window) }
            swipe(
                window.centerX().toFloat(),
                window.top + window.height() * .72f,
                window.top + window.height() * .28f,
            )
            check(find("滚动测试 0") == null) { "小窗列表没有继续滚动" }
            val screenshot = automation.takeScreenshot()
            java.io
                .File(targetContext.getExternalFilesDir(null), "small-window-check.png")
                .outputStream()
                .use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            screenshot.recycle()
            shell("am task resize ${main.taskId} 100 220 600 880")
            SystemClock.sleep(700)
            check(find("滚动测试 0") == null) { "调整窗口大小重置了滚动位置" }
            check(main.isInMultiWindowMode && !main.isDestroyed)
            shell("wm set-display-windowing-mode 1")
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
            await("无障碍服务未连接") { PlaybackConnection.instance != null }
            val service = PlaybackConnection.instance!!
            previousVisible = service.floatingVisible
            val controls = field(currentPlaybackSession(), "floating") as FloatingControls
            runOnMainSync { service.showFloating(true) }
            await("悬浮球未显示") { field(controls, "root") != null }
            fun root() = field(controls, "root") as View
            fun params() = field(controls, "params") as WindowManager.LayoutParams
            fun drag(toX: Float, cancel: Boolean = false) {
                runOnMainSync {
                    val view = root()
                    val p = params()
                    val down = SystemClock.uptimeMillis()
                    val start = p.x + view.width * .75f
                    listOf(
                            MotionEvent.ACTION_DOWN,
                            MotionEvent.ACTION_MOVE,
                            if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,
                        )
                        .forEachIndexed { index, action ->
                            val event =
                                MotionEvent.obtain(
                                    down,
                                    down + index * 40L,
                                    action,
                                    if (index == 0) start else toX,
                                    p.y + view.height / 2f,
                                    0,
                                )
                            view.dispatchTouchEvent(event)
                            event.recycle()
                        }
                }
                SystemClock.sleep(350)
            }
            drag(0f)
            runOnMainSync {
                val location = IntArray(2)
                root().getLocationOnScreen(location)
                check(kotlin.math.abs(location[0] + root().width / 2) <= 1) {
                    "系统没有允许半隐藏：${location[0]}"
                }
                check(root().alpha in 0.44f..0.46f)
            }
            drag(service.screenBounds().width() / 2f)
            runOnMainSync { check(params().x > 0 && root().alpha == 1f) }
            val beforeCancel = params().x
            drag(0f, cancel = true)
            check(params().x == beforeCancel) { "取消拖动改变了位置" }
            drag(service.screenBounds().width().toFloat())
            runOnMainSync { check(params().x == service.screenBounds().width() - root().width / 2) }
            runOnMainSync { root().performClick() }
            SystemClock.sleep(350)
            runOnMainSync {
                check(root().alpha == 1f)
                check(
                    params().x >= 0 && params().x + root().width <= service.screenBounds().width()
                )
            }
            result.putString(
                "stream",
                "通过：真实自由窗口页头拖动、列表惯性滚动、缩窗保留位置；悬浮球左右半隐藏、透明度、拖回恢复、取消回位、展开面板完整显示。\n",
            )
            success = true
        } catch (error: Throwable) {
            val bitmap = automation.takeScreenshot()
            java.io
                .File(targetContext.getExternalFilesDir(null), "interaction-failure.png")
                .outputStream()
                .use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            bitmap.recycle()
            result.putString("stream", error.stackTraceToString())
        } finally {
            runOnMainSync {
                PlaybackConnection.instance?.showFloating(previousVisible)
                activity?.finish()
            }
            floatingPrefs
                .edit()
                .putInt("x", oldX)
                .putInt("y", oldY)
                .putString("dock", oldDock)
                .commit()
            if (priorServices == "null")
                shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $priorServices")
            if (priorEnabled == "null") shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $priorEnabled")
            shell("wm set-display-windowing-mode ${if(priorMode.contains("freeform")) 5 else 1}")
        }
        finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
