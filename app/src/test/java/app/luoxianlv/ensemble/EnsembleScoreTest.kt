package app.luoxianlv.ensemble

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EnsembleScoreTest {
    private fun note(start: Long, end: Long, key: Int = 2) =
        JSONObject()
            .put("startUs", start)
            .put("endUs", end)
            .put("key", key)
            .put("mode", 0)
            .put("halfTone", false)

    @Test
    fun preservesLeadingRestAndCommonEnd() {
        val events =
            EnsembleScore.events(JSONArray().put(note(8_000_000, 9_000_000)).toString(), 10_000_000)
        assertEquals(3, events.size)
        assertTrue(events[0].rest)
        assertEquals(16.0, events[0].beats, 0.000001)
        assertEquals(20.0, events.sumOf { it.beats }, 0.000001)
        assertTrue(events.last().rest)
    }

    @Test
    fun rejectsOverlapBoundsAndInvalidKeys() {
        for (notes in
            listOf(
                JSONArray().put(note(0, 200)).put(note(100, 300)),
                JSONArray().put(note(-1, 200)),
                JSONArray().put(note(0, 0)),
                JSONArray().put(note(0, 1_001)),
                JSONArray().put(note(0, 200, 8)),
            )) assertThrows(IllegalArgumentException::class.java) {
            EnsembleScore.events(notes.toString(), 1_000)
        }
    }

    @Test
    fun authenticatesWholePackageAndExactEventBytes() {
        val events = JSONArray().put(note(0, 1_000)).toString()
        val part =
            JSONObject()
                .put("name", "声部")
                .put("events", events)
                .put("sha256", digest(events.toByteArray()))
        val root =
            JSONObject()
                .put("schemaVersion", 1)
                .put("title", "测试")
                .put("durationUs", 1_000)
                .put("tracks", JSONArray().put(part))
        val bytes = root.toString().toByteArray()
        assertEquals(events, EnsembleScore.read(bytes, digest(bytes)).parts.single().events)
        assertThrows(IllegalArgumentException::class.java) {
            EnsembleScore.read(bytes, "0".repeat(64))
        }
        part.put("events", events + " ")
        val corrupted = root.toString().toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            EnsembleScore.read(corrupted, digest(corrupted))
        }
    }
}
