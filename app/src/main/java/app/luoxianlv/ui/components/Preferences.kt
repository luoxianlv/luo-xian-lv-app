package app.luoxianlv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.luoxianlv.ui.theme.containerElevation

/**
 * 设置页分组卡：**一组一张白卡**，卡与卡之间的缝隙露出渐变底。
 *
 * QQ 设置的版面语言：图标行归拢进圆角白卡，组与组用留白分开，
 * 而不是整页一张大卡再用小标题切分 —— 那样标题和行挤在一起，
 * 组的边界反而不清楚。卡片自身半透明（见 `theme/Theme.kt`），
 * 压在渐变底上不会像死白的板子。
 *
 * 刻意不加描边：半透明卡片在亮渐变上的边界已经够柔，
 * 再加一圈灰线会把「浮在渐变上的毛玻璃」切成「贴在屏幕上的纸片」。
 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = containerElevation(),
    ) {
        Column { content() }
    }
}

/**
 * 分组标题：卡片上方的小灰字。
 *
 * 配合「一组一张卡」用：标题在卡外贴着卡顶，组内各行不再另带标题，
 * 版面才是 QQ 那种「标题 + 白卡」的上下关系。播放诊断页那种
 * 卡片很多的密集页面仍用 [PreferenceSection]（标题留在卡内省纵向空间）。
 */
@Composable
fun PreferenceGroupCaption(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 6.dp, top = 2.dp),
    )
}

/**
 * 卡片内的分组：一个小标题 + 若干行。
 *
 * 用于**单卡多组**的页面（播放诊断）：标题标的是卡片里的这几行，所以放进卡片。
 * 组与组之间用 [PreferenceDivider] 分隔，第 1 组前面不用加。
 *
 * 「我的/设置」这种一组一张卡的页面不要用这个，用 [PreferenceGroupCaption]。
 */
@Composable
fun PreferenceSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
    )
    content()
}

/**
 * 行与行 / 组与组之间的分割线。
 *
 * 默认左右各留 [horizontalPadding]（16dp，等于卡片内容边距）：一条居中的
 * 悬浮短线，两端都有呼吸感，不顶到卡片边缘。
 * 想铺满整行传 <code>0.dp</code>；想对齐正文再加大。
 */
@Composable
fun PreferenceDivider(horizontalPadding: Dp = 16.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = horizontalPadding),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * QQ 风格图标底：圆角色块 + 低透明同色底，图标本身用纯色。
 *
 * 36dp 色块 + 10dp 圆角是 QQ 设置图标的标志性比例（比纯图标重、比头像轻）。
 */
@Composable
private fun PreferenceIconChip(
    icon: ImageVector,
    tint: Color,
) {
    Box(
        modifier =
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 偏好项：图标（可选）+ 标题 + 可选摘要 + 右侧箭头。 */
@Composable
fun PreferenceItem(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            PreferenceIconChip(icon, iconTint)
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 开关型偏好项：图标（可选）+ 标题 + 可选摘要 + 右侧紧凑型开关（点击整行也可切换）。 */
@Composable
fun PreferenceSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            PreferenceIconChip(icon, iconTint)
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        SmallSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
