package app.luoxianlv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 顶级页面的整屏渐变底。
 *
 * 这是「我的」页定下来的视觉基调，现在「曲库」「设置」也共用：
 * 渐变自身是底，白色卡片浮在它上面，两层一深一浅把层次拉开 ——
 * 如果底色也用近白（全局背景默认 #F6F7FB），白卡压白底就几乎没有分界。
 */
val BackdropBrush: Brush =
    Brush.verticalGradient(
        listOf(Color(0xFFA9D7EB), Color(0xFFD8DCE8), Color(0xFFBBC1D3)),
    )

/**
 * 直接压在渐变底上的文字与图标颜色。
 *
 * 渐变整体偏亮偏蓝，用 [androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant]
 * 那种浅灰会糊在一起，必须用深藏青才够对比。
 */
val OnBackdropContent: Color = Color(0xFF29445A)

/**
 * 铺满可用空间的渐变底。
 *
 * 由 [app.luoxianlv.ui.navigation.AppNavHost] 画在系统栏内边距**之外**，
 * 所以能 edge-to-edge，不会在状态栏 / 手势条下方露出背景色接缝。
 */
@Composable
fun GradientBackdrop(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(BackdropBrush))
}
