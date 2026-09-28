package app.luoxianlv.ui.practice

/** 仅控制就绪状态；坐标和曲目事件仍须经过截图识别。 */
object PracticePlaybackGate {
    var active = false
        private set

    var ready = false
        private set

    fun enter() {
        active = true
        ready = false
    }

    fun setReady(value: Boolean) {
        ready = value
    }

    fun leave() {
        ready = false
        active = false
    }
}
