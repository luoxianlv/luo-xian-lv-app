package app.luoxianlv.runtime.realtime

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** 普通通知只保留每个键的最新值；生产者不等待消费者，关闭立即释放排队回调。 */
internal class NotificationMailbox(private val executor: Executor, private val capacity: Int = 8) :
    AutoCloseable {
    private val lock = Any()
    private val pending = LinkedHashMap<String, () -> Unit>()
    private var scheduled = false
    private var closed = false

    init {
        require(capacity > 0)
    }

    fun offer(key: String, action: () -> Unit): Boolean {
        val start =
            synchronized(lock) {
                if (closed || (key !in pending && pending.size >= capacity)) return false
                pending[key] = action
                if (scheduled) false
                else {
                    scheduled = true
                    true
                }
            }
        if (start) {
            try {
                executor.execute(::drain)
            } catch (_: RejectedExecutionException) {
                close()
                return false
            }
        }
        return true
    }

    private fun drain() {
        while (true) {
            val action =
                synchronized(lock) {
                    if (closed || pending.isEmpty()) {
                        scheduled = false
                        return
                    }
                    val first = pending.entries.iterator()
                    first.next().value.also { first.remove() }
                }
            // 消费者异常只影响本次通知，不能终止连接或其他订阅。
            try {
                action()
            } catch (_: Exception) {}
        }
    }

    override fun close() =
        synchronized(lock) {
            closed = true
            pending.clear()
        }
}
