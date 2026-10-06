package app.luoxianlv.business.ui

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import app.luoxianlv.hot.contract.HostActions
import app.luoxianlv.hot.contract.NativePage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DialogHarnessActivity : Activity() {
    val page = DialogPage()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        page.attachHost(
            object : HostActions {
                override fun launch(key: String, intent: Intent, options: Bundle?) = Unit

                override fun permissions(key: String, permissions: Array<String>) = Unit

                override fun resultReady(key: String) = Unit

                override fun hasPendingResults() = false

                override fun finish() = Unit
            }
        )
        setContentView(page.create(this, Bundle(), Bundle(), { _, _ -> }, {}))
        page.lifecycle(NativePage.STARTED)
    }

    override fun onDestroy() {
        page.close()
        super.onDestroy()
    }
}

class DialogPage : ComposePage() {
    var visible by mutableStateOf(false)
    var requested by mutableStateOf(true)
    var custom by mutableStateOf(false)
    var composedVisible = false
        private set

    fun diagnostic() =
        "类型=$custom 生命周期=${lifecycle.currentState} 活动=$isActive 路由=$visible 合成=$composedVisible"

    @Composable
    override fun Content() {
        MaterialTheme {
            val enabled = LocalPageVisible.current && visible
            SideEffect { composedVisible = enabled }
            Text("页面底座")
            CompositionLocalProvider(LocalPageVisible provides enabled) {
                if (requested) {
                    if (custom)
                        PageDialog(onDismissRequest = { requested = false }) { Text("可见性验证") }
                    else
                        PageAlertDialog(
                            onDismissRequest = { requested = false },
                            confirmButton = {
                                TextButton(onClick = { requested = false }) { Text("完成") }
                            },
                            title = { Text("可见性验证") },
                        )
                }
            }
        }
    }
}

internal object DialogVisibilityChecks {
    fun run(runner: Instrumentation) {
        val activity =
            runner.startActivitySync(
                Intent(runner.context, DialogHarnessActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as DialogHarnessActivity
        val automation =
            runner.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags =
                    flags or
                        android.accessibilityservice.AccessibilityServiceInfo
                            .FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        fun contains(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.isVisibleToUser && node.text?.toString() == "可见性验证") return true
            return (0 until node.childCount).any { contains(node.getChild(it)) }
        }
        fun shown(): Boolean {
            // 旧系统没有 clearCache；普通无障碍事件仍会使其缓存失效。
            val clearCache = runCatching { android.app.UiAutomation::class.java.getMethod("clearCache") }.getOrNull()
            clearCache?.invoke(automation)
            return automation.windows.any { contains(it.root) } ||
                contains(automation.rootInActiveWindow)
        }
        fun frames() {
            val ready = CountDownLatch(1)
            runner.runOnMainSync {
                activity.window.decorView.postOnAnimation {
                    activity.window.decorView.postOnAnimation { ready.countDown() }
                }
            }
            check(ready.await(15, TimeUnit.SECONDS)) { "弹窗可见性检查没有收到帧" }
        }
        fun main(action: () -> Unit) {
            var failure: Throwable? = null
            runner.runOnMainSync {
                try {
                    action()
                } catch (error: Throwable) {
                    failure = error
                }
            }
            failure?.let { throw AssertionError("弹窗检查失败", it) }
        }
        try {
            for (custom in listOf(false, true)) {
                main {
                    activity.page.custom = custom
                    activity.page.visible = true
                    activity.page.requested = true
                    activity.page.lifecycle(NativePage.STARTED)
                }
                frames()
                check(!shown()) { "候选预热创建了系统弹窗" }
                main {
                    activity.page.visible = false
                    activity.page.lifecycle(NativePage.RESUMED)
                }
                frames()
                check(!shown()) { "后台分页创建了系统弹窗" }
                main {
                    check(!activity.page.canReplace()) { "未结束的确认在后台丢失保护" }
                    activity.page.visible = true
                }
                val until = SystemClock.uptimeMillis() + 15000
                while (!shown()) {
                    check(SystemClock.uptimeMillis() < until) {
                        "前台弹窗没有正常显示：${activity.page.diagnostic()}"
                    }
                    SystemClock.sleep(40)
                }
                main { activity.page.requested = false }
                frames()
                main { check(activity.page.canReplace()) { "关闭弹窗后未释放安全点" } }
                check(!shown()) { "关闭后仍有遗留弹窗" }
            }
        } catch (error: Throwable) {
            val bitmap = automation.takeScreenshot()
            if (bitmap != null)
                try {
                    java.io
                        .FileOutputStream(
                            java.io.File(runner.context.filesDir, "dialog-visibility-failure.png")
                        )
                        .use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                } finally {
                    bitmap.recycle()
                }
            throw error
        } finally {
            main { activity.finish() }
        }
    }
}
