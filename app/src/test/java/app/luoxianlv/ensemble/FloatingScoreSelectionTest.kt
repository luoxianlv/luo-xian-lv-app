package app.luoxianlv.ensemble

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingScoreSelectionTest {
    private fun room(id: String = "room", run: String = "first", phase: String = "paused") =
        JSONObject().put("id", id).put("run", run).put("phase", phase).put("owner", "owner")

    private val paused = room()
    private val owner =
        EnsembleController.ViewState(room = room(), account = "owner", connection = "connected")

    @Test
    fun onlyTheConnectedOwnerCanSelectAfterPause() {
        assertTrue(owner.canChoosePausedScore(paused))
        assertFalse(owner.copy(account = "member").canChoosePausedScore(paused))
        assertFalse(owner.copy(connection = "disconnected").canChoosePausedScore(paused))
        assertFalse(owner.copy(room = room(phase = "playing")).canChoosePausedScore(paused))
        assertFalse(owner.copy(working = true).canChoosePausedScore(paused))
        assertFalse(owner.copy(preparing = true).canChoosePausedScore(paused))
    }

    @Test
    fun anOpenPickerCannotChangeAnotherRoomOrANewerPerformance() {
        assertFalse(owner.copy(room = null).canChoosePausedScore(paused))
        assertFalse(owner.copy(room = room(id = "other")).canChoosePausedScore(paused))
        assertFalse(owner.copy(room = room(run = "next")).canChoosePausedScore(paused))
    }
}
