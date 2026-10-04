package app.luoxianlv.playback

/** 手势中断与引发中断的点击视为同一次用户操作。 */
internal class PlaybackInterruptionGuard {
    private var interruptedAt: Long? = null

    fun interrupted(now: Long) {
        interruptedAt = now
    }

    fun canStart(now: Long): Boolean = interruptedAt?.let { now - it >= 300 } ?: true
}
