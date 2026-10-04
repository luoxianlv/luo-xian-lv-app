package app.luoxianlv.business.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties

/** 原生页面是否真正可交互；分页缓存、退出中的子页和热更候选都不拥有独立弹窗。 */
val LocalPageVisible = compositionLocalOf { true }

@Composable
fun PageAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
    blockReplacement: Boolean = true,
) {
    val visible = LocalPageVisible.current
    // 输入/确认尚未结束时，即使退后台也保留原实现，避免丢失未保存的草稿。
    PageReplacementGuard { !blockReplacement }
    if (visible)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            properties = properties,
        )
}

@Composable
fun PageDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val visible = LocalPageVisible.current
    PageReplacementGuard { false }
    if (visible) androidx.compose.ui.window.Dialog(onDismissRequest, properties, content)
}
