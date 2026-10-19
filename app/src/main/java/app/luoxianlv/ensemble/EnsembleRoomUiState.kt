package app.luoxianlv.ensemble

import org.json.JSONObject

internal data class EnsembleActionUiState(val enabled: Boolean, val reason: String = "")

internal data class EnsembleTrackUiState(
    val index: Int,
    val name: String,
    val hash: String,
    val valid: Boolean,
)

internal data class EnsembleMemberUiState(
    val account: String,
    val nickname: String,
    val isOwner: Boolean,
    val isSelf: Boolean,
    val online: Boolean,
    val ready: Boolean,
    val playing: Boolean,
    val trackIndex: Int,
    val trackName: String,
    val trackValid: Boolean,
    val rejoin: EnsembleActionUiState = EnsembleActionUiState(false),
    val kick: EnsembleActionUiState = EnsembleActionUiState(false),
    val waitingForStart: Boolean = false,
)

/** 页面只消费完整快照派生的展示和权限，不把远端就绪当成本机输入已准备。 */
internal data class EnsembleRoomUiState(
    val id: String,
    val code: String,
    val phase: String,
    val isOwner: Boolean,
    val scoreTitle: String,
    val scoreHash: String,
    val hasScore: Boolean,
    val durationUs: Long,
    val positionUs: Long,
    val tracks: List<EnsembleTrackUiState>,
    val members: List<EnsembleMemberUiState>,
    val chooseScore: EnsembleActionUiState,
    val assign: EnsembleActionUiState,
    val start: EnsembleActionUiState,
    val resume: EnsembleActionUiState,
    val restart: EnsembleActionUiState,
    val pause: EnsembleActionUiState,
    val prepare: EnsembleActionUiState,
    val pendingStart: Boolean = false,
    val pendingRejoin: Boolean = false,
) {
    val finished: Boolean
        get() =
            phase in setOf("idle", "paused") &&
                hasScore &&
                durationUs > 0 &&
                positionUs >= durationUs

    val self: EnsembleMemberUiState?
        get() = members.firstOrNull { it.isSelf }

    val readyCount: Int
        get() = members.count { it.online && it.ready }

    val onlineCount: Int
        get() = members.count { it.online }

    companion object {
        private val hashPattern = Regex("[0-9a-f]{64}")

        fun parse(
            room: JSONObject,
            account: String,
            connected: Boolean,
            working: Boolean = false,
            preparing: Boolean = false,
            localReady: Boolean = false,
        ): EnsembleRoomUiState {
            val id = room.text("id")
            val owner = room.text("owner")
            val isOwner = account.isNotBlank() && owner == account
            val phase = room.text("phase")
            val descriptor = room.optJSONObject("score")
            val scoreHash = descriptor?.text("sha256").orEmpty()
            val durationUs = descriptor?.optLong("durationUs", 0) ?: 0
            val positionUs = room.optLong("positionUs", 0)
            val finished =
                phase in setOf("idle", "paused") && durationUs > 0 && positionUs >= durationUs
            val trackValues = descriptor?.optJSONArray("tracks")
            val tracks =
                (0 until (trackValues?.length() ?: 0)).map { index ->
                    val track = trackValues?.optJSONObject(index)
                    val hash = track?.text("sha256").orEmpty()
                    EnsembleTrackUiState(
                        index = index,
                        name =
                            track?.text("name")?.ifBlank { "声部 ${index + 1}" } ?: "声部 ${index + 1}",
                        hash = hash,
                        valid = hashPattern.matches(hash),
                    )
                }
            val hasScore =
                descriptor != null &&
                    hashPattern.matches(scoreHash) &&
                    tracks.size in 1..32 &&
                    tracks.all { it.valid }
            val memberValues = room.optJSONArray("members")
            val readyTokens = mutableMapOf<String, String>()
            val parsedMembers =
                (0 until (memberValues?.length() ?: 0)).mapNotNull { index ->
                    val member = memberValues?.optJSONObject(index) ?: return@mapNotNull null
                    val memberAccount =
                        member.text("account").ifBlank {
                            return@mapNotNull null
                        }
                    val trackIndex = member.optInt("track", -1)
                    val track = tracks.getOrNull(trackIndex)
                    val trackValid = hasScore && track?.valid == true
                    val ready = member.optJSONObject("ready")
                    readyTokens[memberAccount] = ready?.text("token").orEmpty()
                    EnsembleMemberUiState(
                        account = memberAccount,
                        nickname = member.text("nickname").ifBlank { "成员 ${index + 1}" },
                        isOwner = memberAccount == owner,
                        isSelf = memberAccount == account,
                        online = member.optBoolean("online"),
                        ready =
                            trackValid &&
                                ready != null &&
                                ready.text("token").isNotBlank() &&
                                ready.text("scoreHash") == scoreHash &&
                                ready.text("trackHash") == track.hash,
                        playing = member.optBoolean("playing"),
                        trackIndex = trackIndex,
                        trackName = track?.name ?: "未分配声部",
                        trackValid = trackValid,
                    )
                }
            val members = parsedMembers.distinctBy { it.account }
            val plan = room.optJSONObject("plan")
            val participants = plan?.optJSONArray("participants")
            val waitingAccounts =
                if (phase in setOf("proposed", "playing"))
                    (0 until (participants?.length() ?: 0))
                        .mapNotNull { index ->
                            val participant =
                                participants?.optJSONObject(index) ?: return@mapNotNull null
                            val participantAccount = participant.text("account")
                            val member =
                                members.firstOrNull { it.account == participantAccount }
                                    ?: return@mapNotNull null
                            val token = participant.text("token")
                            participantAccount.takeIf {
                                !participant.optBoolean("executed") &&
                                    member.online &&
                                    member.ready &&
                                    !member.playing &&
                                    token.isNotBlank() &&
                                    token == readyTokens[participantAccount]
                            }
                        }
                        .toSet()
                else emptySet()
            val pendingStart = waitingAccounts.isNotEmpty() && plan?.optBoolean("rejoin") != true
            val pendingRejoin = waitingAccounts.isNotEmpty() && plan?.optBoolean("rejoin") == true
            val self = members.firstOrNull { it.isSelf }
            val roomValid =
                id.isNotBlank() &&
                    owner.isNotBlank() &&
                    phase in setOf("idle", "paused", "proposed", "playing") &&
                    members.size in 1..6 &&
                    members.size == memberValues?.length() &&
                    members.any { it.isOwner } &&
                    self != null
            val remoteReason =
                when {
                    !connected -> "连接恢复后可操作"
                    !roomValid -> "房间信息异常，请重新同步"
                    working -> "正在处理操作"
                    else -> ""
                }
            val ownerReason = if (isOwner) "" else "仅主控可操作"
            val preparingReason = if (preparing) "正在准备本机输入" else ""
            val scoreReason = if (hasScore) "" else "等待主控选择有效曲目"
            val changePhaseReason = if (phase in setOf("idle", "paused")) "" else "请先暂停合奏"

            fun action(vararg reasons: String): EnsembleActionUiState {
                val reason = reasons.firstOrNull { it.isNotEmpty() }.orEmpty()
                return EnsembleActionUiState(reason.isEmpty(), reason)
            }

            fun startAction(
                allowedPhases: Set<String>,
                phaseReason: String,
                restart: Boolean = false,
            ) =
                action(
                    remoteReason,
                    preparingReason,
                    ownerReason,
                    if (phase in allowedPhases) "" else phaseReason,
                    scoreReason,
                    if (restart || !finished) "" else "曲目已结束，请选择重新开始",
                    if (members.size >= 2) "" else "等待至少两位成员加入",
                    if (members.all { it.online }) "" else "等待离线成员重连或移出房间",
                    if (members.all { it.trackValid }) "" else "请为所有成员分配有效声部",
                    if (localReady) "" else "请先准备本机输入",
                    if (members.all { it.ready }) "" else "等待所有成员准备",
                )

            val memberStates = members.map { member ->
                member.copy(
                    rejoin =
                        action(
                            remoteReason,
                            ownerReason,
                            if (phase == "playing") "" else "合奏开始后可接入",
                            scoreReason,
                            if (!pendingStart && !pendingRejoin) "" else "等待当前开始计划完成",
                            if (members.size >= 2) "" else "等待至少两位成员加入",
                            if (member.online) "" else "成员已离线",
                            if (member.trackValid) "" else "请先分配有效声部",
                            if (member.ready) "" else "等待成员重新准备",
                            if (!member.isSelf || localReady) "" else "请先准备本机输入",
                            if (!member.playing) "" else "成员正在演奏",
                        ),
                    kick = action(remoteReason, ownerReason, if (!member.isOwner) "" else "不能移出主控"),
                    waitingForStart = member.account in waitingAccounts,
                )
            }
            return EnsembleRoomUiState(
                id = id,
                code = room.text("code"),
                phase = phase,
                isOwner = isOwner,
                scoreTitle = descriptor?.text("title")?.ifBlank { "未命名曲目" } ?: "还未选择曲目",
                scoreHash = scoreHash,
                hasScore = hasScore,
                durationUs = durationUs,
                positionUs = positionUs,
                tracks = tracks,
                members = memberStates,
                chooseScore = action(remoteReason, preparingReason, ownerReason, changePhaseReason),
                assign =
                    action(
                        remoteReason,
                        preparingReason,
                        ownerReason,
                        changePhaseReason,
                        scoreReason,
                    ),
                start = startAction(setOf("idle"), "当前不能开始合奏"),
                resume = startAction(setOf("paused"), "暂停后可继续合奏"),
                restart = startAction(setOf("idle", "paused"), "请先暂停合奏", restart = true),
                // 暂停先停止本机，不能被断线或其他命令占用挡住。
                pause =
                    action(
                        ownerReason,
                        if (phase in setOf("proposed", "playing")) "" else "当前没有进行中的合奏",
                    ),
                prepare =
                    action(
                        remoteReason,
                        preparingReason,
                        if (!localReady) "" else "本机已准备",
                        if (self?.playing != true) "" else "本机正在演奏",
                        if (phase in setOf("idle", "paused", "playing")) "" else "等待开始确认完成",
                        scoreReason,
                        if (self?.online == true) "" else "等待本机重新连接房间",
                        if (self?.trackValid == true) "" else "等待主控分配有效声部",
                    ),
                pendingStart = pendingStart,
                pendingRejoin = pendingRejoin,
            )
        }

        private fun JSONObject.text(key: String) = (opt(key) as? String)?.trim().orEmpty()
    }
}
