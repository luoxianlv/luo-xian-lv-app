package app.luoxianlv.ui.practice

/** 控制演练场就绪状态；固定布局模式可读取真实音区，点击仍由无障碍注入。 */
object PracticePlaybackGate {
    private var owner: Any? = null
    private var session: PracticeSession? = null

    fun bindSession(owner: Any, value: PracticeSession) {
        if (owns(owner)) session = value
    }

    fun pitchState(): Pair<app.luoxianlv.core.score.PlayMode, Boolean>? =
        session
            ?.takeIf { active && ready }
            ?.let {
                app.luoxianlv.core.score.PlayMode.valueOf(it.mode.name) to it.half
            }

    val active
        get() = owner != null

    var ready = false
        private set

    fun owns(value: Any) = owner === value

    fun enter(value: Any) {
        if (owns(value)) return
        owner = value
        session = null
        ready = false
    }

    fun setReady(owner: Any, value: Boolean) {
        if (owns(owner)) ready = value
    }

    fun invalidateSession(value: PracticeSession) {
        if (session === value) ready = false
    }

    fun leave(value: Any) {
        if (!owns(value)) return
        owner = null
        session = null
        ready = false
    }
}
