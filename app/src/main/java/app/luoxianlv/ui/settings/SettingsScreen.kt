package app.luoxianlv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.luoxianlv.data.AccountSession
import app.luoxianlv.ui.components.ErrorDialogHost
import app.luoxianlv.ui.components.NavBarClearance
import app.luoxianlv.ui.components.PageTitle
import app.luoxianlv.ui.components.PreferenceDivider
import app.luoxianlv.ui.components.PreferenceItem
import app.luoxianlv.ui.components.PreferenceSection
import app.luoxianlv.ui.components.PreferenceSwitchItem
import app.luoxianlv.ui.components.SettingsCard
import app.luoxianlv.ui.components.SnackbarNotice

/** 设置页：整页一张白卡，卡内按组平铺（小标题 + 行 + 分隔线）；网站登录在对话框中完成。 */
@Composable
fun SettingsScreen(
    onLogin: () -> Unit,
    onAbout: () -> Unit,
    snackbarHostState: SnackbarHostState,
    vm: SettingsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showAppearance by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh() }
    SnackbarNotice(state.message, snackbarHostState, vm::consumeMessage)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = NavBarClearance),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { PageTitle("设置") }
        item {
            SettingsCard {
                PreferenceSection("账号") {
                    PreferenceItem(
                        title = "网站账号",
                        summary =
                            state.session?.let {
                                "已登录${it.nickname.takeIf { n -> n.isNotBlank() }?.let { n -> " · $n" }.orEmpty()}"
                            } ?: "未登录",
                    ) { onLogin() }
                }
                PreferenceDivider()
                PreferenceSection("外观") {
                    PreferenceItem(
                        title = "控件透明度",
                        summary = "${"%.0f".format(state.appearance.transparency * 100)}% · 影响卡片与导航栏等容器",
                    ) { showAppearance = true }
                    PreferenceDivider()
                    PreferenceSwitchItem(
                        title = "飘雪",
                        checked = state.appearance.snowEnabled,
                        onCheckedChange = vm::setSnowEnabled,
                        summary = "从屏幕上方飘落微小雪花",
                    )
                }
                PreferenceDivider()
                PreferenceSection("关于") {
                    PreferenceItem(title = "关于落弦律", onClick = onAbout)
                }
            }
        }
    }

    if (showAppearance) {
        AppearanceDialog(
            initial = state.appearance,
            onDismiss = { showAppearance = false },
            onSave = {
                vm.saveAppearance(it)
                showAppearance = false
            },
        )
    }
    ErrorDialogHost(state.error, vm::dismissError)
}

