package app.luoxianlv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.luoxianlv.ui.theme.containerBorder

/**
 * 胶囊操作按钮：本应用统一的主动作样式（「我的」的「启动」定下来的）。
 *
 * `surface` 白底 + 细描边 + 1dp 阴影。之所以不用 `FilledTonalButton`：
 * 它的容器色是 `secondaryContainer`（浅蓝 #EAF3FF），压在同为蓝灰的渐变底上
 * 会糊成一片；白色才拉得开对比。
 *
 * [compact] 用于并排的双按钮（曲库的「导入谱子 / 平台下载」），
 * 矮一档、字号小一档，避免两个按钮各占半屏时显得笨重。
 */
@Composable
fun ActionPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    compact: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = if (compact) 46.dp else 56.dp),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = containerBorder(),
        // 填充已经半透（见 ON_BACKDROP_SURFACE_ALPHA），
        // 阴影画在填充之下会透上来把胶囊弄脏，边界交给描边。
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
