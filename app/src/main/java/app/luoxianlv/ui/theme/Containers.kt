package app.luoxianlv.ui.theme

import androidx.compose.foundation.BorderStroke
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
 * 控件容器当前是否半透明。
 *
 * 判据取 `surface` 的 alpha，因为 [LuoXianLvTheme] 是统一改写容器角色来实现透明度的。
 */
@Composable
fun translucentContainers(): Boolean = MaterialTheme.colorScheme.surface.alpha < 1f

/**
 * 卡片容器抬升。集中在这里，避免各处 `Card` 各写一套而漏改。
 *
 * 半透明时必须为 0：`Modifier.shadow` 画在填充**之下**，
 * 不透明时被完全盖住，一旦填充半透明，阴影就会透出来，
 * 让卡片发灰、与相邻容器颜色不一致。这也是「包裹器和偏好项颜色不一」的成因之一。
 */
@Composable
fun containerElevation(): CardElevation = CardDefaults.cardElevation(defaultElevation = if (translucentContainers()) 0.dp else 1.dp)

/**
 * 半透明容器的描边，与 [containerElevation] 配对使用。
 *
 * 阴影被拿掉后，卡片就失去了唯一的边界提示：
 * 45% 不透明的浅色卡片叠在图片上会直接「消失」，看不出范围。
 * 这里用一条细描边补回边界，保证任何透明度下卡片的层次都能辨认。
 */
@Composable
fun containerBorder(): BorderStroke? =
    if (translucentContainers()) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    } else {
        null
    }
