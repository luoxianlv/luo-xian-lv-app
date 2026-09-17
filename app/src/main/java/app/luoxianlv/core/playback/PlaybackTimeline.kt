package app.luoxianlv.core.playback
import app.luoxianlv.core.score.NoteEvent

class PlaybackTimeline(
    val events: List<NoteEvent>,
    bpm: Int,
) {
    val offsets = DoubleArray(events.size + 1)

    init {
        events.forEachIndexed { i, e -> offsets[i + 1] = offsets[i] + e.beats * 60000 / bpm }
    }

    val durationMs get() = offsets.last().toLong()

    fun indexAt(milliseconds: Long): Int {
        val found = offsets.binarySearch(milliseconds.toDouble())
        return (if (found >= 0) found else -found - 2).coerceIn(0, events.size)
    }
}
