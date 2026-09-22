package app.luoxianlv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 主题模式：跟随系统 / 强制浅色 / 强制深色。
 *
 * 存成枚举名而不是序号：将来插入新选项时，老用户的偏好不会因为下标移位而跳到别的值。
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    /** 把「系统当前是否深色」折算成「本应用此刻是否深色」。 */
    fun isDark(systemDark: Boolean): Boolean =
        when (this) {
            SYSTEM -> systemDark
            LIGHT -> false
            DARK -> true
        }

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.name == key } ?: SYSTEM
    }
}

/**
 * 全局外观设置：主题模式、飘雪动效与「我的」页的两项自定义（一言文案、左侧竖栏开关）。
 *
 * 页面底色由 `ui/theme/Backdrop.kt` 的渐变底承担（深浅各一套），
 * 控件容器的半透明由主题固定（见 `theme/Containers.kt` 的两个 alpha 常量）。
 * 原先可调的全局背景 / 控件透明度已整体移除。
 */
data class AppearanceSettings(
    /** 深浅色模式。默认跟随系统：系统是深色的用户装上就该是深色。 */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 是否从屏幕上方飘落微小雪花。默认开；纯装饰，且开启时会持续重绘。 */
    val snowEnabled: Boolean = true,
    /** 「我的」页问候语下方的一言文案；空串表示跟随内置默认（见 HomeScreen 的 HOME_QUOTE）。 */
    val homeQuote: String = "",
    /** 「我的」页左侧竖栏是否展开。默认开。 */
    val sideRailEnabled: Boolean = true,
)

/** 外观偏好的持久化边界，同时向 Compose 广播即时变更。 */
object AppearanceStore {
    private const val NAME = "appearance_settings"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_SNOW = "snow_enabled"
    private const val KEY_HOME_QUOTE = "home_quote"
    private const val KEY_SIDE_RAIL = "side_rail_enabled"

    /** 已移除的全局背景图目录，启动时清理一次。 */
    private const val LEGACY_IMAGE_DIR = "background"

    private val _settings = MutableStateFlow(AppearanceSettings())
    val settings = _settings.asStateFlow()

    fun initialize(context: Context) {
        // 一次性清理：全局背景图功能已移除，私有目录里的历史副本留着只会白占空间。
        // 之后每次启动都是一次不存在的删除，开销可忽略。
        runCatching { File(context.filesDir, LEGACY_IMAGE_DIR).deleteRecursively() }
        _settings.value = load(context)
    }

    fun load(context: Context): AppearanceSettings {
        val prefs = Kv.of(context, NAME)
        return AppearanceSettings(
            themeMode = ThemeMode.fromKey(prefs.getString(KEY_THEME_MODE, null)),
            snowEnabled = prefs.getBoolean(KEY_SNOW, true),
            homeQuote = prefs.getString(KEY_HOME_QUOTE, null).orEmpty(),
            sideRailEnabled = prefs.getBoolean(KEY_SIDE_RAIL, true),
        )
    }

    fun save(
        context: Context,
        settings: AppearanceSettings,
    ) {
        Kv
            .of(context, NAME)
            .edit()
            .putString(KEY_THEME_MODE, settings.themeMode.name)
            .putBoolean(KEY_SNOW, settings.snowEnabled)
            .putString(KEY_HOME_QUOTE, settings.homeQuote)
            .putBoolean(KEY_SIDE_RAIL, settings.sideRailEnabled)
            .apply()
        _settings.value = settings
    }
}
