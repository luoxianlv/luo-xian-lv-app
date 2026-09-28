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
        // 仅回收首页闲置缓存，不销毁正在使用的演练场。
        if (level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_CRITICAL) {
            app.luoxianlv.wallpaper.render.PreparedWallpaper.clear()
        }
    }

    override fun onCreate() {
        super.onCreate()
        app.luoxianlv.debug.CrashLog.install(this)
        app.luoxianlv.debug.AppLog.init(this)
        Thread(
                {
                    app.luoxianlv.storage.AppStorage.migrate(this) {
                        app.luoxianlv.debug.AppLog.i("存储", it)
                    }
                },
                "storage-migration",
            )
            .apply {
                isDaemon = true
                start()
            }
        Analytics.preInitialize(this)
    }
}
