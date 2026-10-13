package app.luoxianlv.runtime.realtime

/** 单一控制器维护代次与截止时间，旧 Socket 的回调不能改变新连接。 */
internal class ConnectionPolicy(private val random: () -> Double = Math::random) {
    var generation = 0L
        private set

    var connected = false
        private set

    private var wanted = false
    private var online = false
    private var running = false
    private var rejected = false
    private var network = ""
    private var due = 0L
    private var deadline = 0L
    private var connectedAt = 0L
    private var failures = 0

    fun availability(wanted: Boolean, online: Boolean, network: String): Boolean {
        val invalidate = (!wanted || !online || this.network != network) && running
        this.wanted = wanted
        this.online = online
        this.network = network
        if (invalidate) {
            generation++
            running = false
            connected = false
        }
        return invalidate
    }

    fun begin(now: Long): Long {
        if (!wanted || !online || running || rejected || now < due) return -1
        running = true
        connected = false
        deadline = now + 15_000
        return ++generation
    }

    fun current(attempt: Long) = running && generation == attempt

    fun ready(attempt: Long, now: Long): Boolean {
        if (!current(attempt)) return false
        if (!connected) connectedAt = now
        connected = true
        return true
    }

    fun timedOut(now: Long) = running && !connected && now >= deadline

    fun failed(attempt: Long, now: Long, retryAfter: Long = 0, rejected: Boolean = false): Boolean {
        if (!current(attempt)) return false
        if (connected && now - connectedAt >= 60_000) failures = 0
        generation++
        running = false
        connected = false
        this.rejected = rejected
        failures = (failures + 1).coerceAtMost(6)
        val sample = random().takeIf { it.isFinite() && it in 0.0..1.0 } ?: .5
        val backoff = (1000L shl (failures - 1)).coerceAtMost(30_000)
        due =
            now + maxOf(retryAfter.coerceIn(0, 3_600_000), (backoff * (.5 + sample * .5)).toLong())
        return true
    }

    fun stop() {
        generation++
        wanted = false
        running = false
        connected = false
    }

    fun authorizedAgain() {
        rejected = false
    }

    val state: String
        get() =
            when {
                !wanted -> "stopped"
                !online -> "waiting_network"
                rejected -> "unauthorized"
                connected -> "connected"
                running -> "connecting"
                else -> "backoff"
            }
}
