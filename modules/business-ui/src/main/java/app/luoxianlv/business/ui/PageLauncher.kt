package app.luoxianlv.business.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityOptionsCompat

/** 使用稳定业务键恢复系统结果；尚未迁移的 Activity 继续使用原来的 Jetpack 入口。 */
@Composable
fun <I, O> rememberPageLauncher(
    key: String,
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ActivityResultLauncher<I> {
    val page = LocalNativePage.current
    if (page == null) return rememberLauncherForActivityResult(contract, onResult)
    val context = LocalContext.current
    val currentCallback by rememberUpdatedState(onResult)
    val currentContract by rememberUpdatedState(contract)
    DisposableEffect(page, key) {
        page.registerResult(key) { code, data ->
            currentCallback(currentContract.parseResult(code, data))
        }
        onDispose { page.unregisterResult(key) }
    }
    return remember(page, key) {
        object : ActivityResultLauncher<I>() {
            override fun launch(input: I, options: ActivityOptionsCompat?) {
                val immediate = currentContract.getSynchronousResult(context, input)
                if (immediate != null) currentCallback(immediate.value)
                else
                    page.launchResult(
                        key,
                        currentContract.createIntent(context, input),
                        options?.toBundle(),
                    )
            }

            override fun unregister() {
                page.unregisterResult(key)
            }

            override val contract: ActivityResultContract<I, *>
                get() = currentContract
        }
    }
}
