package app.luoxianlv.business

import android.content.Context
import app.luoxianlv.app.AppProcess
import app.luoxianlv.app.MainPage
import app.luoxianlv.app.OfficialCheckedPage
import app.luoxianlv.app.OfficialCheckedPlayback
import app.luoxianlv.app.OfficialRendererGate
import app.luoxianlv.hot.contract.BusinessFactory
import app.luoxianlv.hot.contract.NativePage
import app.luoxianlv.hot.contract.OfficialResources
import app.luoxianlv.playback.PlaybackServicePolicy
import app.luoxianlv.playback.PlaybackSession
import app.luoxianlv.practice.PracticePage
import app.luoxianlv.wallpaper.WallpaperImportPage
import app.luoxianlv.wallpaper.WallpaperPickerPage

/** 单一工厂覆盖真实 APP 的全部系统入口，不复制另一套界面或播放逻辑。 */
class AppBusinessFactory : BusinessFactory {
    override fun bindResources(resources: OfficialResources) = OfficialRendererGate.bind(resources)

    override fun page(route: String): NativePage =
        when (route) {
            "main" -> MainPage()
            "practice" -> PracticePage()
            "wallpaper.picker" -> WallpaperPickerPage()
            "wallpaper.import" -> WallpaperImportPage()
            else -> error("未知的业务页面：$route")
        }.let { if (OfficialRendererGate.required()) OfficialCheckedPage(it) else it }

    override fun playback(): app.luoxianlv.hot.contract.NativePlaybackSession =
        PlaybackSession().let {
            if (OfficialRendererGate.required()) OfficialCheckedPlayback(it) else it
        }

    override fun process(context: Context) = AppProcess(context)

    override fun foreground(context: Context) = PlaybackServicePolicy(context)
}
