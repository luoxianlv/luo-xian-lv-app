package app.luoxianlv.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.luoxianlv.app.PermissionSettings
import app.luoxianlv.business.ui.PageAlertDialog as AlertDialog
import app.luoxianlv.business.ui.rememberPageLauncher
import app.luoxianlv.library.LibraryViewModel
import app.luoxianlv.playback.PlaybackConnection
import app.luoxianlv.shared.SettingsCard

@Composable
fun InputModeScreen(onBack: () -> Unit, startAfterSetup: Boolean = false) {
    val state = rememberInputModeState()
    val context = LocalContext.current
    val playback: LibraryViewModel? = if (startAfterSetup) viewModel() else null
    var guide by rememberSaveable { mutableStateOf<String?>(null) }
    var showPairing by remember { mutableStateOf(false) }
    var permissionHint by remember { mutableStateOf("") }
    var pairingAction by rememberSaveable { mutableStateOf("startPairing") }
    var pendingConnection by rememberSaveable { mutableStateOf<Bundle?>(null) }
    LaunchedEffect(state.mode) { permissionHint = "" }
    val documentation =
        rememberPageLauncher(
            "input_documentation",
            ActivityResultContracts.StartActivityForResult(),
        ) {}
    val permissionSettings =
        rememberPageLauncher(
            "input_permission_settings",
            ActivityResultContracts.StartActivityForResult(),
        ) {
            inputCommand("refresh")
        }
    val notificationPermission =
        rememberPageLauncher("input_notifications", ActivityResultContracts.RequestPermission()) {
            granted ->
            inputCommand("refresh")
            if (granted) {
                if (pairingNotificationsEnabled(context)) inputCommand(pairingAction)
                else {
                    permissionHint = "请开启无线调试配对通知，返回后再点开始配对。"
                    permissionSettings.launch(notificationSettings(context))
                }
            } else permissionHint = "请允许通知，才能在系统配对窗口中填写配对码。"
        }
    fun allowNotifications() {
        if (pairingAction != "startPairing") {
            inputCommand(pairingAction)
            return
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!state.notificationGranted) {
            permissionHint = "请在系统设置中开启落弦律的通知，返回后再点开始配对。"
            permissionSettings.launch(notificationSettings(context))
        } else inputCommand(pairingAction)
    }
    val networkPermission =
        rememberPageLauncher("input_network", ActivityResultContracts.RequestPermission()) { granted
            ->
            inputCommand("refresh")
            val connection = pendingConnection
            pendingConnection = null
            if (!granted) permissionHint = "请允许本地网络权限，才能查找手机上的无线调试。"
            else if (connection != null) {
                if (currentInputBridge()?.state()?.getString("mode") == "wireless")
                    inputCommand("connect", connection)
                else permissionHint = "输入模式已变化，请重新点击连接。"
            } else allowNotifications()
        }
    fun startPairing(action: String = "startPairing") {
        pairingAction = action
        permissionHint = ""
        if (
            Build.VERSION.SDK_INT >= 37 &&
                context.checkSelfPermission(LOCAL_NETWORK_PERMISSION) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            networkPermission.launch(LOCAL_NETWORK_PERMISSION)
        } else allowNotifications()
    }
    fun primaryAction() {
        val action = state.primaryAction ?: return
        when (action.command) {
            "pairingGuide" -> guide = "wireless"
            "openSettings" -> permissionSettings.launch(PermissionSettings.accessibility(context))
            "wifiSettings" -> permissionSettings.launch(Intent(Settings.ACTION_WIFI_SETTINGS))
            "connect" ->
                Bundle()
                    .apply {
                        putBoolean(
                            "openSettingsAfterStart",
                            state.mode == "wireless" && !state.wirelessEnabled,
                        )
                    }
                    .let { arguments ->
                        permissionHint = ""
                        if (
                            state.mode == "wireless" &&
                                Build.VERSION.SDK_INT >= 37 &&
                                context.checkSelfPermission(LOCAL_NETWORK_PERMISSION) !=
                                    PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingConnection = arguments
                            networkPermission.launch(LOCAL_NETWORK_PERMISSION)
                        } else inputCommand("connect", arguments)
                    }
            else -> inputCommand(action.command)
        }
    }
    BackHandler(enabled = guide != null) { guide = null }

    if (guide != null) {
        InputModeGuide(
            mode = checkNotNull(guide),
            state = state,
            permissionHint = permissionHint,
            onBack = { guide = null },
            onStartPairing = {
                if (!state.wifiConnected)
                    permissionSettings.launch(Intent(Settings.ACTION_WIFI_SETTINGS))
                else startPairing()
            },
            onManualPairing = {
                showPairing = true
                startPairing("discover")
            },
            onNotificationSettings = {
                permissionSettings.launch(notificationSettings(context))
            },
            onNetworkSettings = {
                permissionSettings.launch(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    )
                )
            },
            onShizukuSettings = {
                permissionSettings.launch(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:moe.shizuku.privileged.api"),
                    )
                )
            },
            onOpenDocumentation = { url ->
                documentation.launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "title") { InputModeTitle("输入模式", onBack) }
            if (!state.overlayGranted) {
                item(key = "overlay-permission") {
                    SettingsCard {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("先允许显示悬浮窗", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "打开后下滑，找到「悬浮窗／显示在其他应用上层」并允许，返回后继续选择输入模式。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = {
                                    permissionSettings.launch(PermissionSettings.overlay(context))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("去授权")
                            }
                            TextButton(
                                onClick = {
                                    permissionSettings.launch(
                                        PermissionSettings.overlayList(context)
                                    )
                                }
                            ) {
                                Text("找不到入口？打开权限列表")
                            }
                        }
                    }
                }
            }
            item(key = "modes") {
                SettingsCard {
                    InputModeOption("无障碍", "直接使用，手动触摸可能中断演奏", "accessibility", state)
                    InputModeOption("Shizuku", "通过 Shizuku 连接，可边操作边演奏", "shizuku", state)
                    InputModeOption("无线调试（内置）", "使用手机的无线调试，无需额外应用", "wireless", state)
                }
            }
            item(key = "status") {
                SettingsCard {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(state.statusTitle, style = MaterialTheme.typography.titleMedium)
                        Text(
                            permissionHint.ifBlank { state.statusSummary },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.primaryAction?.let { action ->
                            Button(onClick = ::primaryAction, modifier = Modifier.fillMaxWidth()) {
                                Text(action.label)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.overlayGranted && state.mode != "accessibility") {
                                TextButton(onClick = { guide = state.mode }) { Text("使用帮助") }
                            }
                            if (state.connected || state.busy || state.pairing) {
                                TextButton(onClick = { inputCommand("disconnect") }) {
                                    Text(if (state.busy || state.pairing) "取消连接" else "断开连接")
                                }
                            }
                        }
                    }
                }
            }
            if (startAfterSetup) {
                item(key = "start-floating") {
                    Button(
                        onClick = {
                            playback?.setFloatingEnabled(true)
                            onBack()
                        },
                        enabled = state.overlayGranted && state.usable,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("启动悬浮窗")
                    }
                }
            }
            item(key = "hint") {
                Text(
                    "自动识别按键需要无障碍；固定按键和演练场可使用已连接的 Shizuku 或无线调试。切换模式会暂停演奏。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
    if (showPairing) {
        PairingDialog(serviceFound = state.pairingPort > 0, onDismiss = { showPairing = false }) {
            code ->
            inputCommand("pair", Bundle().apply { putString("code", code) })
            showPairing = false
        }
    }
}

@Composable
internal fun InputModeTitle(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InputModeOption(title: String, summary: String, mode: String, state: InputModeState) {
    val select: () -> Unit = {
        if (state.mode != mode) {
            PlaybackConnection.instance?.pause()
            runCatching { currentInputBridge()?.select(mode) }
        }
    }
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = state.supported && state.overlayGranted, onClick = select)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = state.mode == mode,
            onClick = select,
            enabled = state.supported && state.overlayGranted,
        )
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PairingDialog(serviceFound: Boolean, onDismiss: () -> Unit, onPair: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("填写配对码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("请用分屏保持系统配对窗口打开。更方便的方式是下拉通知栏，在配对通知里填写。")
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { digit -> digit in '0'..'9' }.take(6) },
                    label = { Text("6 位配对码") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(if (serviceFound) "已找到配对窗口" else "正在查找，请保持系统配对窗口打开")
            }
        },
        confirmButton = {
            TextButton(onClick = { onPair(code) }, enabled = code.length == 6 && serviceFound) {
                Text("配对并连接")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun notificationSettings(context: Context): Intent {
    val manager = context.getSystemService(NotificationManager::class.java)
    val channel = manager?.getNotificationChannel(PAIRING_NOTIFICATION_CHANNEL)
    return if (
        manager?.areNotificationsEnabled() == true &&
            channel?.importance == NotificationManager.IMPORTANCE_NONE
    ) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, PAIRING_NOTIFICATION_CHANNEL)
    } else
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}

private fun pairingNotificationsEnabled(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    return manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(PAIRING_NOTIFICATION_CHANNEL)?.importance !=
            NotificationManager.IMPORTANCE_NONE
}

private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
private const val PAIRING_NOTIFICATION_CHANNEL = "input-wireless-pairing"
