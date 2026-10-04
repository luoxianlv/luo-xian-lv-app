package app.luoxianlv.wallpaper

import android.graphics.drawable.Animatable
import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.luoxianlv.app.BusinessJobs
import app.luoxianlv.business.ui.PageAlertDialog as AlertDialog
import app.luoxianlv.business.ui.PageReplacementGuard
import app.luoxianlv.business.ui.rememberPageLauncher
import app.luoxianlv.shared.ActionPill
import app.luoxianlv.shared.ImportFilePicker
import app.luoxianlv.ui.theme.GradientBackdrop
import app.luoxianlv.ui.theme.OnBackdropContent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
internal fun WallpaperPickerScreen(onBack: () -> Unit, onEnterPractice: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<WallpaperProjectStore.Entry>>(emptyList()) }
    var selected by remember { mutableStateOf(WallpaperProjectStore.selectedId(context)) }
    var soundEnabled by remember { mutableStateOf(WallpaperProjectStore.soundEnabled(context)) }
    var busy by remember { mutableStateOf(false) }
    PageReplacementGuard { !busy }
    var error by remember { mutableStateOf<String?>(null) }
    var minute by remember { mutableStateOf(WallpaperProjectStore.minute(context)) }
    suspend fun refresh() {
        entries = BusinessJobs.io { WallpaperProjectStore.entries(context) }
    }
    val requestWallpaper = rememberWallpaperRequest { scope.launch { refresh() } }
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
                BusinessJobs.io {
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
    fun select(entry: WallpaperProjectStore.Entry) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                BusinessJobs.io { WallpaperProjectStore.select(context, entry) }
                selected = entry.id
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: "选择壁纸失败"
            } finally {
                busy = false
            }
        }
    }
    val zip = rememberPageLauncher("wallpaper.import", ImportFilePicker("选择壁纸 ZIP")) { import(it) }
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
            if (!app.luoxianlv.wallpaper.DefaultWallpaper.installed(context)) {
                TextButton(onClick = { if (!busy) requestWallpaper(true) {} }) { Text("下载默认动态壁纸") }
            }
            app.luoxianlv.shared.SettingsCard {
                app.luoxianlv.shared.PreferenceSwitchItem(
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
                    WallpaperCard(entry, selected == entry.id) { select(entry) }
                }
            }
            ActionPill(
                "进入演练场",
                onClick = {
                    if (!busy) {
                        requestWallpaper(false, onEnterPractice)
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

@Composable
internal fun WallpaperCard(
    entry: WallpaperProjectStore.Entry,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = if (selected) BorderStroke(1.dp, OnBackdropContent.copy(alpha = .35f)) else null,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            var preview by
                remember(entry.id) { mutableStateOf<android.graphics.drawable.Drawable?>(null) }
            LaunchedEffect(entry.id) {
                preview = BusinessJobs.io {
                    WallpaperPreview.load(
                        context,
                        entry.root,
                        (112 * context.resources.displayMetrics.density).toInt(),
                    )
                }
            }
            Box(
                Modifier.size(112.dp, 70.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clipToBounds()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                AndroidView(
                    factory = {
                        ImageView(it).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            contentDescription = "${entry.title}预览"
                            cropToPadding = true
                        }
                    },
                    modifier = Modifier.matchParentSize().clipToBounds(),
                    update = { view ->
                        if (view.drawable !== preview) {
                            (view.drawable as? Animatable)?.stop()
                            view.setImageDrawable(preview)
                            (preview as? Animatable)?.start()
                        }
                    },
                    onRelease = {
                        (it.drawable as? Animatable)?.stop()
                        it.setImageDrawable(null)
                    },
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (selected) "使用中" else if (entry.id == null) "默认壁纸" else "已导入",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected)
                Icon(
                    Icons.Default.CheckCircle,
                    "已选择",
                    modifier = Modifier.size(20.dp),
                    tint = OnBackdropContent,
                )
            else Spacer(Modifier.size(20.dp))
        }
    }
}
