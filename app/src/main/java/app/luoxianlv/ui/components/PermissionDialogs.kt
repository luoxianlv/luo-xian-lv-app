package app.luoxianlv.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** 首次启动引导：确保用户开启无障碍服务（悬浮窗控制器依附于它）。 */
@Composable
fun OnboardingDialog(
    onEnable: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("欢迎使用落弦律") },
        text = {
            Text(
                "自动演奏需要「无障碍服务」来模拟点击游戏琴键，" +
                    "悬浮窗控制器也会通过它显示在游戏上方。请先在系统设置中开启。",
            )
        },
        confirmButton = { TextButton(onClick = onEnable) { Text("去开启") } },
        dismissButton = { TextButton(onClick = onLater) { Text("以后再说") } },
    )
}

/** 无障碍引导对话框：开启悬浮窗但服务未启用时展示。 */
@Composable
fun AccessibilityPromptDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("启用无障碍") },
        text = { Text("在系统设置中开启口风琴自动播放器，悬浮窗会显示在游戏上方。") },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text("打开设置") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 通用错误对话框：替代旧版 MaterialAlertDialogBuilder「未能完成」。 */
@Composable
fun ErrorDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("未能完成") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("确定") } },
    )
}
