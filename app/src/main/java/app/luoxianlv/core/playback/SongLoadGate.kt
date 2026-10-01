package app.luoxianlv.core.playback

/** 主线程管理选歌代次与待播放意图；旧加载结果、暂停后的回调都不能启动播放。 */
class SongLoadGate(private val onChanged: () -> Unit = {}) {
    private var generation = 0
    var loading = false
        private set

    var playWhenReady = false
        private set

    fun begin(): Int {
        val token = ++generation
        publish(loading = true, waiting = false)
        return token
    }

    fun requestPlay(): Boolean {
        if (!loading) return false
        publish(loading = true, waiting = true)
        return true
    }

    fun pause() {
        publish(loading, waiting = false)
    }

    fun finish(token: Int): Boolean? {
        if (token != generation) return null
        val start = playWhenReady
        publish(loading = false, waiting = false)
        return start
    }

    private fun publish(loading: Boolean, waiting: Boolean) {
        if (this.loading == loading && playWhenReady == waiting) return
        this.loading = loading
        playWhenReady = waiting
        onChanged()
    }
}
