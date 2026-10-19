package app.luoxianlv.runtime.realtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.luoxianlv.hot.contract.SharedRealtime
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLParameters
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject

/** 共享运行时中的单连接；所有可变协议状态只在 actor 线程处理。 */
class RealtimeEngine(context: Context, private val host: SharedRealtime.Host, origin: String) :
    SharedRealtime.Engine {
    private val uri = URI(origin)
    private val main = Handler(Looper.getMainLooper())
    private val actor =
        ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "realtime-state").apply { isDaemon = true }
        }
    private val transportCleanup = Executors.newSingleThreadExecutor { task ->
        Thread(task, "realtime-close").apply { isDaemon = true }
    }
    private val eventDelivery =
        ThreadPoolExecutor(
                1,
                1,
                30,
                TimeUnit.SECONDS,
                ArrayBlockingQueue(32),
                { task -> Thread(task, "realtime-events").apply { isDaemon = true } },
            )
            .apply { allowCoreThreadTimeOut(true) }
    private val policy = ConnectionPolicy()
    private val clock = EnsembleClock()
    private val timeline = EnsembleTimeline(clock)
    private val scheduled = java.util.concurrent.atomic.AtomicReference<Runnable?>()
    private val queued = AtomicInteger()
    private val inbound = AtomicInteger()
    private val deliveries = AtomicInteger()
    private val closing = AtomicBoolean()
    private val observers = LinkedHashSet<Observer>()
    private val pending = LinkedHashMap<String, Pending>()

    private data class Outbound(
        val text: String,
        val bytes: Int,
        val queuedAt: Long,
        val sent: () -> Unit,
    )

    private val outbound = ArrayDeque<Outbound>()
    private var outboundBytes = 0
    private var flushing = false
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var network = ""
    private var online = false
    private var active = false
    private var room = false
    private var idleUntil = 0L
    private var socket: WebSocketClient? = null
    private var ticket: AutoCloseable? = null
    private var grant: AutoCloseable? = null
    private var epoch = ""
    private var hotReady = false
    private var hasHot = false
    private var hasEnsemble = false
    private var hasQueries = false
    private var hasHotQueries = false
    private var catalog = ""
    private var installationId = ""
    private var accountRejected = false
    private var serviceUnavailable = false
    private var hotDeadline = 0L
    private var revision = "0"
    private var trust = "0"
    private var lastMessage = 0L
    private var nextPing = 0L
    private var calibrationAttempts = 0
    private var ping: Pair<String, Long>? = null
    private var sequence = 0L
    private var account = ""
    private var accountId = ""
    private var accountExpiresAt = 0L
    private var accountGeneration = 0L
    private var accountResetEpoch = 0L
    private var accountRequest = ""
    private var accountDeadline = 0L
    private var accountRefresh = 0L
    @Volatile private var busy = false
    @Volatile private var snapshot = State()

    private data class State(
        val state: String = "stopped",
        val epoch: String = "",
        val hot: Boolean = false,
        val account: String = "",
        val room: Boolean = false,
        val clockReady: Boolean = false,
        val ensemble: Boolean = false,
        val accountRejected: Boolean = false,
        val accountResetEpoch: Long = 0,
        val queries: Boolean = false,
        val hotQueries: Boolean = false,
        val catalog: String = "",
        val installationId: String = "",
        val apiOrigin: String = "",
        val serviceUnavailable: Boolean = false,
    ) {
        fun bundle() =
            Bundle().apply {
                putString("state", state)
                putString("connectionEpoch", epoch)
                putBoolean("connected", state == "connected")
                putBoolean("hotSubscribed", hot)
                putString("accountId", account)
                putBoolean("roomActive", room)
                putBoolean("ensembleEnabled", ensemble)
                putBoolean("accountRefreshRequired", accountRejected)
                putLong("accountResetEpoch", accountResetEpoch)
                putBoolean("clockReady", clockReady)
                putBoolean("queriesReady", queries)
                putBoolean("hotQueriesReady", hotQueries)
                putString("catalog", catalog)
                putString("installationId", installationId)
                putString("apiOrigin", apiOrigin)
                putBoolean("serviceUnavailable", serviceUnavailable)
            }
    }

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                selected: Network,
                capabilities: NetworkCapabilities,
            ) {
                val valid =
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                post {
                    network = selected.toString()
                    online = valid
                    tick()
                }
            }

            override fun onLost(lost: Network) {
                post {
                    if (network == lost.toString()) {
                        network = ""
                        online = false
                        tick()
                    }
                }
            }
        }

    init {
        val debug =
            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE !=
                0
        require(
            (uri.scheme == "wss" ||
                (debug &&
                    uri.scheme == "ws" &&
                    uri.host in setOf("127.0.0.1", "localhost", "[::1]"))) &&
                uri.rawQuery == null &&
                uri.rawUserInfo == null &&
                uri.rawFragment == null &&
                uri.host != null
        )
        actor.removeOnCancelPolicy = true
        connectivity?.registerDefaultNetworkCallback(networkCallback)
        connectivity?.activeNetwork?.let { selected ->
            connectivity.getNetworkCapabilities(selected)?.let {
                networkCallback.onCapabilitiesChanged(selected, it)
            }
        }
        actor.scheduleWithFixedDelay({ guarded { tick() } }, 0, 1, TimeUnit.SECONDS)
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun micros() = SystemClock.elapsedRealtimeNanos() / 1000

    private fun post(work: () -> Unit): Boolean {
        if (closing.get()) return false
        if (queued.incrementAndGet() > 128) {
            queued.decrementAndGet()
            return false
        }
        return try {
            actor.execute {
                try {
                    if (!closing.get()) guarded(work)
                } finally {
                    queued.decrementAndGet()
                }
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            queued.decrementAndGet()
            false
        }
    }

    private fun guarded(work: () -> Unit) {
        try {
            work()
        } catch (failure: Exception) {
            logFailure("protocol", failure)
            fail(policy.generation, 30_000)
        }
    }

    private fun logFailure(stage: String, failure: Exception) {
        // 只保留异常类型，不输出服务端正文、URL、票据或异常消息。
        val categories =
            generateSequence<Throwable>(failure) { it.cause }
                .take(4)
                .joinToString("/") { it.javaClass.simpleName }
        android.util.Log.w("LuoxianlvRealtime", "stage=$stage category=$categories")
    }

    private fun deliver(work: () -> Unit) {
        if (closing.get()) return
        if (deliveries.incrementAndGet() > 128) {
            deliveries.decrementAndGet()
            // 页面消费过慢时停止连接，不能无限持有旧业务回调或静默丢控制指令。
            post { fail(policy.generation, 30_000) }
            return
        }
        main.post {
            try {
                if (!closing.get()) work()
            } catch (_: Exception) {
                /* 监听失败不终止宿主 */
            } finally {
                deliveries.decrementAndGet()
            }
        }
    }

    override fun usage(active: Boolean) {
        post {
            if (this.active && !active) idleUntil = now() + 30_000
            this.active = active
            tick()
        }
    }

    override fun room(active: Boolean, performing: Boolean) {
        busy = active && performing
        post {
            if (active && !room) {
                nextPing = 0
                calibrationAttempts = 0
            }
            room = active
            tick()
            deliver { host.usageChanged() }
        }
    }

    override fun performing() = busy

    private fun tick() {
        val now = now()
        if (policy.availability(active || now < idleUntil, online, network)) disconnect()
        if (policy.timedOut(now)) fail(policy.generation)
        val attempt = policy.begin(now)
        if (attempt >= 0) {
            ticket = host.ticket { secret, status, retry ->
                post {
                    if (policy.current(attempt)) {
                        ticket = null
                        if (status == 404) serviceUnavailable = true
                        else if (status in setOf(401, 403, 409)) serviceUnavailable = false
                        if (status != 0 || secret.isEmpty())
                            fail(attempt, retry, status in setOf(401, 403, 409))
                        else connect(attempt, secret)
                    }
                }
            }
        }
        if (policy.connected) {
            if (accountId.isNotEmpty() && now >= accountExpiresAt) accountId = ""
            if (now - lastMessage >= (if (room) 15_000 else 135_000)) {
                fail(policy.generation)
                return
            }
            if (ping?.let { micros() - it.second >= 10_000_000 } == true) ping = null
            if (ping == null && now >= nextPing) {
                val id = "ping:${++sequence}"
                ping = id to Long.MAX_VALUE
                if (wire(id, "system", "ping", JSONObject()) { ping = id to micros() }) {
                    calibrationAttempts++
                    nextPing =
                        now +
                            if (room && calibrationAttempts < 5 && clock.estimate(micros()) == null)
                                1000
                            else if (room) 5000 else 45_000
                } else ping = null
            }
            if (hasHot && !hotReady && now >= hotDeadline) {
                if (wire("subscribe-hot", "hot", "subscribe", JSONObject()))
                    hotDeadline = now + 15_000
            }
            if (accountRequest.isNotEmpty() && now >= accountDeadline) clearAccountRequest(30_000)
            if (
                (hasEnsemble || hasQueries) &&
                    !accountRejected &&
                    account.isNotEmpty() &&
                    accountRequest.isEmpty() &&
                    now >= accountRefresh
            )
                authenticateAccount()
        }
        val expired = pending.filterValues { it.cancelled.get() || now >= it.deadline }.keys
        expired.forEach { id ->
            pending.remove(id)?.let {
                complete(it, false, "{\"unconfirmed\":true,\"message\":\"操作未确认，请重新同步\"}")
            }
        }
        observers.removeAll { it.closed.get() }
        publish()
    }

    private fun connect(attempt: Long, secret: String) {
        val client =
            object :
                WebSocketClient(
                    uri,
                    Draft_6455(emptyList(), RealtimeWire.MAX_BYTES),
                    mapOf(
                        "Authorization" to "Bearer $secret",
                        "User-Agent" to "Luoxianlv/realtime-v1",
                    ),
                    8000,
                ) {
                override fun onSetSSLParameters(parameters: SSLParameters) {
                    parameters.endpointIdentificationAlgorithm = "HTTPS"
                    getSocket()?.soTimeout = 8000
                }

                override fun onOpen(handshake: ServerHandshake) {
                    getSocket()?.soTimeout = 150_000
                }

                override fun onMessage(message: String) {
                    val receivedUs = micros()
                    val size =
                        if (message.length <= RealtimeWire.MAX_BYTES)
                            message.toByteArray(Charsets.UTF_8).size
                        else Int.MAX_VALUE
                    if (size > RealtimeWire.MAX_BYTES) {
                        closeConnection(1009, "message limit")
                        return
                    }
                    if (inbound.addAndGet(size) > 65_536) {
                        inbound.addAndGet(-size)
                        closeConnection(1009, "queue limit")
                        return
                    }
                    if (
                        !post {
                            try {
                                if (policy.current(attempt)) received(attempt, message, receivedUs)
                            } finally {
                                inbound.addAndGet(-size)
                            }
                        }
                    ) {
                        inbound.addAndGet(-size)
                        closeConnection(1013, "queue limit")
                    }
                }

                override fun onMessage(bytes: ByteBuffer) {
                    closeConnection(1003, "text required")
                }

                override fun onClose(code: Int, reason: String, remote: Boolean) {
                    android.util.Log.w(
                        "LuoxianlvRealtime",
                        "stage=websocket-close code=$code remote=$remote",
                    )
                    post { fail(attempt) }
                }

                override fun onError(error: Exception) {
                    logFailure("websocket", error)
                    closeTransport(this)
                    post { fail(attempt) }
                }
            }
        client.connectionLostTimeout = 0
        client.isTcpNoDelay = true
        client.receiveBufferSize = 8192
        client.isDaemon = true
        socket = client
        client.connect()
    }

    private fun received(attempt: Long, text: String, receivedUs: Long) {
        val value = RealtimeWire.objectValue(text)
        require(value.getInt("protocolVersion") == 1)
        val id = value.getString("messageId")
        val topic = value.getString("topic")
        val type = value.getString("type")
        val data = value.getJSONObject("data")
        require(RealtimeWire.validId(id) && topic.length <= 32 && type.length <= 64)
        if (topic == "system" && type == "hello" && !policy.connected) {
            epoch = value.getString("connectionEpoch").also { RealtimeWire.positive(it) }
            clock.reset(data.getString("clockEpoch").also { require(it.length in 1..128) })
            val capabilities = data.getJSONArray("capabilities")
            require(capabilities.length() <= 16)
            hasHot =
                (0 until capabilities.length()).any { capabilities.getString(it) == "hot-hints-v1" }
            hasEnsemble =
                (0 until capabilities.length()).any { capabilities.getString(it) == "ensemble-v1" }
            hasQueries =
                (0 until capabilities.length()).any { capabilities.getString(it) == "queries-v1" }
            hasHotQueries =
                (0 until capabilities.length()).any {
                    capabilities.getString(it) == "hot-queries-v1"
                }
            policy.ready(attempt, now())
            serviceUnavailable = false
            lastMessage = now()
            nextPing = 0
            hotDeadline = 0
            tick()
            return
        }
        if (!policy.connected || epoch != value.getString("connectionEpoch")) return
        lastMessage = now()
        if (topic == "system" && type == "maintenance") {
            fail(attempt, data.optLong("retryAfterMs", 5_000).coerceIn(1_000, 60_000))
            return
        }
        if (topic == "system" && id == ping?.first) {
            val sent = ping!!.second
            ping = null
            if (type == "result")
                clock.sample(
                    data.getString("clockEpoch"),
                    sent,
                    receivedUs,
                    RealtimeWire.positive(data.getString("receivedUs")),
                    RealtimeWire.positive(data.getString("sentUs")),
                    data.optBoolean("clockHealthy", false),
                )
            return
        }
        if (topic == "catalog" && type == "changed") {
            data.optJSONObject("versions")?.let { catalog = it.toString() }
            observers.toList().forEach { it.message(topic, type, text, "versions") }
            publish()
            return
        }
        if (topic == "query" && type == "chunk") {
            val request =
                pending[id]?.takeIf { it.topic == "query" && !it.cancelled.get() } ?: return
            val chunks = request.chunks ?: QueryChunks().also { request.chunks = it }
            val result = chunks.accept(data)
            check(wire(id, "query", "continue", JSONObject().put("offset", chunks.offset)))
            if (result != null) {
                pending.remove(id)
                complete(request, true, result)
            }
            return
        }
        if (topic == "hot") {
            if (id == "subscribe-hot") hotReady = type == "result" && data.optBoolean("subscribed")
            if (hotReady && (id == "subscribe-hot" || type == "changed")) {
                val nextRevision =
                    data.getString("channelRevision").also { RealtimeWire.positive(it, true) }
                val nextTrust =
                    data.getString("trustVersion").also { RealtimeWire.positive(it, true) }
                if (
                    id == "subscribe-hot" ||
                        nextRevision.toLong() > revision.toLong() ||
                        nextTrust.toLong() > trust.toLong()
                ) {
                    revision = maxOf(nextRevision.toLong(), revision.toLong()).toString()
                    trust = maxOf(nextTrust.toLong(), trust.toLong()).toString()
                    hotChanged()
                }
            }
        }
        if (topic == "account" && id == accountRequest) {
            if (type == "result") {
                accountId = data.getString("accountId")
                accountExpiresAt = now() + data.optLong("expiresIn", 600).coerceIn(1, 600) * 1000
            }
            clearAccountRequest(if (type == "result") 480_000 else 60_000)
        }
        pending[id]
            ?.takeIf { it.topic == topic && type in setOf("result", "error") }
            ?.let {
                pending.remove(id)
                data
                    .put("clientSentUs", it.sentUs.toString())
                    .put("clientReceivedUs", receivedUs.toString())
                complete(it, type == "result", data.toString())
            }
        if (topic in setOf("ensemble", "business"))
            observers.toList().forEach { it.message(topic, type, text, data.optString("kind")) }
        publish()
    }

    private fun hotChanged() {
        val ready = hotReady
        val revision = revision
        val trust = trust
        // 主线程按发送顺序接收订阅、断开和重连，不能过滤掉断开而遗留“已订阅”。
        deliver { host.hotChanged(ready, revision, trust) }
    }

    private fun fail(attempt: Long, retry: Long = 0, rejected: Boolean = false) {
        if (policy.failed(attempt, now(), retry, rejected)) {
            disconnect()
            publish()
        }
    }

    private fun disconnect() {
        stopScheduled()
        outbound.clear()
        outboundBytes = 0
        socket?.let { closeTransport(it) }
        socket = null
        ticket?.close()
        ticket = null
        clearAccountRequest(0)
        hotReady = false
        hasHot = false
        hasEnsemble = false
        hasQueries = false
        hasHotQueries = false
        catalog = ""
        installationId = ""
        epoch = ""
        accountId = ""
        ping = null
        clock.reset()
        calibrationAttempts = 0
        pending.values.forEach {
            complete(it, false, "{\"unconfirmed\":true,\"message\":\"连接中断，请重新同步\"}")
        }
        pending.clear()
        hotChanged()
    }

    private fun closeTransport(client: WebSocketClient) {
        client.closeConnection(1001, "connection replaced")
        // 库在握手完成前尚无写线程；此时只关闭 WS 状态不能取消 TCP/TLS 读。
        val selected = client.socket
        if (!transportCleanup.isShutdown)
            runCatching {
                transportCleanup.execute { runCatching { selected?.close() } }
            }
    }

    private fun wire(
        id: String,
        topic: String,
        type: String,
        data: JSONObject,
        sent: () -> Unit = {},
    ): Boolean {
        val client = socket ?: return false
        if (!policy.connected || !client.isOpen) return false
        val text = RealtimeWire.encode(epoch, id, topic, type, data)
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes > RealtimeWire.MAX_BYTES || outbound.size >= 64 || outboundBytes + bytes > 49_152)
            return false
        outbound.addLast(Outbound(text, bytes, now(), sent))
        outboundBytes += bytes
        flush()
        return true
    }

    private fun flush() {
        val client = socket ?: return
        if (outbound.isEmpty()) return
        if (now() - outbound.first().queuedAt > 10_000) {
            fail(policy.generation)
            return
        }
        // 应用队列 48 KiB，库只排一个 16 KiB 帧，避免库内部无界排队。
        if (!client.hasBufferedData()) {
            val frame = outbound.removeFirst()
            outboundBytes -= frame.bytes
            frame.sent()
            client.send(frame.text)
        }
        if (outbound.isNotEmpty() && !flushing) {
            flushing = true
            actor.schedule(
                {
                    flushing = false
                    if (!closing.get()) guarded { flush() }
                },
                10,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private class Pending(val topic: String, val completion: SharedRealtime.Completion) {
        val cancelled = AtomicBoolean()
        var sentUs = 0L
        var deadline = 0L
        var chunks: QueryChunks? = null
    }

    override fun send(
        id: String,
        topic: String,
        type: String,
        json: String,
        completion: SharedRealtime.Completion,
    ): AutoCloseable {
        val request = Pending(topic, completion)
        if (
            !post {
                try {
                    require(
                        RealtimeWire.validId(id) &&
                            !id.startsWith("ping:") &&
                            !id.startsWith("account:") &&
                            id != "subscribe-hot" &&
                            (topic == "ensemble" || (topic == "query" && type == "request")) &&
                            type.length in 1..64
                    )
                    require(
                        (if (topic == "query")
                            hasQueries && pending.values.none { it.topic == "query" }
                        else hasEnsemble && accountId.isNotEmpty()) &&
                            pending.size < 32 &&
                            !pending.containsKey(id)
                    )
                    if (!request.cancelled.get()) {
                        request.deadline = now() + 15_000
                        check(
                            wire(id, topic, type, RealtimeWire.objectValue(json)) {
                                request.sentUs = micros()
                            }
                        )
                        pending[id] = request
                    }
                } catch (_: Exception) {
                    complete(request, false, "{\"message\":\"当前无法提交操作\"}")
                }
            }
        )
            complete(request, false, "{\"message\":\"连接任务已停止\"}")
        return AutoCloseable {
            if (request.cancelled.compareAndSet(false, true) && topic == "query")
                post { if (pending.remove(id) != null) wire(id, "query", "cancel", JSONObject()) }
        }
    }

    private fun complete(request: Pending, success: Boolean, json: String) {
        deliver { if (!request.cancelled.get()) request.completion.complete(success, json) }
    }

    override fun account(accessToken: String) {
        if (accessToken.length > 8192) return
        post {
            if (account != accessToken) {
                account = accessToken
                accountRejected = false
                if (accessToken.isEmpty()) {
                    accountResetEpoch++
                    stopScheduled()
                    accountId = ""
                    accountExpiresAt = 0
                }
                accountGeneration++
                clearAccountRequest(0)
                if (
                    accessToken.isEmpty() &&
                        policy.connected &&
                        !wire("clear-account", "account", "clear", JSONObject())
                )
                    fail(policy.generation)
                tick()
            }
        }
    }

    private fun clearAccountRequest(delay: Long) {
        grant?.close()
        grant = null
        accountRequest = ""
        accountRefresh = now() + delay
    }

    private fun authenticateAccount() {
        val expectedEpoch = epoch
        val generation = accountGeneration
        val requestId = "account:$generation:${++sequence}"
        accountRequest = requestId
        accountDeadline = now() + 15_000
        grant =
            host.accountGrant(account, expectedEpoch) { secret, status, _ ->
                post {
                    if (
                        expectedEpoch == epoch &&
                            generation == accountGeneration &&
                            accountRequest == requestId
                    ) {
                        grant = null
                        accountRejected = status == 401
                        if (
                            status != 0 ||
                                secret.isEmpty() ||
                                !wire(
                                    requestId,
                                    "account",
                                    "authorize",
                                    JSONObject().put("grant", secret),
                                )
                        ) {
                            clearAccountRequest(60_000)
                            publish()
                        }
                    }
                }
            }
    }

    override fun state(): Bundle = snapshot.bundle()

    override fun clock(): Bundle {
        val (epoch, estimate) = clock.view(micros())
        return Bundle().apply {
            putString("clockEpoch", epoch)
            putBoolean("ready", estimate != null)
            estimate?.let {
                putLong("offsetUs", it.offsetUs)
                putLong("uncertaintyUs", it.uncertaintyUs)
                putLong("sampledAtUs", it.sampledAtUs)
            }
        }
    }

    override fun prepareRun(runId: String) {
        stopScheduled()
        timeline.prepare(runId)
    }

    override fun stopScheduled() {
        timeline.stop()
        scheduled.getAndSet(null)?.let(main::removeCallbacks)
    }

    override fun schedule(
        plan: Bundle,
        execute: SharedRealtime.Execution,
        completion: SharedRealtime.Completion,
    ): AutoCloseable {
        val accepted = runCatching {
            timeline.accept(
                EnsembleTimeline.Plan(
                    plan.getString("id").orEmpty(),
                    plan.getString("runId").orEmpty(),
                    plan.getLong("revision"),
                    plan.getString("clockEpoch").orEmpty(),
                    plan.getLong("executeAtUs"),
                    plan.getLong("expiresAtUs"),
                ),
                micros(),
            )
        }
            .getOrNull()
        if (accepted == null) {
            deliver { completion.complete(false, "{\"message\":\"准备或校时已失效，请重新准备\"}") }
            return AutoCloseable {}
        }
        val cancelled = AtomicBoolean()
        val action =
            object : Runnable {
                override fun run() {
                    if (!closing.get() && !cancelled.get()) {
                        val at = micros()
                        val remaining = accepted.localDeadlineUs - at
                        // uptimeMillis 会舍去小数，Handler 可能提前不足 1 ms；提前只重约，不抢跑。
                        if (remaining > 0) {
                            main.postDelayed(this, (remaining + 999) / 1000)
                            return
                        }
                        val valid = timeline.claim(accepted, at)
                        val executed =
                            valid &&
                                runCatching { execute.execute(accepted.localDeadlineUs) }.isSuccess
                        runCatching {
                            completion.complete(
                                executed,
                                if (executed) "{}"
                                else
                                    JSONObject()
                                        .put("message", "错过开始时间，请重新准备")
                                        .put("lateUs", at - accepted.localDeadlineUs)
                                        .put("stage", if (valid) "input" else "clock")
                                        .toString(),
                            )
                        }
                    }
                }
            }
        scheduled.getAndSet(action)?.let(main::removeCallbacks)
        val delayMs = ((accepted.localDeadlineUs - micros()).coerceAtLeast(0) + 999) / 1000
        main.postAtTime(action, SystemClock.uptimeMillis() + delayMs)
        return AutoCloseable {
            cancelled.set(true)
            main.removeCallbacks(action)
            timeline.cancel(accepted)
            scheduled.compareAndSet(action, null)
        }
    }

    private fun publish() {
        val next =
            State(
                policy.state,
                epoch,
                hotReady,
                accountId,
                room,
                clock.estimate(micros()) != null,
                hasEnsemble,
                accountRejected,
                accountResetEpoch,
                policy.connected && hasQueries,
                policy.connected && hasHotQueries,
                catalog,
                installationId,
                (if (uri.scheme == "wss") "https" else "http") + "://" + uri.rawAuthority,
                serviceUnavailable,
            )
        if (next == snapshot) return
        snapshot = next
        observers.toList().forEach { it.changed() }
    }

    private inner class Observer(val listener: SharedRealtime.Listener, background: Boolean) {
        val closed = AtomicBoolean()
        val stateQueued = AtomicBoolean()
        private val mailbox = if (background) NotificationMailbox(eventDelivery) else null
        private var deliveredState: State? = null

        private fun backgroundState(current: State) {
            if (closed.get() || deliveredState == current) return
            listener.changed(current.bundle())
            deliveredState = current
        }

        fun changed() {
            mailbox?.let {
                it.offer("state") { backgroundState(snapshot) }
                return
            }
            if (!stateQueued.compareAndSet(false, true)) return
            deliver {
                stateQueued.set(false)
                if (!closed.get()) listener.changed(snapshot.bundle())
            }
        }

        fun message(topic: String, type: String, json: String, kind: String) {
            mailbox?.let {
                if (type != "changed" || topic !in setOf("catalog", "business")) return
                if (
                    topic == "business" &&
                        kind !in setOf("library", "interactions", "notifications", "profile")
                )
                    return
                val sourceEpoch = epoch
                val sourceAccount = accountId
                val sourceReset = accountResetEpoch
                it.offer("$topic:$kind") {
                    val current = snapshot
                    if (
                        !closed.get() &&
                            current.epoch == sourceEpoch &&
                            (topic != "business" ||
                                (sourceAccount.isNotEmpty() &&
                                    current.account == sourceAccount &&
                                    current.accountResetEpoch == sourceReset))
                    ) {
                        backgroundState(current)
                        listener.message(topic, type, json)
                    }
                }
                return
            }
            if (topic != "ensemble") return
            deliver { if (!closed.get()) listener.message(topic, type, json) }
        }

        fun close() {
            closed.set(true)
            mailbox?.close()
        }
    }

    override fun observe(listener: SharedRealtime.Listener) = observe(listener, false)

    override fun observeEvents(listener: SharedRealtime.Listener) = observe(listener, true)

    private fun observe(listener: SharedRealtime.Listener, background: Boolean): AutoCloseable {
        val observer = Observer(listener, background)
        if (
            !post {
                if (!observer.closed.get() && observers.size < 32) {
                    observers.add(observer)
                    observer.changed()
                } else observer.close()
            }
        )
            observer.close()
        return AutoCloseable {
            observer.close()
            post { observers.remove(observer) }
        }
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        stopScheduled()
        snapshot = State()
        busy = false
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        actor.execute {
            policy.stop()
            disconnect()
            busy = false
            account = ""
            observers.forEach { it.close() }
            observers.clear()
            eventDelivery.shutdownNow()
            transportCleanup.shutdown()
            actor.shutdownNow()
        }
    }
}
