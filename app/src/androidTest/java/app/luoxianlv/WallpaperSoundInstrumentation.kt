package app.luoxianlv

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import app.luoxianlv.ui.practice.PracticeActivity
import app.luoxianlv.ui.practice.PracticeKeyboard
import app.luoxianlv.ui.practice.PracticePlaybackGate
import app.luoxianlv.ui.practice.WallpaperPickerActivity
import app.luoxianlv.wallpaper.data.DefaultWallpaper
import app.luoxianlv.wallpaper.data.WallpaperProjectStore
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** 验证默认项目完整落盘及真正的视频音量，避免只测试按钮状态。 */
class WallpaperSoundInstrumentation : Instrumentation() {
    private var checkLoops = false
    private var offlineSource = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        checkLoops = arguments?.getString("checkLoops") == "true"
        offlineSource = arguments?.getString("offlineSource") == "true"
        start()
    }

    private fun awaitState(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 75000
        while (!predicate()) {
            check(SystemClock.uptimeMillis() < deadline) { message }
            SystemClock.sleep(100)
        }
    }

    private fun descendants(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()

    private fun js(web: WebView, expression: String): String {
        val latch = CountDownLatch(1)
        var value = ""
        runOnMainSync {
            web.evaluateJavascript(expression) {
                value = it
                latch.countDown()
            }
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "壁纸脚本无响应" }
        return value
    }

    private fun tap(keyboard: PracticeKeyboard, x: Float, y: Float) = runOnMainSync {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time, action, x, y, 0)
            keyboard.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    override fun onStart() {
        val result = Bundle()
        var success = false
        var stage: PracticeActivity? = null
        var picker: Activity? = null
        val prefs = targetContext.getSharedPreferences("practice_wallpaper", 0)
        val previous = prefs.getString("project", null)
        val previousSound = prefs.getBoolean("sound", false)
        try {
            runOnMainSync {}
            val source =
                File(targetContext.getExternalFilesDir(null), "default-wallpaper-source.zip")
            val root: File
            val archiveRoot: File
            val importedId: String?
            if (offlineSource) {
                check(source.isFile) { "缺少本地原始壁纸 ZIP" }
                WallpaperProjectStore.import(targetContext, android.net.Uri.fromFile(source), false)
                root = checkNotNull(WallpaperProjectStore.root(targetContext))
                archiveRoot = checkNotNull(WallpaperProjectStore.current(targetContext))
                importedId = checkNotNull(WallpaperProjectStore.selectedId(targetContext))
            } else {
                kotlinx.coroutines.runBlocking {
                    DefaultWallpaper.download(targetContext) { _, _ -> }
                }
                root = DefaultWallpaper.folder(targetContext)
                archiveRoot = root
                importedId = null
            }
            check(root.path.startsWith(targetContext.getExternalFilesDir(null)!!.path))
            check(File(archiveRoot, ".root").isFile)
            checkWallpaperRanges(root)
            ZipInputStream(
                    File(targetContext.getExternalFilesDir(null), "default-wallpaper-source.zip")
                        .inputStream()
                )
                .use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory) {
                            val expected = MessageDigest.getInstance("SHA-256")
                            val buffer = ByteArray(65536)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                expected.update(buffer, 0, count)
                            }
                            val actual = MessageDigest.getInstance("SHA-256")
                            File(archiveRoot, entry.name).inputStream().use { input ->
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    actual.update(buffer, 0, count)
                                }
                            }
                            check(expected.digest().contentEquals(actual.digest())) {
                                "默认项目文件不完整：${entry.name}"
                            }
                        }
                        zip.closeEntry()
                    }
                }
            if (!offlineSource) {
                val stamp = File(root, "project.json").lastModified()
                prefs.edit().putString("project", "已有用户选择").commit()
                kotlinx.coroutines.runBlocking {
                    DefaultWallpaper.download(targetContext) { _, _ -> }
                }
                check(prefs.getString("project", null) == "已有用户选择")
                check(File(root, "project.json").lastModified() == stamp)
                check(
                    root.parentFile!!.listFiles().orEmpty().none { it.name.startsWith(".install-") }
                )
            }
            prefs
                .edit()
                .apply {
                    if (importedId == null) remove("project") else putString("project", importedId)
                    remove("sound")
                }
                .commit()
            check(!WallpaperProjectStore.soundEnabled(targetContext))
            check(WallpaperProjectStore.entries(targetContext).count { it.root == root } == 1)
            stage =
                startActivitySync(
                    Intent(targetContext, PracticeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                    as PracticeActivity
            val active = stage
            awaitState("演练场未就绪") { PracticePlaybackGate.ready }
            checkIndependentAudio()
            lateinit var keyboard: PracticeKeyboard
            lateinit var web: WebView
            runOnMainSync {
                val views = descendants(active.window.decorView)
                keyboard = views.filterIsInstance<PracticeKeyboard>().single()
                web = views.filterIsInstance<WebView>().single()
            }
            val audible =
                "Array.from(document.querySelectorAll('video,audio')).some(v=>!v.muted&&v.volume>0&&!v.paused)"
            check(js(web, audible) == "false") { "默认壁纸意外出声" }
            check(
                js(
                    web,
                    "window.soundTestVideo=document.querySelector('video'); !!window.soundTestVideo",
                ) == "true"
            ) {
                "可切换声音的视频没有保留音轨通道"
            }
            fun awaitLoop(label: String) {
                if (!checkLoops) return
                val deadline = SystemClock.uptimeMillis() + 150000
                var previous = -1.0
                var progressedAt = SystemClock.uptimeMillis()
                var reportedAt = progressedAt
                while (SystemClock.uptimeMillis() < deadline) {
                    val snapshot =
                        org.json.JSONObject(
                            js(
                                web,
                                """
                                (()=>{const v=document.querySelector('video'); return {
                                  state:window.wallpaperState, time:v?.currentTime??-1,
                                  sources:document.querySelectorAll('video[src]').length,
                                  error:v?.error?.code??0, paused:v?.paused??true
                                }})()
                                """
                                    .trimIndent(),
                            )
                        )
                    check(snapshot.getString("state") == "ready" && snapshot.getInt("error") == 0) {
                        "$label：视频播放失败 $snapshot"
                    }
                    check(snapshot.getInt("sources") == 1) { "$label：循环启动了第二路视频 $snapshot" }
                    val position = snapshot.getDouble("time")
                    if (SystemClock.uptimeMillis() - reportedAt >= 10000) {
                        android.util.Log.i("壁纸回归", "$label：当前进度 $position")
                        reportedAt = SystemClock.uptimeMillis()
                    }
                    if (previous > 0 && position < previous - .5) {
                        android.util.Log.i("壁纸回归", "$label：完整循环通过，$previous → $position")
                        return
                    }
                    if (position != previous) progressedAt = SystemClock.uptimeMillis()
                    check(SystemClock.uptimeMillis() - progressedAt < 10000) {
                        "$label：视频停滞 $snapshot"
                    }
                    previous = position
                    SystemClock.sleep(500)
                }
                error("$label：未完成视频循环，最后进度 $previous")
            }
            awaitLoop("默认静音")
            val density = targetContext.resources.displayMetrics.density
            fun toggle() =
                tap(keyboard, keyboard.safeLeft + 159 * density, keyboard.safeTop + 39 * density)
            toggle()
            awaitState("壁纸声音没有开启") { js(web, audible) == "true" }
            check(WallpaperProjectStore.soundEnabled(targetContext))
            runOnMainSync { check(keyboard.onNoteOn(60)) { "开启壁纸声音后口琴无法发声" } }
            SystemClock.sleep(300)
            runOnMainSync { keyboard.onNoteOff() }
            check(js(web, audible) == "true") { "口琴演奏中断了壁纸播放" }
            awaitLoop("开启声音并演奏后")
            toggle()
            awaitState("壁纸声音没有关闭") { js(web, audible) == "false" }
            check(!WallpaperProjectStore.soundEnabled(targetContext))
            check(js(web, "window.soundTestVideo.isConnected") == "true") {
                "声音切换重建了视频播放器"
            }
            // 已开启声音的缓存/后台不能继续出声，恢复后按偏好重新开启。
            toggle()
            runOnMainSync {
                active.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            }
            awaitState("后台仍有壁纸声音") { !PracticePlaybackGate.ready && js(web, audible) == "false" }
            targetContext.startActivity(
                Intent(targetContext, PracticeActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
            )
            awaitState("后台返回没有恢复声音") { PracticePlaybackGate.ready && js(web, audible) == "true" }
            awaitLoop("后台恢复后")
            val monitor = addMonitor(WallpaperPickerActivity::class.java.name, null, false)
            tap(keyboard, keyboard.safeLeft + 99 * density, keyboard.safeTop + 39 * density)
            picker = waitForMonitorWithTimeout(monitor, 10000) ?: error("未进入壁纸设置")
            removeMonitor(monitor)
            check(WallpaperProjectStore.soundEnabled(targetContext))
            fun soundNode(
                node: android.view.accessibility.AccessibilityNodeInfo?
            ): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.text?.toString()?.contains("壁纸声音") == true) return node
                return (0 until node.childCount).firstNotNullOfOrNull {
                    soundNode(node.getChild(it))
                }
            }
            awaitState("设置页缺少声音入口") {
                soundNode(uiAutomation.rootInActiveWindow) != null
            }
            val node = checkNotNull(soundNode(uiAutomation.rootInActiveWindow))
            var clickable = node
            while (!clickable.isClickable && clickable.parent != null) clickable = clickable.parent
            check(
                clickable.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                )
            )
            awaitState("设置声音状态未同步") { !WallpaperProjectStore.soundEnabled(targetContext) }
            result.putString(
                "stream",
                (if (offlineSource) "本地原始 ZIP 导入与逐文件校验（不验证网络下载）" else "默认 ZIP 逐文件校验、外部安装去重、保留选择") +
                    "、默认静音、真实视频音量切换、后台停音恢复及设置同步通过。\n",
            )
            success = true
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
        } finally {
            runOnMainSync {
                picker?.finish()
                stage?.finish()
            }
            prefs
                .edit()
                .apply {
                    if (previous == null) remove("project") else putString("project", previous)
                    putBoolean("sound", previousSound)
                }
                .commit()
        }
        finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
