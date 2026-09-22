package app.luoxianlv.ui.theme

import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * 压在渐变底上的容器（卡片、按钮、选项卡、导航栏、设置卡）的不透明度上限。
 *
 * 这些都是盖在渐变上的面板，全不透明会变成一块块死白的板子。
 *
 * 下限受两个因素约束：
 * 1. **看得出来**：渐变本身很浅（#A9D7EB..#BBC1D3），白底透明度高到
 *    0.78 时混合结果与纯白只差 10 来个色阶，实际看起来仍是“不透明”。
 *    压到 0.55 时卡片顶部约为 (216,237,246)，蓝调才显出来。
 * 2. **文字可读**：卡片内小字号说明用的是 onSurfaceVariant（#697386，中灰），
 *    卡片越接近渐变、对比越低。0.55 时约 3.8:1，是能接受的下限。
 *
 * 两个约束夹出来的区间很窄，所以改这个值前先把上面两条都算一遍。
 */
const val ON_BACKDROP_SURFACE_ALPHA = 0.55f

/**
 * 深色模式的容器不透明度。
 *
 * 比浅色的 0.55 高一档，因为深色底下容器的余量本来就小：
 * 渐变底 (#1B2436..#141A28) 与容器基色 (#3B4863) 的明度差只有二三十个色阶，
 * 再压到 0.55 就快分不出「板子」和「洞」了。0.62 叠出来的约是 (49,62,88)，
 * 与底色拉开一档，同时渐变仍然透得出来。
 *
 * 下限同样受文字可读性约束：[PlayerTextSecondaryNight] 压在混合色上约 5:1。
 */
const val ON_BACKDROP_SURFACE_ALPHA_DARK = 0.62f

/**
 * 控件容器当前是否半透明。
 *
 * 判据取 `surface` 的 alpha，因为 [LuoXianLvTheme] 统一把 surface 压到
 * [ON_BACKDROP_SURFACE_ALPHA]（深色下是 [ON_BACKDROP_SURFACE_ALPHA_DARK]）。
 * 控件透明度调节移除后这恒为 true，
 * 保留判断是为了将来重新引入不透明容器时，各卡片自动恢复阴影。
 */
@Composable
fun translucentContainers(): Boolean = MaterialTheme.colorScheme.surface.alpha < 1f

/**
 * 卡片容器抬升。集中在这里，避免各处 `Card` 各写一套而漏改。
 *
 * 半透明时必须为 0：`Modifier.shadow` 画在填充**之下**，
 * 不透明时被完全盖住，一旦填充半透明，阴影就会透出来，
 * 让卡片发灰、与相邻容器颜色不一致。这也是「包裹器和偏好项颜色不一」的成因之一。
 *
 * 边界刻意不用描边补：半透明卡片在亮渐变上靠填充与背景的明暗差就能分层，
 * 一圈灰线只会把「浮在渐变上的毛玻璃」切成「贴在屏幕上的纸片」。
 */
@Composable
fun containerElevation(): CardElevation = CardDefaults.cardElevation(defaultElevation = if (translucentContainers()) 0.dp else 1.dp)
