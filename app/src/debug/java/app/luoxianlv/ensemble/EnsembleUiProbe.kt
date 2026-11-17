package app.luoxianlv.ensemble

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.findViewTreeLifecycleOwner
import app.luoxianlv.BuildConfig
import app.luoxianlv.business.ui.ComposePage
import app.luoxianlv.hot.contract.NativePage
import app.luoxianlv.ui.theme.GradientBackdrop
import app.luoxianlv.ui.theme.LuoXianLvTheme
import org.json.JSONArray
import org.json.JSONObject

/** 只向独立 ComposePage 注入展示快照，不读取或更改账号、房间控制器和共享实时连接。 */
@SuppressLint("VisibleForTests") // Debug-only bridge for the Java instrumentation runner.
object EnsembleUiProbe {
    private val mounted = mutableMapOf<View, ProbePage>()

    @JvmStatic
    fun render(context: Context, activity: Activity, scenario: String, dark: Boolean): View {
        mainThread()
        check(BuildConfig.INTERNAL_BUILD && context.packageName.endsWith(".debug"))
        check(mounted.isEmpty()) { "上一个界面快照尚未清理" }
        val page = ProbePage(scenario, dark)
        val root = page.create(context, Bundle(), Bundle(), { _, _ -> }, {})
        val parent = activity.findViewById<ViewGroup>(android.R.id.content)
        parent.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        mounted[root] = page
        page.lifecycle(NativePage.RESUMED)
        return root
    }

    /** 选谱弹窗的实际曲库读取完成后再采样，避免只捕获加载动画。 */
    @JvmStatic
    fun ready(view: View): Boolean {
        mainThread()
        val page = checkNotNull(mounted[view])
        if (page.scenario != "scorePicker") return true
        val labels = nodes(view).flatMap(::labels)
        return "选择曲目" in labels && "正在读取曲库" !in labels
    }

    /** 首屏检查；所有动作只进入本探针的计数器。 */
    @JvmStatic
    fun verify(view: View) {
        mainThread()
        val page = checkNotNull(mounted[view])
        val current = nodes(view)
        check(view.width > 0 && view.height > 0 && current.isNotEmpty()) { "界面尚未完成布局" }
        val labels = current.flatMap(::labels)
        if (page.scenario == "scorePicker") {
            check("选择曲目" in labels && "搜索本地曲目" in labels) { "选谱弹窗节点不完整" }
            check("曲库读取失败" !in labels) { "选谱弹窗未能读取本地曲库" }
            val search = current.single { it.config.contains(SemanticsActions.SetText) }
            check(!search.config.contains(SemanticsProperties.Disabled)) { "选谱搜索框不可编辑" }
            enabled(view, "取消", true)
            enabled(view, "使用曲目", false)
            page.checked += "picker:initial"
            inspect(view)
            return
        }
        check("多人合奏" in labels) { "缺少多人合奏页面标题：$labels" }
        when (page.scenario) {
            "signedOut" -> {
                click(view, "登录")
                check(page.calls["login"] == 1)
            }
            "unsupported" -> check(labels.any { "尚未开放" in it })
            "connecting" -> check(labels.any { "连接" in it })
            "unauthorized" -> {
                check("暂时无法连接合奏服务" in labels)
                check("登录" !in labels) { "服务拒绝连接时不应要求重新登录" }
            }
            "serviceUnavailable" -> {
                check("合奏服务尚未开放" in labels)
                check("连接中断，正在重连" !in labels && "登录" !in labels) {
                    "服务尚未开放时不应提示重连或重新登录"
                }
                check(
                    current.none { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }
                ) {
                    "服务尚未开放时不应显示重连进度"
                }
                page.checked += "serviceUnavailable"
            }
        }
        click(view, "返回")
        check(page.calls["back"] == 1)
        inspect(view)
    }

    /** 文本输入的重组提交后，检查可编辑大厅的六位房间码加入操作。 */
    @JvmStatic
    fun verifyInput(view: View) {
        mainThread()
        val page = checkNotNull(mounted[view])
        if (page.scenario != "lobby" || "input" !in page.checked || "join" in page.checked) return
        enabled(view, "加入房间", true)
        click(view, "加入房间")
        check(page.calls["join:438216"] == 1) { "房间码没有传入加入操作" }
        page.checked += "join"
    }

