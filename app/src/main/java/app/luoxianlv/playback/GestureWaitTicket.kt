package app.luoxianlv.playback

/** 单次无障碍请求的截止时间与完成权；主线程和诊断线程共用，迟到回调不获得完成权。 */
internal class GestureWaitTicket(
    val id: Long,
    val startedAt: Long,
    val duration: Long,
    private val now: () -> Long,
) {
    enum class Stage {
        DISPATCHING,
        WAITING_SYSTEM,
        HANDLING_CALLBACK,
        COMPLETED,
        CANCELLED,
        TIMED_OUT,
    }

    init {
        require(id > 0 && startedAt >= 0 && duration > 0)
        require(duration <= Long.MAX_VALUE - CALLBACK_GRACE_MS)
        require(startedAt <= Long.MAX_VALUE - duration - CALLBACK_GRACE_MS)
    }

    val deadline = startedAt + duration + CALLBACK_GRACE_MS
    @Volatile
    var stage = Stage.DISPATCHING
        private set

    @Volatile
    var accepted: Boolean? = null
        private set

    @Volatile
    var callbackSuccess: Boolean? = null
        private set

    @Synchronized
    fun returned(value: Boolean) {
        if (!pending()) return
        accepted = value
        if (stage == Stage.DISPATCHING) stage = Stage.WAITING_SYSTEM
    }

    @Synchronized
    fun arrived(success: Boolean): Boolean {
        if (!pending() || stage == Stage.HANDLING_CALLBACK || now() >= deadline) return false
        callbackSuccess = success
        stage = Stage.HANDLING_CALLBACK
        return true
    }

    @Synchronized
    fun complete(): Boolean {
        if (!pending() || now() >= deadline) return false
        stage = Stage.COMPLETED
        return true
    }

    @Synchronized
    fun expire(): Boolean {
        if (!pending() || now() < deadline) return false
        stage = Stage.TIMED_OUT
        return true
    }

    @Synchronized
    fun cancel(): Boolean {
        if (!pending()) return false
        stage = Stage.CANCELLED
        return true
    }

    fun expired(): Boolean = stage == Stage.TIMED_OUT || pending() && now() >= deadline

    fun pending(): Boolean =
        stage == Stage.DISPATCHING ||
            stage == Stage.WAITING_SYSTEM ||
            stage == Stage.HANDLING_CALLBACK

    companion object {
        const val CALLBACK_GRACE_MS = 1500L
    }
}
