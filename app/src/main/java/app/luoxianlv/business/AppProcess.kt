package app.luoxianlv.business

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import app.luoxianlv.core.Analytics
import app.luoxianlv.debug.AppLog
import app.luoxianlv.hot.contract.HostDiagnostics
import app.luoxianlv.hot.contract.ProcessHooks
import app.luoxianlv.storage.AppStorage
import app.luoxianlv.wallpaper.render.PreparedWallpaper
import app.luoxianlv.wallpaper.render.WallpaperPreview

/** 业务侧进程初始化；迁移在后台运行，统计预初始化仍遵守原先的主线程和授权边界。 */
class AppProcess(private val context: Context) : ProcessHooks {
    private var diagnostics: AutoCloseable? = null

    override fun initialize() {
        AppLog.init(context)
        diagnostics = HostDiagnostics.install { priority, tag, message, failure ->
            when {
                priority >= Log.ERROR -> AppLog.e(tag, message, failure)
                priority >= Log.WARN -> AppLog.w(tag, message, failure)
                priority >= Log.INFO -> AppLog.i(tag, message)
                else -> AppLog.d(tag, message)
            }
        }
        Thread({ AppStorage.migrate(context) { AppLog.i("存储", it) } }, "storage-migration").apply {
            isDaemon = true
            start()
        }
        // Debug 实现为空；Release 正式统计仍须等用户同意协议后触发。
        Analytics.preInitialize(context)
    }

    override fun trimMemory(level: Int) {
        WallpaperPreview.clearCache()
        // 只回收首页闲置缓存，不销毁正在使用的演练场。
        if (
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            PreparedWallpaper.clear()
        }
    }

    override fun close() {
        diagnostics?.close()
        diagnostics = null
    }
}
