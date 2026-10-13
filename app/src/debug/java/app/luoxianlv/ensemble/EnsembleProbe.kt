package app.luoxianlv.ensemble

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import app.luoxianlv.BuildConfig
import app.luoxianlv.hot.contract.PlaybackBridge
import app.luoxianlv.hot.contract.PlaybackPort
import app.luoxianlv.hot.contract.SharedRealtime
import app.luoxianlv.library.Song
import app.luoxianlv.platform.AccountSession
import app.luoxianlv.platform.SessionStore
import kotlinx.coroutines.*

/** 真实业务协调器 + Rust 对端；仅把最后的系统触摸替换为计数器。 */
object EnsembleProbe {
    @JvmStatic
    fun verifyRetirement(context: Context, completion: SharedRealtime.Completion) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            val previous = SharedRealtime.current()
            val bridge = LifecycleBridge()
            val registration = SharedRealtime.connect(bridge)
            var old: EnsembleController? = null
            var current: EnsembleController? = null
            try {
                old = EnsembleController(context)
                check(bridge.observers == 1)
                old.suspendWork()
                check(bridge.observers == 0)
                old.resumeWork()
                check(bridge.observers == 1)
                old.suspendWork()
                current = EnsembleController(context)
                check(bridge.observers == 1)
                val before = bridge.controls
                old.close()
                check(bridge.controls == before && bridge.observers == 1) { "旧业务退役干扰新业务" }
                current.close()
                check(bridge.observers == 0)
                verifyRoomEvents(context)
                completion.complete(true, "合奏代次与房间竞态通过：冻结/恢复、旧代关闭隔离、迟到同步、先到事件、错房间及退房旧广播")
            } catch (failure: Throwable) {
                completion.complete(false, failure.toString())
            } finally {
                old?.close()
                current?.close()
                registration.close()
                if (previous != null) SharedRealtime.connect(previous)
                scope.cancel()
            }
        }
    }

    private open class LifecycleBridge : SharedRealtime.Bridge {
        var controls = 0
        var observers = 0
        val listeners = mutableListOf<SharedRealtime.Listener>()

        override fun state() = Bundle()

        override fun clock() = Bundle()

        override fun account(token: String) {}

        override fun room(active: Boolean, performing: Boolean) {
            controls++
        }

        override fun prepareRun(run: String) {
            controls++
        }

        override fun stopScheduled() {
            controls++
        }

        override fun observe(listener: SharedRealtime.Listener): AutoCloseable {
            observers++
            listeners.add(listener)
            listener.changed(state())
            val active = java.util.concurrent.atomic.AtomicBoolean(true)
            return AutoCloseable {
                if (active.compareAndSet(true, false)) {
                    observers--
                    listeners.remove(listener)
                }
            }
        }

        override fun send(
            id: String,
            topic: String,
            type: String,
            json: String,
            completion: SharedRealtime.Completion,
        ) = AutoCloseable {}

        override fun schedule(
            plan: Bundle,
            execute: SharedRealtime.Execution,
            completion: SharedRealtime.Completion,
        ) = AutoCloseable {}
    }

    private class RoomBridge : LifecycleBridge() {
        var resetEpoch = 0L
        @Volatile var pending: SharedRealtime.Completion? = null
        private val main = android.os.Handler(android.os.Looper.getMainLooper())

        override fun state() =
            Bundle().apply {
                putString("state", "connected")
                putBoolean("connected", true)
                putString("connectionEpoch", "1")
                putString("accountId", "phone")
                putBoolean("ensembleEnabled", true)
                putLong("accountResetEpoch", resetEpoch)
            }

        fun room(id: String, revision: Int, closed: Boolean = false) =
            org.json
                .JSONObject()
                .put("id", id)
                .put("revision", revision.toString())
                .put("owner", "phone")
                .put("phase", if (closed) "closed" else "idle")
                .put(
                    "members",
                    org.json.JSONArray().apply {
                        if (!closed)
                            put(org.json.JSONObject().put("account", "phone").put("track", 0))
                    },
                )

        fun emit(room: org.json.JSONObject) {
            val message = org.json.JSONObject().put("data", org.json.JSONObject().put("room", room))
            listeners.toList().forEach { it.message("ensemble", "changed", message.toString()) }
        }

        override fun send(
            id: String,
            topic: String,
            type: String,
            json: String,
            completion: SharedRealtime.Completion,
        ): AutoCloseable {
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            main.post {
                if (!cancelled.get())
                    when (type) {
                        "current" -> pending = completion
                        "create" -> {
                            emit(room("room-a", 2))
                            completion.complete(true, room("room-a", 1).toString())
                        }
                        "join" -> completion.complete(true, room("room-a", 4).toString())
                        "leave" -> completion.complete(true, "{}")
                        else -> completion.complete(false, "{}")
                    }
            }
            return AutoCloseable { cancelled.set(true) }
        }
    }

    private suspend fun verifyRoomEvents(context: Context) {
        val previous = SharedRealtime.current()
        val bridge = RoomBridge()
        val lease = SharedRealtime.connect(bridge)
        val controller = EnsembleController(context)
        try {
            await("初始同步") { bridge.pending != null }
            controller.create()
            await("先到事件与建房回执合并") {
                controller.state.value.room?.optString("revision") == "2" &&
                    !controller.state.value.working
            }
            bridge.pending!!.complete(true, bridge.room("stale-other", 10).toString())
            delay(100)
            check(controller.state.value.room?.optString("id") == "room-a") { "迟到同步覆盖了新房间" }
            bridge.emit(bridge.room("other", 99, true))
            bridge.emit(bridge.room("room-a", 1, true))
            check(controller.active) { "错房间或过期退出事件清除了当前房间" }
            controller.leave()
            await("主动退房") { !controller.active && !controller.state.value.working }
            bridge.emit(bridge.room("room-a", 2))
            check(!controller.active) { "退房后被旧广播重新拉入" }
            controller.join("123456")
            await("重新加入") {
                controller.state.value.room?.optString("revision") == "4" &&
                    !controller.state.value.working
            }
            bridge.emit(bridge.room("room-a", 3, true))
            check(controller.active) { "重新加入后应用了旧退出事件" }
            bridge.resetEpoch++
            bridge.listeners.toList().forEach { it.changed(bridge.state()) }
            check(!controller.active) { "退出后快速换号仍保留旧房间" }
        } finally {
            controller.close()
            lease.close()
            if (previous != null) SharedRealtime.connect(previous)
        }
    }

    @JvmStatic
    fun verify(context: Context, token: String, completion: SharedRealtime.Completion) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            val sessions = SessionStore(context)
            var signedIn = false
            val previous = PlaybackBridge.current()
            var lease: AutoCloseable? = null
            val input = Input()
            val controller = EnsembleSession.current
            var passed = false
            var result = ""
            try {
                check(
                    BuildConfig.UPDATE_BASE_URL == "http://127.0.0.1:18474" &&
                        context.packageName.endsWith(".debug")
                )
                check(sessions.current() == null) { "测试不会覆盖已有登录，请使用空白模拟器" }
                check(previous?.query("state")?.getBoolean("playing") != true)
                check(controller != null && !controller.active)
                lease = PlaybackBridge.connect(input)
                sessions.save(
                    AccountSession(token, "", "联调手机", System.currentTimeMillis() + 3_000_000)
                )
                signedIn = true
                await("账号授权") { controller.state.value.account == "phone" }
                controller.create()
                await("创建房间") {
                    controller.state.value.room != null && !controller.state.value.working
                }
                await("参与者加入") {
                    controller.state.value.room!!.getJSONArray("members").length() == 2
                }
                controller.choose(Song("ensemble-probe", "合奏功能联调", "1 2 3 4 5 6 7 1", 120, "测试"))
                await("选谱上传") {
                    controller.state.value.room?.optJSONObject("score") != null &&
                        !controller.state.value.working
                }
                controller.prepare()
                await("输入准备") { controller.state.value.ready }
                await("全员就绪") {
                    val members = controller.state.value.room!!.getJSONArray("members")
                    (0 until members.length()).all {
                        members.getJSONObject(it).optJSONObject("ready") != null
                    }
                }
                check(input.starts == 0) { "准备阶段提前开始" }
                controller.control("start")
                await("倒计时") {
                    controller.waitingToStart &&
                        controller.state.value.room
                            ?.optJSONObject("plan")
                            ?.optBoolean("committed") == true
                }
                controller.toggle()
                check(!input.playing && input.starts == 0) { "取消倒计时未立即停止" }
                await("取消计划") { controller.state.value.room?.optString("phase") == "paused" }
                delay(3000)
                check(input.starts == 0) { "取消后旧计划重新开始" }
                controller.prepare()
                await("重新准备") {
                    controller.state.value.ready &&
                        controller.state.value.room!!.getJSONArray("members").let { members ->
                            (0 until members.length()).all {
                                members.getJSONObject(it).optJSONObject("ready") != null
                            }
                        }
                }
                controller.control("resume")
                await("预约执行") { input.playing }
                check(
                    input.starts == 1 &&
                        input.actualUs >= input.startUs &&
                        input.actualUs - input.startUs <= 50_000
                ) {
                    "没有按单调时钟执行"
                }
                await("执行回执") {
                    val members = controller.state.value.room!!.getJSONArray("members")
                    (0 until members.length()).any {
                        members.getJSONObject(it).optString("account") == "phone" &&
                            members.getJSONObject(it).optBoolean("playing")
                    }
                }
                controller.control("pause")
                check(!input.playing) { "本机暂停等待了网络" }
                await("全员暂停") {
                    controller.state.value.room?.optString("phase") == "paused" &&
                        !controller.state.value.working
                }
                controller.leave()
                await("退出房间") { !controller.active && !controller.state.value.working }
                delay(200)
                check(input.starts == 1 && !input.playing)
                passed = true
                result = "Rust/Android 合奏联调通过：登录授权、建房、两人就绪、Ktor 选谱/验包、提交开始、执行回执、即时暂停及退房；受控时钟和模拟输入"
            } catch (failure: Throwable) {
                result = failure.toString() + "；" + controller?.state?.value?.message
            } finally {
                controller?.stopLocal(false)
                lease?.close()
                if (previous != null) PlaybackBridge.connect(previous)
                if (signedIn) sessions.clear()
                scope.cancel()
            }
            completion.complete(passed, result)
        }
    }

    private suspend fun await(stage: String, test: () -> Boolean) =
        try {
            withTimeout(25000) { while (!test()) delay(20) }
        } catch (failure: TimeoutCancellationException) {
            error("等待$stage 超时")
        }

    private class Input : PlaybackPort {
        var token = ""
        var ready = false
        var playing = false
        var starts = 0
        var startUs = 0L
        var actualUs = 0L

        override fun query(kind: String) =
            Bundle().apply {
                putString("token", token)
                putBoolean("ready", ready)
                putBoolean("playing", playing)
                putLong("localStartUs", startUs)
            }

        override fun command(action: String, args: Bundle) {
            when (action) {
                "ensemblePrepare" -> {
                    token = args.getString("token").orEmpty()
                    ready = true
                    playing = false
                }
                "ensembleCue" -> {
                    check(token.isNotEmpty())
                    ready = true
                }
                "ensembleStart" -> {
                    check(ready && args.getString("token") == token)
                    startUs = args.getLong("localStartUs")
                    actualUs = SystemClock.elapsedRealtimeNanos() / 1000
                    playing = true
                    starts++
                }
                "ensembleStop",
                "ensembleLeave" -> {
                    token = ""
                    ready = false
                    playing = false
                }
                "ensembleClock" -> startUs += args.getLong("correctionUs")
            }
        }
    }
}
