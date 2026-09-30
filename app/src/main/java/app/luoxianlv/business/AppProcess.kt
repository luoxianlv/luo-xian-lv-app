package app.luoxianlv.business

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import app.luoxianlv.BuildConfig
import app.luoxianlv.core.Analytics
import app.luoxianlv.core.score.ScoreWork
import app.luoxianlv.data.DisclaimerStore
import app.luoxianlv.debug.AppLog
import app.luoxianlv.hot.contract.HostDiagnostics
import app.luoxianlv.hot.contract.ProcessHooks
import app.luoxianlv.hot.contract.ProcessOnce
import app.luoxianlv.storage.AppStorage
import app.luoxianlv.wallpaper.render.PreparedWallpaper
import app.luoxianlv.wallpaper.render.WallpaperPreview

/** 业务侧进程初始化；迁移在后台运行，统计预初始化仍遵守原先的主线程和授权边界。 */
class AppProcess(private val context: Context) : ProcessHooks {
    private var diagnostics: AutoCloseable? = null
    private var closed = false

    override fun initialize() {
        check(!closed) { "已退役进程业务不能初始化" }
        AppLog.init(context)
        diagnostics?.close()
        diagnostics = HostDiagnostics.install { priority, tag, message, failure ->
            when {
                priority >= Log.ERROR -> AppLog.e(tag, message, failure)
                priority >= Log.WARN -> AppLog.w(tag, message, failure)
                priority >= Log.INFO -> AppLog.i(tag, message)
                else -> AppLog.d(tag, message)
            }
        }
        if (ProcessOnce.claim("storage-migration"))
            check(
                BusinessJobs.thread("storage-migration") {
                    AppStorage.migrate(context) { AppLog.i("存储", it) }
                }
            )
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
        if (closed) return
        closed = true
        BusinessJobs.gate.retire()
        diagnostics?.close()
        diagnostics = null
        PreparedWallpaper.clear()
        WallpaperPreview.clearCache()
        ScoreWork.retire()
        AppLog.retire()
    }

    override fun canReplace() = !closed && BusinessJobs.gate.idle()

    override fun supportsRetirement() = true

    override fun quiesce() = !closed && BusinessJobs.gate.freeze()

    override fun resumeWork() {
        BusinessJobs.gate.resume()
    }

    override fun released() =
        closed && BusinessJobs.gate.released() && ScoreWork.released && AppLog.released

    override fun diagnosticsAllowed(): Boolean =
        !BuildConfig.DEBUG &&
            runCatching {
                    DisclaimerStore(context).agreedSha() == DisclaimerStore.currentSha(context)
                }
                .getOrDefault(false)
}
