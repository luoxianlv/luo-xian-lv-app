package app.luoxianlv.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** 一次性提示：notice 非空时显示 Snackbar，随后通知调用方清空，避免重组重复提示。 */
@Composable
fun SnackbarNotice(
    notice: String?,
    hostState: SnackbarHostState,
    onConsumed: () -> Unit,
) {
    LaunchedEffect(notice) {
        notice?.let {
            hostState.showSnackbar(it)
            onConsumed()
        }
    }
}

/** 统一的错误弹窗宿主：各页面原本都写一遍 `state.error?.let { ErrorDialog(...) }`。 */
@Composable
fun ErrorDialogHost(
    error: String?,
    onDismiss: () -> Unit,
) {
    error?.let { ErrorDialog(it, onDismiss) }
}
