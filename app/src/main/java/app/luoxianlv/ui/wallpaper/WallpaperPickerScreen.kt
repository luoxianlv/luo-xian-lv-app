package app.luoxianlv.ui.wallpaper

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.luoxianlv.ui.components.ActionPill
import app.luoxianlv.ui.components.ImportFilePicker
import app.luoxianlv.ui.theme.GradientBackdrop
import app.luoxianlv.ui.theme.OnBackdropContent
import app.luoxianlv.wallpaper.data.WallpaperProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WallpaperPickerScreen(onBack: () -> Unit, onEnterPractice: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<WallpaperProjectStore.Entry>>(emptyList()) }
    var selected by remember { mutableStateOf(WallpaperProjectStore.selectedId(context)) }
    var soundEnabled by remember { mutableStateOf(WallpaperProjectStore.soundEnabled(context)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var minute by remember { mutableStateOf(WallpaperProjectStore.minute(context)) }
    suspend fun refresh() {
        entries = withContext(Dispatchers.IO) { WallpaperProjectStore.entries(context) }
    }
    LaunchedEffect(Unit) {
        busy = true
        try {
            refresh()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: "壁纸准备失败"
        } finally {
            busy = false
        }
    }
    fun import(uri: Uri?) {
        if (uri == null || busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()
                    WallpaperProjectStore.import(context, uri, false) { job.ensureActive() }
                }
                selected = WallpaperProjectStore.selectedId(context)
                refresh()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: "导入失败"
            } finally {
                busy = false
            }
        }
    }
    val zip = rememberLauncherForActivityResult(ImportFilePicker("选择壁纸 ZIP")) { import(it) }
    Box(Modifier.fillMaxSize()) {
        GradientBackdrop()
        Column(
            Modifier.fillMaxSize()
                .safeDrawingPadding()
                .widthIn(max = 560.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 20.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onBack() }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        "返回",
                        tint = OnBackdropContent,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "演练场设置",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = OnBackdropContent,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionPill(
                    "导入 ZIP",
                    onClick = {
                        if (!busy)
                            zip.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/x-zip",
                                    "application/x-compressed",
                                    "application/octet-stream",
                                )
                            )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Default.Add,
                    compact = true,
                )
            }
            Spacer(Modifier.height(12.dp))
            app.luoxianlv.ui.components.SettingsCard {
                app.luoxianlv.ui.components.PreferenceSwitchItem(
                    title = "壁纸声音",
                    summary = "不影响口琴声音",
                    checked = soundEnabled,
                    onCheckedChange = {
                        soundEnabled = it
                        WallpaperProjectStore.setSoundEnabled(context, it)
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "壁纸时间",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            minute = null
                            WallpaperProjectStore.setMinute(context, null)
                        }
                    ) {
                        Text(
                            if (minute == null) "跟随系统 ✓" else "跟随系统",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(
                        onClick = {
                            val now = java.util.Calendar.getInstance()
                            android.app
                                .TimePickerDialog(
                                    context,
                                    { _, h, m ->
                                        minute = h * 60 + m
                                        WallpaperProjectStore.setMinute(context, minute)
                                    },
                                    minute?.div(60) ?: now.get(java.util.Calendar.HOUR_OF_DAY),
                                    minute?.rem(60) ?: now.get(java.util.Calendar.MINUTE),
                                    true,
                                )
                                .show()
                        }
                    ) {
                        Text(
                            minute?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "指定",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = { it.id ?: "default" }) { entry ->
                    WallpaperCard(entry, selected == entry.id) {
                        if (!busy) {
                            WallpaperProjectStore.select(context, entry)
                            selected = entry.id
                        }
                    }
                }
            }
            ActionPill(
                "进入演练场",
                onClick = {
                    if (!busy) {
                        onEnterPractice()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Default.PlayArrow,
                compact = true,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
    error?.let {
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("未能导入") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } },
        )
    }
}
