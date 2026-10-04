package app.luoxianlv.shared

import android.os.SystemClock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.luoxianlv.app.ErrorDialog
import kotlinx.coroutines.withTimeoutOrNull

private const val NOTICE_DURATION_MS = 4000L

private data class NoticeVisuals(
    override val message: String,
    val expiresAt: Long = SystemClock.elapsedRealtime() + NOTICE_DURATION_MS,
) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction = true
    // 统一由实际时间控制，避免无障碍推荐时长或动画时钟让提示长期停留。
    override val duration = SnackbarDuration.Indefinite
}

/** 新反馈替换旧反馈，不在队列中积压；后台休眠后的过期检查由宿主补齐。 */
suspend fun SnackbarHostState.showNotice(message: String) {
    currentSnackbarData?.dismiss()
    withTimeoutOrNull(NOTICE_DURATION_MS) { showSnackbar(NoticeVisuals(message)) }
}

@Composable
fun NoticeSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(hostState, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                val data = hostState.currentSnackbarData
                val visuals = data?.visuals as? NoticeVisuals
                if (visuals != null && SystemClock.elapsedRealtime() >= visuals.expiresAt)
                    data.dismiss()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    SnackbarHost(hostState, modifier)
}

/** 页面离开或提示到期都消费事件，避免 Pager 重建后重复显示旧提示。 */
@Composable
fun SnackbarNotice(
    notice: String?,
    hostState: SnackbarHostState,
    onConsumed: () -> Unit,
) {
    val latestNotice by rememberUpdatedState(notice)
    val consume by rememberUpdatedState(onConsumed)
    LaunchedEffect(notice, hostState) {
        notice?.let { message ->
            try {
                hostState.showNotice(message)
            } finally {
                // 新消息取消旧协程时，不能把新消息一起清掉。
                if (latestNotice == message) consume()
            }
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
