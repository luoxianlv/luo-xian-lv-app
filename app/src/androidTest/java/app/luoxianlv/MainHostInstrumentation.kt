package app.luoxianlv

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.luoxianlv.hot.contract.NativePage

/** 在实际主页验证普通 Activity 宿主、页面重建、系统选择结果和既有提示计时。 */
class MainHostInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private fun find(
        label: String,
        node: AccessibilityNodeInfo? = uiAutomation.apply { clearCache() }.rootInActiveWindow,
    ): AccessibilityNodeInfo? {
        node ?: return null
        if (
            node.isVisibleToUser &&
                (node.text?.toString() == label || node.contentDescription?.toString() == label)
        )
            return node
        return (0 until node.childCount).firstNotNullOfOrNull { find(label, node.getChild(it)) }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { label }
            SystemClock.sleep(80)
        }
    }

    private fun click(label: String) {
        var node = checkNotNull(find(label)) { "未显示：$label" }
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "无法点击：$label" }
        SystemClock.sleep(450)
    }

    private fun scroll() {
        val display = targetContext.resources.displayMetrics
        val start = SystemClock.uptimeMillis()
        for (step in 0..20) {
            val event =
                MotionEvent.obtain(
                    start,
                    SystemClock.uptimeMillis(),
                    when (step) {
                        0 -> MotionEvent.ACTION_DOWN
                        20 -> MotionEvent.ACTION_UP
                        else -> MotionEvent.ACTION_MOVE
                    },
                    display.widthPixels * .68f,
                    display.heightPixels * (.78f - .46f * step / 20f),
                    0,
                )
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                check(uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
            SystemClock.sleep(16)
        }
        SystemClock.sleep(500)
    }

    override fun onStart() {
        var activity: MainActivity? = null
        try {
            activity =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                    as MainActivity
            await("原生宿主首页未显示") { find("演练场") != null }
            click("设置")
            await("设置页未显示") { find("网站账号") != null }
            repeat(6) { if (find("关于落弦律") == null) scroll() }
            click("关于落弦律")
            await("关于页未显示") { find("检查新版本") != null }
            val monitor = addMonitor(MainActivity::class.java.name, null, false)
            val old = activity
            runOnMainSync { old.recreate() }
            activity = waitForMonitorWithTimeout(monitor, 15000) as? MainActivity
            removeMonitor(monitor)
            val restored = checkNotNull(activity) { "主 Activity 未完成重建" }
            await("重建后丢失关于页") { find("检查新版本") != null && restored.hasWindowFocus() }
            checkMainPageSwap(restored)
            await("原位替换主业务页后丢失关于页") { find("检查新版本") != null }
            runOnMainSync { restored.onBackPressed() }
            await("返回未恢复设置页") { find("关于落弦律") != null }
            click("曲库")
            await("曲库未显示") { find("导入谱子") != null }
            click("导入谱子")
            await("导入页未显示") { find("选择文件") != null }
            val picker =
                addMonitor(
                    IntentFilter(Intent.ACTION_CHOOSER),
                    ActivityResult(Activity.RESULT_CANCELED, null),
                    true,
                )
            try {
                click("选择文件")
                await("文件选择未经过系统入口") { picker.hits == 1 }
                await("取消文件选择后仍锁住页面") {
                    var free = false
                    runOnMainSync { free = (restored.businessModels() as NativePage).canReplace() }
                    free
                }
                check(find("选择文件") != null) { "取消文件选择改变了当前页面" }
            } finally {
                removeMonitor(picker)
            }
            runOnMainSync { restored.onBackPressed() }
            await("导入返回未恢复曲库") { find("导入谱子") != null && find("选择文件") == null }
            checkSnackbarTiming(restored)
            finish(
                -1,
                Bundle().apply {
                    putString(
                        "stream",
                        "主宿主验证通过：主页/设置/曲库、关于页重建及原位替换、滚动位置、系统返回、替换后的 MIDI 选择取消回调和提示计时。\n",
                    )
                },
            )
        } catch (error: Throwable) {
            finish(
                0,
                Bundle().apply { putString("stream", "主宿主验证失败：${error.stackTraceToString()}") },
            )
        } finally {
            activity?.let { runOnMainSync { it.finish() } }
        }
    }
}
