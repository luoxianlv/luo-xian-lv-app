package app.luoxianlv.update
import android.content.Context
import app.luoxianlv.data.ConfigStore
import app.luoxianlv.data.SongRepository
import app.luoxianlv.service.MusicAccessibilityService

/** Runs content updates in the background without exposing a manual control. */
class HotUpdateCoordinator(
    context: Context,
    private val repository: SongRepository,
) {
    private val appContext = context.applicationContext
    private val updater = UpdateManager(appContext)

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
                    MusicAccessibilityService.instance?.reloadConfig()
                }
                onApplied()
            }
        }
    }
}
