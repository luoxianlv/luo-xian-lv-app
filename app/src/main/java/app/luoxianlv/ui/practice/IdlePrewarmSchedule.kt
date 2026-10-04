package app.luoxianlv.ui.practice

/** 仅在门控从暂停变为可用时排队；过期的绘制/空闲回调不能重新启动预热。主线程使用。 */
internal class IdlePrewarmSchedule(
    private val enqueue: (Runnable) -> Unit,
    private val cancel: () -> Unit,
    private val pause: () -> Unit,
    private val prepare: () -> Unit,
) {
    private var eligible = false
    private var generation = 0L
    private var closed = false

    fun update(allowed: Boolean) {
        if (closed || allowed == eligible) return
        eligible = allowed
        val ticket = ++generation
        cancel()
        if (!allowed) {
            pause()
            return
        }
        enqueue(Runnable { if (!closed && eligible && ticket == generation) prepare() })
    }

    fun close() {
        if (closed) return
        closed = true
        ++generation
        cancel()
        pause()
    }
}
