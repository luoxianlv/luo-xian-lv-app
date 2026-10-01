package app.luoxianlv.ui.wallpaper

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.luoxianlv.business.BusinessJobs
import app.luoxianlv.wallpaper.data.WallpaperProjectStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

class WallpaperImportModel(application: Application) : AndroidViewModel(application) {
    var busy by mutableStateOf(false)
        private set

    var success by mutableStateOf(false)
        private set

    var message by mutableStateOf("正在导入…")
        private set

    private var started = false

    fun saveCompleted() =
        Bundle().apply {
            if (started && !busy) {
                putBoolean("completed", true)
                putBoolean("success", success)
                putString("message", message)
            }
        }

    fun restoreCompleted(state: Bundle?) {
        if (started || state?.getBoolean("completed") != true) return
        started = true
        success = state.getBoolean("success")
        message = state.getString("message") ?: "导入已结束。"
    }

    fun start(uri: Uri?) {
        if (started) return
        started = true
        if (uri == null || uri.scheme !in setOf("content", "file")) {
            message = "未收到可读取的 ZIP 文件，请先下载文件，再选择用其他应用打开。"
            return
        }
        busy = true
        viewModelScope.launch {
            try {
                val title = BusinessJobs.io {
                    val job = currentCoroutineContext()
                    WallpaperProjectStore.import(getApplication(), uri, false) {
                        job.ensureActive()
                    }
                }
                success = true
                message = "已导入：$title"
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                message =
                    when (error) {
                        is SecurityException,
                        is java.io.FileNotFoundException -> "无法读取文件，请先下载完整，再选择用其他应用打开。"
                        is java.util.zip.ZipException -> "无法读取 ZIP，请检查文件是否完整、未加密。"
                        else -> error.message ?: "导入失败，当前壁纸未改变。"
                    }
            } finally {
                busy = false
            }
        }
    }
}
