package app.luoxianlv.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.luoxianlv.data.AccountSession
import app.luoxianlv.data.ThemeMode
import app.luoxianlv.service.KeepAlive
import app.luoxianlv.ui.components.ErrorDialogHost
import app.luoxianlv.ui.components.IconBlue
import app.luoxianlv.ui.components.IconCyan
import app.luoxianlv.ui.components.IconGray
import app.luoxianlv.ui.components.IconGreen
import app.luoxianlv.ui.components.IconIndigo
import app.luoxianlv.ui.components.IconOrange
import app.luoxianlv.ui.components.IconPink
import app.luoxianlv.ui.components.IconTeal
import app.luoxianlv.ui.components.IconUpdate
import app.luoxianlv.ui.components.NavBarClearance
import app.luoxianlv.ui.components.PageTitle
import app.luoxianlv.ui.components.PreferenceGroupCaption
import app.luoxianlv.ui.components.PreferenceItem
import app.luoxianlv.ui.components.PreferenceSwitchItem
import app.luoxianlv.ui.components.SettingsCard
import app.luoxianlv.ui.components.SnackbarNotice

/**
 * 设置页：QQ 式分组 —— 一组一张白卡，组标题小灰字贴在卡上方，
 * 每项一个彩色圆角方块图标 + 标题 + 摘要 + 右侧箭头；网站登录在对话框中完成。
 */
@Composable
fun SettingsScreen(
    onLogin: () -> Unit,
    onAbout: () -> Unit,
    onDiagnostics: () -> Unit,
    snackbarHostState: SnackbarHostState,
    vm: SettingsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showThemePicker by remember { mutableStateOf(false) }
    val notificationPermission =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            vm.refresh()
            if (!granted) KeepAlive.openNotificationSettings(context)
        }

    LaunchedEffect(Unit) { vm.refresh() }
    // 从系统授权页返回时刷新保活状态（电池白名单 / 通知权限都在系统页里改）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    SnackbarNotice(state.message, snackbarHostState, vm::consumeMessage)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = NavBarClearance),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PageTitle("设置") }

        // 组标题 + 白卡是一个视觉单元，放进同一个 item，组间留白交给 spacedBy。
        item {
            Column {
                PreferenceGroupCaption("账号")
                Spacer(modifier = Modifier.height(6.dp))
                SettingsCard {
                    PreferenceItem(
                        title = "网站账号",
                        summary =
                            state.session?.let {
                                "已登录${it.nickname.takeIf { n -> n.isNotBlank() }?.let { n -> " · $n" }.orEmpty()}"
                            } ?: "未登录",
                        icon = Icons.Filled.Person,
                        iconTint = IconBlue,
                    ) { onLogin() }
                }
            }
        }

        item {
            Column {
                PreferenceGroupCaption("外观")
                Spacer(modifier = Modifier.height(6.dp))
                SettingsCard {
                    PreferenceItem(
                        title = "深色模式",
                        summary = state.appearance.themeMode.label(),
                        icon = Icons.Filled.DarkMode,
                        iconTint = IconIndigo,
                    ) { showThemePicker = true }
                    PreferenceSwitchItem(
                        title = "飘雪",
                        checked = state.appearance.snowEnabled,
                        onCheckedChange = vm::setSnowEnabled,
                        summary = "显示雪花动效",
                        icon = Icons.Filled.AcUnit,
                        iconTint = IconCyan,
                    )
                }
            }
        }

        item {
            Column {
                PreferenceGroupCaption("后台运行")
                Spacer(modifier = Modifier.height(6.dp))
                SettingsCard {
                    PreferenceItem(
                        title = "播放诊断",
                        summary = "查看播放状态与诊断记录",
                        icon = Icons.Filled.Speed,
                        iconTint = IconOrange,
                        onClick = onDiagnostics,
                    )
                    PreferenceItem(
                        title = "忽略电池优化",
                        summary =
                            if (state.keepAlive.batteryExempt) {
                                "已允许"
                            } else {
                                "未允许，后台播放可能中断"
                            },
                        icon = Icons.Filled.BatterySaver,
                        iconTint = IconGreen,
                    ) { KeepAlive.requestBatteryExemption(context) }
                    PreferenceItem(
                        title = "播放通知",
                        summary =
                            if (state.keepAlive.notificationsGranted) {
                                "已开启"
                            } else {
                                "未开启，点击设置"
                            },
                        icon = Icons.Filled.Notifications,
                        iconTint = IconPink,
                    ) {
                        if (Build.VERSION.SDK_INT >= 33 &&
                            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            KeepAlive.openNotificationSettings(context)
                        }
                    }
                    PreferenceItem(
                        title = "自启动",
                        summary = "在系统设置中允许落弦律自启动",
                        icon = Icons.Filled.RocketLaunch,
                        iconTint = IconTeal,
                    ) { KeepAlive.openAutoStartSettings(context) }
                }
            }
        }

        item {
            Column {
                PreferenceGroupCaption("关于")
                Spacer(modifier = Modifier.height(6.dp))
                SettingsCard {
                    /* 暂停提供关闭自动检查的入口；保留开关代码便于恢复。
                    PreferenceSwitchItem(
                        title = "自动检查更新",
                        checked = state.autoUpdate,
                        onCheckedChange = vm::setAutoUpdate,
                        summary = "启动和回到前台时自动检查新版本",
                        icon = Icons.Filled.SystemUpdate,
                        iconTint = IconUpdate,
                    )
                    */
                    PreferenceItem(
                        title = "关于落弦律",
                        icon = Icons.Filled.Info,
                        iconTint = IconGray,
                        onClick = onAbout,
                    )
                }
            }
        }
        item {
            Column {
                PreferenceGroupCaption("关注作者")
                Spacer(modifier = Modifier.height(6.dp))
                SettingsCard {
                    PreferenceItem(
                        title = "哔哩哔哩",
                        summary = "使用教程与更新动态",
                        icon = Icons.Filled.Person,
                        iconTint = IconPink,
                    ) {
                        if (!openAuthor(context)) scope.launch {
                            snackbarHostState.showSnackbar("无法打开主页，请在哔哩哔哩搜索 UID 498496565")
                        }
                    }
                }
            }
        }
    }

    if (showThemePicker) {
        ThemeModeDialog(
            current = state.appearance.themeMode,
            onSelect = {
                vm.setThemeMode(it)
                showThemePicker = false
            },
            onDismiss = { showThemePicker = false },
        )
    }

    ErrorDialogHost(state.error, vm::dismissError)
}

/** 偏好里存的模式 → 设置项与对话框里显示的名字。 */
private fun ThemeMode.label(): String =
    when (this) {
        ThemeMode.SYSTEM -> "跟随系统"
        ThemeMode.LIGHT -> "浅色"
        ThemeMode.DARK -> "深色"
    }

private fun ThemeMode.summary(): String =
    when (this) {
        ThemeMode.SYSTEM -> "与系统的深色设置保持一致"
        ThemeMode.LIGHT -> "始终使用浅色界面"
        ThemeMode.DARK -> "始终使用深色界面"
    }

/**
 * 深浅色选择。
 *
 * 用对话框而不是把三态控件直接摊在卡片里：设置卡一直是「一行一件事」的密度，
 * 塞进一个分段控件会把这一行撑高，和上下各行不成比例。
 * 选项只有三个，对话框里一眼看全，选中即生效并关闭（不再要一次「确定」）。
 */
@Composable
private fun ThemeModeDialog(
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("深色模式") },
        text = {
            Column {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelect(mode) }
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == current, onClick = { onSelect(mode) })
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(mode.label(), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                mode.summary(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
