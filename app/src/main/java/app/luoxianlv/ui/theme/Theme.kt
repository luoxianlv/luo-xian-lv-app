package app.luoxianlv.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors =
    lightColorScheme(
        primary = PlayerPrimary,
        onPrimary = Color.White,
        primaryContainer = PlayerSurfaceTint,
        onPrimaryContainer = PlayerPrimaryDark,
        secondary = PlayerAccent,
        background = PlayerBg,
        surface = PlayerSurface,
        onBackground = PlayerText,
        onSurface = PlayerText,
        onSurfaceVariant = PlayerTextSecondary,
        surfaceVariant = PlayerSurfaceTint,
        outline = PlayerDivider,
        error = PlayerDanger,
    )

/**
 * 深色品牌色板。
 *
 * 与浅色不同，深色**不接 Android 12+ 动态取色**：页面底色始终是
 * [DarkBackdrop] 那支固定的深藏青渐变（底是我们自己定的，不是 Material 的 surface），
 * 动态取色会拿来一套紫灰的强调色，压在藏青底上不自洽。
 * 浅色那边保留动态取色是历史行为，这次不动。
 */
private val DarkColors =
    darkColorScheme(
        primary = PlayerPrimaryNight,
        onPrimary = PlayerOnPrimaryNight,
        primaryContainer = PlayerPrimaryContainerNight,
        onPrimaryContainer = PlayerOnPrimaryContainerNight,
        secondary = PlayerAccentNight,
        onSecondary = Color(0xFF00201B),
        background = PlayerBgNight,
        onBackground = PlayerTextNight,
        surface = PlayerSurfaceNight,
        onSurface = PlayerTextNight,
        surfaceVariant = PlayerSurfaceVariantNight,
        onSurfaceVariant = PlayerTextSecondaryNight,
        // 浮层容器：对话框/菜单的底，不透明。
        surfaceContainer = PlayerContainerNight,
        surfaceContainerHigh = PlayerContainerHighNight,
        outline = PlayerDividerNight,
        error = PlayerDangerNight,
        onError = Color(0xFF3B0510),
    )

/**
 * 落弦律 Material 3 主题：Android 12+ 动态取色（仅浅色），低版本回退品牌色。
 *
 * 页面底色由 `Backdrop.kt` 的渐变底承担；容器半透明固定为
 * [ON_BACKDROP_SURFACE_ALPHA]（深色下 [ON_BACKDROP_SURFACE_ALPHA_DARK]），
 * 文字与强调色保持不透明 —— 半透明容器要是连文字一起透，可读性会明显下降。
 *
 * @param darkTheme 是否深色。调用方（`MainActivity`）按用户的「深色模式」偏好
 *   与系统设置算出结果传进来，不在这里自己读偏好，
 *   这样预览和测试可以直接控制主题而不用造存储。
 */
@Composable
fun LuoXianLvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val baseColors =
        when {
            darkTheme -> DarkColors
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
            else -> LightColors
        }
    val alpha =
        if (darkTheme) {
            ON_BACKDROP_SURFACE_ALPHA_DARK
        } else {
            ON_BACKDROP_SURFACE_ALPHA
        }
    // 只动「容器」这一类角色，对话框/菜单用的 surfaceContainer* 刻意保持不透明。
    //
    // 取值已核对 material3 1.3.1 的 token 类（非推测）：
    //   DialogTokens.ContainerColor  = SurfaceContainerHigh  → AlertDialog
    //   MenuTokens.ContainerColor    = SurfaceContainer      → DropdownMenu
    //   CardTokens.ContainerColor    = SurfaceContainerLow   → 未指定颜色的 Card
    // 升级 Material 后若对话框重新变透明，先回来核对这几个 token。
    val colors =
        baseColors.copy(
            // 禁用色调抬升：Surface 的 tonalElevation 会把 surfaceTint 叠到表面上，
            // 叠加后不但带上色偏，不透明度也会变大（0.45 → 0.478）。
            // 结果同样是半透明容器，1dp 的卡片 / 3dp 的导航栏 / 0dp 的对话框颜色各不相同。
            surfaceTint = Color.Transparent,
            // 控件容器：卡片、导航栏、次级背景、按钮、选项卡。
            // surface 压到半透明：这些都盖在渐变底上，完全不透明会糊成一块块死板的色块。
            surface = baseColors.surface.copy(alpha = alpha),
            // 「发现」的搜索入口、「关于」的版本卡没有显式指定容器色，
            // 走的是 CardTokens 的 SurfaceContainerLow。这里也拉进半透明容器一档，
            // 否则深色下会冒出两块实心板，和旁边的卡片不是一个层次。
            surfaceContainerLow = baseColors.surface.copy(alpha = alpha),
            // 刻意不透明化这两个角色：它们是 Material 浮层的容器色。
            // 浮层在独立窗口里叠在黑色 scrim 上，跟着变透明只会更难辨认。
            surfaceContainerHigh = baseColors.surfaceContainerHigh,
            surfaceContainer = baseColors.surfaceContainer,
        )
    CompositionLocalProvider(LocalBackdropPalette provides backdropPalette(darkTheme)) {
        MaterialTheme(colorScheme = colors, typography = PlayerTypography) {
            SystemBarsAppearance(darkTheme)
            content()
        }
    }
}

/**
 * 让状态栏 / 手势条的图标跟着主题反色。
 *
 * `enableEdgeToEdge()` 的自动判定只认系统深色设置，用户在应用内选了「深色」
 * 而系统仍是浅色时，它会把深色底的图标继续画成黑。这里按最终结果显式覆盖一次：
 * 主题一变就重跑（`darkTheme` 在 `SideEffect` 的闭包里），
 * 系统设置变化时 [isSystemInDarkTheme] 也会触发重组。
 */
@Composable
private fun SystemBarsAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.context as? Activity)?.window ?: return
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            // 「浅色图标」= 深色主题
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}
