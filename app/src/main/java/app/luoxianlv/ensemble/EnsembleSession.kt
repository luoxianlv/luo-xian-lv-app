package app.luoxianlv.ensemble

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import app.luoxianlv.BuildConfig
import app.luoxianlv.app.BusinessJobs
import app.luoxianlv.diagnostics.AppLog
import app.luoxianlv.hot.contract.PlaybackBridge
import app.luoxianlv.hot.contract.SharedRealtime
import app.luoxianlv.library.Song
import app.luoxianlv.platform.BackendClient
import app.luoxianlv.platform.PlatformClient
import app.luoxianlv.platform.SessionStore
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/** The floating control follows the same resume, replay and rejoin rules as the room page. */
internal fun ensembleOwnerStartAction(room: JSONObject): String {
    val phase = room.optString("phase")
    val duration = room.optJSONObject("score")?.optLong("durationUs") ?: 0L
    return when {
        phase == "playing" -> "rejoin"
        phase in setOf("idle", "paused") &&
            duration > 0 &&
            room.optLong("positionUs") >= duration -> "restart"
        phase == "paused" -> "resume"
        else -> "start"
    }
}

/** 业务代次持有房间展示与输入准备，Socket 和校时仍归共享运行时。 */
internal class EnsembleController(context: Context) : AutoCloseable {
    data class ViewState(
        val room: JSONObject? = null,
        val account: String = "",
        val connection: String = "stopped",
        val working: Boolean = false,
        val preparing: Boolean = false,
        val ready: Boolean = false,
        val message: String = "",
        val supported: Boolean = true,
        val serviceUnavailable: Boolean = false,
    )

    private val app = context.applicationContext
    private val sessions = SessionStore(app)
    private val api = BackendClient(BuildConfig.UPDATE_BASE_URL, sessions)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bridge = SharedRealtime.current()
    private val processLease = BusinessJobs.gate.retain()
    private val mutable = MutableStateFlow(ViewState(supported = bridge != null))
    val state = mutable.asStateFlow()
    private var observer: AutoCloseable? = null
    private var scheduled: AutoCloseable? = null
    private var score: EnsembleScore? = null
    private var prepared = ""
    private var preparedHash = ""
    private var preparingJob: Job? = null
    private var preparationGeneration = 0L
    private var lastProposal = ""
    private var lastCommit = ""
    private var connectionEpoch = ""
    private var accountResetEpoch = Long.MIN_VALUE
    private var runStartUs = 0L
    private var runClockEpoch = ""
    private var sampledAtUs = 0L
    private var closed = false
    private var suspended = false
    private var syncJob: Job? = null
    private var startCommand: Job? = null
    private var pendingRoomEvent: JSONObject? = null
    private var messageGeneration = 0L
    @Volatile private var roomGeneration = 0L
    private var lastSync = 0L
    val active
        get() = mutable.value.room != null

    val owner
        get() = mutable.value.room?.optString("owner") == mutable.value.account

    val waitingToStart
        get() =
            prepared.isNotEmpty() &&
                runStartUs == 0L &&
                (startCommand?.isActive == true || lastProposal.isNotEmpty())

    val pendingStatus: String?
        get() {
            if (!waitingToStart) return null
            val plan = mutable.value.room?.optJSONObject("plan")
            if (plan?.optBoolean("committed") != true) return "等待确认 · 点击取消"
            val remaining =
                plan
                    .optString("executeAtUs")
                    .toLongOrNull()
                    ?.minus(
                        SystemClock.elapsedRealtimeNanos() / 1000 +
                            (bridge?.clock()?.getLong("offsetUs") ?: 0)
                    ) ?: return "等待开始 · 点击取消"
            val seconds = ((remaining.coerceAtLeast(0) + 999_999) / 1_000_000).coerceAtMost(10)
            return if (seconds > 0) "$seconds 秒后开始 · 点击取消" else "即将开始 · 点击取消"
        }

