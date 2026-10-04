package app.luoxianlv

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.lifecycle.findViewTreeLifecycleOwner
import app.luoxianlv.hot.contract.AccessibilityBinding
import app.luoxianlv.hot.contract.PlaybackBridge
import app.luoxianlv.library.Song
import app.luoxianlv.playback.PlaybackConnection
import app.luoxianlv.practice.*
import app.luoxianlv.recognition.ConfigStore
import app.luoxianlv.settings.ExperimentalOptions
import app.luoxianlv.shared.ImportFilePicker
import app.luoxianlv.ui.practice.PracticeActivity
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 在禁止截图的真实窗口中演奏，并验证关闭开关后恢复截图定位。 */
class ExperimentalInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private val automation
        get() = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun shell(command: String) =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand(command)
            )
            .bufferedReader()
            .use { it.readText().trim() }

    private fun await(label: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 75000
        while (!predicate()) {
            check(SystemClock.uptimeMillis() < end) { label }
            SystemClock.sleep(100)
        }
    }

    private fun keyboard(view: View): PracticeKeyboard? =
        when (view) {
            is PracticeKeyboard -> view
            is ViewGroup ->
                (0 until view.childCount).firstNotNullOfOrNull { keyboard(view.getChildAt(it)) }
            else -> null
        }

    @Suppress("DEPRECATION")
    private fun checkPicker() {
        for ((title, types) in
            listOf(
                "选择壁纸 ZIP" to arrayOf("application/zip"),
                "选择 MIDI 文件" to arrayOf("audio/midi", "audio/x-midi"),
            )) {
            val picker = ImportFilePicker(title)
            val chooser = picker.createIntent(targetContext, types)
            check(chooser.action == Intent.ACTION_CHOOSER)
            val content = checkNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            check(content.action == Intent.ACTION_GET_CONTENT && content.type == "*/*")
            check(content.hasCategory(Intent.CATEGORY_OPENABLE))
            check(content.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.contentEquals(types))
            val uri = Uri.parse("content://test-files/example")
            check(picker.parseResult(Activity.RESULT_OK, Intent().setData(uri)) == uri)
            check(
                picker.parseResult(
                    Activity.RESULT_OK,
                    Intent().apply { clipData = ClipData.newRawUri("文件", uri) },
                ) == uri
            )
            check(picker.parseResult(Activity.RESULT_CANCELED, Intent().setData(uri)) == null)
        }
    }

    override fun onStart() {
        val result = Bundle()
        var ok = false
        var stage: PracticeActivity? = null
        var previousSong: Song? = null
        var service: PlaybackConnection? = null
        val priorEnabled = shell("settings get secure accessibility_enabled")
        val priorServices = shell("settings get secure enabled_accessibility_services")
        val previousFixed = ExperimentalOptions.fixedHarmonicaKeys(targetContext)
        val beforeLayout = ConfigStore.load(targetContext)
        try {
            checkPicker()
            val component =
                "${targetContext.packageName}/app.luoxianlv.service.MusicAccessibilityService"
            val enabled =
                priorServices
                    .takeUnless { it == "null" }
                    .orEmpty()
                    .split(':')
                    .filter { it.isNotBlank() }
                    .toMutableSet()
                    .apply { add(component) }
            shell("settings put secure enabled_accessibility_services ${enabled.joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            await("无障碍未连接") { PlaybackConnection.instance != null }
            service = PlaybackConnection.instance!!
            val player = service
            previousSong = player.song
            stage =
                startActivitySync(
                    Intent(targetContext, PracticeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                    as PracticeActivity
            val active = stage
            await("演练场未就绪") { PracticePlaybackGate.ready && active.hasWindowFocus() }
            lateinit var keys: PracticeKeyboard
            runOnMainSync {
                keys = checkNotNull(keyboard(active.window.decorView))
                // 坐标回归不测视频解码压力，避免模拟器错过节拍后误报命中失败。
                fun pauseBackdrop(view: View) {
                    if (view is app.luoxianlv.wallpaper.PracticeBackdrop) view.suspendRendering()
                    if (view is ViewGroup)
                        repeat(view.childCount) { pauseBackdrop(view.getChildAt(it)) }
                }
                pauseBackdrop(active.window.decorView)
                active.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                // 模拟其他软件：无法借助演练场状态，也无法截图。
                PracticePlaybackGate.leave(checkNotNull(keys.findViewTreeLifecycleOwner()))
            }
            SystemClock.sleep(500)
            val blocked = CountDownLatch(1)
            var screenshotFailed = false
            runOnMainSync {
                (PlaybackBridge.current() as AccessibilityBinding).screenshot(
                    Display.DEFAULT_DISPLAY,
                    object : AccessibilityBinding.ScreenshotCallback {
                        override fun success(screenshot: AccessibilityBinding.Frame) {
                            Thread {
                                screenshotFailed =
                                    app.luoxianlv.recognition.ScreenshotAnalyzer.recognize(
                                        screenshot
                                    ) == null
                                blocked.countDown()
                            }
                                .start()
                        }

                        override fun failure(errorCode: Int) {
                            screenshotFailed = true
                            blocked.countDown()
                        }
                    },
                )
            }
            check(blocked.await(5, TimeUnit.SECONDS) && screenshotFailed) { "受保护窗口的琴键仍能被截图识别" }
            val played = CopyOnWriteArrayList<Int>()
            val song = Song("fixed-layout-test", "固定布局回归", "1 2 #3 [4] (5) 6 7 8", 30, "测试")
            val expected =
                song.events.map { note ->
                    PracticeSession.pitch(
                        note.keyIndex,
                        PracticeSession.Mode.valueOf(note.mode.name),
                        note.halfTone,
                    )
                }
            runOnMainSync {
                keys.onNoteOn = {
                    played.add(it)
                    true
                }
                ExperimentalOptions.setFixedHarmonicaKeys(targetContext, true)
                player.reloadExperimentalOptions()
                player.select(song)
                player.play()
            }
            await("固定模式未衔接播放") { !player.loadingSong && player.playing }
            await("无截图播放未完成：$played") { !player.playing }
            check(player.error == null) { "固定布局播放失败：${player.error}" }
            check(played.toList() == expected) { "固定坐标或音区不匹配：$played / $expected" }
            check(ConfigStore.load(targetContext).noteX.contentEquals(beforeLayout.noteX)) {
                "固定布局覆盖了识别缓存"
            }
            runOnMainSync {
                ExperimentalOptions.setFixedHarmonicaKeys(targetContext, false)
                player.reloadExperimentalOptions()
                player.seek(0)
                player.play()
            }
            await("关闭开关后仍未尝试识别") { !player.preparing }
            check(!player.playing && player.error != null) { "关闭开关后没有恢复截图定位" }
            result.putString(
                "stream",
                "通过：ZIP/MIDI 系统应用选择协议与取消处理；禁止截图窗口中的八键与音区/半音演奏；识别缓存保留；关闭实验开关恢复截图定位。\n",
            )
            ok = true
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
        } finally {
            runOnMainSync {
                service?.pause()
                ExperimentalOptions.setFixedHarmonicaKeys(targetContext, previousFixed)
                service?.reloadExperimentalOptions()
                previousSong?.let { service?.select(it) }
                stage?.finish()
            }
            if (priorServices == "null")
                shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $priorServices")
            if (priorEnabled == "null") shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $priorEnabled")
        }
        finish(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
