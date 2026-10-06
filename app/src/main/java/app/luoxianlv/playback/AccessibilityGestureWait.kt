package app.luoxianlv.playback

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import app.luoxianlv.diagnostics.AppLog
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 只保护业务等待；超时不代表系统触摸已结束，不修改宿主的手势租约计数。 */
internal class AccessibilityGestureWait(private val handler: Handler) : AutoCloseable {
    private val worker =
        ScheduledThreadPoolExecutor(1) { task ->
                Thread(
                        {
                            try {
                                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                            } catch (_: RuntimeException) {
                                // 诊断仍可运行，不依赖厂商是否允许调整线程优先级。
                            }
                            task.run()
                        },
                        "accessibility-gesture-watchdog",
                    )
                    .apply { isDaemon = true }
            }
            .apply {
                removeOnCancelPolicy = true
                setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
            }
    private var nextId = 0L
    @Volatile private var current: Ticket? = null
    @Volatile private var closed = false

    val idle
        get() = current == null

    val released
        get() = closed && worker.isTerminated

    init {
        require(handler.looper === Looper.getMainLooper())
    }

    fun start(duration: Long, onTimeout: () -> Unit): Ticket {
        requireMain()
        check(!closed) { "无障碍等待监控已关闭" }
        cancel()
        val ticket =
            Ticket(
                GestureWaitTicket(
                    ++nextId,
                    SystemClock.uptimeMillis(),
                    duration,
                    SystemClock::uptimeMillis,
                ),
                onTimeout,
            )
        current = ticket
        if (!handler.postAtTime(ticket.timeout, ticket.deadline)) {
            ticket.cancel()
            error("主线程不再接收无障碍等待任务")
        }
        try {
            ticket.diagnostic =
                worker.schedule(
                    { diagnose(ticket) },
                    ticket.deadline - SystemClock.uptimeMillis() + DIAGNOSTIC_GRACE_MS,
                    TimeUnit.MILLISECONDS,
                )
        } catch (failure: Throwable) {
            ticket.cancel()
            throw failure
        }
        return ticket
    }

    inner class Ticket
    internal constructor(
        private val state: GestureWaitTicket,
        private val onTimeout: () -> Unit,
    ) {
        val id
            get() = state.id

        val startedAt
            get() = state.startedAt

        val duration
            get() = state.duration

        val deadline
            get() = state.deadline

        internal val timeout = Runnable { expire(this) }
        internal var diagnostic: ScheduledFuture<*>? = null

        fun returned(accepted: Boolean) {
            requireMain()
            state.returned(accepted)
        }

        fun arrived(success: Boolean): Boolean {
            requireMain()
            if (!owned()) return false
            if (state.arrived(success)) return true
            if (state.expired()) expire(this)
            return false
        }

        /** 返回 true 后调用方才可提交结果或推进曲目；重复及迟到完成均返回 false。 */
        fun complete(): Boolean {
            requireMain()
            if (!owned()) return false
            if (!state.complete()) {
                if (state.expired()) expire(this)
                return false
            }
            release(this)
            return true
        }

        fun expired(): Boolean = state.expired()

        fun cancel() {
            requireMain()
            state.cancel()
            release(this)
        }

        private fun owned(): Boolean = !closed && current === this

        internal fun pending(): Boolean = state.pending()

        internal fun expireOnce(): Boolean = state.expire()

        internal fun deliverTimeout() = onTimeout()

        internal fun phase(): String =
            when (state.stage) {
                GestureWaitTicket.Stage.DISPATCHING -> "派发尚未返回"
                GestureWaitTicket.Stage.WAITING_SYSTEM -> "等待系统回调"
                GestureWaitTicket.Stage.HANDLING_CALLBACK -> "回调已到，处理尚未完成"
                GestureWaitTicket.Stage.COMPLETED -> "已完成"
                GestureWaitTicket.Stage.CANCELLED -> "已取消"
                GestureWaitTicket.Stage.TIMED_OUT -> "已超时"
            }
    }

    private fun expire(ticket: Ticket) {
        requireMain()
        if (closed || current !== ticket) return
        val phase = ticket.phase()
        if (!ticket.expireOnce()) return
        release(ticket)
        AppLog.w("无障碍手势", "等待超时：请求=${ticket.id} 时长=${ticket.duration}毫秒 阶段=$phase；本次请求按失败处理")
        if (!closed) ticket.deliverTimeout()
    }

    private fun diagnose(ticket: Ticket) {
        if (closed || current !== ticket || !ticket.pending() || !ticket.expired()) return
        val phase = ticket.phase()
        val stack =
            try {
                handler.looper.thread.stackTrace.take(MAX_STACK_FRAMES).joinToString("\n") {
                    "  at $it"
                }
            } catch (error: SecurityException) {
                "堆栈读取失败：${error.javaClass.simpleName}"
            }
        if (
            closed ||
                current !== ticket ||
                !ticket.pending() ||
                Thread.currentThread().isInterrupted
        )
            return
        AppLog.w(
            "无障碍手势",
            "主线程尚未处理超时：请求=${ticket.id} 阶段=$phase " +
                "等待=${SystemClock.uptimeMillis() - ticket.startedAt}毫秒\n主线程堆栈（最多${MAX_STACK_FRAMES}帧）：\n$stack",
        )
    }

    private fun release(ticket: Ticket) {
        handler.removeCallbacks(ticket.timeout)
        ticket.diagnostic?.cancel(false)
        ticket.diagnostic = null
        if (current === ticket) current = null
    }

    fun cancel() {
        requireMain()
        current?.cancel()
    }

    override fun close() {
        requireMain()
        if (closed) return
        cancel()
        closed = true
        worker.shutdownNow()
    }

    private fun requireMain() {
        check(Looper.myLooper() === handler.looper) { "无障碍等待状态只能在主线程更新" }
    }

    companion object {
        private const val DIAGNOSTIC_GRACE_MS = 500L
        private const val MAX_STACK_FRAMES = 20
    }
}
