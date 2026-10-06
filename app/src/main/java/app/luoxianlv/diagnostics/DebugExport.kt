package app.luoxianlv.diagnostics

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import app.luoxianlv.BuildConfig
import app.luoxianlv.app.BusinessJobs
import app.luoxianlv.hot.contract.SharedFiles
import app.luoxianlv.hot.contract.SharedInput
import app.luoxianlv.playback.PlaybackConnection
import app.luoxianlv.recognition.ConfigStore
import app.luoxianlv.shared.AppStorage
import app.luoxianlv.shared.Kv
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
    suspend fun create(context: Context): File = BusinessJobs.io {
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
                val uri = SharedFiles.getUriForFile(context, context.packageName + ".updates", file)
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
                        "日志：logs；截图：shots；设备原始字段：device.json；播放与识别状态：diagnostics.json；输入连接与失败阶段：input.json。\n" +
                        "闪退堆栈：logs/play-debug-crash.log（最近两次）；系统退出原因：process-exits.json（Android 11 起）。\n" +
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
            zip.putNextEntry(ZipEntry("process-exits.json"))
            zip.write(processExits(context).toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("input.json"))
            zip.write(inputState().toString(2).toByteArray(Charsets.UTF_8))
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

    /** 仅导出定位连接和触控故障所需的状态；不包含配对身份、候选地址或令牌。 */
    private fun inputState(): JSONObject = runCatching {
        val state = SharedInput.current()?.state()
        JSONObject().apply {
            put("bridgeAvailable", state != null)
            listOf(
                    "supported",
                    "mode",
                    "installed",
                    "binderAlive",
                    "binderReady",
                    "permissionGranted",
                    "overlayGranted",
                    "accessibilityEnabled",
                    "permissionState",
                    "permissionCheckError",
                    "shizukuUid",
                    "connected",
                    "touchReady",
                    "active",
                    "waitingForFingers",
                    "activationWaitMs",
                    "trackedSlots",
                    "touchSupported",
                    "touchPressed",
                    "touchState",
                    "contactReadErrno",
                    "staleContactIgnored",
                    "contactDecision",
                    "busy",
                    "uid",
                    "wirelessSupported",
                    "wifiConnected",
                    "wirelessEnabled",
                    "notificationGranted",
                    "localNetworkGranted",
                    "paired",
                    "message",
                    "wirelessMessage",
                    "errorStage",
                    "errorType",
                    "failureStage",
                    "errorClass",
                    "diagnosticStage",
                    "diagnosticType",
                    "diagnosticMessage",
                    "deviceId",
                    "vendorId",
                    "productId",
                    "physicalSlots",
                    "deviceMatchMethod",
                    "touchProtocol",
                    "hardwareTrackingIds",
                    "hostUid",
                    "ownerUid",
                    "helperUid",
                    "callerUid",
                    "helperArchitecture",
                    "serviceRevision",
                    "installedVersionCode",
                    "installedUpdateTime",
                    "details",
                    "nativeStatus",
                    "physicalSlots",
                    "maxPointers",
                    "width",
                    "height",
                    "rotation",
                )
                .forEach { key -> if (state?.containsKey(key) == true) put(key, state.get(key)) }
        }
    }
        .getOrElse { JSONObject().put("errorType", it.javaClass.simpleName) }

    /** Android 11 起可区分 Java 崩溃、原生崩溃、ANR、低内存回收与主动退出。 */
    private fun processExits(context: Context): JSONObject {
        val result = JSONObject().put("supported", Build.VERSION.SDK_INT >= 30)
        if (Build.VERSION.SDK_INT < 30) return result
        return runCatching {
            val manager = context.getSystemService(android.app.ActivityManager::class.java)
            val history = org.json.JSONArray()
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 5).forEach { exit ->
                history.put(
                    JSONObject()
                        .put("timestamp", exit.timestamp)
                        .put("processName", exit.processName)
                        .put("reason", exit.reason)
                        .put(
                            "reasonLabel",
                            when (exit.reason) {
                                android.app.ApplicationExitInfo.REASON_CRASH -> "Java 未捕获异常"
                                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃"
                                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "系统低内存回收"
                                android.app.ApplicationExitInfo.REASON_ANR -> "应用无响应"
                                android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "应用主动退出"
                                android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "用户或系统请求停止"
                                else -> "其他系统退出原因（${exit.reason}）"
                            },
                        )
                        .put("status", exit.status)
                        .put("description", exit.description)
                        .put("importance", exit.importance)
                        .put("pssKb", exit.pss)
                        .put("rssKb", exit.rss)
                )
            }
            result.put("history", history)
        }
            .getOrElse { result.put("error", it.toString()) }
    }

    private fun diagnostics(context: Context): JSONObject {
        val window = app.luoxianlv.hot.contract.PlaybackBridge.current()?.query("state")
        val d = PlaybackConnection.instance?.diagnostics()
        val layout = ConfigStore.load(context)
        val prefs = Kv.of(context, "ratio_config_v3").all
        val modes = JSONObject()
        layout.modes.forEach { (mode, point) ->
            modes.put(mode.name, "%.4f,%.4f".format(point[0], point[1]))
        }
        return JSONObject()
            .put("serviceRunning", d != null)
            .put("floatingEnabled", window?.getBoolean("floatingEnabled"))
            .put("floatingRequested", window?.getBoolean("floatingRequested"))
            .put("floatingVisible", window?.getBoolean("floatingVisible"))
            .put("floatingError", window?.getString("floatingError"))
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