    /** 用公共滚动语义覆盖 LazyColumn 后续卡片，由仪器在两次调用之间等待实际帧。 */
    @JvmStatic
    fun advance(view: View): Boolean {
        mainThread()
        inspect(view)
        val page = checkNotNull(mounted[view])
        if (page.scenario == "scorePicker") return false
        if ("input" in page.checked && "join" !in page.checked) return true
        val scroll =
            nodes(view).singleOrNull {
                it.config.contains(SemanticsActions.ScrollBy) &&
                    it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
            } ?: return false
        val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
        if (range.value() >= range.maxValue()) return false
        return scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, view.height * 0.65f) ==
            true
    }

    @JvmStatic
    fun finish(view: View): String {
        mainThread()
        inspect(view)
        val page = checkNotNull(mounted[view])
        if (page.scenario == "scorePicker") {
            check("picker:initial" in page.checked)
            check(page.calls["scoreSelect"] == null) { "选谱检查不得提交曲目" }
            click(view, "取消")
            check(page.calls["scoreDismiss"] == 1)
            page.checked += "picker:cancel"
        } else if (
            page.scenario in
                setOf(
                    "signedOut",
                    "unsupported",
                    "connecting",
                    "unauthorized",
                    "serviceUnavailable",
                    "lobby",
                )
        ) {
            check("create" in page.checked && "joinDisabled" in page.checked) { "没有覆盖大厅操作" }
            if (page.scenario == "lobby") check("join" in page.checked) { "没有覆盖六位房间码加入操作" }
            if (page.scenario == "serviceUnavailable") {
                check("serviceUnavailable" in page.checked && "inputDisabled" in page.checked) {
                    "没有覆盖服务尚未开放及输入禁用状态"
                }
                check("登录" !in page.labels && page.calls["login"] == null)
                check(
                    page.calls["create"] == null && page.calls.keys.none { it.startsWith("join:") }
                )
            }
            if (page.scenario == "unauthorized")
                check("登录" !in page.labels && page.calls["login"] == null)
        } else {
            check(page.labels.any { it.replace(" ", "").contains("438216") }) { "缺少房间码" }
            if (page.scenario == "emptyRoom") {
                check("还未选择曲目" in page.labels && "1/6" in page.labels) { "新房间缺少无谱或人数状态" }
                check(page.labels.none { it in setOf("天空之城", "清和", "晚风") }) { "新房间显示了不存在的曲目或成员" }
            } else check(page.labels.any { it.contains("天空之城") }) { "缺少曲目信息" }
            check(page.labels.any { it.contains("弦音") }) { "缺少房间成员" }
            expectedButtons(page.scenario).forEach { (label, _) ->
                check("button:$label" in page.checked) { "没有覆盖操作：$label" }
            }
            if (page.scenario == "member") {
                check(page.labels.none { it in setOf("选择曲目", "开始合奏", "全员暂停", "结束房间") }) {
                    "成员获得了主控操作"
                }
            }
            if (page.scenario in setOf("finished", "finishedReady")) {
                check("已结束" in page.labels && "开始合奏" !in page.labels) { "自然结束后仍提供初次开始操作" }
                check(page.calls["control:start:"] == null)
                if (page.scenario == "finishedReady") check(page.calls["control:restart:"] == 1)
                else {
                    check(page.calls["prepare"] == 1 && page.calls["control:restart:"] == null) {
                        "自然结束后应先重新准备，不能直接重新开始"
                    }
                }
            }
            if (page.scenario == "rejoin") {
                check(page.calls["control:rejoin:fixture-owner"] == 1) { "主控重接入未携带本人的账号" }
            }
            if (page.scenario in setOf("scheduledStart", "pendingRejoin")) {
                val waiting = if (page.scenario == "scheduledStart") "等待同步开始" else "等待接入"
                check(waiting in page.labels) { "预约尚未执行时缺少等待状态：$waiting" }
                check(
                    "重新接入合奏" !in page.labels &&
                        page.calls.keys.none { it.startsWith("control:rejoin:") }
                ) {
                    "等待中的预约被误当成可以重新接入"
                }
            }
        }
        return JSONObject()
            .put("scenario", page.scenario)
            .put("theme", if (page.dark) "dark" else "light")
            .put("passed", true)
            .put("frames", JSONArray(page.frames))
            .put("checks", JSONArray(page.checked.toList()))
            .put("actions", JSONObject(page.calls.toMap()))
            .toString()
    }

    private fun inspect(view: View) {
        val page = checkNotNull(mounted[view])
        val current = nodes(view)
        page.frames += snapshot(current)
        page.labels += current.flatMap(::labels)
        val buttons = current.filter { it.config.contains(SemanticsActions.OnClick) }
        if (buttons.any { "创建房间" in labels(it) } && page.checked.add("create")) {
            enabled(view, "创建房间", page.scenario == "lobby")
            if (page.scenario == "lobby") {
                click(view, "创建房间")
                check(page.calls["create"] == 1)
            }
        }
        if (buttons.any { "加入房间" in labels(it) } && page.checked.add("joinDisabled")) {
            enabled(view, "加入房间", false)
            if (page.scenario == "lobby") {
                val input = current.single { it.config.contains(SemanticsActions.SetText) }
                check(
                    input.config[SemanticsActions.SetText]
                        .action
                        ?.invoke(AnnotatedString("438216")) == true
                )
                page.checked += "input"
            } else if (page.scenario == "serviceUnavailable") {
                check(
                    current.none {
                        it.config.contains(SemanticsActions.SetText) &&
                            !it.config.contains(SemanticsProperties.Disabled)
                    }
                ) {
                    "服务尚未开放时不应提供可编辑的房间码输入"
                }
                page.checked += "inputDisabled"
            }
        }
        expectedButtons(page.scenario).forEach { (label, expected) ->
            if (buttons.any { label in labels(it) } && page.checked.add("button:$label")) {
                enabled(view, label, expected)
                val callback = callbackFor(label)
                if (expected && callback != null) {
                    click(view, label)
                    check(page.calls[callback] == 1) { "$label 没有执行预期动作 $callback" }
                }
            }
        }
    }

    private fun expectedButtons(scenario: String): Map<String, Boolean> {
        if (
            scenario in
                setOf(
                    "signedOut",
                    "unsupported",
                    "connecting",
                    "unauthorized",
                    "serviceUnavailable",
                    "lobby",
                    "scorePicker",
                )
        )
            return emptyMap()
        return buildMap {
            put("重新同步", scenario != "disconnected")
            put("启动悬浮窗", scenario != "preparing")
            if (scenario == "member") {
                put("准备演奏", true)
                put("退出房间", true)
            } else {
                put(
                    "选择曲目",
                    scenario in setOf("owner", "paused", "finished", "finishedReady", "emptyRoom"),
                )
                put("结束房间", scenario != "disconnected")
                put(
                    when (scenario) {
                        "preparing" -> "取消准备"
                        "playing",
                        "disconnected" -> "停止我的演奏"
                        "finished",
                        "emptyRoom" -> "准备演奏"
                        else -> "取消我的准备"
                    },
                    scenario != "emptyRoom",
                )
                when (scenario) {
                    "playing",
                    "disconnected",
                    "scheduledStart",
                    "pendingRejoin" -> put("全员暂停", true)
                    "rejoin" -> {
                        put("重新接入合奏", true)
                        put("全员暂停", true)
                    }
                    "finished",
                    "finishedReady" -> put("重新开始", scenario == "finishedReady")
                    "proposed" -> put("取消开始", true)
                    "paused" -> {
                        put("继续合奏", true)
                        put("重新开始", true)
                    }
                    else -> put("开始合奏", scenario == "owner")
                }
            }
        }
    }

    private fun callbackFor(label: String): String? =
        when (label) {
            "重新同步" -> "synchronize"
            "启动悬浮窗" -> "inputSetup"
            "选择曲目" -> "selectScore"
            "准备演奏" -> "prepare"
            "取消准备",
            "取消我的准备",
            "停止我的演奏" -> "stop"
            "开始合奏" -> "control:start:"
            "继续合奏" -> "control:resume:"
            "重新开始" -> "control:restart:"
            "重新接入合奏" -> "control:rejoin:fixture-owner"
            "取消开始",
            "全员暂停" -> "control:pause:"
            else -> null
        }

    @JvmStatic
    fun close(view: View) {
        mainThread()
        val page = mounted.remove(view) ?: return
        page.lifecycle(NativePage.CREATED)
        (view.parent as? ViewGroup)?.removeView(view)
        page.close()
    }

    private fun mainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "界面探针必须在主线程执行" }
    }

    private fun roots(view: View): List<ViewRootForTest> = buildList {
        if (view is ViewRootForTest) add(view)
        if (view is ViewGroup) repeat(view.childCount) { addAll(roots(view.getChildAt(it))) }
    }

    private fun nodes(view: View): List<SemanticsNode> {
        val page = checkNotNull(mounted[view])
        val activeRoots =
            if (page.scenario == "scorePicker") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    WindowInspector.getGlobalWindowViews().flatMap(::roots).filter {
                        it.view.findViewTreeLifecycleOwner() === page
                    }
                else error("选谱弹窗探针需要 Android 10 的公共窗口检查 API")
            } else roots(view)
        return activeRoots.distinct().flatMap { root ->
            root.measureAndLayoutForTest()
            root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
        }
    }

    private fun labels(node: SemanticsNode): List<String> = buildList {
        node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.forEach { add(it.text) }
        node.config
            .getOrElseNullable(SemanticsProperties.ContentDescription) { null }
            ?.let(::addAll)
    }

    private fun action(view: View, label: String): SemanticsNode =
        nodes(view).singleOrNull {
            label in labels(it) && it.config.contains(SemanticsActions.OnClick)
        } ?: error("没有唯一的可点击节点：$label")

    private fun enabled(view: View, label: String, expected: Boolean) {
        val enabled = !action(view, label).config.contains(SemanticsProperties.Disabled)
        check(enabled == expected) { "$label 的 enabled=$enabled，预期 $expected" }
    }

    private fun click(view: View, label: String) {
        enabled(view, label, true)
        check(action(view, label).config[SemanticsActions.OnClick].action?.invoke() == true) {
            "$label 未执行点击动作"
        }
    }

    private fun snapshot(nodes: List<SemanticsNode>) =
        JSONArray().apply {
            nodes.forEach { node ->
                val text = labels(node)
                if (text.isNotEmpty()) {
                    put(
                        JSONObject()
                            .put("labels", JSONArray(text))
                            .put("enabled", !node.config.contains(SemanticsProperties.Disabled))
                            .put("click", node.config.contains(SemanticsActions.OnClick))
                            .put("bounds", node.boundsInRoot.toString())
                    )
                }
            }
        }

    private class ProbePage(val scenario: String, val dark: Boolean) : ComposePage() {
        val calls = mutableMapOf<String, Int>()
        val checked = mutableSetOf<String>()
        val labels = mutableSetOf<String>()
        val frames = mutableListOf<JSONArray>()

        private fun record(action: String) {
            calls[action] = calls.getOrDefault(action, 0) + 1
        }

        private val actions =
            EnsembleActions(
                back = { record("back") },
                login = { record("login") },
                inputSetup = { record("inputSetup") },
                create = { record("create") },
                join = { record("join:$it") },
                selectScore = { record("selectScore") },
                assign = { account, track -> record("assign:$account:$track") },
                control = { command, account -> record("control:$command:${account.orEmpty()}") },
                prepare = { record("prepare") },
                stop = { record("stop") },
                synchronize = { record("synchronize") },
                leave = { record("leave") },
            )

        private val state = fixture(scenario)

        @Composable
        override fun Content() {
            LuoXianLvTheme(darkTheme = dark, applySystemBars = false) {
                Box(Modifier.fillMaxSize()) {
                    GradientBackdrop()
                    Scaffold(containerColor = Color.Transparent) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding)) {
                            if (scenario == "scorePicker") {
                                EnsembleScorePicker(
                                    onDismiss = { record("scoreDismiss") },
                                    onSelect = { _, _, _ -> record("scoreSelect") },
                                )
                            } else EnsemblePage(state, signedIn = scenario != "signedOut", actions)
                        }
                    }
                }
            }
        }
    }

    private fun fixture(scenario: String): EnsembleController.ViewState {
        val account = if (scenario == "member") "fixture-member" else "fixture-owner"
        return when (scenario) {
            "signedOut" -> EnsembleController.ViewState()
            "unsupported" -> EnsembleController.ViewState(supported = false)
            "connecting" ->
                EnsembleController.ViewState(account = account, connection = "connecting")
            "unauthorized" ->
                EnsembleController.ViewState(account = account, connection = "unauthorized")
            "serviceUnavailable" ->
                EnsembleController.ViewState(connection = "backoff", serviceUnavailable = true)
            "lobby",
            "scorePicker" ->
                EnsembleController.ViewState(account = account, connection = "connected")
            "owner",
            "member",
            "disconnected",
            "preparing",
            "paused",
            "proposed",
            "finished",
            "finishedReady",
            "rejoin",
            "emptyRoom",
            "scheduledStart",
            "pendingRejoin",
            "playing" ->
                EnsembleController.ViewState(
                    room = room(scenario),
                    account = account,
                    connection = if (scenario == "disconnected") "disconnected" else "connected",
                    preparing = scenario == "preparing",
                    ready =
                        scenario in
                            setOf(
                                "owner",
                                "playing",
                                "paused",
                                "proposed",
                                "disconnected",
                                "finishedReady",
                                "rejoin",
                                "scheduledStart",
                                "pendingRejoin",
                            ),
                    message = if (scenario == "disconnected") "连接已断开，请重新同步" else "",
                )
            else -> error("未知界面快照：$scenario")
        }
    }

    private fun room(scenario: String): JSONObject {
        val playing = scenario in setOf("playing", "disconnected", "rejoin", "pendingRejoin")
        val members = JSONArray()
        val names = if (scenario == "emptyRoom") listOf("弦音") else listOf("弦音", "清和", "晚风")
        names.forEachIndexed { index, name ->
            members.put(
                JSONObject()
                    .put("account", if (index == 0) "fixture-owner" else "fixture-member-$index")
                    .put("nickname", name)
                    .put("track", index % 2)
                    .put("online", true)
                    .put(
                        "playing",
                        playing && !(scenario in setOf("rejoin", "pendingRejoin") && index == 0),
                    )
                    .put(
                        "ready",
                        if (
                            scenario in
                                setOf(
                                    "owner",
                                    "paused",
                                    "proposed",
                                    "finishedReady",
                                    "scheduledStart",
                                ) || playing
                        )
                            JSONObject()
                                .put("token", "fixture-prepared-$index")
                                .put("scoreHash", "a".repeat(64))
                                .put("trackHash", (if (index % 2 == 0) "b" else "c").repeat(64))
                                .put("uncertaintyUs", 5_000L)
                                .put("roundTripUs", 12_000L)
                        else JSONObject.NULL,
                    )
            )
        }
        if (scenario == "member") members.getJSONObject(1).put("account", "fixture-member")
        val snapshot =
            JSONObject()
                .put("id", "00000000-0000-4000-8000-000000000001")
                .put("run", "00000000-0000-4000-8000-000000000201")
                .put("code", "438216")
                .put("owner", "fixture-owner")
                .put(
                    "phase",
                    if (playing || scenario == "scheduledStart") "playing"
                    else if (scenario in setOf("paused", "proposed")) scenario else "idle",
                )
                .put("members", members)
                .put("revision", "1")
                .put(
                    "positionUs",
                    when (scenario) {
                        "finished",
                        "finishedReady" -> 180_000_000L
                        "paused",
                        "rejoin",
                        "playing",
                        "disconnected",
                        "pendingRejoin" -> 30_000_000L
                        else -> 0L
                    },
                )
                .put(
                    "score",
                    JSONObject()
                        .put("title", "天空之城")
                        .put("sha256", "a".repeat(64))
                        .put("durationUs", 180_000_000L)
                        .put(
                            "tracks",
                            JSONArray()
                                .put(JSONObject().put("name", "主旋律").put("sha256", "b".repeat(64)))
                                .put(JSONObject().put("name", "伴奏").put("sha256", "c".repeat(64))),
                        ),
                )
                .apply { if (scenario == "emptyRoom") put("score", JSONObject.NULL) }
        if (scenario in setOf("scheduledStart", "pendingRejoin")) {
            val participants = JSONArray()
            repeat(members.length()) { index ->
                if (scenario == "scheduledStart" || index == 0) {
                    val member = members.getJSONObject(index)
                    participants.put(
                        JSONObject()
                            .put("account", member.getString("account"))
                            .put("token", member.getJSONObject("ready").getString("token"))
                            .put("ack", true)
                            .put("executed", false)
                    )
                }
            }
            snapshot.put(
                "plan",
                JSONObject()
                    .put("id", "00000000-0000-4000-8000-000000000101")
                    .put("run", "00000000-0000-4000-8000-000000000201")
                    .put("executeAtUs", "4102444800000000")
                    .put("positionUs", if (scenario == "pendingRejoin") "30000000" else "0")
                    .put("participants", participants)
                    .put("committed", true)
                    .put("rejoin", scenario == "pendingRejoin"),
            )
        }
        return snapshot
    }
}
