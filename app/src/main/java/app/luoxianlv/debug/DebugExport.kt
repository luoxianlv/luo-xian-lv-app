package app.luoxianlv.debug

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.core.content.FileProvider
import app.luoxianlv.BuildConfig
import app.luoxianlv.data.ConfigStore
import app.luoxianlv.data.Kv
import app.luoxianlv.service.MusicAccessibilityService
import app.luoxianlv.storage.AppStorage
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** 打包调试信息（日志/截图/布局/设备信息）成 ZIP 并通过 FileProvider 分享。 */
object DebugExport {
    /** 只生成诊断包，不启动分享界面；导出前等待后台日志写入。 */
    suspend fun create(context: Context): File =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            AppLog.init(context)
            AppLog.flush()
            AppLog.withSnapshot { export(context) }
        }

    suspend fun exportAndShare(context: Context): Boolean {
        val file =
            runCatching { create(context) }.onFailure { AppLog.e("诊断", "生成诊断包失败", it) }.getOrNull()
                ?: return false
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            runCatching {
                val uri =
                    FileProvider.getUriForFile(context, context.packageName + ".updates", file)
                val send =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                context.startActivity(
                    Intent.createChooser(send, "分享调试 ZIP").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
                .isSuccess
        }
    }

    private fun export(context: Context): File {
        val dir = AppLog.directory()
        val zipFile =
            File(
                AppStorage.diagnostics(context),
                "luoxianlv-debug-" +
                    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
                    ".zip",
            )
        zipFile.parentFile?.mkdirs()
        zipFile.parentFile
            ?.listFiles { f -> f.name.startsWith("luoxianlv-debug-") && f.extension == "zip" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(2)
            ?.forEach { it.delete() }
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry("说明.txt"))
            zip.write(
                ("落弦律诊断包（UTF-8）\n" +
                        "日志：logs；截图：shots；设备原始字段：device.json；播放与识别状态：diagnostics.json。\n" +
                        "文件目录：${AppStorage.root(context).absolutePath}\n" +
                        "技术字段及异常原文保持原样，便于定位问题。\n")
                    .toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("device.json"))
            zip.write(deviceInfo(context).toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("diagnostics.json"))
            zip.write(diagnostics(context).toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            dir?.listFiles { f -> f.isFile && f.name.startsWith("play-debug") }
                ?.forEach { log ->
                    zip.putNextEntry(ZipEntry("logs/" + log.name))
                    log.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            File(dir, "shots").listFiles()?.forEach { shot ->
                zip.putNextEntry(ZipEntry("shots/" + shot.name))
                shot.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        AppLog.trim()
        check(zipFile.isFile) { "诊断文件超过留存上限" }
        return zipFile
    }

    private fun deviceInfo(context: Context): JSONObject {
        val display =
            context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        @Suppress("DEPRECATION") display.getRealSize(size)
        val metrics = context.resources.displayMetrics
        return JSONObject()
            .put("packageName", context.packageName)
            .put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("manufacturer", Build.MANUFACTURER)
            .put("brand", Build.BRAND)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)
            .put("securityPatch", Build.VERSION.SECURITY_PATCH)
            .put("realSize", "${size.x}x${size.y}")
            .put("rotation", display.rotation)
            .put("density", metrics.density)
            .put("densityDpi", metrics.densityDpi)
            .put("appMetrics", "${metrics.widthPixels}x${metrics.heightPixels}")
    }

    private fun diagnostics(context: Context): JSONObject {
        val d = MusicAccessibilityService.instance?.diagnostics()
        val layout = ConfigStore.load(context)
        val prefs = Kv.of(context, "ratio_config_v3").all
        val modes = JSONObject()
        layout.modes.forEach { (mode, point) ->
            modes.put(mode.name, "%.4f,%.4f".format(point[0], point[1]))
        }
        return JSONObject()
            .put("serviceRunning", d != null)
            .put("serviceEnabled", d?.serviceEnabled)
            .put("playing", d?.playing)
            .put("preparing", d?.preparing)
            .put("songTitle", d?.songTitle)
            .put("display", d?.display?.let { "${it.width}x${it.height} rot=${it.rotation}" })
            .put("playbackDisplay", d?.playbackDisplay?.toString())
            .put("lastCoordinates", d?.lastCoordinates)
            .put("error", d?.error)
            .put("gestureFailure", d?.gestureFailure)
            .put("noteX", layout.noteX.joinToString(",") { "%.4f".format(it) })
            .put("noteY", "%.4f".format(layout.noteY))
            .put("modes", modes)
            .put("savedPrefs", JSONObject(prefs.mapValues { it.value.toString() }))
    }
}
