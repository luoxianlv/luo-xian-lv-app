package app.luoxianlv.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.luoxianlv.BuildConfig
import app.luoxianlv.business.ui.AboutContent
import app.luoxianlv.ui.components.ErrorDialogHost
import app.luoxianlv.ui.components.NavBarClearance

/** 宿主暂时提供更新状态和错误交互，页面内容已进入可动态打包的业务源码。 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    onCheckUpdate: () -> Unit,
    checkingUpdate: Boolean,
    vm: SettingsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    AboutContent(BuildConfig.VERSION_NAME, checkingUpdate, onBack, onCheckUpdate, NavBarClearance)
    ErrorDialogHost(state.error, vm::dismissError)
}
