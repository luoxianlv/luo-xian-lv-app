package app.luoxianlv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 全局外观设置。
 *
 * 现在只剩「透明度」一项：页面底色由固定的渐变底承担（见 `ui/theme/Backdrop.kt`），
 * 原先的全局背景色 / 本地背景图 / 背景遮罩已整体移除。
 */
data class AppearanceSettings(
    /**
     * 统一透明度轴：0 = 完全不透明，1 = 最透明。
     */
    val transparency: Float = 0f,
    /** 是否从屏幕上方飘落微小雪花。默认开；纯装饰，且开启时会持续重绘。 */
    val snowEnabled: Boolean = true,
) {
    init {
        require(transparency in 0f..1f)
    }

    /** 控件容器不透明度：透明度越高，容器越透。 */
    val controlAlpha: Float get() = 1f - transparency * (1f - MIN_CONTROL_ALPHA)

    companion object {
        /** 控件容器的透明度上限：再透下去文字与背景就分不开了。 */
        const val MIN_CONTROL_ALPHA = 0.45f

        /** 归一化持久化数据：NaN 或越界值回退到合法区间，避免脏数据导致崩溃。 */
        fun normalizeTransparency(raw: Float): Float = if (raw.isNaN()) 0f else raw.coerceIn(0f, 1f)
    }
}

/** 外观偏好的持久化边界，同时向 Compose 主题广播即时变更。 */
object AppearanceStore {
    private const val NAME = "appearance_settings"
    private const val KEY_TRANSPARENCY = "transparency"
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
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return AppearanceSettings(
            transparency =
                AppearanceSettings.normalizeTransparency(prefs.getFloat(KEY_TRANSPARENCY, 0f)),
            snowEnabled = prefs.getBoolean(KEY_SNOW, true),
        )
    }

    fun save(
        context: Context,
        settings: AppearanceSettings,
    ) {
        require(settings.transparency in 0f..1f)
        context
            .getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_TRANSPARENCY, settings.transparency)
            .putBoolean(KEY_SNOW, settings.snowEnabled)
            .apply()
        _settings.value = settings
    }
}
