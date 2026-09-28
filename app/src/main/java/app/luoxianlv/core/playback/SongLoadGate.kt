package app.luoxianlv.core.playback

/** 主线程管理选歌代次与待播放意图；旧加载结果、暂停后的回调都不能启动播放。 */
class SongLoadGate {
    private var generation = 0
    var loading = false
        private set

    var playWhenReady = false
        private set

    fun begin(): Int {
        loading = true
        playWhenReady = false
        return ++generation
    }

    fun requestPlay(): Boolean {
        if (!loading) return false
        playWhenReady = true
        return true
    }

    fun pause() {
        playWhenReady = false
    }

    fun finish(token: Int): Boolean? {
        if (token != generation) return null
        loading = false
        return playWhenReady.also { playWhenReady = false }
    }
}
