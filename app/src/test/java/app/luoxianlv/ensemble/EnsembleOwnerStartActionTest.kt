package app.luoxianlv.ensemble

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class EnsembleOwnerStartActionTest {
    private fun room(phase: String, positionUs: Long = 0, durationUs: Long = 20_000_000) =
        JSONObject()
            .put("phase", phase)
            .put("positionUs", positionUs)
            .put("score", JSONObject().put("durationUs", durationUs))

    @Test
    fun naturalEndReplaysFromTheBeginningInsteadOfSubmittingRejectedStart() {
        assertEquals("restart", ensembleOwnerStartAction(room("idle", 20_000_000)))
        assertEquals("restart", ensembleOwnerStartAction(room("paused", 20_000_000)))
    }

    @Test
    fun activeScoreKeepsOrdinaryStartAndResumeBehavior() {
        assertEquals("start", ensembleOwnerStartAction(room("idle")))
        assertEquals("resume", ensembleOwnerStartAction(room("paused", 8_000_000)))
    }

    @Test
    fun preparedOwnerRejoinsOngoingPerformanceInsteadOfStartingTheWholeRoom() {
        assertEquals("rejoin", ensembleOwnerStartAction(room("playing", 8_000_000)))
        // Position can be retained in a snapshot; the active phase still takes precedence.
        assertEquals("rejoin", ensembleOwnerStartAction(room("playing", 20_000_000)))
    }

    @Test
    fun missingDurationDoesNotMisclassifyANewRoomAsFinished() {
        assertEquals("start", ensembleOwnerStartAction(room("idle", durationUs = 0)))
        assertEquals("start", ensembleOwnerStartAction(JSONObject().put("phase", "idle")))
    }
}
