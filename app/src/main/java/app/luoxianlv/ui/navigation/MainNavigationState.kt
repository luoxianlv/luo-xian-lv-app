package app.luoxianlv.ui.navigation

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
    )

/** 导航用基础值跨页面代际保存，不能把 Pager/导航控制器对象带到另一套业务代码。 */
class MainNavigationState {
    var tab = Routes.HOME
    val subPage = mutableStateOf<String?>(null)
    var moving = false

    fun restore(state: Bundle?) {
        tab = state?.getString("tab")?.takeIf { it in mainTabs } ?: Routes.HOME
        subPage.value = state?.getString("overlay")?.takeIf { it in overlayRoutes }
    }

    fun save() =
        Bundle().apply {
            putString("tab", tab)
            subPage.value?.let { putString("overlay", it) }
        }
}
