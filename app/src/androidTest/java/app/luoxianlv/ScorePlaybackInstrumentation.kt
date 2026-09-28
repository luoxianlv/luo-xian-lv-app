package app.luoxianlv

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import app.luoxianlv.core.score.ScoreWork
import app.luoxianlv.data.ExperimentalOptions
import app.luoxianlv.data.Kv
import app.luoxianlv.data.Song
import app.luoxianlv.service.MusicAccessibilityService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import org.json.JSONArray
import org.json.JSONObject

/** 使用休止谱验证真实服务的异步起播，不向其他应用发送按键。未裁剪测试包运行。 */
class ScorePlaybackInstrumentation : Instrumentation() {
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

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (true) {
            var ready = false
            runOnMainSync { ready = predicate() }
            if (ready) return
            check(SystemClock.uptimeMillis() < deadline) { message }
            SystemClock.sleep(30)
        }
    }

    private fun blockPlayback(): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        ScoreWork.playback.dispatch(EmptyCoroutineContext) {
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "测试未释放播放队列" }
        }
        check(entered.await(10, TimeUnit.SECONDS))
        return release
    }

    override fun onStart() {
        val result = Bundle()
        val library = Kv.of(targetContext, "song_library")
        val previousSongs = library.getString("songs", "[]")
        val previousSelected = library.getString("selected", null)
        val previousFloating = library.getBoolean("floating", true)
        val previousFixed = ExperimentalOptions.fixedHarmonicaKeys(targetContext)
        val priorServices = shell("settings get secure enabled_accessibility_services")
        val priorEnabled = shell("settings get secure accessibility_enabled")
        var blocked: CountDownLatch? = null
        var passed = false
        try {
            val performance = ScorePerformanceChecks.run()
            val fixture = Song("score-startup-check", "起播回归", "0:8", 120, "测试")
            val songs =
                JSONArray(previousSongs)
                    .put(
                        JSONObject()
                            .put("id", fixture.id)
                            .put("title", fixture.title)
                            .put("score", fixture.score)
                            .put("bpm", 120)
                    )
            library
                .edit()
                .putString("songs", songs.toString())
                .putString("selected", fixture.id)
                .putBoolean("floating", false)
                .commit()
            ExperimentalOptions.setFixedHarmonicaKeys(targetContext, true)
            startActivitySync(
                Intent(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            blocked = blockPlayback()
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
            await("无障碍服务未连接") { MusicAccessibilityService.instance != null }
            val player = checkNotNull(MusicAccessibilityService.instance)
            runOnMainSync {
                player.reloadExperimentalOptions()
                if (!player.loadingSong) player.select(fixture)
                player.play()
                check(player.loadingSong && player.waitingToPlay && !player.playing)
            }
            blocked.countDown()
            blocked = null
            await("启动期间的播放请求没有自动衔接") { player.playing && !player.loadingSong }
            runOnMainSync { player.pause() }

            blocked = blockPlayback()
            runOnMainSync {
                player.select(fixture.copy(id = "cancel-load"))
                player.play()
                player.toggle()
                check(!player.waitingToPlay)
            }
            blocked.countDown()
            blocked = null
            await("取消后解析未结束") { !player.loadingSong }
            runOnMainSync { check(!player.playing && !player.waitingToPlay) }

            blocked = blockPlayback()
            runOnMainSync {
                player.select(fixture.copy(id = "stale-load"))
                player.play()
                player.select(fixture.copy(id = "latest-load"))
                player.play()
            }
            blocked.countDown()
            blocked = null
            await("换曲后的播放请求丢失") { !player.loadingSong && player.playing }
            runOnMainSync {
                check(player.song.id == "latest-load" && player.error == null)
                player.pause()
            }
            result.putString("stream", "$performance\n通过：启动即播放、准备中取消、快速换曲仅播放最新曲目。\n")
            passed = true
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
        } finally {
            blocked?.countDown()
            runOnMainSync { MusicAccessibilityService.instance?.pause() }
            library
                .edit()
                .putString("songs", previousSongs)
                .putString("selected", previousSelected)
                .putBoolean("floating", previousFloating)
                .commit()
            ExperimentalOptions.setFixedHarmonicaKeys(targetContext, previousFixed)
            if (priorServices == "null")
                shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $priorServices")
            if (priorEnabled == "null") shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $priorEnabled")
        }
        finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
