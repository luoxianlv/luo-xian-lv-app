package app.luoxianlv.ui.practice

/** Pure single-voice touch state. A stale finger-up must never release a newer note. */
class PracticeSession {
    enum class Mode(val semitones: Int, val label: String) {
        LOWER(-12, "降调"), NATURAL(0, "自然音"), RAISE(12, "升调")
    }
    data class Note(val pointer: Int, val key: Int, val midi: Int)
    var mode = Mode.NATURAL
        private set
    var half = false
        private set
    var active: Note? = null
        private set

    fun select(mode: Mode) { this.mode = mode }
    fun toggleHalf() { half = !half }
    fun press(pointer: Int, key: Int): Note = Note(pointer, key, pitch(key, mode, half)).also { active = it }
    fun release(pointer: Int): Boolean {
        if (active?.pointer != pointer) return false
        active = null
        return true
    }
    fun cancel() { active = null }
    companion object {
        private val intervals = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12)
        fun pitch(key: Int, mode: Mode, half: Boolean): Int {
            require(key in intervals.indices)
            return 60 + intervals[key] + mode.semitones + if (half) 1 else 0
        }
        fun sampleName(midi: Int): String {
            require(midi in 48..85)
            val names = listOf("C", "Cs", "D", "Ds", "E", "F", "Fs", "G", "Gs", "A", "As", "B")
            return "Harmonica_${names[midi % 12]}${midi / 12}.wav"
        }
    }
}
