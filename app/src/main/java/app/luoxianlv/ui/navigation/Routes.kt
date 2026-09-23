package app.luoxianlv.ui.navigation

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

    /** 统计诊断：友盟集成测试排障页（仅 debug 包可见入口）。 */
    const val ANALYTICS_DEBUG = "analyticsDebug"

    /** 导入：从顶级 Tab 降为「曲库」里的子页面。 */
    const val IMPORT = "import"
}
