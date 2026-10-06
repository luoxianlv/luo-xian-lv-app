package app.luoxianlv.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.luoxianlv.diagnostics.AppLog
import app.luoxianlv.hot.contract.SharedInput
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 每个播放业务独占自己的输入租约，退出后仍等待宿主确认物理触屏已归还。 */
internal class PlaybackInput : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val pending = AtomicInteger()
    private var bridge: SharedInput.Bridge? = null
    private var lease: SharedInput.Session? = null
    private var observer: AutoCloseable? = null
    private val retiring = mutableListOf<SharedInput.Session>()
    private var mode = SharedInput.ACCESSIBILITY
    private var changed: (() -> Unit)? = null
    private var closed = false
    private var waiting: Runnable? = null
    private var activationStartedAt = 0L
    private var activationDelay = 0L
    private var activationSubmittedAt = 0L

    /** 首次接管等待不计入曲目时间；完成后交给播放时钟一次性补偿。 */
    val activationDelayMs: Long
        get() =
            if (pending.get() == 0) 0
            else if (activationStartedAt > 0) SystemClock.uptimeMillis() - activationStartedAt
            else activationDelay

    fun initialize(changed: () -> Unit) {
        this.changed = changed
        refreshBridge()
    }

    val merged: Boolean
        get() {
            refreshBridge()
            return bridge != null && mode != SharedInput.ACCESSIBILITY
        }

    val idle: Boolean
        get() {
            retiring.removeAll { it.idle() }
            return pending.get() == 0 && (lease?.idle() != false) && retiring.isEmpty()
        }

    fun press(
        x: Float,
        y: Float,
        durationMs: Int,
        width: Int,
        height: Int,
        rotation: Int,
        done: (Boolean, String, Long) -> Unit,
    ): Boolean {
        if (!merged) return false
        if (closed) {
            done(false, "播放会话已关闭", 0)
            return true
        }
        val owner =
            try {
                lease ?: checkNotNull(bridge).openSession().also { lease = it }
            } catch (failure: Exception) {
                reportFailure("创建会话", failure)
                done(false, "无法建立触控连接，请重新连接", 0)
                return true
            } catch (failure: LinkageError) {
                reportFailure("创建会话接口", failure)
                done(false, "触控组件版本不匹配，请重启应用", 0)
                return true
            }
        pending.incrementAndGet()
        val submittedAt = SystemClock.uptimeMillis()
        activationSubmittedAt = submittedAt
        activationStartedAt = 0
        activationDelay = 0
        val completed = AtomicBoolean()
        fun finish(success: Boolean, message: String) {
            onMain {
                if (!completed.compareAndSet(false, true)) return@onMain
                val observedDelay = activationDelayMs
                val delay = runCatching {
                    val state = bridge?.state()
                    if (
                        state != null &&
                            state.getLong("activationRequestAt", 0) >= submittedAt &&
                            !state.getBoolean("waitingForFingers")
                    )
                        state.getLong("activationWaitMs", 0).coerceAtLeast(0)
                    else observedDelay
                }
                    .getOrDefault(observedDelay)
                pending.decrementAndGet()
                activationStartedAt = 0
                activationDelay = 0
                activationSubmittedAt = 0
                if (!closed) done(success, message, delay)
            }
        }
        try {
            owner.press(floatArrayOf(x, y), durationMs, width, height, rotation) { success, message
                ->
                finish(success, message)
            }
        } catch (failure: Exception) {
            reportFailure("提交按键", failure)
            finish(false, "触控连接异常，请重新连接")
        } catch (failure: LinkageError) {
            reportFailure("提交按键接口", failure)
            finish(false, "触控组件版本不匹配，请重启应用")
        }
        return true
    }

    fun release() {
        cancelWaiting()
        lease?.release()
    }

    /** 变速或拖动后马上续播时，等上次物理归还完成，不让新按键抢过释放任务。 */
    fun waitUntilIdle(done: (Boolean) -> Unit) {
        cancelWaiting()
        val deadline = SystemClock.uptimeMillis() + 6500
        val poll =
            object : Runnable {
                override fun run() {
                    if (closed || waiting !== this) return
                    val available = idle
                    if (available || SystemClock.uptimeMillis() >= deadline) {
                        waiting = null
                        done(available)
                    } else main.postDelayed(this, 30)
                }
            }
        waiting = poll
        main.post(poll)
    }

    private fun cancelWaiting() {
        waiting?.let(main::removeCallbacks)
        waiting = null
    }

    private fun refreshBridge() {
        if (closed) return
        val selected = SharedInput.current()
        if (selected === bridge) return
        observer?.close()
        lease?.let {
            it.close()
            retiring += it
        }
        lease = null
        bridge = selected
        mode =
            selected?.state()?.getString("mode", SharedInput.ACCESSIBILITY)
                ?: SharedInput.ACCESSIBILITY
        observer = selected?.observe {
            onMain {
                if (closed || selected !== bridge) return@onMain
                val state = selected.state()
                val next = state.getString("mode", SharedInput.ACCESSIBILITY)
                if (
                    pending.get() > 0 &&
                        state.getLong("activationRequestAt", 0) >= activationSubmittedAt
                ) {
                    activationDelay = state.getLong("activationWaitMs", 0).coerceAtLeast(0)
                    if (state.getBoolean("waitingForFingers")) {
                        if (activationStartedAt == 0L)
                            activationStartedAt = SystemClock.uptimeMillis() - activationDelay
                    } else activationStartedAt = 0
                }
                val modeChanged = next != mode
                mode = next
                if (modeChanged) {
                    release()
                    changed?.invoke()
                }
            }
        }
    }

    private inline fun onMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private fun reportFailure(stage: String, failure: Throwable) {
        AppLog.w("触控连接", "阶段=$stage 错误类型=${failure.javaClass.simpleName}")
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelWaiting()
        observer?.close()
        observer = null
        changed = null
        lease?.let {
            it.close()
            retiring += it
        }
        lease = null
    }
}
