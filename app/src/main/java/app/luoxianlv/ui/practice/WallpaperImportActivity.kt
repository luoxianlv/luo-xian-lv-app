package app.luoxianlv.ui.practice

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.luoxianlv.ui.theme.LuoXianLvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** System file associations hand over granted content URIs; no storage permission is requested. */
class WallpaperImportActivity : AppCompatActivity() {
    private val model: WallpaperImportModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val uri = runCatching { when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
            else -> null
        } }.getOrNull()
        model.start(uri)
        setContent {
            LuoXianLvTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(28.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("导入落弦律壁纸", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(20.dp))
                        if (model.busy) CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(model.message)
                        Spacer(Modifier.height(24.dp))
                        if (model.success) Button(onClick = {
                            startActivity(Intent(this@WallpaperImportActivity,PracticeActivity::class.java)); finish()
                        }) { Text("进入演练场") }
                        TextButton(onClick = { finish() }) { Text(if (model.busy) "取消" else "关闭") }
                    }
                }
            }
        }
    }
}

class WallpaperImportModel(application: Application) : AndroidViewModel(application) {
    var busy by mutableStateOf(false); private set
    var success by mutableStateOf(false); private set
    var message by mutableStateOf("正在导入…"); private set
    private var started = false
    fun start(uri: Uri?) {
        if (started) return
        started = true
        if (uri == null || uri.scheme !in setOf("content", "file")) {
            message = "未收到可读取的 ZIP 文件，请先下载文件，再选择用其他应用打开。"; return
        }
        busy = true
        viewModelScope.launch {
            try {
                val title = withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()
                    WallpaperProjectStore.import(getApplication(), uri, false) { job.ensureActive() }
                }
                success = true; message = "已导入：$title"
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                message = when (error) {
                    is SecurityException, is java.io.FileNotFoundException -> "无法读取文件，请先下载完整，再选择用其他应用打开。"
                    is java.util.zip.ZipException -> "无法读取 ZIP，请检查文件是否完整、未加密。"
                    else -> error.message ?: "导入失败，当前壁纸未改变。"
                }
            } finally { busy = false }
        }
    }
}
