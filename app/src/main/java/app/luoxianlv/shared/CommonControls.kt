package app.luoxianlv.shared

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.luoxianlv.ui.theme.OnBackdropContent
import app.luoxianlv.ui.theme.PlayerQqBlue

/**
 * 胶囊操作按钮：本应用统一的主动作样式（「我的」的「启动」定下来的）。
 *
 * 默认使用 `surface`，强调动作可指定容器与内容色。之所以不用 `FilledTonalButton`： 它的容器色是
 * `secondaryContainer`（浅蓝 #EAF3FF），压在同为蓝灰的渐变底上 会糊成一片；白色才拉得开对比。
 *
 * [compact] 用于并排的双按钮（曲库的「导入谱子 / 平台下载」）， 矮一档、字号小一档，避免两个按钮各占半屏时显得笨重。
 */
@Composable
fun ActionPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    compact: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = if (compact) 46.dp else 56.dp),
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
        // 填充已经半透（见 ON_BACKDROP_SURFACE_ALPHA），
        // 阴影画在填充之下会透上来把胶囊弄脏，所以阴影也不用；
        // 边界靠半透明填充本身与渐变底的明暗差。
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier =
                Modifier.padding(
                    horizontal = if (compact) 14.dp else 20.dp,
                    vertical = if (compact) 12.dp else 16.dp,
                ),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 18.dp else 20.dp),
                )
                Spacer(modifier = Modifier.width(if (compact) 6.dp else 8.dp))
            }
            Text(
                label,
                style =
                    if (compact) {
                        MaterialTheme.typography.labelLarge
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/** 顶级页面大标题：标题 + 可选右侧计数/说明。 我的 / 曲库 / 发现 / 设置共用同一套排版，避免各写一遍字号与对齐。 */
@Composable
fun PageTitle(
    title: String,
    trailing: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            // 深藏青：页面标题现在压在蓝灰渐变底上，浅灰会糊；
            // 发现页没有渐变底，这个颜色在浅灰背景上同样能看。
            color = OnBackdropContent,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelMedium,
                color = OnBackdropContent.copy(alpha = 0.72f),
            )
        }
    }
}

/** 紧凑型开关（40×22dp）：替代默认 M3 Switch（52×32dp）， 用于偏好项、播放条等空间紧凑的场景。 */
@Composable
fun SmallSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackColor by
        animateColorAsState(
            targetValue =
                if (checked) {
                    // 强调蓝用 QQ 蓝而非主色：默认主色偏深，小尺寸轨道里更显沉闷
                    PlayerQqBlue
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            label = "small-switch-track",
        )
    val thumbOffset by
        animateDpAsState(
            targetValue = if (checked) 20.dp else 2.dp,
            label = "small-switch-thumb",
        )
    Box(
        modifier =
            modifier
                .width(40.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(trackColor)
                .clickable(role = Role.Switch) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier =
                Modifier.offset(x = thumbOffset)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.White)
        )
    }
}
