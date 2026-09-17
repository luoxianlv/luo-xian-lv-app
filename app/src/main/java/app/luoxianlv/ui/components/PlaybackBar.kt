package app.luoxianlv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.luoxianlv.ui.theme.containerBorder
import app.luoxianlv.ui.theme.containerElevation

/**
 * 悬浮窗开关。
 *
 * 用 Material 的 [ListItem] 承载「图标 + 标题 + 状态 + 开关」：
 * 标题与状态各占一行，不再像原来那样把「已连接」和开关挤在同一行里；
 * 图标底色与图标色统一使用 primaryContainer / onPrimaryContainer 一对角色
 * （原来底色是 primaryContainer、图标却是 secondary，颜色对不上）。
 *
 * 整行可点，触控面积不再只有那个 40×22 的小开关。
 */
@Composable
fun PlaybackBar(
    floatingEnabled: Boolean,
    serviceConnected: Boolean,
    serviceError: String?,
    onFloatingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val healthy = serviceConnected && serviceError == null
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = containerElevation(),
        border = containerBorder(),
    ) {
        ListItem(
            headlineContent = { Text("悬浮窗") },
            supportingContent = {
                Text(
                    serviceError ?: if (serviceConnected) "已连接" else "未连接",
                    color =
                        if (healthy) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            },
            leadingContent = {
                Box(
                    modifier =
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
            trailingContent = {
                SmallSwitch(checked = floatingEnabled, onCheckedChange = onFloatingChange)
            },
            // 容器透明：外层 Card 已经提供了背景与描边。
            // 这里若沿用 ListItem 默认的 surface，半透明时会和 Card 再叠一层而变深。
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier =
                Modifier.clickable(role = Role.Switch) {
                    onFloatingChange(!floatingEnabled)
                },
        )
    }
}
