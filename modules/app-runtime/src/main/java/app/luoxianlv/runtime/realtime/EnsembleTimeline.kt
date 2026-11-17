package app.luoxianlv.runtime.realtime

/** 把服务端开始计划固定到本机单调期限；迟到、旧代次和本地停止均不能补点。 */
internal class EnsembleTimeline(private val clock: EnsembleClock) {
    data class Plan(
        val id: String,
        val run: String,
        val revision: Long,
        val clockEpoch: String,
        val executeAtUs: Long,
        val expiresAtUs: Long,
    )

    data class Scheduled(
        val plan: Plan,
        val localDeadlineUs: Long,
        val localExpiryUs: Long,
        val generation: Long,
    )

    private var run = ""
    private var generation = 0L
    private var revision = -1L
    private var stopped = true
    private var scheduled: Scheduled? = null
    private var accepted: Plan? = null

    @Synchronized
    fun prepare(run: String) {
        require(RealtimeWire.validId(run))
        this.run = run
        generation++
        stopped = false
        revision = -1
        scheduled = null
        accepted = null
    }

    @Synchronized
    fun stop() {
        stopped = true
        generation++
        scheduled = null
    }

    @Synchronized
    fun cancel(value: Scheduled) {
        if (scheduled == value) scheduled = null
    }

    @Synchronized
    fun accept(plan: Plan, nowUs: Long): Scheduled? {
        if (
            stopped ||
                plan.run != run ||
                plan.clockEpoch != clock.epoch ||
                !RealtimeWire.validId(plan.id)
        )
            return null
        // 已接受过的同一指令只返回尚未执行的句柄，绝不再次安排执行。
        if (accepted?.id == plan.id) return if (accepted == plan) scheduled else null
        if (plan.revision <= revision) return null
        val estimate = clock.estimate(nowUs) ?: return null
        val deadline =
            runCatching { Math.subtractExact(plan.executeAtUs, estimate.offsetUs) }.getOrNull()
                ?: return null
        val expiry =
            runCatching { Math.subtractExact(plan.expiresAtUs, estimate.offsetUs) }.getOrNull()
                ?: return null
        if (
            deadline < nowUs ||
                deadline - nowUs > 10_000_000 ||
                expiry < deadline ||
                expiry - deadline > 1_000_000
        )
            return null
        return Scheduled(plan, deadline, expiry, generation).also {
            revision = plan.revision
            accepted = plan
            scheduled = it
        }
    }

    /** 调度线程唤醒后再次核验；本地停止会立即废弃所有未提交计划。 */
    @Synchronized
    fun claim(value: Scheduled, nowUs: Long): Boolean {
        if (
            stopped ||
                value != scheduled ||
                generation != value.generation ||
                clock.epoch != value.plan.clockEpoch
        )
            return false
        if (nowUs < value.localDeadlineUs) return false
        scheduled = null
        if (nowUs > value.localExpiryUs || nowUs - value.localDeadlineUs > 50_000) return false
        val estimate = clock.estimate(nowUs) ?: return false
        val currentDeadline = value.plan.executeAtUs - estimate.offsetUs
        return kotlin.math.abs(currentDeadline - value.localDeadlineUs) <= 20_000
    }
}
