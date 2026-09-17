package app.luoxianlv.core.score

enum class PlayMode(
    val label: String,
) {
    NATURAL("自然音"),
    SEMITONE("半音"),
    RAISE("升调"),
    LOWER("降调"),
}

data class NoteEvent(
    val keyIndex: Int,
    val mode: PlayMode = PlayMode.NATURAL,
    val beats: Double = 1.0,
    val rest: Boolean = false,
    val halfTone: Boolean = false,
) {
    init {
        require(beats.isFinite() && beats > 0) { "音符拍数必须大于 0" }
    }

    companion object {
        fun rest(beats: Double) = NoteEvent(-1, beats = beats, rest = true)
    }
}
