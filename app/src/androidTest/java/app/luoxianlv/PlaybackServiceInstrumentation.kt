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
import app.luoxianlv.hot.contract.ForegroundPolicy
import app.luoxianlv.library.SongRepository
import app.luoxianlv.playback.PlaybackConnection
import app.luoxianlv.service.PlaybackForegroundService
import java.util.concurrent.atomic.AtomicBoolean

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

    private fun awaitState(label: String, timeoutMs: Long = 10000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
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
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                check(
                    targetContext.checkSelfPermission(
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    "通知停止回归需要测试设备已授予通知权限"
                }
            }
            activity =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            awaitState("无障碍服务连接") { PlaybackConnection.instance != null }
            checkPlaybackBoundary()
            runOnMainSync { PlaybackConnection.instance!!.showFloating(false) }
            awaitState("初始服务停止") { service() == null }

            // 同一条主线程消息内开关，确保关闭发生在系统分发启动命令之前。
            repeat(20) {
                runOnMainSync {
                    PlaybackConnection.instance!!.showFloating(true)
                    PlaybackConnection.instance!!.showFloating(false)
                }
                awaitState("取消尚未完成的启动") { service() == null }
            }
            runOnMainSync {
                repeat(50) { PlaybackConnection.instance!!.showFloating(true) }
            }
            awaitState("重复启动后保持前台") { service()?.foreground == true }
            val firstStart = service()!!.activeSince
            runOnMainSync {
                repeat(50) { PlaybackConnection.instance!!.showFloating(true) }
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
            runOnMainSync { PlaybackConnection.instance!!.showFloating(false) }
            awaitState("绑定实例退出前台") { service()?.let { !it.foreground && !it.started } == true }
            runOnMainSync { PlaybackConnection.instance!!.showFloating(true) }
            awaitState("同一实例重新登记前台") { service()?.foreground == true }
            check(service()?.activeSince == firstStart)

            // 系统允许延迟展示前台通知；服务登记成功不等于通知已出现在列表中。
            awaitState("系统发布前台通知", timeoutMs = 20000) {
                targetContext
                    .getSystemService(NotificationManager::class.java)
                    .activeNotifications
                    .any {
                        it.notification.channelId == PlaybackForegroundService.CHANNEL
                    }
            }
            val notification =
                targetContext
                    .getSystemService(NotificationManager::class.java)
                    .activeNotifications
                    .single { it.notification.channelId == PlaybackForegroundService.CHANNEL }
            notification.notification.actions.single().actionIntent.send()
            awaitState("通知停止") { !repository.floatingEnabled && service()?.foreground == false }
            runOnMainSync {
                PlaybackConnection.instance!!.showFloating(true)
                PlaybackConnection.instance!!.showFloating(false)
                PlaybackConnection.instance!!.showFloating(true)
            }
            awaitState("开关交错后的最终开启") { service()?.foreground == true }
            SystemClock.sleep(12000) // 超过前台登记超时窗口，确认不会延迟崩溃。
            check(service()?.foreground == true)
            val instanceField =
                PlaybackForegroundService::class.java.getDeclaredField("instance").apply {
                    isAccessible = true
                }
            val policyField =
                PlaybackForegroundService::class.java.getDeclaredField("policy").apply {
                    isAccessible = true
                }
            val host = instanceField.get(null)
            for (failure in
                listOf(
                    NoClassDefFoundError("测试业务加载失败"),
                    java.io.IOException("测试业务异常"),
                    AssertionError("测试业务断言"),
                )) {
                runOnMainSync { PlaybackConnection.instance!!.showFloating(false) }
                awaitState("故障注入前退出前台") { service()?.foreground == false }
                val registeredBeforeFailure = AtomicBoolean()
                runOnMainSync {
                    policyField.set(
                        host,
                        object : ForegroundPolicy {
                            override fun shouldRun(): Boolean {
                                registeredBeforeFailure.set(service()?.foreground == true)
                                throw failure
                            }

                            override fun stopPlayback() = Unit
                        },
                    )
                    PlaybackConnection.instance!!.showFloating(true)
                }
                awaitState("业务失败后安全停止：${failure.javaClass.simpleName}") {
                    registeredBeforeFailure.get() && service()?.foreground == false
                }
                runOnMainSync {
                    policyField.set(host, null)
                    PlaybackConnection.instance!!.showFloating(true)
                }
                awaitState("恢复原策略后重新进入前台") { service()?.foreground == true }
            }
            result.putString(
                "stream",
                "通过：基础值与状态隔离、旧连接/迟到手势和截图丢弃、20 次启动即取消、100 次重复恢复、绑定实例停止再启动、通知停止、交错开关、延迟崩溃观察及三类业务异常前已登记前台/失败后恢复。\n",
            )
            success = true
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
        } finally {
            runOnMainSync { PlaybackConnection.instance?.showFloating(previous) }
            if (bound) targetContext.unbindService(connection)
            activity?.let { runOnMainSync { it.finish() } }
        }
        finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
