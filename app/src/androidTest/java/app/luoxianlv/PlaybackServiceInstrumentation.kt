package app.luoxianlv

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import app.luoxianlv.data.SongRepository
import app.luoxianlv.service.MusicAccessibilityService
import app.luoxianlv.service.PlaybackForegroundService

/** 在真实系统服务调度下覆盖重复恢复、快速开关、原实例重启与通知停止。 */
class PlaybackServiceInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    @Suppress("DEPRECATION")
    private fun service() =
        targetContext
            .getSystemService(ActivityManager::class.java)
            .getRunningServices(100)
            .firstOrNull {
                it.service.className == PlaybackForegroundService::class.java.name
            }

    private fun awaitState(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!predicate()) {
            check(SystemClock.uptimeMillis() < deadline) { "等待超时：$label" }
            SystemClock.sleep(50)
        }
    }

    override fun onStart() {
        val result = Bundle()
        var success = false
        var activity: Activity? = null
        var bound = false
        val repository = SongRepository(targetContext)
        val previous = repository.floatingEnabled
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = Unit

                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
        try {
            activity =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            awaitState("无障碍服务连接") { MusicAccessibilityService.instance != null }
            runOnMainSync { MusicAccessibilityService.instance!!.showFloating(false) }
            awaitState("初始服务停止") { service() == null }

            // 同一条主线程消息内开关，确保关闭发生在系统分发启动命令之前。
            repeat(20) {
                runOnMainSync {
                    MusicAccessibilityService.instance!!.showFloating(true)
                    MusicAccessibilityService.instance!!.showFloating(false)
                }
                awaitState("取消尚未完成的启动") { service() == null }
            }
            runOnMainSync {
                repeat(50) { MusicAccessibilityService.instance!!.showFloating(true) }
            }
            awaitState("重复启动后保持前台") { service()?.foreground == true }
            val firstStart = service()!!.activeSince
            runOnMainSync {
                repeat(50) { MusicAccessibilityService.instance!!.showFloating(true) }
            }
            check(service()?.activeSince == firstStart)

            // 绑定令已停止的服务实例暂不销毁，稳定复现旧版漏掉 onCreate 登记的路径。
            bound =
                targetContext.bindService(
                    Intent(targetContext, PlaybackForegroundService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            check(bound)
            runOnMainSync { MusicAccessibilityService.instance!!.showFloating(false) }
            awaitState("绑定实例退出前台") { service()?.let { !it.foreground && !it.started } == true }
            runOnMainSync { MusicAccessibilityService.instance!!.showFloating(true) }
            awaitState("同一实例重新登记前台") { service()?.foreground == true }
            check(service()?.activeSince == firstStart)

            val notification =
                targetContext
                    .getSystemService(NotificationManager::class.java)
                    .activeNotifications
                    .single { it.notification.channelId == PlaybackForegroundService.CHANNEL }
            notification.notification.actions.single().actionIntent.send()
            awaitState("通知停止") { !repository.floatingEnabled && service()?.foreground == false }
            runOnMainSync {
                MusicAccessibilityService.instance!!.showFloating(true)
                MusicAccessibilityService.instance!!.showFloating(false)
                MusicAccessibilityService.instance!!.showFloating(true)
            }
            awaitState("开关交错后的最终开启") { service()?.foreground == true }
            SystemClock.sleep(12000) // 超过前台登记超时窗口，确认不会延迟崩溃。
            check(service()?.foreground == true)
            result.putString("stream", "通过：20 次启动即取消、100 次重复恢复、绑定实例停止再启动、通知停止、交错开关及延迟崩溃观察。\n")
            success = true
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
        } finally {
            runOnMainSync { MusicAccessibilityService.instance?.showFloating(previous) }
            if (bound) targetContext.unbindService(connection)
            activity?.let { runOnMainSync { it.finish() } }
        }
        finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
