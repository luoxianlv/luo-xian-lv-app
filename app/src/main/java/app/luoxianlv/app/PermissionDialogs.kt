package app.luoxianlv.app

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.luoxianlv.business.ui.PageAlertDialog as AlertDialog

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
            Text("自动演奏需要「无障碍服务」来模拟点击游戏琴键，" + "悬浮窗控制器也会通过它显示在游戏上方。请先在系统设置中开启。")
        },
        confirmButton = { TextButton(onClick = onEnable) { Text("去开启") } },
        dismissButton = { TextButton(onClick = onLater) { Text("以后再说") } },
    )
}

/**
 * 无障碍引导：开启悬浮窗但服务不在线时展示。
 *
 * [alreadyEnabled] 用来区分两种“服务不在线”的原因，因为处理方式完全不同： 没开过去系统设置打开；开过但服务没跑起来（ROM 省电策略回收无障碍服务后很常见）
 * 需要关掉再重新开启才会重新绑定。
 */
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
                    "系统设置里无障碍已开启，但服务没有运行起来（省电策略常会把它回收掉）。" + "请在系统设置里把「落弦律」关掉再重新开启，然后回到这里重新点「启动」。"
                } else {
                    "在系统设置中开启口琴自动播放器，悬浮窗会显示在游戏上方。"
                }
            )
        },
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
        blockReplacement = false,
        onDismissRequest = onDismiss,
        title = { Text("未能完成") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("确定") } },
    )
}
