package app.luoxianlv

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import app.luoxianlv.ui.practice.PracticeActivity
import app.luoxianlv.ui.practice.PracticePlaybackGate
import app.luoxianlv.wallpaper.data.DefaultWallpaper
import app.luoxianlv.wallpaper.render.PreparedWallpaper
import java.io.File

/** 真机验证首次提示、稍后再问、永久跳过和真实 OSS 下载，不依赖随包壁纸。 */
class WallpaperDownloadInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var home: Activity? = null
        var stage: Activity? = null
        var picker: Activity? = null
        val prefs = targetContext.getSharedPreferences("practice_wallpaper", 0)
        val previousProject = prefs.getString("project", null)
        val previousSkip = prefs.getBoolean("skip_default_download", false)
        val root = DefaultWallpaper.folder(targetContext)
        val backup = File(root.parentFile, ".download-check-backup")
        var saved = false
        var monitor = addMonitor(PracticeActivity::class.java.name, null, false)
        fun awaitHome() {
            await("首页返回动画未结束") {
                var ready = false
                runOnMainSync {
                    ready =
                        home?.hasWindowFocus() == true &&
                            home
                                ?.window
                                ?.decorView
                                ?.findViewWithTag<android.view.View>("practice-entry-curtain") ==
                                null
                }
                ready
            }
            // waitForMonitorWithTimeout 会自动移除监视器，下次进入必须重新注册。
            removeMonitor(monitor)
            monitor = addMonitor(PracticeActivity::class.java.name, null, false)
        }
        try {
            check(!backup.exists()) { "上次测试备份尚未恢复" }
            runOnMainSync { PreparedWallpaper.clear() }
            if (root.exists()) {
                check(root.renameTo(backup))
                saved = true
            }
            prefs.edit().remove("project").remove("skip_default_download").commit()
            check(targetContext.assets.list("")?.contains("default-wallpaper.zip") != true)
            home =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            tap("演练场")
            await("未显示三项下载选择") {
                find("下载") != null && find("稍后再问") != null && find("不再提示") != null
            }
            check(
                home.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_PORTRAIT
            )
            tap("稍后再问")
            stage = waitForMonitorWithTimeout(monitor, 15000)
            check(stage != null)
            await("跳过下载不能进入演练场") { PracticePlaybackGate.ready }
            check(DefaultWallpaper.shouldOffer(targetContext))
            runOnMainSync { stage!!.finish() }
            stage = null
            awaitHome()
            tap("演练场")
            tap("不再提示")
            stage = waitForMonitorWithTimeout(monitor, 15000)
            check(stage != null)
            await("不再提示后不能进入") { PracticePlaybackGate.ready }
            check(!DefaultWallpaper.shouldOffer(targetContext))
            runOnMainSync { stage!!.finish() }
            stage = null
            awaitHome()
            // 再进一次确认偏好已生效，而不只是弹窗临时关闭。
            tap("演练场")
            stage = waitForMonitorWithTimeout(monitor, 15000)
            check(stage != null && find("下载演练场壁纸？") == null)
            await("再次进入未就绪") { PracticePlaybackGate.ready }
            runOnMainSync { stage!!.finish() }
            stage = null
            awaitHome()
            picker =
                startActivitySync(
                    Intent(
                            targetContext,
                            app.luoxianlv.ui.practice.WallpaperPickerActivity::class.java,
                        )
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            // 永久跳过后，设置里的手动入口仍能下载。
            tap("下载默认动态壁纸")
            tap("下载")
            await("OSS 下载未完成", 120000) { DefaultWallpaper.installed(targetContext) }
            await("安装后下载按钮未隐藏") { find("下载默认动态壁纸") == null }
            runOnMainSync { picker!!.finish() }
            picker = null
            awaitHome()
            tap("演练场")
            stage = waitForMonitorWithTimeout(monitor, 30000)
            check(stage != null)
            await("下载后舞台未就绪", 60000) { PracticePlaybackGate.ready }
            check(
                !File(
                        app.luoxianlv.storage.AppStorage.imports(targetContext),
                        "default-wallpaper.download",
                    )
                    .exists()
            )
            check(!DefaultWallpaper.shouldOffer(targetContext))
            val stamp = File(root, "project.json").lastModified()
            kotlinx.coroutines.runBlocking {
                DefaultWallpaper.download(targetContext) { _, _ -> error("已安装壁纸不应重复下载") }
            }
            check(File(root, "project.json").lastModified() == stamp)
            runOnMainSync { stage?.finish() }
            stage = null
            awaitHome()
            checkSnackbarTiming(home as MainActivity)
            result.putString(
                "stream",
                "PASS：APK 无大壁纸、竖屏三项提示、两种跳过、永久跳过后手动下载、OSS 校验安装、进入演练场、不重复下载；提示自动到期、后台返回、切页消费及新提示替换。",
            )
        } catch (error: Throwable) {
            result.putString("stream", "FAIL：${error.stackTraceToString()}")
        } finally {
            runOnMainSync {
                stage?.finish()
                picker?.finish()
                home?.finish()
                PreparedWallpaper.clear()
            }
            removeMonitor(monitor)
            if (saved) {
                if (root.exists()) check(root.deleteRecursively())
                check(backup.renameTo(root))
            }
            prefs
                .edit()
                .putString("project", previousProject)
                .putBoolean("skip_default_download", previousSkip)
                .commit()
        }
        finish(Activity.RESULT_OK, result)
    }

    private fun await(message: String, timeout: Long = 30000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(100)
        }
        error(message)
    }

    private fun find(
        label: String,
        node: AccessibilityNodeInfo? = uiAutomation.rootInActiveWindow,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && node.text?.toString() == label) return node
        return (0 until node.childCount).firstNotNullOfOrNull { find(label, node.getChild(it)) }
    }

    private fun tap(label: String) {
        var node: AccessibilityNodeInfo? = null
        await("找不到按钮：$label") {
            node = find(label)
            node != null
        }
        while (node != null && !node!!.isClickable) node = node!!.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "无法点击：$label" }
    }
}
