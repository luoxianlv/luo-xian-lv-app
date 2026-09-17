package app.luoxianlv.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * 一次性提示：非空时弹 Snackbar，并立刻上报已消费。
 *
 * 各页面原本各写一段同样的 `LaunchedEffect`，且字段名互不相同
 * （message / downloaded / importedTitle / registered），
 * 消费时机也容易写错。统一到这里后，页面只提供「提示文本」和「消费回调」。
 */
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
