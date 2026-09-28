package app.luoxianlv

import android.app.Application
import app.luoxianlv.core.Analytics

/**
 * 友盟统计接入（U-App）：
 * - preInit 在 Application.onCreate 主线程执行，不采集设备信息、不上报，合规要求；
 * - 正式 init 必须等用户同意免责协议后由 [app.luoxianlv.core.Analytics] 触发，见 MainActivity。
 */
class LuoXianLvApp : Application() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        app.luoxianlv.wallpaper.render.WallpaperPreview.clearCache()
        // Only the unused home cache is owned here; never destroy the active stage.
        if (level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_CRITICAL) {
            app.luoxianlv.wallpaper.render.PreparedWallpaper.clear()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Analytics.preInitialize(this)
    }
}
