package app.luoxianlv

import android.accessibilityservice.GestureDescription
import android.app.Instrumentation
import android.graphics.Path
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import app.luoxianlv.business.playback.PlaybackSession
import app.luoxianlv.hot.NativeAccessibilityService
import app.luoxianlv.hot.contract.AccessibilityBinding
import app.luoxianlv.hot.contract.PlaybackBridge
import app.luoxianlv.hot.contract.PlaybackValues
import app.luoxianlv.hot.contract.PracticeBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 测试内部浮窗状态，生产页面只能使用基础值协议。 */
internal fun currentPlaybackSession(): PlaybackSession {
    val port = checkNotNull(PlaybackBridge.current())
    return port.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(port)
        as PlaybackSession
}

/** 强制一次系统会话重连，检查旧控制句柄和迟到平台回调不会落到新会话。 */
internal fun Instrumentation.checkPlaybackBoundary() {
    val old = checkNotNull(PlaybackBridge.current())
    val outer =
        old.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.get(old)
            as NativeAccessibilityService
    val reconnect =
        NativeAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply {
            isAccessible = true
        }
    val callbacks = AtomicInteger()
    val idleDeadline = SystemClock.uptimeMillis() + 15000
    while (true) {
        var idle = false
        runOnMainSync { idle = outer.playbackCanReplace() }
        if (idle) break
        check(SystemClock.uptimeMillis() < idleDeadline) { "初始播放会话未进入空闲安全点" }
        SystemClock.sleep(40)
    }
    runOnMainSync {
        check(outer.playbackCanReplace())
        val snapshot = old.query("state")
        val realId = snapshot.getString("songId")
        snapshot.putString("songId", "不应写回服务")
        check(old.query("state").getString("songId") == realId)
        check(
            runCatching {
                old.command(
                    "showFloating",
                    Bundle().apply { putParcelable("foreign", android.content.Intent()) },
                )
            }
                .isFailure
        )
        check(
            runCatching { old.command("setSpeed", Bundle().apply { putFloat("speed", Float.NaN) }) }
                .isFailure
        )
        val original =
            Bundle().apply { putBundle("nested", Bundle().apply { putString("value", "原值") }) }
        PlaybackValues.copy(original).getBundle("nested")!!.putString("value", "已修改")
        check(original.getBundle("nested")!!.getString("value") == "原值")

        val firstOwner = Any()
        val secondOwner = Any()
        PracticeBridge.enter(firstOwner) { PracticeBridge.Pitch("LOWER", false) }
        PracticeBridge.enter(secondOwner) { PracticeBridge.Pitch("RAISE", true) }
        PracticeBridge.setReady(secondOwner, true)
        PracticeBridge.leave(firstOwner)
        PracticeBridge.setReady(firstOwner, false)
        check(
            PracticeBridge.ready() &&
                PracticeBridge.pitch()!!.mode == "RAISE" &&
                PracticeBridge.pitch()!!.half
        )
        PracticeBridge.leave(secondOwner)

        val access = old as AccessibilityBinding
        val gesture =
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(Path().apply { moveTo(5f, 5f) }, 0, 120)
                )
                .build()
        check(access.gesture(gesture) { callbacks.incrementAndGet() })
        check(!outer.playbackCanReplace()) { "尚未结束的系统手势被当成安全替换点" }
        access.screenshot(
            Display.DEFAULT_DISPLAY,
            object : AccessibilityBinding.ScreenshotCallback {
                override fun success(frame: AccessibilityBinding.Frame) {
                    frame.close()
                    callbacks.incrementAndGet()
                }

                override fun failure(code: Int) {
                    callbacks.incrementAndGet()
                }
            },
        )
        // 主线程暂被本检查占用；旧命令排队后先重连，再交还主循环。
        val queued = CountDownLatch(1)
        Thread {
            old.command("showFloating", Bundle().apply { putBoolean("enabled", false) })
            queued.countDown()
        }
            .start()
        check(queued.await(2, TimeUnit.SECONDS))
        reconnect.invoke(outer)
        check(PlaybackBridge.current() !== old)
        check(
            old.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(old) == null
        ) {
            "旧控制句柄仍持有已退役业务"
        }
        old.command("showFloating", Bundle().apply { putBoolean("enabled", true) })
        check(old.query("state").isEmpty)
        check(!access.gesture(gesture) { error("旧连接不应再执行手势") })
        PlaybackBridge.current()!!.command(
            "showFloating",
            Bundle().apply { putBoolean("enabled", true) },
        )
    }
    val deadline = SystemClock.uptimeMillis() + 5000
    val captures = old.javaClass.getDeclaredField("captures").apply { isAccessible = true }
    val gestures = old.javaClass.getDeclaredField("gestures").apply { isAccessible = true }
    while (true) {
        var completed = false
        runOnMainSync { completed = captures.getInt(old) == 0 && gestures.getInt(old) == 0 }
        if (completed) break
        check(SystemClock.uptimeMillis() < deadline) { "旧会话平台请求未完成" }
        SystemClock.sleep(40)
    }
    runOnMainSync {
        check(callbacks.get() == 0) { "迟到的旧回调仍进入业务" }
        check(PlaybackBridge.current()!!.query("state").getBoolean("floatingVisible")) {
            "旧命令隐藏了新会话浮窗"
        }
        PlaybackBridge.current()!!.command(
            "showFloating",
            Bundle().apply { putBoolean("enabled", false) },
        )
    }
}
