package app.luoxianlv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.luoxianlv.BuildConfig
import app.luoxianlv.core.Analytics
import app.luoxianlv.update.AppUpdateState

@Composable
fun AppUpdateDialog(state: AppUpdateState, onDismiss: () -> Unit, onSource: (String) -> Unit, onDownload: () -> Unit, onInstall: () -> Unit) {
    val release = state.release ?: return
    val context = LocalContext.current
    // 埋点：更新弹窗展示。LaunchedEffect 以 versionCode 为 key，同一弹窗实例
    // 反复重组只进入一次，不会重复上报；换新版本重新弹出会再计一次。
    LaunchedEffect(release.versionCode) { Analytics.logEvent(context, "update_prompt_show") }
    val colors = MaterialTheme.colorScheme
    val dismissible = !release.mandatory && !state.downloading
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible)) {
        Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(colors.primary.copy(alpha = .14f), colors.secondary.copy(alpha = .06f)))).padding(24.dp)) {
                    Icon(Icons.Default.SystemUpdate, null, tint = colors.primary, modifier = Modifier.size(36.dp))
                    Text("让旋律更动听", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                    Text("落弦律 ${release.versionName} 已就绪", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                    Text("当前 ${BuildConfig.VERSION_NAME}" + if (release.size > 0) "  ·  %.1f MB".format(release.size / 1048576.0) else "", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("本次更新", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    val noteText = release.notes.joinToString("\n") { section ->
                        "${section.title}\n${section.items.joinToString("\n") { "• $it" }}"
                    }.ifBlank { "更新体验与稳定性优化" }
                    Text(noteText, style = MaterialTheme.typography.bodyMedium)
                    if (release.mandatory) Text("此版本需要更新后继续使用", color = colors.primary, style = MaterialTheme.typography.bodySmall)
                    if (release.sources.size > 1 && !state.ready) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            release.sources.forEach { source ->
                                FilterChip(selected = state.selectedSource == source.id, onClick = { onSource(source.id) }, label = { Text(source.label) }, enabled = !state.downloading)
                            }
                        }
                    }
                    if (state.downloading) {
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        Text("${state.source} · 已下载 ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.needsPermission) Text("请允许安装未知应用，返回后继续安装。", style = MaterialTheme.typography.bodySmall)
                    state.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = {
                        // 埋点：更新弹窗点「立即更新 / 重新下载 / 安装更新」
                        Analytics.logEvent(context, "update_accept")
                        if (state.ready) onInstall() else onDownload()
                    }, enabled = !state.downloading, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(24.dp)) {
                        Text(when { state.downloading -> "正在下载…"; state.ready -> "安装更新"; state.error != null -> "重新下载"; else -> "立即更新" })
                    }
                    if (dismissible) TextButton(onClick = {
                        onDismiss()
                    }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("稍后再说") }
                }
            }
        }
    }
}
