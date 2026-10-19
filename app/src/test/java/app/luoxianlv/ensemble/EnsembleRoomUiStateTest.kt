package app.luoxianlv.ensemble

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EnsembleRoomUiStateTest {
    private val scoreHash = "a".repeat(64)
    private val trackHash = "b".repeat(64)

    private fun member(
        account: String,
        online: Boolean = true,
        ready: Boolean = true,
        playing: Boolean = false,
        track: Int = 0,
    ) =
        JSONObject()
            .put("account", account)
            .put("nickname", if (account == "owner") "主控昵称" else "成员昵称")
            .put("online", online)
            .put("playing", playing)
            .put("track", track)
            .apply {
                if (ready)
                    put(
                        "ready",
                        JSONObject()
                            .put("token", "token-$account")
                            .put("scoreHash", scoreHash)
                            .put("trackHash", trackHash),
                    )
            }

    private fun room(
        phase: String = "idle",
        members: List<JSONObject> = listOf(member("owner"), member("guest")),
    ) =
        JSONObject()
            .put("id", "room-id")
            .put("code", "123456")
            .put("owner", "owner")
            .put("phase", phase)
            .put("positionUs", 0)
            .put("members", JSONArray(members))
            .put(
                "score",
                JSONObject()
                    .put("title", "合奏曲目")
                    .put("sha256", scoreHash)
                    .put("durationUs", 20_000_000)
                    .put(
                        "tracks",
                        JSONArray().put(JSONObject().put("name", "主旋律").put("sha256", trackHash)),
                    ),
            )

    private fun parse(
        value: JSONObject = room(),
        account: String = "owner",
        connected: Boolean = true,
        working: Boolean = false,
        preparing: Boolean = false,
        localReady: Boolean = true,
    ) = EnsembleRoomUiState.parse(value, account, connected, working, preparing, localReady)

    private fun plan(
        vararg accounts: String,
        rejoin: Boolean = false,
        executed: Boolean = false,
        committed: Boolean = true,
        tokenPrefix: String = "token-",
    ) =
        JSONObject()
            .put("id", "d37ea425-589b-4b70-93aa-74f39141cf38")
            .put("run", "1dc1330e-e82a-47bf-b099-36fb4fa31e99")
            .put("executeAtUs", "3600000000")
            .put("positionUs", "0")
            .put("committed", committed)
            .put("rejoin", rejoin)
            .put(
                "participants",
                JSONArray(
                    accounts.map { account ->
                        JSONObject()
                            .put("account", account)
                            .put("token", "$tokenPrefix$account")
                            .put("ack", committed)
                            .put("executed", executed)
                    }
                ),
            )

    @Test
    fun readyRoomAllowsOwnerToStartAndPreservesSameScoreAssignment() {
        val state = parse()
        assertTrue(state.isOwner)
        assertTrue(state.hasScore)
        assertTrue(state.start.enabled)
        assertTrue(state.restart.enabled)
        assertFalse(state.resume.enabled)
        assertEquals(2, state.readyCount)
        assertEquals(2, state.onlineCount)
        assertEquals(1, state.tracks.size)
        assertEquals("主旋律", state.self?.trackName)
        assertEquals("主控昵称", state.self?.nickname)
        assertTrue(state.members.all { it.trackIndex == 0 })
    }

    @Test
    fun guestCanPrepareWithoutReceivingOwnerControls() {
        val state = parse(account = "guest", localReady = false)
        assertTrue(state.prepare.enabled)
        assertFalse(state.isOwner)
        assertFalse(state.chooseScore.enabled)
        assertFalse(state.assign.enabled)
        assertFalse(state.start.enabled)
        assertFalse(state.restart.enabled)
        assertFalse(state.pause.enabled)
        assertTrue(state.members.none { it.kick.enabled || it.rejoin.enabled })
    }

    @Test
    fun serverReadyCannotReplaceMissingLocalPreparation() {
        val state = parse(localReady = false)
        assertEquals(2, state.readyCount)
        assertFalse(state.start.enabled)
        assertEquals("请先准备本机输入", state.start.reason)
        assertTrue(state.prepare.enabled)
    }

    @Test
    fun startRequiresAnotherMemberAndEveryMemberOnlineAndReady() {
        val alone = parse(room(members = listOf(member("owner"))))
        assertFalse(alone.start.enabled)
        assertEquals("等待至少两位成员加入", alone.start.reason)

        val offline =
            parse(room(members = listOf(member("owner"), member("guest", online = false))))
        assertFalse(offline.start.enabled)
        assertEquals(1, offline.readyCount)
        assertEquals("等待离线成员重连或移出房间", offline.start.reason)

        val unprepared =
            parse(room(members = listOf(member("owner"), member("guest", ready = false))))
        assertFalse(unprepared.start.enabled)
        assertEquals("等待所有成员准备", unprepared.start.reason)
    }

    @Test
    fun pausedRoomRequiresPreparationBeforeResumingOrRestarting() {
        val state = parse(room(phase = "paused"))
        assertTrue(state.resume.enabled)
        assertTrue(state.restart.enabled)
        assertFalse(state.start.enabled)
        assertTrue(state.chooseScore.enabled)
        assertTrue(state.assign.enabled)

        val notReady = parse(room(phase = "paused"), localReady = false)
        assertFalse(notReady.resume.enabled)
        assertFalse(notReady.restart.enabled)
        assertTrue(notReady.prepare.enabled)
    }

    @Test
    fun completedRoomOffersRestartAfterEveryonePreparesAgain() {
        // Room.expire() ends in idle at the common end and clears every ready marker.
        val ended =
            room(members = listOf(member("owner", ready = false), member("guest", ready = false)))
                .put("positionUs", 20_000_000)
        val state = parse(ended, localReady = false)
        assertTrue(state.finished)
        assertEquals(20_000_000L, state.durationUs)
        assertEquals(state.durationUs, state.positionUs)
        assertEquals(0, state.readyCount)
        assertFalse(state.start.enabled)
        assertEquals("曲目已结束，请选择重新开始", state.start.reason)
        assertFalse(state.restart.enabled)
        assertTrue(state.prepare.enabled)

        val preparedAgain = parse(room().put("positionUs", 20_000_000))
        assertFalse(preparedAgain.start.enabled)
        assertTrue(preparedAgain.restart.enabled)
        assertFalse(parse(room("paused").put("positionUs", 20_000_000)).resume.enabled)
        assertTrue(parse(room("paused").put("positionUs", 20_000_000)).restart.enabled)
    }

    @Test
    fun restartedSongIsNotFinishedWhileStoredPositionStillContainsPreviousEnd() {
        // Restart plans start from zero; Room.positionUs remains at the previous end until pause.
        for (phase in listOf("proposed", "playing")) {
            val state =
                parse(
                    room(phase)
                        .put("positionUs", 20_000_000)
                        .put("plan", plan("owner", "guest", committed = phase == "playing"))
                )
            assertFalse(state.finished)
            assertTrue(state.pendingStart)
        }
    }

    @Test
    fun remainingOwnerCannotRejoinWithoutAnotherParticipant() {
        val state = parse(room("playing", listOf(member("owner"))))
        assertFalse(state.self!!.rejoin.enabled)
        assertEquals("等待至少两位成员加入", state.self!!.rejoin.reason)
    }

    @Test
    fun committedPlanIsStillWaitingUntilParticipantsExecute() {
        val state = parse(room("playing").put("plan", plan("owner", "guest")))
        assertTrue(state.pendingStart)
        assertFalse(state.pendingRejoin)
        assertTrue(state.members.all { it.waitingForStart })
        assertTrue(state.members.none { it.rejoin.enabled })
        assertEquals("等待当前开始计划完成", state.self!!.rejoin.reason)

        val confirming =
            parse(room("proposed").put("plan", plan("owner", "guest", committed = false)))
        assertTrue(confirming.pendingStart)
        assertTrue(confirming.members.all { it.waitingForStart })
    }

    @Test
    fun executedOrAlreadyPlayingMembersDoNotRemainInWaitingState() {
        val executed = parse(room("playing").put("plan", plan("owner", "guest", executed = true)))
        assertFalse(executed.pendingStart)
        assertFalse(executed.pendingRejoin)
        assertTrue(executed.members.none { it.waitingForStart })

        val playing =
            parse(
                room(
                        "playing",
                        listOf(member("owner", playing = true), member("guest", playing = true)),
                    )
                    .put("plan", plan("owner", "guest"))
            )
        assertFalse(playing.pendingStart)
        assertTrue(playing.members.none { it.waitingForStart })
    }

    @Test
    fun rejoinPlanOnlyMarksItsTargetAndPreventsReplacementByAnotherRejoin() {
        val state =
            parse(
                room("playing", listOf(member("owner", playing = true), member("guest")))
                    .put("plan", plan("guest", rejoin = true))
            )
        assertFalse(state.pendingStart)
        assertTrue(state.pendingRejoin)
        assertFalse(state.self!!.waitingForStart)
        assertTrue(state.members.first { !it.isSelf }.waitingForStart)
        assertTrue(state.members.none { it.rejoin.enabled })
    }

    @Test
    fun staleTokensAndInactivePhasesCannotReopenWaitingState() {
        val stale = parse(room("playing").put("plan", plan("owner", "guest", tokenPrefix = "old-")))
        assertFalse(stale.pendingStart)
        assertFalse(stale.pendingRejoin)
        assertTrue(stale.members.none { it.waitingForStart })
        assertTrue(stale.self!!.rejoin.enabled)

        for (phase in listOf("idle", "paused")) {
            val inactive = parse(room(phase).put("plan", plan("owner", "guest")))
            assertFalse(inactive.pendingStart)
            assertTrue(inactive.members.none { it.waitingForStart })
        }
    }

    @Test
    fun preparationBlocksConflictingActionsAndCanBeDisplayedImmediately() {
        val state = parse(preparing = true, localReady = false)
        assertFalse(state.prepare.enabled)
        assertEquals("正在准备本机输入", state.prepare.reason)
        assertFalse(state.chooseScore.enabled)
        assertFalse(state.assign.enabled)
        assertFalse(state.start.enabled)
        assertFalse(state.restart.enabled)
    }

    @Test
    fun pauseRemainsAvailableWhileDisconnectedOrBusy() {
        val state =
            parse(room(phase = "proposed"), connected = false, working = true, preparing = true)
        assertTrue(state.pause.enabled)
        assertFalse(state.start.enabled)
        assertFalse(state.prepare.enabled)
        assertFalse(state.chooseScore.enabled)
        assertFalse(parse(room(phase = "proposed"), account = "guest").pause.enabled)
    }

    @Test
    fun rejoinIncludesTheOwnerAfterReconnectButRequiresLocalPreparation() {
        val value = room(phase = "playing")
        val state = parse(value)
        assertTrue(state.self!!.rejoin.enabled)
        assertFalse(state.self!!.kick.enabled)
        assertTrue(state.members.first { !it.isSelf }.rejoin.enabled)
        assertTrue(state.members.first { !it.isSelf }.kick.enabled)
        assertFalse(parse(value, localReady = false).self!!.rejoin.enabled)
        assertFalse(parse(value, connected = false).self!!.rejoin.enabled)

        val offline =
            parse(room("playing", listOf(member("owner"), member("guest", online = false))))
        assertFalse(offline.members.first { !it.isSelf }.rejoin.enabled)
        val playing =
            parse(room("playing", listOf(member("owner"), member("guest", playing = true))))
        assertFalse(playing.members.first { !it.isSelf }.rejoin.enabled)
    }

    @Test
    fun invalidOrMissingScoreAndTrackNeverEnablePreparation() {
        val noScore = room().apply { remove("score") }
        assertFalse(parse(noScore, localReady = false).prepare.enabled)
        assertFalse(parse(noScore).start.enabled)
        assertTrue(parse(noScore).chooseScore.enabled)

        for (track in listOf(-1, 1, Int.MAX_VALUE)) {
            val state =
                parse(
                    room(members = listOf(member("owner", track = track), member("guest"))),
                    localReady = false,
                )
            assertFalse(state.self!!.trackValid)
            assertEquals("未分配声部", state.self!!.trackName)
            assertFalse(state.prepare.enabled)
            assertFalse(state.start.enabled)
            assertTrue(state.assign.enabled)
        }
    }

    @Test
    fun staleReadyHashesAndEmptyTokensAreNotReportedAsReady() {
        for (field in listOf("token", "scoreHash", "trackHash")) {
            val guest = member("guest")
            guest.getJSONObject("ready").put(field, "")
            val state = parse(room(members = listOf(member("owner"), guest)))
            assertEquals(1, state.readyCount)
            assertFalse(state.members.first { !it.isSelf }.ready)
            assertFalse(state.start.enabled)
        }
    }

    @Test
    fun malformedSnapshotsUseSafeFallbacksAndDoNotInventParticipants() {
        val empty = parse(JSONObject(), localReady = false)
        assertEquals("还未选择曲目", empty.scoreTitle)
        assertTrue(empty.members.isEmpty())
        assertNull(empty.self)
        assertFalse(empty.chooseScore.enabled)
        assertFalse(empty.prepare.enabled)
        assertFalse(empty.start.enabled)

        val malformed = room().put("members", JSONArray().put(JSONObject.NULL).put("invalid"))
        malformed.getJSONObject("score").put("tracks", JSONArray().put(JSONObject.NULL))
        val state = parse(malformed, localReady = false)
        assertFalse(state.hasScore)
        assertEquals("声部 1", state.tracks.single().name)
        assertFalse(state.start.enabled)

        val duplicate = parse(room(members = listOf(member("owner"), member("owner"))))
        assertEquals(1, duplicate.members.size)
        assertFalse(duplicate.start.enabled)
        assertFalse(duplicate.assign.enabled)
    }
}
