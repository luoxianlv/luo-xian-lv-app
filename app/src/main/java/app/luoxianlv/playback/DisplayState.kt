package app.luoxianlv.playback

/** 系统显示状态只用于拒绝过期请求，不参与截图坐标缩放。 */
data class DisplayState(val width: Int, val height: Int, val rotation: Int) {
    override fun toString(): String = "($width, $height, $rotation)"
}

internal class DisplayStability {
    private var last: DisplayState? = null
    private var since = 0L

    fun reset() {
        last = null
    }

    fun ready(sample: DisplayState, now: Long): Boolean {
        if (sample.width <= 0 || sample.height <= 0) {
            reset()
            return false
        }
        if (sample != last) {
            last = sample
            since = now
            return false
        }
        return now - since >= 240
    }
}
