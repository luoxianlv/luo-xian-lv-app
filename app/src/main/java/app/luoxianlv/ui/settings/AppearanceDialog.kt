package app.luoxianlv.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.luoxianlv.data.AppearanceSettings

/**
 * 外观设置：只有一条统一的透明度轴。
 *
 * 原先的全局背景色 / 本地背景图 / 背景遮罩已整体移除 ——
 * 页面底色由固定的渐变底承担（见 `ui/theme/Backdrop.kt`），
 * 所以这一项现在只影响卡片、导航栏、对话框等**控件容器**的透明程度。
 */
@Composable
fun AppearanceDialog(
    initial: AppearanceSettings,
    onDismiss: () -> Unit,
    onSave: (AppearanceSettings) -> Unit,
) {
    var transparency by remember { mutableFloatStateOf(initial.transparency) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("控件透明度") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "透明度  ${"%.0f".format(transparency * 100)}%",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = transparency,
                    onValueChange = { transparency = it },
                    valueRange = 0f..1f,
                    steps = 9,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "作用于卡片、导航栏、对话框等控件容器，文字与强调色保持不透明。" +
                        "100% 时容器最透，下面的渐变底会透上来。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                // 用 initial.copy 而不是新建 AppearanceSettings：
                // 这个对话框只负责透明度，新建对象会把不归它管的字段
                // （比如飘雪开关）重置成默认值。
                onClick = { onSave(initial.copy(transparency = transparency)) },
            ) {
                Text("应用")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
