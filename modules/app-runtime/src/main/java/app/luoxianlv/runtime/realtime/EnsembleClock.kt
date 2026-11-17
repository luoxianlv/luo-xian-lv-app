package app.luoxianlv.runtime.realtime

import kotlin.math.abs

/** 四时间戳校时；只用单调时钟，不随用户修改手机时间跳变。 */
internal class EnsembleClock {
    data class Estimate(
        val offsetUs: Long,
        val uncertaintyUs: Long,
        val sampledAtUs: Long,
        val roundTripUs: Long,
    )

    private data class Sample(
        val earliestOffset: Long,
        val latestOffset: Long,
        val delay: Long,
        val received: Long,
        val roundTrip: Long,
    )

    private val samples = ArrayDeque<Sample>()
    var epoch = ""
        private set

    @Synchronized
    fun reset(epoch: String = "") {
        samples.clear()
        this.epoch = epoch
    }

    @Synchronized
    fun sample(
        epoch: String,
        sentUs: Long,
        receivedUs: Long,
        serverReceivedUs: Long,
        serverSentUs: Long,
        healthy: Boolean,
    ): Boolean {
        if (epoch != this.epoch || !healthy) {
            reset(epoch)
            if (!healthy) return false
        }
        if (
            epoch.isEmpty() ||
                sentUs < 0 ||
                receivedUs < sentUs ||
                serverReceivedUs < 0 ||
                serverSentUs < serverReceivedUs
        )
            return false
        val roundTrip = receivedUs - sentUs
        val serverWork = serverSentUs - serverReceivedUs
        if (roundTrip > 1_000_000 || serverWork > roundTrip) return false
        val delay = roundTrip - serverWork
        // 双向传输时间均非负，因此四个时间戳给出偏移量的上下界，无需假定上下行对称。
        val earliestOffset = serverSentUs - receivedUs
        val latestOffset = serverReceivedUs - sentUs
        val offset = earliestOffset + delay / 2
        // 大幅漂移需要重新取得足够样本，不能挪动已安排的按键期限。
        estimate(receivedUs)?.let { if (abs(offset - it.offsetUs) > 250_000) samples.clear() }
        samples.removeAll { receivedUs - it.received > 30_000_000 }
        if (samples.size == 12) samples.removeFirst()
        samples.addLast(Sample(earliestOffset, latestOffset, delay, receivedUs, roundTrip))
        return true
    }

    @Synchronized
    fun estimate(nowUs: Long): Estimate? {
        val recent = samples.filter { nowUs >= it.received && nowUs - it.received <= 30_000_000 }
        if (recent.size < 3) return null
        val best = recent.sortedBy { it.delay }.take(3)
        // 同一稳定时钟的真实偏移应同时落在这三个区间内；区间相离时等待新样本。
        val earliest = best.maxOf { it.earliestOffset }
        val latest = best.minOf { it.latestOffset }
        if (earliest > latest) return null
        val width = latest - earliest
        val offset = earliest + width / 2
        val uncertainty = (width + 1) / 2 + 1000
        if (uncertainty > 50_000) return null
        // 校时选低延迟样本，开始确认的预算则要覆盖近期最慢的一次完整往返。
        return Estimate(
            offset,
            uncertainty,
            recent.maxOf { it.received },
            recent.maxOf { it.roundTrip },
        )
    }

    @Synchronized fun view(nowUs: Long) = epoch to estimate(nowUs)
}
