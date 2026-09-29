package app.luoxianlv.business

import android.content.Context
import app.luoxianlv.business.playback.PlaybackServicePolicy
import app.luoxianlv.business.playback.PlaybackSession
import app.luoxianlv.business.practice.PracticePage
import app.luoxianlv.business.wallpaper.WallpaperImportPage
import app.luoxianlv.business.wallpaper.WallpaperPickerPage
import app.luoxianlv.hot.contract.BusinessFactory
import app.luoxianlv.hot.contract.NativePage

/** 单一工厂覆盖真实 APP 的全部系统入口，不复制另一套界面或播放逻辑。 */
class AppBusinessFactory : BusinessFactory {
    override fun page(route: String): NativePage =
        when (route) {
            "main" -> MainPage()
            "practice" -> PracticePage()
            "wallpaper.picker" -> WallpaperPickerPage()
            "wallpaper.import" -> WallpaperImportPage()
            else -> error("未知的业务页面：$route")
        }

    override fun playback() = PlaybackSession()

    override fun process(context: Context) = AppProcess(context)

    override fun foreground(context: Context) = PlaybackServicePolicy(context)
}
