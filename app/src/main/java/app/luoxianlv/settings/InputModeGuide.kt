package app.luoxianlv.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.luoxianlv.business.ui.PageAlertDialog as AlertDialog
import app.luoxianlv.shared.SettingsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 操作指南留在原页面中，返回及系统设置结果不丢失连接状态。 */
@Composable
internal fun InputModeGuide(
    mode: String,
    state: InputModeState,
    permissionHint: String,
    onBack: () -> Unit,
    onStartPairing: () -> Unit,
    onManualPairing: () -> Unit,
    onNotificationSettings: () -> Unit,
    onNetworkSettings: () -> Unit,
    onShizukuSettings: () -> Unit,
    onOpenDocumentation: (String) -> Unit,
) {
    val wireless = mode == "wireless"
    var showLicenses by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "title") {
            InputModeTitle(if (wireless) "无线调试使用帮助" else "Shizuku 使用帮助", onBack)
        }
        item(key = "steps") {
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (wireless) {
                        GuideStep("1", "准备 Wi-Fi 和通知", "连接 Wi-Fi，并允许落弦律显示通知。")
                        GuideStep("2", "开启无线调试", "点下方按钮，在系统设置中开启“无线调试”。")
                        GuideStep("3", "显示配对码", "选择“使用配对码配对设备”，保持这个窗口打开。")
                        GuideStep("4", "在通知里填写", "下拉通知栏，在落弦律的配对通知中填入 6 位配对码，连接后返回 APP。")
                    } else {
                        GuideStep("1", "安装 Shizuku", "点“安装 Shizuku”，从官方页面下载并安装。")
                        GuideStep("2", "启动 Shizuku", "连接 Wi-Fi，按 Shizuku 中“通过无线调试启动”的指引完成配对和启动。")
                        GuideStep("3", "允许落弦律使用", "返回这里，点“授权并连接”，在弹出的授权窗口中选择允许。")
                    }
                }
            }
        }
        item(key = "action") {
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(state.statusTitle, style = MaterialTheme.typography.titleMedium)
                    if (permissionHint.isNotBlank()) {
                        Text(
                            permissionHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (state.statusSummary.isNotBlank()) {
                        Text(
                            state.statusSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when {
                        state.usable ->
                            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                                Text("返回输入模式")
                            }
                        wireless -> {
                            Button(
                                onClick = onStartPairing,
                                enabled = state.wirelessSupported && !state.busy && !state.pairing,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    when {
                                        state.pairing -> "正在配对…"
                                        state.busy -> "正在连接…"
                                        state.paired -> "重新配对，打开设置"
                                        else -> "开始配对，打开设置"
                                    }
                                )
                            }
                            if (!state.notificationGranted) {
                                TextButton(onClick = onNotificationSettings) { Text("开启配对通知") }
                            }
                            if (Build.VERSION.SDK_INT >= 37 && !state.localNetworkGranted) {
                                TextButton(onClick = onNetworkSettings) { Text("允许本地网络权限") }
                            }
                        }
                        else ->
                            state.primaryAction?.let { action ->
                                Button(
                                    onClick = { inputCommand(action.command) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(action.label)
                                }
                            }
                    }
                    if (state.busy || state.pairing) {
                        TextButton(onClick = { inputCommand("disconnect") }) { Text("取消连接") }
                    }
                }
            }
        }
        item(key = "tips") {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 6.dp),
            ) {
                Text(
                    if (wireless) "配对通常只需一次。手机重启或连接中断后，回来点“连接”即可。"
                    else "手机重启后，请先在 Shizuku 中重新启动服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "找不到开发者选项时，可在“关于手机”里连续点按版本号，按系统提示开启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InputSecurityHint()
                if (!wireless) {
                    Text(
                        "后台连接仍失败时，请在系统应用管理或电池设置中允许 Shizuku 后台运行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onShizukuSettings) { Text("打开 Shizuku 应用设置") }
                }
                TextButton(onClick = { inputCommand("openSettings") }) { Text("打开开发者选项") }
                if (wireless && !state.usable) {
                    TextButton(
                        onClick = onManualPairing,
                        enabled = state.wirelessSupported && !state.pairing,
                    ) {
                        Text("无法从通知填写？手动填写配对码")
                    }
                }
            }
        }
        item(key = "sources") {
            Column(Modifier.padding(horizontal = 6.dp)) {
                Text(
                    "无线调试的配对通知与服务发现移植自 Shizuku v13.6.0（Apache-2.0）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { onOpenDocumentation(SHIZUKU_GUIDE) }) { Text("官方使用说明") }
                    TextButton(onClick = { showLicenses = true }) { Text("开源许可") }
                }
            }
        }
    }
    if (showLicenses) {
        InputLicenseDialog(
            onDismiss = { showLicenses = false },
            onOpenDocumentation = onOpenDocumentation,
        )
    }
}

@Composable
private fun GuideStep(number: String, title: String, summary: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "$number. $title",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InputLicenseDialog(onDismiss: () -> Unit, onOpenDocumentation: (String) -> Unit) {
    val context = LocalContext.current
    val notice by
        produceState("正在读取许可说明…", context) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.assets
                            .open("licenses/input/NOTICE.md")
                            .bufferedReader(Charsets.UTF_8)
                            .use { it.readText() }
                    }
                        .getOrElse { "许可说明暂不可用，可查看下方官方许可原文。" }
                }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开源许可") },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(notice, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onOpenDocumentation(SHIZUKU_LICENSE) }) {
                    Text("Shizuku 许可原文")
                }
                TextButton(onClick = { onOpenDocumentation(SHIZUKU_API_LICENSE) }) {
                    Text("Shizuku API 许可原文")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private const val SHIZUKU_GUIDE = "https://shizuku.rikka.app/guide/setup/"

@Composable
internal fun InputSecurityHint(modifier: Modifier = Modifier) {
    Text(
        "部分手机还需要开启「USB 调试（安全设置）」。它与普通「USB 调试」是两个开关；已连接但游戏没有反应时，请检查此项。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

private const val SHIZUKU_LICENSE = "https://github.com/RikkaApps/Shizuku/blob/v13.6.0/LICENSE"
private const val SHIZUKU_API_LICENSE =
    "https://github.com/RikkaApps/Shizuku-API/blob/510fc988c02c3475d8c25db170f96792f105bdf8/LICENSE"
