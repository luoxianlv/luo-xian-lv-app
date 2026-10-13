package app.luoxianlv.playback

/** 合奏只接受本次准备令牌；音符时钟与触控回调等待分离。 */
internal class EnsemblePlayback {
    var token = ""
    var ready = false
    var preparing = false
    var localStartUs = 0L
    var positionUs = 0L
    var sleepOffsetMs = 0L
    var lastEvent = -1
    var correctionUs = 0L
    var skipped = 0
    val active
        get() = token.isNotEmpty()

    fun clear() {
        token = ""
        ready = false
        preparing = false
        localStartUs = 0
        positionUs = 0
        lastEvent = -1
        correctionUs = 0
        skipped = 0
    }

    fun position(nowUs: Long) = (positionUs + (nowUs - localStartUs).coerceAtLeast(0)) / 1000
}
