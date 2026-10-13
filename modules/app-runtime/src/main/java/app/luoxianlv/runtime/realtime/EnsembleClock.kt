package app.luoxianlv.runtime.realtime

import kotlin.math.abs

/** 四时间戳校时；只用单调时钟，不随用户修改手机时间跳变。 */
internal class EnsembleClock {
    data class Estimate(val offsetUs: Long, val uncertaintyUs: Long, val sampledAtUs: Long)

    private data class Sample(val offset: Long, val delay: Long, val received: Long)

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
        val offset = (serverReceivedUs - sentUs) / 2 + (serverSentUs - receivedUs) / 2
        // 大幅漂移需要重新取得足够样本，不能挪动已安排的按键期限。
        estimate(receivedUs)?.let { if (abs(offset - it.offsetUs) > 250_000) samples.clear() }
        samples.removeAll { receivedUs - it.received > 30_000_000 }
        if (samples.size == 12) samples.removeFirst()
        samples.addLast(Sample(offset, delay, receivedUs))
        return true
    }

    @Synchronized
    fun estimate(nowUs: Long): Estimate? {
        val recent = samples.filter { nowUs >= it.received && nowUs - it.received <= 30_000_000 }
        if (recent.size < 3) return null
        val best = recent.sortedBy { it.delay }.take(3)
        val offset = best.map { it.offset }.sorted()[1]
        val uncertainty = best.maxOf { abs(it.offset - offset) + it.delay / 2 } + 1000
        if (uncertainty > 50_000) return null
        return Estimate(offset, uncertainty, recent.maxOf { it.received })
    }

    @Synchronized fun view(nowUs: Long) = epoch to estimate(nowUs)
}
