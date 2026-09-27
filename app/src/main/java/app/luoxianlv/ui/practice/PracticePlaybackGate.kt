package app.luoxianlv.ui.practice

/** Readiness only. Coordinates and song events must still go through screenshot recognition. */
object PracticePlaybackGate {
    var active = false
        private set
    var ready = false
        private set
    fun enter() { active = true; ready = false }
    fun setReady(value: Boolean) { ready = value }
    fun leave() { ready = false; active = false }
}
