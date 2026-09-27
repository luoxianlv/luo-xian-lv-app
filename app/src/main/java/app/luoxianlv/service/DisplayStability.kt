package app.luoxianlv.service

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
