package app.luoxianlv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicVideo
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.luoxianlv.data.ConfigStore
import app.luoxianlv.debug.DebugExport
import app.luoxianlv.service.MusicAccessibilityService
import app.luoxianlv.ui.components.IconBlue
import app.luoxianlv.ui.components.IconCyan
import app.luoxianlv.ui.components.IconGreen
import app.luoxianlv.ui.components.IconOrange
import app.luoxianlv.ui.components.IconPink
import app.luoxianlv.ui.components.IconTeal
import app.luoxianlv.ui.components.NavBarClearance
import app.luoxianlv.ui.components.PreferenceGroupCaption
import app.luoxianlv.ui.components.PreferenceItem
import app.luoxianlv.ui.components.SettingsCard
import kotlinx.coroutines.launch

/**
 * 播放诊断页：与设置主页同一套 QQ 分组语言 —— 组标题小灰字贴在卡上方，
 * 每项彩色圆角方块图标 + 标题 + 摘要；只读状态行不带箭头（可点项才有）。
 */
@Composable
fun PlaybackDiagnosticsScreen(
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    var diagnostics by remember { mutableStateOf<MusicAccessibilityService.Diagnostics?>(null) }
    LaunchedEffect(Unit) { diagnostics = MusicAccessibilityService.instance?.diagnostics() }
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    val d = diagnostics
    val layout = remember { ConfigStore.load(context.applicationContext) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 子页面统一标题：返回箭头 + 粗体大标题（与关于/账号页同一套）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp),
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text(
                "播放诊断",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = NavBarClearance),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 组标题 + 白卡是一个视觉单元，放进同一个 item，组间留白交给 spacedBy。
            item {
                Column {
                    PreferenceGroupCaption("当前状态")
                    Spacer(modifier = Modifier.height(6.dp))
                    SettingsCard {
                        PreferenceItem(
                            title = "无障碍服务",
                            summary = if (d?.serviceEnabled == true) "已开启" else "未开启",
                            icon = Icons.Filled.AccessibilityNew,
                            iconTint = IconBlue,
                        )
                        PreferenceItem(
                            title = "播放状态",
                            summary =
                                when {
                                    d == null -> "服务未运行"
                                    d.playing -> "播放中"
                                    d.preparing -> "正在识别琴键"
                                    else -> "已停止"
                                },
                            icon = Icons.Filled.PlayCircle,
                            iconTint = IconPink,
                        )
                        PreferenceItem(
                            title = "当前曲目",
                            summary = d?.songTitle ?: "服务未运行",
                            icon = Icons.Filled.MusicNote,
                            iconTint = IconOrange,
                        )
                    }
                }
            }
            item {
                Column {
                    PreferenceGroupCaption("屏幕与手势")
                    Spacer(modifier = Modifier.height(6.dp))
                    SettingsCard {
                        val display = d?.display?.let { "${it.first} × ${it.second} · 旋转 ${it.third * 90}°" } ?: "暂无"
                        PreferenceItem(
                            title = "当前屏幕",
                            summary = display,
                            icon = Icons.Filled.Screenshot,
                            iconTint = IconTeal,
                        )
                        PreferenceItem(
                            title = "播放时屏幕",
                            summary = d?.playbackDisplay?.let { "${it.first} × ${it.second} · 旋转 ${it.third * 90}°" } ?: "暂无",
                            icon = Icons.Filled.MusicVideo,
                            iconTint = IconCyan,
                        )
                        PreferenceItem(
                            title = "最近点击坐标",
                            summary = d?.lastCoordinates ?: "暂无",
                            icon = Icons.Filled.TouchApp,
                            iconTint = IconGreen,
                        )
                        PreferenceItem(
                            title = "播放错误",
                            summary = d?.error ?: "无",
                            icon = Icons.Filled.Error,
                            iconTint = IconPink,
                        )
                        PreferenceItem(
                            title = "手势错误",
                            summary = d?.gestureFailure ?: "无",
                            icon = Icons.Filled.Gesture,
                            iconTint = IconOrange,
                        )
                    }
                }
            }
            item {
                Column {
                    PreferenceGroupCaption("按键位置（比例 0–1）")
                    Spacer(modifier = Modifier.height(6.dp))
                    SettingsCard {
                        PreferenceItem(
                            title = "琴键横坐标",
                            summary = layout.noteX.joinToString(" ") { "%.3f".format(it) },
                            icon = Icons.Filled.ViewColumn,
                            iconTint = IconBlue,
                        )
                        PreferenceItem(
                            title = "琴键纵坐标",
                            summary = "%.3f".format(layout.noteY),
                            icon = Icons.Filled.ViewAgenda,
                            iconTint = IconCyan,
                        )
                        PreferenceItem(
                            title = "模式键",
                            summary =
                                layout.modes.entries.joinToString(" ") {
                                    "${it.key.name}=%.3f,%.3f".format(it.value[0], it.value[1])
                                },
                            icon = Icons.Filled.GridView,
                            iconTint = IconTeal,
                        )
                    }
                }
            }
            item {
                Column {
                    PreferenceGroupCaption("诊断记录")
                    Spacer(modifier = Modifier.height(6.dp))
                    SettingsCard {
                        PreferenceItem(
                            title = "导出诊断 ZIP",
                            summary = "含游戏截图、日志和设备信息，分享前请检查",
                            icon = Icons.Filled.Share,
                            iconTint = IconBlue,
                        ) {
                            if (!exporting) {
                                scope.launch {
                                    exporting = true
                                    val ok = DebugExport.exportAndShare(context.applicationContext)
                                    exporting = false
                                    snackbarHostState.showSnackbar(if (ok) "已打开分享面板" else "导出失败，请稍后重试")
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "播放前请打开游戏琴键界面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}
