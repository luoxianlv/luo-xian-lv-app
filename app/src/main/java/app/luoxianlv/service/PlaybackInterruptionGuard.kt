package app.luoxianlv.service

/** An interruption and the finger tap that caused it are one user action. */
internal class PlaybackInterruptionGuard {
    private var interruptedAt: Long? = null

    fun interrupted(now: Long) {
        interruptedAt = now
    }

    fun canStart(now: Long): Boolean = interruptedAt?.let { now - it >= 300 } ?: true
}
