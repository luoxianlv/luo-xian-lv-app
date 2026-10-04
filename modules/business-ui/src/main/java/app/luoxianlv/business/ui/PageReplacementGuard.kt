package app.luoxianlv.business.ui

import androidx.compose.runtime.*

/** 用业务当前状态判断安全点；读取闭包中的 State，可立即看到尚未重组的输入/任务变更。 */
@Composable
fun PageReplacementGuard(allowed: () -> Boolean) {
    val page = LocalNativePage.current
    val current by rememberUpdatedState(allowed)
    DisposableEffect(page) {
        val guard = page?.guardReplacement { current() }
        onDispose { guard?.close() }
    }
}
