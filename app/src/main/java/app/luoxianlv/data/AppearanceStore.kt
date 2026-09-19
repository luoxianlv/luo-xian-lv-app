package app.luoxianlv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 全局外观设置。
 *
 * 现在只剩「飘雪」一项：页面底色由固定的渐变底承担（见 `ui/theme/Backdrop.kt`），
 * 控件容器的半透明由主题固定（见 `theme/Theme.kt` 的 ON_BACKDROP_SURFACE_ALPHA）。
 * 原先可调的全局背景 / 控件透明度已整体移除。
 */
data class AppearanceSettings(
    /** 是否从屏幕上方飘落微小雪花。默认开；纯装饰，且开启时会持续重绘。 */
    val snowEnabled: Boolean = true,
)

/** 外观偏好的持久化边界，同时向 Compose 广播即时变更。 */
object AppearanceStore {
    private const val NAME = "appearance_settings"
    private const val KEY_SNOW = "snow_enabled"

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
        return AppearanceSettings(snowEnabled = prefs.getBoolean(KEY_SNOW, true))
    }

    fun save(
        context: Context,
        settings: AppearanceSettings,
    ) {
        Kv
            .of(context, NAME)
            .edit()
            .putBoolean(KEY_SNOW, settings.snowEnabled)
            .apply()
        _settings.value = settings
    }
}
