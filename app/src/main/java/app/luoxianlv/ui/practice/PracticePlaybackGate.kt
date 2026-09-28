package app.luoxianlv.ui.practice

/** 控制演练场就绪状态；固定布局模式可读取真实音区，点击仍由无障碍注入。 */
object PracticePlaybackGate {
    private var session: PracticeSession? = null

    fun bindSession(value: PracticeSession) {
        session = value
    }

    fun pitchState(): Pair<app.luoxianlv.core.score.PlayMode, Boolean>? =
        session
            ?.takeIf { active && ready }
            ?.let {
                app.luoxianlv.core.score.PlayMode.valueOf(it.mode.name) to it.half
            }

    var active = false
        private set

    var ready = false
        private set

    fun enter() {
        session = null
        active = true
        ready = false
    }

    fun setReady(value: Boolean) {
        ready = value
    }

    fun leave() {
        session = null
        ready = false
        active = false
    }
}
