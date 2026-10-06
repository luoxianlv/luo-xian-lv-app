package app.luoxianlv.app

import android.os.Bundle
import androidx.compose.runtime.mutableStateOf

internal val mainTabs = listOf(Routes.HOME, Routes.LIBRARY, Routes.DISCOVER, Routes.SETTINGS)
internal val overlayRoutes =
    listOf(
        Routes.SEARCH,
        Routes.PLATFORM,
        Routes.LOGIN,
        Routes.ABOUT,
        Routes.IMPORT,
        Routes.DIAGNOSTICS,
        Routes.ANALYTICS_DEBUG,
        Routes.EXPERIMENTAL,
        Routes.INPUT_MODE,
    )

/** 导航用基础值跨页面代际保存，不能把 Pager/导航控制器对象带到另一套业务代码。 */
class MainNavigationState {
    var tab = Routes.HOME
    val subPage = mutableStateOf<String?>(null)
    var startFloatingAfterSetup = false
    var moving = false

    fun restore(state: Bundle?) {
        tab = state?.getString("tab")?.takeIf { it in mainTabs } ?: Routes.HOME
        subPage.value = state?.getString("overlay")?.takeIf { it in overlayRoutes }
        startFloatingAfterSetup =
            subPage.value == Routes.INPUT_MODE && state?.getBoolean("floatingSetup", false) == true
    }

    fun save() =
        Bundle().apply {
            putString("tab", tab)
            subPage.value?.let { putString("overlay", it) }
            putBoolean(
                "floatingSetup",
                startFloatingAfterSetup && subPage.value == Routes.INPUT_MODE,
            )
        }
}

object Routes {
    /** 我的：首页式页面（插画 + 问候语 + 悬浮窗开关）。进入时隐藏底部导航栏。 */
    const val HOME = "home"

    /** 曲库：谱面列表。原「我的曲目」列表已移到左侧导航栏的这个入口。 */
    const val LIBRARY = "library"

    const val DISCOVER = "discover"
    const val SETTINGS = "settings"

    const val SEARCH = "search"
    const val PLATFORM = "platform"
    const val LOGIN = "login"
    const val ABOUT = "about"
    const val DIAGNOSTICS = "diagnostics"
    const val EXPERIMENTAL = "experimental"
    const val INPUT_MODE = "inputMode"

    /** 统计诊断：友盟集成测试排障页（仅 debug 包可见入口）。 */
    const val ANALYTICS_DEBUG = "analyticsDebug"

    /** 导入：从顶级 Tab 降为「曲库」里的子页面。 */
    const val IMPORT = "import"
}
