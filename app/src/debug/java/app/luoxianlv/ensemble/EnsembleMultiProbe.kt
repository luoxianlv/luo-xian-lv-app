package app.luoxianlv.ensemble

import android.content.Context
import app.luoxianlv.BuildConfig
import app.luoxianlv.diagnostics.AppLog
import app.luoxianlv.hot.contract.PlaybackBridge
import app.luoxianlv.hot.contract.SharedRealtime
import app.luoxianlv.library.Song
import app.luoxianlv.platform.AccountSession
import app.luoxianlv.platform.SessionStore
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject

/** 六个真实模拟器中的业务控制入口；只读取本机测试指令，不替换 WS 或校时时钟。 */
object EnsembleMultiProbe {
    @JvmStatic
    fun run(context: Context, config: String, completion: SharedRealtime.Completion) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            val configuration = JSONObject(config)
            val directory = File(context.filesDir, "ensemble-multi")
            val control = File(directory, "control.json")
            val report = File(directory, "state.json")
            val sessions = SessionStore(context)
            val controller = EnsembleSession.current
            var signedIn = false
            var lastCommand = ""
            var failure = ""
            var success = false
            var cancelOnCommit = false
            var cancelledPlan: JSONObject? = null
            var cancelledAtUs = 0L
            val history = org.json.JSONArray()
            var lastHistory = ""
            try {
                check(
                    context.packageName.endsWith(".debug") &&
                        BuildConfig.UPDATE_BASE_URL == "http://127.0.0.1:18474"
                )
                check(sessions.current() == null) { "测试不会覆盖现有账号" }
                check(controller != null && !controller.active)
                check(directory.isDirectory)
                AppLog.init(context)
                sessions.save(
                    AccountSession(
                        configuration.getString("token"),
                        "",
                        "模拟器",
                        System.currentTimeMillis() + 3_000_000,
                    )
                )
                signedIn = true
                val deadline = android.os.SystemClock.elapsedRealtime() + 1_500_000
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    val command =
                        withContext(Dispatchers.IO) {
                            if (!control.isFile) null
                            else runCatching { JSONObject(control.readText()) }.getOrNull()
                        }
                    if (command != null && command.optString("id") != lastCommand) {
                        lastCommand = command.getString("id")
                        when (command.getString("action")) {
                            "create" -> controller.create()
                            "join" -> controller.join(command.getString("code"))
                            "choose" ->
                                controller.choose(
                                    Song(
                                        "multi-ensemble",
                                        "六模拟器十分钟同谱",
                                        "1 ".repeat(1320),
                                        120,
                                        "本机测试",
                                    )
                                )
                            "prepare" -> controller.prepare()
                            "start",
                            "pause",
                            "resume",
                            "restart" -> controller.control(command.getString("action"))
                            "startAndCancel" -> {
                                cancelOnCommit = true
                                controller.control("start")
                            }
                            "cancel" -> controller.toggle()
                            "stop" -> controller.stopLocal(true)
                            "rejoin" -> controller.control("rejoin", command.getString("account"))
                            "leave" -> controller.leave()
                            "finish" -> {
                                success = true
                                break
                            }
                            else -> error("未知模拟器指令")
                        }
                    }
                    val state = controller.state.value
                    if (
                        cancelOnCommit &&
                            state.room?.optJSONObject("plan")?.optBoolean("committed") == true
                    ) {
                        cancelledPlan = JSONObject(state.room.getJSONObject("plan").toString())
                        cancelledAtUs =
                            android.os.SystemClock.elapsedRealtimeNanos() / 1000 +
                                SharedRealtime.current()!!.clock().getLong("offsetUs")
                        check(
                            cancelledPlan.getString("executeAtUs").toLong() - cancelledAtUs >=
                                500_000
                        ) {
                            "夹具取消来不及到达，不能当成取消验证"
                        }
                        cancelOnCommit = false
                        controller.toggle()
                    }
                    val connection = SharedRealtime.current()?.state()
                    val clock = SharedRealtime.current()?.clock()
                    val input = PlaybackBridge.current()?.query("ensemble")
                    val snapshot =
                        JSONObject()
                            .put("index", configuration.getInt("index"))
                            .put("account", state.account)
                            .put("connection", state.connection)
                            .put("epoch", connection?.getString("connectionEpoch").orEmpty())
                            .put("ready", state.ready)
                            .put("working", state.working)
                            .put("message", state.message)
                            .put("room", state.room ?: JSONObject.NULL)
                            .put("waiting", controller.waitingToStart)
                            .put("lastCommand", lastCommand)
                            .put("playing", input?.getBoolean("playing") ?: false)
                            .put("inputReady", input?.getBoolean("ready") ?: false)
                            .put("inputError", input?.getString("error").orEmpty())
                            .put("clockReady", clock?.getBoolean("ready") ?: false)
                            .put("offsetUs", clock?.getLong("offsetUs") ?: 0)
                            .put("uncertaintyUs", clock?.getLong("uncertaintyUs") ?: 0)
                            .put("cancelledPlan", cancelledPlan ?: JSONObject.NULL)
                            .put("cancelledAtUs", cancelledAtUs)
                    val moment =
                        JSONObject()
                            .put("ready", state.ready)
                            .put("inputReady", input?.getBoolean("ready") ?: false)
                            .put("inputError", input?.getString("error").orEmpty())
                            .put("message", state.message)
                            .put("connection", state.connection)
                            .put("epoch", connection?.getString("connectionEpoch"))
                            .put("clockReady", clock?.getBoolean("ready") ?: false)
                            .put("playing", input?.getBoolean("playing") ?: false)
                            .put("phase", state.room?.optString("phase"))
                            .put("revision", state.room?.optString("revision"))
                    val changed = moment.toString() != lastHistory
                    if (changed) {
                        lastHistory = moment.toString()
                        moment.put("localUs", android.os.SystemClock.elapsedRealtimeNanos() / 1000)
                        if (history.length() == 200) history.remove(0)
                        history.put(moment)
                    }
                    withContext(Dispatchers.IO) {
                        val temporary = File(directory, "state.tmp")
                        temporary.writeText(snapshot.toString(), Charsets.UTF_8)
                        check(temporary.renameTo(report))
                        if (changed)
                            File(directory, "history.json")
                                .writeText(history.toString(), Charsets.UTF_8)
                    }
                    delay(100)
                }
                check(success) { "多模拟器测试未收到结束指令" }
            } catch (error: Throwable) {
                failure = error.toString() + "；" + controller?.state?.value?.message
            } finally {
                controller?.stopLocal(false)
                if (signedIn) sessions.clear()
                scope.cancel()
            }
            completion.complete(success, if (success) "多模拟器业务控制完成" else failure)
        }
    }
}