    private val listener =
        object : SharedRealtime.Listener {
            override fun changed(value: Bundle) {
                if (closed || suspended) return
                val account = value.getString("accountId").orEmpty()
                val nextEpoch = value.getString("connectionEpoch").orEmpty()
                val reset = value.getLong("accountResetEpoch")
                if (accountResetEpoch != Long.MIN_VALUE && accountResetEpoch != reset) {
                    clearRoom()
                    lastSync = 0
                }
                accountResetEpoch = reset
                if (nextEpoch != connectionEpoch) {
                    stopLocal(false, reason = "连接代次改变")
                    connectionEpoch = nextEpoch
                    lastSync = 0
                }
                if (mutable.value.account.isNotEmpty() && mutable.value.account != account) {
                    if (account.isNotEmpty() || sessions.current() == null) clearRoom()
                    else stopLocal(false)
                }
                if (prepared.isNotEmpty() && !value.getBoolean("clockReady"))
                    stopLocal(true, reason = "校时未就绪")
                mutable.value =
                    mutable.value.copy(
                        connection = value.getString("state").orEmpty(),
                        serviceUnavailable = value.getBoolean("serviceUnavailable", false),
                        account = account,
                        supported =
                            !value.getBoolean("connected") || value.getBoolean("ensembleEnabled"),
                    )
                if (account.isNotEmpty() && nextEpoch.isNotEmpty() && lastSync == 0L) synchronize()
            }

            override fun message(topic: String, type: String, json: String) {
                if (!closed && !suspended && topic == "ensemble" && type == "changed")
                    runCatching {
                        roomEvent(JSONObject(json).getJSONObject("data").getJSONObject("room"))
                    }
                        .onFailure { message("房间状态异常，请重新同步") }
            }
        }

    init {
        scope.coroutineContext[Job]!!.invokeOnCompletion { processLease?.close() }
        observer = bridge?.observe(listener)
        scope.launch {
            while (isActive) {
                if (suspended) {
                    delay(30_000)
                    continue
                }
                if (
                    sessions.current() != null &&
                        bridge != null &&
                        mutable.value.connection == "connected"
                )
                    runCatching {
                        BusinessJobs.io {
                            api.authorizedToken(bridge.state().getBoolean("accountRefreshRequired"))
                        }
                    }
                        .onSuccess { bridge.account(it) }
                if (
                    active &&
                        mutable.value.account.isNotEmpty() &&
                        SystemClock.elapsedRealtime() - lastSync >= 60_000
                )
                    synchronize()
                delay(30_000)
            }
        }
        scope.launch {
            state
                .map { it.ready && it.room?.optString("phase") == "playing" }
                .distinctUntilChanged()
                .collectLatest { playing ->
                    if (!playing) return@collectLatest
                    while (isActive) {
                        if (prepared.isNotEmpty() && runStartUs > 0) {
                            val clock = bridge?.clock()
                            val playback = PlaybackBridge.current()
                            val input = playback?.query("ensemble")
                            if (
                                clock != null &&
                                    input?.getBoolean("playing") == true &&
                                    clock.getLong("sampledAtUs") != sampledAtUs
                            ) {
                                sampledAtUs = clock.getLong("sampledAtUs")
                                val drift =
                                    runStartUs -
                                        clock.getLong("offsetUs") -
                                        input.getLong("localStartUs")
                                if (
                                    !clock.getBoolean("ready") ||
                                        clock.getString("clockEpoch") != runClockEpoch ||
                                        kotlin.math.abs(drift) > 50_000
                                ) {
                                    stopLocal(true, reason = "校时漂移或时钟代次改变")
                                    message("校时发生变化，请重新准备")
                                } else
                                    playback.command(
                                        "ensembleClock",
                                        Bundle().apply {
                                            putString("token", prepared)
                                            putLong("correctionUs", drift)
                                        },
                                    )
                            }
                        }
                        delay(1000)
                    }
                }
        }
    }

    private fun message(text: String) {
        messageGeneration++
        mutable.value = mutable.value.copy(message = text.take(200))
    }

    private suspend fun command(type: String, body: JSONObject = JSONObject()): JSONObject {
        val generation = roomGeneration
        mutable.value.room?.optString("id")?.let { if (!body.has("roomId")) body.put("roomId", it) }
        val result = BusinessJobs.io {
            if (generation != roomGeneration) throw CancellationException("房间已改变")
            val id = UUID.randomUUID().toString()
            try {
                request(id, type, body)
            } catch (unknown: Unconfirmed) {
                if (type == "commandResult" || bridge?.state()?.getBoolean("connected") != true)
                    throw unknown
                // 只查原幂等结果，不重新发送开始/退出/上传许可等写操作。
                request(
                    UUID.randomUUID().toString(),
                    "commandResult",
                    JSONObject().put("commandId", id),
                )
            }
        }
        if (generation != roomGeneration || closed || suspended)
            throw CancellationException("房间已改变")
        return result
    }

