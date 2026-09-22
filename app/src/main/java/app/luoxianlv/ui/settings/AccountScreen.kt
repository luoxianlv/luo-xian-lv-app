package app.luoxianlv.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.luoxianlv.ui.components.ErrorDialogHost
import app.luoxianlv.ui.components.NavBarClearance
import app.luoxianlv.ui.components.SettingsCard
import app.luoxianlv.ui.components.SnackbarNotice

/** 登录页（独立页面，对齐 App 浅色风格：渐变底由导航层铺，这里放白卡表单）。
 * 已登录时显示账号信息与退出。 */
@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onShushuLogin: () -> Unit,
    onRegister: () -> Unit,
    snackbarHostState: SnackbarHostState,
    vm: SettingsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refresh() }
    SnackbarNotice(state.message, snackbarHostState, vm::consumeMessage)

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                if (state.session == null) "登录" else "网站账号",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(
            modifier =
                Modifier
                    .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = NavBarClearance),
        ) {
            SettingsCard {
                if (state.session == null) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = account,
                            onValueChange = { account = it },
                            label = { Text("邮箱") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        )
                        Button(
                            onClick = { vm.login(account, password) },
                            enabled = !state.busy && account.isNotBlank() && password.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (state.busy) "登录中…" else "登录") }
                        TextButton(
                            onClick = onShushuLogin,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        ) { Text("鼠鼠账号登录") }
                        TextButton(
                            onClick = onRegister,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("注册网站账号") }
                    }
                } else {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "已登录${state.session?.nickname?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        TextButton(
                            onClick = {
                                vm.logout()
                                onBack()
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) { Text("退出网站账号") }
                    }
                }
            }
        }
    }

    ErrorDialogHost(state.error, vm::dismissError)
}
