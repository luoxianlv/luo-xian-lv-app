package app.luoxianlv.ui.practice

import android.content.Intent
import android.graphics.drawable.Animatable
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.luoxianlv.data.AppearanceStore
import app.luoxianlv.ui.components.ActionPill
import app.luoxianlv.ui.theme.GradientBackdrop
import app.luoxianlv.ui.theme.OnBackdropContent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.luoxianlv.ui.theme.LuoXianLvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Portrait library of original project previews; selection applies before the next stage starts. */
class WallpaperPickerActivity : AppCompatActivity() {
    private fun returnFromPicker() {
        if (intent.getBooleanExtra("returnToPractice",false)) startActivity(Intent(this,PracticeActivity::class.java))
        finish()
    }
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this,object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { returnFromPicker() }
        })
        setContent {
            val appearance by AppearanceStore.settings.collectAsState()
            LuoXianLvTheme(darkTheme = appearance.themeMode.isDark(isSystemInDarkTheme())) {
            val scope = rememberCoroutineScope()
            var entries by remember { mutableStateOf<List<WallpaperProjectStore.Entry>>(emptyList()) }
            var selected by remember { mutableStateOf(WallpaperProjectStore.current(this)?.name) }
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var minute by remember { mutableStateOf(WallpaperProjectStore.minute(this)) }
            suspend fun refresh() { entries = withContext(Dispatchers.IO) { WallpaperProjectStore.entries(this@WallpaperPickerActivity) } }
            LaunchedEffect(Unit) { refresh() }
            fun import(uri: Uri?, tree: Boolean) {
                if (uri == null || busy) return
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val job = currentCoroutineContext()
                            WallpaperProjectStore.import(this@WallpaperPickerActivity,uri,tree) { job.ensureActive() }
                        }
                        selected = WallpaperProjectStore.current(this@WallpaperPickerActivity)?.name
                        refresh()
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        error = e.message ?: "导入失败"
                    } finally { busy = false }
                }
            }
            val zip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { import(it,false) }
            val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { import(it,true) }
            Box(Modifier.fillMaxSize()) {
                GradientBackdrop()
                Column(Modifier.fillMaxSize().safeDrawingPadding().widthIn(max = 560.dp).align(Alignment.TopCenter).padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth().height(56.dp),verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { returnFromPicker() }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回",tint = OnBackdropContent,modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("演练场壁纸",fontSize = 20.sp,fontWeight = FontWeight.Bold,color = OnBackdropContent)
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionPill("导入 ZIP",onClick = { if (!busy) zip.launch(arrayOf("application/zip","application/x-zip-compressed","application/x-zip","application/x-compressed","application/octet-stream")) },modifier = Modifier.weight(1f),icon = Icons.Default.Add,compact = true)
                        ActionPill("项目文件夹",onClick = { if (!busy) folder.launch(null) },modifier = Modifier.weight(1f),icon = Icons.Default.FolderOpen,compact = true)
                    }
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = RoundedCornerShape(18.dp),color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp),verticalAlignment = Alignment.CenterVertically) {
                            Text("壁纸时间",style = MaterialTheme.typography.bodyMedium,modifier = Modifier.weight(1f))
                            TextButton(onClick = { minute = null; WallpaperProjectStore.setMinute(this@WallpaperPickerActivity,null) }) {
                                Text(if (minute == null) "跟随系统 ✓" else "跟随系统",style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(onClick = {
                                val now = java.util.Calendar.getInstance()
                                android.app.TimePickerDialog(this@WallpaperPickerActivity,{ _, h, m -> minute = h*60+m; WallpaperProjectStore.setMinute(this@WallpaperPickerActivity,minute) },
                                    minute?.div(60) ?: now.get(java.util.Calendar.HOUR_OF_DAY),minute?.rem(60) ?: now.get(java.util.Calendar.MINUTE),true).show()
                            }) { Text(minute?.let { "%02d:%02d".format(it/60,it%60) } ?: "指定",style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding = PaddingValues(vertical = 14.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(entries,key = { it.id ?: "default" }) { entry ->
                            Surface(onClick = {
                                if (!busy) { WallpaperProjectStore.select(this@WallpaperPickerActivity,entry); selected = entry.id }
                            },shape = RoundedCornerShape(18.dp),color = MaterialTheme.colorScheme.surface,
                                border = if (selected == entry.id) BorderStroke(1.dp,OnBackdropContent.copy(alpha = .35f)) else null) {
                                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    var preview by remember(entry.id) { mutableStateOf<android.graphics.drawable.Drawable?>(null) }
                                    LaunchedEffect(entry.id) { preview = withContext(Dispatchers.IO) { WallpaperPreview.load(this@WallpaperPickerActivity,entry.root) } }
                                    Box(Modifier.size(112.dp,70.dp).clip(RoundedCornerShape(10.dp)).clipToBounds().background(MaterialTheme.colorScheme.surface)) {
                                        AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = "${entry.title}预览"; cropToPadding = true } },
                                            modifier = Modifier.matchParentSize().clipToBounds(),update = { view ->
                                                if (view.drawable !== preview) { (view.drawable as? Animatable)?.stop(); view.setImageDrawable(preview); (preview as? Animatable)?.start() }
                                            },onRelease = { (it.drawable as? Animatable)?.stop(); it.setImageDrawable(null) })
                                    }
                                    Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(entry.title,style = MaterialTheme.typography.bodyMedium,fontWeight = FontWeight.Medium,maxLines = 2,overflow = TextOverflow.Ellipsis)
                                        Text(if (selected == entry.id) "使用中" else if (entry.id == null) "默认壁纸" else "已导入",style = MaterialTheme.typography.labelSmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (selected == entry.id) Icon(Icons.Default.CheckCircle,"已选择",modifier = Modifier.size(20.dp),tint = OnBackdropContent)
                                    else Spacer(Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                    ActionPill("进入演练场",onClick = {
                        if (!busy) { startActivity(Intent(this@WallpaperPickerActivity,PracticeActivity::class.java)); finish() }
                    },modifier = Modifier.fillMaxWidth(),icon = Icons.Default.PlayArrow,compact = true)
                    Spacer(Modifier.height(12.dp))
                }
            }
            error?.let { AlertDialog(onDismissRequest = { error = null },title = { Text("未能导入") },text = { Text(it) },confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } }) }
        } }
    }
}
