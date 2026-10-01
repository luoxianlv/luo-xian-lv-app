package app.luoxianlv.ui.practice

import app.luoxianlv.hot.contract.PracticeBridge

/** 控制演练场就绪状态；固定布局模式可读取真实音区，点击仍由无障碍注入。 */
object PracticePlaybackGate {
    private var owner: Any? = null
    private var session: PracticeSession? = null

    fun bindSession(owner: Any, value: PracticeSession) {
        if (owns(owner)) session = value
    }

    fun pitchState(): Pair<app.luoxianlv.core.score.PlayMode, Boolean>? =
        PracticeBridge.pitch()?.let {
            runCatching {
                app.luoxianlv.core.score.PlayMode.valueOf(it.mode) to it.half
            }
                .getOrNull()
        }

    val active
        get() = PracticeBridge.active()

    val ready
        get() = PracticeBridge.ready()

    fun owns(value: Any) = PracticeBridge.owns(value)

    fun enter(value: Any) {
        if (owns(value)) return
        owner = value
        session = null
        PracticeBridge.enter(value) { session?.let { PracticeBridge.Pitch(it.mode.name, it.half) } }
    }

    fun setReady(owner: Any, value: Boolean) {
        PracticeBridge.setReady(owner, value)
    }

    fun invalidateSession(value: PracticeSession) {
        if (session === value) owner?.let { PracticeBridge.setReady(it, false) }
    }

    fun leave(value: Any) {
        PracticeBridge.leave(value)
        if (owner !== value) return
        owner = null
        session = null
    }
}
