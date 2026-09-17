package app.luoxianlv.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.luoxianlv.data.AppearanceSettings

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

/** 落弦律 Material 3 主题：Android 12+ 动态取色，低版本回退品牌色。
 * 外观设置把统一透明度应用到容器角色上；页面底色由 `Backdrop.kt` 的渐变底承担。
 *
 * 透明度只作用于容器，文字与强调色保持不透明：
 * 否则拉到最大透明度时按钮文字、分组标题会一起发灰，可读性明显下降。 */
@Composable
fun LuoXianLvTheme(
    appearance: AppearanceSettings = AppearanceSettings(),
    content: @Composable () -> Unit,
) {
    val baseColors =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dynamicLightColorScheme(LocalContext.current)
        } else {
            LightColors
        }
    val alpha = appearance.controlAlpha
    val colors =
        baseColors.copy(
            // 禁用色调抬升：Surface 的 tonalElevation 会把 surfaceTint 叠到表面上，
            // 叠加后不但带上色偏，不透明度也会变大（0.45 → 0.478）。
            // 结果同样是半透明容器，1dp 的卡片 / 3dp 的导航栏 / 0dp 的对话框颜色各不相同。
            surfaceTint = Color.Transparent,
            // 控件容器：卡片、导航栏、次级背景。
            // surface 额外压到最多 ON_BACKDROP_SURFACE_ALPHA：按钮、选项卡、
            // 设置卡片这些全都盖在渐变底上，完全不透明会糊成一块块死白的板子。
            // 只动 surface —— 对话框/菜单用的 surfaceContainer* 刻意保持不透明（见下）。
            surface =
                baseColors.surface.copy(
                    alpha = minOf(alpha, ON_BACKDROP_SURFACE_ALPHA),
                ),
            surfaceVariant = baseColors.surfaceVariant.copy(alpha = alpha),
            surfaceContainerLowest = baseColors.surfaceContainerLowest.copy(alpha = alpha),
            surfaceContainerLow = baseColors.surfaceContainerLow.copy(alpha = alpha),
            surfaceContainerHighest = baseColors.surfaceContainerHighest.copy(alpha = alpha),
            primaryContainer = baseColors.primaryContainer.copy(alpha = alpha),
            secondaryContainer = baseColors.secondaryContainer.copy(alpha = alpha),
            tertiaryContainer = baseColors.tertiaryContainer.copy(alpha = alpha),
            // 刻意不透明化这两个角色：它们是 Material 浮层的容器色。
            // 浮层在独立窗口里叠在黑色 scrim 上，跟着变透明只会更难辨认。
            //
            // 取值已核对 material3 1.3.1 的 token 类（非推测）：
            //   DialogTokens.ContainerColor = SurfaceContainerHigh  → AlertDialog
            //   MenuTokens.ContainerColor   = SurfaceContainer      → DropdownMenu
            // 本项目的页面容器一律用 surface / surfaceVariant / *Container，不受影响。
            // 升级 Material 后若对话框重新变透明，先回来核对这两个 token。
            surfaceContainerHigh = baseColors.surfaceContainerHigh,
            surfaceContainer = baseColors.surfaceContainer,
        )
    MaterialTheme(colorScheme = colors, typography = PlayerTypography, content = content)
}
