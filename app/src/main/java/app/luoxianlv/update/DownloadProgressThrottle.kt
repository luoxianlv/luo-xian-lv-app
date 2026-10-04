package app.luoxianlv.update

/** 下载字节不断累计，界面最多每 100ms 接收一次进度；成功和失败终态由调用方立即发布。 */
internal class DownloadProgressThrottle(private val intervalNanos: Long = 100_000_000L) {
    private var lastPublished: Long? = null

    init {
        require(intervalNanos > 0)
    }

    fun shouldPublish(nowNanos: Long): Boolean {
        val last = lastPublished
        if (last != null && nowNanos - last < intervalNanos) return false
        lastPublished = nowNanos
        return true
    }
}
