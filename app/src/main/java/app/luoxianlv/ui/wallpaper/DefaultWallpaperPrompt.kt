package app.luoxianlv.ui.wallpaper

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.luoxianlv.business.ui.PageAlertDialog as AlertDialog
import app.luoxianlv.business.ui.PageReplacementGuard
import app.luoxianlv.wallpaper.data.DefaultWallpaper
import app.luoxianlv.wallpaper.render.PreparedWallpaper
import app.luoxianlv.wallpaper.render.WallpaperPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 首页与壁纸设置共用下载确认；用户明确选择前不发起网络请求。 */
@Composable
fun rememberWallpaperRequest(onDownloaded: () -> Unit = {}): (Boolean, () -> Unit) -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val downloaded by rememberUpdatedState(onDownloaded)
    var continuation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    PageReplacementGuard { !busy && continuation == null }

    fun proceed() {
        val next = continuation
        continuation = null
        next?.invoke()
    }

    fun download() {
        if (busy) return
        busy = true
        error = null
        progress = 0f
        job = scope.launch {
            try {
                DefaultWallpaper.download(context) { received, total ->
                    progress = received.toFloat() / total
                }
                WallpaperPreview.clearCache()
                PreparedWallpaper.clear()
                downloaded()
                proceed()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // 网络异常可能含临时签名 URL，不向界面或日志输出原始异常内容。
                app.luoxianlv.debug.AppLog.w("壁纸", "默认壁纸下载失败：${failure.javaClass.simpleName}")
                error = "下载未完成，请检查网络和存储空间后重试，也可以先进入演练场。"
            } finally {
                busy = false
                job = null
            }
        }
    }

    if (continuation != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) continuation = null },
            title = { Text(if (busy) "正在下载壁纸" else if (error != null) "下载未完成" else "下载演练场壁纸？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(error ?: if (busy) "下载完成后即可离线使用。" else "动态壁纸需要单独下载。也可以先用默认背景，不影响练习。")
                    if (busy) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(if (progress >= 1f) "正在校验并安装…" else "已下载 ${(progress * 100).toInt()}%")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (busy) {
                            job?.cancel()
                            continuation = null
                        } else download()
                    }
                ) {
                    Text(if (busy) "取消下载" else if (error != null) "重试" else "下载")
                }
            },
            dismissButton = {
                if (!busy) {
                    Row {
                        TextButton(
                            onClick = {
                                DefaultWallpaper.stopOffering(context)
                                proceed()
                            }
                        ) {
                            Text("不再提示")
                        }
                        TextButton(onClick = { proceed() }) { Text("稍后再问") }
                    }
                }
            },
        )
    }
    return { force, next ->
        if (!busy && continuation == null) {
            if (
                !DefaultWallpaper.installed(context) &&
                    (force || DefaultWallpaper.shouldOffer(context))
            ) {
                error = null
                continuation = next
            } else next()
        }
    }
}
