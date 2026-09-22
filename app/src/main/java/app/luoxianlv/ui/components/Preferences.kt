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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
 * 设置项图标色板：QQ 设置的标志之一就是一组高饱和但不刺眼的彩色图标，
 * 给每一项固定一个色相，用户扫颜色就能定位功能。
 */
val IconBlue = Color(0xFF3E7BE0)
val IconCyan = Color(0xFF3FA8C9)
val IconOrange = Color(0xFFE8862E)
val IconGreen = Color(0xFF4CAF50)
val IconPink = Color(0xFFD8659E)
val IconTeal = Color(0xFF2FA3A0)
val IconGray = Color(0xFF7A8AA0)
val IconUpdate = Color(0xFF5B8DEF)

/** 「深色模式」专用：靖蓝，与旁边的飘雪青、账号蓝区分得开。 */
val IconIndigo = Color(0xFF6C7BE0)

/**
 * 分组标题：卡片上方的小灰字。
 *
 * 配合「一组一张卡」用：标题在卡外贴着卡顶，组内各行不再另带标题，
 * 版面才是 QQ 那种「标题 + 白卡」的上下关系。设置主界面、播放诊断页
 * 都遵循这个语言。
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

/**
 * 偏好项：图标（可选）+ 标题 + 可选摘要 + 右侧箭头。
 *
 * [onClick] 传 null 表示只读行（如播放诊断的状态读出）：
 * 整行不可点击、无箭头，避免点了没反应的假入口。
 */
@Composable
fun PreferenceItem(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
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
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
