package app.luoxianlv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 主题里**不属于 Material 色板**的那部分配色。
 *
 * 这套设计的层次是「整屏渐变底 + 半透明容器」，而渐变底、压在底上的字色、
 * 「我的」页内容卡的渐变、曲库分段按钮的选中块都是本项目自造的取值，
 * Material 的 colorScheme 里没有对应角色（硬塞进 primary/tertiary 只会更难维护）。
 * 集中在这里，深浅两套并列，改配色只看这一个文件。
 */
@Immutable
data class BackdropPalette(
    /** 顶级页面的整屏渐变底。 */
    val brush: Brush,
    /** 直接压在渐变底上（没有容器兜着）的文字与图标色。 */
    val content: Color,
    /** 「我的」页内容卡的渐变：整张卡铺一支，插画与信息区共用。 */
    val heroBrush: Brush,
    /** 曲库分段按钮的选中块。未选中态是 surface，选中态靠它拉开。 */
    val segmentSelected: Color,
)

/**
 * 浅色：本项目原来的基调，取值与历史实现逐一对齐，不随这次深色支持改动。
 *
 * 渐变整体偏亮偏蓝，压在底上的字用深藏青
 * （[androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant]
 * 那种浅灰会糊在一起）。
 */
private val LightBackdrop =
    BackdropPalette(
        brush =
            Brush.verticalGradient(
                listOf(Color(0xFFA9D7EB), Color(0xFFD8DCE8), Color(0xFFBBC1D3)),
            ),
        content = Color(0xFF29445A),
        heroBrush =
            Brush.linearGradient(
                listOf(Color(0xFF8FD8F7), Color(0xFFDCE6F2), Color(0xFFF4DDEB)),
            ),
        // 选中态同样留一点透明：它是压在渐变上的实心块，全不透明会显得闷。
        // 0.55 的藏青叠在渐变上仍是中深石板色，和白底未选中态对比依旧明确。
        segmentSelected = Color(0xFF29445A).copy(alpha = ON_BACKDROP_SURFACE_ALPHA),
    )

/**
 * 深色：把浅色那支渐变按同一组色相压到深藏青，结构不变。
 *
 * 三个色站与 [LightBackdrop] 一一对应（偏蓝 → 灰 → 灰蓝），
 * 「我的」页内容卡同理保留浅色那支的蓝 → 灰 → 粉三段，
 * 只是统一压暗并降饱和 —— 这样换主题时看到的是同一个界面换了光，
 * 而不是另一套设计。
 *
 * 底上的字反过来用浅蓝白（`#D7E2F0`）：底色已经很深，任何深色字都会糊。
 */
private val DarkBackdrop =
    BackdropPalette(
        brush =
            Brush.verticalGradient(
                listOf(Color(0xFF1B2436), Color(0xFF232C42), Color(0xFF141A28)),
            ),
        content = Color(0xFFD7E2F0),
        heroBrush =
            Brush.linearGradient(
                listOf(Color(0xFF1E3A55), Color(0xFF243044), Color(0xFF3A2A3E)),
            ),
        // 深色下选中块反过来要比未选中的 surface **更亮**才叫「选中」。
        // 用不透明的中蓝实色块：白字约 5:1，色相上也一眼分得出来。
        segmentSelected = Color(0xFF2F6FB8),
    )

/** 当前主题的底色方案，由 [LuoXianLvTheme] 注入。默认浅色，供预览与未套主题的场景兜底。 */
val LocalBackdropPalette = staticCompositionLocalOf { LightBackdrop }

internal fun backdropPalette(dark: Boolean): BackdropPalette = if (dark) DarkBackdrop else LightBackdrop

/**
 * 直接压在渐变底上的文字与图标颜色。
 *
 * 保留属性名与写法（`OnBackdropContent.copy(alpha = 0.72f)`），
 * 这样十几处调用点不用改，深浅切换由主题统一决定。
 */
val OnBackdropContent: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalBackdropPalette.current.content

/**
 * 顶级页面的整屏渐变底。
 *
 * 由 [app.luoxianlv.ui.navigation.AppNavHost] 画在系统栏内边距**之外**，
 * 所以能 edge-to-edge，不会在状态栏 / 手势条下方露出背景色接缝。
 */
val BackdropBrush: Brush
    @Composable
    @ReadOnlyComposable
    get() = LocalBackdropPalette.current.brush

/** 铺满可用空间的渐变底。 */
@Composable
fun GradientBackdrop(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(BackdropBrush))
}
