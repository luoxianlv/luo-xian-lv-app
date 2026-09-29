package app.luoxianlv.update

import android.content.Context
import app.luoxianlv.business.playback.PlaybackConnection
import app.luoxianlv.data.ConfigStore
import app.luoxianlv.data.SongRepository
import app.luoxianlv.platform.PlatformClient

/** 后台更新内容，不提供额外手动入口。 */
class HotUpdateCoordinator(
    context: Context,
    private val repository: SongRepository,
) {
    private val appContext = context.applicationContext
    private val updater = PlatformClient(appContext)

    @Volatile private var running = false

    fun check(onApplied: () -> Unit = {}) {
        if (running) return
        running = true
        updater.checkHot { result ->
            running = false
            result.onSuccess { update ->
                if (!repository.applyHotUpdate(update.payload)) return@onSuccess
                update.payload.optJSONObject("layout")?.let {
                    ConfigStore.applyHotLayout(appContext, it)
                    PlaybackConnection.instance?.reloadConfig()
                }
                onApplied()
            }
        }
    }
}