    private class Unconfirmed : IllegalStateException("操作未确认，请重新同步")

    private suspend fun request(id: String, type: String, body: JSONObject): JSONObject =
        suspendCancellableCoroutine { continuation ->
            val selected = bridge
            if (selected == null) {
                continuation.resumeWithException(IllegalStateException("当前版本尚未开放多人合奏"))
                return@suspendCancellableCoroutine
            }
            val lease =
                selected.send(
                    id,
                    "ensemble",
                    type,
                    body.toString(),
                ) { ok, text ->
                    if (continuation.isActive) {
                        val result = runCatching { JSONObject(text) }
                        if (ok && result.isSuccess) continuation.resume(result.getOrThrow())
                        else if (result.getOrNull()?.optBoolean("unconfirmed") == true)
                            continuation.resumeWithException(Unconfirmed())
                        else
                            continuation.resumeWithException(
                                IllegalStateException(
                                    result.getOrNull()?.optString("message")?.takeIf {
                                        it.isNotBlank()
                                    } ?: "操作未确认，请重新同步"
                                )
                            )
                    }
                }
            continuation.invokeOnCancellation { lease.close() }
        }

    private fun work(action: suspend () -> Unit) {
        if (closed || suspended || mutable.value.working) return
        scope.launch {
            mutable.value = mutable.value.copy(working = true, message = "")
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                message(failure.message ?: "操作失败，请重试")
            } finally {
                mutable.value = mutable.value.copy(working = false)
            }
        }
    }

    fun create() = work {
        roomGeneration++
        applyRoom(command("create"))
    }

    fun join(code: String) = work {
        roomGeneration++
        applyRoom(command("join", JSONObject().put("code", code)))
    }

    fun leave() = work {
        roomGeneration++
        stopLocal(false)
        command("leave")
        clearRoom()
    }

    fun control(type: String, account: String? = null) {
        // 暂停不能被选谱或开始确认占用的工作状态挡住。
        if (type == "pause") {
            stopLocal(true, true)
            return
        }
        work {
            val starting = type in setOf("start", "resume", "restart")
            if (starting) startCommand = currentCoroutineContext()[Job]
            try {
                applyRoom(
                    command(type, JSONObject().apply { account?.let { put("accountId", it) } })
                )
            } finally {
                if (starting) startCommand = null
            }
        }
    }

    fun assign(account: String, track: Int) = work {
        applyRoom(command("assign", JSONObject().put("accountId", account).put("track", track)))
    }

    fun synchronize() {
        if (closed || suspended || syncJob?.isActive == true) return
        syncJob = scope.launch {
            lastSync = SystemClock.elapsedRealtime()
            val previousMessageGeneration = messageGeneration
            runCatching {
                command("current").let {
                    if (closed || suspended) return@let
                    if (it.has("id")) {
                        applyRoom(it)
                        // 原位回退不一定更换 WS；清理旧业务留下的本机就绪标记，不能自动续点。
                        val mine = mutable.value.room?.let(::self)
                        if (
                            prepared.isEmpty() &&
                                preparingJob?.isActive != true &&
                                (mine?.optJSONObject("ready") != null ||
                                    mine?.optBoolean("playing") == true)
                        ) {
                            applyRoom(command("stop"))
                        }
                    } else clearRoom()
                }
            }
                .onSuccess {
                    // A successful refresh clears the error it was meant to recover, while
                    // preserving any newer error reported by a concurrent room operation.
                    if (messageGeneration == previousMessageGeneration)
                        mutable.value = mutable.value.copy(message = "")
                }
                .onFailure { if (it !is CancellationException) message(it.message ?: "同步失败") }
        }
    }

    fun choose(song: Song, speed: Float = 1f, transpose: Int? = null) = work {
        val generation = roomGeneration
        val room = mutable.value.room ?: error("请先创建房间")
        check(owner) { "只有主控可以选谱" }
        val body = BusinessJobs.io {
            val midi =
                EnsembleSources(app).read(song.id)
                    ?: song.remoteId
                        .takeIf { it.isNotBlank() }
                        ?.let {
                            PlatformClient(app).ensembleMidi(it)
                        }
            val payload =
                if (midi != null)
                    JSONObject()
                        .put("title", song.title)
                        .put(
                            "midi",
                            android.util.Base64.encodeToString(midi, android.util.Base64.NO_WRAP),
                        )
                else {
                    check(transpose == null || transpose == 0) { "这份谱子没有原始 MIDI，请将移调保持为自动" }
                    JSONObject(EnsembleScore.sameScore(song))
                }
            payload.put("speed", speed.toDouble())
            transpose?.let { payload.put("transpose", it) }
            payload.toString()
        }
        val hash = BusinessJobs.io { digest(body.toByteArray(Charsets.UTF_8)) }
        if (generation != roomGeneration) throw CancellationException("房间已改变")
        val permit = command("scoreAuthorize", JSONObject().put("sha256", hash)).getString("permit")
        val response = BusinessJobs.io {
            api.authenticatedBytes(
                "${BuildConfig.UPDATE_BASE_URL}/api/ensemble/v1/rooms/${room.getString("id")}/score",
                JSONObject().put("permit", permit).put("content", body).toString(),
            )
        }
        if (generation != roomGeneration) throw CancellationException("房间已改变")
        applyRoom(JSONObject(response.toString(Charsets.UTF_8)))
    }

    private fun self(room: JSONObject) =
        room.getJSONArray("members").let { members ->
            (0 until members.length())
                .map { members.getJSONObject(it) }
                .firstOrNull { it.getString("account") == mutable.value.account }
        }

    private fun clearRoom() {
        roomGeneration++
        stopLocal(false)
        pendingRoomEvent = null
        if (!suspended) bridge?.room(false, false)
        score = null
        if (!suspended) PlaybackBridge.current()?.command("ensembleLeave", Bundle())
        mutable.value = mutable.value.copy(room = null, ready = false)
    }

    private fun roomEvent(room: JSONObject) {
        val current = mutable.value.room
        if (current == null) {
            // 事件可能先于建房/加入回执抵达，只暂存，不能凭旧广播把用户拉回房间。
            val previous = pendingRoomEvent
            if (
                previous == null ||
                    previous.optString("id") != room.getString("id") ||
                    previous.getString("revision").toLong() < room.getString("revision").toLong()
            )
                pendingRoomEvent = room
        } else if (current.getString("id") == room.getString("id")) applyRoom(room)
    }

    private fun applyRoom(received: JSONObject) {
        if (closed || suspended) return
        val room =
            pendingRoomEvent?.takeIf {
                it.getString("id") == received.getString("id") &&
                    it.getString("revision").toLong() > received.getString("revision").toLong()
            } ?: received
        pendingRoomEvent = null
        val current = mutable.value.room
        if (
            current?.optString("id") == room.getString("id") &&
                current.getString("revision").toLong() > room.getString("revision").toLong()
        )
            return
        val me = self(room)
        if (room.optString("phase") == "closed" || me == null) {
            clearRoom()
            return
        }
        val hash = room.optJSONObject("score")?.optString("sha256").orEmpty()
        val trackHash =
            room
                .optJSONObject("score")
                ?.getJSONArray("tracks")
                ?.getJSONObject(me.getInt("track"))
                ?.getString("sha256")
                .orEmpty()
        if (
            prepared.isNotEmpty() &&
                (preparedHash != "$hash:$trackHash" ||
                    me.optJSONObject("ready")?.optString("token") != prepared)
        )
            stopLocal(false, reason = "房间准备令牌改变")
        if (
            current?.optString("phase") in setOf("playing", "proposed") &&
                room.optString("phase") in setOf("paused", "idle")
        )
            stopLocal(false)
        mutable.value = mutable.value.copy(room = room, ready = prepared.isNotEmpty())
        bridge?.room(
            true,
            prepared.isNotEmpty() ||
                preparingJob?.isActive == true ||
                room.optString("phase") in setOf("playing", "proposed", "paused"),
        )
        handlePlan(room)
    }

    fun toggle() {
        if (!active || suspended) return
        val playback = PlaybackBridge.current()?.query("ensemble")
        if (
            playback?.getBoolean("playing") == true ||
                preparingJob?.isActive == true ||
                waitingToStart
        ) {
            stopLocal(true, owner)
            return
        }
        if (prepared.isEmpty()) prepare()
        else if (owner) {
            val action = mutable.value.room?.let(::ensembleOwnerStartAction) ?: return
            control(action, mutable.value.account.takeIf { action == "rejoin" })
        } else stopLocal(true)
    }

    fun prepare() {
        if (closed || suspended || preparingJob?.isActive == true) return
        val generation = ++preparationGeneration
        mutable.value = mutable.value.copy(preparing = true, message = "")
        preparingJob = scope.launch {
            try {
                val room = mutable.value.room ?: error("请先加入房间")
                val selected = self(room) ?: error("你已退出房间")
                val descriptor = room.optJSONObject("score") ?: error("请先选择曲目")
                val hash = descriptor.getString("sha256")
                bridge?.room(true, true)
                val cached =
                    score?.takeIf { it.hash == hash }
                        ?: BusinessJobs.io {
                                EnsembleScore.read(
                                    api.authenticatedBytes(
                                        "${BuildConfig.UPDATE_BASE_URL}/api/ensemble/v1/rooms/${room.getString("id")}/score"
                                    ),
                                    hash,
                                )
                            }
                            .also { score = it }
                val part = cached.parts[selected.getInt("track")]
                val token = UUID.randomUUID().toString()
                val playback = PlaybackBridge.current() ?: error("请先开启悬浮窗并进入游戏")
                val positionUs =
                    if (room.getString("phase") == "paused") room.optLong("positionUs") else 0L
                playback.command(
                    "ensemblePrepare",
                    Bundle().apply {
                        putString("token", token)
                        putString("hash", hash)
                        putString("title", cached.title)
                        putString("events", part.events)
                        putLong("durationUs", cached.durationUs)
                        putLong("positionUs", positionUs)
                    },
                )
                val deadline = SystemClock.elapsedRealtime() + 20_000
                while (true) {
                    val input = playback.query("ensemble")
                    if (
                        input.getString("token") == token &&
                            input.getBoolean("ready") &&
                            bridge?.clock()?.getBoolean("ready") == true
                    )
                        break
                    input.getString("error")?.takeIf { it.isNotBlank() }?.let { error(it) }
                    check(SystemClock.elapsedRealtime() < deadline) { "输入或校时未就绪，请重试" }
                    delay(50)
                }
                val clock = checkNotNull(bridge).clock()
                val ready =
                    JSONObject()
                        .put("token", token)
                        .put("scoreHash", hash)
                        .put("trackHash", part.hash)
                        .put("uncertaintyUs", clock.getLong("uncertaintyUs"))
                        .put("roundTripUs", clock.getLong("uncertaintyUs") * 2)
                val response = command("ready", JSONObject().put("ready", ready))
                prepared = token
                preparedHash = "$hash:${part.hash}"
                applyRoom(response)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                stopLocal(false)
                message(failure.message ?: "准备失败")
            } finally {
                if (generation == preparationGeneration)
                    mutable.value = mutable.value.copy(preparing = false)
            }
        }
    }

    private fun handlePlan(room: JSONObject) {
        val plan = room.optJSONObject("plan") ?: return
        if (prepared.isEmpty()) return
        val participants = plan.getJSONArray("participants")
        val mine =
            (0 until participants.length())
                .map { participants.getJSONObject(it) }
                .firstOrNull {
                    it.getString("account") == mutable.value.account &&
                        it.getString("token") == prepared
                } ?: return
        val id = plan.getString("id")
        if (!plan.getBoolean("committed")) {
            if (lastProposal == id) return
            lastProposal = id
            bridge?.prepareRun(plan.getString("run"))
            scope.launch {
                runCatching {
                    val playback = PlaybackBridge.current() ?: error("输入会话已结束")
                    playback.command(
                        "ensembleCue",
                        Bundle().apply {
                            putLong("positionUs", plan.getString("positionUs").toLong())
                        },
                    )
                    val deadline = SystemClock.elapsedRealtime() + 1000
                    while (!playback.query("ensemble").getBoolean("ready")) {
                        check(
                            prepared == mine.getString("token") &&
                                SystemClock.elapsedRealtime() < deadline
                        ) {
                            "调式准备超时，请重新开始"
                        }
                        delay(20)
                    }
                    check(prepared == mine.getString("token")) { "准备已取消" }
                    applyRoom(
                        command(
                            "ack",
                            JSONObject().put("planId", id).put("token", mine.getString("token")),
                        )
                    )
                }
                    .onFailure { if (it !is CancellationException) message(it.message ?: "开始确认失败") }
            }
            return
        }
        if (lastCommit == id || lastProposal != id) return
        lastCommit = id
        val token = prepared
        val executeAt = plan.getString("executeAtUs").toLong()
        val clock = bridge?.clock() ?: return
        val arguments =
            Bundle().apply {
                putString("id", id)
                putString("runId", plan.getString("run"))
                putLong("revision", room.getString("revision").toLong())
                putString("clockEpoch", clock.getString("clockEpoch"))
                putLong("executeAtUs", executeAt)
                putLong("expiresAtUs", executeAt + 250_000)
            }
        scheduled?.close()
        scheduled =
            bridge.schedule(
                arguments,
                { localStartUs ->
                    val playback = PlaybackBridge.current() ?: error("输入会话已结束")
                    check(prepared == token && playback.query("ensemble").getBoolean("ready"))
                    playback.command(
                        "ensembleStart",
                        Bundle().apply {
                            putString("token", token)
                            putLong("positionUs", plan.getString("positionUs").toLong())
                            putLong("localStartUs", localStartUs)
                        },
                    )
                    check(playback.query("ensemble").getBoolean("playing")) { "输入准备已失效" }
                    runStartUs = executeAt
                    runClockEpoch = clock.getString("clockEpoch").orEmpty()
                    sampledAtUs = clock.getLong("sampledAtUs")
                },
            ) { success, result ->
                if (!success) {
                    val reason = runCatching { JSONObject(result) }.getOrNull()
                    AppLog.w(
                        "多人合奏",
                        "预约执行未完成：阶段=${reason?.optString("stage", "prepare")}，期限偏差=${reason?.optLong("lateUs", 0)}微秒",
                    )
                    stopLocal(true)
                    message("错过开始时间，请重新准备")
                } else
                    scope.launch {
                        runCatching {
                            applyRoom(
                                command(
                                    "executed",
                                    JSONObject().put("planId", id).put("token", token),
                                )
                            )
                        }
                            .onFailure {
                                if (
                                    it !is CancellationException &&
                                        prepared == token &&
                                        lastProposal == id &&
                                        lastCommit == id
                                ) {
                                    stopLocal(true, reason = "开始回执未确认")
                                    message("本机已停止，开始回执尚未确认，请重新同步")
                                }
                            }
                    }
            }
    }

    fun stopLocal(notify: Boolean, pauseRoom: Boolean = false, reason: String = "本机停止") {
        if (prepared.isNotEmpty() || runStartUs > 0) AppLog.d("多人合奏", "本机停止：$reason；通知房间=$notify")
        startCommand?.cancel()
        startCommand = null
        preparationGeneration++
        preparingJob?.cancel()
        preparingJob = null
        scheduled?.close()
        scheduled = null
        prepared = ""
        preparedHash = ""
        lastProposal = ""
        lastCommit = ""
        runStartUs = 0
        runClockEpoch = ""
        if (!suspended) {
            bridge?.stopScheduled()
            PlaybackBridge.current()?.command("ensembleStop", Bundle())
        }
        mutable.value = mutable.value.copy(ready = false, preparing = false)
        if (notify && active && !suspended)
            scope.launch {
                runCatching { applyRoom(command(if (owner && pauseRoom) "pause" else "stop")) }
                    .onFailure {
                        if (it !is CancellationException)
                            message(if (owner && pauseRoom) "本机已暂停，全员暂停尚未确认" else "本机已停止，房间状态待同步")
                    }
            }
        if (!suspended)
            bridge?.room(
                active,
                mutable.value.room?.optString("phase") in setOf("paused", "playing", "proposed"),
            )
    }

    val hasPriority
        get() =
            mutable.value.working ||
                prepared.isNotEmpty() ||
                preparingJob?.isActive == true ||
                mutable.value.room?.optString("phase") in setOf("proposed", "playing", "paused")

    fun suspendWork() {
        if (closed || suspended) return
        check(!hasPriority) { "合奏尚未空闲" }
        suspended = true
        observer?.close()
        observer = null
        syncJob?.cancel()
        syncJob = null
        bridge?.room(false, false)
    }

    fun resumeWork() {
        if (closed || !suspended) return
        suspended = false
        lastSync = 0
        observer = bridge?.observe(listener)
    }

    override fun close() {
        if (closed) return
        closed = true
        stopLocal(false)
        observer?.close()
        if (!suspended) bridge?.room(false, false)
        scope.cancel()
    }
}

internal object EnsembleSession {
    var current: EnsembleController? = null
        private set

    fun open(context: Context) {
        if (current == null) current = EnsembleController(context)
    }

    fun suspendWork() {
        current?.suspendWork()
    }

    fun resumeWork() {
        current?.resumeWork()
    }

    fun close() {
        current?.close()
        current = null
    }
}
