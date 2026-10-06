package app.luoxianlv.app

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.luoxianlv.business.ui.PageAlertDialog as AlertDialog

/** 首次启动说明；具体授权按悬浮窗权限、输入模式的顺序进行。 */
@Composable
fun OnboardingDialog(
    onEnable: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("欢迎使用落弦律") },
        text = {
            Text("先允许悬浮窗显示在其他应用上方，再选择演奏使用的输入模式。按指引设置后即可使用。")
        },
        confirmButton = { TextButton(onClick = onEnable) { Text("开始设置") } },
        dismissButton = { TextButton(onClick = onLater) { Text("以后再说") } },
    )
}

/** 无障碍开关与实际连接分别检查，不能凭开关推断服务已运行。 */
@Composable
fun AccessibilityPromptDialog(
    alreadyEnabled: Boolean,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (alreadyEnabled) "无障碍服务未运行" else "启用无障碍") },
        text = {
            Text(
                if (alreadyEnabled) {
                    "无障碍已开启，但连接尚未就绪。请在系统设置中检查落弦律，返回后再试。"
                } else {
                    "在系统设置中开启落弦律的无障碍服务，用于自动识别和演奏。"
                }
            )
        },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text("打开设置") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun OverlayPermissionPromptDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("允许显示悬浮窗") },
        text = { Text("打开后下滑，找到「悬浮窗／显示在其他应用上层」并允许，返回后继续选择输入模式。") },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text("去授权") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun InputModePromptDialog(onConfigure: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置输入模式") },
        text = { Text("当前输入模式尚未连接。完成设置后即可启动悬浮窗。") },
        confirmButton = { TextButton(onClick = onConfigure) { Text("去设置") } },
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
        blockReplacement = false,
        onDismissRequest = onDismiss,
        title = { Text("未能完成") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("确定") } },
    )
}
