package app.luoxianlv.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.luoxianlv.data.AccountSession
import app.luoxianlv.service.KeepAlive
import app.luoxianlv.ui.components.ErrorDialogHost
import app.luoxianlv.ui.components.NavBarClearance
import app.luoxianlv.ui.components.PageTitle
import app.luoxianlv.ui.components.PreferenceDivider
import app.luoxianlv.ui.components.PreferenceGroupCaption
import app.luoxianlv.ui.components.PreferenceItem
import app.luoxianlv.ui.components.PreferenceSwitchItem
import app.luoxianlv.ui.components.SettingsCard
import app.luoxianlv.ui.components.SnackbarNotice

/**
 * 各组图标的颜色。QQ 设置的标志之一就是一组高饱和但不刺眼的彩色图标，
 * 这里给每一项固定一个色相，用户扫颜色就能定位功能。
 */
private val IconBlue = Color(0xFF3E7BE0)
private val IconCyan = Color(0xFF3FA8C9)
private val IconOrange = Color(0xFFE8862E)
private val IconGreen = Color(0xFF4CAF50)
private val IconPink = Color(0xFFD8659E)
private val IconTeal = Color(0xFF2FA3A0)
private val IconGray = Color(0xFF7A8AA0)
private val IconUpdate = Color(0xFF5B8DEF)

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
                    PreferenceDivider()
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
                    PreferenceDivider()
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
                    PreferenceDivider()
                    PreferenceItem(
                        title = "自启动与后台权限",
                        summary = "部分手机需在系统设置中允许后台运行",
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
                    PreferenceSwitchItem(
                        title = "自动检查更新",
                        checked = state.autoUpdate,
                        onCheckedChange = vm::setAutoUpdate,
                        summary = "启动和回到前台时自动检查新版本",
                        icon = Icons.Filled.SystemUpdate,
                        iconTint = IconUpdate,
                    )
                    PreferenceDivider()
                    PreferenceItem(
                        title = "关于落弦律",
                        icon = Icons.Filled.Info,
                        iconTint = IconGray,
                        onClick = onAbout,
                    )
                }
            }
        }
    }

    ErrorDialogHost(state.error, vm::dismissError)
}
